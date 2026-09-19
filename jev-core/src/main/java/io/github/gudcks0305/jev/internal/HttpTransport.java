package io.github.gudcks0305.jev.internal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.spi.JevTransport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous JSON-over-HTTP transport with a deadline covering every attempt and backoff.
 *
 * <p>Only explicit retryable HTTP status responses are retried. Connection failures and
 * ambiguous timeouts are deliberately not retried because a remote service may already have
 * accepted and billed the request.</p>
 */
public final class HttpTransport implements JevTransport {
    private static final Duration MAX_TIMEOUT = Duration.ofDays(1);
    private static final long RETRY_BASE_NANOS = Duration.ofMillis(100).toNanos();
    private static final long RETRY_CAP_NANOS = Duration.ofSeconds(2).toNanos();
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 529, 502, 503, 504);

    private final HttpClient client;
    private final Duration timeout;
    private final int maxRetries;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private final Set<Call> activeCalls = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public HttpTransport(HttpClient client, Duration timeout, int maxRetries) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = validateTimeout(timeout);
        if (maxRetries < 0 || maxRetries > 10) {
            throw new IllegalArgumentException("maxRetries must be between 0 and 10");
        }
        this.maxRetries = maxRetries;
        this.objectMapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jev-http-transport");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public CompletableFuture<JsonNode> post(
            URI uri, Map<String, String> headers, JsonNode body) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");

        if (closed.get()) {
            return failedFuture(closedException());
        }

        final byte[] requestBody;
        try {
            requestBody = objectMapper.writeValueAsBytes(body);
        } catch (RuntimeException exception) {
            return failedFuture(new JevException(
                    JevException.Kind.PROTOCOL, "Unable to encode JSON request"));
        } catch (Exception exception) {
            return failedFuture(new JevException(
                    JevException.Kind.PROTOCOL, "Unable to encode JSON request"));
        }

        Call call = new Call(uri, Map.copyOf(headers), requestBody);
        activeCalls.add(call);
        if (closed.get()) {
            call.closeFromTransport();
        } else {
            call.start();
        }
        return call.result;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Call call : activeCalls) {
            call.closeFromTransport();
        }
        scheduler.shutdownNow();
    }

    private static Duration validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must be positive and at most 1 day");
        }
        return timeout;
    }

    private static JevException closedException() {
        return new JevException(JevException.Kind.CLOSED, "HTTP transport is closed");
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }

    private final class Call {
        private final URI uri;
        private final Map<String, String> headers;
        private final byte[] requestBody;
        private final long deadlineNanos;
        private final Object taskLock = new Object();
        private final CompletableFuture<JsonNode> result = new CompletableFuture<>();

        private CompletableFuture<HttpResponse<byte[]>> inFlight;
        private ScheduledFuture<?> retryTask;
        private ScheduledFuture<?> deadlineTask;

        private Call(URI uri, Map<String, String> headers, byte[] requestBody) {
            this.uri = uri;
            this.headers = headers;
            this.requestBody = requestBody;
            this.deadlineNanos = System.nanoTime() + timeout.toNanos();
            result.whenComplete((ignored, failure) -> {
                cancelTasks();
                activeCalls.remove(this);
            });
        }

        private void start() {
            try {
                ScheduledFuture<?> deadline = scheduler.schedule(
                        this::timeOut, timeout.toNanos(), TimeUnit.NANOSECONDS);
                synchronized (taskLock) {
                    if (result.isDone()) {
                        deadline.cancel(false);
                        return;
                    }
                    deadlineTask = deadline;
                }
                attempt(0);
            } catch (RejectedExecutionException exception) {
                closeFromTransport();
            }
        }

        private void attempt(int retriesUsed) {
            if (result.isDone()) {
                return;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                timeOut();
                return;
            }

            final HttpRequest request;
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofNanos(remainingNanos))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody));
                headers.forEach(builder::header);
                request = builder.build();
            } catch (RuntimeException exception) {
                result.completeExceptionally(new JevException(
                        JevException.Kind.VALIDATION, "Invalid HTTP request"));
                return;
            }

            final CompletableFuture<HttpResponse<byte[]>> requestFuture;
            try {
                requestFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (RuntimeException exception) {
                result.completeExceptionally(new JevException(
                        JevException.Kind.CONNECTION, "HTTP connection failed"));
                return;
            }

            synchronized (taskLock) {
                if (result.isDone()) {
                    requestFuture.cancel(true);
                    return;
                }
                inFlight = requestFuture;
            }
            requestFuture.whenComplete((response, failure) -> {
                synchronized (taskLock) {
                    if (inFlight == requestFuture) {
                        inFlight = null;
                    }
                }
                if (result.isDone()) {
                    return;
                }
                if (failure != null) {
                    handleFailure(failure);
                } else {
                    handleResponse(response, retriesUsed);
                }
            });
        }

        private void handleFailure(Throwable failure) {
            Throwable cause = unwrap(failure);
            if (cause instanceof CancellationException && result.isDone()) {
                return;
            }
            JevException.Kind kind = cause instanceof HttpTimeoutException
                    ? JevException.Kind.TIMEOUT
                    : JevException.Kind.CONNECTION;
            String message = kind == JevException.Kind.TIMEOUT
                    ? "HTTP request timed out"
                    : "HTTP connection failed";
            // Do not retain arbitrary client exceptions: their messages can contain request data.
            result.completeExceptionally(new JevException(kind, message));
        }

        private void handleResponse(HttpResponse<byte[]> response, int retriesUsed) {
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                parseSuccess(response.body());
                return;
            }
            if (RETRYABLE_STATUSES.contains(status) && retriesUsed < maxRetries) {
                scheduleRetry(response, retriesUsed);
                return;
            }
            result.completeExceptionally(httpStatusException(status));
        }

        private void parseSuccess(byte[] responseBody) {
            try {
                JsonNode json = objectMapper.readTree(responseBody);
                if (json == null) {
                    throw new IllegalArgumentException("empty JSON");
                }
                result.complete(json);
            } catch (Exception exception) {
                result.completeExceptionally(new JevException(
                        JevException.Kind.PROTOCOL, "HTTP response was not valid JSON"));
            }
        }

        private void scheduleRetry(HttpResponse<byte[]> response, int retriesUsed) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                timeOut();
                return;
            }

            Long retryAfterNanos = parseRetryAfterNanos(
                    response.headers().firstValue("Retry-After").orElse(null));
            long delayNanos = retryAfterNanos != null
                    ? retryAfterNanos
                    : jitteredBackoffNanos(retriesUsed);

            // Retrying before Retry-After would violate the server instruction. The total call
            // deadline remains authoritative, so let the deadline task finish the call instead.
            if (delayNanos >= remainingNanos) {
                return;
            }

            try {
                ScheduledFuture<?> retry = scheduler.schedule(
                        () -> attempt(retriesUsed + 1), delayNanos, TimeUnit.NANOSECONDS);
                synchronized (taskLock) {
                    if (result.isDone()) {
                        retry.cancel(false);
                    } else {
                        retryTask = retry;
                    }
                }
            } catch (RejectedExecutionException exception) {
                if (closed.get()) {
                    closeFromTransport();
                }
            }
        }

        private void timeOut() {
            result.completeExceptionally(new JevException(
                    JevException.Kind.TIMEOUT, "HTTP request timed out"));
        }

        private void closeFromTransport() {
            result.completeExceptionally(closedException());
            cancelTasks();
        }

        private void cancelTasks() {
            CompletableFuture<HttpResponse<byte[]>> request;
            ScheduledFuture<?> retry;
            ScheduledFuture<?> deadline;
            synchronized (taskLock) {
                request = inFlight;
                retry = retryTask;
                deadline = deadlineTask;
                inFlight = null;
                retryTask = null;
                deadlineTask = null;
            }
            if (request != null) {
                request.cancel(true);
            }
            if (retry != null) {
                retry.cancel(false);
            }
            if (deadline != null) {
                deadline.cancel(false);
            }
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static JevException httpStatusException(int status) {
        JevException.Kind kind;
        if (status == 401 || status == 403) {
            kind = JevException.Kind.AUTHENTICATION;
        } else if (status == 400 || status == 422) {
            kind = JevException.Kind.VALIDATION;
        } else if (status == 429) {
            kind = JevException.Kind.RATE_LIMIT;
        } else if (status >= 500 && status <= 599) {
            kind = JevException.Kind.SERVER;
        } else {
            kind = JevException.Kind.HTTP;
        }
        return new JevException(kind, "HTTP request failed with status " + status, status);
    }

    private static Long parseRetryAfterNanos(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        String value = headerValue.trim();
        if (value.matches("[0-9]+")) {
            try {
                long seconds = Long.parseLong(value);
                if (seconds > TimeUnit.NANOSECONDS.toSeconds(Long.MAX_VALUE)) {
                    return Long.MAX_VALUE;
                }
                return TimeUnit.SECONDS.toNanos(seconds);
            } catch (NumberFormatException exception) {
                return Long.MAX_VALUE;
            }
        }
        try {
            Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
            Duration delay = Duration.between(Instant.now(), retryAt);
            if (delay.isNegative() || delay.isZero()) {
                return 0L;
            }
            try {
                return delay.toNanos();
            } catch (ArithmeticException exception) {
                return Long.MAX_VALUE;
            }
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static long jitteredBackoffNanos(int retriesUsed) {
        long exponential = RETRY_BASE_NANOS << Math.min(retriesUsed, 5);
        long bound = Math.min(exponential, RETRY_CAP_NANOS);
        return ThreadLocalRandom.current().nextLong(bound + 1);
    }
}
