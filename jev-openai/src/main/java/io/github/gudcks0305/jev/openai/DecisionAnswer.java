package io.github.gudcks0305.jev.openai;

import java.util.List;
import java.util.Objects;

/** Native answers in question order. A refusal does not discard successful sibling answers. */
public sealed interface DecisionAnswer
        permits DecisionAnswer.Predicate, DecisionAnswer.Choice, DecisionAnswer.Score, DecisionAnswer.Refusal {
    String name();

    record Predicate(String name, double probability) implements DecisionAnswer {
        public Predicate { checkProbability(probability); }
    }

    record Choice(String name, DecisionValue choice, List<ChoiceProbability> probabilities,
                  double confidence) implements DecisionAnswer {
        public Choice {
            Objects.requireNonNull(choice, "Choice must not be null");
            probabilities = List.copyOf(probabilities);
            checkProbability(confidence);
        }
    }

    /** Provider's weighted level index; never rounded or recomputed from probabilities. */
    record Score(String name, double score, List<ScoreProbability> probabilities,
                 double confidence) implements DecisionAnswer {
        public Score {
            if (!Double.isFinite(score) || score < 0) throw new IllegalArgumentException("Invalid score");
            probabilities = List.copyOf(probabilities);
            checkProbability(confidence);
        }
    }

    record Refusal(String name) implements DecisionAnswer {}

    record ChoiceProbability(DecisionValue value, double probability) {
        public ChoiceProbability {
            Objects.requireNonNull(value, "Choice value must not be null");
            checkProbability(probability);
        }
    }

    record ScoreProbability(int value, String label, double probability) {
        public ScoreProbability {
            if (value < 0) throw new IllegalArgumentException("Score index must be nonnegative");
            Objects.requireNonNull(label, "Score label must not be null");
            checkProbability(probability);
        }
    }

    private static void checkProbability(double probability) {
        if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
            throw new IllegalArgumentException("Probability must be finite and within [0, 1]");
        }
    }
}
