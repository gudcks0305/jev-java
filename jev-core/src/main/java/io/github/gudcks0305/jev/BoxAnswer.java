package io.github.gudcks0305.jev;

import java.util.Objects;
import java.util.OptionalDouble;

/** One bounding box; absent provider confidence remains absent. */
public record BoxAnswer(BoxCoordinates box, OptionalDouble confidence) implements Answer {
    public BoxAnswer {
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(confidence, "confidence");
        if (confidence.isPresent()) {
            double value = confidence.getAsDouble();
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException("Confidence must be between 0 and 1");
            }
        }
    }
}
