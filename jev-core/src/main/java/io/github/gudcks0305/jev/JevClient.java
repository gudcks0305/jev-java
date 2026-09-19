package io.github.gudcks0305.jev;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public interface JevClient extends AutoCloseable {
    CompletableFuture<Evaluation> evaluateAsync(Object state, Question<?>... questions);

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
