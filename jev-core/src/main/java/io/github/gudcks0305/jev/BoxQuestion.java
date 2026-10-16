package io.github.gudcks0305.jev;

/** An experimental Jev 1.14 question locating one target within an image. */
public final class BoxQuestion extends Question<BoxAnswer> {
    private BoxQuestion(String id, Object instructions) {
        super(id, instructions);
    }

    public static BoxQuestion of(String id, Object instructions) {
        return new BoxQuestion(id, instructions);
    }
}
