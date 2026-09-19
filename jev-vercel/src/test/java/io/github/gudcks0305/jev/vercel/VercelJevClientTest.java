package io.github.gudcks0305.jev.vercel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gudcks0305.jev.*;
import io.github.gudcks0305.jev.spi.JevTransport;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VercelJevClientTest {
    @Test void mapsGatewayContractMetadataAndStructuredLevels() throws Exception {
        var response = new ObjectMapper().readTree("""
                {"answers":{
                  "route":{"type":"choice","choice":"billing","probabilities":{"billing":0.9,"technical":0.1}},
                  "urgent":{"type":"boolean","probability":0.97},
                  "score":{"type":"score","score":1.8,"probabilities":{"0":0.05,"1":0.1,"2":0.85}}
                },"usage":{"inputTokens":123,"outputTokens":0},
                "rounding":{"probabilityDecimals":2},"providerMetadata":{"typesafe":{"confidence":{"route":0.8,"score":0.75}}},
                "warnings":[{"type":"other","message":"example"}]}
                """);
        var route = ChoiceQuestion.of("route", "Which team?", Map.of("billing", "Payments", "technical", "Bugs"));
        var urgent = NoulQuestion.of("urgent", "Urgent?").withCriteria("Time sensitive", "Not urgent");
        var score = ScoreQuestion.of("score", "Severity", List.of(Map.of("description", "Low"), "Medium", "High"));
        JevTransport transport = new JevTransport() {
            @Override public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
                assertEquals(URI.create("https://ai-gateway.vercel.sh/v4/ai/evaluation-model"), uri);
                assertEquals("typesafe-ai/jev", headers.get("ai-model-id"));
                assertEquals("0.0.1", headers.get("ai-gateway-protocol-version"));
                assertEquals("4", headers.get("ai-evaluation-model-specification-version"));
                assertEquals("api-key", headers.get("ai-gateway-auth-method"));
                assertEquals("Bearer test-key", headers.get("Authorization"));
                assertFalse(body.has("model"));
                assertEquals("boolean", body.at("/questions/urgent/type").asText());
                assertEquals("Time sensitive", body.at("/questions/urgent/criteria/true").asText());
                return CompletableFuture.completedFuture(response);
            }
            @Override public void close() {}
        };
        try (var client = VercelJevClient.builder().apiKey("test-key").transport(transport).build()) {
            var result = client.evaluate("Double charge", route, urgent, score);
            assertEquals("billing", result.answer(route).choice());
            assertEquals(.8, result.answer(route).confidence().orElseThrow());
            assertEquals(.97, result.answer(urgent).probability());
            assertEquals(1.8, result.answer(score).score());
            assertEquals("Low", result.answer(score).legend().get(0).path("description").asText());
            assertEquals(.75, result.answer(score).confidence().orElseThrow());
            assertEquals(123, result.usage().inputTokens().orElseThrow());
            assertEquals(1, result.rawResponse().path("warnings").size());
        }
    }

    @Test void optionalProviderDataIsNotFabricated() throws Exception {
        var response = new ObjectMapper().readTree("""
                {"answers":{"route":{"type":"choice","choice":"yes"}}}
                """);
        JevTransport transport = new JevTransport() {
            @Override public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
                return CompletableFuture.completedFuture(response);
            }
            @Override public void close() {}
        };
        var question = ChoiceQuestion.of("route", "Select", Map.of("yes", "Yes", "no", "No"));
        try (var client = VercelJevClient.builder().apiKey("test").transport(transport).build()) {
            var result = client.evaluate("state", question);
            assertTrue(result.answer(question).probabilities().isEmpty());
            assertTrue(result.answer(question).confidence().isEmpty());
            assertTrue(result.usage().inputTokens().isEmpty());
        }
    }
}
