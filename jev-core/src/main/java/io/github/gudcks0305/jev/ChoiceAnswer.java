package io.github.gudcks0305.jev;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/** Empty probabilities means the provider did not return a distribution. */
public record ChoiceAnswer<T>(T choice, Map<T, Double> probabilities, OptionalDouble confidence) implements Answer {
    public ChoiceAnswer {
        Objects.requireNonNull(choice, "choice");
        probabilities = Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));
        Objects.requireNonNull(confidence, "confidence");
    }
}
