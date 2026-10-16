# Experimental TypeSafe images and Box

**Since 0.4.0.** The Java APIs below expose TypeSafe’s experimental image features.

Jev 1.13 and 1.14 accept still PNG/JPEG images through TypeSafe's System One
API. Box questions require Jev 1.14. TypeSafe marks both features experimental
and warns that request formats, answers, limits, and accuracy can change. Use
them for prototypes, not production-critical decisions. The model catalog still
lists text input, while its dedicated [Images guide](https://docs.typesafe.ai/images)
explicitly documents image input for both releases; treat the dedicated guide
as the image contract and recheck it before upgrading.

This SDK exposes image input only on `TypeSafeJevClient`. OpenAI, OpenRouter,
Vercel, and Cloudflare have different wire formats; this feature does not enable
images or Box for those clients or compatible gateways.

## Add images to a request

Create an image from a local path or byte array, then put its generated
reference in a string value in state, instructions, or criteria. Attach the
image with `JevInput.withImages` and pass that input as the `state` argument to
the existing `evaluate` or `evaluateAsync` API. Record mapping and the Reactor
facade accept the same input.

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
        var input = JevInput.withImages(
                Map.of("photo", photo.reference()), photo);
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

`JevImage.fromPath(Path, format)` throws `IOException`; `fromBytes(byte[],
format)` accepts `png`, `jpg`, or `jpeg`. File paths are not sent. Image bytes
are sent as base64 JSON assets associated with generated UUID references.
`JevInput` deduplicates attached images by reference. Every attached image must
be referenced in a string value in state, question instructions, or criteria.
References inside longer strings are replaced too. Put references in values,
not object keys such as question IDs or Choice labels.

The request opts into TypeSafe's experimental feature automatically by sending
`TypeSafe-Danger-Zone-Images: true` whenever it contains images or a Box
question. The SDK sends JSON assets; it does not use multipart uploads, fetch
remote URLs, resize images, or apply EXIF rotation. Prepare upright, still PNG or
JPEG images before sending. No `jev.*` Spring property is required; select
`jev.provider: typesafe` and configure the model as `jev.model: jev-1.14.0` for
Box requests. The default `jev-latest` currently resolves to Jev 1.13, which
supports images but not Box.

## Box answers

`BoxQuestion.of(id, instructions)` asks for one target. The target must be in an
attached image, and the answer is always a box even if the target is absent.
Pair Box with a `NoulQuestion` that asks whether the target exists, then ignore
the box when that probability is low. A `BoxAnswer` exposes `box()` as
`BoxCoordinates` (`top`, `bottom`, `left`, `right`); each edge is a fraction of
image height or width in the range 0–1. Optional confidence stays absent when
the provider omits it. Coordinates are not pixel values; multiply horizontal
edges by image width and vertical edges by image height before drawing or
cropping.

There is no `@JevBox` record annotation. Existing record annotations can classify
the image-bearing state, but record mapping does not produce Box coordinates.

## Limits and compatibility

The TypeSafe image guide currently sets these limits: PNG/JPEG still images,
16 MiB per file, each side 280–1568 pixels, and 32 MiB for the JSON request,
processed images together, and expanded request. Reusing one reference in
several values sends the image once but counts each occurrence toward expanded
size and image tokens. The SDK enforces the per-file and JSON body byte limits;
the API checks dimensions, decoding, processed-image size, and expanded size.
The server may reject an image even when its file bytes pass SDK validation.

Image input is documented for Jev 1.13 and 1.14. Box requires the explicit
`jev-1.14.0` model (or `jev-preview`, which is a moving alias); prefer the
versioned model for reproducibility. `jev-latest` still points to stable Jev
1.13 according to the current model catalog. Do not infer image or Box support
for other providers from their shared question names or endpoint compatibility.

Adding Box to the sealed `Question` and `Answer` hierarchies may require updates
to exhaustive `switch` expressions in application code.

Sources, checked 2026-10-16: [Images](https://docs.typesafe.ai/images),
[Models](https://docs.typesafe.ai/models), [Box](https://docs.typesafe.ai/primitives/box),
[API reference](https://docs.typesafe.ai/api).

See [validation](validation.md#typesafe-images-and-box-040-2026-10-16)
for offline coverage and the separate, limited live smoke result.
