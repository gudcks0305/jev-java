# AI/ML API Jev

AI/ML API documents Jev at its Decisions endpoint. Use the existing
`jev-typesafe` module or `jev-spring-boot-starter` with
`jev.provider=typesafe`; there is no separate AI/ML API client or provider
value. Its documented request has the TypeSafe-shaped `model`, `state`, and
named `questions` fields, and its response has native Noul, Choice, and Score
answers.

## Configure Java and Spring Boot

Create an enabled AI/ML API key and expose it as `AIMLAPI_KEY`. Pass the key
explicitly: `TypeSafeJevClient` otherwise reads `TYPESAFE_API_KEY`. Use the
complete Decisions URL and AI/ML API's model ID:

```java
import io.github.gudcks0305.jev.ChoiceQuestion;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.net.URI;
import java.util.Map;

var urgent = NoulQuestion.of("urgent", "Does this need urgent attention?");
var department = ChoiceQuestion.of("department", "Which team should handle this?",
        Map.of("billing", "Payments, invoicing, refunds",
               "technical", "Bugs, outages, integrations"));
try (var client = TypeSafeJevClient.builder()
        .apiKey(System.getenv("AIMLAPI_KEY"))
        .model("typesafe/jev")
        .endpoint(URI.create("https://api.aimlapi.com/v1/decisions"))
        .build()) {
    var result = client.evaluate("My payment failed again.", urgent, department);
    System.out.println(result.answer(urgent).probability());
    System.out.println(result.answer(department).choice());
}
```

Add `io.github.gudcks0305:jev-typesafe:0.2.0` for plain Java. For Spring Boot,
add `io.github.gudcks0305:jev-spring-boot-starter:0.2.0` and configure:

```yaml
jev:
  provider: typesafe
  api-key: ${AIMLAPI_KEY}
  model: typesafe/jev
  endpoint: https://api.aimlapi.com/v1/decisions
```

`endpoint` is the complete URL. Do not also set `baseUrl` / `jev.base-url`:
the client appends `/v1/systemone` to a base URL, not `/v1/decisions`.

## Contract and verification limits

The [AI/ML API Jev schema](https://docs.aimlapi.com/api-references/decision-models/typesafe/jev)
allows string, object, or array `state` and `instructions`. Its Choice
criteria map option keys to **string descriptions**; Noul criteria, when
provided, use string `true` and `false` descriptions; Score criteria are an
array of at least two string levels. The SDK supports broader criteria values
for other providers, but this published AI/ML API schema does not establish
support for null, object, or array criteria. In particular,
`ChoiceQuestion.of(id, instructions, Enum.class)` produces null descriptions;
use an explicit descriptive map as above, or `withDescriptions` for an enum.

The official response example includes a Noul probability, Choice label,
distribution and confidence, a fractional Score with legend and distribution,
snake_case token usage, and extra `meta` usage fields. Offline parsing of that
published example with this SDK produced Noul `0.96`, Choice `billing`, Score
`1.3`, input tokens `403`, and `meta.usage.credits_used=47` in the raw
response. This checks example parsing only, not a live provider response or
judgment accuracy. Missing provider metadata remains missing in Java.

On 2026-09-28, an unauthenticated POST to the Decisions URL returned HTTP
401. No AI/ML API key was available and no successful Java inference was
verified. See [validation evidence](validation.md).

Source checked 2026-09-28: [AI/ML API Jev reference](https://docs.aimlapi.com/api-references/decision-models/typesafe/jev).
