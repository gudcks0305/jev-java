# Liquid AI d1

Liquid AI documents d1 through a TypeSafe-compatible Decisions endpoint.
Use `jev-typesafe`, or the starter with `jev.provider=typesafe`; there is no
separate Liquid client or provider value.

## Configure Java and Spring Boot

Add `io.github.gudcks0305:jev-typesafe:0.2.0`, set `LIQUID_API_KEY`, and pass
the key explicitly. `TypeSafeJevClient` otherwise reads `TYPESAFE_API_KEY`.

```java
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.net.URI;
import java.util.Objects;

var complaint = NoulQuestion.of("complaint", "Is this message a complaint?");
try (var client = TypeSafeJevClient.builder()
        .apiKey(Objects.requireNonNull(System.getenv("LIQUID_API_KEY"), "Set LIQUID_API_KEY"))
        .model("d1:free")
        .endpoint(URI.create("https://api.liquid.ai/decisions/v1/systemone"))
        .build()) {
    var result = client.evaluate("My order is late and support has not replied.", complaint);
    System.out.println(result.answer(complaint).probability());
}
```

For `io.github.gudcks0305:jev-spring-boot-starter:0.2.0`:

```yaml
jev:
  provider: typesafe
  api-key: ${LIQUID_API_KEY}
  model: d1:free
  endpoint: https://api.liquid.ai/decisions/v1/systemone
```

Use the complete URL from Liquid's cURL examples. Its Python/TypeScript examples
show a base URL at the origin, but this Java client's `baseUrl` appends
`/v1/systemone`; using that origin would omit `/decisions`. Do not configure
`baseUrl` / `jev.base-url` together with `endpoint`.

## Contract and verification limits

The documentation specifies Noul as a yes probability, Choice as a selected
label with its distribution, and Score as a continuous position across ordered
zero-based levels. Choice/Score confidence expresses ambiguity; it is not an
accuracy guarantee or necessarily the selected probability. Use string Choice
descriptions and ordered string Score levels as shown in the provider examples.

The client preserves every returned numeric value. Published examples contain
rounded distributions whose sum is not exactly one; the SDK does not normalize
them or recompute a Score. Missing confidence remains absent. Missing model
uses the requested model; missing usage stays absent. Complete Choice/Score
distributions remain required by this codec.

The model ID `d1:free` is the documented identifier, not an SDK promise about
future pricing, quotas or availability. Check the provider before deployment.

Local HTTP tests wrap the official combined three-primitive `answers` example
in a synthetic response envelope. A separate published Noul response covers
snake_case usage, including zero output tokens. These are documentation
fixtures, not recorded live responses. No credentialed inference was made;
see [validation evidence](validation.md).

Source checked 2026-10-01: [Liquid AI Decision Models](https://docs.liquid.ai/lfm/models/decision-models).
