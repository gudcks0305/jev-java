package io.github.gudcks0305.jev.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.gudcks0305.jev.JevException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Native wire contract, kept separate from the generic Jev compatibility mapping. */
final class NativeDecisionCodec {
    private NativeDecisionCodec() {}

    static ObjectNode request(DecisionRequest request, String defaultModel) {
        Objects.requireNonNull(request, "Decision request must not be null");
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("model", Objects.requireNonNull(request.model() == null ? defaultModel : request.model(),
                "Decision model must not be null"));
        if (request.safetyIdentifier() != null) body.put("safety_identifier", request.safetyIdentifier());
        if (request.input() instanceof DecisionInput.Text text) {
            body.put("input", text.text());
        } else if (request.input() instanceof DecisionInput.Messages messages) {
            ArrayNode wireMessages = body.putArray("input");
            for (var message : messages.messages()) {
                ObjectNode wire = wireMessages.addObject().put("role", "user");
                if (message.content() instanceof DecisionInput.MessageText text) {
                    wire.put("content", text.text());
                } else if (message.content() instanceof DecisionInput.ContentParts content) {
                    ArrayNode parts = wire.putArray("content");
                    for (var part : content.parts()) {
                        if (part instanceof DecisionInput.TextPart text) {
                            parts.addObject().put("type", "input_text").put("text", text.text());
                        } else if (part instanceof DecisionInput.ImagePart image) {
                            ObjectNode imagePart = parts.addObject().put("type", "input_image")
                                    .put("image_url", image.imageUrl());
                            if (image.detail() != null) imagePart.put("detail", image.detail().name().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        }
        ArrayNode questions = body.putArray("questions");
        for (DecisionQuestion question : request.questions()) {
            ObjectNode wire = questions.addObject().put("type", wireType(question))
                    .put("instructions", question.instructions());
            if (question.name() != null) wire.put("name", question.name());
            if (question instanceof DecisionQuestion.Choice choice) {
                ArrayNode choices = wire.putArray("choices");
                for (var option : choice.choices()) {
                    ObjectNode entry = choices.addObject();
                    entry.set("value", valueNode(option.value()));
                    if (option.description() != null) entry.put("description", option.description());
                }
            } else if (question instanceof DecisionQuestion.Score score) {
                ArrayNode levels = wire.putArray("levels");
                for (var level : score.levels()) {
                    ObjectNode entry = levels.addObject().put("label", level.label());
                    if (level.description() != null) entry.put("description", level.description());
                }
            }
        }
        return body;
    }

    static DecisionResult response(JsonNode body, DecisionRequest request) {
        Objects.requireNonNull(request, "Decision request must not be null");
        require(body != null && body.isObject(), "Expected an object decision response");
        require(body.path("model").isTextual(), "Missing or invalid decision model");
        DecisionUsage usage = usage(body.path("usage"));
        JsonNode wireAnswers = body.path("answers");
        require(wireAnswers.isArray() && wireAnswers.size() == request.questions().size(),
                "Decision answer count does not match questions");
        List<DecisionAnswer> answers = new ArrayList<>(wireAnswers.size());
        for (int i = 0; i < wireAnswers.size(); i++) {
            DecisionQuestion question = request.questions().get(i);
            JsonNode wire = wireAnswers.get(i);
            require(wire.isObject(), "Invalid decision answer");
            // Missing fields must not masquerade as an explicit JSON null for unnamed questions.
            JsonNode name = wire.get("name");
            require(name != null && (name.isNull() || name.isTextual()), "Missing or invalid decision answer name");
            String answerName = name.isNull() ? null : name.textValue();
            require(Objects.equals(question.name(), answerName), "Decision answer names or order mismatch");
            JsonNode type = wire.path("type");
            require(type.isTextual(), "Missing or invalid decision answer type");
            if ("refusal".equals(type.textValue())) {
                answers.add(new DecisionAnswer.Refusal(answerName));
                continue;
            }
            require(wireType(question).equals(type.textValue()), "Decision answer type mismatch");
            if (question instanceof DecisionQuestion.Predicate) {
                answers.add(new DecisionAnswer.Predicate(answerName, probability(wire.path("probability"))));
            } else if (question instanceof DecisionQuestion.Choice choice) {
                answers.add(choiceAnswer(answerName, wire, choice));
            } else if (question instanceof DecisionQuestion.Score score) {
                answers.add(scoreAnswer(answerName, wire, score));
            }
        }
        return new DecisionResult(body.path("model").textValue(), answers, usage, body);
    }

    private static DecisionAnswer.Choice choiceAnswer(String name, JsonNode wire, DecisionQuestion.Choice question) {
        Set<DecisionValue> declared = new HashSet<>();
        for (var option : question.choices()) declared.add(option.value());
        DecisionValue selected = value(wire.path("choice"));
        require(declared.contains(selected), "Unknown decision choice value");
        JsonNode distribution = wire.path("probabilities");
        require(distribution.isArray() && distribution.size() == declared.size(), "Decision choice distribution count mismatch");
        Set<DecisionValue> seen = new HashSet<>();
        List<DecisionAnswer.ChoiceProbability> probabilities = new ArrayList<>(distribution.size());
        for (JsonNode entry : distribution) {
            require(entry.isObject(), "Invalid decision choice distribution entry");
            DecisionValue item = value(entry.path("value"));
            require(declared.contains(item), "Unknown decision choice distribution value");
            require(seen.add(item), "Duplicate decision choice distribution value");
            probabilities.add(new DecisionAnswer.ChoiceProbability(item, probability(entry.path("probability"))));
        }
        return new DecisionAnswer.Choice(name, selected, probabilities, probability(wire.path("confidence")));
    }

    private static DecisionAnswer.Score scoreAnswer(String name, JsonNode wire, DecisionQuestion.Score question) {
        double score = number(wire.path("score"));
        require(score >= 0 && score <= question.levels().size() - 1, "Decision score outside declared levels");
        JsonNode distribution = wire.path("probabilities");
        require(distribution.isArray() && distribution.size() == question.levels().size(),
                "Decision score distribution count mismatch");
        Set<Integer> seen = new HashSet<>();
        List<DecisionAnswer.ScoreProbability> probabilities = new ArrayList<>(distribution.size());
        for (JsonNode entry : distribution) {
            require(entry.isObject(), "Invalid decision score distribution entry");
            JsonNode value = entry.path("value");
            require(value.isIntegralNumber() && value.canConvertToInt(), "Invalid decision score distribution index");
            int index = value.intValue();
            require(index >= 0 && index < question.levels().size(), "Unknown decision score distribution index");
            require(seen.add(index), "Duplicate decision score distribution index");
            JsonNode label = entry.path("label");
            require(label.isTextual() && label.textValue().equals(question.levels().get(index).label()),
                    "Decision score distribution label mismatch");
            probabilities.add(new DecisionAnswer.ScoreProbability(index, label.textValue(), probability(entry.path("probability"))));
        }
        return new DecisionAnswer.Score(name, score, probabilities, probability(wire.path("confidence")));
    }

    private static DecisionUsage usage(JsonNode usage) {
        require(usage.isObject(), "Missing or invalid decision token usage");
        JsonNode inputDetails = usage.path("input_tokens_details");
        JsonNode outputDetails = usage.path("output_tokens_details");
        require(inputDetails.isObject() && outputDetails.isObject(), "Missing or invalid decision token details");
        return new DecisionUsage(tokens(usage.path("input_tokens")), tokens(usage.path("output_tokens")),
                tokens(usage.path("total_tokens")), tokens(inputDetails.path("cached_tokens")),
                tokens(inputDetails.path("cache_write_tokens")), tokens(outputDetails.path("reasoning_tokens")));
    }

    private static long tokens(JsonNode node) {
        require(node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0,
                "Missing or invalid decision token count");
        return node.longValue();
    }
    private static String wireType(DecisionQuestion question) {
        return question instanceof DecisionQuestion.Predicate ? "predicate"
                : question instanceof DecisionQuestion.Choice ? "choice" : "score";
    }
    private static JsonNode valueNode(DecisionValue value) {
        return value instanceof DecisionValue.Text text ? TextNode.valueOf(text.value())
                : BooleanNode.valueOf(((DecisionValue.Bool) value).value());
    }
    private static DecisionValue value(JsonNode node) {
        require(node.isTextual() || node.isBoolean(), "Invalid decision choice value type");
        return node.isTextual() ? DecisionValue.text(node.textValue()) : DecisionValue.bool(node.booleanValue());
    }
    private static double number(JsonNode node) {
        require(node.isNumber() && Double.isFinite(node.doubleValue()), "Expected finite decision numeric value");
        return node.doubleValue();
    }
    private static double probability(JsonNode node) {
        double value = number(node);
        require(value >= 0 && value <= 1, "Decision probability outside [0, 1]");
        return value;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new JevException(JevException.Kind.PROTOCOL, message);
    }
}
