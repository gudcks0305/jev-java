package io.github.gudcks0305.jev.vercel;

import io.github.gudcks0305.jev.internal.AbstractJevClient;
import io.github.gudcks0305.jev.internal.ClientBuilder;
import io.github.gudcks0305.jev.internal.EvaluationCodec;
import io.github.gudcks0305.jev.internal.JsonSupport;
import io.github.gudcks0305.jev.internal.WireFormat;
import io.github.gudcks0305.jev.Question;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** Experimental adapter for Vercel AI Gateway's evaluation and decision v4 protocols. */
public final class VercelJevClient extends AbstractJevClient {
    public enum Protocol { EVALUATION_V4, DECISION_V4 }
    private final Protocol protocol;
    private VercelJevClient(Builder builder) {
        super(builder.configuration(), builder.protocol == Protocol.DECISION_V4 ? WireFormat.VERCEL_DECISION : WireFormat.VERCEL);
        this.protocol = builder.protocol;
    }
    public static Builder builder() { return new Builder(); }

    @Override protected JsonNode encodeRequest(Object state, Map<String, Question<?>> questions) {
        if (state instanceof VercelDecisionInput input) {
            if (protocol != Protocol.DECISION_V4) {
                throw new IllegalArgumentException("VercelDecisionInput requires decision-model v4");
            }
            ObjectNode body = EvaluationCodec.request("state parts", questions, configuredModel(), WireFormat.VERCEL_DECISION);
            body.set("stateParts", input.stateParts());
            return checked(body);
        }
        JsonNode body = super.encodeRequest(state, questions);
        return protocol == Protocol.DECISION_V4 ? checked(body) : body;
    }

    private static JsonNode checked(JsonNode body) {
        if (JsonSupport.utf8Bytes(body).length > 32 * 1024 * 1024) {
            throw new IllegalArgumentException("Vercel decision request body exceeds 32 MiB");
        }
        return body;
    }

    public static final class Builder extends ClientBuilder<Builder> {
        private Protocol protocol = Protocol.EVALUATION_V4;
        private Builder() {}
        public Builder protocol(Protocol protocol) { this.protocol = Objects.requireNonNull(protocol, "protocol"); return this; }
        @Override protected Builder self() { return this; }
        private Config configuration() {
            return configure("AI_GATEWAY_API_KEY", "typesafe-ai/jev", URI.create("https://ai-gateway.vercel.sh"),
                    protocol == Protocol.DECISION_V4 ? "v4/ai/decision-model" : "v4/ai/evaluation-model");
        }
        public VercelJevClient build() { return new VercelJevClient(this); }
    }
}
