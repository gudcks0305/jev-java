package io.github.gudcks0305.jev.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.Objects;

/** Internal JSON conversion; deliberately does not enable polymorphic deserialization. */
public final class JsonSupport {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final ObjectMapper WIRE_VALUES = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(32 * 1024 * 1024).build())
            .build()).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private JsonSupport() {}

    public static JsonNode json(Object value) {
        try {
            return value instanceof JsonNode node ? node.deepCopy() : MAPPER.valueToTree(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Value cannot be represented as JSON");
        }
    }

    /** Materializes embedded POJOs as ordinary JSON values and snapshots their data. */
    public static JsonNode materialize(Object value) {
        try {
            return materializeEmbedded(MAPPER.valueToTree(value));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Value cannot be represented as JSON");
        }
    }

    private static JsonNode materializeEmbedded(JsonNode node) throws java.io.IOException {
        // TokenBuffer retains RawValue as a POJONode. Parse only these embedded
        // values, leaving ordinary numeric nodes (including BigDecimal) intact.
        if (node.isPojo()) {
            JsonNode parsed = WIRE_VALUES.readTree(MAPPER.writeValueAsBytes(node));
            if (parsed == null || parsed.isMissingNode()) throw new IllegalArgumentException("Missing JSON value");
            return parsed;
        }
        if (node instanceof ObjectNode object) {
            var fields = object.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                field.setValue(materializeEmbedded(field.getValue()));
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) array.set(i, materializeEmbedded(array.get(i)));
        }
        return node;
    }

    /** Uses the same UTF-8 byte generator as the built-in HTTP transports. */
    public static byte[] utf8Bytes(JsonNode value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (Exception ex) {
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
