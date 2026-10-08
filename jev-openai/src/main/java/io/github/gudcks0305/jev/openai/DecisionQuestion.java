package io.github.gudcks0305.jev.openai;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Ordered native questions; null names are omitted and names need not be unique. */
public sealed interface DecisionQuestion
        permits DecisionQuestion.Predicate, DecisionQuestion.Choice, DecisionQuestion.Score {
    String name();
    String instructions();

    record Predicate(String name, String instructions) implements DecisionQuestion {
        public Predicate { Objects.requireNonNull(instructions, "Instructions must not be null"); }
        public Predicate(String instructions) { this(null, instructions); }
        @Override public String toString() { return "DecisionQuestion.Predicate[redacted]"; }
    }

    record Choice(String name, String instructions, List<Option> choices) implements DecisionQuestion {
        public Choice {
            Objects.requireNonNull(instructions, "Instructions must not be null");
            choices = List.copyOf(choices);
            if (choices.isEmpty()) throw new IllegalArgumentException("At least one choice is required");
            var values = new HashSet<DecisionValue>();
            for (Option choice : choices) {
                if (!values.add(choice.value())) throw new IllegalArgumentException("Choice values must be unique");
            }
        }
        public Choice(String instructions, List<Option> choices) { this(null, instructions, choices); }
        @Override public String toString() { return "DecisionQuestion.Choice[choices=" + choices.size() + "]"; }
    }

    record Score(String name, String instructions, List<Level> levels) implements DecisionQuestion {
        public Score {
            Objects.requireNonNull(instructions, "Instructions must not be null");
            levels = List.copyOf(levels);
            if (levels.isEmpty()) throw new IllegalArgumentException("At least one score level is required");
        }
        public Score(String instructions, List<Level> levels) { this(null, instructions, levels); }
        @Override public String toString() { return "DecisionQuestion.Score[levels=" + levels.size() + "]"; }
    }

    /** Null description is omitted; an empty description is sent unchanged. */
    record Option(DecisionValue value, String description) {
        public Option { Objects.requireNonNull(value, "Choice value must not be null"); }
        public Option(DecisionValue value) { this(value, null); }
        public Option(String value) { this(DecisionValue.text(value), null); }
        public Option(String value, String description) { this(DecisionValue.text(value), description); }
        public Option(boolean value) { this(DecisionValue.bool(value), null); }
        public Option(boolean value, String description) { this(DecisionValue.bool(value), description); }
        @Override public String toString() { return "DecisionQuestion.Option[redacted]"; }
    }

    /** Level identity is its zero-based position; labels may be repeated. */
    record Level(String label, String description) {
        public Level { Objects.requireNonNull(label, "Level label must not be null"); }
        public Level(String label) { this(label, null); }
        @Override public String toString() { return "DecisionQuestion.Level[redacted]"; }
    }
}
