package io.github.gudcks0305.jev.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayDeque;

/** Internal validation and snapshots for OpenAI Decisions input_json data. */
public final class OpenAiJsonInput {
    private static final int MAX_CONTAINER_LEVELS = 32;
    private static final int MAX_VALUES = 10_000;
    private static final int MAX_UTF8_BYTES = 256 * 1024;

    private OpenAiJsonInput() {}

    /** Returns ordinary JSON detached from the caller, without interpreting any data fields. */
    public static JsonNode snapshot(Object value) {
        final JsonNode data;
        try {
            data = JsonSupport.materialize(value);
        } catch (RuntimeException | StackOverflowError failure) {
            // Serializer exceptions may contain application data, property paths, or raw JSON.
            throw new IllegalArgumentException("OpenAI JSON input cannot be represented as JSON");
        }
        if (!(data.isObject() || data.isArray())) {
            throw new IllegalArgumentException("OpenAI JSON input must be an object or an array of objects");
        }
        if (data.isArray()) {
            for (JsonNode element : data) {
                if (!element.isObject()) {
                    throw new IllegalArgumentException("OpenAI JSON input arrays must contain only objects");
                }
            }
        }
        var pending = new ArrayDeque<Value>();
        pending.push(new Value(data, 0));
        int values = 0;
        while (!pending.isEmpty()) {
            Value current = pending.pop();
            if (++values > MAX_VALUES) {
                throw new IllegalArgumentException("OpenAI JSON input exceeds 10000 values");
            }
            JsonNode node = current.node();
            if (node.isContainerNode()) {
                int levels = current.containerLevels() + 1;
                if (levels > MAX_CONTAINER_LEVELS) {
                    throw new IllegalArgumentException("OpenAI JSON input exceeds 32 container levels");
                }
                if (node instanceof ObjectNode object) {
                    var fields = object.fields();
                    while (fields.hasNext()) {
                        var field = fields.next();
                        JsonNode child = normalizeBinary(field.getValue());
                        field.setValue(child);
                        pending.push(new Value(child, levels));
                    }
                } else if (node instanceof ArrayNode array) {
                    for (int i = 0; i < array.size(); i++) {
                        JsonNode child = normalizeBinary(array.get(i));
                        array.set(i, child);
                        pending.push(new Value(child, levels));
                    }
                }
            } else if (node.isNumber()) {
                // BigInteger and BigDecimal remain exact, including values outside double range.
                if ((node.isDouble() || node.isFloat()) && !Double.isFinite(node.doubleValue())) {
                    throw new IllegalArgumentException("OpenAI JSON input numbers must be finite");
                }
            } else if (!(node.isTextual() || node.isBoolean() || node.isNull())) {
                throw new IllegalArgumentException("OpenAI JSON input contains an unsupported JSON value");
            }
        }
        if (JsonSupport.utf8Bytes(data).length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("OpenAI JSON input exceeds 256 KiB of compact UTF-8 JSON");
        }
        return data;
    }

    private static JsonNode normalizeBinary(JsonNode node) {
        // Jackson writes byte[] as a base64 JSON string. Freeze that string now,
        // because BinaryNode may still retain the caller's mutable byte array.
        return node.isBinary() ? TextNode.valueOf(node.asText()) : node;
    }

    private record Value(JsonNode node, int containerLevels) {}
}
