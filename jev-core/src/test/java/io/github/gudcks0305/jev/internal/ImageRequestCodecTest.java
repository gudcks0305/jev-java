package io.github.gudcks0305.jev.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import io.github.gudcks0305.jev.JevImage;
import io.github.gudcks0305.jev.JevInput;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.Question;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class ImageRequestCodecTest {
    private static final NoulQuestion QUESTION = NoulQuestion.of("visible", "Is the object visible?");

    @ParameterizedTest
    @EnumSource(value = WireFormat.class, names = {"TYPESAFE", "OPENROUTER", "CLOUDFLARE"})
    void enforcesExactUtf8BodyLimitIncludingProviderEnvelope(WireFormat format) {
        var image = JevImage.fromBytes(new byte[]{1}, "png");
        var questions = Map.<String, Question<?>>of("visible", QUESTION);
        var empty = JevInput.withImages(Map.of("photo", image.reference(), "note", ""), image);
        int overhead = JsonSupport.utf8Bytes(EvaluationCodec.request(empty, questions, "model", format)).length;
        int limit = 32 * 1024 * 1024;
        String text = "a".repeat(limit - overhead - 12) + "😀";
        var input = JevInput.withImages(Map.of("photo", image.reference(), "note", text), image);
        assertEquals(limit, JsonSupport.utf8Bytes(EvaluationCodec.request(input, questions, "model", format)).length);
        assertThrows(IllegalArgumentException.class, () -> EvaluationCodec.request(
                JevInput.withImages(Map.of("photo", image.reference(), "note", text + "a"), image),
                questions, "model", format));
    }

    @Test void oneInputAndQuestionCanBeReusedAcrossProviderEncodings() {
        var image = JevImage.fromBytes(new byte[]{1}, "png");
        var input = JevInput.withImages(Map.of("photo", image.reference()), image);
        var question = NoulQuestion.of("same", "Compare " + image.reference());
        var questions = Map.<String, Question<?>>of("same", question);
        JsonNode initialState = input.state();
        JsonNode initialInstructions = question.instructions();
        var router = EvaluationCodec.request(input, questions, "model", WireFormat.OPENROUTER);
        var cloudflare = EvaluationCodec.request(input, questions, "model", WireFormat.CLOUDFLARE);
        var direct = EvaluationCodec.request(input, questions, "model", WireFormat.TYPESAFE);
        ((ObjectNode) router.at("/state/photo")).removeAll();
        ((ObjectNode) cloudflare.path("input")).removeAll();
        assertEquals(initialState, input.state());
        assertEquals(initialInstructions, question.instructions());
        assertEquals(image.reference(), direct.at("/state/photo").textValue());
        assertEquals("image_url", EvaluationCodec.request(input, questions, "model", WireFormat.OPENROUTER)
                .at("/state/photo/type").textValue());
    }

    @ParameterizedTest
    @EnumSource(value = WireFormat.class, names = {"OPENROUTER", "CLOUDFLARE"})
    void materializesPojoAndRawReferencesBeforeProviderEncoding(WireFormat format) {
        var image = JevImage.fromBytes(new byte[]{1}, "png");
        var state = JsonSupport.object().putPOJO("photo", image.reference());
        var instruction = JsonSupport.object().putRawValue("photo", new RawValue("\"" + image.reference() + "\""));
        var rawCriterion = instruction.get("photo");
        var question = NoulQuestion.of("same", instruction).withCriteria(rawCriterion, "Different");
        var body = EvaluationCodec.request(JevInput.withImages(state, image), Map.of("same", question), "model", format);
        JsonNode content = format == WireFormat.CLOUDFLARE ? body.path("input") : body;
        if (format == WireFormat.OPENROUTER) {
            assertEquals("image_url", content.at("/state/photo/type").textValue());
            assertEquals("image_url", content.at("/questions/same/instructions/photo/type").textValue());
            assertEquals("image_url", content.at("/questions/same/criteria/true/type").textValue());
        } else {
            assertEquals(image.reference(), content.at("/state/photo").textValue());
            assertEquals(image.reference(), content.at("/questions/same/instructions/photo").textValue());
            assertEquals(image.reference(), content.at("/questions/same/criteria/true").textValue());
            assertTrue(content.path("assets").has(image.reference()));
        }
    }
}
