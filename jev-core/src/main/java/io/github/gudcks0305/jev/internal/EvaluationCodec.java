package io.github.gudcks0305.jev.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gudcks0305.jev.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/** Provider-specific wire names are normalized here without changing judgment semantics. */
public final class EvaluationCodec {
    private EvaluationCodec() {}

    public static ObjectNode request(Object state, Map<String, Question<?>> questions, String model, boolean gateway) {
        ObjectNode body = JsonSupport.object();
        body.set("state", JsonSupport.content(state, "State"));
        if (!gateway) body.put("model", model);
        ObjectNode encoded = body.putObject("questions");
        questions.forEach((id, question) -> {
            ObjectNode entry = encoded.putObject(id);
            entry.set("instructions", question.instructions());
            if (question instanceof ChoiceQuestion<?> choice) {
                entry.put("type", "choice");
                entry.set("criteria", choice.criteria());
            } else if (question instanceof ScoreQuestion score) {
                entry.put("type", "score");
                entry.set("criteria", score.criteria());
            } else if (question instanceof NoulQuestion noul) {
                entry.put("type", gateway ? "boolean" : "noul");
                if (noul.criteria() != null) entry.set("criteria", noul.criteria());
            }
        });
        return body;
    }

    public static Evaluation response(JsonNode body, Map<String, Question<?>> questions, String requestedModel, boolean gateway) {
        require(body != null && body.isObject(), "Expected an object response");
        JsonNode wireAnswers = body.path("answers");
        require(wireAnswers.isObject() && wireAnswers.size() == questions.size(), "Answer ids do not match requested questions");
        Map<String, Answer> answers = new LinkedHashMap<>();
        questions.forEach((id, question) -> {
            JsonNode wire = wireAnswers.path(id);
            require(wire.isObject(), "Missing or invalid answer");
            JsonNode confidence = gateway ? body.path("providerMetadata").path("typesafe").path("confidence").path(id)
                    : wire.path("confidence");
            if (question instanceof NoulQuestion) {
                require(wire.path("type").asText().equals(gateway ? "boolean" : "noul"), "Answer type mismatch");
                answers.put(id, new NoulAnswer(probability(wire.path(gateway ? "probability" : "noul"))));
            } else if (question instanceof ChoiceQuestion<?> choice) {
                require(wire.path("type").asText().equals("choice"), "Answer type mismatch");
                answers.put(id, choiceAnswer(choice, wire, confidence, gateway));
            } else if (question instanceof ScoreQuestion score) {
                require(wire.path("type").asText().equals("score"), "Answer type mismatch");
                double value = number(wire.path("score"));
                require(value >= 0 && value <= score.levelCount() - 1, "Score outside declared levels");
                Map<Integer, Double> probabilities = new LinkedHashMap<>();
                Map<Integer, JsonNode> legend = new LinkedHashMap<>();
                JsonNode distribution = wire.path("probabilities");
                require(gateway || distribution.isObject(), "Missing score distribution");
                if (!distribution.isMissingNode()) {
                    require(distribution.isObject() && distribution.size() == score.levelCount(), "Score distribution levels mismatch");
                    for (int i = 0; i < score.levelCount(); i++) probabilities.put(i, probability(distribution.path(Integer.toString(i))));
                }
                JsonNode wireLegend = wire.path("legend");
                if (!wireLegend.isMissingNode()) {
                    require(wireLegend.isObject() && wireLegend.size() == score.levelCount(), "Score legend levels mismatch");
                }
                JsonNode criteria = score.criteria();
                for (int i = 0; i < score.levelCount(); i++) {
                    if (wireLegend.isMissingNode()) legend.put(i, criteria.get(i));
                    else {
                        require(wireLegend.has(Integer.toString(i)), "Score legend levels mismatch");
                        legend.put(i, wireLegend.get(Integer.toString(i)));
                    }
                }
                answers.put(id, new ScoreAnswer(value, probabilities, legend, confidence(confidence)));
            }
        });
        String model = requestedModel;
        if (body.has("model")) {
            require(body.path("model").isTextual() && !body.path("model").asText().isBlank(), "Invalid response model");
            model = body.path("model").textValue();
        }
        JsonNode usage = body.path("usage");
        require(usage.isMissingNode() || usage.isObject(), "Invalid token usage");
        Usage tokenUsage = new Usage(tokens(usage.path(gateway ? "inputTokens" : "input_tokens")),
                tokens(usage.path(gateway ? "outputTokens" : "output_tokens")));
        return new Evaluation(model, tokenUsage, questions, answers, body);
    }

    private static <T> ChoiceAnswer<T> choiceAnswer(ChoiceQuestion<T> question, JsonNode wire, JsonNode confidence, boolean gateway) {
        require(wire.path("choice").isTextual(), "Missing choice label");
        String label = wire.path("choice").textValue();
        require(question.options().containsKey(label), "Unknown choice label");
        Map<T, Double> probabilities = new LinkedHashMap<>();
        JsonNode distribution = wire.path("probabilities");
        require(gateway || distribution.isObject(), "Missing choice distribution");
        if (!distribution.isMissingNode()) {
            require(distribution.isObject() && distribution.size() == question.options().size(), "Choice distribution options mismatch");
            question.options().forEach((key, value) -> probabilities.put(value, probability(distribution.path(key))));
        }
        return new ChoiceAnswer<>(question.options().get(label), probabilities, confidence(confidence));
    }

    private static OptionalDouble confidence(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? OptionalDouble.empty() : OptionalDouble.of(probability(node));
    }
    private static OptionalLong tokens(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return OptionalLong.empty();
        require(node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0, "Invalid token count");
        return OptionalLong.of(node.longValue());
    }
    private static double probability(JsonNode node) {
        double value = number(node);
        require(value >= 0 && value <= 1, "Probability outside [0, 1]");
        return value;
    }
    private static double number(JsonNode node) {
        require(node.isNumber() && Double.isFinite(node.doubleValue()), "Expected finite numeric answer");
        return node.doubleValue();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new JevException(JevException.Kind.PROTOCOL, message);
    }
}
