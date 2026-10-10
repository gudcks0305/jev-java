# Evaluation observation

This feature is available in the source branch and is not part of the published
0.3.0 artifacts. Build from source to use it before the next release.

Attach an observer to any provider builder to receive metadata when a logical
evaluation completes. The hook has no logging backend or metrics dependency.
Nothing is logged unless your application registers a callback.

The hook observes `evaluate` and `evaluateAsync`, including OpenAI's compatible
evaluation path. Native OpenAI `decide` and `decideAsync` do not emit
`EvaluationEvent`: they return `DecisionResult` with separate `DecisionUsage`
metadata and may override the model per request. Their cancellation, retries,
deadlines, and client-close behavior still use the same shared lifecycle.

```java
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;

System.Logger logger = System.getLogger("jev.evaluations");
try (var client = TypeSafeJevClient.builder()
        .observer(event -> logger.log(System.Logger.Level.INFO,
                "Jev outcome={0} elapsedMs={1} requestedModel={2} errorKind={3}",
                event.outcome(), event.elapsed().toMillis(),
                event.requestedModel(), event.errorKind().orElse(null)))
        .build()) {
    client.evaluate("Please refund the duplicate charge.",
            NoulQuestion.of("refund", "Does this request a refund?"));
}
```

`EvaluationEvent` contains:

| Field | Meaning |
| --- | --- |
| `elapsed` | Duration of a logical evaluation, including transport retries |
| `requestedModel` | Model configured on the client |
| `returnedModel` | Optional model identifier explicitly present in a successful provider response |
| `questionCount` | Number of questions submitted together |
| `outcome` | `SUCCESS`, `FAILURE`, or `CANCELLED` |
| `usage` | Successful evaluation's usage; missing token counters remain absent |
| `errorKind` | Optional `JevException.Kind`; unrelated exceptions have no invented kind |
| `statusCode` | Optional HTTP status carried by a `JevException` |

The event contains no API key, headers, endpoint, input state, question text,
answers, raw response, exception object, or exception message. A caller may
aggregate events into its existing telemetry system without handling those
payloads. Failure and cancellation events do not invent token usage or a
resolved model.

## Lifecycle

- Observations begin after input validation and request encoding succeed.
  Invalid arguments and requests rejected because the client is already closed
  emit no event.
- An accepted evaluation emits one terminal event. Retries are included in that
  evaluation rather than reported as separate requests.
- Cancelling an asynchronous request still cancels its transport. Closing a
  client with requests in flight emits a failure with kind `CLOSED`.
- Observer failures do not replace an inference result or error, and do not
  interrupt cancellation.
- Callbacks can run concurrently, on caller or transport threads. Keep them
  short and nonblocking; enqueue work if exporting telemetry may block. Do not
  assume the callback has finished merely because another thread obtained the
  evaluation result.

Record mapping and the Reactor facade use the same underlying evaluation.
An observation describes inference completion, not later application processing
or record decoding. A custom `JevClient` implementation must provide its own
observation integration; the hook belongs to this SDK's provider builders.

## Spring

The minimal hook does not add configuration properties or a Micrometer binder.
Register a provider client bean with `.observer(...)`; existing auto-configuration
backs off when a `JevClient` bean is present. Choose the transport and lifecycle
for that bean as with any other caller-configured client. See the
[WebClient and Reactor configuration](../README.md#spring-boot).
