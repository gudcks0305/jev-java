package io.github.gudcks0305.jev.webflux;

import io.github.gudcks0305.jev.Evaluation;
import io.github.gudcks0305.jev.JevClient;
import io.github.gudcks0305.jev.Question;
import io.github.gudcks0305.jev.schema.JevSchema;
import io.github.gudcks0305.jev.schema.TypedEvaluation;
import reactor.core.publisher.Mono;

import java.util.Objects;

/** Lazy Reactor facade over a caller-owned {@link JevClient}. */
public final class ReactorJevClient {
    private final JevClient delegate;

    public ReactorJevClient(JevClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Starts one independent evaluation per subscription. Cancelling the subscription cancels
     * the delegate future. This facade does not close or otherwise own the delegate.
     */
    public Mono<Evaluation> evaluate(Object state, Question<?>... questions) {
        Question<?>[] submittedQuestions = Objects.requireNonNull(questions, "questions").clone();
        return Mono.defer(() -> Mono.fromFuture(
                delegate.evaluateAsync(state, submittedQuestions.clone())));
    }

    /** Lazy record evaluation; subscription cancellation reaches the HTTP request. */
    public <T> Mono<TypedEvaluation<T>> evaluate(Object state, JevSchema<T> schema) {
        Objects.requireNonNull(schema, "schema");
        return Mono.defer(() -> Mono.fromFuture(delegate.evaluateAsync(state, schema)));
    }

    public <T> Mono<TypedEvaluation<T>> evaluate(Object state, Class<T> recordType) {
        Objects.requireNonNull(recordType, "recordType");
        return Mono.defer(() -> Mono.fromFuture(delegate.evaluateAsync(state, recordType)));
    }
}
