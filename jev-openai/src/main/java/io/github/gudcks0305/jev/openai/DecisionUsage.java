package io.github.gudcks0305.jev.openai;

/** All required native token counters, preserved without deriving or changing totals. */
public record DecisionUsage(long inputTokens, long outputTokens, long totalTokens,
                            long cachedTokens, long cacheWriteTokens, long reasoningTokens) {
    public DecisionUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0
                || cachedTokens < 0 || cacheWriteTokens < 0 || reasoningTokens < 0) {
            throw new IllegalArgumentException("Token counts must be nonnegative");
        }
    }
}
