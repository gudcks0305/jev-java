package io.github.gudcks0305.jev.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.observation.EvaluationEvent;
import io.github.gudcks0305.jev.spi.JevTransport;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class OpenAiObservationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final NoulQuestion QUESTION = NoulQuestion.of("q", "Synthetic question?");

    @Test void compatibleEvaluationReportsReturnedModelAndUsageOnce() throws Exception {
        var transport = new Stub();
        var events = new CopyOnWriteArrayList<EvaluationEvent>();
        try (var client = OpenAiJevClient.builder().apiKey("test-key").model("requested")
                .transport(transport).observer(events::add).build()) {
            var result = client.evaluateAsync("synthetic state", QUESTION);
            transport.pending.complete(response("predicate"));
            assertEquals(.8, result.join().answer(QUESTION).probability());
        }
        assertEquals(1, events.size());
        var event = events.get(0);
        assertEquals(EvaluationEvent.Outcome.SUCCESS, event.outcome());
        assertEquals("requested", event.requestedModel());
        assertEquals("resolved", event.returnedModel().orElseThrow());
        assertEquals(1, event.questionCount());
        assertEquals(12, event.usage().orElseThrow().inputTokens().orElseThrow());
        assertEquals(0, event.usage().orElseThrow().outputTokens().orElseThrow());
        assertTrue(event.errorKind().isEmpty());
    }

    @Test void compatibleRefusalEmitsFailureWithoutSuccessMetadata() throws Exception {
        var transport = new Stub();
        var events = new CopyOnWriteArrayList<EvaluationEvent>();
        try (var client = OpenAiJevClient.builder().apiKey("test-key")
                .transport(transport).observer(events::add).build()) {
            var result = client.evaluateAsync("synthetic state", QUESTION);
            transport.pending.complete(response("refusal"));
            var failure = assertThrows(CompletionException.class, result::join);
            assertEquals(JevException.Kind.REFUSAL, ((JevException) failure.getCause()).kind());
        }
        assertEquals(1, events.size());
        var event = events.get(0);
        assertEquals(EvaluationEvent.Outcome.FAILURE, event.outcome());
        assertEquals(JevException.Kind.REFUSAL, event.errorKind().orElseThrow());
        assertTrue(event.returnedModel().isEmpty());
        assertTrue(event.usage().isEmpty());
        assertTrue(event.statusCode().isEmpty());
    }

    @Test void nativeSuccessAndRefusalPreserveDecisionUsageWithoutEvaluationEvents() throws Exception {
        var transport = new Stub();
        var events = new CopyOnWriteArrayList<EvaluationEvent>();
        var request = new DecisionRequest(DecisionInput.text("synthetic state"),
                List.of(new DecisionQuestion.Predicate("q", "Synthetic question?")), "request-model");
        try (var client = OpenAiJevClient.builder().apiKey("test-key")
                .transport(transport).observer(events::add).build()) {
            var success = client.decideAsync(request);
            transport.pending.complete(response("predicate"));
            assertEquals("request-model", transport.body.path("model").textValue());
            assertEquals(12, success.join().usage().totalTokens());
            assertEquals(2, success.join().usage().cachedTokens());
            assertEquals("resolved", success.join().model());
            transport.pending = new CompletableFuture<>();
            var refused = client.decideAsync(request);
            transport.pending.complete(response("refusal"));
            assertInstanceOf(DecisionAnswer.Refusal.class, refused.join().answers().get(0));
        }
        assertTrue(events.isEmpty());
    }

    @Test void closeDuringTransportReturnEmitsOnceAndCancelsEventualSource() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pending = new CompletableFuture<JsonNode>();
        var events = new CopyOnWriteArrayList<EvaluationEvent>();
        JevTransport transport = new JevTransport() {
            @Override public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Transport release timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                return pending;
            }
            @Override public void close() {}
        };
        try (var client = OpenAiJevClient.builder().apiKey("test-key")
                .transport(transport).observer(events::add).build()) {
            var invocation = CompletableFuture.supplyAsync(() -> client.evaluateAsync("synthetic state", QUESTION));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                client.close();
            } finally {
                release.countDown();
            }
            var result = invocation.get(5, TimeUnit.SECONDS);
            assertThrows(CompletionException.class, result::join);
            assertTrue(pending.isCancelled());
        }
        assertEquals(1, events.size());
        assertEquals(EvaluationEvent.Outcome.FAILURE, events.get(0).outcome());
        assertEquals(JevException.Kind.CLOSED, events.get(0).errorKind().orElseThrow());
    }

    private static JsonNode response(String type) throws Exception {
        return JSON.readTree("""
                {"model":"resolved","answers":[{"name":"q","type":"%s","probability":0.8}],
                 "usage":{"input_tokens":12,"output_tokens":0,"total_tokens":12,
                  "input_tokens_details":{"cached_tokens":2,"cache_write_tokens":0},
                  "output_tokens_details":{"reasoning_tokens":0}}}
                """.formatted(type));
    }

    private static final class Stub implements JevTransport {
        CompletableFuture<JsonNode> pending = new CompletableFuture<>();
        JsonNode body;
        @Override public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
            this.body = body;
            return pending;
        }
        @Override public void close() {}
    }
}
