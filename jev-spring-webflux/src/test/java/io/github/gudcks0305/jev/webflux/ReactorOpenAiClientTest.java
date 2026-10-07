package io.github.gudcks0305.jev.webflux;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gudcks0305.jev.openai.DecisionAnswer;
import io.github.gudcks0305.jev.openai.DecisionInput;
import io.github.gudcks0305.jev.openai.DecisionQuestion;
import io.github.gudcks0305.jev.openai.DecisionRequest;
import io.github.gudcks0305.jev.openai.OpenAiJevClient;
import io.github.gudcks0305.jev.spi.JevTransport;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorOpenAiClientTest {
    private static final DecisionRequest REQUEST = new DecisionRequest(
            DecisionInput.text("test state"),
            List.of(new DecisionQuestion.Predicate("decision", "Decide")));

    @Test
    void nativeRequestIsLazyPerSubscriptionAndCancellationPropagates() {
        RecordingTransport transport = new RecordingTransport();
        try (var client = OpenAiJevClient.builder().apiKey("test-key").transport(transport).build()) {
            var reactor = new ReactorOpenAiClient(client);
            var mono = reactor.decide(REQUEST);
            assertEquals(0, transport.calls.get());
            var first = mono.subscribe();
            assertEquals(1, transport.calls.get());
            var firstFuture = transport.latest;
            assertFalse(firstFuture.isCancelled());
            first.dispose();
            assertTrue(firstFuture.isCancelled());
            var second = mono.subscribe();
            assertEquals(2, transport.calls.get());
            second.dispose();
            assertTrue(transport.latest.isCancelled());
            assertEquals(0, transport.closeCalls.get());
        }
        assertEquals(0, transport.closeCalls.get());
    }

    @Test
    void nativeMixedRefusalRemainsSuccessfulResult() throws Exception {
        JsonNode response = new ObjectMapper().readTree("""
                {
                  "model":"gpt-6-luna",
                  "answers":[
                    {"type":"predicate","name":"decision","probability":0.8},
                    {"type":"refusal","name":"restricted"}
                  ],
                  "usage":{
                    "input_tokens":1,"output_tokens":0,"total_tokens":1,
                    "input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},
                    "output_tokens_details":{"reasoning_tokens":0}
                  }
                }
                """);
        RecordingTransport transport = new RecordingTransport();
        transport.response = response;
        try (var client = OpenAiJevClient.builder().apiKey("test-key").transport(transport).build()) {
            var request = new DecisionRequest(DecisionInput.text("test state"), List.of(
                    new DecisionQuestion.Predicate("decision", "Decide"),
                    new DecisionQuestion.Predicate("restricted", "Decide restricted question")));
            var result = new ReactorOpenAiClient(client).decide(request).block(Duration.ofSeconds(5));
            assertEquals(1, transport.calls.get());
            assertEquals(2, result.answers().size());
            assertInstanceOf(DecisionAnswer.Predicate.class, result.answers().get(0));
            assertInstanceOf(DecisionAnswer.Refusal.class, result.answers().get(1));
        }
    }

    @Test
    void rejectsNullDelegateAndRequest() {
        assertThrows(NullPointerException.class, () -> new ReactorOpenAiClient(null));
        try (var client = OpenAiJevClient.builder().apiKey("test-key").build()) {
            assertThrows(NullPointerException.class, () -> new ReactorOpenAiClient(client).decide(null));
        }
    }

    private static final class RecordingTransport implements JevTransport {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private CompletableFuture<JsonNode> latest;
        private JsonNode response;

        @Override
        public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
            calls.incrementAndGet();
            latest = response == null ? new CompletableFuture<>() : CompletableFuture.completedFuture(response);
            return latest;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
