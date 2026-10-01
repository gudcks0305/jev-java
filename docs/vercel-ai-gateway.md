# Vercel AI Gateway public TypeSafe API

Vercel provides a TypeSafe-compatible HTTP endpoint usable with `jev-typesafe`.
The existing `jev-vercel` module uses the AI SDK evaluation-model v4 protocol;
its default behavior is unchanged. Use the configuration below to select the
public TypeSafe-compatible route explicitly.

## Configure Java and Spring Boot

Add `io.github.gudcks0305:jev-typesafe:0.2.0` for plain Java. Set
`AI_GATEWAY_API_KEY` to a Vercel Gateway key, then run:

```java
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.net.URI;
import java.util.Objects;

var refund = NoulQuestion.of("refund", "Is the customer asking for money back?");
try (var client = TypeSafeJevClient.builder()
        .apiKey(Objects.requireNonNull(System.getenv("AI_GATEWAY_API_KEY"),
                "Set AI_GATEWAY_API_KEY"))
        .model("typesafe-ai/jev")
        .endpoint(URI.create("https://ai-gateway.vercel.sh/typesafe/v1/systemone"))
        .build()) {
    var result = client.evaluate("Please refund the duplicate charge.", refund);
    System.out.println(result.answer(refund).probability());
}
```

For Spring Boot, add `io.github.gudcks0305:jev-spring-boot-starter:0.2.0`:

```yaml
jev:
  provider: typesafe
  api-key: ${AI_GATEWAY_API_KEY}
  model: typesafe-ai/jev
  endpoint: https://ai-gateway.vercel.sh/typesafe/v1/systemone
```

`provider: typesafe` chooses the wire format; this request is billed by
Vercel. Pass the Gateway key explicitly because the TypeSafe client defaults
to `TYPESAFE_API_KEY`. `endpoint` is a complete URL. Alternatively set only
`baseUrl` / `jev.base-url` to `https://ai-gateway.vercel.sh/typesafe`, which
appends `/v1/systemone`. Do not set both URL options.

## Route and metadata boundaries

| Route | Java configuration | Format |
| --- | --- | --- |
| `/typesafe/v1/systemone` | `TypeSafeJevClient`, as above | Model in body; native Noul, Choice, Score; snake_case usage |
| `/v4/ai/evaluation-model` | Existing `VercelJevClient` | Model in header; boolean mapping and AI SDK protocol headers |
| `/v1/evaluate` | No direct adapter in this SDK | Public evaluation API with model in body and boolean questions |

Do not point `VercelJevClient` at `/v1/evaluate` as a URL-only migration: its
body omits `model`. The TypeSafe-compatible route avoids that mismatch.
Extra `provider_metadata`, including Gateway routing/cost fields when present,
remains available in `Evaluation.rawResponse()`.

This SDK does not expose Vercel's `providerOptions` evaluation fallback
extension. In particular, a fallback response containing an empty
Choice/Score `probabilities` object fails this client's distribution checks;
it is not a supported substitute for native Jev answers. Do not carry Jev
thresholds to another model without evaluating them separately.

## Verification

Official documentation and local fixture coverage are recorded in
[validation](validation.md). Successful live Java inference through this
public route has not been verified. The previously recorded Vercel HTTP 403
account check was on the v4 route and does not validate this route.

Sources checked 2026-10-01:

- [TypeSafe-compatible API](https://vercel.com/docs/ai-gateway/sdks-and-apis/typesafe)
- [Public evaluation API](https://vercel.com/docs/ai-gateway/modalities/evaluation)
- [HTTP API announcement](https://vercel.com/changelog/ai-gateway-now-supports-typesafe-clients-and-http-api-for-jev)
