# Upstage Solar Decide

Solar Decide uses the System One request shape supported by `jev-typesafe`.
Use the existing client or `jev.provider=typesafe`; no additional provider
module is required.

## Configure Java and Spring Boot

Add `io.github.gudcks0305:jev-typesafe:0.2.0`. Set `UPSTAGE_API_KEY` and pass
it explicitly; the client's default environment variable is `TYPESAFE_API_KEY`.

```java
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.net.URI;
import java.util.Objects;

var angry = NoulQuestion.of("angry", "Is the customer angry?");
try (var client = TypeSafeJevClient.builder()
        .apiKey(Objects.requireNonNull(System.getenv("UPSTAGE_API_KEY"), "Set UPSTAGE_API_KEY"))
        .model("solar-decide")
        .endpoint(URI.create("https://api.upstage.ai/v1/systemone"))
        .build()) {
    var result = client.evaluate("My package is missing and nobody has replied.", angry);
    System.out.println(result.answer(angry).probability());
}
```

For `io.github.gudcks0305:jev-spring-boot-starter:0.2.0`:

```yaml
jev:
  provider: typesafe
  api-key: ${UPSTAGE_API_KEY}
  model: solar-decide
  endpoint: https://api.upstage.ai/v1/systemone
```

`endpoint` supplies the full URL. Do not also set `baseUrl` / `jev.base-url`.

## Contract and verification limits

The API is beta. Choice accepts 2–26 options; the SDK does not enforce this
provider-specific maximum. A documented overflow returns HTTP 422, mapped to
`JevException.Kind.VALIDATION`. Reduce candidates before submitting.

The official example has named Choice, Noul and Score answers, complete
distributions, Choice/Score confidence and snake_case usage. Values are preserved;
confidence is not inferred from the winning probability. Use string Choice
descriptions and ordered string Score levels, matching the documented request.
The TypeSafe codec requires complete Choice/Score distributions.

Local HTTP tests replay the published response and overflow error. This proves
offline serialization/parsing, not account access, model accuracy or live
inference. No credentialed call was made. See [validation evidence](validation.md).

Source checked 2026-10-01: [Upstage System One API](https://console.upstage.ai/api/systemone).
