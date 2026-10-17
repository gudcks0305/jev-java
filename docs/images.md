# Experimental images and Box

Jev image input and Box judgments are experimental. TypeSafe describes them as
prototype features whose formats, limits, answers, and accuracy may change.
Use them to explore ideas; validate carefully before relying on results.

The `JevImage` and `JevInput` APIs arrived in 0.4.0 for TypeSafe. Version 0.5.0
adds image and Box support to OpenRouter and Cloudflare, plus a separate image
state API for Vercel's decision-model v4 protocol. Existing provider defaults
and Vercel's default protocol stay unchanged.

| Client and protocol | Images | Box | Model for Jev 1.14 |
| --- | --- | --- | --- |
| `TypeSafeJevClient` | State, instructions, criteria | Yes | `jev-1.14.0` |
| `OpenRouterJevClient` | State, instructions, criteria | Yes | `typesafe/jev-1.14` |
| `CloudflareJevClient` | State, instructions, criteria | Yes | `typesafe/jev-1.14.0` |
| `VercelJevClient` with `DECISION_V4` | State only | No | `typesafe-ai/jev-1.14.0` |

OpenAI's existing native Decisions image input remains separate and uses
`DecisionInput.ImagePart`; the new `JevImage` API does not add Box to OpenAI.
Do not infer support for other compatible gateways from these clients.
The listed routes have documented contracts and offline tests; see the live
verification limits below. The
Vercel-compatible `/typesafe/v1/systemone` route has not been verified for
image assets or Box.

## TypeSafe, OpenRouter, and Cloudflare

Create a `JevImage` from PNG/JPEG bytes or a local path, then attach it to the
state passed to the usual `evaluate` or `evaluateAsync` method. The same state
works with Java record mapping and the Reactor API. Put `image.reference()` in a
string value in state, question instructions, or criteria. References may also
appear inside longer strings. Object keys, such as question IDs and Choice
labels, remain unchanged.

```java
import io.github.gudcks0305.jev.BoxQuestion;
import io.github.gudcks0305.jev.JevImage;
import io.github.gudcks0305.jev.JevInput;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.nio.file.Path;
import java.util.Map;

public final class ImageExample {
    public static void main(String[] args) throws Exception {
        var photo = JevImage.fromPath(Path.of("kitchen.jpg"), "jpg");
        var input = JevInput.withImages(Map.of("photo", photo.reference()), photo);
        var visible = NoulQuestion.of("mug_visible", "Is there a coffee mug in `photo`?");
        var mug = BoxQuestion.of("mug", "The coffee mug in `photo`");

        try (var client = TypeSafeJevClient.builder().model("jev-1.14.0").build()) {
            var result = client.evaluate(input, visible, mug);
            if (result.answer(visible).atLeast(.8)) {
                // Example threshold only; validate on your own images.
                var box = result.answer(mug).box();
                System.out.printf("left=%.3f top=%.3f right=%.3f bottom=%.3f%n",
                        box.left(), box.top(), box.right(), box.bottom());
            }
        }
    }
}
```

Pin the provider model when using Box. Examples: `.model("jev-1.14.0")` for
TypeSafe, `.model("typesafe/jev-1.14")` for OpenRouter, and
`.model("typesafe/jev-1.14.0")` for Cloudflare. Their defaults remain
unchanged and may resolve to Jev 1.13. Image requests through direct TypeSafe
send `TypeSafe-Danger-Zone-Images: true`; OpenRouter and Cloudflare use their
own request formats and do not send this TypeSafe-only header.

For TypeSafe and Cloudflare, images are sent once as base64 JSON assets. For
OpenRouter, each reference becomes an inline `image_url` data URL at its use
site. In OpenRouter requests, an exact reference becomes an image part; a
reference inside text becomes ordered text and image parts. Repeated references
therefore repeat the image data and count toward the 32 MiB request cap. All
attached images must be used in state, instructions, or criteria.

`JevImage.fromPath(Path, format)` throws `IOException`; `fromBytes(byte[],
format)` accepts `png`, `jpg`, or `jpeg`. Paths are not sent. The SDK validates
file bytes up to 16 MiB and the encoded request body up to 32 MiB. The TypeSafe
image contract also limits dimensions to 280–1568 pixels per side, decoded
image total/expanded request size to 32 MiB, and image formats to still PNG or
JPEG. The server validates image decoding, dimensions, processed assets, and
expanded size. Prepare pixels upright before sending: the SDK does not resize,
fetch URLs, or rotate EXIF metadata. The API may reject a file that passes
client-side byte checks.

## Box answers

`BoxQuestion.of(id, instructions)` locates one target in an attached image. A
Box answer always contains coordinates, even when the target is absent. Pair a
Box question with a Noul question that checks whether the target exists; ignore
the coordinates when the probability is low.

`BoxAnswer.box()` returns `BoxCoordinates` with `top`, `bottom`, `left`, and
`right` edges. Each edge is a fraction from 0 to 1. Multiply horizontal edges
by image width and vertical edges by image height before drawing or cropping.
`confidence()` is optional and remains absent if the provider omits it. No
`@JevBox` record annotation exists; record mapping can classify image state but
does not produce Box coordinates.

## Vercel decision-model v4

The existing Vercel evaluation-model v4 protocol does not define file inputs.
Opt into the separate decision-model v4 protocol and pass a
`VercelDecisionInput` as state:

```java
import io.github.gudcks0305.jev.JevImage;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.vercel.VercelDecisionInput;
import io.github.gudcks0305.jev.vercel.VercelJevClient;
import java.nio.file.Path;

var image = JevImage.fromPath(Path.of("receipt.png"), "png");
var input = VercelDecisionInput.builder()
        .text("A product photo from a support ticket.")
        .image(image)
        .build();
var red = NoulQuestion.of("red", "Is the image predominantly red?");
try (var client = VercelJevClient.builder()
        .protocol(VercelJevClient.Protocol.DECISION_V4)
        .model("typesafe-ai/jev-1.14.0")
        .build()) {
    var result = client.evaluate(input, red);
    System.out.println(result.answer(red).probability());
}
```

`VercelDecisionInput.Builder` accepts ordered `.text(String)`, `.json(Object)`,
and `.image(JevImage)` state parts. Images are supported only in state, not in
question instructions or criteria. Vercel decision-model v4 supports Choice,
Score, and Noul questions; it does not support Box. `JevInput` is not accepted
by this protocol.

The decision-model v4 request uses `/v4/ai/decision-model`, a
`stateParts` array, and Vercel's `ai-decision-model-specification-version: 4`
header. This differs from the default evaluation-model v4 wire format. The
legacy evaluation protocol remains the default; see the [Vercel guide](vercel-ai-gateway.md)
for Java and Spring configuration.

## Contract and verification

The model catalog currently maps `jev-latest` to stable `jev-1.13.0` and
`jev-preview` to `jev-1.14.0`. Jev 1.13 and 1.14 accept images. Box requires
Jev 1.14, so use a versioned model ID for reproducibility. TypeSafe's model
table still lists text input, while the dedicated images guide documents image
support; this SDK follows the dedicated images contract for the feature.

OpenRouter and Cloudflare were implemented against their documented image and
Box shapes using local protocol fixtures; live inference has not been verified
because credentials were unavailable. Vercel request serialization is covered
offline; successful live image inference is not claimed here. Direct TypeSafe
validation is recorded in [validation](validation.md).

Sources checked 2026-10-17: [TypeSafe Images](https://docs.typesafe.ai/images),
[TypeSafe Models](https://docs.typesafe.ai/models), [TypeSafe Box](https://docs.typesafe.ai/primitives/box),
[TypeSafe API](https://docs.typesafe.ai/api), [Cloudflare Jev 1.14](https://developers.cloudflare.com/ai/models/typesafe/jev-1.14.0/),
[OpenRouter multimodal Decisions](https://openrouter.ai/docs/guides/community/multimodal-decisions),
[OpenRouter Box](https://openrouter.ai/docs/guides/community/jev#box-questions),
[Vercel Jev 1.14](https://vercel.com/changelog/typesafe-ais-jev-1-14-is-now-available-on-ai-gateway),
[Vercel TypeSafe-compatible API](https://vercel.com/docs/ai-gateway/sdks-and-apis/typesafe).
