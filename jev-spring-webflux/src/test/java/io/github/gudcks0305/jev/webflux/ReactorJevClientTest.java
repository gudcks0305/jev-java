package io.github.gudcks0305.jev.webflux;

import io.github.gudcks0305.jev.Evaluation;
import io.github.gudcks0305.jev.JevClient;
import io.github.gudcks0305.jev.Question;
import io.github.gudcks0305.jev.schema.JevBoolean;
import io.github.gudcks0305.jev.schema.JevSchema;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorJevClientTest {
    record Decision(@JevBoolean(value = "Is this urgent?", threshold = .8) boolean urgent) {}

    @Test
    void recordEvaluationRemainsLazyAndCancellationReachesNativeFuture() {
        RecordingClient delegate = new RecordingClient();
        ReactorJevClient client = new ReactorJevClient(delegate);
        var schema = JevSchema.of(Decision.class);
        var mono = client.evaluate("state", schema);
        assertEquals(0, delegate.calls.get());
        Disposable subscription = mono.subscribe();
        assertEquals(1, delegate.calls.get());
        subscription.dispose();
        assertTrue(delegate.latest.isCancelled());
        Disposable byClass = client.evaluate("state", Decision.class).subscribe();
        assertEquals(2, delegate.calls.get());
        byClass.dispose();
        assertTrue(delegate.latest.isCancelled());
    }

    @Test
    void evaluationIsLazyPerSubscriptionAndCancellationPropagates() {
        RecordingClient delegate = new RecordingClient();
        ReactorJevClient client = new ReactorJevClient(delegate);

        var evaluation = client.evaluate("state");
        assertEquals(0, delegate.calls.get());

        Disposable first = evaluation.subscribe();
        assertEquals(1, delegate.calls.get());
        CompletableFuture<Evaluation> firstFuture = delegate.latest;
        assertFalse(firstFuture.isCancelled());

        first.dispose();
        assertTrue(firstFuture.isCancelled());

        Disposable second = evaluation.subscribe();
        assertEquals(2, delegate.calls.get());
        second.dispose();
        assertTrue(delegate.latest.isCancelled());
        assertEquals(0, delegate.closeCalls.get());
    }

    private static final class RecordingClient implements JevClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private volatile CompletableFuture<Evaluation> latest;

        @Override
        public CompletableFuture<Evaluation> evaluateAsync(
                Object state, Question<?>... questions) {
            calls.incrementAndGet();
            latest = new CompletableFuture<>();
            return latest;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
