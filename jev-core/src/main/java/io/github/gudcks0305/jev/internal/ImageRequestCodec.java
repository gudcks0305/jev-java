package io.github.gudcks0305.jev.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gudcks0305.jev.JevImage;
import io.github.gudcks0305.jev.JevInput;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Encodes explicit image references without rewriting object keys or question ids. */
final class ImageRequestCodec {
    private static final long MAX_BODY_BYTES = 32L * 1024 * 1024;

    private ImageRequestCodec() {}

    static ObjectNode encode(ObjectNode body, JevInput input, WireFormat format) {
        ObjectNode result = (ObjectNode) JsonSupport.materialize(body);
        ObjectNode content = format == WireFormat.CLOUDFLARE ? (ObjectNode) result.path("input") : result;
        var context = new References(input, format);
        JsonNode state = context.visit(content.path("state"));
        if (format == WireFormat.OPENROUTER && content.path("state").isTextual() && state.isObject()) {
            state = JsonSupport.object().arrayNode().add(state);
        }
        content.set("state", state);
        for (JsonNode question : content.path("questions")) {
            var entry = (ObjectNode) question;
            entry.set("instructions", context.visit(entry.path("instructions")));
            if (entry.has("criteria")) entry.set("criteria", context.visit(entry.path("criteria")));
        }
        if (context.used.size() != input.images().size()) {
            throw new IllegalArgumentException("Every image must be referenced in state, instructions, or criteria");
        }
        if (format != WireFormat.OPENROUTER) {
            ObjectNode assets = content.putObject("assets");
            for (JevImage image : input.images()) {
                assets.putObject(image.reference()).put("type", "image").put("format", image.format())
                        .put("data_base64", image.dataBase64());
            }
        }
        if (JsonSupport.utf8Bytes(result).length > MAX_BODY_BYTES) throw tooLarge();
        return result;
    }

    private static IllegalArgumentException tooLarge() {
        return new IllegalArgumentException("Image request body exceeds 32 MiB");
    }

    private static final class References {
        private final Map<String, JevImage> images = new LinkedHashMap<>();
        private final Map<String, ObjectNode> parts = new LinkedHashMap<>();
        private final Set<String> used = new HashSet<>();
        private final Pattern pattern;
        private final boolean inline;
        private long inlineBytes;

        References(JevInput input, WireFormat format) {
            inline = format == WireFormat.OPENROUTER;
            long uploadedBytes = 0;
            for (JevImage image : input.images()) {
                images.put(image.reference(), image);
                uploadedBytes += image.dataBase64().length();
            }
            if (uploadedBytes > MAX_BODY_BYTES) throw tooLarge();
            pattern = Pattern.compile(images.keySet().stream().map(Pattern::quote).collect(Collectors.joining("|")));
        }

        JsonNode visit(JsonNode node) {
            if (node.isTextual()) return text(node);
            if (node instanceof ObjectNode object) {
                var fields = object.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    field.setValue(visit(field.getValue()));
                }
            } else if (node instanceof ArrayNode array) {
                for (int i = 0; i < array.size(); i++) array.set(i, visit(array.get(i)));
            }
            return node;
        }

        private JsonNode text(JsonNode node) {
            String value = node.textValue();
            var matcher = pattern.matcher(value);
            ArrayNode fragments = null;
            int offset = 0;
            while (matcher.find()) {
                String reference = matcher.group();
                used.add(reference);
                if (!inline) continue;
                JevImage image = images.get(reference);
                // Each occurrence carries its own data URL on the wire. Check before
                // allocating/serializing an expanded request, not after a huge copy.
                inlineBytes += image.dataBase64().length() + 32L;
                if (inlineBytes > MAX_BODY_BYTES) throw tooLarge();
                ObjectNode part = parts.computeIfAbsent(reference, ignored -> imagePart(image));
                if (matcher.start() == 0 && matcher.end() == value.length()) return part.deepCopy();
                if (fragments == null) fragments = JsonSupport.object().arrayNode();
                if (matcher.start() > offset) fragments.add(value.substring(offset, matcher.start()));
                fragments.add(part.deepCopy());
                offset = matcher.end();
            }
            if (fragments == null) return node;
            if (offset < value.length()) fragments.add(value.substring(offset));
            return fragments;
        }

        private static ObjectNode imagePart(JevImage image) {
            String mime = image.format().equals("png") ? "image/png" : "image/jpeg";
            var part = JsonSupport.object().put("type", "image_url");
            part.putObject("image_url").put("url", "data:" + mime + ";base64," + image.dataBase64());
            return part;
        }
    }
}
