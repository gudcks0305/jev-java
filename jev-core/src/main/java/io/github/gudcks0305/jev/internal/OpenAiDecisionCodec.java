package io.github.gudcks0305.jev.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gudcks0305.jev.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/** OpenAI Decisions mapping; preserves native judgments and rejects unsupported request content. */
final class OpenAiDecisionCodec {
    private OpenAiDecisionCodec() {}

    static ObjectNode request(Object state, Map<String, Question<?>> questions, String model) {
        if (!(state instanceof String || state instanceof JsonNode node && node.isTextual())) {
            throw new IllegalArgumentException("OpenAI input must be a string or textual JsonNode");
        }
        ObjectNode body = JsonSupport.object();
        body.put("model", model);
        body.set("input", JsonSupport.content(state, "OpenAI input"));
        var encoded = body.putArray("questions");
        questions.forEach((id, question) -> {
            JsonNode instructions = question.instructions();
            requireText(instructions, "OpenAI instructions");
            ObjectNode entry = encoded.addObject();
            entry.put("name", id);
            entry.set("instructions", instructions);
            if (question instanceof ChoiceQuestion<?> choice) {
                entry.put("type", "choice");
                var choices = entry.putArray("choices");
                JsonNode criteria = choice.criteria();
                choice.options().keySet().forEach(label -> {
                    ObjectNode option = choices.addObject().put("value", label);
                    JsonNode description = criteria.get(label);
                    if (!description.isNull()) {
                        if (!description.isTextual()) {
                            throw new IllegalArgumentException("OpenAI choice descriptions must be strings or null");
                        }
                        option.set("description", description);
                    }
                });
            } else if (question instanceof ScoreQuestion score) {
                entry.put("type", "score");
                var levels = entry.putArray("levels");
                for (JsonNode level : score.criteria()) {
                    requireText(level, "OpenAI score levels");
                    levels.addObject().set("label", level);
                }
            } else if (question instanceof NoulQuestion noul) {
                if (noul.criteria() != null) {
                    throw new IllegalArgumentException("OpenAI predicates do not support Noul criteria");
                }
                entry.put("type", "predicate");
            }
        });
        return body;
    }

    static Evaluation response(JsonNode body, Map<String, Question<?>> questions) {
        require(body != null && body.isObject(), "Expected an object response");
        JsonNode model = body.path("model");
        require(model.isTextual() && !model.textValue().isBlank(), "Missing or invalid response model");
        JsonNode usage = body.path("usage");
        require(usage.isObject(), "Missing or invalid token usage");
        Usage tokenUsage = new Usage(tokens(usage.path("input_tokens")), tokens(usage.path("output_tokens")));
        JsonNode wireAnswers = body.path("answers");
        require(wireAnswers.isArray() && wireAnswers.size() == questions.size(), "Answer count does not match requested questions");
        int index = 0;
        boolean refused = false;
        for (var entry : questions.entrySet()) {
            JsonNode wire = wireAnswers.get(index++);
            require(wire.isObject(), "Missing or invalid answer");
            require(wire.path("name").isTextual() && wire.path("name").textValue().equals(entry.getKey()),
                    "Answer names or order do not match requested questions");
            JsonNode type = wire.path("type");
            require(type.isTextual(), "Missing or invalid answer type");
            if (type.textValue().equals("refusal")) refused = true;
            else require(type.textValue().equals(wireType(entry.getValue())), "Answer type mismatch");
        }
        // A refusal cannot become a default value or a partially successful evaluation.
        if (refused) throw new JevException(JevException.Kind.REFUSAL, "OpenAI refused an evaluation question");
        Map<String, Answer> answers = new LinkedHashMap<>();
        index = 0;
        for (var entry : questions.entrySet()) {
            JsonNode wire = wireAnswers.get(index++);
            Question<?> question = entry.getValue();
            if (question instanceof NoulQuestion) {
                answers.put(entry.getKey(), new NoulAnswer(probability(wire.path("probability"))));
            } else if (question instanceof ChoiceQuestion<?> choice) {
                answers.put(entry.getKey(), choiceAnswer(choice, wire));
            } else if (question instanceof ScoreQuestion score) {
                answers.put(entry.getKey(), scoreAnswer(score, wire));
            }
        }
        return new Evaluation(model.textValue(), tokenUsage, questions, answers, body);
    }

    private static String wireType(Question<?> question) {
        return question instanceof NoulQuestion ? "predicate" : question instanceof ChoiceQuestion<?> ? "choice" : "score";
    }

    private static <T> ChoiceAnswer<T> choiceAnswer(ChoiceQuestion<T> question, JsonNode wire) {
        JsonNode label = wire.path("choice");
        require(label.isTextual() && question.options().containsKey(label.textValue()), "Missing or unknown choice label");
        JsonNode distribution = wire.path("probabilities");
        require(distribution.isArray() && distribution.size() == question.options().size(), "Choice distribution options mismatch");
        Map<T, Double> probabilities = new LinkedHashMap<>();
        for (JsonNode item : distribution) {
            require(item.isObject(), "Invalid choice distribution entry");
            JsonNode value = item.path("value");
            require(value.isTextual() && question.options().containsKey(value.textValue()), "Unknown choice distribution label");
            T option = question.options().get(value.textValue());
            require(!probabilities.containsKey(option), "Duplicate choice distribution label");
            probabilities.put(option, probability(item.path("probability")));
        }
        return new ChoiceAnswer<>(question.options().get(label.textValue()), probabilities,
                OptionalDouble.of(probability(wire.path("confidence"))));
    }

    private static ScoreAnswer scoreAnswer(ScoreQuestion question, JsonNode wire) {
        double value = number(wire.path("score"));
        require(value >= 0 && value <= question.levelCount() - 1, "Score outside declared levels");
        JsonNode distribution = wire.path("probabilities");
        require(distribution.isArray() && distribution.size() == question.levelCount(), "Score distribution levels mismatch");
        JsonNode criteria = question.criteria();
        Map<Integer, Double> probabilities = new LinkedHashMap<>();
        for (JsonNode item : distribution) {
            require(item.isObject(), "Invalid score distribution entry");
            JsonNode level = item.path("value");
            require(level.isIntegralNumber() && level.canConvertToInt(), "Invalid score distribution index");
            int index = level.intValue();
            require(index >= 0 && index < question.levelCount(), "Unknown score distribution index");
            require(!probabilities.containsKey(index), "Duplicate score distribution index");
            require(item.path("label").isTextual() && item.path("label").equals(criteria.get(index)),
                    "Score distribution label mismatch");
            probabilities.put(index, probability(item.path("probability")));
        }
        Map<Integer, JsonNode> legend = new LinkedHashMap<>();
        for (int i = 0; i < question.levelCount(); i++) legend.put(i, criteria.get(i));
        return new ScoreAnswer(value, probabilities, legend, OptionalDouble.of(probability(wire.path("confidence"))));
    }

    private static void requireText(JsonNode node, String field) {
        if (!node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be nonblank strings");
        }
    }
    private static OptionalLong tokens(JsonNode node) {
        require(node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0, "Missing or invalid token count");
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
