package io.github.gudcks0305.jev.examples;

import io.github.gudcks0305.jev.openai.DecisionCacheOptions;
import io.github.gudcks0305.jev.openai.DecisionInput;
import io.github.gudcks0305.jev.openai.DecisionQuestion;
import io.github.gudcks0305.jev.openai.DecisionRequest;
import io.github.gudcks0305.jev.openai.OpenAiJevClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Opt-in GA request: requires OPENAI_API_KEY, may be billed, and sends any supplied image to OpenAI. */
public final class GaDecisionExample {
    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: GaDecisionExample [png-path]");
        var input = DecisionInput.history(
                new DecisionInput.DeveloperMessage("Evaluate the supplied evidence only."),
                new DecisionInput.UserMessage(List.of(
                        DecisionInput.JsonPart.of(Map.of("expected_color", "red", "expected_shape", "rectangle")),
                        new DecisionInput.TextPart("Use this reference for each candidate.").withCacheBreakpoint())),
                new DecisionInput.FunctionCall("lookup", "lookup_reference", "{}"),
                new DecisionInput.FunctionCallOutput("lookup", "The reference describes a red rectangle."));
        // Tool items are supplied evidence; the SDK does not execute these calls.
        DecisionQuestion question = new DecisionQuestion.Predicate("match", "Does the tool evidence agree with the reference JSON?");
        if (args.length == 1) {
            var image = Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(args[0])));
            question = DecisionQuestion.Predicate.multipart("match", List.of(
                    new DecisionInput.TextPart("Does this image match the expected color and shape?"),
                    new DecisionInput.ImagePart("data:image/png;base64," + image)));
        }
        var request = DecisionRequest.builder().input(input).questions(question)
                .promptCacheKey("jev-java-ga-example")
                .promptCacheOptions(new DecisionCacheOptions(DecisionCacheOptions.Mode.EXPLICIT,
                        DecisionCacheOptions.Ttl.THIRTY_MINUTES)).build();
        try (var client = OpenAiJevClient.builder().maxRetries(0).build()) {
            var result = client.decide(request);
            System.out.println("model=" + result.model());
            System.out.println("answer=" + result.answer(0));
            System.out.println("usage=" + result.usage());
        }
    }
}
