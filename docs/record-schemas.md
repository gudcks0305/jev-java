# Declarative record outputs

Since 0.2.0, every `JevClient` can fill an annotated Java record. This feature is
part of `jev-core`; it needs no Spring, LangChain4j or annotation processor.

```java
import io.github.gudcks0305.jev.JevClient;
import io.github.gudcks0305.jev.schema.*;
import java.util.List;
import java.util.Optional;

public final class RecordGuide {
    enum Department {
        @JevLabel("Invoices, charges and refunds") BILLING,
        @JevLabel("Bugs and outages") TECHNICAL
    }

    record Risk(
            @JevProbability("Does this customer request show signs of fraud?") double fraudProbability) {}

    record Ticket(
            @JevChoice("Which department should handle this customer request?") Department department,
            @JevBoolean(value = "Does the customer need a reply today?", threshold = .8) boolean urgent,
            @JevScore(value = "How severe is the customer's issue?", levels = {"Low", "Medium", "High"}) double severity,
            @JevLabels(value = "Does this request concern the given department?", threshold = .7) List<Department> related,
            @JevChoice(value = "Which department should receive an escalation, if any?",
                    noneDescription = "No escalation is warranted") Optional<Department> escalation,
            Risk risk) {}

    static Ticket classify(JevClient client, String text) {
        JevSchema<Ticket> schema = JevSchema.of(Ticket.class);
        TypedEvaluation<Ticket> result = client.evaluate(text, schema);
        return result.value();
    }
}
```

## Compilation and execution

`JevSchema.of(Ticket.class)` validates and caches an immutable schema per class,
using `ClassValue` so application class loaders can be unloaded. Invalid schemas
fail before any client request. Reuse schemas across threads and providers.

`evaluate(state, Ticket.class)` is shorthand for compiling/reusing that schema.
`evaluateAsync` returns `CompletableFuture<TypedEvaluation<Ticket>>` and the
Reactor facade returns a lazy `Mono<TypedEvaluation<Ticket>>`. Cancelling the
mapped future or Mono cancels its underlying request. Each subscription is a new
evaluation; record mapping never introduces a second inference call.

Each leaf compiles to one native question, except `JevLabels`, which expands to
one Noul per enum constant. All questions share one state and one request.
Existing provider token/context limits still apply; no automatic chunking or
fallback is performed.

## Type and policy rules

- Every scalar/collection leaf requires exactly one judgment annotation. Nested
  record containers have no judgment annotation; annotate their leaf fields.
- `JevBoolean` accepts boolean/Boolean and requires a finite threshold in [0, 1].
  The result is `probability >= threshold`. No default threshold is invented.
- `JevProbability` accepts double/Double and returns the original yes probability.
- `JevScore` accepts double/Double and 2–10 nonblank ordered descriptions. The
  output is the continuous zero-based level index, not a percentage or integer.
- `JevChoice` accepts an enum or `Optional<Enum>`. Labels use `Enum.name()`, never
  an overridden `toString()`. `JevLabel` on enum constants supplies descriptions.
  At most 255 options are allowed, including the Optional no-match option.
- An Optional choice adds a distinct `<none>` wire option. It means none of the
  choices applies; it is not a confidence threshold or automatic abstention.
- `JevLabels` accepts `List<Enum>` or `Set<Enum>` with a concrete enum argument.
  Its instruction is paired with each label and optional label description.
  Independent probabilities need not sum to one. Selected values preserve enum
  declaration order in immutable collections; zero selections is valid.
- Nested paths become IDs such as `risk.fraudProbability` and `related.BILLING`.
  Field names/IDs are not model instructions: write complete questions in the
  annotations, especially when reusing a nested record in multiple contexts.
- Unsupported: free-text String fields, arbitrary POJOs/maps, unbounded integers,
  floats, arrays, wildcard/raw collections, Optional non-enums, generic record
  classes, empty records and recursive record graphs.

## Results and constructor validation

`result.value()` is the record. `result.evaluation()` retains every native answer,
distribution, confidence, model, usage value and raw provider response. Enum
choice metadata uses wire label strings. Schema question objects can be inspected
with `schema.questions()`; `schema.decode(evaluation)` requires those same question
instances, preserving the SDK's native identity checks.

Canonical record constructors run after the response is decoded, including any
compact-constructor validation written by the application. A rejected result
raises a `JevException` with kind `PROTOCOL` and is not retried. Exception messages
do not include constructor-provided details or response values. Mapping does not
guarantee that independent judgments satisfy cross-field business invariants.
Jakarta Bean Validation annotations are not executed by the SDK; use a compact
constructor or an application validator for those constraints.

In a named JPMS module, use accessible public records/constructors or open the
record package to the SDK. Inaccessible constructors fail during schema creation.
Records and annotations must be retained when configuring native-image reflection;
automatic GraalVM reflection metadata is not provided in this release.

Jev's judgments remain probabilistic. Tune thresholds and any confidence-based
policy against labeled examples appropriate to the application. The schema layer
does not perform tool calls, model routing, safety approval or LLM fallback.
