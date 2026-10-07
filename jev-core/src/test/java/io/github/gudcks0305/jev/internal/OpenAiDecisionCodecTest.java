package io.github.gudcks0305.jev.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gudcks0305.jev.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiDecisionCodecTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    enum Team {
        BILLING { @Override public String toString() { return "not-a-wire-label"; } }, TECHNICAL
    }
    private static final NoulQuestion URGENT = NoulQuestion.of("urgent", "Is this urgent?");
    private static final ChoiceQuestion<Team> ROUTE = ChoiceQuestion.of("route", "Choose a team", Team.class);
    private static final ScoreQuestion SEVERITY = ScoreQuestion.of("severity", "How severe?", List.of("Low", "Medium", "High"));

    @Test void encodesOrderedQuestionsWithTextInputAndNativeLabels() throws Exception {
        Map<Team, Object> descriptions = new LinkedHashMap<>();
        descriptions.put(Team.BILLING, "Payments");
        descriptions.put(Team.TECHNICAL, null);
        var route = ROUTE.withDescriptions(descriptions);
        var body = request(JSON.readTree("\"ticket\""), questions(URGENT, route, SEVERITY));
        assertEquals(JSON.readTree("""
                {"model":"gpt-6-luna","input":"ticket","questions":[
                  {"name":"urgent","instructions":"Is this urgent?","type":"predicate"},
                  {"name":"route","instructions":"Choose a team","type":"choice","choices":[
                    {"value":"BILLING","description":"Payments"},{"value":"TECHNICAL"}]},
                  {"name":"severity","instructions":"How severe?","type":"score","levels":[
                    {"label":"Low"},{"label":"Medium"},{"label":"High"}]}]}
                """), body);
        assertEquals("ticket", request("ticket", questions(URGENT)).path("input").textValue());
    }

    @Test void keepsStringChoiceLabelsIncludingBooleanLookingStrings() {
        Map<String, Object> descriptions = new LinkedHashMap<>();
        descriptions.put("true", null);
        descriptions.put("false", "No");
        var choice = ChoiceQuestion.of("flag", "Choose", descriptions);
        var request = request("ticket", questions(choice));
        assertTrue(request.at("/questions/0/choices/0/value").isTextual());
        var response = responseWithAnswers("""
                [{"name":"flag","type":"choice","choice":"true","confidence":1,
                  "probabilities":[{"value":"true","probability":1},{"value":"false","probability":0}]}]
                """);
        assertEquals("true", decode(response, questions(choice)).answer(choice).choice());
        ((ObjectNode) response.at("/answers/0")).put("choice", true);
        assertProtocol(response, questions(choice));
    }

    @Test void mapsEveryAnswerAndPreservesNativeValuesAndRawMetadata() {
        ObjectNode body = validResponse();
        Evaluation result = decode(body, questions(URGENT, ROUTE, SEVERITY));
        assertEquals("gpt-6-luna-resolved", result.model());
        assertEquals(.91, result.answer(URGENT).probability());
        assertEquals(Team.BILLING, result.answer(ROUTE).choice());
        assertEquals(Map.of(Team.BILLING, .67, Team.TECHNICAL, .34), result.answer(ROUTE).probabilities());
        assertEquals(.34567, result.answer(ROUTE).confidence().orElseThrow());
        assertEquals(.9, result.answer(SEVERITY).score()); // Keep provider score even when distribution implies another value.
        assertEquals(Map.of(0, .1, 1, .2, 2, .7), result.answer(SEVERITY).probabilities());
        assertEquals(.81234, result.answer(SEVERITY).confidence().orElseThrow());
        assertEquals(SEVERITY.criteria().get(2), result.answer(SEVERITY).legend().get(2));
        assertEquals(0, result.usage().inputTokens().orElseThrow());
        assertEquals(0, result.usage().outputTokens().orElseThrow());
        assertEquals(body, result.rawResponse());
        assertEquals(3, result.rawResponse().at("/usage/input_tokens_details/cached_tokens").intValue());
        body.put("private_extension", "changed");
        assertEquals("preserved", result.rawResponse().path("private_extension").textValue());
        ((ObjectNode) result.rawResponse()).put("private_extension", "changed-again");
        assertEquals("preserved", result.rawResponse().path("private_extension").textValue());
    }

    @Test void acceptsProbabilityBoundariesAndReorderedDistributionsWithoutNormalization() {
        ObjectNode body = validResponse();
        ((ObjectNode) body.at("/answers/0")).put("probability", 0);
        ((ObjectNode) body.at("/answers/1")).put("confidence", 1);
        ((ObjectNode) body.at("/answers/2")).put("score", 2);
        ArrayNode choices = (ArrayNode) body.at("/answers/1/probabilities");
        JsonNode first = choices.remove(0);
        choices.add(first);
        ArrayNode levels = (ArrayNode) body.at("/answers/2/probabilities");
        JsonNode last = levels.remove(2);
        levels.insert(0, last);
        Evaluation result = decode(body, questions(URGENT, ROUTE, SEVERITY));
        assertEquals(0, result.answer(URGENT).probability());
        assertEquals(1, result.answer(ROUTE).confidence().orElseThrow());
        assertEquals(1.01, result.answer(ROUTE).probabilities().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12);
        assertEquals(2, result.answer(SEVERITY).score());
    }

    @ParameterizedTest(name = "invalid {0}: {1}")
    @MethodSource("malformedFields")
    void rejectsMalformedResponseFields(String path, String replacement) throws Exception {
        ObjectNode body = validResponse();
        int slash = path.lastIndexOf('/');
        JsonNode parent = body.at(path.substring(0, slash));
        String key = path.substring(slash + 1);
        if (parent instanceof ObjectNode object) {
            if (replacement.equals("<missing>")) object.remove(key);
            else object.set(key, JSON.readTree(replacement));
        } else {
            ArrayNode array = (ArrayNode) parent;
            if (replacement.equals("<missing>")) array.remove(Integer.parseInt(key));
            else array.set(Integer.parseInt(key), JSON.readTree(replacement));
        }
        assertProtocol(body, questions(URGENT, ROUTE, SEVERITY));
    }

    static Stream<Arguments> malformedFields() {
        return Stream.of(
                Arguments.of("/model", "<missing>"), Arguments.of("/model", "null"),
                Arguments.of("/model", "\" \""), Arguments.of("/model", "1"),
                Arguments.of("/usage", "<missing>"), Arguments.of("/usage", "null"), Arguments.of("/usage", "[]"),
                Arguments.of("/usage/input_tokens", "<missing>"), Arguments.of("/usage/input_tokens", "null"),
                Arguments.of("/usage/input_tokens", "-1"), Arguments.of("/usage/input_tokens", "0.5"),
                Arguments.of("/usage/input_tokens", "9223372036854775808"), Arguments.of("/usage/output_tokens", "\"1\""),
                Arguments.of("/usage/output_tokens", "<missing>"),
                Arguments.of("/answers", "<missing>"), Arguments.of("/answers", "{}"), Arguments.of("/answers", "[]"),
                Arguments.of("/answers/0", "null"), Arguments.of("/answers/0", "<missing>"),
                Arguments.of("/answers/0/name", "<missing>"), Arguments.of("/answers/0/name", "true"),
                Arguments.of("/answers/0/name", "\"route\""), Arguments.of("/answers/1/name", "\"urgent\""),
                Arguments.of("/answers/0/type", "<missing>"), Arguments.of("/answers/0/type", "null"),
                Arguments.of("/answers/0/type", "\"noul\""), Arguments.of("/answers/1/type", "\"predicate\""),
                Arguments.of("/answers/0/probability", "<missing>"), Arguments.of("/answers/0/probability", "true"),
                Arguments.of("/answers/0/probability", "-0.01"), Arguments.of("/answers/0/probability", "1.01"),
                Arguments.of("/answers/1/choice", "<missing>"), Arguments.of("/answers/1/choice", "true"),
                Arguments.of("/answers/1/choice", "\"UNKNOWN\""), Arguments.of("/answers/1/confidence", "<missing>"),
                Arguments.of("/answers/1/confidence", "null"), Arguments.of("/answers/1/confidence", "1.1"),
                Arguments.of("/answers/1/probabilities", "<missing>"), Arguments.of("/answers/1/probabilities", "{}"),
                Arguments.of("/answers/1/probabilities/0", "null"), Arguments.of("/answers/1/probabilities/0", "<missing>"),
                Arguments.of("/answers/1/probabilities/0/value", "true"),
                Arguments.of("/answers/1/probabilities/0/value", "\"TECHNICAL\""),
                Arguments.of("/answers/1/probabilities/0/value", "\"UNKNOWN\""),
                Arguments.of("/answers/1/probabilities/0/probability", "<missing>"),
                Arguments.of("/answers/1/probabilities/0/probability", "\"0.67\""),
                Arguments.of("/answers/2/score", "<missing>"), Arguments.of("/answers/2/score", "null"),
                Arguments.of("/answers/2/score", "-0.01"), Arguments.of("/answers/2/score", "2.01"),
                Arguments.of("/answers/2/confidence", "<missing>"), Arguments.of("/answers/2/confidence", "-0.1"),
                Arguments.of("/answers/2/probabilities", "<missing>"), Arguments.of("/answers/2/probabilities", "null"),
                Arguments.of("/answers/2/probabilities/0", "<missing>"),
                Arguments.of("/answers/2/probabilities/0/value", "<missing>"),
                Arguments.of("/answers/2/probabilities/0/value", "-1"), Arguments.of("/answers/2/probabilities/0/value", "3"),
                Arguments.of("/answers/2/probabilities/0/value", "1"), Arguments.of("/answers/2/probabilities/0/value", "0.0"),
                Arguments.of("/answers/2/probabilities/0/value", "2147483648"),
                Arguments.of("/answers/2/probabilities/0/label", "<missing>"),
                Arguments.of("/answers/2/probabilities/0/label", "\"wrong\""),
                Arguments.of("/answers/2/probabilities/0/probability", "1.1"));
    }

    @Test void rejectsNonfiniteNumbersAndExtraOrReorderedAnswers() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (String field : List.of("probability", "score", "confidence")) {
                ObjectNode body = validResponse();
                ((ObjectNode) body.at(field.equals("probability") ? "/answers/0" : "/answers/2")).put(field, value);
                assertProtocol(body, questions(URGENT, ROUTE, SEVERITY));
            }
        }
        ObjectNode extra = validResponse();
        ((ArrayNode) extra.path("answers")).add(extra.at("/answers/0").deepCopy());
        assertProtocol(extra, questions(URGENT, ROUTE, SEVERITY));
        ObjectNode reordered = validResponse();
        ArrayNode answers = (ArrayNode) reordered.path("answers");
        answers.add(answers.remove(0));
        assertProtocol(reordered, questions(URGENT, ROUTE, SEVERITY));
        assertProtocol(null, questions(URGENT));
        assertProtocol(JSON.createArrayNode(), questions(URGENT));
    }

    @Test void rejectsDuplicateScoreIndicesEvenWhenTheirLabelsMatch() {
        ObjectNode body = validResponse();
        ArrayNode distribution = (ArrayNode) body.at("/answers/2/probabilities");
        distribution.set(0, distribution.get(1).deepCopy());
        assertProtocol(body, questions(URGENT, ROUTE, SEVERITY));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void anyRefusalFailsWholeMixedEvaluation(int refusedIndex) {
        ObjectNode body = validResponse();
        ObjectNode refusal = (ObjectNode) body.at("/answers/" + refusedIndex);
        String name = refusal.path("name").textValue();
        refusal.removeAll();
        refusal.put("name", name).put("type", "refusal");
        // Unrelated invalid numeric content cannot turn a refusal into a default answer.
        if (refusedIndex != 0) ((ObjectNode) body.at("/answers/0")).remove("probability");
        var failure = assertThrows(JevException.class, () -> decode(body, questions(URGENT, ROUTE, SEVERITY)));
        assertEquals(JevException.Kind.REFUSAL, failure.kind());
        assertEquals(0, failure.statusCode());
        assertFalse(failure.getMessage().contains(name));
    }

    @Test void singleRefusalHasExplicitFailureKind() {
        var body = responseWithAnswers("[{\"name\":\"urgent\",\"type\":\"refusal\"}]");
        var failure = assertThrows(JevException.class, () -> decode(body, questions(URGENT)));
        assertEquals(JevException.Kind.REFUSAL, failure.kind());
    }

    @Test void rejectsUnsupportedRequestsWithoutStringifyingOrDroppingContent() {
        for (Object state : List.of(Map.of("ticket", "text"), List.of("text"), 1, Team.BILLING,
                JSON.createObjectNode(), JSON.createArrayNode())) {
            assertThrows(IllegalArgumentException.class, () -> request(state, questions(URGENT)));
        }
        assertThrows(IllegalArgumentException.class, () -> request(" ", questions(URGENT)));
        List<Question<?>> unsupported = List.of(
                NoulQuestion.of("q", Map.of("instruction", "check")),
                URGENT.withCriteria("yes", "no"), URGENT.withCriteria(null, null),
                ChoiceQuestion.of("q", "choose", Map.of("A", Map.of("detail", "structured"))),
                ChoiceQuestion.of("q", "choose", Map.of("A", List.of("structured"))),
                ChoiceQuestion.of("q", "choose", Map.of("A", true)),
                ScoreQuestion.of("q", "score", List.of("Low", Map.of("label", "High"))),
                ScoreQuestion.of("q", "score", List.of("Low", 1)),
                ScoreQuestion.of("q", "score", java.util.Arrays.asList("Low", null)),
                ScoreQuestion.of("q", "score", List.of("Low", " ")));
        for (Question<?> question : unsupported) {
            assertThrows(IllegalArgumentException.class, () -> request("ticket", questions(question)));
        }
    }

    private static ObjectNode validResponse() {
        return responseWithAnswers("""
                [{"type":"predicate","name":"urgent","probability":0.91},
                 {"type":"choice","name":"route","choice":"BILLING","confidence":0.34567,
                  "probabilities":[{"value":"BILLING","probability":0.67},{"value":"TECHNICAL","probability":0.34}]},
                 {"type":"score","name":"severity","score":0.9,"confidence":0.81234,
                  "probabilities":[{"value":0,"label":"Low","probability":0.1},
                    {"value":1,"label":"Medium","probability":0.2},{"value":2,"label":"High","probability":0.7}]}]
                """);
    }

    private static ObjectNode responseWithAnswers(String answers) {
        try {
            return (ObjectNode) JSON.readTree("""
                    {"model":"gpt-6-luna-resolved","usage":{"input_tokens":0,"output_tokens":0,
                      "input_tokens_details":{"cached_tokens":3}},"private_extension":"preserved","answers":
                    """ + answers + "}");
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static Map<String, Question<?>> questions(Question<?>... questions) {
        Map<String, Question<?>> result = new LinkedHashMap<>();
        for (Question<?> question : questions) result.put(question.id(), question);
        return result;
    }
    private static ObjectNode request(Object state, Map<String, Question<?>> questions) {
        return EvaluationCodec.request(state, questions, "gpt-6-luna", WireFormat.OPENAI);
    }
    private static Evaluation decode(JsonNode body, Map<String, Question<?>> questions) {
        return EvaluationCodec.response(body, questions, "requested-model", WireFormat.OPENAI);
    }
    private static void assertProtocol(JsonNode body, Map<String, Question<?>> questions) {
        assertEquals(JevException.Kind.PROTOCOL, assertThrows(JevException.class, () -> decode(body, questions)).kind());
    }
}
