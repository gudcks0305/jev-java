package io.github.gudcks0305.jev.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gudcks0305.jev.internal.OpenAiJsonInput;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable shared evidence. Hosted image URLs are sent to OpenAI, never fetched by this SDK. */
public sealed interface DecisionInput permits DecisionInput.Text, DecisionInput.Messages, DecisionInput.History {
    static Text text(String text) { return new Text(text); }
    static Messages messages(List<UserMessage> messages) { return new Messages(messages); }
    static Messages messages(UserMessage... messages) { return new Messages(List.of(messages)); }
    static History history(List<? extends Item> items) { return new History(List.copyOf(items)); }
    static History history(Item... items) { return new History(List.of(items)); }
    static Messages json(Object value) { return messages(new UserMessage(List.of(JsonPart.of(value)))); }

    record Text(String text) implements DecisionInput {
        public Text { Objects.requireNonNull(text, "Input text must not be null"); }
        @Override public String toString() { return "DecisionInput.Text[redacted]"; }
    }

    record Messages(List<UserMessage> messages) implements DecisionInput {
        public Messages { messages = List.copyOf(messages); }
        @Override public String toString() { return "DecisionInput.Messages[count=" + messages.size() + "]"; }
    }

    record History(List<Item> items) implements DecisionInput {
        public History {
            items = List.copyOf(items);
            Set<String> functions = new HashSet<>();
            Set<String> custom = new HashSet<>();
            for (Item item : items) {
                if (item instanceof FunctionCall call) functions.add(call.callId());
                else if (item instanceof CustomToolCall call) custom.add(call.callId());
            }
            for (Item item : items) {
                if (item instanceof FunctionCallOutput output && !functions.contains(output.callId())) {
                    throw new IllegalArgumentException("Tool output requires a matching call of the same kind");
                }
                if (item instanceof CustomToolCallOutput output && !custom.contains(output.callId())) {
                    throw new IllegalArgumentException("Tool output requires a matching call of the same kind");
                }
            }
        }
        @Override public String toString() { return "DecisionInput.History[count=" + items.size() + "]"; }
    }

    sealed interface Item permits UserMessage, DeveloperMessage, SystemMessage, AssistantMessage,
            FunctionCall, FunctionCallOutput, CustomToolCall, CustomToolCallOutput {}

    record UserMessage(Content content) implements Item {
        public UserMessage { validateContent(content, "user"); }
        public UserMessage(String text) { this(new MessageText(text)); }
        public UserMessage(List<Part> parts) { this(new ContentParts(parts)); }
        @Override public String toString() { return "DecisionInput.UserMessage[redacted]"; }
    }

    record DeveloperMessage(Content content) implements Item {
        public DeveloperMessage { validateContent(content, "instructions"); }
        public DeveloperMessage(String text) { this(new MessageText(text)); }
        public DeveloperMessage(List<Part> parts) { this(new ContentParts(parts)); }
        @Override public String toString() { return "DecisionInput.DeveloperMessage[redacted]"; }
    }

    record SystemMessage(Content content) implements Item {
        public SystemMessage { validateContent(content, "instructions"); }
        public SystemMessage(String text) { this(new MessageText(text)); }
        public SystemMessage(List<Part> parts) { this(new ContentParts(parts)); }
        @Override public String toString() { return "DecisionInput.SystemMessage[redacted]"; }
    }

    record AssistantMessage(Content content, Phase phase) implements Item {
        public AssistantMessage { validateContent(content, "assistant"); }
        public AssistantMessage(Content content) { this(content, null); }
        public AssistantMessage(String text) { this(new MessageText(text), null); }
        public AssistantMessage(String text, Phase phase) { this(new MessageText(text), phase); }
        public AssistantMessage(List<Part> parts) { this(new ContentParts(parts), null); }
        public AssistantMessage(List<Part> parts, Phase phase) { this(new ContentParts(parts), phase); }
        @Override public String toString() { return "DecisionInput.AssistantMessage[redacted]"; }
    }

    record FunctionCall(String callId, String name, String arguments, String id, String namespace, Status status) implements Item {
        public FunctionCall {
            length(callId, "Call id", 1, 64);
            length(name, "Function name", 1, 128);
            length(arguments, "Arguments", 0, 1_048_576);
            optionalLength(id, "Item id", 0, 1_048_576);
            optionalLength(namespace, "Namespace", 1, 64);
        }
        public FunctionCall(String callId, String name, String arguments) { this(callId, name, arguments, null, null, null); }
        @Override public String toString() { return "DecisionInput.FunctionCall[redacted]"; }
    }

    record FunctionCallOutput(String callId, Content output, String id, Status status) implements Item {
        public FunctionCallOutput {
            length(callId, "Call id", 1, 64);
            validateContent(output, "tool");
            optionalLength(id, "Item id", 0, 1_048_576);
        }
        public FunctionCallOutput(String callId, Content output) { this(callId, output, null, null); }
        public FunctionCallOutput(String callId, String output) { this(callId, new MessageText(output)); }
        public FunctionCallOutput(String callId, List<Part> output) { this(callId, new ContentParts(output)); }
        @Override public String toString() { return "DecisionInput.FunctionCallOutput[redacted]"; }
    }

    record CustomToolCall(String callId, String name, String input, String id, String namespace, Status status) implements Item {
        public CustomToolCall {
            length(callId, "Call id", 1, 64);
            length(name, "Custom tool name", 1, 64);
            length(input, "Tool input", 0, 1_048_576);
            optionalLength(id, "Item id", 0, 1_048_576);
            optionalLength(namespace, "Namespace", 1, 64);
        }
        public CustomToolCall(String callId, String name, String input) { this(callId, name, input, null, null, null); }
        @Override public String toString() { return "DecisionInput.CustomToolCall[redacted]"; }
    }

    record CustomToolCallOutput(String callId, Content output, String id, Status status) implements Item {
        public CustomToolCallOutput {
            length(callId, "Call id", 1, 64);
            validateContent(output, "tool");
            optionalLength(id, "Item id", 0, 1_048_576);
        }
        public CustomToolCallOutput(String callId, Content output) { this(callId, output, null, null); }
        public CustomToolCallOutput(String callId, String output) { this(callId, new MessageText(output)); }
        public CustomToolCallOutput(String callId, List<Part> output) { this(callId, new ContentParts(output)); }
        @Override public String toString() { return "DecisionInput.CustomToolCallOutput[redacted]"; }
    }

    enum Phase { COMMENTARY, FINAL_ANSWER }
    enum Status { IN_PROGRESS, COMPLETED, INCOMPLETE }
    enum CacheBreakpoint { EXPLICIT }

    sealed interface Content permits MessageText, ContentParts {}

    record MessageText(String text) implements Content {
        public MessageText { Objects.requireNonNull(text, "Message text must not be null"); }
        @Override public String toString() { return "DecisionInput.MessageText[redacted]"; }
    }

    record ContentParts(List<Part> parts) implements Content {
        public ContentParts { parts = List.copyOf(parts); }
        @Override public String toString() { return "DecisionInput.ContentParts[count=" + parts.size() + "]"; }
    }

    sealed interface Part permits TextPart, ImagePart, JsonPart, OutputTextPart, RefusalPart {}

    record TextPart(String text, CacheBreakpoint breakpoint) implements Part {
        public TextPart { length(text, "Part text", 0, 10_485_760); }
        public TextPart(String text) { this(text, null); }
        public TextPart withCacheBreakpoint() { return new TextPart(text, CacheBreakpoint.EXPLICIT); }
        @Override public String toString() { return "DecisionInput.TextPart[redacted]"; }
    }

    /** Inline base64 or hosted HTTP(S) URL; null detail uses the provider default. */
    record ImagePart(String imageUrl, Detail detail, CacheBreakpoint breakpoint) implements Part {
        public ImagePart { validateImageUrl(imageUrl); }
        public ImagePart(String imageUrl, Detail detail) { this(imageUrl, detail, null); }
        public ImagePart(String imageUrl) { this(imageUrl, null, null); }
        public ImagePart withCacheBreakpoint() { return new ImagePart(imageUrl, detail, CacheBreakpoint.EXPLICIT); }
        @Override public String toString() { return "DecisionInput.ImagePart[detail=" + detail + ", imageUrl=redacted]"; }
    }

    record JsonPart(JsonNode data) implements Part {
        public JsonPart { data = OpenAiJsonInput.snapshot(data); }
        public static JsonPart of(Object data) { return new JsonPart(OpenAiJsonInput.snapshot(data)); }
        @Override public JsonNode data() { return data.deepCopy(); }
        @Override public String toString() { return "DecisionInput.JsonPart[redacted]"; }
    }

    record OutputTextPart(String text) implements Part {
        public OutputTextPart { length(text, "Output text", 0, 10_485_760); }
        @Override public String toString() { return "DecisionInput.OutputTextPart[redacted]"; }
    }

    record RefusalPart(String refusal) implements Part {
        public RefusalPart { length(refusal, "Refusal text", 0, 10_485_760); }
        @Override public String toString() { return "DecisionInput.RefusalPart[redacted]"; }
    }

    enum Detail { LOW, HIGH, AUTO, ORIGINAL }
    private static void validateContent(Content content, String context) {
        Objects.requireNonNull(content, "Message content must not be null");
        if (content instanceof ContentParts parts) {
            for (Part part : parts.parts()) {
                boolean supported = switch (context) {
                    case "user" -> part instanceof TextPart || part instanceof ImagePart || part instanceof JsonPart;
                    case "instructions" -> part instanceof TextPart;
                    case "assistant" -> part instanceof OutputTextPart || part instanceof RefusalPart;
                    case "tool" -> part instanceof TextPart || part instanceof ImagePart;
                    default -> throw new IllegalArgumentException("Unknown content context");
                };
                if (!supported) throw new IllegalArgumentException("Content part is not supported for this message or tool output");
            }
        }
    }

    private static void optionalLength(String value, String field, int min, int max) {
        if (value != null) length(value, field, min, max);
    }
    private static void length(String value, String field, int min, int max) {
        Objects.requireNonNull(value, field + " must not be null");
        int count = value.codePointCount(0, value.length());
        if (count < min || count > max) throw new IllegalArgumentException(field + " length is outside the supported range");
    }

    private static void validateImageUrl(String url) {
        length(url, "Image URL", 0, 1_073_741_824);
        if (url.startsWith("data:")) {
            validateDataUrl(url);
            return;
        }
        try {
            URI uri = URI.create(url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            // URI parsing exceptions can contain the complete private URL.
            throw new IllegalArgumentException("Image URL must be inline base64 or an HTTP(S) URL without credentials or fragment");
        }
    }

    private static void validateDataUrl(String url) {
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
