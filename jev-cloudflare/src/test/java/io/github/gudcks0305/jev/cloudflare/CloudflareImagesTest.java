package io.github.gudcks0305.jev.cloudflare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.gudcks0305.jev.*;
import io.github.gudcks0305.jev.internal.EvaluationCodec;
import io.github.gudcks0305.jev.internal.WireFormat;
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

/** Synthetic contract fixtures and local HTTP only; no live inference or image accuracy evidence. */
class CloudflareImagesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final URI ENDPOINT = URI.create("https://proxy.example.test/run");
    private static final NoulQuestion VISIBLE = NoulQuestion.of("visible", "Is the mug visible in `photo`?");
    private static final BoxQuestion BOX = BoxQuestion.of("mug", "Find the mug in `photo`");

    @Test void sendsAssetsInsideInputAndMixedBoxOverHttpWithoutTypeSafeHeader() throws Exception {
        var image = image();
        var input = JevInput.withImages(Map.of("photo", image.reference()), image);
        AtomicReference<JsonNode> request = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> dangerHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/run", exchange -> {
            request.set(JSON.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            dangerHeader.set(exchange.getRequestHeaders().getFirst("TypeSafe-Danger-Zone-Images"));
            byte[] response = envelope(mixedResponse()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var client = CloudflareJevClient.builder().apiKey("fixture-token").model("typesafe/jev-1.14.0")
                .endpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/run"))
                .maxRetries(0).build()) {
            var result = client.evaluate(input, VISIBLE, BOX);
            assertEquals("Bearer fixture-token", authorization.get());
            assertNull(dangerHeader.get());
            assertEquals("typesafe/jev-1.14.0", request.get().path("model").textValue());
            assertEquals(image.reference(), request.get().at("/input/state/photo").textValue());
            var asset = request.get().at("/input/assets").path(image.reference());
            assertEquals("image", asset.path("type").textValue());
            assertEquals("png", asset.path("format").textValue());
            assertEquals("AQID", asset.path("data_base64").textValue());
            assertFalse(request.get().has("assets"));
            assertFalse(request.get().has("state"));
            assertFalse(request.get().has("questions"));
            assertEquals("box", request.get().at("/input/questions/mug/type").textValue());
            assertEquals(.95, result.answer(VISIBLE).probability());
            assertEquals(new BoxCoordinates(.25, .82, .44, .84), result.answer(BOX).box());
            assertEquals(.92, result.answer(BOX).confidence().orElseThrow());
            assertTrue(result.rawResponse().path("success").booleanValue());
            assertEquals("fixture", result.rawResponse().path("messages").get(0).textValue());
        } finally { server.stop(0); }
    }

    @Test void keepsReferencesAtEveryDepthInStateInstructionsAndAllCriteria() {
        var image = image();
        var choice = ChoiceQuestion.of("choice", Map.of(image.reference(), "key stays text", "picture", image.reference()),
                Map.of("same", Map.of("examples", List.of(image.reference())), "different", "Different"));
        var score = ScoreQuestion.of("score", List.of("Compare", image.reference()), List.of("Low", image.reference()));
        var noul = NoulQuestion.of("same", "Before " + image.reference() + " after")
                .withCriteria(image.reference(), Map.of("example", image.reference()));
        var body = EvaluationCodec.request(JevInput.withImages(Map.of("nested", List.of(image.reference())), image, image),
                Map.of("choice", choice, "score", score, "same", noul), "typesafe/jev-1.14.0", WireFormat.CLOUDFLARE);
        assertEquals(1, body.at("/input/assets").size());
        assertEquals(image.reference(), body.at("/input/state/nested/0").textValue());
        assertEquals("key stays text", body.at("/input/questions/choice/instructions").path(image.reference()).textValue());
        assertEquals(image.reference(), body.at("/input/questions/choice/instructions/picture").textValue());
        assertEquals(image.reference(), body.at("/input/questions/choice/criteria/same/examples/0").textValue());
        assertEquals(image.reference(), body.at("/input/questions/score/instructions/1").textValue());
        assertEquals(image.reference(), body.at("/input/questions/score/criteria/1").textValue());
        assertEquals(image.reference(), body.at("/input/questions/same/criteria/true").textValue());
        assertEquals(image.reference(), body.at("/input/questions/same/criteria/false/example").textValue());
        assertEquals("Before " + image.reference() + " after", body.at("/input/questions/same/instructions").textValue());
        assertFalse(body.has("assets"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void supportsDirectAndEnvelopeResponsesWithMissingBoxConfidence(boolean wrapped) {
        var image = image();
        ObjectNode response = mixedResponse();
        ((ObjectNode) response.at("/answers/mug")).remove("confidence");
        JsonNode wire = wrapped ? envelope(response) : response;
        var stub = new Stub(wire);
        try (var client = CloudflareJevClient.builder().apiKey("fixture-token").endpoint(ENDPOINT)
                .model("typesafe/jev-1.14.0").transport(stub).build()) {
            var result = client.evaluate(JevInput.withImages(image.reference(), image), VISIBLE, BOX);
            assertEquals(new BoxCoordinates(.25, .82, .44, .84), result.answer(BOX).box());
            assertTrue(result.answer(BOX).confidence().isEmpty());
            assertEquals("jev-1.14.0", result.model());
            assertEquals(100, result.usage().inputTokens().orElseThrow());
            assertEquals(wire, result.rawResponse());
        }
    }

    @Test void preservesTextBodyAndDefaultModelAfterImageRequest() {
        var image = image();
        var stub = new Stub(mixedResponse());
        try (var client = CloudflareJevClient.builder().apiKey("fixture-token").endpoint(ENDPOINT).transport(stub).build()) {
            client.evaluate(JevInput.withImages(image.reference(), image), VISIBLE, BOX);
            assertEquals("typesafe/jev", stub.body.path("model").textValue());
            assertFalse(stub.headers.containsKey("TypeSafe-Danger-Zone-Images"));
            stub.response = JSON.createObjectNode().set("answers", JSON.createObjectNode().set("visible",
                    JSON.createObjectNode().put("type", "noul").put("noul", .2)));
            client.evaluate("plain text", VISIBLE);
            assertEquals("plain text", stub.body.at("/input/state").textValue());
            assertEquals("typesafe/jev", stub.body.path("model").textValue());
            assertFalse(stub.body.at("/input").has("assets"));
            assertFalse(stub.body.has("assets"));
        }
    }

    @Test void rejectsUnusedImagesKeyOnlyReferenceAndFailedEnvelopeWithoutSendingPrivateDetails() {
        var image = image();
        var stub = new Stub(mixedResponse());
        try (var client = CloudflareJevClient.builder().apiKey("fixture-token").endpoint(ENDPOINT).transport(stub).build()) {
            assertThrows(IllegalArgumentException.class,
                    () -> client.evaluate(JevInput.withImages("plain state", image), VISIBLE));
            assertThrows(IllegalArgumentException.class,
                    () -> client.evaluate(JevInput.withImages(Map.of(image.reference(), "plain value"), image), VISIBLE));
            assertThrows(IllegalArgumentException.class, () -> client.evaluate("plain state", BOX));
            assertNull(stub.body);
            ObjectNode failed = envelope(mixedResponse()).put("success", false);
            failed.putArray("errors").addObject().put("message", "private upstream detail");
            stub.response = failed;
            var error = assertThrows(JevException.class,
                    () -> client.evaluate(JevInput.withImages(image.reference(), image), VISIBLE, BOX));
            assertEquals(JevException.Kind.PROTOCOL, error.kind());
            assertFalse(error.getMessage().contains("private upstream detail"));
        }
    }

    @Test void malformedBoxRemainsProtocolErrorAndCancellationPreservesTransportOwnership() {
        var image = image();
        var input = JevInput.withImages(image.reference(), image);
        ObjectNode response = mixedResponse();
        ((ObjectNode) response.at("/answers/mug/box")).put("left", -1);
        var stub = new Stub(envelope(response));
        var client = CloudflareJevClient.builder().apiKey("fixture-token").endpoint(ENDPOINT).transport(stub).build();
        try {
            assertEquals(JevException.Kind.PROTOCOL,
                    assertThrows(JevException.class, () -> client.evaluate(input, VISIBLE, BOX)).kind());
            stub.pending = new CompletableFuture<>();
            assertTrue(client.evaluateAsync(input, VISIBLE, BOX).cancel(true));
            assertTrue(stub.pending.isCancelled());
            stub.pending = new CompletableFuture<>();
            var future = client.evaluateAsync(input, VISIBLE, BOX);
            client.close();
            assertTrue(future.isCompletedExceptionally());
            assertTrue(stub.pending.isCancelled());
            assertFalse(stub.closed);
        } finally { client.close(); }
    }

    private static JevImage image() { return JevImage.fromBytes(new byte[]{1, 2, 3}, "png"); }
    private static ObjectNode mixedResponse() {
        try {
            return (ObjectNode) JSON.readTree("""
                    {"model":"jev-1.14.0","answers":{
                      "visible":{"type":"noul","noul":0.95},
                      "mug":{"type":"box","box":{"top":0.25,"bottom":0.82,"left":0.44,"right":0.84},"confidence":0.92}
                    },"usage":{"input_tokens":100,"output_tokens":20}}
                    """);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static ObjectNode envelope(JsonNode response) {
        var body = JSON.createObjectNode().put("success", true);
        body.set("result", response);
        body.putArray("errors");
        body.putArray("messages").add("fixture");
        return body;
    }
    private static final class Stub implements JevTransport {
        private JsonNode response;
        private JsonNode body;
        private Map<String, String> headers;
        private CompletableFuture<JsonNode> pending;
        private boolean closed;
        private Stub(JsonNode response) { this.response = response; }
        @Override public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
            this.headers = headers;
            this.body = body;
            return pending == null ? CompletableFuture.completedFuture(response) : pending;
        }
        @Override public void close() { closed = true; }
    }
}
