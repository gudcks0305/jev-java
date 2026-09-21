package io.github.gudcks0305.jev.internal;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Mapping that retains the cancellation link to an SDK request. */
public final class CancellableFutures {
    private CancellableFutures() {}

    public static <T, R> CompletableFuture<R> map(CompletableFuture<T> source, Function<T, R> mapper) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mapper, "mapper");
        CompletableFuture<R> result = new CompletableFuture<>();
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) source.cancel(true);
        });
        source.whenComplete((value, failure) -> {
            if (result.isDone()) return;
            if (failure != null) result.completeExceptionally(failure);
            else {
                try { result.complete(mapper.apply(value)); }
                catch (Throwable ex) { result.completeExceptionally(ex); }
            }
        });
        return result;
    }
}
