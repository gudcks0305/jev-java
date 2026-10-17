package io.github.gudcks0305.jev.vercel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.gudcks0305.jev.*;
import io.github.gudcks0305.jev.spi.JevTransport;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic fixtures and loopback HTTP; never live inference or image accuracy evidence. */
class VercelDecisionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final NoulQuestion PRESENT = NoulQuestion.of("present", "Is a mug present?");
    private static final ChoiceQuestion<String> ROUTE = ChoiceQuestion.of("route", "Route", Map.of("yes", "Yes", "no", "No"));
    private static final ScoreQuestion SCORE = ScoreQuestion.of("score", "Severity", List.of("Low", "High"));

    @Test void sendsPinnedDecisionProtocolWithOrderedNativePartsOverHttp() throws Exception {
        var image = JevImage.fromBytes(new byte[]{1, 2, 3}, "png");
        var input = VercelDecisionInput.builder().text("Inspect photo").json(Map.of("id", 7)).image(image).build();
        var body = new AtomicReference<JsonNode>();
        var specification = new AtomicReference<String>();
        var legacySpecification = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v4/ai/decision-model", exchange -> {
            body.set(JSON.readTree(exchange.getRequestBody()));
            specification.set(exchange.getRequestHeaders().getFirst("ai-decision-model-specification-version"));
            legacySpecification.set(exchange.getRequestHeaders().getFirst("ai-evaluation-model-specification-version"));
            byte[] response = response().toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var client = VercelJevClient.builder().apiKey("fixture-key").protocol(VercelJevClient.Protocol.DECISION_V4)
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).maxRetries(0).build()) {
            var result = client.evaluate(input, PRESENT, ROUTE, SCORE);
            assertEquals("4", specification.get());
            assertNull(legacySpecification.get());
            assertEquals(JSON.readTree("""
                    [{"type":"text","text":"Inspect photo"},{"type":"json","value":{"id":7}},
                     {"type":"file","mediaType":"image/png","data":{"type":"data","data":"AQID"}}]
                    """), body.get().path("stateParts"));
            assertFalse(body.get().has("state"));
            assertFalse(body.get().has("model"));
            assertFalse(body.get().has("assets"));
            assertEquals("boolean", body.get().at("/questions/present/type").textValue());
            assertEquals(.9, result.answer(PRESENT).probability());
            assertEquals("yes", result.answer(ROUTE).choice());
            assertEquals(.8, result.answer(ROUTE).confidence().orElseThrow());
            assertEquals(.7, result.answer(SCORE).score());
            assertEquals("fixture", result.rawResponse().path("warnings").get(0).path("message").textValue());
        } finally { server.stop(0); }
    }

    @Test void resolvesDefaultDecisionUrlHeadersAndExactEndpointAndWrapsTextJson() throws Exception {
        var stub = new Stub(response());
        try (var client = decision(stub)) {
            client.evaluate("text state", PRESENT, ROUTE, SCORE);
            assertEquals(URI.create("https://ai-gateway.vercel.sh/v4/ai/decision-model"), stub.uri);
            assertEquals("typesafe-ai/jev", stub.headers.get("ai-model-id"));
            assertEquals("0.0.1", stub.headers.get("ai-gateway-protocol-version"));
            assertEquals("api-key", stub.headers.get("ai-gateway-auth-method"));
            assertEquals("Bearer fixture-key", stub.headers.get("Authorization"));
            assertEquals(JSON.readTree("[{\"type\":\"text\",\"text\":\"text state\"}]"), stub.body.path("stateParts"));
            client.evaluate(List.of(Map.of("value", 1)), PRESENT, ROUTE, SCORE);
            assertEquals(JSON.readTree("[{\"type\":\"json\",\"value\":[{\"value\":1}]}]"), stub.body.path("stateParts"));
            assertFalse(stub.headers.containsKey("TypeSafe-Danger-Zone-Images"));
        }
        URI endpoint = URI.create("https://proxy.example.test/custom?tenant=a");
        try (var client = VercelJevClient.builder().apiKey("fixture-key").protocol(VercelJevClient.Protocol.DECISION_V4)
                .endpoint(endpoint).model("custom-model").transport(stub).build()) {
            client.evaluate(Map.of("value", "json"), PRESENT, ROUTE, SCORE);
            assertEquals(endpoint, stub.uri);
            assertEquals("custom-model", stub.headers.get("ai-model-id"));
        }
    }

    @Test void snapshotsNativePartsAndBuilderWithoutLeakingImageOrState() {
        var state = JSON.createObjectNode().put("secret", "private");
        var builder = VercelDecisionInput.builder().json(state).image(JevImage.fromBytes(new byte[]{4, 5}, "jpg"));
        var input = builder.build();
        state.removeAll();
        builder.text("later");
        input.stateParts().removeAll();
        assertEquals(2, input.stateParts().size());
        assertEquals("private", input.stateParts().at("/0/value/secret").textValue());
        assertEquals("image/jpeg", input.stateParts().at("/1/mediaType").textValue());
        assertFalse(input.toString().contains("private"));
        assertFalse(input.toString().contains("BAU="));
        assertThrows(IllegalArgumentException.class, () -> VercelDecisionInput.builder().build());
        assertThrows(IllegalArgumentException.class, () -> VercelDecisionInput.builder().text(" "));
        assertThrows(NullPointerException.class, () -> VercelDecisionInput.builder().image(null));
        assertThrows(NullPointerException.class, () -> VercelJevClient.builder().protocol(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"present", "route", "score"})
    void anyRefusalFailsWholeBatchBeforeDecodingUnrelatedNumericAnswer(String refused) {
        ObjectNode response = response();
        ObjectNode answers = (ObjectNode) response.path("answers");
        answers.set(refused, JSON.createObjectNode().put("type", "refusal"));
        if (!refused.equals("present")) ((ObjectNode) answers.path("present")).put("probability", -1);
        var stub = new Stub(response);
        try (var client = decision(stub)) {
            var error = assertThrows(JevException.class, () -> client.evaluate("state", PRESENT, ROUTE, SCORE));
            assertEquals(JevException.Kind.REFUSAL, error.kind());
            assertEquals(0, error.statusCode());
            assertFalse(error.getMessage().contains(refused));
        }
    }

    @Test void optionalGatewayMetadataRemainsAbsent() {
        var response = JSON.createObjectNode();
        response.putObject("answers").putObject("route").put("type", "choice").put("choice", "yes");
        try (var client = decision(new Stub(response))) {
            var result = client.evaluate("state", ROUTE);
            assertTrue(result.answer(ROUTE).confidence().isEmpty());
            assertTrue(result.answer(ROUTE).probabilities().isEmpty());
            assertTrue(result.usage().inputTokens().isEmpty());
            assertEquals("typesafe-ai/jev", result.model());
        }
    }

    @Test void bothProtocolsRejectBoxAndJevInputAndLegacyRejectsNativeParts() {
        var image = JevImage.fromBytes(new byte[]{1}, "png");
        var input = VercelDecisionInput.builder().image(image).build();
        for (var protocol : VercelJevClient.Protocol.values()) {
            var stub = new Stub(response());
            try (var client = VercelJevClient.builder().apiKey("fixture-key").protocol(protocol).transport(stub).build()) {
                assertThrows(IllegalArgumentException.class, () -> client.evaluate(input, BoxQuestion.of("box", "Find")));
                assertThrows(IllegalArgumentException.class, () -> client.evaluate(JevInput.withImages(image.reference(), image), PRESENT));
                if (protocol == VercelJevClient.Protocol.EVALUATION_V4) {
                    assertThrows(IllegalArgumentException.class, () -> client.evaluate(input, PRESENT));
                }
                assertNull(stub.body);
            }
        }
    }

    @Test void repeatedImagePartsAndWholeJsonBodyAreBoundedBeforeTransport() {
        var image = JevImage.fromBytes(new byte[13 * 1024 * 1024], "png");
        assertThrows(IllegalArgumentException.class, () -> VercelDecisionInput.builder().image(image).image(image).build());
        var stub = new Stub(response());
        var input = VercelDecisionInput.builder().text("x".repeat(32 * 1024 * 1024)).build();
        try (var client = decision(stub)) {
            assertThrows(IllegalArgumentException.class, () -> client.evaluate(input, PRESENT));
            assertNull(stub.body);
        }
    }

    @Test void retainsCancellationCloseAndInjectedTransportOwnership() {
        var stub = new Stub(response());
        stub.pending = new CompletableFuture<>();
        var input = VercelDecisionInput.builder().image(JevImage.fromBytes(new byte[]{1}, "png")).build();
        var client = decision(stub);
        try {
            assertTrue(client.evaluateAsync(input, PRESENT).cancel(true));
            assertTrue(stub.pending.isCancelled());
            stub.pending = new CompletableFuture<>();
            var future = client.evaluateAsync(input, PRESENT);
            client.close();
            assertTrue(future.isCompletedExceptionally());
            assertTrue(stub.pending.isCancelled());
            assertFalse(stub.closed);
        } finally { client.close(); }
    }

    private static VercelJevClient decision(Stub stub) {
        return VercelJevClient.builder().apiKey("fixture-key").protocol(VercelJevClient.Protocol.DECISION_V4).transport(stub).build();
    }
    private static ObjectNode response() {
        try {
            return (ObjectNode) JSON.readTree("""
                    {"answers":{"present":{"type":"boolean","probability":0.9},
                    "route":{"type":"choice","choice":"yes"},"score":{"type":"score","score":0.7}},
                    "providerMetadata":{"typesafe":{"confidence":{"route":0.8}}},
                    "warnings":[{"type":"other","message":"fixture"}],"usage":{"inputTokens":10}}
                    """);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static final class Stub implements JevTransport {
        private final JsonNode response;
        private URI uri;
        private Map<String, String> headers;
        private JsonNode body;
        private CompletableFuture<JsonNode> pending;
        private boolean closed;
        private Stub(JsonNode response) { this.response = response; }
        @Override public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
            this.uri = uri;
            this.headers = headers;
            this.body = body;
            return pending == null ? CompletableFuture.completedFuture(response) : pending;
        }
        @Override public void close() { closed = true; }
    }
}
