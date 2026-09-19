package io.github.gudcks0305.jev;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gudcks0305.jev.internal.JsonSupport;

/** A yes/no judgment whose answer is a probability, not an implicit boolean threshold. */
public final class NoulQuestion extends Question<NoulAnswer> {
    private final JsonNode criteria;
    private NoulQuestion(String id, Object instructions, JsonNode criteria) {
        super(id, instructions);
        this.criteria = criteria == null ? null : criteria.deepCopy();
    }
    public static NoulQuestion of(String id, Object instructions) { return new NoulQuestion(id, instructions, null); }
    public NoulQuestion withCriteria(Object yes, Object no) {
        var node = JsonSupport.object();
        node.set("true", JsonSupport.json(yes));
        node.set("false", JsonSupport.json(no));
        return new NoulQuestion(id(), instructions(), node);
    }
    public JsonNode criteria() { return criteria == null ? null : criteria.deepCopy(); }
}
