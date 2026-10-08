# DigitalOcean Jev

DigitalOcean documents Jev through its System One API. Reuse `jev-typesafe`
or `jev-spring-boot-starter` with `jev.provider=typesafe`; no separate
DigitalOcean module or provider enum value is needed for this wire format.

## Configure Java and Spring Boot

Create a DigitalOcean **model access key** for Serverless Inference and set
`MODEL_ACCESS_KEY`. This is the DigitalOcean credential, not a TypeSafe key.
Pass it explicitly because the TypeSafe client otherwise reads
`TYPESAFE_API_KEY`.

```java
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.net.URI;
import java.util.Objects;

var key = Objects.requireNonNull(System.getenv("MODEL_ACCESS_KEY"), "Set MODEL_ACCESS_KEY");
var urgent = NoulQuestion.of("urgent", "Does this need urgent attention?");
try (var client = TypeSafeJevClient.builder()
        .apiKey(key)
        .model("typesafe-jev-1.13.0")
        .endpoint(URI.create("https://inference.do-ai.run/v1/systemone"))
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
  api-key: ${MODEL_ACCESS_KEY}
  model: typesafe-jev-1.13.0
  endpoint: https://inference.do-ai.run/v1/systemone
```

`endpoint` is the full URL; do not also set `baseUrl` / `jev.base-url`.
It preserves the configured path instead of appending a provider suffix.

## Contract and verification limits

The documented body contains `model`, `state`, and named `questions`; answers
use native Noul, Choice, and Score fields. This endpoint does not support
Chat Completions, streaming, or image/audio/video input. Third-party model
access requires a qualifying subscription tier; an available key alone does
not establish eligibility. DigitalOcean documents separate 64K total-request
and 32K state-plus-longest-question token limits.

This guide records documented compatibility, not a successful live Java
inference. No credentials or billable calls were used. Official response
examples are illustrations; offline synthetic fixtures, when used, establish
codec behavior only. Applications own thresholds and must validate judgments
against their own data. See [validation evidence](validation.md).

Source checked 2026-10-01: [DigitalOcean System One API](https://docs.digitalocean.com/products/inference/how-to/use-system-one-api/).
