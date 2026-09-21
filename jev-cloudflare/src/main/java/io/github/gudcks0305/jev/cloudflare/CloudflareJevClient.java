package io.github.gudcks0305.jev.cloudflare;

import io.github.gudcks0305.jev.internal.AbstractJevClient;
import io.github.gudcks0305.jev.internal.ClientBuilder;
import io.github.gudcks0305.jev.internal.WireFormat;
import java.net.URI;
import java.util.Objects;

/** Cloudflare AI Gateway client for the TypeSafe Jev model. Thread safe. */
public final class CloudflareJevClient extends AbstractJevClient {
    private CloudflareJevClient(Builder builder) {
        super(builder.configuration(), WireFormat.CLOUDFLARE);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends ClientBuilder<Builder> {
        private String accountId;

        private Builder() {}

        @Override
        protected Builder self() {
            return this;
        }

        /** Cloudflare account ID used to construct the default {@code /accounts/{id}/ai/run} endpoint. */
        public Builder accountId(String accountId) {
            this.accountId = Objects.requireNonNull(accountId, "accountId");
            return this;
        }

        private Config configuration() {
            String path = "ai/run";
            if (!hasEndpointOverride()) {
                String id = accountId == null ? System.getenv("CLOUDFLARE_ACCOUNT_ID") : accountId;
                validateAccountId(id);
                path = "accounts/" + id + "/ai/run";
            }
            return configure(
                    "CLOUDFLARE_API_TOKEN",
                    "typesafe/jev",
                    URI.create("https://api.cloudflare.com/client/v4"),
                    path);
        }

        public CloudflareJevClient build() {
            return new CloudflareJevClient(this);
        }

        private static void validateAccountId(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Set accountId or CLOUDFLARE_ACCOUNT_ID");
            }
            boolean unsafe = value.equals(".") || value.equals("..")
                    || value.chars().anyMatch(c ->
                            Character.isWhitespace(c) || Character.isISOControl(c)
                                    || c == '/' || c == '\\' || c == '?' || c == '#' || c == '%');
            if (unsafe) {
                throw new IllegalArgumentException("Invalid Cloudflare account ID format");
            }
        }
    }
}
