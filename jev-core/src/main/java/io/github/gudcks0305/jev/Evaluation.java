package io.github.gudcks0305.jev;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable result with the complete provider payload available for forward-compatible metadata. */
public final class Evaluation {
    private final String model;
    private final Usage usage;
    private final Map<String, Question<?>> questions;
    private final Map<String, Answer> answers;
    private final JsonNode rawResponse;

    public Evaluation(String model, Usage usage, Map<String, Question<?>> questions,
                      Map<String, Answer> answers, JsonNode rawResponse) {
        this.model = model;
        this.usage = usage;
        this.questions = Collections.unmodifiableMap(new LinkedHashMap<>(questions));
        this.answers = Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        this.rawResponse = rawResponse.deepCopy();
    }
    public String model() { return model; }
    public Usage usage() { return usage; }
    public Map<String, Answer> answers() { return answers; }
    public JsonNode rawResponse() { return rawResponse.deepCopy(); }

    @SuppressWarnings("unchecked")
    public <A extends Answer> A answer(Question<A> question) {
        if (questions.get(question.id()) != question) {
            throw new IllegalArgumentException("Use the same question instance submitted in this evaluation");
        }
        return (A) answers.get(question.id());
    }
}
