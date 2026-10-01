package io.github.gudcks0305.jev.typesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.observation.EvaluationEvent;
import io.github.gudcks0305.jev.spi.JevTransport;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObservationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final NoulQuestion QUESTION = NoulQuestion.of("q", "Question?");
    record Ticket(@io.github.gudcks0305.jev.schema.JevBoolean(value = "Question?", threshold = 0.5) boolean yes) {}

    @Test void reportsSuccessOnlyOnceWithProviderMetadata() throws Exception {
        Stub transport = new Stub();
        List<EvaluationEvent> events = new ArrayList<>();
        try (var client = client(transport, events::add)) {
            var future = client.evaluateAsync("state", QUESTION);
            transport.pending.complete(response("\"model\":\"jev-1\",\"usage\":{\"input_tokens\":12}"));
            assertEquals("jev-1", future.join().model());
        }
        assertEquals(1, events.size());
        var event = events.get(0);
        assertEquals(EvaluationEvent.Outcome.SUCCESS, event.outcome());
        assertEquals("jev-latest", event.requestedModel());
        assertEquals("jev-1", event.returnedModel().orElseThrow());
        assertEquals(1, event.questionCount());
        assertFalse(event.elapsed().isNegative());
        assertEquals(12, event.usage().orElseThrow().inputTokens().orElseThrow());
        assertTrue(event.usage().orElseThrow().outputTokens().isEmpty());
        assertTrue(event.errorKind().isEmpty());
        assertTrue(event.statusCode().isEmpty());
    }

    @Test void missingProviderMetadataRemainsMissing() throws Exception {
        Stub transport = new Stub();
        List<EvaluationEvent> events = new ArrayList<>();
        try (var client = client(transport, events::add)) {
            transport.pending.complete(response(""));
            assertEquals("jev-latest", client.evaluate("state", QUESTION).model());
        }
        assertTrue(events.get(0).returnedModel().isEmpty());
        assertTrue(events.get(0).usage().orElseThrow().inputTokens().isEmpty());
        assertTrue(events.get(0).usage().orElseThrow().outputTokens().isEmpty());
    }

    @Test void reportsProtocolAndTransportFailuresWithoutExceptionContent() throws Exception {
        Stub protocol = new Stub();
        List<EvaluationEvent> events = new ArrayList<>();
        try (var client = client(protocol, events::add)) {
            protocol.pending.complete(JSON.readTree("{\"answers\":{}}"));
            assertEquals(JevException.Kind.PROTOCOL,
                    assertThrows(JevException.class, () -> client.evaluate("state", QUESTION)).kind());
        }
        assertEquals(EvaluationEvent.Outcome.FAILURE, events.get(0).outcome());
        assertEquals(JevException.Kind.PROTOCOL, events.get(0).errorKind().orElseThrow());
        assertTrue(events.get(0).usage().isEmpty());
        assertTrue(events.get(0).returnedModel().isEmpty());

        events.clear();
        Stub syncFailure = new Stub();
        syncFailure.synchronousFailure = new JevException(JevException.Kind.RATE_LIMIT, "secret", 429);
        try (var client = client(syncFailure, events::add)) {
            assertEquals(JevException.Kind.RATE_LIMIT,
                    assertThrows(JevException.class, () -> client.evaluate("state", QUESTION)).kind());
        }
        assertEquals(1, events.size());
        assertEquals(429, events.get(0).statusCode().orElseThrow());
        assertEquals(JevException.Kind.RATE_LIMIT, events.get(0).errorKind().orElseThrow());
        assertFalse(events.get(0).toString().contains("secret"));
    }

    @Test void observerFailureCannotChangeResultOrCancellation() throws Exception {
        Stub success = new Stub();
        success.pending.complete(response(""));
        try (var client = client(success, event -> { throw new AssertionError("observer failure"); })) {
            assertNotNull(client.evaluate("state", QUESTION));
        }
        Stub failure = new Stub();
        failure.pending.completeExceptionally(new JevException(JevException.Kind.CONNECTION, "transport failed"));
        try (var client = client(failure, event -> { throw new AssertionError("observer failure"); })) {
            assertEquals(JevException.Kind.CONNECTION,
                    assertThrows(JevException.class, () -> client.evaluate("state", QUESTION)).kind());
        }
        Stub cancelled = new Stub();
        try (var client = client(cancelled, event -> { throw new AssertionError("observer failure"); })) {
            var future = client.evaluateAsync("state", QUESTION);
            assertTrue(future.cancel(true));
            assertTrue(cancelled.pending.isCancelled());
        }
    }

    @Test void cancellationAndCloseEachReportOneLogicalCompletion() {
        Stub transport = new Stub();
        List<EvaluationEvent> events = new ArrayList<>();
        var client = client(transport, events::add);
        var cancelled = client.evaluateAsync("state", QUESTION);
        assertTrue(cancelled.cancel(true));
        assertTrue(transport.pending.isCancelled());
        assertEquals(1, events.size());
        assertEquals(EvaluationEvent.Outcome.CANCELLED, events.get(0).outcome());
        transport.pending = new CompletableFuture<>();
        var closed = client.evaluateAsync("state", QUESTION);
        client.close();
        assertEquals(JevException.Kind.CLOSED,
                ((JevException) assertThrows(CompletionException.class, closed::join).getCause()).kind());
        assertTrue(transport.pending.isCancelled());
        assertEquals(2, events.size());
        assertEquals(EvaluationEvent.Outcome.FAILURE, events.get(1).outcome());
        assertEquals(JevException.Kind.CLOSED, events.get(1).errorKind().orElseThrow());
        assertFalse(transport.closed);
        assertEquals(JevException.Kind.CLOSED,
                ((JevException) assertThrows(CompletionException.class,
                        () -> client.evaluateAsync("state", QUESTION).join()).getCause()).kind());
        assertEquals(2, events.size());
    }

    @Test void validationFailureDoesNotEmitAnEvent() throws Exception {
        Stub transport = new Stub();
        List<EvaluationEvent> events = new ArrayList<>();
        try (var client = client(transport, events::add)) {
            assertThrows(IllegalArgumentException.class, () -> client.evaluateAsync("state"));
            assertThrows(IllegalArgumentException.class, () -> client.evaluateAsync(42, QUESTION));
        }
        assertTrue(events.isEmpty());
        assertEquals(0, transport.posts);
    }

    @Test void asyncRecordMappingRetainsOneUnderlyingObservation() throws Exception {
        Stub transport = new Stub();
        List<EvaluationEvent> events = new ArrayList<>();
        try (var client = client(transport, events::add)) {
            var mapped = client.evaluateAsync("state", Ticket.class);
            transport.pending.complete(JSON.readTree("""
                    {"answers":{"yes":{"type":"noul","noul":0.8}}}
                    """));
            assertTrue(mapped.join().value().yes());
        }
        assertEquals(1, events.size());
        assertEquals(EvaluationEvent.Outcome.SUCCESS, events.get(0).outcome());
    }

    @Test void defaultTransportRetriesButEmitsOneLogicalEvent() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch observed = new CountDownLatch(1);
        List<EvaluationEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/evaluate", exchange -> {
            int request = requests.incrementAndGet();
            if (request == 1) exchange.getResponseHeaders().add("Retry-After", "0");
            byte[] body = (request == 1 ? "rate limited"
                    : "{\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.8}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(request == 1 ? 429 : 200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try (var client = TypeSafeJevClient.builder().apiKey("test-key")
                .endpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/evaluate"))
                .timeout(Duration.ofSeconds(3)).maxRetries(1)
                .observer(event -> { events.add(event); observed.countDown(); }).build()) {
            assertEquals(0.8, client.evaluate("state", QUESTION).answer(QUESTION).probability());
            assertTrue(observed.await(2, TimeUnit.SECONDS));
        } finally {
            server.stop(0);
        }
        assertEquals(2, requests.get());
        assertEquals(1, events.size());
        assertEquals(EvaluationEvent.Outcome.SUCCESS, events.get(0).outcome());
    }

    private static TypeSafeJevClient client(Stub transport,
            io.github.gudcks0305.jev.observation.EvaluationObserver observer) {
        return TypeSafeJevClient.builder().apiKey("test-key").transport(transport).observer(observer).build();
    }

    private static JsonNode response(String metadata) throws Exception {
        return JSON.readTree("{" + metadata + (metadata.isEmpty() ? "" : ",")
                + "\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.8}}}");
    }

    private static final class Stub implements JevTransport {
        CompletableFuture<JsonNode> pending = new CompletableFuture<>();
        RuntimeException synchronousFailure;
        int posts;
        boolean closed;

        @Override public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
            posts++;
            if (synchronousFailure != null) throw synchronousFailure;
            return pending;
        }
        @Override public void close() { closed = true; }
    }
}
