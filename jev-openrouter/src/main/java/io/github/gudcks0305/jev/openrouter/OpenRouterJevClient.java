package io.github.gudcks0305.jev.openrouter;

import io.github.gudcks0305.jev.internal.AbstractJevClient;
import io.github.gudcks0305.jev.internal.ClientBuilder;
import io.github.gudcks0305.jev.internal.WireFormat;
import java.net.URI;

/** OpenRouter Decisions API client. Thread safe; reuse a client across requests. */
public final class OpenRouterJevClient extends AbstractJevClient {
    private OpenRouterJevClient(Builder builder) {
        super(builder.configuration(), WireFormat.OPENROUTER);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends ClientBuilder<Builder> {
        private Builder() {}

        @Override
        protected Builder self() {
            return this;
        }

        private Config configuration() {
            return configure(
                    "OPENROUTER_API_KEY",
                    "typesafe/jev-1.13",
                    URI.create("https://openrouter.ai"),
                    "api/alpha/decisions");
        }

        public OpenRouterJevClient build() {
            return new OpenRouterJevClient(this);
        }
    }
}
