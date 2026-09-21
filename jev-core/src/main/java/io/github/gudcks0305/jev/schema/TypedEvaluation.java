package io.github.gudcks0305.jev.schema;

import io.github.gudcks0305.jev.Evaluation;
import java.util.Objects;

/** A mapped record together with the complete original answers, usage, and provider metadata. */
public record TypedEvaluation<T>(T value, Evaluation evaluation) {
    public TypedEvaluation {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(evaluation, "evaluation");
    }
}
