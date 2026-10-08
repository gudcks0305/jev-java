package io.github.gudcks0305.jev.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gudcks0305.jev.JevException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic contract fixtures. No credentials or live inference. */
class NativeDecisionCodecTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IMAGE = "data:image/png;base64,AQ==";
    private static final String USAGE = """
            {"input_tokens":42,"output_tokens":4,"total_tokens":46,
             "input_tokens_details":{"cached_tokens":7,"cache_write_tokens":2},
             "output_tokens_details":{"reasoning_tokens":3}}
            """;

    @Test
    void encodesAllQuestionsTypedChoiceValuesDescriptionsAndOptionalFields() throws Exception {
        var request = new DecisionRequest(DecisionInput.text(""), List.of(
                new DecisionQuestion.Predicate(""),
                new DecisionQuestion.Choice("same", "", List.of(
                        new DecisionQuestion.Option("true", ""), new DecisionQuestion.Option(true))),
                new DecisionQuestion.Score("same", "", List.of(
                        new DecisionQuestion.Level("", ""), new DecisionQuestion.Level("high")))), "override", "opaque");
        assertEquals(JSON.readTree("""
                {"model":"override","input":"","safety_identifier":"opaque","questions":[
                 {"type":"predicate","instructions":""},
                 {"type":"choice","name":"same","instructions":"","choices":[
                   {"value":"true","description":""},{"value":true}]},
                 {"type":"score","name":"same","instructions":"","levels":[
                   {"label":"","description":""},{"label":"high"}]}]}
                """), NativeDecisionCodec.request(request, "default"));
        var defaults = NativeDecisionCodec.request(new DecisionRequest("", List.of(new DecisionQuestion.Predicate("", ""))), "default");
        assertEquals("default", defaults.path("model").textValue());
        assertEquals("", defaults.at("/questions/0/name").textValue());
        assertFalse(defaults.has("safety_identifier"));
        assertEquals("", NativeDecisionCodec.request(new DecisionRequest(DecisionInput.text(""),
                List.of(new DecisionQuestion.Predicate("")), "", ""), "default").path("model").textValue());
    }

    @Test
    void encodesUserStringsOrderedPartsAndEveryDetail() {
        var parts = new ArrayList<DecisionInput.Part>();
        parts.add(new DecisionInput.TextPart(""));
        parts.add(new DecisionInput.ImagePart(IMAGE));
        for (var detail : DecisionInput.Detail.values()) parts.add(new DecisionInput.ImagePart(IMAGE, detail));
        var input = DecisionInput.messages(new DecisionInput.UserMessage(""), new DecisionInput.UserMessage(parts));
        var wire = NativeDecisionCodec.request(new DecisionRequest(input,
                List.of(new DecisionQuestion.Predicate(""))), "model");
        assertEquals("user", wire.at("/input/0/role").textValue());
        assertEquals("", wire.at("/input/0/content").textValue());
        assertEquals("user", wire.at("/input/1/role").textValue());
        assertEquals("input_text", wire.at("/input/1/content/0/type").textValue());
        assertEquals("", wire.at("/input/1/content/0/text").textValue());
        assertEquals("input_image", wire.at("/input/1/content/1/type").textValue());
        assertEquals(IMAGE, wire.at("/input/1/content/1/image_url").textValue());
        assertFalse(wire.at("/input/1/content/1").has("detail"));
        assertEquals("low", wire.at("/input/1/content/2/detail").textValue());
        assertEquals("high", wire.at("/input/1/content/3/detail").textValue());
        assertEquals("auto", wire.at("/input/1/content/4/detail").textValue());
        assertEquals("original", wire.at("/input/1/content/5/detail").textValue());
    }

    @Test
    void decodesEveryAnswerAndKeepsSiblingResultsOnRefusal() throws Exception {
        var request = mixedRequest();
        ObjectNode body = mixedResponse();
        var result = NativeDecisionCodec.response(body, request);
        assertEquals("gpt-6-luna", result.model());
        assertEquals(new DecisionAnswer.Predicate(null, .125), result.answer(0));
        var choice = assertInstanceOf(DecisionAnswer.Choice.class, result.answer(1));
        assertEquals(DecisionValue.bool(true), choice.choice());
        assertEquals(.61, choice.confidence());
        // Keep distribution order and exact provider numbers; do not normalize a partial sum.
        assertEquals(List.of(new DecisionAnswer.ChoiceProbability(DecisionValue.bool(true), .17),
                new DecisionAnswer.ChoiceProbability(DecisionValue.text("true"), .22)), choice.probabilities());
        var score = assertInstanceOf(DecisionAnswer.Score.class, result.answer(2));
        assertEquals(.6, score.score());
        assertEquals(.78, score.confidence());
        assertEquals(List.of(new DecisionAnswer.ScoreProbability(1, "high", .2),
                new DecisionAnswer.ScoreProbability(0, "", .5)), score.probabilities());
        assertEquals(new DecisionAnswer.Refusal("refused"), result.answer(3));
        assertEquals(new DecisionUsage(42, 4, 46, 7, 2, 3), result.usage());
        assertEquals("kept", result.rawResponse().at("/future_metadata/value").textValue());
        assertEquals(7.5, result.rawResponse().at("/usage/compute_units").doubleValue());
    }

    @Test
    void namesCanBeNullOrRepeatedAndPositionIsAuthoritative() throws Exception {
        var request = new DecisionRequest("", List.of(new DecisionQuestion.Predicate("dup", "first"),
                new DecisionQuestion.Predicate("dup", "second"), new DecisionQuestion.Predicate("third")));
        var result = NativeDecisionCodec.response(response("""
                [{"name":"dup","type":"predicate","probability":0.2},
                 {"name":"dup","type":"predicate","probability":0.8},
                 {"name":null,"type":"refusal"}]
                """), request);
        assertEquals(.2, ((DecisionAnswer.Predicate) result.answer(0)).probability());
        assertEquals(.8, ((DecisionAnswer.Predicate) result.answer(1)).probability());
        assertEquals(new DecisionAnswer.Refusal(null), result.answer(2));
    }

    @Test
    void acceptsSingleScoreLevelAndRepeatedLabelsWithoutRecomputingScore() throws Exception {
        var request = new DecisionRequest("", List.of(new DecisionQuestion.Score("", List.of(new DecisionQuestion.Level("")))));
        var result = NativeDecisionCodec.response(response("""
                [{"name":null,"type":"score","score":0,"confidence":0,
                  "probabilities":[{"value":0,"label":"","probability":0.2}]}]
                """), request);
        assertEquals(0, ((DecisionAnswer.Score) result.answer(0)).score());
        var repeats = new DecisionRequest("", List.of(new DecisionQuestion.Score("", List.of(
                new DecisionQuestion.Level("same"), new DecisionQuestion.Level("same")))));
        assertDoesNotThrow(() -> NativeDecisionCodec.response(response("""
                [{"name":null,"type":"score","score":0.7,"confidence":1,
                  "probabilities":[{"value":0,"label":"same","probability":0.4},
                    {"value":1,"label":"same","probability":0.6}]}]
                """), repeats));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"name\":7,\"type\":\"predicate\",\"probability\":0.5}",
            "{\"name\":\"wrong\",\"type\":\"refusal\"}",
            "{\"name\":null,\"type\":\"choice\",\"probability\":0.5}",
            "{\"name\":null,\"type\":\"unknown\"}",
            "{\"name\":null,\"type\":\"predicate\",\"probability\":\"0.5\"}",
            "{\"name\":null,\"type\":\"predicate\",\"probability\":-0.1}",
            "{\"name\":null,\"type\":\"predicate\",\"probability\":1.1}"})
    void rejectsInvalidAnswerShapesWithoutPayloadLeak(String answer) throws Exception {
        var request = new DecisionRequest("sensitive-input", List.of(new DecisionQuestion.Predicate("")));
        protocolFailure(response("[" + answer + "]"), request);
    }

    @Test
    void missingNameIsDifferentFromExplicitNullAndNamedQuestionCannotReceiveNull() throws Exception {
        var unnamed = new DecisionRequest("", List.of(new DecisionQuestion.Predicate("")));
        protocolFailure(response("[{\"type\":\"refusal\"}]"), unnamed);
        var named = new DecisionRequest("", List.of(new DecisionQuestion.Predicate("expected", "")));
        protocolFailure(response("[{\"name\":null,\"type\":\"refusal\"}]"), named);
        assertDoesNotThrow(() -> NativeDecisionCodec.response(response("[{\"name\":null,\"type\":\"refusal\"}]"), unnamed));
    }

    @Test
    void rejectsCountOrderAndMissingModel() throws Exception {
        var request = mixedRequest();
        ObjectNode body = mixedResponse();
        ((ArrayNode) body.get("answers")).remove(0);
        protocolFailure(body, request);
        body = mixedResponse();
        var answers = (ArrayNode) body.get("answers");
        JsonNode first = answers.get(0);
        answers.set(0, answers.get(1));
        answers.set(1, first);
        protocolFailure(body, request);
        body = mixedResponse();
        body.remove("model");
        protocolFailure(body, request);
        body = mixedResponse();
        body.putNull("model");
        protocolFailure(body, request);
        protocolFailure(null, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"input_tokens", "output_tokens", "total_tokens",
            "input_tokens_details/cached_tokens", "input_tokens_details/cache_write_tokens",
            "output_tokens_details/reasoning_tokens"})
    void everyUsageCounterIsRequiredNonnegativeIntegralAndLongRepresentable(String path) throws Exception {
        for (String invalid : List.of("null", "-1", "1.5", "\"1\"", "true", "9223372036854775808")) {
            ObjectNode body = mixedResponse();
            putUsage(body, path, JSON.readTree(invalid));
            protocolFailure(body, mixedRequest());
        }
        ObjectNode body = mixedResponse();
        String[] segments = path.split("/");
        ObjectNode parent = segments.length == 1 ? (ObjectNode) body.path("usage")
                : (ObjectNode) body.path("usage").path(segments[0]);
        parent.remove(segments[segments.length - 1]);
        protocolFailure(body, mixedRequest());
    }

    @Test
    void rejectsMissingNestedUsageAndPreservesExplicitTotals() throws Exception {
        for (String field : List.of("input_tokens_details", "output_tokens_details")) {
            ObjectNode body = mixedResponse();
            ((ObjectNode) body.path("usage")).remove(field);
            protocolFailure(body, mixedRequest());
            body = mixedResponse();
            ((ObjectNode) body.path("usage")).putNull(field);
            protocolFailure(body, mixedRequest());
        }
        ObjectNode body = mixedResponse();
        body.remove("usage");
        protocolFailure(body, mixedRequest());
        body = mixedResponse();
        ((ObjectNode) body.path("usage")).put("total_tokens", 99);
        assertEquals(99, NativeDecisionCodec.response(body, mixedRequest()).usage().totalTokens());
    }

    @ParameterizedTest
    @ValueSource(strings = {"7", "null", "\"not-declared\"", "false"})
    void rejectsUnknownOrWrongTypeSelectedChoice(String value) throws Exception {
        ObjectNode body = mixedResponse();
        ((ObjectNode) body.at("/answers/1")).set("choice", JSON.readTree(value));
        protocolFailure(body, mixedRequest());
    }

    @Test
    void rejectsIncompleteDuplicateUnknownChoiceDistributionsAndMissingConfidence() throws Exception {
        ObjectNode body = mixedResponse();
        ((ArrayNode) body.at("/answers/1/probabilities")).remove(0);
        protocolFailure(body, mixedRequest());
        body = mixedResponse();
        ((ObjectNode) body.at("/answers/1/probabilities/1")).put("value", true);
        protocolFailure(body, mixedRequest());
        body = mixedResponse();
        ((ObjectNode) body.at("/answers/1/probabilities/1")).put("value", false);
        protocolFailure(body, mixedRequest());
        body = mixedResponse();
        ((ObjectNode) body.at("/answers/1")).remove("confidence");
        protocolFailure(body, mixedRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.01", "null", "\"0.5\"", "1e400"})
    void rejectsInvalidProbabilityOrConfidenceForEveryNumericAnswer(String value) throws Exception {
        for (String pointer : List.of("/answers/0/probability", "/answers/1/confidence",
                "/answers/1/probabilities/0/probability", "/answers/2/confidence", "/answers/2/probabilities/0/probability")) {
            ObjectNode body = mixedResponse();
            int lastSlash = pointer.lastIndexOf('/');
            ((ObjectNode) body.at(pointer.substring(0, lastSlash))).set(pointer.substring(lastSlash + 1), JSON.readTree(value));
            protocolFailure(body, mixedRequest());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.1", "1.1", "null", "\"0.5\"", "1e400"})
    void rejectsInvalidScoreRangeAndNonfiniteNumbers(String value) throws Exception {
        ObjectNode body = mixedResponse();
        ((ObjectNode) body.at("/answers/2")).set("score", JSON.readTree(value));
        protocolFailure(body, mixedRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "2", "0.0", "\"0\"", "2147483648", "null"})
    void rejectsUnknownFractionalOrWrongTypeScoreIndices(String index) throws Exception {
        ObjectNode body = mixedResponse();
        ((ObjectNode) body.at("/answers/2/probabilities/0")).set("value", JSON.readTree(index));
        protocolFailure(body, mixedRequest());
    }

    @Test
    void rejectsDuplicateMissingAndMislabeledScoreEntries() throws Exception {
        ObjectNode body = mixedResponse();
        ((ObjectNode) body.at("/answers/2/probabilities/0")).put("value", 0).put("label", "");
        protocolFailure(body, mixedRequest());
        body = mixedResponse();
        ((ArrayNode) body.at("/answers/2/probabilities")).remove(0);
        protocolFailure(body, mixedRequest());
        body = mixedResponse();
        ((ObjectNode) body.at("/answers/2/probabilities/0")).put("label", "wrong");
        protocolFailure(body, mixedRequest());
        body = mixedResponse();
        ((ObjectNode) body.at("/answers/2/probabilities/0")).remove("label");
        protocolFailure(body, mixedRequest());
    }

    @Test
    void resultSnapshotsRawJsonAnswersAndDistributions() throws Exception {
        ObjectNode body = mixedResponse();
        var result = NativeDecisionCodec.response(body, mixedRequest());
        body.put("model", "changed");
        ((ObjectNode) body.path("future_metadata")).put("value", "changed");
        ((ObjectNode) result.rawResponse()).removeAll();
        assertEquals("gpt-6-luna", result.model());
        assertEquals("kept", result.rawResponse().at("/future_metadata/value").textValue());
        assertThrows(UnsupportedOperationException.class, () -> result.answers().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ((DecisionAnswer.Choice) result.answer(1)).probabilities().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ((DecisionAnswer.Score) result.answer(2)).probabilities().clear());
        var mutableAnswers = new ArrayList<>(result.answers());
        var copied = new DecisionResult(result.model(), mutableAnswers, result.usage(), result.rawResponse());
        mutableAnswers.clear();
        assertEquals(4, copied.answers().size());
    }

    private static DecisionRequest mixedRequest() {
        return new DecisionRequest("sensitive-input", List.of(new DecisionQuestion.Predicate(""),
                new DecisionQuestion.Choice("same", "", List.of(new DecisionQuestion.Option("true"), new DecisionQuestion.Option(true))),
                new DecisionQuestion.Score("same", "", List.of(new DecisionQuestion.Level(""), new DecisionQuestion.Level("high"))),
                new DecisionQuestion.Predicate("refused", "")));
    }

    private static ObjectNode mixedResponse() throws Exception {
        ObjectNode body = response("""
                [{"name":null,"type":"predicate","probability":0.125},
                 {"name":"same","type":"choice","choice":true,"confidence":0.61,
                  "probabilities":[{"value":true,"probability":0.17},{"value":"true","probability":0.22}]},
                 {"name":"same","type":"score","score":0.6,"confidence":0.78,
                  "probabilities":[{"value":1,"label":"high","probability":0.2},{"value":0,"label":"","probability":0.5}]},
                 {"name":"refused","type":"refusal"}]
                """);
        body.putObject("future_metadata").put("value", "kept");
        ((ObjectNode) body.path("usage")).put("compute_units", 7.5);
        return body;
    }

    private static ObjectNode response(String answers) throws Exception {
        return (ObjectNode) JSON.readTree("{\"model\":\"gpt-6-luna\",\"answers\":" + answers + ",\"usage\":" + USAGE + "}");
    }

    private static void putUsage(ObjectNode body, String path, JsonNode value) {
        String[] segments = path.split("/");
        ObjectNode parent = segments.length == 1 ? (ObjectNode) body.path("usage")
                : (ObjectNode) body.path("usage").path(segments[0]);
        parent.set(segments[segments.length - 1], value);
    }

    private static void protocolFailure(JsonNode body, DecisionRequest request) {
        JevException failure = assertThrows(JevException.class, () -> NativeDecisionCodec.response(body, request));
        assertEquals(JevException.Kind.PROTOCOL, failure.kind());
        assertFalse(failure.getMessage().contains("sensitive-input"));
        assertFalse(failure.getMessage().contains("wrong"));
    }
}
