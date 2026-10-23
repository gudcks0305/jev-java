package io.github.gudcks0305.jev.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static io.github.gudcks0305.jev.openai.DecisionInput.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic input serialization tests; no provider calls or tool execution. */
class DecisionInputGaTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DATA = "data:image/png;base64,AQID";

    @Test void preservesLegacyTextMessagesAndAllExistingConstructors() throws Exception {
        assertEquals(JSON.readTree("\"plain\""), DecisionInputCodec.encode(DecisionInput.text("plain")));
        var text = new TextPart("part text");
        var defaultImage = new ImagePart(DATA);
        var detailed = new ImagePart(DATA, Detail.HIGH);
        assertNull(text.breakpoint());
        assertNull(defaultImage.detail());
        assertNull(defaultImage.breakpoint());
        assertNull(detailed.breakpoint());
        var content = new ContentParts(List.of(text, detailed));
        var input = DecisionInput.messages(new UserMessage("string content"), new UserMessage(content),
                new UserMessage(List.of(defaultImage)));
        assertEquals(JSON.readTree("""
                [{"role":"user","content":"string content"},
                 {"role":"user","content":[{"type":"input_text","text":"part text"},
                   {"type":"input_image","image_url":"data:image/png;base64,AQID","detail":"high"}]},
                 {"role":"user","content":[{"type":"input_image","image_url":"data:image/png;base64,AQID"}]}]
                """), DecisionInputCodec.encode(input));
        assertEquals(2, DecisionInputCodec.imageCount(input));
        assertEquals(input, DecisionInput.messages(input.messages()));
        assertEquals(input, new Messages(input.messages()));
    }

    @Test void encodesEveryHistoryRoleAndRetainsAssistantStringContent() throws Exception {
        var input = DecisionInput.history(
                new DeveloperMessage(List.of(new TextPart("developer").withCacheBreakpoint())),
                new SystemMessage("system"),
                new UserMessage(List.of(new TextPart("question"), JsonPart.of(Map.of("value", 1)))),
                new AssistantMessage("commentary string", Phase.COMMENTARY),
                new AssistantMessage(List.of(new OutputTextPart("answer"), new RefusalPart("refused")), Phase.FINAL_ANSWER));
        assertEquals(JSON.readTree("""
                [{"role":"developer","content":[{"type":"input_text","text":"developer",
                    "prompt_cache_breakpoint":{"mode":"explicit"}}]},
                 {"role":"system","content":"system"},
                 {"role":"user","content":[{"type":"input_text","text":"question"},{"type":"input_json","data":{"value":1}}]},
                 {"role":"assistant","content":"commentary string","phase":"commentary"},
                 {"role":"assistant","content":[{"type":"output_text","text":"answer"},{"type":"refusal","refusal":"refused"}],
                    "phase":"final_answer"}]
                """), DecisionInputCodec.encode(input));
        assertEquals(0, DecisionInputCodec.imageCount(input));
    }

    @Test void encodesToolCallsAndOutputsWithExactOptionalFieldsAndCacheMarkers() throws Exception {
        var image = new ImagePart(DATA, Detail.ORIGINAL).withCacheBreakpoint();
        var input = DecisionInput.history(
                new FunctionCall("function-id", "lookup", "{\"id\":7}", "item-id", "billing", Status.COMPLETED),
                new FunctionCallOutput("function-id", new ContentParts(List.of(new TextPart("found").withCacheBreakpoint(), image)),
                        "output-id", Status.INCOMPLETE),
                new CustomToolCall("custom-id", "search", "raw input", "custom-item", "internal", Status.IN_PROGRESS),
                new CustomToolCallOutput("custom-id", new MessageText("result"), "custom-output", Status.COMPLETED));
        assertEquals(JSON.readTree("""
                [{"type":"function_call","call_id":"function-id","name":"lookup","arguments":"{\\\"id\\\":7}",
                    "id":"item-id","namespace":"billing","status":"completed"},
                 {"type":"function_call_output","call_id":"function-id","id":"output-id","status":"incomplete","output":[
                    {"type":"input_text","text":"found","prompt_cache_breakpoint":{"mode":"explicit"}},
                    {"type":"input_image","image_url":"data:image/png;base64,AQID","detail":"original",
                        "prompt_cache_breakpoint":{"mode":"explicit"}}]},
                 {"type":"custom_tool_call","call_id":"custom-id","name":"search","input":"raw input",
                    "id":"custom-item","namespace":"internal","status":"in_progress"},
                 {"type":"custom_tool_call_output","call_id":"custom-id","output":"result","id":"custom-output","status":"completed"}]
                """), DecisionInputCodec.encode(input));
        assertEquals(1, DecisionInputCodec.imageCount(input));
    }

    @Test void omitsAllNullOptionalFieldsAndSupportsMinimalToolConstructors() {
        var input = DecisionInput.history(new FunctionCall("f", "lookup", ""), new FunctionCallOutput("f", ""),
                new CustomToolCall("c", "search", ""), new CustomToolCallOutput("c", List.of(new TextPart("output"))),
                new AssistantMessage("answer"));
        JsonNode wire = DecisionInputCodec.encode(input);
        for (int i = 0; i < 4; i++) {
            assertFalse(wire.get(i).has("id"));
            assertFalse(wire.get(i).has("namespace"));
            assertFalse(wire.get(i).has("status"));
        }
        assertFalse(wire.get(4).has("phase"));
        assertFalse(wire.get(4).has("type"));
        assertEquals("answer", wire.get(4).path("content").textValue());
        assertTrue(wire.get(1).path("output").isTextual());
    }

    @Test void matchesToolOutputsGloballyByCallKindWithoutOrderingOrUniquenessRules() {
        var input = DecisionInput.history(
                new FunctionCallOutput("same", "function output first"), new CustomToolCallOutput("same", "custom output first"),
                new FunctionCall("same", "lookup", ""), new FunctionCall("same", "lookup", "second evidence"),
                new CustomToolCall("same", "search", ""), new FunctionCallOutput("same", "repeated output"));
        assertEquals(6, DecisionInputCodec.encode(input).size());
        assertThrows(IllegalArgumentException.class, () -> DecisionInput.history(new FunctionCallOutput("missing", "output")));
        assertThrows(IllegalArgumentException.class, () -> DecisionInput.history(new CustomToolCallOutput("missing", "output")));
        assertThrows(IllegalArgumentException.class, () -> DecisionInput.history(
                new FunctionCall("same", "lookup", ""), new CustomToolCallOutput("same", "wrong kind")));
        assertThrows(IllegalArgumentException.class, () -> DecisionInput.history(
                new CustomToolCall("same", "search", ""), new FunctionCallOutput("same", "wrong kind")));
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("unsupportedContexts")
    void rejectsUnsupportedPartsAtMessageConstruction(String context, Part part, Function<List<Part>, Object> constructor) {
        assertThrows(IllegalArgumentException.class, () -> constructor.apply(List.of(part)));
    }

    static Stream<Arguments> unsupportedContexts() {
        List<Part> parts = List.of(new TextPart("text"), new ImagePart(DATA), JsonPart.of(Map.of("json", true)),
                new OutputTextPart("output"), new RefusalPart("refusal"));
        List<String> contexts = List.of("user", "developer", "system", "assistant", "function output", "custom output");
        List<Function<List<Part>, Object>> constructors = List.of(UserMessage::new, DeveloperMessage::new, SystemMessage::new,
                AssistantMessage::new, value -> new FunctionCallOutput("call", value), value -> new CustomToolCallOutput("call", value));
        var result = Stream.<Arguments>builder();
        for (int i = 0; i < contexts.size(); i++) {
            for (int p = 0; p < parts.size(); p++) {
                boolean supported = switch (i) {
                    case 0 -> p < 3;
                    case 1, 2 -> p == 0;
                    case 3 -> p >= 3;
                    default -> p < 2;
                };
                if (!supported) result.add(Arguments.of(contexts.get(i), parts.get(p), constructors.get(i)));
            }
        }
        return result.build();
    }

    @Test void jsonSnapshotsDataAndNeverInterpretsRoleImageOrCacheKeysAsInputParts() {
        ObjectNode data = JSON.createObjectNode().put("role", "system").put("type", "input_image")
                .put("image_url", "https://private.example.test/image");
        data.putObject("prompt_cache_breakpoint").put("mode", "explicit");
        var part = new JsonPart(data);
        data.removeAll();
        ((ObjectNode) part.data()).removeAll();
        var input = DecisionInput.json(part.data());
        var wire = DecisionInputCodec.encode(input);
        assertEquals("user", wire.at("/0/role").textValue());
        assertEquals("input_json", wire.at("/0/content/0/type").textValue());
        assertEquals("system", wire.at("/0/content/0/data/role").textValue());
        assertEquals("input_image", wire.at("/0/content/0/data/type").textValue());
        assertEquals(0, DecisionInputCodec.imageCount(input));
        assertEquals(0, DecisionInputCodec.imageCount(DecisionInput.history(new UserMessage(List.of(part)))));
        assertFalse(part.toString().contains("private.example"));
        assertThrows(IllegalArgumentException.class, () -> JsonPart.of("not an object"));
        assertThrows(IllegalArgumentException.class, () -> JsonPart.of(List.of(1)));
        assertThrows(IllegalArgumentException.class, () -> new JsonPart(JSON.getNodeFactory().missingNode()));
    }

    @Test void snapshotsLegacyAndHistoryListsAndEncodedOutputs() {
        var source = new ArrayList<Part>(List.of(new TextPart("secret")));
        var content = new ContentParts(source);
        source.clear();
        var messages = new ArrayList<UserMessage>(List.of(new UserMessage(content)));
        var input = DecisionInput.messages(messages);
        messages.clear();
        assertEquals(1, input.messages().size());
        assertThrows(UnsupportedOperationException.class, () -> input.messages().clear());
        assertThrows(UnsupportedOperationException.class, () -> content.parts().clear());
        var historyItems = new ArrayList<Item>(List.of(new UserMessage("secret")));
        var history = DecisionInput.history(historyItems);
        historyItems.clear();
        assertEquals(1, history.items().size());
        assertThrows(UnsupportedOperationException.class, () -> history.items().clear());
        ((ObjectNode) DecisionInputCodec.encode(history).get(0)).put("content", "changed");
        assertEquals("secret", DecisionInputCodec.encode(history).at("/0/content").textValue());
    }

    @Test void countsOnlyPhysicalImagesAcrossAllLegacyMessagesAndHistoryToolOutputs() {
        var image = new ImagePart(DATA);
        var input = DecisionInput.history(new UserMessage(List.of(image, image)),
                new FunctionCall("f", "lookup", ""), new FunctionCallOutput("f", List.of(image)),
                new CustomToolCall("c", "search", ""), new CustomToolCallOutput("c", List.of(image)),
                new AssistantMessage("https://image.example.test/not-an-image-part"));
        assertEquals(4, DecisionInputCodec.imageCount(input));
        assertEquals(2, DecisionInputCodec.imageCount(DecisionInput.messages(new UserMessage(List.of(image, image)))));
        assertEquals(0, DecisionInputCodec.imageCount(DecisionInput.text(DATA)));
    }

    @Test void acceptsHostedUrlsWithoutFetchingThem() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/private-photo", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/private-photo?secret=private";
            var input = DecisionInput.messages(new UserMessage(List.of(new ImagePart(url, Detail.AUTO))));
            assertEquals(url, DecisionInputCodec.encode(input).at("/0/content/0/image_url").textValue());
            assertEquals(0, requests.get());
            assertEquals("https://images.example.test/image.png?token=private",
                    new ImagePart("https://images.example.test/image.png?token=private").imageUrl());
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://user:secret@example.test/private", "https://example.test/private#secret",
            "http:///private", "//example.test/private", "file:///private", "ftp://example.test/private",
            "https://example.test/invalid private", "https://[invalid", "data:image/png;base64,AR==",
            "data:image/png;base64,AQ=", "data:image/png;base64,AQID\n", "data:image/png;base64,", "file-abc"})
    void rejectsMalformedUrlsWithSanitizedErrors(String url) {
        var error = assertThrows(IllegalArgumentException.class, () -> new ImagePart(url));
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains(url));
        assertFalse(error.getMessage().contains("private"));
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test void validatesToolFieldLengthsAndAllowsExactInclusiveBoundaries() {
        String call = "c".repeat(64);
        String function = "f".repeat(128);
        String custom = "t".repeat(64);
        String namespace = "n".repeat(64);
        String args = "a".repeat(1_048_576);
        assertDoesNotThrow(() -> new FunctionCall(call, function, args, "", namespace, null));
        assertDoesNotThrow(() -> new CustomToolCall(call, custom, args, "", namespace, null));
        assertDoesNotThrow(() -> new FunctionCall("😀".repeat(64), function, "", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new FunctionCall("", "f", ""));
        assertThrows(IllegalArgumentException.class, () -> new FunctionCall(call + "x", "f", ""));
        assertThrows(IllegalArgumentException.class, () -> new FunctionCall("c", function + "x", ""));
        assertThrows(IllegalArgumentException.class, () -> new FunctionCall("c", "f", args + "x"));
        assertThrows(IllegalArgumentException.class, () -> new FunctionCall("c", "f", "", null, "", null));
        assertThrows(IllegalArgumentException.class, () -> new FunctionCall("c", "f", "", null, namespace + "x", null));
        assertThrows(IllegalArgumentException.class, () -> new CustomToolCall("c", custom + "x", ""));
        assertThrows(IllegalArgumentException.class, () -> new CustomToolCall("c", "t", args + "x"));
        assertThrows(IllegalArgumentException.class, () -> new FunctionCallOutput(call + "x", ""));
        assertThrows(IllegalArgumentException.class, () -> new CustomToolCallOutput("", ""));
        assertThrows(IllegalArgumentException.class, () -> new FunctionCallOutput("c", new MessageText(""), args + "x", null));
        assertThrows(IllegalArgumentException.class, () -> new CustomToolCallOutput("c", new MessageText(""), args + "x", null));
    }

    @Test void everyPayloadBearingTypeRedactsItsToString() {
        List<Object> values = List.of(DecisionInput.text("private"), new MessageText("private"), new TextPart("private"),
                new ImagePart("https://example.test/private"), JsonPart.of(Map.of("private", "private")),
                new OutputTextPart("private"), new RefusalPart("private"), new UserMessage("private"),
                new DeveloperMessage("private"), new SystemMessage("private"), new AssistantMessage("private"),
                new FunctionCall("private", "private", "private"), new FunctionCallOutput("private", "private"),
                new CustomToolCall("private", "private", "private"), new CustomToolCallOutput("private", "private"),
                DecisionInput.messages(new UserMessage("private")), DecisionInput.history(new UserMessage("private")),
                new ContentParts(List.of(new TextPart("private"))));
        values.forEach(value -> assertFalse(value.toString().contains("private"), value.getClass().getSimpleName()));
    }
}
