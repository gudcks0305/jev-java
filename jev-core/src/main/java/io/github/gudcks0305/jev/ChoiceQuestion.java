package io.github.gudcks0305.jev;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gudcks0305.jev.internal.JsonSupport;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Selects one string label or enum constant, preserving the complete returned distribution. */
public final class ChoiceQuestion<T> extends Question<ChoiceAnswer<T>> {
    private final Map<String, T> options;
    private final JsonNode criteria;

    private ChoiceQuestion(String id, Object instructions, Map<String, T> options, JsonNode criteria) {
        super(id, instructions);
        if (options.isEmpty()) throw new IllegalArgumentException("Choice needs at least one option");
        options.keySet().forEach(key -> JsonSupport.nonBlank(key, "Option label"));
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
        this.criteria = criteria.deepCopy();
    }

    public static <E extends Enum<E>> ChoiceQuestion<E> of(String id, Object instructions, Class<E> type) {
        Objects.requireNonNull(type, "Enum type is required");
        Map<String, E> options = new LinkedHashMap<>();
        var criteria = JsonSupport.object();
        for (E value : type.getEnumConstants()) {
            options.put(value.name(), value);
            criteria.putNull(value.name());
        }
        return new ChoiceQuestion<>(id, instructions, options, criteria);
    }

    /** Criteria maps labels to descriptions (string/structured JSON) or null. */
    public static ChoiceQuestion<String> of(String id, Object instructions, Map<String, ?> criteria) {
        Objects.requireNonNull(criteria, "Criteria is required");
        Map<String, String> options = new LinkedHashMap<>();
        criteria.keySet().forEach(key -> options.put(key, key));
        return new ChoiceQuestion<>(id, instructions, options, JsonSupport.json(criteria));
    }

    /** Returns a new question with descriptions for every option. Use the new question to retrieve answers. */
    public ChoiceQuestion<T> withDescriptions(Map<T, ?> descriptions) {
        Objects.requireNonNull(descriptions, "Descriptions are required");
        if (descriptions.size() != options.size() || !descriptions.keySet().containsAll(options.values())) {
            throw new IllegalArgumentException("Descriptions must cover exactly the declared choices");
        }
        var node = JsonSupport.object();
        options.forEach((label, value) -> node.set(label, JsonSupport.json(descriptions.get(value))));
        return new ChoiceQuestion<>(id(), instructions(), options, node);
    }

    public Map<String, T> options() { return options; }
    public JsonNode criteria() { return criteria.deepCopy(); }
}
