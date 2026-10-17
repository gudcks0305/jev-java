package io.github.gudcks0305.jev.typesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.RawValue;
import com.sun.net.httpserver.HttpServer;
import io.github.gudcks0305.jev.*;
import io.github.gudcks0305.jev.internal.EvaluationCodec;
import io.github.gudcks0305.jev.internal.WireFormat;
import io.github.gudcks0305.jev.schema.JevProbability;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic contract responses, never live inference or image accuracy evidence. */
class TypeSafeImagesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final NoulQuestion PRESENT = NoulQuestion.of("present", "Is a mug visible in `photo`?");
    private static final String NOUL = "{\"answers\":{\"present\":{\"type\":\"noul\",\"noul\":0.9}}}";
    record Presence(@JevProbability("Is a mug visible in `photo`?") double present) {}

    private static JevImage image() { return JevImage.fromBytes(new byte[]{1, 2, 3}, "png"); }

    @Test void sendsAssetsAndExperimentalHeaderOverHttpWithRetryAndBox() throws Exception {
        var image = image();
        var input = JevInput.withImages(Map.of("photo", image.reference()), image);
        var box = BoxQuestion.of("mug", "The mug in `photo`");
        var received = new AtomicReference<JsonNode>();
        var header = new AtomicReference<String>();
        var calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            received.set(JSON.readTree(exchange.getRequestBody()));
            header.set(exchange.getRequestHeaders().getFirst("TypeSafe-Danger-Zone-Images"));
            boolean retry = calls.incrementAndGet() == 1;
            byte[] response = (retry ? "{}" : """
                    {"model":"jev-1.14.0","answers":{
                      "present":{"type":"noul","noul":0.9},
                      "mug":{"type":"box","box":{"top":0.25,"bottom":0.82,"left":0.44,"right":0.84},"confidence":0.92}
                    }}
                    """).getBytes(StandardCharsets.UTF_8);
            if (retry) exchange.getResponseHeaders().add("Retry-After", "0");
            exchange.sendResponseHeaders(retry ? 429 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var client = TypeSafeJevClient.builder().apiKey("fixture-key").model("jev-1.14.0")
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .timeout(Duration.ofSeconds(5)).maxRetries(1).build()) {
            var result = client.evaluate(input, PRESENT, box);
            assertEquals(2, calls.get());
            assertEquals("true", header.get());
            assertEquals("jev-1.14.0", received.get().path("model").asText());
            assertEquals(image.reference(), received.get().at("/state/photo").asText());
            assertEquals("AQID", received.get().path("assets").path(image.reference()).path("data_base64").asText());
            assertEquals("png", received.get().path("assets").path(image.reference()).path("format").asText());
            assertEquals(.44, result.answer(box).box().left());
            assertEquals(.92, result.answer(box).confidence().orElseThrow());
            assertEquals(.9, result.answer(PRESENT).probability());
        } finally { server.stop(0); }
    }

    @Test void referencesWorkInsideInstructionsAndAllCriteriaWithoutReplacingKeys() {
        var image = image();
        var choice = ChoiceQuestion.of("choice", List.of("Match", Map.of("example", image.reference())),
                Map.of("yes", Map.of("examples", List.of(image.reference())), "no", "Different"));
        var score = ScoreQuestion.of("score", "Match strength", List.of("Different", image.reference()));
        var noul = NoulQuestion.of("same", "Matches " + image.reference() + "?")
                .withCriteria(image.reference(), "Different");
        var body = EvaluationCodec.request(JevInput.withImages("Compare photos", image, image),
                Map.of("choice", choice, "score", score, "same", noul), "jev-1.14.0", WireFormat.TYPESAFE);
        assertEquals(1, body.path("assets").size());
        assertEquals(image.reference(), body.at("/questions/choice/criteria/yes/examples/0").asText());
        assertEquals(image.reference(), body.at("/questions/score/criteria/1").asText());
        assertEquals(image.reference(), body.at("/questions/same/criteria/true").asText());
        assertEquals("Matches " + image.reference() + "?", body.at("/questions/same/instructions").asText());
    }

    @Test void rejectsUnreferencedAssetsIncludingReferencesOnlyInObjectKeysBeforeTransport() {
        var image = image();
        var stub = new TypeSafeJevClientTest.Stub();
        try (var client = TypeSafeJevClient.builder().apiKey("fixture-key").transport(stub).build()) {
            assertThrows(IllegalArgumentException.class,
                    () -> client.evaluate(JevInput.withImages(Map.of(image.reference(), "value"), image), PRESENT));
            assertThrows(IllegalArgumentException.class,
                    () -> client.evaluate("state", BoxQuestion.of("box", "Find mug")));
            assertNull(stub.body);
        }
    }

    @Test void rejectsUnsupportedProviderFormatsBeforeSerializingAssets() {
        var image = image();
        for (var format : WireFormat.values()) {
            if (format != WireFormat.VERCEL && format != WireFormat.VERCEL_DECISION && format != WireFormat.OPENAI) continue;
            assertThrows(IllegalArgumentException.class, () -> EvaluationCodec.request(
                    JevInput.withImages(image.reference(), image), Map.of("present", PRESENT), "model", format));
        }
    }

    @Test void rejectsBase64BodyOver32MiB() {
        var first = JevImage.fromBytes(new byte[13 * 1024 * 1024], "png");
        var second = JevImage.fromBytes(new byte[13 * 1024 * 1024], "jpg");
        var input = JevInput.withImages(List.of(first.reference(), second.reference()), first, second);
        var stub = new TypeSafeJevClientTest.Stub();
        try (var client = TypeSafeJevClient.builder().apiKey("fixture-key").transport(stub).build()) {
            assertThrows(IllegalArgumentException.class, () -> client.evaluate(input, PRESENT));
            assertNull(stub.body);
        }
    }

    @Test void rejectsImageRequestWhoseEscapedUnicodeExceedsActualWireLimit() {
        var image = image();
        // String-based JSON needs ~12 MB; Jackson's UTF-8 generator emits two
        // six-byte surrogate escapes per emoji, making the wire body ~36 MB.
        var input = JevInput.withImages(Map.of("photo", image.reference(),
                "note", "😀".repeat(3_000_000)), image);
        var stub = new TypeSafeJevClientTest.Stub();
        try (var client = TypeSafeJevClient.builder().apiKey("fixture-key").transport(stub).build()) {
            assertThrows(IllegalArgumentException.class, () -> client.evaluate(input, PRESENT));
            assertNull(stub.body);
        }
    }

    @Test void bodyLimitIsInclusiveAndCountsSupplementaryCharactersAsWireBytes() throws Exception {
        var image = image();
        var questions = Map.<String, Question<?>>of("present", PRESENT);
        var empty = JevInput.withImages(Map.of("photo", image.reference(), "note", ""), image);
        int overhead = JSON.writeValueAsBytes(EvaluationCodec.request(empty, questions,
                "jev-1.14.0", WireFormat.TYPESAFE)).length;
        int limit = 32 * 1024 * 1024;
        String atLimit = "a".repeat(limit - overhead - 12) + "😀";
        var accepted = EvaluationCodec.request(JevInput.withImages(
                Map.of("photo", image.reference(), "note", atLimit), image),
                questions, "jev-1.14.0", WireFormat.TYPESAFE);
        assertEquals(limit, JSON.writeValueAsBytes(accepted).length);
        assertThrows(IllegalArgumentException.class, () -> EvaluationCodec.request(
                JevInput.withImages(Map.of("photo", image.reference(), "note", atLimit + "a"), image),
                questions, "jev-1.14.0", WireFormat.TYPESAFE));
    }

    @Test void recognizesPojoReferencesInStateInstructionsAndCriteria() {
        var stateImage = image();
        var instructionsImage = image();
        var choiceImage = image();
        var scoreImage = image();
        var noulImage = image();
        var state = JSON.createObjectNode().putPOJO("photo", stateImage.reference());
        var instructions = JSON.createObjectNode().putPOJO("examples",
                Map.of("photos", List.of(instructionsImage.reference())));
        var choice = ChoiceQuestion.of("choice", instructions, Map.of("yes",
                JSON.createObjectNode().putPOJO("examples", List.of(choiceImage.reference()))));
        var score = ScoreQuestion.of("score", "Rate the match", List.of("none",
                JSON.createObjectNode().putPOJO("photo", scoreImage.reference())));
        var noul = NoulQuestion.of("noul", "Is it the same?").withCriteria(
                JSON.createObjectNode().putPOJO("photo", noulImage.reference()), "different");
        var body = EvaluationCodec.request(JevInput.withImages(state,
                stateImage, instructionsImage, choiceImage, scoreImage, noulImage),
                Map.of("choice", choice, "score", score, "noul", noul), "jev-1.14.0", WireFormat.TYPESAFE);
        assertEquals(5, body.path("assets").size());
        assertEquals(stateImage.reference(), body.at("/state/photo").textValue());
        assertEquals(instructionsImage.reference(), body.at("/questions/choice/instructions/examples/photos/0").textValue());
        assertEquals(choiceImage.reference(), body.at("/questions/choice/criteria/yes/examples/0").textValue());
        assertEquals(scoreImage.reference(), body.at("/questions/score/criteria/1/photo").textValue());
        assertEquals(noulImage.reference(), body.at("/questions/noul/criteria/true/photo").textValue());
    }

    @Test void recognizesRawJsonReferencesWithoutRoundingNumbers() {
        var stateImage = image();
        var instructionsImage = image();
        var criteriaImage = image();
        var decimal = new java.math.BigDecimal("0.12345678901234567890123456789");
        var integer = new java.math.BigInteger("123456789012345678901234567890123456789");
        var state = JSON.createObjectNode().put("decimal", decimal).put("integer", integer)
                .putRawValue("photo", new RawValue("\"" + stateImage.reference() + "\""))
                .putRawValue("rawNumbers", new RawValue("{\"decimal\":" + decimal + ",\"integer\":" + integer + "}"));
        var instructions = JSON.createObjectNode().putRawValue("photo",
                new RawValue("{\"nested\":[\"" + instructionsImage.reference() + "\"]}"));
        var criterion = JSON.createObjectNode().putRawValue("photo", new RawValue("\"" + criteriaImage.reference() + "\""));
        var question = ChoiceQuestion.of("match", instructions, Map.of("yes", criterion));
        var body = EvaluationCodec.request(JevInput.withImages(state, stateImage, instructionsImage, criteriaImage),
                Map.of("match", question), "jev-1.14.0", WireFormat.TYPESAFE);
        assertEquals(3, body.path("assets").size());
        assertEquals(stateImage.reference(), body.at("/state/photo").textValue());
        assertEquals(instructionsImage.reference(), body.at("/questions/match/instructions/photo/nested/0").textValue());
        assertEquals(criteriaImage.reference(), body.at("/questions/match/criteria/yes/photo").textValue());
        assertEquals(decimal, body.at("/state/decimal").decimalValue());
        assertEquals(integer, body.at("/state/integer").bigIntegerValue());
        assertEquals(decimal, body.at("/state/rawNumbers/decimal").decimalValue());
        assertEquals(integer, body.at("/state/rawNumbers/integer").bigIntegerValue());
    }

    @Test void malformedRawJsonDoesNotExposeItsContentsInErrors() {
        for (String raw : List.of("PRIVATE-INVALID-JSON", "123 true")) {
            var state = JSON.createObjectNode().putRawValue("private", new RawValue(raw));
            var failure = assertThrows(IllegalArgumentException.class, () -> JevInput.withImages(state, image()));
            assertEquals("Value cannot be represented as JSON", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test void headerDoesNotLeakIntoTextRequestsAndExistingRecordMappingWorks() throws Exception {
        var stub = new TypeSafeJevClientTest.Stub(JSON.readTree(NOUL));
        var image = image();
        try (var client = TypeSafeJevClient.builder().apiKey("fixture-key").transport(stub).build()) {
            var result = client.evaluate(JevInput.withImages(Map.of("photo", image.reference()), image), Presence.class);
            assertEquals(.9, result.value().present());
            assertEquals("true", stub.headers.get("TypeSafe-Danger-Zone-Images"));
            client.evaluate("ordinary text", PRESENT);
            assertFalse(stub.headers.containsKey("TypeSafe-Danger-Zone-Images"));
            assertFalse(stub.body.has("assets"));
            assertEquals("jev-latest", stub.body.path("model").asText());
        }
    }

    @Test void imageFutureCancellationAndCloseReachCallerOwnedTransport() {
        var image = image();
        var input = JevInput.withImages(image.reference(), image);
        var stub = new TypeSafeJevClientTest.Stub();
        var client = TypeSafeJevClient.builder().apiKey("fixture-key").transport(stub).build();
        assertTrue(client.evaluateAsync(input, PRESENT).cancel(true));
        assertTrue(stub.pending.isCancelled());
        stub.pending = new CompletableFuture<>();
        var next = client.evaluateAsync(input, PRESENT);
        client.close();
        assertTrue(next.isCompletedExceptionally());
        assertTrue(stub.pending.isCancelled());
        assertFalse(stub.closed);
    }
}
