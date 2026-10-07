package io.github.gudcks0305.jev.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.schema.JevBoolean;
import io.github.gudcks0305.jev.schema.JevLabels;
import io.github.gudcks0305.jev.schema.JevProbability;
import io.github.gudcks0305.jev.spi.JevTransport;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class OpenAiJevClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final NoulQuestion damaged = NoulQuestion.of("damaged", "Is the item damaged?");

    @Test
    void sendsExactHttpContractAndParsesOfficialExample() throws Exception {
        AtomicReference<JsonNode> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> gatewayHeader = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/proxy/decisions", exchange -> {
            method.set(exchange.getRequestMethod());
            query.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            gatewayHeader.set(exchange.getRequestHeaders().getFirst("ai-model-id"));
            body.set(JSON.readTree(exchange.getRequestBody()));
            byte[] bytes = officialResponse().toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (var client = OpenAiJevClient.builder().apiKey("test-key")
                .endpoint(endpoint(server, "/proxy/decisions?tenant=acme")).build()) {
            var result = client.evaluate("The screen is broken.", damaged);
            assertEquals("POST", method.get());
            assertEquals("tenant=acme", query.get());
            assertEquals("Bearer test-key", authorization.get());
            assertNull(gatewayHeader.get());
            assertEquals(JSON.readTree("""
                    {"model":"gpt-6-luna","input":"The screen is broken.","questions":[
                      {"name":"damaged","instructions":"Is the item damaged?","type":"predicate"}]}
                    """), body.get());
            assertEquals(.95, result.answer(damaged).probability());
            assertEquals(42, result.usage().inputTokens().orElseThrow());
            assertEquals(0, result.usage().outputTokens().orElseThrow());
            assertEquals(0, result.rawResponse().at("/usage/input_tokens_details/cached_tokens").intValue());
            assertEquals(42, result.rawResponse().at("/usage/total_tokens").intValue());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolvesDefaultAndBaseUrlAndKeepsInjectedTransportCallerOwned() throws Exception {
        Stub stub = new Stub(CompletableFuture.completedFuture(officialResponse()));
        try (var client = OpenAiJevClient.builder().apiKey("test-key").transport(stub).build()) {
            client.evaluate("text", damaged);
            assertEquals(URI.create("https://api.openai.com/v1/decisions"), stub.uri);
            assertEquals("gpt-6-luna", stub.body.path("model").textValue());
        }
        assertFalse(stub.closed);
        try (var client = OpenAiJevClient.builder().apiKey("test-key").model("custom-model")
                .baseUrl(URI.create("https://proxy.example/prefix/")).transport(stub).build()) {
            client.evaluate("text", damaged);
            assertEquals(URI.create("https://proxy.example/prefix/v1/decisions"), stub.uri);
            assertEquals("custom-model", stub.body.path("model").textValue());
        }
        assertThrows(IllegalArgumentException.class, () -> OpenAiJevClient.builder().apiKey("test-key")
                .baseUrl(URI.create("https://proxy.example"))
                .endpoint(URI.create("https://proxy.example/exact")).build());
    }

    @Test
    void rejectsStructuredStateAndMultiLabelRecordBeforeTransport() {
        Stub stub = new Stub(new CompletableFuture<>());
        try (var client = OpenAiJevClient.builder().apiKey("test-key").transport(stub).build()) {
            assertThrows(IllegalArgumentException.class, () -> client.evaluate(Map.of("text", "broken"), damaged));
            assertThrows(IllegalArgumentException.class, () -> client.evaluate("text", Labels.class));
            assertEquals(0, stub.calls);
        }
    }

    enum Label { DAMAGE, REFUND }
    record Labels(@JevLabels(value = "Which apply?", threshold = .8) List<Label> labels) {}
    record Damage(@JevBoolean(value = "Damaged?", threshold = .9) boolean damaged,
                  @JevProbability("Damaged?") double probability) {}

    @Test
    void mapsRecordWithExplicitThresholdFromOneRequest() throws Exception {
        JsonNode response = JSON.readTree("""
                {"model":"gpt-6-luna","answers":[
                  {"name":"damaged","type":"predicate","probability":0.95},
                  {"name":"probability","type":"predicate","probability":0.95}],
                 "usage":{"input_tokens":42,"output_tokens":0}}
                """);
        Stub stub = new Stub(CompletableFuture.completedFuture(response));
        try (var client = OpenAiJevClient.builder().apiKey("test-key").transport(stub).build()) {
            assertEquals(new Damage(true, .95), client.evaluate("broken", Damage.class).value());
            assertEquals(1, stub.calls);
            assertEquals(2, stub.body.path("questions").size());
        }
    }

    @Test
    void cancellationAndCloseReachPendingRequests() {
        Stub cancelled = new Stub(new CompletableFuture<>());
        try (var client = OpenAiJevClient.builder().apiKey("test-key").transport(cancelled).build()) {
            var future = client.evaluateAsync("text", damaged);
            assertTrue(future.cancel(true));
            assertTrue(cancelled.pending.isCancelled());
        }
        Stub closed = new Stub(new CompletableFuture<>());
        var client = OpenAiJevClient.builder().apiKey("test-key").transport(closed).build();
        var future = client.evaluateAsync("text", damaged);
        client.close();
        var error = assertThrows(CompletionException.class, future::join);
        assertEquals(JevException.Kind.CLOSED, ((JevException) error.getCause()).kind());
        assertTrue(closed.pending.isCancelled());
        assertFalse(closed.closed);
        assertEquals(JevException.Kind.CLOSED,
                assertThrows(JevException.class, () -> client.evaluate("text", damaged)).kind());
    }

    @Test
    void retriesExplicitServiceFailureThenSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/v1/decisions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int status = attempts.incrementAndGet() == 1 ? 503 : 200;
            byte[] response = (status == 200 ? officialResponse().toString() : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After", "0");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var client = OpenAiJevClient.builder().apiKey("test-key")
                .baseUrl(endpoint(server, "")).maxRetries(1).build()) {
            assertEquals(.95, client.evaluate("text", damaged).answer(damaged).probability());
            assertEquals(2, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 401, 400})
    void refusalAndNonRetryableHttpErrorsFailWithoutRetryOrPayloadLeak(int status) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/v1/decisions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            attempts.incrementAndGet();
            byte[] response = (status == 200 ? """
                    {"model":"gpt-6-luna","answers":[{"type":"refusal","name":"damaged"}],
                     "usage":{"input_tokens":1,"output_tokens":0},"secret":"sensitive-content"}
                    """ : "{\"error\":{\"message\":\"sensitive-content\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var client = OpenAiJevClient.builder().apiKey("test-key").baseUrl(endpoint(server, "")).build()) {
            JevException error = assertThrows(JevException.class, () -> client.evaluate("text", damaged));
            assertEquals(switch (status) {
                case 200 -> JevException.Kind.REFUSAL;
                case 401 -> JevException.Kind.AUTHENTICATION;
                default -> JevException.Kind.VALIDATION;
            }, error.kind());
            assertFalse(error.getMessage().contains("sensitive-content"));
            assertEquals(1, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void totalDeadlineIncludesRetryDelay() throws Exception {
        HttpServer server = server();
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/v1/decisions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            attempts.incrementAndGet();
            exchange.getResponseHeaders().set("Retry-After", "60");
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try (var client = OpenAiJevClient.builder().apiKey("test-key").baseUrl(endpoint(server, ""))
                .timeout(Duration.ofSeconds(1)).maxRetries(2).build()) {
            assertEquals(JevException.Kind.TIMEOUT,
                    assertThrows(JevException.class, () -> client.evaluate("text", damaged)).kind());
            assertEquals(1, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server() throws Exception {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }
    private static URI endpoint(HttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }
    private static JsonNode officialResponse() throws java.io.IOException {
        try (var stream = OpenAiJevClientTest.class.getResourceAsStream("/openai-decisions/predicate.json")) {
            return JSON.readTree(stream);
        }
    }
    private static final class Stub implements JevTransport {
        final CompletableFuture<JsonNode> pending;
        URI uri;
        JsonNode body;
        boolean closed;
        int calls;
        Stub(CompletableFuture<JsonNode> pending) { this.pending = pending; }
        @Override public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
            this.uri = uri;
            this.body = body;
            calls++;
            return pending;
        }
        @Override public void close() { closed = true; }
    }
}
