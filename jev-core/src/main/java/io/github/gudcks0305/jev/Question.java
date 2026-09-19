package io.github.gudcks0305.jev;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gudcks0305.jev.internal.JsonSupport;

/** An immutable question. Its identity is the type-safe handle used to retrieve its answer. */
public abstract sealed class Question<A extends Answer> permits ChoiceQuestion, NoulQuestion, ScoreQuestion {
    private final String id;
    private final JsonNode instructions;

    protected Question(String id, Object instructions) {
        this.id = JsonSupport.nonBlank(id, "Question id");
        this.instructions = JsonSupport.content(instructions, "Instructions");
    }
    public final String id() { return id; }
    public final JsonNode instructions() { return instructions.deepCopy(); }
}
