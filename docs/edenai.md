# Eden AI Jev

Eden AI documents Jev through its alpha Decisions endpoint. Reuse
`jev-typesafe` or `jev-spring-boot-starter` with `jev.provider=typesafe`;
no separate Eden AI module or provider enum value is needed for this wire format.

## Configure Java and Spring Boot

Create an Eden AI API key and set `EDENAI_API_KEY`. Pass that key explicitly
because the TypeSafe client otherwise reads `TYPESAFE_API_KEY`, which may
belong to another service.

```java
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.net.URI;
import java.util.Objects;

var key = Objects.requireNonNull(System.getenv("EDENAI_API_KEY"), "Set EDENAI_API_KEY");
var urgent = NoulQuestion.of("urgent", "Does this need urgent attention?");
try (var client = TypeSafeJevClient.builder()
        .apiKey(key)
        .model("typesafe/jev-latest")
        .endpoint(URI.create("https://api.edenai.run/v3/alpha/decisions"))
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
  api-key: ${EDENAI_API_KEY}
  model: typesafe/jev-latest
  endpoint: https://api.edenai.run/v3/alpha/decisions
```

`endpoint` is the complete Decisions URL. Do not also set `baseUrl` /
`jev.base-url`: the TypeSafe client would append `/v1/systemone`, which
does not match Eden AI's `/v3/alpha/decisions` path.

## Contract and verification limits

Eden AI documents TypeSafe-shaped request and response bodies: `model`,
`state`, named `questions`, and named `answers`, with native Noul, Choice,
and Score semantics. This is not its Chat Completions endpoint. The alpha
contract, model IDs, and pricing should be checked before deployment.
`typesafe/jev-latest` is a moving alias; discover current models through
`/v3/alpha/decisions/models` and pin a supported concrete version when
validating application thresholds. Additional fields such as `cost` stay
available through `rawResponse()`; missing confidence stays missing.

This guide records documented compatibility, not a successful live Java
inference. No credentials or billable calls were used. Official response
examples are illustrations; offline synthetic fixtures, when used, establish
codec behavior only. Applications own thresholds and must validate judgments
against their own data. See [validation evidence](validation.md).

Source checked 2026-10-01: [Eden AI Jev and Decisions endpoint](https://www.edenai.co/post/jev-a-new-kind-of-ai-model-built-for-decisions-not-conversation).
