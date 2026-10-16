package io.github.gudcks0305.jev.examples;

import io.github.gudcks0305.jev.BoxQuestion;
import io.github.gudcks0305.jev.JevImage;
import io.github.gudcks0305.jev.JevInput;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.nio.file.Path;
import java.util.Map;

/** Opt-in experimental image request; requires TYPESAFE_API_KEY and may be billed. */
public final class ImageExample {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ImageExample <image-path> <png|jpg|jpeg>");
        var image = JevImage.fromPath(Path.of(args[0]), args[1]);
        var input = JevInput.withImages(Map.of("photo", image.reference()), image);
        var present = NoulQuestion.of("present", "Is there a red rectangle in `photo`?");
        var location = BoxQuestion.of("location", "The red rectangle in `photo`");
        try (var client = TypeSafeJevClient.builder().model("jev-1.14.0").maxRetries(0).build()) {
            var result = client.evaluate(input, present, location);
            System.out.println("model=" + result.model());
            System.out.println("presenceProbability=" + result.answer(present).probability());
            System.out.println("box=" + result.answer(location).box());
            System.out.println("confidence=" + result.answer(location).confidence());
            System.out.println("usage=" + result.usage());
            // A Box is always returned, even when the target is absent. Applications
            // must validate a presence threshold on their own data before acting.
        }
    }
}
