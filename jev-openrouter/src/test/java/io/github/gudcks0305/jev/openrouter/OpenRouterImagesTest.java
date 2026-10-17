package io.github.gudcks0305.jev.openrouter;

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
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic contract fixtures and local HTTP only; no live inference or image accuracy evidence. */
class OpenRouterImagesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final NoulQuestion VISIBLE = NoulQuestion.of("visible", "Is the mug visible in `photo`?");
    private static final BoxQuestion BOX = BoxQuestion.of("mug", "Find the mug in `photo`");

    @Test void sendsImagePartsAndMixedBoxOverHttpWithoutTypeSafeHeader() throws Exception {
        var image = image();
        var input = JevInput.withImages(Map.of("photo", image.reference()), image);
        AtomicReference<JsonNode> request = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> dangerHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/decisions", exchange -> {
            request.set(JSON.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            dangerHeader.set(exchange.getRequestHeaders().getFirst("TypeSafe-Danger-Zone-Images"));
            byte[] response = mixedResponse().toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var client = OpenRouterJevClient.builder().apiKey("fixture-key").model("typesafe/jev-1.14")
                .endpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/decisions"))
                .maxRetries(0).build()) {
            var result = client.evaluate(input, VISIBLE, BOX);
            assertEquals("Bearer fixture-key", authorization.get());
            assertNull(dangerHeader.get());
            assertEquals("typesafe/jev-1.14", request.get().path("model").textValue());
            assertEquals(imagePart(image), request.get().at("/state/photo"));
            assertFalse(request.get().has("assets"));
            assertFalse(request.get().has("input"));
            assertEquals("box", request.get().at("/questions/mug/type").textValue());
            assertFalse(request.get().at("/questions/mug").has("criteria"));
            assertEquals(.95, result.answer(VISIBLE).probability());
            assertEquals(new BoxCoordinates(.25, .82, .44, .84), result.answer(BOX).box());
            assertEquals(.92, result.answer(BOX).confidence().orElseThrow());
            assertEquals("fixture-id", result.rawResponse().path("id").textValue());
            assertEquals(.001, result.rawResponse().at("/usage/cost").doubleValue());
        } finally { server.stop(0); }
    }

    @Test void mapsImagesAtEveryDepthInStateInstructionsAndAllCriteriaWithoutChangingKeys() {
        var image = image();
        var part = imagePart(image);
        var choice = ChoiceQuestion.of("choice", Map.of(image.reference(), "key stays text", "picture", image.reference()),
                Map.of("same", Map.of("examples", List.of(image.reference())), "different", "Different"));
        var score = ScoreQuestion.of("score", List.of("Compare", image.reference()), List.of("Low", image.reference()));
        var noul = NoulQuestion.of("same", "Before " + image.reference() + " after")
                .withCriteria(image.reference(), Map.of("example", image.reference()));
        var body = EvaluationCodec.request(JevInput.withImages(Map.of("nested", List.of(image.reference())), image, image),
                Map.of("choice", choice, "score", score, "same", noul), "typesafe/jev-1.14", WireFormat.OPENROUTER);
        assertEquals(part, body.at("/state/nested/0"));
        assertEquals("key stays text", body.at("/questions/choice/instructions").path(image.reference()).textValue());
        assertEquals(part, body.at("/questions/choice/instructions/picture"));
        assertEquals(part, body.at("/questions/choice/criteria/same/examples/0"));
        assertEquals(part, body.at("/questions/score/instructions/1"));
        assertEquals(part, body.at("/questions/score/criteria/1"));
        assertEquals(part, body.at("/questions/same/criteria/true"));
        assertEquals(part, body.at("/questions/same/criteria/false/example"));
        var instructions = body.at("/questions/same/instructions");
        assertEquals(3, instructions.size());
        assertEquals("Before ", instructions.get(0).textValue());
        assertEquals(part, instructions.get(1));
        assertEquals(" after", instructions.get(2).textValue());
        assertFalse(body.has("assets"));
    }

    @Test void replacesRepeatedAndAdjacentReferencesInSentenceOrder() {
        var first = image();
        var second = JevImage.fromBytes(new byte[]{4, 5}, "jpeg");
        String text = "First " + first.reference() + second.reference() + " then " + first.reference() + ".";
        var body = EvaluationCodec.request(JevInput.withImages(text, first, second), Map.of("visible", VISIBLE),
                "typesafe/jev-1.14", WireFormat.OPENROUTER);
        assertEquals(JSON.createArrayNode().add("First ").add(imagePart(first)).add(imagePart(second))
                .add(" then ").add(imagePart(first)).add("."), body.path("state"));
        assertEquals("data:image/jpeg;base64,BAU=", body.at("/state/2/image_url/url").textValue());
    }

    @Test void preservesMissingConfidenceAndTextDefaultsAfterImageRequest() {
        var image = image();
        ObjectNode response = mixedResponse();
        ((ObjectNode) response.at("/answers/mug")).remove("confidence");
        var stub = new Stub(response);
        try (var client = OpenRouterJevClient.builder().apiKey("fixture-key").transport(stub).build()) {
            var result = client.evaluate(JevInput.withImages(image.reference(), image), VISIBLE, BOX);
            assertTrue(result.answer(BOX).confidence().isEmpty());
            assertEquals(JSON.createArrayNode().add(imagePart(image)), stub.body.path("state"));
            assertEquals("typesafe/jev-1.13", stub.body.path("model").textValue());
            stub.response = JSON.createObjectNode().set("answers", JSON.createObjectNode().set("visible",
                    JSON.createObjectNode().put("type", "noul").put("noul", .2)));
            client.evaluate("plain text", VISIBLE);
            assertEquals("plain text", stub.body.path("state").textValue());
            assertEquals("typesafe/jev-1.13", stub.body.path("model").textValue());
            assertFalse(stub.body.has("assets"));
            assertFalse(stub.headers.containsKey("TypeSafe-Danger-Zone-Images"));
        }
    }

    @Test void rejectsUnusedImageAndKeyOnlyReferenceBeforeTransport() {
        var image = image();
        var stub = new Stub(mixedResponse());
        try (var client = OpenRouterJevClient.builder().apiKey("fixture-key").transport(stub).build()) {
            assertThrows(IllegalArgumentException.class,
                    () -> client.evaluate(JevInput.withImages("plain state", image), VISIBLE));
            assertThrows(IllegalArgumentException.class,
                    () -> client.evaluate(JevInput.withImages(Map.of(image.reference(), "plain value"), image), VISIBLE));
            assertThrows(IllegalArgumentException.class, () -> client.evaluate("plain state", BOX));
            assertNull(stub.body);
        }
    }

    @Test void repeatedImagePartsCountTowardEncodedRequestLimitBeforeTransport() {
        var image = JevImage.fromBytes(new byte[8 * 1024 * 1024], "png");
        var stub = new Stub(mixedResponse());
        try (var client = OpenRouterJevClient.builder().apiKey("fixture-key").transport(stub).build()) {
            var input = JevInput.withImages(List.of(image.reference(), image.reference(), image.reference(), image.reference()), image);
            assertThrows(IllegalArgumentException.class, () -> client.evaluate(input, VISIBLE));
            assertNull(stub.body);
        }
    }

    @Test void acceptsEmbeddedRawCriteriaAfterTheirJsonValuesAreMaterialized() {
        var image = image();
        var raw = JSON.getNodeFactory().pojoNode(new com.fasterxml.jackson.databind.util.RawValue("\"" + image.reference() + "\""));
        var choice = ChoiceQuestion.of("choice", "Choose", Map.of("yes", raw, "no", "No"));
        var score = ScoreQuestion.of("score", "Score", List.of("Low", raw));
        var noul = NoulQuestion.of("same", "Same?").withCriteria(raw, "Different");
        var body = EvaluationCodec.request(JevInput.withImages("state", image),
                Map.of("choice", choice, "score", score, "same", noul), "typesafe/jev-1.14", WireFormat.OPENROUTER);
        assertEquals(imagePart(image), body.at("/questions/choice/criteria/yes"));
        assertEquals(imagePart(image), body.at("/questions/score/criteria/1"));
        assertEquals(imagePart(image), body.at("/questions/same/criteria/true"));
    }

    @Test void malformedBoxRemainsProtocolErrorAndCancellationPreservesTransportOwnership() {
        var image = image();
        var input = JevInput.withImages(image.reference(), image);
        ObjectNode response = mixedResponse();
        ((ObjectNode) response.at("/answers/mug/box")).put("right", .44);
        var stub = new Stub(response);
        var client = OpenRouterJevClient.builder().apiKey("fixture-key").transport(stub).build();
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
    private static ObjectNode imagePart(JevImage image) {
        var part = JSON.createObjectNode().put("type", "image_url");
        part.putObject("image_url").put("url", "data:image/" + (image.format().equals("png") ? "png" : "jpeg")
                + ";base64," + image.dataBase64());
        return part;
    }
    private static ObjectNode mixedResponse() {
        try {
            return (ObjectNode) JSON.readTree("""
                    {"id":"fixture-id","model":"typesafe/jev-1.14","answers":{
                      "visible":{"type":"noul","noul":0.95},
                      "mug":{"type":"box","box":{"top":0.25,"bottom":0.82,"left":0.44,"right":0.84},"confidence":0.92}
                    },"usage":{"input_tokens":100,"output_tokens":20,"cost":0.001}}
                    """);
        } catch (Exception failure) { throw new AssertionError(failure); }
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
