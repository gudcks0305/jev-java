package io.github.gudcks0305.jev.openai;

import java.util.List;
import java.util.Objects;

/** Native text or user messages. No file loading, external images, roles, or tools. */
public sealed interface DecisionInput permits DecisionInput.Text, DecisionInput.Messages {
    static Text text(String text) { return new Text(text); }
    static Messages messages(List<UserMessage> messages) { return new Messages(messages); }
    static Messages messages(UserMessage... messages) { return new Messages(List.of(messages)); }

    record Text(String text) implements DecisionInput {
        public Text { Objects.requireNonNull(text, "Input text must not be null"); }
        @Override public String toString() { return "DecisionInput.Text[redacted]"; }
    }

    record Messages(List<UserMessage> messages) implements DecisionInput {
        public Messages { messages = List.copyOf(messages); }
        @Override public String toString() { return "DecisionInput.Messages[count=" + messages.size() + "]"; }
    }

    record UserMessage(Content content) {
        public UserMessage { Objects.requireNonNull(content, "Message content must not be null"); }
        public UserMessage(String text) { this(new MessageText(text)); }
        public UserMessage(List<Part> parts) { this(new ContentParts(parts)); }
        @Override public String toString() { return "DecisionInput.UserMessage[redacted]"; }
    }

    sealed interface Content permits MessageText, ContentParts {}

    record MessageText(String text) implements Content {
        public MessageText { Objects.requireNonNull(text, "Message text must not be null"); }
        @Override public String toString() { return "DecisionInput.MessageText[redacted]"; }
    }

    record ContentParts(List<Part> parts) implements Content {
        public ContentParts { parts = List.copyOf(parts); }
        @Override public String toString() { return "DecisionInput.ContentParts[count=" + parts.size() + "]"; }
    }

    sealed interface Part permits TextPart, ImagePart {}

    record TextPart(String text) implements Part {
        public TextPart { Objects.requireNonNull(text, "Part text must not be null"); }
        @Override public String toString() { return "DecisionInput.TextPart[redacted]"; }
    }

    /** Inline base64 data URL; null detail is omitted and uses the provider default. */
    record ImagePart(String imageUrl, Detail detail) implements Part {
        public ImagePart { validateDataUrl(imageUrl); }
        public ImagePart(String imageUrl) { this(imageUrl, null); }
        @Override public String toString() { return "DecisionInput.ImagePart[detail=" + detail + ", imageUrl=redacted]"; }
    }

    enum Detail { LOW, HIGH, AUTO, ORIGINAL }

    private static void validateDataUrl(String url) {
        Objects.requireNonNull(url, "Image data URL must not be null");
        int comma = url.indexOf(',');
        int marker = comma - ";base64".length();
        if (!url.startsWith("data:image/") || marker <= "data:image/".length()
                || !url.regionMatches(marker, ";base64", 0, ";base64".length())) {
            throw new IllegalArgumentException("Image must be an inline base64 image data URL");
        }
        for (int i = "data:image/".length(); i < marker; i++) {
            char c = url.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '.' || c == '+' || c == '-')) {
                throw new IllegalArgumentException("Invalid image media type");
            }
        }
        // Scan the original string: decoding large images would allocate another full image.
        int start = comma + 1;
        int length = url.length() - start;
        if (length == 0) throw new IllegalArgumentException("Image base64 payload must not be empty");
        int padding = url.endsWith("==") ? 2 : url.endsWith("=") ? 1 : 0;
        int dataLength = length - padding;
        if (dataLength % 4 == 1 || padding > 0 && (length % 4 != 0 || dataLength % 4 != 4 - padding)) {
            throw new IllegalArgumentException("Invalid image base64 length");
        }
        for (int i = start; i < url.length() - padding; i++) {
            if (base64Value(url.charAt(i)) < 0) throw new IllegalArgumentException("Invalid image base64 payload");
        }
        int remainder = dataLength % 4;
        int last = base64Value(url.charAt(url.length() - padding - 1));
        if (remainder == 2 && (last & 15) != 0 || remainder == 3 && (last & 3) != 0) {
            throw new IllegalArgumentException("Invalid image base64 trailing bits");
        }
    }

    private static int base64Value(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        return c == '+' ? 62 : c == '/' ? 63 : -1;
    }
}
