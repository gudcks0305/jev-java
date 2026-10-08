# OpenAI Decisions API

Checked against official documentation on 2026-10-07. Available starting with
`jev-openai:0.3.0`, or `jev-spring-boot-starter:0.3.0` for Spring applications.
Versions 0.2.0 and earlier do not include this adapter.

OpenAI Decisions is a public beta API at `POST https://api.openai.com/v1/decisions`.
The currently documented model is `gpt-6-luna`. This is an independent OpenAI
model, not Jev hosting; evaluate its judgments and thresholds separately.

Sources: [Decisions guide](https://developers.openai.com/api/docs/guides/decisions),
[HTTP contract](https://developers.openai.com/api/reference/resources/decisions/methods/create),
[official Java API](https://developers.openai.com/api/reference/java/resources/decisions/methods/create).
OpenAI's own Java SDK also exposes this endpoint. This adapter uses Jev Java's
existing HTTP transports and does not depend on that SDK.

## Existing Jev questions

The `jev-openai` module includes `jev-core`. Set `OPENAI_API_KEY`
in the environment, or supply `.apiKey(...)` from your application's secret
configuration. Real calls can be billed.

```java
import io.github.gudcks0305.jev.ChoiceQuestion;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.ScoreQuestion;
import io.github.gudcks0305.jev.openai.OpenAiJevClient;
import java.util.List;

public final class OpenAiDecisionsExample {
    enum Team { BILLING, TECHNICAL }

    public static void main(String[] args) {
        var route = ChoiceQuestion.of("route", "Which team should handle this?", Team.class);
        var refund = NoulQuestion.of("refund", "Does the customer request a refund?");
        var severity = ScoreQuestion.of("severity", "How severe is the problem?",
                List.of("Minor", "Workaround available", "Blocked"));

        try (var client = OpenAiJevClient.builder().build()) {
            var result = client.evaluate("I was charged twice. Please refund the duplicate payment.",
                    route, refund, severity);
            System.out.println(result.answer(route).choice());
            System.out.println(result.answer(refund).probability());
            System.out.println(result.answer(severity).score());
        } catch (JevException error) {
            if (error.kind() == JevException.Kind.REFUSAL) {
                System.err.println("Evaluation refused; no decision was taken.");
                return;
            }
            throw error;
        }
    }
}
```

The builder defaults to `gpt-6-luna`. `model(...)` remains configurable for
compatible endpoints or future models; setting a value does not establish
provider support. `endpoint(URI)` accepts a full URL, including its query;
`baseUrl(URI)` appends `/v1/decisions`. They are mutually exclusive. A custom
base URL should therefore omit that suffix.

## Native Decisions: images, boolean choices, and partial refusals

`decide(DecisionRequest)` and `decideAsync(DecisionRequest)` expose the native
contract through immutable Java 17 types. Use these methods for features that
do not fit the existing Jev question/record interface.

```java
import io.github.gudcks0305.jev.openai.*;
import java.util.List;

public final class NativeOpenAiExample {
    public static void main(String[] args) {
        var request = DecisionRequest.builder()
                .input("The package arrived with a cracked screen.")
                .safetyIdentifier("opaque-example-user")
                .questions(
                        new DecisionQuestion.Predicate(null, "Does the customer report damage?"),
                        new DecisionQuestion.Choice("claim", "Is this a damage claim?",
                                List.of(new DecisionQuestion.Option(DecisionValue.bool(true), "Damage"),
                                        new DecisionQuestion.Option(DecisionValue.bool(false), "Other"))))
                .build();
        try (var client = OpenAiJevClient.builder().build()) {
            var result = client.decide(request);
            for (var answer : result.answers()) {
                if (answer instanceof DecisionAnswer.Refusal) {
                    System.out.println("Question refused");
                } else if (answer instanceof DecisionAnswer.Predicate predicate) {
                    System.out.println(predicate.probability());
                } else if (answer instanceof DecisionAnswer.Choice choice) {
                    System.out.println(choice.choice());
                }
            }
        }
    }
}
```

Answers stay in request order. A native `DecisionAnswer.Refusal` does not discard
successful siblings. Names may be omitted or repeated: use list position to
associate answers in those cases. `DecisionValue.text("true")` and
`DecisionValue.bool(true)` remain different values. Native strings may be empty;
questions, choices and score levels must have at least one entry. Score levels
have a label and optional description; duplicate labels are distinguished by index.

For images, construct `DecisionInput.messages(...)` with `UserMessage` content
containing `TextPart` and `ImagePart`. For example, after creating a base64 PNG
data URL in the application:

```java
DecisionInput input = DecisionInput.messages(new DecisionInput.UserMessage(List.of(
        new DecisionInput.TextPart("Inspect this product."),
        new DecisionInput.ImagePart(imageDataUrl, DecisionInput.Detail.HIGH))));
```

Only inline base64 image data URLs are accepted; the SDK does not fetch URLs or
read files. Omitted detail uses the provider default; `LOW`, `HIGH`, `AUTO`, and
`ORIGINAL` are available. At most 128 images are allowed across all messages.
User messages may also contain a plain string. Files, audio, tools and non-user
roles are not part of the Decisions endpoint.

`DecisionRequest.model` overrides the client model for that request; null uses
the configured default. `safetyIdentifier` is optional and request-scoped, with
a maximum of 128 Unicode code points. Supply an opaque application identifier,
not the user's email or other direct personal data. Requests, inputs and image
parts redact content in their `toString()` output.

`DecisionUsage` preserves input, output, total, cached, cache-write, and reasoning
token counts. Unknown response fields remain available through the defensively
copied `DecisionResult.rawResponse()`. Required response fields and all successful
answers are validated even when a sibling is refused. No probability normalization,
score recomputation or confidence inference occurs.

## Spring Boot and WebFlux

Use the starter:

```yaml
jev:
  provider: openai
  model: gpt-6-luna
  timeout: 30s
  max-retries: 2
```

The client reads `OPENAI_API_KEY` unless `jev.api-key` is explicitly set.
`jev.endpoint` and `jev.base-url` have the same semantics as the Java builder.
Add `jev-spring-webflux` and select `jev.transport: webclient` for the existing
WebClient transport and lazy `ReactorJevClient`. Cancellation propagates through
the existing asynchronous pipeline. Caller-supplied transports remain caller-owned
and control their own retry/deadline policy.

With `jev.provider=openai`, inject `OpenAiJevClient` for native methods, or
`ReactorOpenAiClient` when WebFlux is present. Its `decide(request)` returns a
lazy `Mono<DecisionResult>` and propagates cancellation. Both Reactor facades
use the same client and transport. A custom `JevClient` bean still disables
automatic provider construction; a custom non-OpenAI client does not create
the native Reactor facade.

## Supported subset and error behavior

The inherited `evaluate` / `evaluateAsync` methods retain this compatibility
subset. These restrictions do not apply to the separate native `decide` API.

| Java surface | OpenAI mapping / limit |
| --- | --- |
| `String` or textual `JsonNode` state | Nonblank `input` text; objects and arrays rejected |
| Question instructions | Nonblank strings only; structured instructions rejected |
| Basic `NoulQuestion` | `predicate` probability; `withCriteria(...)` rejected, including all-null criteria |
| String/enum `ChoiceQuestion` | String-valued `choices`; string descriptions or null (omitted) |
| `ScoreQuestion` | Nonblank string levels become labels in their original order; structured levels rejected |
| Record mapping | `@JevBoolean`, `@JevProbability`, `@JevChoice`, `@JevScore` work with text input |
| `@JevLabels` | Unsupported: its generated instructions are structured; rejected before HTTP |

Input validation fails with `IllegalArgumentException` before transport.
The adapter never silently serializes structured criteria into prose or drops
Noul criteria. Use the native API for image/message inputs, boolean choice
values, request-level `safety_identifier`, and partial result access.

A native `refusal` fails the **whole evaluation**, including mixed batches,
with `JevException.Kind.REFUSAL`. No partial `Evaluation` or record is returned;
refusal is never converted to false, zero probability, or a retryable HTTP error.
Generic async and Reactor callers receive the same failure. Native calls instead
return per-question refusal variants. Consumers using exhaustive
switches over `JevException.Kind` must handle the new enum constant.

The decoder validates answer order/names, declared types, complete distributions,
score indices/labels and numeric bounds. Malformed payloads fail with `PROTOCOL`.
Scores remain weighted zero-based indices; probabilities are not renormalized.
Choice/Score confidence is preserved separately from probabilities. Typed usage
exposes input/output tokens, including zero; additional usage fields and unknown
metadata remain in `rawResponse()`.

Transport retry policy is unchanged: only 429, 529, 502, 503, and 504 retry.
The total deadline includes retry delays. No extra inference calls or automatic
provider fallback are introduced.

## Run and verify

Offline checks require no provider key:

```sh
./mvnw -pl jev-openai -am test
./mvnw verify
```

After a source install, these examples support the opt-in `openai` argument:

```sh
./mvnw -q -pl examples exec:java \
  -Dexec.mainClass=io.github.gudcks0305.jev.examples.Quickstart \
  -Dexec.args=openai
```

`RecordExample` and `WebClientExample` accept the same argument.
`NativeDecisionExample` exercises native questions without a provider argument.
Compiling examples does not invoke the provider. See [validation evidence](validation.md) and
[fixture provenance](../jev-openai/src/test/resources/openai-decisions/README.md).
