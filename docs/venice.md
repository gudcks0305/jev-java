# Venice Jev

Venice offers Jev through its beta Decisions API. Use the existing
`jev-typesafe` module or `jev-spring-boot-starter` with
`jev.provider=typesafe`; there is no separate Venice client or provider value.
The documented request uses the TypeSafe-shaped `model`, `state`, and named
`questions`, with native Noul, Choice, and Score answers.

## Configure Java and Spring Boot

Create a Venice API key and expose it as `VENICE_API_KEY`. Pass that key
explicitly: `TypeSafeJevClient` otherwise reads `TYPESAFE_API_KEY`, which may
belong to a different service. Use Venice's complete Decisions URL:

```java
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.net.URI;

var urgent = NoulQuestion.of("urgent", "Does this need urgent attention?");
try (var client = TypeSafeJevClient.builder()
        .apiKey(System.getenv("VENICE_API_KEY"))
        .model("jev-latest")
        .endpoint(URI.create("https://api.venice.ai/api/v1/decisions"))
        .build()) {
    var result = client.evaluate("My payouts have failed for three days.", urgent);
    System.out.println(result.answer(urgent).probability());
}
```

Add `io.github.gudcks0305:jev-typesafe:0.2.0` for plain Java. For Spring Boot,
add `io.github.gudcks0305:jev-spring-boot-starter:0.2.0` and configure:

```yaml
jev:
  provider: typesafe
  api-key: ${VENICE_API_KEY}
  model: jev-latest
  endpoint: https://api.venice.ai/api/v1/decisions
```

`endpoint` is the complete URL. Do not also set `baseUrl` / `jev.base-url`:
the client appends `/v1/systemone` to a base URL, whereas the documented
Venice Decisions path is `/api/v1/decisions`. Venice also documents a
TypeSafe-compatible `/systemone` handler, but this guide uses the explicit
Decisions URL and does not assume its base URL path layout.

## Contract and verification limits

Venice says Jev and the Decisions API are beta, and their schemas may change.
Its page illustrates Noul, Choice, and Score responses; the displayed numbers
are illustrative, not evidence of a live call or model accuracy. Applications
must choose thresholds using their own labeled data. Venice says availability
can vary by account; check its Models API for your account before use.

On 2026-09-28, an unauthenticated `GET
https://api.venice.ai/api/v1/models?type=decision` returned HTTP 200 and
listed `jev-latest`. An unauthenticated POST to the Decisions URL returned
HTTP 402 `Authentication required`. These checks establish a model listing
and an authentication boundary only. No Venice key was available and no
successful Java inference was verified. See [validation evidence](validation.md).

Sources checked 2026-09-28: [Venice Jev and Decisions API](https://venice.ai/lp/jev).
