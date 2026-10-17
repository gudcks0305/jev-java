package io.github.gudcks0305.jev.vercel;

import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.gudcks0305.jev.JevImage;
import io.github.gudcks0305.jev.internal.JsonSupport;
import java.util.Objects;

/** Explicit ordered state parts for Vercel's decision-model v4 protocol. */
public final class VercelDecisionInput {
    private final ArrayNode parts;

    private VercelDecisionInput(ArrayNode parts) { this.parts = parts.deepCopy(); }
    public static Builder builder() { return new Builder(); }
    ArrayNode stateParts() { return parts.deepCopy(); }
    @Override public String toString() { return "VercelDecisionInput[parts=" + parts.size() + ", data=<redacted>]"; }

    public static final class Builder {
        private final ArrayNode parts = JsonSupport.object().arrayNode();
        private long imageBytes;
        private Builder() {}
        public Builder text(String text) {
            String value = JsonSupport.nonBlank(text, "Text");
            parts.addObject().put("type", "text").put("text", value);
            return this;
        }
        public Builder json(Object value) {
            var snapshot = JsonSupport.materialize(value);
            parts.addObject().put("type", "json").set("value", snapshot);
            return this;
        }
        public Builder image(JevImage image) {
            Objects.requireNonNull(image, "image");
            long total = imageBytes + image.dataBase64().length();
            if (total > 32L * 1024 * 1024) {
                throw new IllegalArgumentException("Vercel decision request body exceeds 32 MiB");
            }
            var part = parts.addObject().put("type", "file");
            part.put("mediaType", image.format().equals("png") ? "image/png" : "image/jpeg");
            part.putObject("data").put("type", "data").put("data", image.dataBase64());
            imageBytes = total;
            return this;
        }
        public VercelDecisionInput build() {
            if (parts.isEmpty()) throw new IllegalArgumentException("At least one state part is required");
            return new VercelDecisionInput(parts);
        }
    }
}
