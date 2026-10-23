package io.github.gudcks0305.jev.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Encodes typed input evidence without interpreting JSON data or fetching hosted images. */
final class DecisionInputCodec {
    private DecisionInputCodec() {}

    static JsonNode encode(DecisionInput input) {
        Objects.requireNonNull(input, "Decision input must not be null");
        if (input instanceof DecisionInput.Text text) return TextNode.valueOf(text.text());
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        if (input instanceof DecisionInput.Messages messages) {
            for (var message : messages.messages()) result.add(message("user", message.content()));
        } else if (input instanceof DecisionInput.History history) {
            for (var item : history.items()) result.add(item(item));
        } else throw new IllegalArgumentException("Unsupported decision input");
        return result;
    }

    private static ObjectNode item(DecisionInput.Item item) {
        if (item instanceof DecisionInput.UserMessage message) return message("user", message.content());
        if (item instanceof DecisionInput.DeveloperMessage message) return message("developer", message.content());
        if (item instanceof DecisionInput.SystemMessage message) return message("system", message.content());
        if (item instanceof DecisionInput.AssistantMessage message) {
            ObjectNode wire = message("assistant", message.content());
            if (message.phase() != null) wire.put("phase", lower(message.phase()));
            return wire;
        }
        if (item instanceof DecisionInput.FunctionCall call) {
            ObjectNode wire = tool("function_call", call.callId(), call.id(), call.status());
            wire.put("name", call.name()).put("arguments", call.arguments());
            if (call.namespace() != null) wire.put("namespace", call.namespace());
            return wire;
        }
        if (item instanceof DecisionInput.CustomToolCall call) {
            ObjectNode wire = tool("custom_tool_call", call.callId(), call.id(), call.status());
            wire.put("name", call.name()).put("input", call.input());
            if (call.namespace() != null) wire.put("namespace", call.namespace());
            return wire;
        }
        if (item instanceof DecisionInput.FunctionCallOutput output) {
            ObjectNode wire = tool("function_call_output", output.callId(), output.id(), output.status());
            wire.set("output", content(output.output()));
            return wire;
        }
        if (item instanceof DecisionInput.CustomToolCallOutput output) {
            ObjectNode wire = tool("custom_tool_call_output", output.callId(), output.id(), output.status());
            wire.set("output", content(output.output()));
            return wire;
        }
        throw new IllegalArgumentException("Unsupported decision input item");
    }

    private static ObjectNode message(String role, DecisionInput.Content content) {
        var result = JsonNodeFactory.instance.objectNode().put("role", role);
        result.set("content", content(content));
        return result;
    }

    private static ObjectNode tool(String type, String callId, String id, DecisionInput.Status status) {
        var result = JsonNodeFactory.instance.objectNode().put("type", type).put("call_id", callId);
        if (id != null) result.put("id", id);
        if (status != null) result.put("status", lower(status));
        return result;
    }

    private static JsonNode content(DecisionInput.Content content) {
        if (content instanceof DecisionInput.MessageText text) return TextNode.valueOf(text.text());
        if (content instanceof DecisionInput.ContentParts value) return parts(value.parts());
        throw new IllegalArgumentException("Unsupported decision input content");
    }

    static ArrayNode parts(List<DecisionInput.Part> parts) {
        var result = JsonNodeFactory.instance.arrayNode();
        for (var part : parts) {
            Objects.requireNonNull(part, "Decision content part must not be null");
            ObjectNode wire = result.addObject();
            if (part instanceof DecisionInput.TextPart text) {
                wire.put("type", "input_text").put("text", text.text());
                breakpoint(wire, text.breakpoint());
            } else if (part instanceof DecisionInput.ImagePart image) {
                wire.put("type", "input_image").put("image_url", image.imageUrl());
                if (image.detail() != null) wire.put("detail", lower(image.detail()));
                breakpoint(wire, image.breakpoint());
            } else if (part instanceof DecisionInput.JsonPart json) {
                wire.put("type", "input_json").set("data", json.data());
            } else if (part instanceof DecisionInput.OutputTextPart text) {
                wire.put("type", "output_text").put("text", text.text());
            } else if (part instanceof DecisionInput.RefusalPart refusal) {
                wire.put("type", "refusal").put("refusal", refusal.refusal());
            } else throw new IllegalArgumentException("Unsupported decision content part");
        }
        return result;
    }

    private static void breakpoint(ObjectNode wire, DecisionInput.CacheBreakpoint breakpoint) {
        if (breakpoint != null) wire.putObject("prompt_cache_breakpoint").put("mode", lower(breakpoint));
    }

    static int imageCount(DecisionInput input) {
        Objects.requireNonNull(input, "Decision input must not be null");
        int count = 0;
        if (input instanceof DecisionInput.Messages messages) {
            for (var message : messages.messages()) count += imageCount(message.content());
        } else if (input instanceof DecisionInput.History history) {
            for (var item : history.items()) {
                if (item instanceof DecisionInput.UserMessage message) count += imageCount(message.content());
                else if (item instanceof DecisionInput.FunctionCallOutput output) count += imageCount(output.output());
                else if (item instanceof DecisionInput.CustomToolCallOutput output) count += imageCount(output.output());
            }
        }
        return count;
    }

    private static int imageCount(DecisionInput.Content content) {
        if (!(content instanceof DecisionInput.ContentParts value)) return 0;
        int count = 0;
        for (var part : value.parts()) if (part instanceof DecisionInput.ImagePart) count++;
        return count;
    }

    private static String lower(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
}
