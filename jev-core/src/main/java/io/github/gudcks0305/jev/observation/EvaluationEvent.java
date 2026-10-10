package io.github.gudcks0305.jev.observation;

import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.Usage;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Bounded, immutable metadata for one logical evaluation, including all transport retries.
 * No input, question content, response body, endpoint, headers, or exception is retained.
 * Missing provider model and usage values remain absent. An event is emitted only after
 * input validation and request encoding succeed; calls made after client closure are excluded.
 * An in-flight call completed by client closure emits FAILURE with CLOSED kind.
 */
public record EvaluationEvent(
        Duration elapsed,
        String requestedModel,
        Optional<String> returnedModel,
        int questionCount,
        Outcome outcome,
        Optional<Usage> usage,
        Optional<JevException.Kind> errorKind,
        OptionalInt statusCode) {

    public enum Outcome { SUCCESS, FAILURE, CANCELLED }

    public EvaluationEvent {
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(requestedModel, "requestedModel");
        Objects.requireNonNull(returnedModel, "returnedModel");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(errorKind, "errorKind");
        Objects.requireNonNull(statusCode, "statusCode");
        if (elapsed.isNegative() || questionCount < 1) throw new IllegalArgumentException("Invalid evaluation metadata");
    }
}
