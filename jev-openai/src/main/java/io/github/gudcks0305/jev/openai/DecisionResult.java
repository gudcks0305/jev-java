package io.github.gudcks0305.jev.openai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/** Immutable native result with all provider metadata retained in a defensive JSON copy. */
public record DecisionResult(String model, List<DecisionAnswer> answers,
                             DecisionUsage usage, JsonNode rawResponse) {
    public DecisionResult {
        Objects.requireNonNull(model, "Model must not be null");
        answers = List.copyOf(answers);
        Objects.requireNonNull(usage, "Usage must not be null");
        rawResponse = Objects.requireNonNull(rawResponse, "Raw response must not be null").deepCopy();
    }

    @Override public JsonNode rawResponse() { return rawResponse.deepCopy(); }
    /** Position is authoritative, including when names are null or repeated. */
    public DecisionAnswer answer(int questionIndex) { return answers.get(questionIndex); }
    @Override public String toString() { return "DecisionResult[answers=" + answers.size() + ", usage=" + usage + "]"; }
}
