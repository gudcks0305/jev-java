package io.github.gudcks0305.jev.openai;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Ordered native questions; null names are omitted and names need not be unique. */
public sealed interface DecisionQuestion
        permits DecisionQuestion.Predicate, DecisionQuestion.Choice, DecisionQuestion.Score {
    String name();
    /** Text instructions, or null for a multipart question. */
    String instructions();
    /** Immutable instruction parts; empty for a text-only question. */
    List<DecisionInput.Part> instructionParts();

    record Predicate(String name, String instructions, List<DecisionInput.Part> instructionParts) implements DecisionQuestion {
        public Predicate { instructionParts = checkedInstructions(instructions, instructionParts); }
        public Predicate(String name, String instructions) { this(name, Objects.requireNonNull(instructions, "Instructions must not be null"), List.of()); }
        public Predicate(String instructions) { this(null, instructions); }
        public static Predicate multipart(String name, List<? extends DecisionInput.Part> parts) {
            return new Predicate(name, null, List.copyOf(parts));
        }
        public static Predicate multipart(List<? extends DecisionInput.Part> parts) { return multipart(null, parts); }
        @Override public String toString() { return "DecisionQuestion.Predicate[redacted]"; }
    }

    record Choice(String name, String instructions, List<Option> choices,
                  List<DecisionInput.Part> instructionParts) implements DecisionQuestion {
        public Choice {
            instructionParts = checkedInstructions(instructions, instructionParts);
            choices = List.copyOf(choices);
            if (choices.isEmpty()) throw new IllegalArgumentException("At least one choice is required");
            var values = new HashSet<DecisionValue>();
            for (Option choice : choices) {
                if (!values.add(choice.value())) throw new IllegalArgumentException("Choice values must be unique");
            }
        }
        public Choice(String name, String instructions, List<Option> choices) {
            this(name, Objects.requireNonNull(instructions, "Instructions must not be null"), choices, List.of());
        }
        public Choice(String instructions, List<Option> choices) { this(null, instructions, choices); }
        public static Choice multipart(String name, List<? extends DecisionInput.Part> parts, List<Option> choices) {
            return new Choice(name, null, choices, List.copyOf(parts));
        }
        public static Choice multipart(List<? extends DecisionInput.Part> parts, List<Option> choices) { return multipart(null, parts, choices); }
        @Override public String toString() { return "DecisionQuestion.Choice[choices=" + choices.size() + "]"; }
    }

    record Score(String name, String instructions, List<Level> levels,
                 List<DecisionInput.Part> instructionParts) implements DecisionQuestion {
        public Score {
            instructionParts = checkedInstructions(instructions, instructionParts);
            levels = List.copyOf(levels);
            if (levels.isEmpty()) throw new IllegalArgumentException("At least one score level is required");
        }
        public Score(String name, String instructions, List<Level> levels) {
            this(name, Objects.requireNonNull(instructions, "Instructions must not be null"), levels, List.of());
        }
        public Score(String instructions, List<Level> levels) { this(null, instructions, levels); }
        public static Score multipart(String name, List<? extends DecisionInput.Part> parts, List<Level> levels) {
            return new Score(name, null, levels, List.copyOf(parts));
        }
        public static Score multipart(List<? extends DecisionInput.Part> parts, List<Level> levels) { return multipart(null, parts, levels); }
        @Override public String toString() { return "DecisionQuestion.Score[levels=" + levels.size() + "]"; }
    }

    private static List<DecisionInput.Part> checkedInstructions(String text, List<DecisionInput.Part> parts) {
        List<DecisionInput.Part> snapshot = List.copyOf(parts);
        if (text != null && !snapshot.isEmpty()) {
            throw new IllegalArgumentException("Use text instructions or instruction parts, not both");
        }
        if (text == null && snapshot.isEmpty()) {
            throw new IllegalArgumentException("Instruction parts must not be empty");
        }
        for (var part : snapshot) {
            if (part instanceof DecisionInput.TextPart value) {
                if (value.breakpoint() != null) throw new IllegalArgumentException("Question instructions cannot contain cache breakpoints");
            } else if (part instanceof DecisionInput.ImagePart value) {
                if (value.breakpoint() != null) throw new IllegalArgumentException("Question instructions cannot contain cache breakpoints");
            } else if (!(part instanceof DecisionInput.JsonPart)) {
                throw new IllegalArgumentException("Question instructions support only input text, images, and JSON");
            }
        }
        return snapshot;
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
