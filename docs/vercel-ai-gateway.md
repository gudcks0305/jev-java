# Vercel AI Gateway

`jev-vercel` supports two distinct AI SDK protocols. The default remains the
legacy evaluation-model v4 protocol. Opt into decision-model v4 for image state
with `VercelJevClient.Protocol.DECISION_V4`. Both use
`AI_GATEWAY_API_KEY` and model `typesafe-ai/jev` by default. Pin
`typesafe-ai/jev-1.14.0` when using images.

## Decision-model v4 with image state

Vercel documents Jev 1.14 image input as PNG/JPEG file parts in the shared
state. Build ordered state parts with `VercelDecisionInput`; use `.text(...)`,
`.json(...)`, and `.image(...)` as needed.

```java
import io.github.gudcks0305.jev.JevImage;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.vercel.VercelDecisionInput;
import io.github.gudcks0305.jev.vercel.VercelJevClient;
import java.nio.file.Path;

public final class VercelImageExample {
    public static void main(String[] args) throws Exception {
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
    }
}
```

`VercelDecisionInput.Builder` accepts `.text(String)`, `.json(Object)`, and
`.image(JevImage)` parts in insertion order. Images are supported in state
only; question instructions and criteria are JSON values, not file parts.
Pass `VercelDecisionInput` as the state argument to `evaluate` or
`evaluateAsync`. Record and Reactor evaluation accept it through their state
argument as well. `JevInput` is for TypeSafe, OpenRouter, and Cloudflare and is
rejected by Vercel decision-model v4.

Decision-model v4 sends `POST /v4/ai/decision-model` with
`ai-decision-model-specification-version: 4`, `ai-model-id`, and a `stateParts`
array. A file part carries `type: "file"`, `mediaType`, and tagged base64 data
`{type: "data", data: ...}`. The Gateway supports Choice, Score, and Noul
questions. Box is not supported by its published decision interface or answer
schema.

To configure decision-model v4 in Spring Boot, add the starter and select the
protocol:

```yaml
jev:
  provider: vercel
  vercel-protocol: decision-v4
  model: typesafe-ai/jev-1.14.0
  api-key: ${AI_GATEWAY_API_KEY}
```

The new protocol is opt-in. Existing plain Java and Spring users continue to
use evaluation-model v4 unless they select `DECISION_V4` or
`jev.vercel-protocol: decision-v4`.

Use `baseUrl` for an origin/path prefix: the client appends the complete
`v4/ai/decision-model` suffix for the new protocol. Use `endpoint` for a full
URL instead. Do not include the suffix in `baseUrl` or configure both options.
Any refused question fails the whole evaluation with `JevException.Kind.REFUSAL`.

## Existing evaluation-model v4 protocol

The default Java configuration stays:

```java
try (var client = VercelJevClient.builder().build()) {
    // evaluation-model v4
}
```

It posts JSON `{state, questions}` to
`https://ai-gateway.vercel.sh/v4/ai/evaluation-model` with
`ai-gateway-protocol-version: 0.0.1`, `ai-gateway-auth-method: api-key`,
`ai-evaluation-model-specification-version: 4`, and `ai-model-id`. This protocol
does not define file parts. It continues to support text and JSON state only.

## TypeSafe-compatible route

Vercel also provides `/typesafe/v1/systemone`. Configure the existing
`TypeSafeJevClient` with a Gateway key, model, and full endpoint:

```java
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.net.URI;

var client = TypeSafeJevClient.builder()
        .apiKey(System.getenv("AI_GATEWAY_API_KEY"))
        .model("typesafe-ai/jev")
        .endpoint(URI.create("https://ai-gateway.vercel.sh/typesafe/v1/systemone"))
        .build();
```

Vercel documents this endpoint as TypeSafe-compatible, but its public API guide
does not specify image assets, the TypeSafe images header, or Box support. This
SDK does not claim image or Box support through that route. Its availability
also does not change which wire format the selected Java client sends.

## Verification and sources

Local protocol tests cover both Vercel wire formats. A live image request to
`/v4/ai/decision-model` on 2026-10-17 returned HTTP 403 without a successful inference result; the
error code was not established, so successful live image inference remains
unverified.

Sources checked 2026-10-17: [Vercel Jev 1.14 image support](https://vercel.com/changelog/typesafe-ais-jev-1-14-is-now-available-on-ai-gateway),
[TypeSafe-compatible API](https://vercel.com/docs/ai-gateway/sdks-and-apis/typesafe),
[current Gateway decision-model source at `fce9210`](https://github.com/vercel/ai/blob/fce9210c6405c0ad8452fee3f50717e8ea910b97/packages/gateway/src/gateway-decision-model.ts),
[Gateway decision-model tests](https://github.com/vercel/ai/blob/fce9210c6405c0ad8452fee3f50717e8ea910b97/packages/gateway/src/gateway-decision-model.test.ts),
[older evaluation-model V4 source at `b67b1b7`](https://github.com/vercel/ai/blob/b67b1b746341ca591bd1551fde809278ae7f9f44/packages/gateway/src/gateway-evaluation-model.ts).
