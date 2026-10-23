package io.github.gudcks0305.jev.openai;

/** Decisions cache controls. Null fields are omitted and retain provider defaults. */
public record DecisionCacheOptions(Mode mode, Ttl ttl) {
    public enum Mode { IMPLICIT, EXPLICIT }
    /** Decisions currently documents only a 30-minute minimum lifetime. */
    public enum Ttl { THIRTY_MINUTES }

    public DecisionCacheOptions(Mode mode) { this(mode, null); }
    public static DecisionCacheOptions explicit() { return new DecisionCacheOptions(Mode.EXPLICIT); }
    public static DecisionCacheOptions implicit() { return new DecisionCacheOptions(Mode.IMPLICIT); }
}
