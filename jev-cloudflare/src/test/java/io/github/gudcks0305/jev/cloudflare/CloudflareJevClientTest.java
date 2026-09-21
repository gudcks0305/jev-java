package io.github.gudcks0305.jev.cloudflare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.gudcks0305.jev.ChoiceQuestion;
import io.github.gudcks0305.jev.Evaluation;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.spi.JevTransport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CloudflareJevClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final NoulQuestion urgent = NoulQuestion.of("urgent", "Is it urgent?");

    @Test
    void sendsRealHttpRequestToExactEndpointAndMapsCloudflareEnvelope() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom/run", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            requestBody.set(JSON.readTree(exchange.getRequestBody()));
            byte[] response = envelopeResponse().toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/custom/run?tenant=acme");
        try (var client = CloudflareJevClient.builder()
                .apiKey("test-token")
                .endpoint(endpoint)
                .maxRetries(0)
                .build()) {
            Evaluation result = client.evaluate(Map.of("message", "Three days late"), urgent);

            assertEquals("/custom/run", path.get());
            assertEquals("tenant=acme", query.get());
            assertEquals("Bearer test-token", authorization.get());
            assertEquals("jev-java/0.2.0", userAgent.get());
            assertEquals("typesafe/jev", requestBody.get().path("model").asText());
            assertEquals("Three days late", requestBody.get().at("/input/state/message").asText());
            assertEquals("noul", requestBody.get().at("/input/questions/urgent/type").asText());
            assertFalse(requestBody.get().has("questions"));

            assertEquals(.95, result.answer(urgent).probability());
            assertEquals("jev-1.13.0", result.model());
            assertEquals(426, result.usage().inputTokens().orElseThrow());
            assertTrue(result.rawResponse().path("success").booleanValue());
            assertEquals(.95, result.rawResponse().at("/result/answers/urgent/noul").asDouble());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsDocumentedDirectModelResponse() throws Exception {
        Stub transport = new Stub(directResponse());
        try (var client = CloudflareJevClient.builder()
                .apiKey("test-token")
                .accountId("023e105f4ecef8ad9ca31a8372d0c353")
                .transport(transport)
                .build()) {
            Evaluation result = client.evaluate("state", urgent);

            assertEquals(.95, result.answer(urgent).probability());
            assertEquals("jev-1.13.0", result.model());
            assertEquals(73, result.usage().outputTokens().orElseThrow());
            assertEquals("jev-1.13.0", result.rawResponse().path("model").asText());
            assertEquals(
                    URI.create("https://api.cloudflare.com/client/v4/accounts/023e105f4ecef8ad9ca31a8372d0c353/ai/run"),
                    transport.uri);
        }
    }

    @Test
    void buildsAccountPathAgainstCustomBaseUrl() throws Exception {
        Stub transport = new Stub(directResponse());
        try (var client = CloudflareJevClient.builder()
                .apiKey("test-token")
                .accountId("account-id_123")
                .baseUrl(URI.create("https://proxy.example.test/cloudflare/"))
                .transport(transport)
                .build()) {
            client.evaluate("state", urgent);
            assertEquals(
                    URI.create("https://proxy.example.test/cloudflare/accounts/account-id_123/ai/run"),
                    transport.uri);
        }
    }

    @Test
    void rejectsUnsafeAccountIdBeforeTransport() {
        for (String value : new String[] {"", ".", "..", "account/id", "account?id", "account#id", "account%2Fid", "account id"}) {
            Stub transport = new Stub(directResponse());
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                    CloudflareJevClient.builder()
                            .apiKey("test-token")
                            .accountId(value)
                            .transport(transport)
                            .build());
            assertTrue(error.getMessage().contains(value.isEmpty() ? "accountId" : "account ID"));
            assertNull(transport.uri);
        }
    }

    @Test
    void rejectsConflictingBaseUrlAndExactEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> CloudflareJevClient.builder()
                .apiKey("test-token")
                .baseUrl(URI.create("https://base.example.test"))
                .endpoint(URI.create("https://endpoint.example.test/run?tenant=one"))
                .build());
    }

    @Test
    void rejectsFailedCloudflareEnvelopeWithoutLeakingProviderDetails() throws Exception {
        JsonNode response = JSON.readTree("""
                {"success":false,"result":null,
                 "errors":[{"code":10000,"message":"secret upstream detail"}],"messages":[]}
                """);
        try (var client = CloudflareJevClient.builder()
                .apiKey("test-token")
                .endpoint(URI.create("https://proxy.example.test/run"))
                .transport(new Stub(response))
                .build()) {
            JevException error = assertThrows(JevException.class, () -> client.evaluate("state", urgent));
            assertEquals(JevException.Kind.PROTOCOL, error.kind());
            assertEquals("Cloudflare request failed", error.getMessage());
            assertFalse(error.getMessage().contains("secret upstream detail"));
        }
    }

    @Test
    void requiresCloudflareChoiceProbabilities() throws Exception {
        JsonNode response = JSON.readTree("""
                {"model":"jev-1.13.0","answers":{"route":{"type":"choice","choice":"billing"}}}
                """);
        var route = ChoiceQuestion.of(
                "route", "Which team?", Map.of("billing", "Billing", "technical", "Technical"));
        try (var client = CloudflareJevClient.builder()
                .apiKey("test-token")
                .endpoint(URI.create("https://proxy.example.test/run"))
                .transport(new Stub(response))
                .build()) {
            JevException error = assertThrows(JevException.class, () -> client.evaluate("state", route));
            assertEquals(JevException.Kind.PROTOCOL, error.kind());
            assertEquals("Missing choice distribution", error.getMessage());
        }
    }

    private static JsonNode directResponse() {
        try {
            return JSON.readTree("""
                    {"model":"jev-1.13.0","answers":{"urgent":{"type":"noul","noul":0.95}},
                     "usage":{"input_tokens":426,"output_tokens":73}}
                    """);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static JsonNode envelopeResponse() {
        try {
            return JSON.readTree("""
                    {"success":true,"result":{"model":"jev-1.13.0",
                     "answers":{"urgent":{"type":"noul","noul":0.95}},
                     "usage":{"input_tokens":426,"output_tokens":73}},"errors":[],"messages":[]}
                    """);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class Stub implements JevTransport {
        private final JsonNode response;
        private URI uri;

        private Stub(JsonNode response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
            this.uri = uri;
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public void close() {}
    }
}
