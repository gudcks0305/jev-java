package io.github.gudcks0305.jev.examples;

import io.github.gudcks0305.jev.JevClient;
import io.github.gudcks0305.jev.cloudflare.CloudflareJevClient;
import io.github.gudcks0305.jev.openrouter.OpenRouterJevClient;
import io.github.gudcks0305.jev.schema.JevBoolean;
import io.github.gudcks0305.jev.schema.JevChoice;
import io.github.gudcks0305.jev.schema.JevLabel;
import io.github.gudcks0305.jev.schema.JevProbability;
import io.github.gudcks0305.jev.schema.JevSchema;
import io.github.gudcks0305.jev.schema.JevScore;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import io.github.gudcks0305.jev.vercel.VercelJevClient;

/** Opt-in live example: the record is the question schema; full answers remain available. */
public final class RecordExample {
    public enum Department {
        @JevLabel("Payments, invoices and refunds") BILLING,
        @JevLabel("Bugs, outages and integrations") TECHNICAL
    }

    public record Triage(
            @JevChoice("Which team should handle this customer request?") Department department,
            @JevBoolean(value = "Does the customer need help today?", threshold = .8) boolean urgent,
            @JevProbability("Is the customer requesting a refund?") double refundProbability,
            @JevScore(value = "How severe is the customer's problem?",
                    levels = {"No problem", "Minor inconvenience", "Service or payment blocked"}) double severity) {}

    public static void main(String[] args) {
        String provider = args.length == 0 ? "typesafe" : args[0];
        try (JevClient client = switch (provider) {
            case "typesafe" -> TypeSafeJevClient.builder().build();
            case "openrouter" -> OpenRouterJevClient.builder().build();
            case "vercel" -> VercelJevClient.builder().build();
            case "cloudflare" -> CloudflareJevClient.builder().build();
            default -> throw new IllegalArgumentException("Unknown provider");
        }) {
            JevSchema<Triage> schema = JevSchema.of(Triage.class);
            var result = client.evaluate("I was charged twice. Please refund the duplicate payment today.", schema);
            System.out.println(result.value());
            System.out.printf("provider=%s model=%s questions=%d inputTokens=%s%n", provider,
                    result.evaluation().model(), schema.questions().size(), result.evaluation().usage().inputTokens());
        }
    }
}
