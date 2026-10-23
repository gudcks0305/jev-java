package io.github.gudcks0305.jev.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.gudcks0305.jev.spi.JevTransport;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** GA contract fixtures only; these tests never contact OpenAI or fetch remote images. */
class DecisionGaTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DecisionInput.ImagePart IMAGE = new DecisionInput.ImagePart("data:image/png;base64,AQ==");
    private static final DecisionQuestion.Predicate QUESTION = new DecisionQuestion.Predicate("ok", "Condition?");

    @Test void encodesMultipartInstructionsForEveryQuestionTypeAndKeepsLegacyAccessors() {
        var parts = new ArrayList<DecisionInput.Part>(List.of(new DecisionInput.TextPart("Compare"), IMAGE,
                DecisionInput.JsonPart.of(Map.of("expected", true))));
        var predicate = DecisionQuestion.Predicate.multipart("p", parts);
        var choice = DecisionQuestion.Choice.multipart("c", parts, List.of(new DecisionQuestion.Option(true), new DecisionQuestion.Option(false)));
        var score = DecisionQuestion.Score.multipart("s", parts, List.of(new DecisionQuestion.Level("No match"), new DecisionQuestion.Level("Match")));
        parts.clear();
        var body = NativeDecisionCodec.request(new DecisionRequest("evidence", List.of(predicate, choice, score)), "model");
        for (int i = 0; i < 3; i++) {
            assertEquals("input_text", body.at("/questions/" + i + "/instructions/0/type").textValue());
            assertEquals("input_image", body.at("/questions/" + i + "/instructions/1/type").textValue());
            assertTrue(body.at("/questions/" + i + "/instructions/2/data/expected").booleanValue());
        }
        assertNull(predicate.instructions());
        assertEquals(3, predicate.instructionParts().size());
        assertThrows(UnsupportedOperationException.class, () -> predicate.instructionParts().clear());
        assertEquals("Condition?", QUESTION.instructions());
        assertTrue(QUESTION.instructionParts().isEmpty());
        assertThrows(NullPointerException.class, () -> new DecisionQuestion.Predicate((String) null));
        assertThrows(IllegalArgumentException.class, () -> DecisionQuestion.Predicate.multipart(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DecisionQuestion.Predicate("p", "text", List.of(IMAGE)));
    }

    @Test void questionInstructionsRejectOutputPartsAndCacheBreakpoints() {
        for (var part : List.<DecisionInput.Part>of(new DecisionInput.OutputTextPart("output"),
                new DecisionInput.RefusalPart("refused"),
                new DecisionInput.TextPart("shared", DecisionInput.CacheBreakpoint.EXPLICIT),
                new DecisionInput.ImagePart(IMAGE.imageUrl(), null, DecisionInput.CacheBreakpoint.EXPLICIT))) {
            assertThrows(IllegalArgumentException.class, () -> DecisionQuestion.Predicate.multipart(List.of(part)));
            assertThrows(IllegalArgumentException.class, () -> DecisionQuestion.Choice.multipart(List.of(part), List.of(new DecisionQuestion.Option("yes"))));
            assertThrows(IllegalArgumentException.class, () -> DecisionQuestion.Score.multipart(List.of(part), List.of(new DecisionQuestion.Level("yes"))));
        }
    }

    @Test void cachingPreservesOmissionAndExplicitModeWithoutBreakpointsIsValid() {
        var baseline = NativeDecisionCodec.request(new DecisionRequest("input", List.of(QUESTION)), "model");
        assertFalse(baseline.has("prompt_cache_key"));
        assertFalse(baseline.has("prompt_cache_options"));
        var request = DecisionRequest.builder().input("input").questions(QUESTION)
                .promptCacheKey("😀".repeat(64))
                .promptCacheOptions(new DecisionCacheOptions(DecisionCacheOptions.Mode.EXPLICIT,
                        DecisionCacheOptions.Ttl.THIRTY_MINUTES)).build();
        var wire = NativeDecisionCodec.request(request, "model");
        assertEquals("explicit", wire.at("/prompt_cache_options/mode").textValue());
        assertEquals("30m", wire.at("/prompt_cache_options/ttl").textValue());
        assertEquals("input", wire.path("input").textValue());
        assertThrows(IllegalArgumentException.class, () -> DecisionRequest.builder().input("input").questions(QUESTION)
                .promptCacheKey("😀".repeat(65)).build());
        assertFalse(request.toString().contains("😀"));
        var defaultOptions = NativeDecisionCodec.request(DecisionRequest.builder().input("input").questions(QUESTION)
                .promptCacheOptions(new DecisionCacheOptions(null, null)).build(), "model");
        assertTrue(defaultOptions.path("prompt_cache_options").isEmpty());
        assertFalse(defaultOptions.has("prompt_cache_retention"));
    }

    @Test void retainsAllExplicitInputMarkersForProviderBoundarySelection() {
        var marked = new DecisionInput.TextPart("shared", DecisionInput.CacheBreakpoint.EXPLICIT);
        var input = DecisionInput.messages(new DecisionInput.UserMessage(Collections.nCopies(5, marked)));
        var request = DecisionRequest.builder().input(input).questions(QUESTION)
                .promptCacheOptions(DecisionCacheOptions.implicit()).build();
        var body = NativeDecisionCodec.request(request, "model");
        assertEquals(5, body.at("/input/0/content").size());
        assertEquals("explicit", body.at("/input/0/content/4/prompt_cache_breakpoint/mode").textValue());
    }

    @Test void enforcesChoiceAndScoreBoundsAtRequestConstruction() {
        for (int count : new int[] {1, 2, 10, 11, 255, 256}) {
            var options = java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> new DecisionQuestion.Option("option" + i)).toList();
            var choice = new DecisionQuestion.Choice("Choose", options);
            var score = new DecisionQuestion.Score("Rate", Collections.nCopies(count, new DecisionQuestion.Level("level")));
            if (count >= 2 && count <= 255) {
                assertDoesNotThrow(() -> new DecisionRequest("input", List.of(choice)));
            } else {
                assertThrows(IllegalArgumentException.class, () -> new DecisionRequest("input", List.of(choice)));
            }
            if (count >= 2 && count <= 10) {
                assertDoesNotThrow(() -> new DecisionRequest("input", List.of(score)));
            } else {
                assertThrows(IllegalArgumentException.class, () -> new DecisionRequest("input", List.of(score)));
            }
        }
    }

    @Test void enforces400QuestionsAndCountsAllImagesAcrossSharedAndQuestionContent() {
        assertDoesNotThrow(() -> new DecisionRequest("evidence", Collections.nCopies(400, QUESTION)));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRequest("evidence", Collections.nCopies(401, QUESTION)));
        var input = DecisionInput.messages(new DecisionInput.UserMessage(Collections.nCopies(64, IMAGE)));
        var question = DecisionQuestion.Predicate.multipart("p", Collections.nCopies(64, IMAGE));
        assertDoesNotThrow(() -> new DecisionRequest(input, List.of(question)));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRequest(input, List.of(question,
                DecisionQuestion.Predicate.multipart(List.of(IMAGE)))));
        var history = DecisionInput.history(new DecisionInput.FunctionCall("call", "tool", "{}"),
                new DecisionInput.FunctionCallOutput("call", new DecisionInput.ContentParts(Collections.nCopies(64, IMAGE)), null, null));
        assertDoesNotThrow(() -> new DecisionRequest(history, List.of(question)));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRequest(history,
                List.of(DecisionQuestion.Predicate.multipart(Collections.nCopies(65, IMAGE)))));
        assertDoesNotThrow(() -> new DecisionRequest(DecisionInput.json(Map.of("image_url", Collections.nCopies(200, IMAGE.imageUrl()))), List.of(QUESTION)));
    }

    @Test void sendsGaRequestOverHttpWithoutFetchingHostedImageLocally() throws Exception {
        var body = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var imageFetches = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> { imageFetches.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.createContext("/decisions", exchange -> {
            body.set(JSON.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {"model":"gpt-6-luna","answers":[{"type":"predicate","name":"ok","probability":0.9}],
                     "usage":{"input_tokens":100,"output_tokens":2,"total_tokens":102,
                       "input_tokens_details":{"cached_tokens":30,"cache_write_tokens":40},
                       "output_tokens_details":{"reasoning_tokens":0}}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        try (var client = OpenAiJevClient.builder().apiKey("fixture-key").endpoint(URI.create(root + "/decisions")).maxRetries(0).build()) {
            var request = DecisionRequest.builder().input(DecisionInput.json(Map.of("order", 123)))
                    .questions(DecisionQuestion.Predicate.multipart("ok", List.of(new DecisionInput.TextPart("Does the photo match?"),
                            new DecisionInput.ImagePart(root + "/image"))))
                    .promptCacheKey("test-partition").promptCacheOptions(DecisionCacheOptions.explicit()).build();
            var result = client.decide(request);
            assertEquals(100, result.usage().inputTokens());
            assertEquals(30, result.usage().cachedTokens());
            assertEquals(40, result.usage().cacheWriteTokens());
            assertEquals("input_json", body.get().at("/input/0/content/0/type").textValue());
            assertEquals(root + "/image", body.get().at("/questions/0/instructions/1/image_url").textValue());
            assertEquals(0, imageFetches.get());
        } finally { server.stop(0); }
    }

    @Test void gaRequestCancellationStillReachesCallerOwnedTransport() {
        var pending = new CompletableFuture<com.fasterxml.jackson.databind.JsonNode>();
        var closed = new AtomicInteger();
        JevTransport transport = new JevTransport() {
            public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> post(URI uri, Map<String,String> headers,
                    com.fasterxml.jackson.databind.JsonNode body) { return pending; }
            public void close() { closed.incrementAndGet(); }
        };
        try (var client = OpenAiJevClient.builder().apiKey("fixture-key").transport(transport).build()) {
            var request = DecisionRequest.builder().input(DecisionInput.json(Map.of("key", "value")))
                    .questions(DecisionQuestion.Predicate.multipart(List.of(IMAGE))).build();
            assertTrue(client.decideAsync(request).cancel(true));
            assertTrue(pending.isCancelled());
        }
        assertEquals(0, closed.get());
    }
}
