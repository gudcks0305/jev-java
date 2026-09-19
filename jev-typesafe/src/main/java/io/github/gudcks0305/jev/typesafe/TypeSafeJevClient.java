package io.github.gudcks0305.jev.typesafe;

import io.github.gudcks0305.jev.internal.AbstractJevClient;
import io.github.gudcks0305.jev.internal.ClientBuilder;
import java.net.URI;

/** TypeSafe's public /v1/systemone API. Thread safe; reuse a client across requests. */
public final class TypeSafeJevClient extends AbstractJevClient {
    private TypeSafeJevClient(Builder builder) { super(builder.configuration(), false); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder extends ClientBuilder<Builder> {
        private Builder() {}
        @Override protected Builder self() { return this; }
        private Config configuration() {
            return configure("TYPESAFE_API_KEY", "jev-latest", URI.create("https://api.typesafe.ai"), "v1/systemone");
        }
        public TypeSafeJevClient build() { return new TypeSafeJevClient(this); }
    }
}
