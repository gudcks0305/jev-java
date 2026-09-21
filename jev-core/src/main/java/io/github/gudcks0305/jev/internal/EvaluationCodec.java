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
        return request(state, questions, model, gateway ? WireFormat.VERCEL : WireFormat.TYPESAFE);
    }

    public static ObjectNode request(Object state, Map<String, Question<?>> questions, String model, WireFormat format) {
        boolean gateway = format == WireFormat.VERCEL;
        boolean cloudflare = format == WireFormat.CLOUDFLARE;
        ObjectNode body = JsonSupport.object();
        ObjectNode input = body;
        if (cloudflare) {
            body.put("model", model);
            input = body.putObject("input");
        }
        input.set("state", JsonSupport.content(state, "State"));
        if (!gateway && !cloudflare) body.put("model", model);
        ObjectNode encoded = input.putObject("questions");
        questions.forEach((id, question) -> {
            if (format == WireFormat.OPENROUTER) validateOpenRouterCriteria(question);
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
                JsonNode criteria = noul.criteria();
                if (criteria != null && !(format == WireFormat.OPENROUTER
                        && criteria.path("true").isNull() && criteria.path("false").isNull())) {
                    entry.set("criteria", criteria);
                }
            }
        });
        return body;
    }

    public static Evaluation response(JsonNode body, Map<String, Question<?>> questions, String requestedModel, boolean gateway) {
        return response(body, questions, requestedModel, gateway ? WireFormat.VERCEL : WireFormat.TYPESAFE);
    }

    public static Evaluation response(JsonNode body, Map<String, Question<?>> questions, String requestedModel, WireFormat format) {
        boolean gateway = format == WireFormat.VERCEL;
        boolean optionalProbabilities = format == WireFormat.VERCEL || format == WireFormat.OPENROUTER;
        require(body != null && body.isObject(), "Expected an object response");
        JsonNode normalized = body;
        if (format == WireFormat.CLOUDFLARE && isCloudflareEnvelope(body)) {
            require(body.path("success").isBoolean(), "Invalid Cloudflare response envelope");
            require(body.path("success").booleanValue(), "Cloudflare request failed");
            require(body.path("result").isObject(), "Invalid Cloudflare response result");
            normalized = body.path("result");
        }
        JsonNode payload = normalized;
        JsonNode wireAnswers = payload.path("answers");
        require(wireAnswers.isObject() && wireAnswers.size() == questions.size(), "Answer ids do not match requested questions");
        Map<String, Answer> answers = new LinkedHashMap<>();
        questions.forEach((id, question) -> {
            JsonNode wire = wireAnswers.path(id);
            require(wire.isObject(), "Missing or invalid answer");
            JsonNode confidence = gateway ? payload.path("providerMetadata").path("typesafe").path("confidence").path(id)
                    : wire.path("confidence");
            if (question instanceof NoulQuestion) {
                require(wire.path("type").asText().equals(gateway ? "boolean" : "noul"), "Answer type mismatch");
                answers.put(id, new NoulAnswer(probability(wire.path(gateway ? "probability" : "noul"))));
            } else if (question instanceof ChoiceQuestion<?> choice) {
                require(wire.path("type").asText().equals("choice"), "Answer type mismatch");
                answers.put(id, choiceAnswer(choice, wire, confidence, optionalProbabilities));
            } else if (question instanceof ScoreQuestion score) {
                require(wire.path("type").asText().equals("score"), "Answer type mismatch");
                double value = number(wire.path("score"));
                require(value >= 0 && value <= score.levelCount() - 1, "Score outside declared levels");
                Map<Integer, Double> probabilities = new LinkedHashMap<>();
                Map<Integer, JsonNode> legend = new LinkedHashMap<>();
                JsonNode distribution = wire.path("probabilities");
                require(optionalProbabilities || distribution.isObject(), "Missing score distribution");
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
                        JsonNode description = wireLegend.get(Integer.toString(i));
                        if (format == WireFormat.OPENROUTER) {
                            require(description.isTextual() || description.isObject() || description.isArray(),
                                    "Invalid OpenRouter score legend description");
                        }
                        legend.put(i, description);
                    }
                }
                answers.put(id, new ScoreAnswer(value, probabilities, legend, confidence(confidence)));
            }
        });
        String model = requestedModel;
        if (payload.has("model")) {
            require(payload.path("model").isTextual() && !payload.path("model").asText().isBlank(), "Invalid response model");
            model = payload.path("model").textValue();
        }
        JsonNode usage = payload.path("usage");
        require(usage.isMissingNode() || usage.isObject(), "Invalid token usage");
        Usage tokenUsage = new Usage(tokens(usage.path(gateway ? "inputTokens" : "input_tokens")),
                tokens(usage.path(gateway ? "outputTokens" : "output_tokens")));
        return new Evaluation(model, tokenUsage, questions, answers, body);
    }

    private static boolean isCloudflareEnvelope(JsonNode body) {
        return body.has("success") || body.has("result") || body.has("errors") || body.has("messages");
    }

    private static <T> ChoiceAnswer<T> choiceAnswer(ChoiceQuestion<T> question, JsonNode wire, JsonNode confidence, boolean optionalProbabilities) {
        require(wire.path("choice").isTextual(), "Missing choice label");
        String label = wire.path("choice").textValue();
        require(question.options().containsKey(label), "Unknown choice label");
        Map<T, Double> probabilities = new LinkedHashMap<>();
        JsonNode distribution = wire.path("probabilities");
        require(optionalProbabilities || distribution.isObject(), "Missing choice distribution");
        if (!distribution.isMissingNode()) {
            require(distribution.isObject() && distribution.size() == question.options().size(), "Choice distribution options mismatch");
            question.options().forEach((key, value) -> probabilities.put(value, probability(distribution.path(key))));
        }
        return new ChoiceAnswer<>(question.options().get(label), probabilities, confidence(confidence));
    }

    private static OptionalDouble confidence(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? OptionalDouble.empty() : OptionalDouble.of(probability(node));
    }
    private static void validateOpenRouterCriteria(Question<?> question) {
        if (question instanceof ChoiceQuestion<?> choice) {
            choice.criteria().elements().forEachRemaining(value -> requireCriterion(value, true));
        } else if (question instanceof ScoreQuestion score) {
            score.criteria().elements().forEachRemaining(value -> requireCriterion(value, false));
        } else if (question instanceof NoulQuestion noul && noul.criteria() != null) {
            JsonNode criteria = noul.criteria();
            JsonNode yes = criteria.path("true");
            JsonNode no = criteria.path("false");
            if (yes.isNull() && no.isNull()) return;
            requireCriterion(yes, false);
            requireCriterion(no, false);
        }
    }
    private static void requireCriterion(JsonNode node, boolean allowNull) {
        if (!(node.isTextual() || node.isObject() || node.isArray() || (allowNull && node.isNull()))) {
            throw new IllegalArgumentException("OpenRouter criteria must be strings, objects, or arrays"
                    + (allowNull ? " (null is also allowed for choice descriptions)" : ""));
        }
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
