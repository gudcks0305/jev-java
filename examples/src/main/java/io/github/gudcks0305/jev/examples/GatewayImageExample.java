package io.github.gudcks0305.jev.examples;

import io.github.gudcks0305.jev.BoxQuestion;
import io.github.gudcks0305.jev.JevClient;
import io.github.gudcks0305.jev.JevImage;
import io.github.gudcks0305.jev.JevInput;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.Question;
import io.github.gudcks0305.jev.cloudflare.CloudflareJevClient;
import io.github.gudcks0305.jev.openrouter.OpenRouterJevClient;
import io.github.gudcks0305.jev.vercel.VercelDecisionInput;
import io.github.gudcks0305.jev.vercel.VercelJevClient;
import java.nio.file.Path;
import java.util.Map;

/** Explicit live request: requires the selected provider's credentials and may be billed. */
public final class GatewayImageExample {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException(
                "Usage: GatewayImageExample <openrouter|cloudflare|vercel> <image-path> <png|jpg|jpeg>");
        var image = JevImage.fromPath(Path.of(args[1]), args[2]);
        boolean vercel = args[0].equals("vercel");
        Object input = vercel
                ? VercelDecisionInput.builder().text("A product photo.").image(image).build()
                : JevInput.withImages(Map.of("photo", image.reference()), image);
        var present = NoulQuestion.of("present", "Is a red rectangle present in the provided image?");
        var box = BoxQuestion.of("rectangle", "The red rectangle in `photo`");
        try (JevClient client = switch (args[0]) {
            case "openrouter" -> OpenRouterJevClient.builder().model("typesafe/jev-1.14").maxRetries(0).build();
            case "cloudflare" -> CloudflareJevClient.builder().model("typesafe/jev-1.14.0").maxRetries(0).build();
            case "vercel" -> VercelJevClient.builder().protocol(VercelJevClient.Protocol.DECISION_V4)
                    .model("typesafe-ai/jev-1.14.0").maxRetries(0).build();
            default -> throw new IllegalArgumentException("Unknown provider");
        }) {
            var questions = vercel ? new Question<?>[]{present} : new Question<?>[]{present, box};
            var result = client.evaluate(input, questions);
            System.out.println("model=" + result.model());
            System.out.println("presenceProbability=" + result.answer(present).probability());
            if (!vercel) System.out.println("box=" + result.answer(box));
            // Raw judgments only: validate a presence threshold on your own data
            // before using a Box, which also returns coordinates for absent targets.
        }
    }
}
