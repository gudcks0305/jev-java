package io.github.gudcks0305.jev.openrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.gudcks0305.jev.ChoiceQuestion;
import io.github.gudcks0305.jev.Evaluation;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.ScoreQuestion;
import io.github.gudcks0305.jev.spi.JevTransport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class OpenRouterJevClientTest {
    enum Team { BILLING, TECHNICAL }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ChoiceQuestion<Team> route = ChoiceQuestion.of("route", "Which team?", Team.class);
    private final NoulQuestion urgent = NoulQuestion.of("urgent", "Is it urgent?");
    private final ScoreQuestion severity = ScoreQuestion.of(
            "severity", "How severe?", List.of("Low", Map.of("label", "Medium"), List.of("High")));

    @Test
    void sendsRealHttpRequestToExactEndpointAndMapsOpenRouterContract() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> gatewayHeader = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/proxy/decisions", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            gatewayHeader.set(exchange.getRequestHeaders().getFirst("ai-model-id"));
            requestBody.set(JSON.readTree(exchange.getRequestBody()));
            byte[] response = response().toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/proxy/decisions?tenant=acme");
        try (var client = OpenRouterJevClient.builder()
                .apiKey("test-key")
                .endpoint(endpoint)
                .maxRetries(0)
                .build()) {
            Evaluation result = client.evaluate(Map.of("message", "Double charge"), route, urgent, severity);

            assertEquals("/proxy/decisions", path.get());
            assertEquals("tenant=acme", query.get());
            assertEquals("Bearer test-key", authorization.get());
            assertNull(gatewayHeader.get(), "OpenRouter must not receive Vercel gateway headers");
            assertEquals("typesafe/jev-1.13", requestBody.get().path("model").asText());
            assertEquals("noul", requestBody.get().at("/questions/urgent/type").asText());
            assertTrue(requestBody.get().at("/questions/route/criteria/BILLING").isNull());
            assertEquals("Medium", requestBody.get().at("/questions/severity/criteria/1/label").asText());

            assertEquals(Team.BILLING, result.answer(route).choice());
            assertEquals(.85, result.answer(route).probabilities().get(Team.BILLING));
            assertEquals(.82, result.answer(route).confidence().orElseThrow());
            assertEquals(.92, result.answer(urgent).probability());
            assertEquals(1.6, result.answer(severity).score());
            assertEquals("High", result.answer(severity).legend().get(2).asText());
            assertEquals(.78, result.answer(severity).confidence().orElseThrow());
            assertEquals("typesafe/jev-1.13", result.model());
            assertEquals(312, result.usage().inputTokens().orElseThrow());
            assertEquals("gen-123", result.rawResponse().path("id").asText());
            assertEquals("Together", result.rawResponse().path("provider").asText());
            assertEquals(.0042, result.rawResponse().at("/usage/cost").asDouble());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolvesDefaultAndCustomBaseUrlsAndRejectsAmbiguousTarget() throws Exception {
        Stub defaultStub = new Stub(singleNoulResponse());
        try (var client = client(defaultStub)) {
            client.evaluate("state", urgent);
            assertEquals(URI.create("https://openrouter.ai/api/alpha/decisions"), defaultStub.uri);
            assertEquals("typesafe/jev-1.13", defaultStub.body.path("model").asText());
        }

        Stub customStub = new Stub(singleNoulResponse());
        try (var client = OpenRouterJevClient.builder()
                .apiKey("test-key")
                .model("custom/model")
                .baseUrl(URI.create("https://proxy.example/openrouter/"))
                .transport(customStub)
                .build()) {
            client.evaluate("state", urgent);
            assertEquals(URI.create("https://proxy.example/openrouter/api/alpha/decisions"), customStub.uri);
            assertEquals("custom/model", customStub.body.path("model").asText());
        }

        assertThrows(IllegalArgumentException.class, () -> OpenRouterJevClient.builder()
                .apiKey("test-key")
                .baseUrl(URI.create("https://proxy.example/root"))
                .endpoint(URI.create("https://proxy.example/exact?tenant=a"))
                .build());
    }

    @Test
    void acceptsMissingOptionalMetadataAndDistributionsAndFallsBackToQuestionLegend() throws Exception {
        JsonNode response = JSON.readTree("""
                {"answers":{
                  "route":{"type":"choice","choice":"TECHNICAL"},
                  "urgent":{"type":"noul","noul":0.4},
                  "severity":{"type":"score","score":1.25}
                }}
                """);
        try (var client = client(new Stub(response))) {
            Evaluation result = client.evaluate("state", route, urgent, severity);
            assertTrue(result.answer(route).probabilities().isEmpty());
            assertTrue(result.answer(route).confidence().isEmpty());
            assertTrue(result.answer(severity).probabilities().isEmpty());
            assertTrue(result.answer(severity).confidence().isEmpty());
            assertEquals("Low", result.answer(severity).legend().get(0).asText());
            assertEquals("Medium", result.answer(severity).legend().get(1).path("label").asText());
            assertEquals("High", result.answer(severity).legend().get(2).get(0).asText());
            assertEquals("typesafe/jev-1.13", result.model());
            assertTrue(result.usage().inputTokens().isEmpty());
            assertTrue(result.usage().outputTokens().isEmpty());
            assertTrue(result.rawResponse().path("provider").isMissingNode());
        }
    }

    @Test
    void acceptsStructuredOpenRouterResponseLegendValues() throws Exception {
        ObjectNode body = (ObjectNode) response();
        ObjectNode legend = (ObjectNode) body.at("/answers/severity/legend");
        legend.set("0", JSON.readTree("{\"label\":\"Low\"}"));
        legend.set("1", JSON.readTree("[\"Medium\",{\"detail\":\"review\"}]"));
        try (var client = client(new Stub(body))) {
            Evaluation result = client.evaluate("state", route, urgent, severity);
            assertEquals("Low", result.answer(severity).legend().get(0).path("label").asText());
            assertEquals("Medium", result.answer(severity).legend().get(1).get(0).asText());
            assertEquals("review", result.answer(severity).legend().get(1).get(1).path("detail").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"boolean", "null", "number"})
    void rejectsInvalidProvidedOpenRouterLegendValue(String defect) throws Exception {
        ObjectNode body = (ObjectNode) response();
        ObjectNode legend = (ObjectNode) body.at("/answers/severity/legend");
        switch (defect) {
            case "boolean" -> legend.put("1", false);
            case "null" -> legend.putNull("1");
            case "number" -> legend.put("1", 42);
        }
        try (var client = client(new Stub(body))) {
            JevException error = assertThrows(
                    JevException.class, () -> client.evaluate("state", route, urgent, severity));
            assertEquals(JevException.Kind.PROTOCOL, error.kind());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"choice-partial", "choice-probability", "score-partial", "score-confidence"})
    void rejectsMalformedProvidedDistributionsAndProbabilities(String defect) throws Exception {
        ObjectNode body = (ObjectNode) response();
        switch (defect) {
            case "choice-partial" -> ((ObjectNode) body.at("/answers/route/probabilities")).remove("TECHNICAL");
            case "choice-probability" -> ((ObjectNode) body.at("/answers/route/probabilities")).put("BILLING", 1.01);
            case "score-partial" -> ((ObjectNode) body.at("/answers/severity/probabilities")).remove("2");
            case "score-confidence" -> ((ObjectNode) body.at("/answers/severity")).put("confidence", -0.01);
        }
        try (var client = client(new Stub(body))) {
            JevException error = assertThrows(
                    JevException.class, () -> client.evaluate("state", route, urgent, severity));
            assertEquals(JevException.Kind.PROTOCOL, error.kind());
        }
    }

    @Test
    void validatesOpenRouterCriteriaBeforeTransportAndOmitsAllNullNoulCriteria() throws Exception {
        Stub stub = new Stub(singleNoulResponse());
        NoulQuestion noDescriptions = NoulQuestion.of("urgent", "Urgent?").withCriteria(null, null);
        try (var client = client(stub)) {
            client.evaluate("state", noDescriptions);
            assertFalse(stub.body.at("/questions/urgent").has("criteria"));
        }

        assertRejectedBeforeTransport(ChoiceQuestion.of("bad", "Pick", Map.of("yes", true, "no", "No")));
        assertRejectedBeforeTransport(ScoreQuestion.of("bad", "Rate", Arrays.asList("Low", null, "High")));
        assertRejectedBeforeTransport(NoulQuestion.of("bad", "Decide").withCriteria(null, "No"));
    }

    private void assertRejectedBeforeTransport(io.github.gudcks0305.jev.Question<?> question) throws Exception {
        Stub stub = new Stub(singleNoulResponse());
        try (var client = client(stub)) {
            assertThrows(IllegalArgumentException.class, () -> client.evaluate("state", question));
            assertNull(stub.body);
        }
    }

    private OpenRouterJevClient client(Stub transport) {
        return OpenRouterJevClient.builder().apiKey("test-key").transport(transport).build();
    }

    private static JsonNode singleNoulResponse() throws IOException {
        return JSON.readTree("""
                {"answers":{"urgent":{"type":"noul","noul":0.4}}}
                """);
    }

    private static JsonNode response() throws IOException {
        return JSON.readTree("""
                {"id":"gen-123","model":"typesafe/jev-1.13","provider":"Together","answers":{
                  "route":{"type":"choice","choice":"BILLING","probabilities":{"BILLING":0.85,"TECHNICAL":0.15},"confidence":0.82},
                  "urgent":{"type":"noul","noul":0.92},
                  "severity":{"type":"score","score":1.6,"probabilities":{"0":0.05,"1":0.3,"2":0.65},"legend":{"0":"Low","1":{"label":"Medium"},"2":"High"},"confidence":0.78}
                },"usage":{"input_tokens":312,"output_tokens":48,"cost":0.0042}}
                """);
    }

    private static final class Stub implements JevTransport {
        private final JsonNode response;
        private URI uri;
        private JsonNode body;

        private Stub(JsonNode response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
            this.uri = uri;
            this.body = body;
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public void close() {}
    }
}
