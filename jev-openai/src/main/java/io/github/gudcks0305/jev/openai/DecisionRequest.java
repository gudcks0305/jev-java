package io.github.gudcks0305.jev.openai;

import java.util.List;
import java.util.Objects;

/** Immutable native request. Null model uses the client default; null safety identifier is omitted. */
public record DecisionRequest(DecisionInput input, List<DecisionQuestion> questions,
                              String model, String safetyIdentifier,
                              String promptCacheKey, DecisionCacheOptions promptCacheOptions) {
    public DecisionRequest {
        Objects.requireNonNull(input, "Decision input must not be null");
        questions = List.copyOf(questions);
        if (questions.isEmpty() || questions.size() > 400) throw new IllegalArgumentException("Between 1 and 400 questions are required");
        if (safetyIdentifier != null && safetyIdentifier.codePointCount(0, safetyIdentifier.length()) > 128) {
            throw new IllegalArgumentException("Safety identifier must contain at most 128 Unicode code points");
        }
        if (promptCacheKey != null && promptCacheKey.codePointCount(0, promptCacheKey.length()) > 64) {
            throw new IllegalArgumentException("Prompt cache key must contain at most 64 Unicode code points");
        }
        long images = DecisionInputCodec.imageCount(input);
        for (var question : questions) {
            if (question instanceof DecisionQuestion.Choice choice
                    && (choice.choices().size() < 2 || choice.choices().size() > 255)) {
                throw new IllegalArgumentException("OpenAI Choice requires 2 to 255 choices");
            }
            if (question instanceof DecisionQuestion.Score score
                    && (score.levels().size() < 2 || score.levels().size() > 10)) {
                throw new IllegalArgumentException("OpenAI Score requires 2 to 10 levels");
            }
            images += question.instructionParts().stream().filter(DecisionInput.ImagePart.class::isInstance).count();
        }
        if (images > 128) throw new IllegalArgumentException("At most 128 images are allowed per request");
    }

    public DecisionRequest(DecisionInput input, List<DecisionQuestion> questions, String model, String safetyIdentifier) {
        this(input, questions, model, safetyIdentifier, null, null);
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
        return "DecisionRequest[input=redacted, questions=" + questions.size() + ", model=redacted, safetyIdentifier=redacted, promptCacheKey=redacted]";
    }

    public static final class Builder {
        private DecisionInput input;
        private List<DecisionQuestion> questions;
        private String model;
        private String safetyIdentifier;
        private String promptCacheKey;
        private DecisionCacheOptions promptCacheOptions;

        private Builder() {}
        public Builder input(DecisionInput input) { this.input = input; return this; }
        public Builder input(String input) { return input(DecisionInput.text(input)); }
        public Builder questions(List<DecisionQuestion> questions) { this.questions = List.copyOf(questions); return this; }
        public Builder questions(DecisionQuestion... questions) { return questions(List.of(questions)); }
        public Builder model(String model) { this.model = model; return this; }
        public Builder safetyIdentifier(String safetyIdentifier) { this.safetyIdentifier = safetyIdentifier; return this; }
        public Builder promptCacheKey(String promptCacheKey) { this.promptCacheKey = promptCacheKey; return this; }
        public Builder promptCacheOptions(DecisionCacheOptions options) { this.promptCacheOptions = options; return this; }
        public DecisionRequest build() { return new DecisionRequest(input, questions, model, safetyIdentifier, promptCacheKey, promptCacheOptions); }
        @Override public String toString() { return "DecisionRequest.Builder[redacted]"; }
    }
}
