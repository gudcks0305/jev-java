package io.github.gudcks0305.jev;

import java.util.OptionalLong;

/** Absent provider usage stays absent; it is never reported as zero. */
public record Usage(OptionalLong inputTokens, OptionalLong outputTokens) {}
