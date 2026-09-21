package io.github.gudcks0305.jev;

import io.github.gudcks0305.jev.internal.CancellableFutures;
import io.github.gudcks0305.jev.schema.JevSchema;
import io.github.gudcks0305.jev.schema.TypedEvaluation;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public interface JevClient extends AutoCloseable {
    CompletableFuture<Evaluation> evaluateAsync(Object state, Question<?>... questions);

    /** Evaluates all annotated record fields in one native question batch. */
    default <T> TypedEvaluation<T> evaluate(Object state, JevSchema<T> schema) {
        Objects.requireNonNull(schema, "schema");
        return schema.decode(evaluate(state, schema.questions().toArray(Question<?>[]::new)));
    }

    default <T> TypedEvaluation<T> evaluate(Object state, Class<T> recordType) {
        return evaluate(state, JevSchema.of(recordType));
    }

    /** Cancellation of this mapped future propagates to the underlying evaluation. */
    default <T> CompletableFuture<TypedEvaluation<T>> evaluateAsync(Object state, JevSchema<T> schema) {
        Objects.requireNonNull(schema, "schema");
        return CancellableFutures.map(evaluateAsync(state, schema.questions().toArray(Question<?>[]::new)), schema::decode);
    }

    default <T> CompletableFuture<TypedEvaluation<T>> evaluateAsync(Object state, Class<T> recordType) {
        return evaluateAsync(state, JevSchema.of(recordType));
    }

    /** Blocking convenience API. Use evaluateAsync or ReactorJevClient on reactive event loops. */
    default Evaluation evaluate(Object state, Question<?>... questions) {
        CompletableFuture<Evaluation> future = evaluateAsync(state, questions);
        try {
            return future.get();
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new JevException(JevException.Kind.CONNECTION, "Evaluation interrupted");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw new JevException(JevException.Kind.CONNECTION, "Evaluation failed");
        }
    }
    @Override void close();
}
