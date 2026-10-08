package io.github.gudcks0305.jev.openai;

import java.util.Objects;

/** A native choice value. Text {@code "true"} and boolean {@code true} are distinct. */
public sealed interface DecisionValue permits DecisionValue.Text, DecisionValue.Bool {
    static Text text(String value) { return new Text(value); }
    static Bool bool(boolean value) { return new Bool(value); }

    record Text(String value) implements DecisionValue {
        public Text { Objects.requireNonNull(value, "Choice text must not be null"); }
        @Override public String toString() { return "DecisionValue.Text[redacted]"; }
    }

    record Bool(boolean value) implements DecisionValue {}
}
