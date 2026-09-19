package io.github.gudcks0305.jev;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gudcks0305.jev.internal.JsonSupport;
import java.util.List;
import java.util.Objects;

/** Score is a weighted zero-based level index, not an automatically normalized percentage. */
public final class ScoreQuestion extends Question<ScoreAnswer> {
    private final JsonNode criteria;
    private ScoreQuestion(String id, Object instructions, List<?> levels) {
        super(id, instructions);
        if (Objects.requireNonNull(levels, "Levels are required").size() < 2) {
            throw new IllegalArgumentException("Score needs at least two ordered levels");
        }
        this.criteria = JsonSupport.json(levels);
    }
    public static ScoreQuestion of(String id, Object instructions, List<?> levels) {
        return new ScoreQuestion(id, instructions, levels);
    }
    public JsonNode criteria() { return criteria.deepCopy(); }
    public int levelCount() { return criteria.size(); }
}
