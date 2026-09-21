package io.github.gudcks0305.jev.examples;

import io.github.gudcks0305.jev.*;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import io.github.gudcks0305.jev.vercel.VercelJevClient;
import io.github.gudcks0305.jev.openrouter.OpenRouterJevClient;
import io.github.gudcks0305.jev.cloudflare.CloudflareJevClient;
import java.util.List;

/** A small, opt-in live request. Requires the selected provider's API key in the environment. */
public final class Quickstart {
    public enum Department { BILLING, TECHNICAL, SALES }

    public static void main(String[] args) {
        String provider = args.length == 0 ? "typesafe" : args[0];
        try (JevClient client = switch (provider) {
            case "typesafe" -> TypeSafeJevClient.builder().build();
            case "vercel" -> VercelJevClient.builder().build();
            case "openrouter" -> OpenRouterJevClient.builder().build();
            case "cloudflare" -> CloudflareJevClient.builder().build();
            default -> throw new IllegalArgumentException("Provider must be typesafe, vercel, openrouter, or cloudflare");
        }) {
            var route = ChoiceQuestion.of("route", "Which department should handle this?", Department.class);
            var billing = NoulQuestion.of("billing", "Does the message describe a billing problem?");
            var severity = ScoreQuestion.of("severity", "How severe is the problem?", List.of("No problem", "Minor problem", "Major problem"));
            var result = client.evaluate("A customer requests a refund after being charged twice.", route, billing, severity);
            System.out.printf("provider=%s model=%s department=%s billingProbability=%.2f severity=%.2f inputTokens=%s%n",
                    provider, result.model(), result.answer(route).choice(), result.answer(billing).probability(),
                    result.answer(severity).score(), result.usage().inputTokens());
        } catch (JevException ex) {
            System.err.printf("Jev request failed: kind=%s status=%d%n", ex.kind(), ex.statusCode());
            throw ex;
        }
    }
}
