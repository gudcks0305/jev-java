package io.github.gudcks0305.jev.typesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.gudcks0305.jev.ChoiceQuestion;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.ScoreQuestion;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Offline HTTP replay only; fixture provenance is in compatible-providers/README.md. */
class CompatibleDecisionProvidersTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIXTURE_KEY = "fixture-provider-key";
    private static final URI SOLAR = URI.create("https://api.upstage.ai/v1/systemone");
    private static final URI LIQUID = URI.create("https://api.liquid.ai/decisions/v1/systemone");
    private static final URI VERCEL = URI.create("https://ai-gateway.vercel.sh/typesafe/v1/systemone");

    @Test void solarPublishedResponsePreservesAllPrimitiveValuesOverHttp() throws Exception {
        var intent = ChoiceQuestion.of("intent", "Choose intent", Map.of(
                "refund", "Money back", "track", "Delivery status", "other", "Other request"));
        var angry = NoulQuestion.of("is_angry", "Is the customer angry?");
        var urgency = ScoreQuestion.of("urgency", "Rate urgency", List.of(
                "No action needed", "Can wait a week", "Needs attention today", "Escalate immediately"));
        try (var server = new LocalServer(200, fixture("solar-decide-response.json"));
             var client = client(server, SOLAR, "solar-decide")) {
            var result = client.evaluate(Map.of("ticket", "Package missing"), intent, angry, urgency);
            var request = server.request.get();
            assertRequest(request, SOLAR, "solar-decide", "intent", "is_angry", "urgency");
            assertEquals("Package missing", request.body().at("/state/ticket").asText());
            assertEquals("Money back", request.body().at("/questions/intent/criteria/refund").asText());
            assertEquals("Escalate immediately", request.body().at("/questions/urgency/criteria/3").asText());
            assertFalse(request.body().at("/questions/is_angry").has("criteria"));
            assertEquals("refund", result.answer(intent).choice());
            assertEquals(0.997405, result.answer(intent).probabilities().get("refund"));
            assertEquals(0.983124, result.answer(intent).confidence().orElseThrow());
            assertEquals(0.99883, result.answer(angry).probability());
            assertEquals(2.846517, result.answer(urgency).score());
            assertEquals(0.8497, result.answer(urgency).probabilities().get(3));
            assertEquals(0.684143, result.answer(urgency).confidence().orElseThrow());
            assertEquals("Escalate immediately", result.answer(urgency).legend().get(3).asText());
            assertEquals("solar-decide", result.model());
            assertEquals(670, result.usage().inputTokens().orElseThrow());
            assertEquals(4, result.usage().outputTokens().orElseThrow());
        }
    }

    @Test void liquidPublishedAnswersKeepRoundedDistributionsAndMissingEnvelopeMetadata() throws Exception {
        var team = liquidTeam();
        var bug = NoulQuestion.of("is_bug", "Is this a bug?");
        var urgency = liquidUrgency();
        try (var server = new LocalServer(200, fixture("liquid-d1-answers.json"));
             var client = client(server, LIQUID, "d1:free")) {
            var result = client.evaluate("Checkout fails", team, bug, urgency);
            assertRequest(server.request.get(), LIQUID, "d1:free", "team", "is_bug", "urgency");
            assertEquals("Checkout fails", server.request.get().body().path("state").asText());
            assertEquals("engineering", result.answer(team).choice());
            assertEquals(0.80, result.answer(team).probabilities().get("engineering"));
            assertEquals(1.0005, result.answer(team).probabilities().values().stream()
                    .mapToDouble(Double::doubleValue).sum(), 1e-12);
            assertEquals(0.74, result.answer(team).confidence().orElseThrow());
            assertEquals(0.9998, result.answer(bug).probability());
            assertEquals(1.996, result.answer(urgency).score());
            assertEquals(0.996, result.answer(urgency).probabilities().get(2));
            assertEquals(0.995, result.answer(urgency).confidence().orElseThrow());
            assertEquals("d1:free", result.model());
            assertTrue(result.usage().inputTokens().isEmpty());
            assertTrue(result.usage().outputTokens().isEmpty());
        }
    }

    @Test void liquidPublishedNoulResponseRetainsZeroOutputTokens() throws Exception {
        var question = NoulQuestion.of("is_complaint", "Is this a complaint?");
        try (var server = new LocalServer(200, fixture("liquid-d1-noul-response.json"));
             var client = client(server, LIQUID, "d1:free")) {
            var result = client.evaluate("My order is late", question);
            assertEquals(0.999, result.answer(question).probability());
            assertEquals(84, result.usage().inputTokens().orElseThrow());
            assertEquals(0, result.usage().outputTokens().orElseThrow());
        }
    }

    @Test void solarDocumentedChoiceOverflowRemainsProviderValidationError() throws Exception {
        Map<String, String> criteria = new LinkedHashMap<>();
        for (int i = 0; i < 27; i++) criteria.put("option-" + i, "Candidate " + i);
        var question = ChoiceQuestion.of("q", "Choose candidate", criteria);
        try (var server = new LocalServer(422, fixture("solar-decide-overflow.json"));
             var client = client(server, SOLAR, "solar-decide")) {
            var error = assertThrows(JevException.class, () -> client.evaluate("Synthetic state", question));
            assertEquals(JevException.Kind.VALIDATION, error.kind());
            assertEquals(422, error.statusCode());
            assertEquals(27, server.request.get().body().at("/questions/q/criteria").size());
            assertEquals(1, server.calls.get());
            assertFalse(error.getMessage().contains("27 candidates"), "Remote error text is not exposed");
        }
    }

    @Test void absentConfidenceAndLegendStayAbsentOrUseDeclaredLevels() throws Exception {
        ObjectNode response = (ObjectNode) fixture("liquid-d1-answers.json");
        ((ObjectNode) response.at("/answers/team")).remove("confidence");
        ((ObjectNode) response.at("/answers/urgency")).remove(List.of("confidence", "legend"));
        var team = liquidTeam();
        var bug = NoulQuestion.of("is_bug", "Is this a bug?");
        var urgency = liquidUrgency();
        try (var server = new LocalServer(200, response);
             var client = client(server, LIQUID, "d1:free")) {
            var result = client.evaluate("Synthetic state", team, bug, urgency);
            assertTrue(result.answer(team).confidence().isEmpty());
            assertTrue(result.answer(urgency).confidence().isEmpty());
            assertEquals("High", result.answer(urgency).legend().get(2).asText());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"team", "urgency"})
    void typeSafeRouteRejectsMissingRequiredDistribution(String answerId) throws Exception {
        ObjectNode response = (ObjectNode) fixture("liquid-d1-answers.json");
        ((ObjectNode) response.at("/answers/" + answerId)).remove("probabilities");
        try (var server = new LocalServer(200, response);
             var client = client(server, LIQUID, "d1:free")) {
            var error = assertThrows(JevException.class, () -> client.evaluate("Synthetic state",
                    liquidTeam(), NoulQuestion.of("is_bug", "Is this a bug?"), liquidUrgency()));
            assertEquals(JevException.Kind.PROTOCOL, error.kind());
        }
    }

    @Test void vercelTypeSafeRouteKeepsNativeWireAndRawProviderMetadata() throws Exception {
        ObjectNode response = (ObjectNode) fixture("solar-decide-response.json");
        // Entire gateway response is synthetic, not a Vercel inference capture.
        response.put("model", "typesafe-ai/jev");
        response.putObject("provider_metadata").putObject("gateway").put("route", "synthetic");
        var intent = ChoiceQuestion.of("intent", "Choose intent", Map.of(
                "refund", "Money back", "track", "Delivery status", "other", "Other request"));
        var angry = NoulQuestion.of("is_angry", "Is the customer angry?");
        var urgency = ScoreQuestion.of("urgency", "Rate urgency", List.of("None", "Later", "Today", "Now"));
        try (var server = new LocalServer(200, response);
             var client = client(server, VERCEL, "typesafe-ai/jev")) {
            var result = client.evaluate("Synthetic state", intent, angry, urgency);
            assertRequest(server.request.get(), VERCEL, "typesafe-ai/jev", "intent", "is_angry", "urgency");
            assertEquals("refund", result.answer(intent).choice());
            assertEquals(0.99883, result.answer(angry).probability());
            assertEquals(2.846517, result.answer(urgency).score());
            assertEquals(670, result.usage().inputTokens().orElseThrow());
            assertEquals("synthetic", result.rawResponse().at("/provider_metadata/gateway/route").asText());
        }
    }

    @Test void vercelFallbackEmptyDistributionIsOutsideNativeTypeSafeContract() throws Exception {
        ObjectNode response = (ObjectNode) fixture("liquid-d1-answers.json");
        ((ObjectNode) response.at("/answers/team")).putObject("probabilities");
        try (var server = new LocalServer(200, response);
             var client = client(server, VERCEL, "typesafe-ai/jev")) {
            var error = assertThrows(JevException.class, () -> client.evaluate("Synthetic state",
                    liquidTeam(), NoulQuestion.of("is_bug", "Is this a bug?"), liquidUrgency()));
            assertEquals(JevException.Kind.PROTOCOL, error.kind());
        }
    }

    private static ChoiceQuestion<String> liquidTeam() {
        return ChoiceQuestion.of("team", "Choose team", Map.of(
                "engineering", "Software", "frontend", "User interface", "billing", "Payments", "account", "Access"));
    }

    private static ScoreQuestion liquidUrgency() {
        return ScoreQuestion.of("urgency", "Rate urgency", List.of("Low", "Medium", "High"));
    }

    private static TypeSafeJevClient client(LocalServer server, URI provider, String model) {
        return TypeSafeJevClient.builder().apiKey(FIXTURE_KEY).model(model)
                .endpoint(server.endpoint(provider.getRawPath())).timeout(Duration.ofSeconds(5)).build();
    }

    private static void assertRequest(Request request, URI provider, String model,
            String choiceId, String noulId, String scoreId) {
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals(provider.getRawPath(), request.uri().getRawPath());
        assertEquals("Bearer " + FIXTURE_KEY, request.authorization());
        assertEquals("application/json", request.contentType());
        assertEquals(model, request.body().path("model").asText());
        assertEquals(3, request.body().path("questions").size());
        assertEquals("choice", request.body().at("/questions/" + choiceId + "/type").asText());
        assertEquals("noul", request.body().at("/questions/" + noulId + "/type").asText());
        assertEquals("score", request.body().at("/questions/" + scoreId + "/type").asText());
        assertTrue(request.body().at("/questions/" + choiceId + "/criteria").isObject());
        assertTrue(request.body().at("/questions/" + scoreId + "/criteria").isArray());
    }

    private static JsonNode fixture(String name) throws IOException {
        try (var stream = CompatibleDecisionProvidersTest.class.getResourceAsStream("/compatible-providers/" + name)) {
            if (stream == null) throw new IOException("Missing fixture " + name);
            return JSON.readTree(stream);
        }
    }

    private record Request(String method, URI uri, String authorization, String contentType, JsonNode body) {}

    private static final class LocalServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<Request> request = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();

        private LocalServer(int status, JsonNode response) throws IOException {
            byte[] bytes = JSON.writeValueAsBytes(response);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    calls.incrementAndGet();
                    request.set(new Request(exchange.getRequestMethod(), exchange.getRequestURI(),
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            exchange.getRequestHeaders().getFirst("Content-Type"),
                            JSON.readTree(exchange.getRequestBody())));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        private URI endpoint(String path) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        }

        @Override public void close() { server.stop(0); }
    }
}
