# LLM Gateway Jev

LLM Gateway documents Jev through its System One API. Reuse `jev-typesafe`
or `jev-spring-boot-starter` with `jev.provider=typesafe`; no separate
LLM Gateway module or provider enum value is needed for this wire format.

## Configure Java and Spring Boot

Create an LLM Gateway API key and set `LLM_GATEWAY_API_KEY`. Pass that key
explicitly because the TypeSafe client otherwise reads `TYPESAFE_API_KEY`,
which may belong to another service.

```java
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.net.URI;
import java.util.Objects;

var key = Objects.requireNonNull(System.getenv("LLM_GATEWAY_API_KEY"), "Set LLM_GATEWAY_API_KEY");
var urgent = NoulQuestion.of("urgent", "Does this need urgent attention?");
try (var client = TypeSafeJevClient.builder()
        .apiKey(key)
        .model("typesafe/jev-1.13.0")
        .endpoint(URI.create("https://api.llmgateway.io/v1/systemone"))
        .build()) {
    var result = client.evaluate("My payouts have failed for three days.", urgent);
    System.out.println(result.answer(urgent).probability());
}
```

Add `io.github.gudcks0305:jev-typesafe:0.2.0` for plain Java, or
`io.github.gudcks0305:jev-spring-boot-starter:0.2.0` for Spring Boot:

```yaml
jev:
  provider: typesafe
  api-key: ${LLM_GATEWAY_API_KEY}
  model: typesafe/jev-1.13.0
  endpoint: https://api.llmgateway.io/v1/systemone
```

`endpoint` is the full URL; do not also set `baseUrl` / `jev.base-url`.
It preserves the configured path instead of appending a provider suffix.

## Contract and verification limits

The documented body contains `model`, `state`, and named `questions` with
Noul, Choice, and Score answers. The changelog identifies the model as
`typesafe/jev-1.13.0`, although its cURL example uses `jev-1.13.0`.
This guide uses the provider-qualified ID. Decision models use
`/v1/systemone`, not Chat Completions, and cannot generate text or call
tools. IAM policies, credits, and organization rate limits still apply.
Do not infer account access from the public announcement.

This guide records documented compatibility, not a successful live Java
inference. No credentials or billable calls were used. Official request
examples describe the contract; offline synthetic fixtures, when used,
establish codec behavior only. Applications own thresholds and must validate
judgments against their own data. See [validation evidence](validation.md).

Source checked 2026-10-01: [LLM Gateway System One changelog](https://llmgateway.io/changelog/system-one-typed-decisions).
