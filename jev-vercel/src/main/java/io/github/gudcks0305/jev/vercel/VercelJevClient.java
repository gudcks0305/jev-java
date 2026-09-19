package io.github.gudcks0305.jev.vercel;

import io.github.gudcks0305.jev.internal.AbstractJevClient;
import io.github.gudcks0305.jev.internal.ClientBuilder;
import java.net.URI;

/** Experimental adapter for Vercel AI Gateway's evaluation-model v4 protocol. */
public final class VercelJevClient extends AbstractJevClient {
    private VercelJevClient(Builder builder) { super(builder.configuration(), true); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder extends ClientBuilder<Builder> {
        private Builder() {}
        @Override protected Builder self() { return this; }
        private Config configuration() {
            return configure("AI_GATEWAY_API_KEY", "typesafe-ai/jev", URI.create("https://ai-gateway.vercel.sh"), "v4/ai/evaluation-model");
        }
        public VercelJevClient build() { return new VercelJevClient(this); }
    }
}
