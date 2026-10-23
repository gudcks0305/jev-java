# OpenAI Decisions API

OpenAI Decisions is generally available at `POST https://api.openai.com/v1/decisions`.
The currently supported model is `gpt-6-luna`; this is an OpenAI model, not Jev
hosting. Treat its judgments and thresholds as application inputs and evaluate
them against your own examples.

The `jev-openai` adapter first shipped in 0.3.0 with the public-beta subset.
Version 0.6.0 adds the GA contract described below. Use `jev-openai:0.6.0`
or `jev-spring-boot-starter:0.6.0`; 0.5.0 artifacts do not include these additions.

Sources checked 2026-10-23: [Decisions guide](https://developers.openai.com/api/docs/guides/decisions),
[create reference](https://developers.openai.com/api/reference/resources/decisions/methods/create),
[official Java API](https://developers.openai.com/api/reference/java/resources/decisions/methods/create).

## Existing Jev questions

The `jev-openai` module includes `jev-core`. Set `OPENAI_API_KEY` in the
environment or supply `.apiKey(...)`. Existing `evaluate` and record APIs map
basic Noul to predicate, string/enum Choice to string values, and Score to
string-level labels. OpenAI refusal fails a generic evaluation with
`JevException.Kind.REFUSAL`.

```java
import io.github.gudcks0305.jev.ChoiceQuestion;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.ScoreQuestion;
import io.github.gudcks0305.jev.openai.OpenAiJevClient;
import java.util.List;

var route = ChoiceQuestion.of("route", "Which team should handle this?",
        java.util.Map.of("billing", "Payments", "technical", "Technical support"));
var refund = NoulQuestion.of("refund", "Does the customer request a refund?");
var severity = ScoreQuestion.of("severity", "How severe is the problem?",
        List.of("Minor", "Workaround available", "Blocked"));
try (var client = OpenAiJevClient.builder().build()) {
    var result = client.evaluate("I was charged twice. Please refund the duplicate payment.",
            route, refund, severity);
    System.out.println(result.answer(route).choice());
    System.out.println(result.answer(refund).probability());
    System.out.println(result.answer(severity).score());
}
```

JSON state and structured instructions now work through `input_json`. This also
enables record schemas such as `@JevLabels` whose generated instructions are
structured. The generic adapter still has limited criteria support: Choice
descriptions must be strings or null, Score levels must be strings, and Noul
criteria are rejected. `BoxQuestion` and `JevInput` image wrappers are not
supported by the OpenAI Jev adapter. Use the native `decide` API for full GA
input control.

## Native GA input

`decide(DecisionRequest)` and `decideAsync(DecisionRequest)` expose OpenAI's
native API through immutable Java types. The request accepts a text string or
ordered `DecisionInput` history, 1–400 questions, and at most 128 images across
shared input, tool outputs, and question instructions. Images may be base64
data URLs or publicly accessible HTTP(S) URLs. The SDK validates URL syntax but
never downloads the image; OpenAI fetches hosted URLs. File IDs, audio, and
video are unsupported.

The example uses structured JSON, conversation history, a function call and
output, a custom tool call and output, an image in a question, and explicit
prompt-cache controls. Replace the example image path with a PNG if you want to
send an image. The example compiles without credentials, but calling it requires
`OPENAI_API_KEY` and can be billed.

```java
import io.github.gudcks0305.jev.openai.DecisionCacheOptions;
import io.github.gudcks0305.jev.openai.DecisionInput;
import io.github.gudcks0305.jev.openai.DecisionQuestion;
import io.github.gudcks0305.jev.openai.DecisionRequest;
import io.github.gudcks0305.jev.openai.OpenAiJevClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public final class DecisionsGaExample {
    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: DecisionsGaExample [png-path]");
        var input = DecisionInput.history(
                new DecisionInput.DeveloperMessage("Evaluate the supplied evidence only."),
                new DecisionInput.UserMessage(List.of(
                        DecisionInput.JsonPart.of(Map.of("expected_color", "red", "expected_shape", "rectangle")),
                        new DecisionInput.TextPart("Use this reference for each candidate.").withCacheBreakpoint())),
                new DecisionInput.FunctionCall("lookup", "lookup_reference", "{}"),
                new DecisionInput.FunctionCallOutput("lookup", "The reference describes a red rectangle."),
                new DecisionInput.CustomToolCall("policy", "lookup_policy", "{\"category\":\"refund\"}"),
                new DecisionInput.CustomToolCallOutput("policy", "Duplicate charges are refundable."),
                new DecisionInput.AssistantMessage(
                        List.of(new DecisionInput.OutputTextPart("The lookup found duplicate charges.")),
                        DecisionInput.Phase.COMMENTARY));

        // Tool items are supplied history; the SDK does not execute these calls.
        DecisionQuestion question = new DecisionQuestion.Predicate(
                "match", "Does the tool evidence agree with the reference JSON?");
        if (args.length == 1) {
            var image = Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(args[0])));
            question = DecisionQuestion.Predicate.multipart("match", List.of(
                    new DecisionInput.TextPart("Does this image match the expected color and shape?"),
                    new DecisionInput.ImagePart("data:image/png;base64," + image)));
        }

        var request = DecisionRequest.builder()
                .input(input)
                .questions(question)
                .promptCacheKey("jev-java-ga-example")
                .promptCacheOptions(new DecisionCacheOptions(
                        DecisionCacheOptions.Mode.EXPLICIT,
                        DecisionCacheOptions.Ttl.THIRTY_MINUTES))
                .build();
        try (var client = OpenAiJevClient.builder().maxRetries(0).build()) {
            var result = client.decide(request);
            System.out.println("model=" + result.model());
            System.out.println("answer=" + result.answer(0));
            System.out.println("usage=" + result.usage());
        }
    }
}
```

### Input shapes

`DecisionInput.history(...)` preserves ordered conversation and tool items:

| Java item | API item | Required fields and content |
| --- | --- | --- |
| `UserMessage` | `role: "user"` | Text, `input_image`, and `input_json` parts; or a plain text string |
| `DeveloperMessage` / `SystemMessage` | `role: "developer"` / `"system"` | Text only; system is treated as developer |
| `AssistantMessage` | `role: "assistant"` | Output text or refusal parts; optional `Phase.COMMENTARY` or `FINAL_ANSWER` |
| `FunctionCall` | `function_call` | `call_id`, `name`, `arguments` JSON string; optional `id`, `namespace`, `status` |
| `FunctionCallOutput` | `function_call_output` | Matching `call_id`, text or ordered text/image `output`; optional `id`, `status` |
| `CustomToolCall` | `custom_tool_call` | `call_id`, `name`, string `input`; optional `id`, `namespace`, `status` |
| `CustomToolCallOutput` | `custom_tool_call_output` | Matching `call_id`, text or ordered text/image `output`; optional `id`, `status` |

Tool statuses are `IN_PROGRESS`, `COMPLETED`, or `INCOMPLETE`; null omits the
field. Function and custom-tool outputs must match a call of the same kind.
The SDK validates required IDs and names and rejects unmatched outputs before
HTTP. Java-side validation also bounds call IDs, tool names, and input strings.
It describes historical calls; the SDK does not execute them.

`DecisionInput.JsonPart.of(Object)` and `DecisionInput.json(Object)` create an
`input_json` part with `{ "type": "input_json", "data": ... }`. JSON data must
be an object or an array of objects; nested values may be any JSON value. Limits
are 32 container levels, 10,000 values, and 256 KiB of compact UTF-8 JSON.
`input_json` in shared message content is supported only in a user message. JSON
keys such as `role` and `image_url` are data and do not create messages or fetch
images.

`ImagePart` accepts an inline base64 image data URL or a public HTTP(S) URL.
It never fetches hosted URLs. Optional image detail is `LOW`, `HIGH`, `AUTO`, or
`ORIGINAL`. `DecisionRequest` counts images across input history and all
question instructions and rejects more than 128. Question factories support
multipart instructions:

```java
var question = DecisionQuestion.Predicate.multipart("same_product", List.of(
        new DecisionInput.TextPart("Does this match the reference?"),
        new DecisionInput.ImagePart("https://example.com/candidate.png")));
```

The same `multipart(name, parts, ...)` factory pattern exists for Choice and
Score. Their parts can be input text, images, or JSON; they cannot carry prompt
cache breakpoints. Text-only question instructions remain strings and the
`instructions()` accessor remains present (null for multipart instructions);
`instructionParts()` is empty for text-only instructions.

Question names are optional and may repeat. `DecisionResult.answer(index)` uses
request order, including when names are omitted or repeated. Choice values may
be strings or booleans; the same text and boolean value are distinct. OpenAI
supports predicate, choice, and score questions, not Jev Box questions. Choice
requires 2–255 unique choices; Score accepts 2–10 ordered levels.

### Prompt caching

Decisions automatically places an implicit cache breakpoint at the end of
shared input. A reusable prefix must contain at least 1,024 tokens and remain
identical across requests. Question content, including question-specific
images, is outside the reusable prefix.

`DecisionRequest.Builder.promptCacheKey(String)` partitions cache reuse; the
current API and SDK limit it to 64 Unicode code points. Cache options are
`DecisionCacheOptions.Mode.IMPLICIT` or `EXPLICIT`. The default is implicit.
In explicit mode, cache only boundaries marked on a shared `TextPart` or
`ImagePart` with `.withCacheBreakpoint()`. Breakpoints go in shared `input`, not
question instructions. Each marker serializes as
`prompt_cache_breakpoint: {"mode":"explicit"}`. The backend writes at most four
breakpoints per request and considers up to the latest 80 breakpoints in a
conversation; implicit mode writes its implicit boundary and up to three
explicit boundaries, while explicit mode writes up to four explicit ones. No
marker in explicit mode means no cache write.

`DecisionCacheOptions.Ttl.THIRTY_MINUTES` serializes as `ttl: "30m"`; this is
the only TTL currently documented for Decisions. It is a minimum cache
lifetime, not a deletion deadline. Do not use Responses' `prompt_cache_retention`
or its 12-hour and in-memory options for Decisions. The SDK leaves cache fields
omitted when unset. `DecisionUsage.cachedTokens()` and `cacheWriteTokens()` are
already included in `inputTokens()`; don't add them again.

## Jev compatibility and refusal behavior

For generic `evaluate`/`evaluateAsync`, non-text JSON state becomes a user
`input_json` part. Structured question instructions also become
`input_json`, which supports `@JevLabels` record schemas. Choice descriptions
must remain strings or null, Score levels strings, and Noul criteria are
rejected. Box and `JevInput` image wrappers are rejected for OpenAI; use the
native `DecisionInput` API for images.

Generic Jev evaluation treats any refusal as a whole-evaluation failure with
`JevException.Kind.REFUSAL`; it does not return partial answers. Native
`decide` returns `DecisionAnswer.Refusal` at the refused position while keeping
successful siblings. Probabilities, scores, confidence, and usage are validated
and preserved without normalization or inference. `DecisionResult.rawResponse()`
retains unknown response metadata.

## Spring Boot and WebFlux

Use the Spring starter:

```yaml
jev:
  provider: openai
  model: gpt-6-luna
  timeout: 30s
  max-retries: 2
```

The client reads `OPENAI_API_KEY` unless `jev.api-key` is explicitly set.
`jev.endpoint` and `jev.base-url` work as in the Java builder. Add
`jev-spring-webflux` and select `jev.transport: webclient` for the WebClient
transport and lazy `ReactorJevClient` / `ReactorOpenAiClient`. `decide(request)`
returns a lazy `Mono<DecisionResult>`; cancellation propagates. Caller-supplied
transports remain caller-owned and control their own retry and deadline policy.

## Organization usage reporting

OpenAI exposes organization-level reporting separately from inference:

```text
GET https://api.openai.com/v1/organization/usage/decisions
```

It requires an admin API key with `api.usage.read`, not the normal
`OPENAI_API_KEY`. `start_time` is required Unix seconds and inclusive;
`end_time` is exclusive. `bucket_width` is `1m`, `1h`, or `1d` (default `1d`).
The default/max bucket counts are 60/1,440 for 1m, 24/168 for 1h, and 7/31 for
1d. Use `page` with `next_page` to paginate.

Filters: `project_ids`, `user_ids`, `api_key_ids`, `models`, and `batch`
(`true` batch only, `false` non-batch only, omitted both). `group_by` supports
`project_id`, `user_id`, `api_key_id`, `model`, `batch`, `service_tier`, and
`api_source`. With `api_source`, `agents_api` means attributed Agents API
activity and `unlabeled` means no published source attribution; unlabeled does
not imply direct API use. `num_model_requests` counts requests, not question
items. This endpoint is intentionally outside `OpenAiJevClient`; use OpenAI's
admin API surface for reporting.

## Platform-managed GA features

GPT-Live can delegate Decisions natively or trigger them on transcript events;
that is configured on Live sessions and requires `api.decisions.write`. This
does not change the standalone Decisions request format. Private Link uses
Azure Private Endpoint and DNS provisioning, not a Decisions body field. If
your organization has been provisioned, the existing full `.endpoint(URI)` can
target its host. Private Link is currently not self-service. ZDR, HIPAA, and
regional processing depend on organization eligibility and configuration; they
are not additional Java request DTOs.

## Compatibility notes

The existing `DecisionRequest` and `DecisionQuestion` constructors and
accessors remain available, but their Java record component sets changed to
include GA fields such as cache options and multipart instructions. Reflection,
record patterns, and code depending on the old record shape may need updates.
The sealed `DecisionInput` hierarchy now includes history, JSON and more part
types. Exhaustive switches over sealed input and part types must handle
the additions. The native request now rejects Choice counts outside 2–255 and
Score counts outside 2–10 before HTTP, including questions built with the
legacy constructors. The generic adapter enforces the same OpenAI-specific
bounds without changing other providers' question limits.

## Validation status

GA support in this source checkout has offline request/response coverage. A
2026-10-23 smoke call with synthetic JSON, image and history input returned
HTTP 429 before an inference result; the provider error code was not inspected. No
successful GA inference has been verified. The 2026-10-07 beta-era HTTP 429
`billing_not_active` result is historical and does not establish the cause of
the current response. Offline tests require no credentials.

Sources checked 2026-10-23: [Decisions guide](https://developers.openai.com/api/docs/guides/decisions),
[create reference](https://developers.openai.com/api/reference/resources/decisions/methods/create),
[Decisions usage reference](https://developers.openai.com/api/reference/resources/admin/subresources/organization/subresources/usage/methods/decisions),
[GPT-Live Decisions guide](https://developers.openai.com/api/docs/guides/decisions-voice),
[Private Link](https://developers.openai.com/api/docs/guides/private-link),
[data controls](https://developers.openai.com/api/docs/guides/your-data).
