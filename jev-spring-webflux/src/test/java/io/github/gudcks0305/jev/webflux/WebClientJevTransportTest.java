package io.github.gudcks0305.jev.webflux;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gudcks0305.jev.JevException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebClientJevTransportTest {
    private static final URI URI = java.net.URI.create("https://jev.invalid/evaluate");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<WebClientJevTransport> transports = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        transports.forEach(WebClientJevTransport::close);
    }

    @Test
    void usesWebClientFiltersAndParsesJson() throws Exception {
        AtomicInteger filterCalls = new AtomicInteger();
        AtomicInteger exchanges = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            exchanges.incrementAndGet();
            assertEquals("seen", request.headers().getFirst("X-From-Filter"));
            assertEquals("secret", request.headers().getFirst("Authorization"));
            assertEquals("application/json", request.headers().getFirst(HttpHeaders.CONTENT_TYPE));
            return Mono.just(response(200, "{\"decision\":\"yes\"}"));
        };
        WebClient client = WebClient.builder()
                .exchangeFunction(exchange)
                .filter((request, next) -> {
                    filterCalls.incrementAndGet();
                    ClientRequest filtered = ClientRequest.from(request)
                            .header("X-From-Filter", "seen")
                            .build();
                    return next.exchange(filtered);
                })
                .build();
        WebClientJevTransport transport = transport(client, Duration.ofSeconds(2), 0);

        JsonNode result = transport.post(
                URI, Map.of("Authorization", "secret"), JSON.createObjectNode()).get();

        assertEquals("yes", result.get("decision").asText());
        assertEquals(1, filterCalls.get());
        assertEquals(1, exchanges.get());
    }

    @Test
    void retriesExplicitRetryableStatusOnly() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        WebClient client = client(request -> {
            if (exchanges.incrementAndGet() == 1) {
                return Mono.just(response(429, "rate limited", "0"));
            }
            return Mono.just(response(200, "{\"retried\":true}"));
        });
        WebClientJevTransport transport = transport(client, Duration.ofSeconds(2), 1);

        JsonNode result = transport.post(URI, Map.of(), JSON.createObjectNode()).get();

        assertTrue(result.get("retried").asBoolean());
        assertEquals(2, exchanges.get());
    }

    @Test
    void authenticationFailureDoesNotRetryOrExposeResponse() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        WebClientJevTransport transport = transport(client(request -> {
            exchanges.incrementAndGet();
            return Mono.just(response(401, "secret-token-and-state"));
        }), Duration.ofSeconds(2), 3);

        JevException failure = failure(transport.post(
                URI, Map.of("Authorization", "secret"), JSON.createObjectNode()));

        assertEquals(JevException.Kind.AUTHENTICATION, failure.kind());
        assertEquals(401, failure.statusCode());
        assertFalse(failure.getMessage().contains("secret-token-and-state"));
        assertFalse(failure.getMessage().contains("secret"));
        assertNull(failure.getCause());
        assertEquals(1, exchanges.get());
    }

    @Test
    void authenticationStatusSurvivesErrorBodyDecodeFailure() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        WebClientJevTransport transport = transport(client(request -> {
            exchanges.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatusCode.valueOf(401))
                    .body(Flux.<DataBuffer>error(
                            new IllegalStateException("secret-body-decoder-failure")))
                    .build());
        }), Duration.ofSeconds(2), 3);

        JevException failure = failure(transport.post(
                URI, Map.of(), JSON.createObjectNode()));

        assertEquals(JevException.Kind.AUTHENTICATION, failure.kind());
        assertEquals(401, failure.statusCode());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("secret-body-decoder-failure"));
        assertEquals(1, exchanges.get());
    }

    @Test
    void oversizedAuthenticationBodyPreservesKnownStatus() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        String oversizedBody = "sensitive".repeat(40_000);
        WebClientJevTransport transport = transport(client(request -> {
            exchanges.incrementAndGet();
            return Mono.just(response(401, oversizedBody));
        }), Duration.ofSeconds(2), 3);

        JevException failure = failure(transport.post(
                URI, Map.of(), JSON.createObjectNode()));

        assertEquals(JevException.Kind.AUTHENTICATION, failure.kind());
        assertEquals(401, failure.statusCode());
        assertFalse(failure.getMessage().contains("sensitive"));
        assertEquals(1, exchanges.get());
    }

    @Test
    void oversizedRateLimitBodyIsReleasedBeforeRetry() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        String oversizedBody = "rate-limit-detail".repeat(20_000);
        WebClientJevTransport transport = transport(client(request -> {
            if (exchanges.incrementAndGet() == 1) {
                return Mono.just(response(429, oversizedBody, "0"));
            }
            return Mono.just(response(200, "{\"retried\":true}"));
        }), Duration.ofSeconds(2), 1);

        JsonNode result = transport.post(
                URI, Map.of(), JSON.createObjectNode()).get();

        assertTrue(result.get("retried").asBoolean());
        assertEquals(2, exchanges.get());
    }

    @Test
    void wholeCallDeadlineIncludesRetryDelay() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        WebClientJevTransport transport = transport(client(request -> {
            exchanges.incrementAndGet();
            return Mono.just(response(429, "rate limited", "10"));
        }), Duration.ofMillis(100), 3);

        JevException failure = failure(transport.post(
                URI, Map.of(), JSON.createObjectNode()));

        assertEquals(JevException.Kind.TIMEOUT, failure.kind());
        assertEquals(1, exchanges.get());
    }

    @Test
    void hugeRetryAfterRemainsBoundedByCallDeadline() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        WebClientJevTransport transport = transport(client(request -> {
            exchanges.incrementAndGet();
            return Mono.just(response(
                    429, "rate limited", Long.toString(Long.MAX_VALUE)));
        }), Duration.ofMillis(100), 1);

        JevException failure = failure(transport.post(
                URI, Map.of(), JSON.createObjectNode()));

        assertEquals(JevException.Kind.TIMEOUT, failure.kind());
        assertEquals(1, exchanges.get());
    }

    @Test
    void deadlineCancelsActiveExchange() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        WebClientJevTransport transport = transport(client(request ->
                Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true))),
                Duration.ofMillis(100), 3);

        JevException failure = failure(transport.post(
                URI, Map.of(), JSON.createObjectNode()));

        assertEquals(JevException.Kind.TIMEOUT, failure.kind());
        assertTrue(cancelled.get());
    }

    @Test
    void callerCancellationCancelsSubscriptionAndRetryTimer() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        WebClientJevTransport transport = transport(client(request -> {
            exchanges.incrementAndGet();
            return Mono.just(response(503, "retry later", "1"));
        }), Duration.ofSeconds(5), 2);

        CompletableFuture<JsonNode> call = transport.post(
                URI, Map.of(), JSON.createObjectNode());
        assertTrue(call.cancel(true));
        assertTrue(call.isCancelled());
        assertThrows(CancellationException.class, call::join);

        TimeUnit.MILLISECONDS.sleep(1_100);
        assertEquals(1, exchanges.get());
    }

    @Test
    void closeCancelsActiveCallRejectsNewCallsAndLeavesClientUsable() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger exchanges = new AtomicInteger();
        WebClient client = client(request -> {
            int call = exchanges.incrementAndGet();
            if (call == 1) {
                return Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true));
            }
            return Mono.just(response(200, "{}"));
        });
        WebClientJevTransport transport = transport(client, Duration.ofSeconds(5), 2);
        CompletableFuture<JsonNode> active = transport.post(
                URI, Map.of(), JSON.createObjectNode());

        transport.close();

        assertEquals(JevException.Kind.CLOSED, failure(active).kind());
        assertTrue(cancelled.get());
        assertEquals(JevException.Kind.CLOSED, failure(transport.post(
                URI, Map.of(), JSON.createObjectNode())).kind());
        client.get().uri(URI).exchangeToMono(response -> Mono.just(response.statusCode().value()))
                .toFuture().get();
        assertEquals(2, exchanges.get());
    }

    @Test
    void successfulResponseMustBeStrictJson() throws Exception {
        WebClientJevTransport transport = transport(client(request ->
                Mono.just(response(200, "{\"a\":1} trailing"))), Duration.ofSeconds(2), 0);

        JevException failure = failure(transport.post(
                URI, Map.of(), JSON.createObjectNode()));

        assertEquals(JevException.Kind.PROTOCOL, failure.kind());
        assertNull(failure.getCause());
    }

    @Test
    void honorsRfc1123RetryAfterDate() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        String elapsedRetryAfter = ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .minusSeconds(1)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        WebClientJevTransport transport = transport(client(request -> {
            if (exchanges.incrementAndGet() == 1) {
                return Mono.just(response(529, "overloaded", elapsedRetryAfter));
            }
            return Mono.just(response(200, "{}"));
        }), Duration.ofSeconds(2), 1);

        transport.post(URI, Map.of(), JSON.createObjectNode()).get();

        assertEquals(2, exchanges.get());
    }

    @Test
    void callerInterruptionDoesNotAffectAsyncCall() {
        WebClientJevTransport transport = transport(client(request ->
                Mono.just(response(200, "{}"))), Duration.ofSeconds(2), 0);

        Thread.currentThread().interrupt();
        try {
            JsonNode result = transport.post(
                    URI, Map.of(), JSON.createObjectNode()).join();
            assertTrue(result.isObject());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void connectionFailureIsSanitizedAndNeverRetried() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        WebClientJevTransport transport = transport(client(request -> {
            exchanges.incrementAndGet();
            return Mono.error(new IllegalStateException("secret-key-and-body"));
        }), Duration.ofSeconds(2), 3);

        JevException failure = failure(transport.post(
                URI, Map.of(), JSON.createObjectNode()));

        assertEquals(JevException.Kind.CONNECTION, failure.kind());
        assertFalse(failure.getMessage().contains("secret-key-and-body"));
        assertNull(failure.getCause());
        assertEquals(1, exchanges.get());
    }

    @Test
    void validatesConstructorBounds() {
        WebClient client = client(request -> Mono.just(response(200, "{}")));

        assertThrows(IllegalArgumentException.class,
                () -> new WebClientJevTransport(client, Duration.ZERO, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new WebClientJevTransport(client, Duration.ofDays(1).plusNanos(1), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new WebClientJevTransport(client, Duration.ofSeconds(1), -1));
        assertThrows(IllegalArgumentException.class,
                () -> new WebClientJevTransport(client, Duration.ofSeconds(1), 11));
    }

    private WebClientJevTransport transport(WebClient client, Duration timeout, int retries) {
        WebClientJevTransport transport = new WebClientJevTransport(client, timeout, retries);
        transports.add(transport);
        return transport;
    }

    private static WebClient client(ExchangeFunction exchangeFunction) {
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    private static ClientResponse response(int status, String body) {
        return ClientResponse.create(HttpStatusCode.valueOf(status)).body(body).build();
    }

    private static ClientResponse response(int status, String body, String retryAfter) {
        return ClientResponse.create(HttpStatusCode.valueOf(status))
                .header(HttpHeaders.RETRY_AFTER, retryAfter)
                .body(body)
                .build();
    }

    private static JevException failure(CompletableFuture<?> future) throws Exception {
        ExecutionException execution = assertThrows(
                ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        return (JevException) execution.getCause();
    }
}
