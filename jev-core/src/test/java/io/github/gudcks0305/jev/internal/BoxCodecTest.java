package io.github.gudcks0305.jev.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gudcks0305.jev.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class BoxCodecTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BoxQuestion BOX = BoxQuestion.of("mug", "The coffee mug in photo");
    private static final NoulQuestion VISIBLE = NoulQuestion.of("visible", "Is the mug visible?");
    private static final ChoiceQuestion<String> COLOR = ChoiceQuestion.of("color", "Mug color", Map.of("white", "White"));
    private static final ScoreQuestion SIZE = ScoreQuestion.of("size", "Mug size", List.of("Small", "Large"));

    @Test void encodesBoxWithoutCriteriaAlongsideOtherQuestionTypes() throws Exception {
        var image = JevImage.fromBytes(new byte[]{1}, "png");
        var body = EvaluationCodec.request(JevInput.withImages(Map.of("photo", image.reference()), image), questions(BOX, VISIBLE, COLOR, SIZE),
                "jev-1.14.0", WireFormat.TYPESAFE);
        assertEquals(JSON.readTree("""
                {"type":"box","instructions":"The coffee mug in photo"}
                """), body.at("/questions/mug"));
        assertEquals(4, body.path("questions").size());
        assertEquals("noul", body.at("/questions/visible/type").textValue());
        assertEquals("choice", body.at("/questions/color/type").textValue());
        assertEquals("score", body.at("/questions/size/type").textValue());
    }

    @Test void preservesStructuredInstructionsDefensively() {
        ObjectNode instructions = JSON.createObjectNode().put("target", "mug");
        var box = BoxQuestion.of("box", instructions);
        instructions.put("target", "changed");
        ((ObjectNode) box.instructions()).put("target", "changed again");
        var image = JevImage.fromBytes(new byte[]{1}, "png");
        var body = EvaluationCodec.request(JevInput.withImages(image.reference(), image), questions(box), "jev-preview", WireFormat.TYPESAFE);
        assertEquals("mug", body.at("/questions/box/instructions/target").textValue());
        assertFalse(body.at("/questions/box").has("criteria"));
        assertThrows(IllegalArgumentException.class, () -> BoxQuestion.of(" ", "mug"));
        assertThrows(IllegalArgumentException.class, () -> BoxQuestion.of("box", " "));
        assertThrows(IllegalArgumentException.class, () -> BoxQuestion.of("box", 1));
        assertThrows(NullPointerException.class, () -> BoxQuestion.of("box", null));
    }

    @Test void decodesMixedAnswersAndKeepsCoordinatesConfidenceAndRawResponse() {
        ObjectNode body = validResponse();
        ObjectNode answers = (ObjectNode) body.path("answers");
        answers.putObject("visible").put("type", "noul").put("noul", .8);
        ObjectNode color = answers.putObject("color").put("type", "choice").put("choice", "white");
        color.putObject("probabilities").put("white", 1);
        ObjectNode size = answers.putObject("size").put("type", "score").put("score", .7);
        size.putObject("probabilities").put("0", .3).put("1", .7);
        var result = decode(body, questions(BOX, VISIBLE, COLOR, SIZE));
        assertEquals(new BoxCoordinates(.25, .82, .44, .84), result.answer(BOX).box());
        assertEquals(.92345, result.answer(BOX).confidence().orElseThrow());
        assertEquals(.8, result.answer(VISIBLE).probability());
        assertEquals("white", result.answer(COLOR).choice());
        assertEquals(.7, result.answer(SIZE).score());
        assertEquals(body, result.rawResponse());
        assertEquals("jev-1.14.0", result.model());
        assertThrows(IllegalArgumentException.class, () -> result.answer(BoxQuestion.of("mug", "same id")));
    }

    @Test void keepsMissingOrNullConfidenceAbsentAndAcceptsBoundaryEdges() {
        for (boolean explicitNull : List.of(false, true)) {
            ObjectNode body = validResponse();
            ObjectNode answer = (ObjectNode) body.at("/answers/mug");
            if (explicitNull) answer.putNull("confidence");
            else answer.remove("confidence");
            ObjectNode box = (ObjectNode) answer.path("box");
            box.put("top", 0).put("bottom", 1).put("left", 0).put("right", 1);
            var result = decode(body, questions(BOX));
            assertTrue(result.answer(BOX).confidence().isEmpty());
            assertEquals(new BoxCoordinates(0, 1, 0, 1), result.answer(BOX).box());
        }
        for (double confidence : new double[]{0, 1}) {
            ObjectNode body = validResponse();
            ((ObjectNode) body.at("/answers/mug")).put("confidence", confidence);
            assertEquals(confidence, decode(body, questions(BOX)).answer(BOX).confidence().orElseThrow());
        }
    }

    @ParameterizedTest(name = "invalid {0}: {1}")
    @MethodSource("malformedFields")
    void malformedBoxesAreProtocolFailures(String path, String replacement) throws Exception {
        ObjectNode body = validResponse();
        int slash = path.lastIndexOf('/');
        ObjectNode parent = (ObjectNode) body.at(path.substring(0, slash));
        String field = path.substring(slash + 1);
        if (replacement.equals("<missing>")) parent.remove(field);
        else parent.set(field, JSON.readTree(replacement));
        assertProtocol(body);
    }

    static Stream<Arguments> malformedFields() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (String value : List.of("<missing>", "null", "true", "\"noul\"", "{}", "1")) {
            cases.add(Arguments.of("/answers/mug/type", value));
        }
        for (String value : List.of("<missing>", "null", "[]", "\"box\"", "0")) {
            cases.add(Arguments.of("/answers/mug/box", value));
        }
        for (String edge : List.of("top", "bottom", "left", "right")) {
            for (String value : List.of("<missing>", "null", "true", "\"0.5\"", "{}", "-0.01", "1.01")) {
                cases.add(Arguments.of("/answers/mug/box/" + edge, value));
            }
        }
        cases.add(Arguments.of("/answers/mug/box/top", "0.82"));
        cases.add(Arguments.of("/answers/mug/box/top", "0.9"));
        cases.add(Arguments.of("/answers/mug/box/left", "0.84"));
        cases.add(Arguments.of("/answers/mug/box/left", "0.9"));
        for (String value : List.of("true", "\"0.5\"", "[]", "-0.01", "1.01")) {
            cases.add(Arguments.of("/answers/mug/confidence", value));
        }
        cases.add(Arguments.of("/answers/mug", "null"));
        cases.add(Arguments.of("/answers/mug", "<missing>"));
        return cases.build();
    }

    @Test void rejectsNonfiniteWireEdgesAndConfidence() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (String edge : List.of("top", "bottom", "left", "right")) {
                ObjectNode body = validResponse();
                ((ObjectNode) body.at("/answers/mug/box")).put(edge, value);
                assertProtocol(body);
            }
            ObjectNode body = validResponse();
            ((ObjectNode) body.at("/answers/mug")).put("confidence", value);
            assertProtocol(body);
        }
    }

    @ParameterizedTest
    @EnumSource(value = WireFormat.class, names = "TYPESAFE", mode = EnumSource.Mode.EXCLUDE)
    void rejectsBoxBeforeProviderEncodingIncludingOpenAiDelegation(WireFormat format) {
        var failure = assertThrows(IllegalArgumentException.class, () ->
                EvaluationCodec.request("photo", questions(VISIBLE, BOX), "model", format));
        assertEquals("Box questions require the TypeSafe wire format", failure.getMessage());
    }

    @Test void publicAnswerAndCoordinatesRejectInvalidValues() {
        var box = new BoxCoordinates(0, 1, 0, 1);
        assertThrows(NullPointerException.class, () -> new BoxAnswer(null, OptionalDouble.empty()));
        assertThrows(NullPointerException.class, () -> new BoxAnswer(box, null));
        for (double value : new double[]{-.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new BoxAnswer(box, OptionalDouble.of(value)));
            assertThrows(IllegalArgumentException.class, () -> new BoxCoordinates(value, 1, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> new BoxCoordinates(0, value, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> new BoxCoordinates(0, 1, value, 1));
            assertThrows(IllegalArgumentException.class, () -> new BoxCoordinates(0, 1, 0, value));
        }
        assertThrows(IllegalArgumentException.class, () -> new BoxCoordinates(.5, .5, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoxCoordinates(.9, .5, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoxCoordinates(0, 1, .5, .5));
        assertThrows(IllegalArgumentException.class, () -> new BoxCoordinates(0, 1, .9, .5));
    }

    private static ObjectNode validResponse() {
        try {
            return (ObjectNode) JSON.readTree("""
                    {"model":"jev-1.14.0","answers":{"mug":{"type":"box",
                      "box":{"top":0.25,"bottom":0.82,"left":0.44,"right":0.84},"confidence":0.92345}}}
                    """);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static Map<String, Question<?>> questions(Question<?>... questions) {
        Map<String, Question<?>> result = new LinkedHashMap<>();
        for (Question<?> question : questions) result.put(question.id(), question);
        return result;
    }

    private static Evaluation decode(JsonNode body, Map<String, Question<?>> questions) {
        return EvaluationCodec.response(body, questions, "jev-preview", WireFormat.TYPESAFE);
    }

    private static void assertProtocol(JsonNode body) {
        assertEquals(JevException.Kind.PROTOCOL,
                assertThrows(JevException.class, () -> decode(body, questions(BOX))).kind());
    }
}
