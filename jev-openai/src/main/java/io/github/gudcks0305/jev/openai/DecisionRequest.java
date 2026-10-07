package io.github.gudcks0305.jev.openai;

import java.util.List;
import java.util.Objects;

/** Immutable native request. Null model uses the client default; null safety identifier is omitted. */
public record DecisionRequest(DecisionInput input, List<DecisionQuestion> questions,
                              String model, String safetyIdentifier) {
    public DecisionRequest {
        Objects.requireNonNull(input, "Decision input must not be null");
        questions = List.copyOf(questions);
        if (questions.isEmpty()) throw new IllegalArgumentException("At least one question is required");
        if (safetyIdentifier != null && safetyIdentifier.codePointCount(0, safetyIdentifier.length()) > 128) {
            throw new IllegalArgumentException("Safety identifier must contain at most 128 Unicode code points");
        }
        int images = 0;
        if (input instanceof DecisionInput.Messages messages) {
            for (var message : messages.messages()) {
                if (message.content() instanceof DecisionInput.ContentParts content) {
                    for (var part : content.parts()) {
                        if (part instanceof DecisionInput.ImagePart && ++images > 128) {
                            throw new IllegalArgumentException("At most 128 images are allowed per request");
                        }
                    }
                }
            }
        }
    }

    public DecisionRequest(DecisionInput input, List<DecisionQuestion> questions) {
        this(input, questions, null, null);
    }
    public DecisionRequest(DecisionInput input, List<DecisionQuestion> questions, String model) {
        this(input, questions, model, null);
    }
    public DecisionRequest(String input, List<DecisionQuestion> questions) {
        this(DecisionInput.text(input), questions, null, null);
    }

    public static Builder builder() { return new Builder(); }

    @Override public String toString() {
        return "DecisionRequest[input=redacted, questions=" + questions.size() + ", model=redacted, safetyIdentifier=redacted]";
    }

    public static final class Builder {
        private DecisionInput input;
        private List<DecisionQuestion> questions;
        private String model;
        private String safetyIdentifier;

        private Builder() {}
        public Builder input(DecisionInput input) { this.input = input; return this; }
        public Builder input(String input) { return input(DecisionInput.text(input)); }
        public Builder questions(List<DecisionQuestion> questions) { this.questions = List.copyOf(questions); return this; }
        public Builder questions(DecisionQuestion... questions) { return questions(List.of(questions)); }
        public Builder model(String model) { this.model = model; return this; }
        public Builder safetyIdentifier(String safetyIdentifier) { this.safetyIdentifier = safetyIdentifier; return this; }
        public DecisionRequest build() { return new DecisionRequest(input, questions, model, safetyIdentifier); }
        @Override public String toString() { return "DecisionRequest.Builder[redacted]"; }
    }
}
