package io.github.gudcks0305.jev;

public record NoulAnswer(double probability) implements Answer {
    public NoulAnswer {
        if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
            throw new IllegalArgumentException("Probability must be between 0 and 1");
        }
    }
    public boolean atLeast(double threshold) {
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("Threshold must be between 0 and 1");
        }
        return probability >= threshold;
    }
}
