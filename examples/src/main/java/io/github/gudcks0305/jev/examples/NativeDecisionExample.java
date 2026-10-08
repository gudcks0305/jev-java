package io.github.gudcks0305.jev.examples;

import io.github.gudcks0305.jev.openai.DecisionAnswer;
import io.github.gudcks0305.jev.openai.DecisionInput;
import io.github.gudcks0305.jev.openai.DecisionQuestion;
import io.github.gudcks0305.jev.openai.DecisionRequest;
import io.github.gudcks0305.jev.openai.DecisionValue;
import io.github.gudcks0305.jev.openai.OpenAiJevClient;
import java.util.List;

/** Opt-in native OpenAI request; requires OPENAI_API_KEY and may be billed. */
public final class NativeDecisionExample {
    public static void main(String[] args) {
        var request = DecisionRequest.builder()
                .input(DecisionInput.text("The package arrived with a cracked screen."))
                .questions(
                        new DecisionQuestion.Predicate(null, "Does the customer report product damage?"),
                        new DecisionQuestion.Choice("route", "Is this a damage claim?",
                                List.of(new DecisionQuestion.Option(DecisionValue.bool(true), "Product damage"),
                                        new DecisionQuestion.Option(DecisionValue.bool(false), "Other requests"))),
                        new DecisionQuestion.Score("severity", "How severe is the damage?",
                                List.of(new DecisionQuestion.Level("Low", "Cosmetic only"),
                                        new DecisionQuestion.Level("High", "Product may not work"))))
                .build();
        try (var client = OpenAiJevClient.builder().build()) {
            var result = client.decide(request);
            for (int i = 0; i < result.answers().size(); i++) {
                var answer = result.answers().get(i);
                if (answer instanceof DecisionAnswer.Refusal) {
                    System.out.printf("question=%d refused%n", i);
                } else {
                    System.out.printf("question=%d type=%s%n", i, answer.getClass().getSimpleName());
                }
            }
            System.out.printf("model=%s inputTokens=%d outputTokens=%d%n", result.model(),
                    result.usage().inputTokens(), result.usage().outputTokens());
        }
    }
}
