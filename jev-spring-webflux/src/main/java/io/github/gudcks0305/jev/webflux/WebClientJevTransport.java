package io.github.gudcks0305.jev.webflux;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.spi.JevTransport;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-blocking Jev transport backed by a caller-owned {@link WebClient}.
 *
 * <p>The timeout is one total budget for attempts and retry delays. Only explicit retryable
 * HTTP responses are retried. Connection failures and ambiguous timeouts are not retried because
 * a remote service may already have accepted and billed the request.</p>
 *
 * <p>Closing this transport cancels its calls but does not dispose the supplied client or its
 * shared connector resources.</p>
 */
public final class WebClientJevTransport implements JevTransport {
    private static final Duration MAX_TIMEOUT = Duration.ofDays(1);
    private static final Duration MAX_RETRY_DELAY = MAX_TIMEOUT.plusNanos(1);
    private static final long RETRY_BASE_NANOS = Duration.ofMillis(100).toNanos();
    private static final long RETRY_CAP_NANOS = Duration.ofSeconds(2).toNanos();
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 529, 502, 503, 504);

    private final WebClient client;
    private final Duration timeout;
    private final int maxRetries;
    private final ObjectMapper objectMapper;
    private final Set<Call> activeCalls = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public WebClientJevTransport(WebClient client, Duration timeout, int maxRetries) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = validateTimeout(timeout);
        if (maxRetries < 0 || maxRetries > 10) {
            throw new IllegalArgumentException("maxRetries must be between 0 and 10");
        }
        this.maxRetries = maxRetries;
        this.objectMapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
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

        final String requestBody;
        final Map<String, String> requestHeaders;
        try {
            requestBody = objectMapper.writeValueAsString(body);
            requestHeaders = Map.copyOf(headers);
        } catch (RuntimeException exception) {
            return failedFuture(new JevException(
                    JevException.Kind.VALIDATION, "Invalid HTTP request"));
        } catch (Exception exception) {
            return failedFuture(new JevException(
                    JevException.Kind.PROTOCOL, "Unable to encode JSON request"));
        }

        Call call = new Call(uri, requestHeaders, requestBody);
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
    }

    private Mono<JsonNode> attempt(URI uri, Map<String, String> headers, String body, int retriesUsed) {
        return Mono.defer(() -> {
            try {
                return client.post()
                        .uri(uri)
                        .headers(httpHeaders -> headers.forEach(httpHeaders::set))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .exchangeToMono(response -> {
                            int status = response.statusCode().value();
                            if (status >= 200 && status < 300) {
                                return response.bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .flatMap(this::parseSuccess);
                            }

                            Mono<Void> consumeBody = response.releaseBody()
                                    .onErrorResume(ignored -> Mono.empty());
                            if (RETRYABLE_STATUSES.contains(status)
                                    && retriesUsed < maxRetries) {
                                Duration delay = retryDelay(
                                        response.headers().asHttpHeaders(), retriesUsed);
                                return consumeBody.then(Mono.delay(delay))
                                        .then(attempt(uri, headers, body, retriesUsed + 1));
                            }
                            return consumeBody.then(Mono.error(httpStatusException(status)));
                        });
            } catch (RuntimeException exception) {
                return Mono.error(new JevException(
                        JevException.Kind.VALIDATION, "Invalid HTTP request"));
            }
        });
    }

    private Mono<JsonNode> parseSuccess(String responseBody) {
        try {
            JsonNode json = objectMapper.readTree(responseBody);
            if (json == null) {
                throw new IllegalArgumentException("empty JSON");
            }
            return Mono.just(json);
        } catch (Exception exception) {
            return Mono.error(new JevException(
                    JevException.Kind.PROTOCOL, "HTTP response was not valid JSON"));
        }
    }

    private static Throwable sanitizeFailure(Throwable failure) {
        if (failure instanceof JevException exception) {
            return sanitizedJevException(exception);
        }
        JevException.Kind kind = isTimeout(failure)
                ? JevException.Kind.TIMEOUT
                : JevException.Kind.CONNECTION;
        String message = kind == JevException.Kind.TIMEOUT
                ? "HTTP request timed out"
                : "HTTP connection failed";
        return new JevException(kind, message);
    }

    private static JevException sanitizedJevException(JevException exception) {
        if (exception.statusCode() != 0) {
            return httpStatusException(exception.statusCode());
        }
        return switch (exception.kind()) {
            case AUTHENTICATION -> new JevException(
                    JevException.Kind.AUTHENTICATION, "HTTP authentication failed");
            case VALIDATION -> new JevException(
                    JevException.Kind.VALIDATION, "Invalid HTTP request");
            case RATE_LIMIT -> new JevException(
                    JevException.Kind.RATE_LIMIT, "HTTP request was rate limited");
            case SERVER -> new JevException(
                    JevException.Kind.SERVER, "HTTP server failed");
            case HTTP -> new JevException(
                    JevException.Kind.HTTP, "HTTP request failed");
            case CONNECTION -> new JevException(
                    JevException.Kind.CONNECTION, "HTTP connection failed");
            case TIMEOUT -> new JevException(
                    JevException.Kind.TIMEOUT, "HTTP request timed out");
            case PROTOCOL -> new JevException(
                    JevException.Kind.PROTOCOL, "HTTP response was not valid JSON");
            case CLOSED -> closedException();
        };
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException || current instanceof HttpTimeoutException
                    || current.getClass().getSimpleName().contains("TimeoutException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Duration validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must be positive and at most 1 day");
        }
        return timeout;
    }

    private static Duration retryDelay(HttpHeaders headers, int retriesUsed) {
        Duration retryAfter = parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER));
        return retryAfter != null ? retryAfter : jitteredBackoff(retriesUsed);
    }

    private static Duration parseRetryAfter(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        String value = headerValue.trim();
        if (value.matches("[0-9]+")) {
            try {
                long seconds = Long.parseLong(value);
                return seconds > MAX_TIMEOUT.toSeconds()
                        ? MAX_RETRY_DELAY
                        : Duration.ofSeconds(seconds);
            } catch (NumberFormatException | ArithmeticException exception) {
                return MAX_RETRY_DELAY;
            }
        }
        try {
            Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
            Duration delay = Duration.between(Instant.now(), retryAt);
            if (delay.isNegative() || delay.isZero()) {
                return Duration.ZERO;
            }
            return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
        } catch (DateTimeParseException | ArithmeticException exception) {
            return null;
        }
    }

    private static Duration jitteredBackoff(int retriesUsed) {
        long exponential = RETRY_BASE_NANOS << Math.min(retriesUsed, 5);
        long bound = Math.min(exponential, RETRY_CAP_NANOS);
        return Duration.ofNanos(ThreadLocalRandom.current().nextLong(bound + 1));
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
        private final String body;
        private final CallFuture result = new CallFuture();

        private Call(URI uri, Map<String, String> headers, String body) {
            this.uri = uri;
            this.headers = headers;
            this.body = body;
            result.whenComplete((ignored, failure) -> activeCalls.remove(this));
        }

        private void start() {
            if (result.isDone()) {
                return;
            }
            Mono<JsonNode> call = attempt(uri, headers, body, 0)
                    .onErrorMap(WebClientJevTransport::sanitizeFailure)
                    .timeout(timeout, Mono.error(new JevException(
                            JevException.Kind.TIMEOUT, "HTTP request timed out")));
            result.subscribeTo(call);
        }

        private void closeFromTransport() {
            result.failAndDispose(closedException());
        }
    }

    private static final class CallFuture extends CompletableFuture<JsonNode> {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Disposable subscription;

        private synchronized void subscribeTo(Mono<JsonNode> call) {
            if (isDone()) {
                return;
            }
            Disposable current = call.subscribe(
                    this::complete,
                    this::completeExceptionally);
            subscription = current;
            if (isDone()) {
                current.dispose();
            }
        }

        private synchronized void failAndDispose(Throwable failure) {
            completeExceptionally(failure);
            disposeSubscription();
        }

        private void disposeSubscription() {
            Disposable current = subscription;
            if (current != null) {
                current.dispose();
            }
        }

        @Override
        public synchronized boolean cancel(boolean mayInterruptIfRunning) {
            boolean didCancel = super.cancel(mayInterruptIfRunning);
            if (didCancel && cancelled.compareAndSet(false, true)) {
                disposeSubscription();
            }
            return didCancel;
        }
    }
}
