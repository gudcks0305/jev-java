package io.github.gudcks0305.jev.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/** Internal JSON conversion; deliberately does not enable polymorphic deserialization. */
public final class JsonSupport {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private JsonSupport() {}

    public static JsonNode json(Object value) {
        try {
            return value instanceof JsonNode node ? node.deepCopy() : MAPPER.valueToTree(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Value cannot be represented as JSON");
        }
    }

    public static JsonNode content(Object value, String field) {
        JsonNode node = json(Objects.requireNonNull(value, field + " is required"));
        if (!(node.isTextual() || node.isObject() || node.isArray())) {
            throw new IllegalArgumentException(field + " must be a string, object, or array");
        }
        if (node.isTextual() && node.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return node;
    }

    public static String nonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
    public static ObjectNode object() { return MAPPER.createObjectNode(); }
}
