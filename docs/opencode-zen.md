# OpenCode Zen Jev

OpenCode Zen documents Jev on a System One endpoint. Reuse `jev-typesafe`
or `jev-spring-boot-starter` with `jev.provider=typesafe`; no separate
OpenCode module or provider enum value is needed for this wire format.

## Configure Java and Spring Boot

Create an OpenCode Zen API key and set `OPENCODE_API_KEY`. Pass that key
explicitly because the TypeSafe client otherwise reads `TYPESAFE_API_KEY`,
which may belong to another service.

```java
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.net.URI;
import java.util.Objects;

var key = Objects.requireNonNull(System.getenv("OPENCODE_API_KEY"), "Set OPENCODE_API_KEY");
var urgent = NoulQuestion.of("urgent", "Does this need urgent attention?");
try (var client = TypeSafeJevClient.builder()
        .apiKey(key)
        .model("jev-1.13")
        .endpoint(URI.create("https://opencode.ai/zen/v1/systemone"))
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
  api-key: ${OPENCODE_API_KEY}
  model: jev-1.13
  endpoint: https://opencode.ai/zen/v1/systemone
```

`endpoint` is the full URL; do not also set `baseUrl` / `jev.base-url`.
It preserves the configured path instead of appending a provider suffix.

## Contract and verification limits

The Jev request uses `model`, `state`, and named `questions` with Noul,
Choice, or Score types. Use the HTTP model ID `jev-1.13`; the OpenCode
application's `opencode/<model-id>` syntax is not the model value in this
request. Zen also documents `jev-1.13-free` on the same endpoint as a
limited-time offer. Its duration and availability are not guaranteed; this
guide uses the regular model. Check current pricing and workspace access
before running calls.

This guide records documented compatibility, not a successful live Java
inference. No credentials or billable calls were used. Official request
examples describe the contract; offline synthetic fixtures, when used,
establish codec behavior only. Applications own thresholds and must validate
judgments against their own data. See [validation evidence](validation.md).

Source checked 2026-10-01: [OpenCode Zen Jev](https://opencode.ai/docs/en/zen/#jev).
