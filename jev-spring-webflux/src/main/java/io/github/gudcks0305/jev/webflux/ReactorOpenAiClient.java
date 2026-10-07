package io.github.gudcks0305.jev.webflux;

import io.github.gudcks0305.jev.openai.DecisionRequest;
import io.github.gudcks0305.jev.openai.DecisionResult;
import io.github.gudcks0305.jev.openai.OpenAiJevClient;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Lazy native Decisions facade over a caller-owned {@link OpenAiJevClient}. */
public final class ReactorOpenAiClient {
    private final OpenAiJevClient delegate;

    public ReactorOpenAiClient(OpenAiJevClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Starts one independent request per subscription. Cancellation reaches the delegate
     * future; this facade does not close or otherwise own the delegate.
     */
    public Mono<DecisionResult> decide(DecisionRequest request) {
        Objects.requireNonNull(request, "request");
        return Mono.defer(() -> Mono.fromFuture(delegate.decideAsync(request)));
    }
}
