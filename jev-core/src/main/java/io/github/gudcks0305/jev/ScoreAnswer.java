package io.github.gudcks0305.jev;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/** Legend preserves structured level descriptions; empty probabilities means unavailable. */
public record ScoreAnswer(double score, Map<Integer, Double> probabilities,
                          Map<Integer, JsonNode> legend, OptionalDouble confidence) implements Answer {
    public ScoreAnswer {
        probabilities = Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));
        legend = copyLegend(legend);
        Objects.requireNonNull(confidence, "confidence");
    }
    @Override public Map<Integer, JsonNode> legend() { return copyLegend(legend); }
    private static Map<Integer, JsonNode> copyLegend(Map<Integer, JsonNode> source) {
        Map<Integer, JsonNode> result = new LinkedHashMap<>();
        source.forEach((k, v) -> result.put(k, v.deepCopy()));
        return Collections.unmodifiableMap(result);
    }
}
