package io.github.gudcks0305.jev.openai;

import io.github.gudcks0305.jev.internal.AbstractJevClient;
import io.github.gudcks0305.jev.internal.ClientBuilder;
import io.github.gudcks0305.jev.internal.WireFormat;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import io.github.gudcks0305.jev.JevException;

/**
 * OpenAI's beta Decisions API. Reuse a client across requests.
 * The inherited evaluate methods support text-based Jev questions and fail a whole
 * evaluation on refusal. The native decide methods support images, typed boolean
 * choices, and per-question refusal results.
 */
public final class OpenAiJevClient extends AbstractJevClient {
    private OpenAiJevClient(Builder builder) { super(builder.configuration(), WireFormat.OPENAI); }
    public static Builder builder() { return new Builder(); }

    /** Native ordered Decisions request. Cancellation reaches HTTP and pending retry delays. */
    public CompletableFuture<DecisionResult> decideAsync(DecisionRequest request) {
        return executeAsync(() -> NativeDecisionCodec.request(Objects.requireNonNull(request, "request"), configuredModel()),
                response -> NativeDecisionCodec.response(response, request));
    }

    /** Blocking native request. Use decideAsync on reactive event loops. */
    public DecisionResult decide(DecisionRequest request) {
        CompletableFuture<DecisionResult> future = decideAsync(request);
        try {
            return future.get();
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new JevException(JevException.Kind.CONNECTION, "Decision interrupted");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new JevException(JevException.Kind.CONNECTION, "Decision failed");
        }
    }

    public static final class Builder extends ClientBuilder<Builder> {
        private Builder() {}
        @Override protected Builder self() { return this; }
        private Config configuration() {
            return configure("OPENAI_API_KEY", "gpt-6-luna", URI.create("https://api.openai.com"), "v1/decisions");
        }
        public OpenAiJevClient build() { return new OpenAiJevClient(this); }
    }
}
