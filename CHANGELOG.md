# Changelog

## Unreleased

- OpenAI Decisions GA input supports JSON, developer/system/assistant history,
  function/custom-tool calls and outputs, and hosted image URLs. The SDK does
  not execute tools or fetch remote images.
- Native questions accept text/image/JSON instruction parts. Requests expose
  prompt-cache keys, implicit/explicit modes, 30-minute TTL and input breakpoints.
- Generic OpenAI evaluation accepts JSON state and structured instructions,
  including `@JevLabels`. Provider-specific question, image and JSON limits
  are validated before transport.
- Existing constructor/accessor signatures remain available. Record shapes and
  sealed input/part variants changed; exhaustive switches and reflection may
  need updates. Invalid Choice/Score counts are now rejected at request creation.

See the [OpenAI guide](docs/openai-decisions.md) and
[validation evidence](docs/validation.md). These changes are not in 0.5.0.

## 0.5.0 — 2026-10-17

- OpenRouter and Cloudflare clients now accept `JevInput` images and
  `BoxQuestion`. OpenRouter expands references to inline image parts; Cloudflare
  sends native assets under `input`. Provider-specific model IDs remain explicit.
- Vercel's current Decision v4 protocol is available through
  `VercelJevClient.Protocol.DECISION_V4` and `VercelDecisionInput` text/JSON/image
  state parts. Existing evaluation-v4 behavior remains the default. Spring
  applications can select `jev.vercel-protocol=decision-v4`.
- Vercel Decision refusals fail the whole evaluation with `REFUSAL`; Box remains
  unsupported by Vercel Gateway. Images in Vercel questions/criteria are not
  inferred from plain JSON or Jev asset references.
- Repeated inline OpenRouter images count toward the complete 32 MiB request
  limit. Image normalization preserves caller data, reference order, metadata,
  numeric precision, and the existing cancellation/transport ownership rules.

Provider image features remain experimental. OpenRouter/Cloudflare verification
uses official contracts and offline tests; credentials were unavailable.
A synthetic Vercel Decision image request returned HTTP 403, so successful
Vercel live inference remains unverified. See [release notes](docs/releases/0.5.0.md).

## 0.4.0 — 2026-10-17

- Experimental TypeSafe image inputs: `JevImage` and `JevInput` attach PNG/JPEG
  assets referenced from state, instructions, or criteria. Image requests send
  base64 JSON and the required experimental header without changing text requests.
- Jev 1.14 `BoxQuestion`, `BoxAnswer`, and `BoxCoordinates` preserve normalized
  edges and optional confidence. Box requires an attached image and the TypeSafe
  wire format; pin `jev-1.14.0` rather than relying on the stable model alias.
- Existing synchronous, asynchronous, record, Spring and Reactor APIs accept the
  image input wrapper, retaining cancellation, retries and transport ownership.
- Request-size validation matches the UTF-8 bytes sent by JDK and WebClient,
  including supplementary Unicode. Embedded Jackson POJO/raw JSON references
  are recognized without losing numeric precision or exposing malformed content.
- `Question` and `Answer` gain sealed subtypes; exhaustive switches may need
  Box cases. No Box record annotation, multipart uploads or automatic resizing.

One synthetic live image request succeeded, but its returned Box did not match
the true boundaries. This is interoperability evidence, not an accuracy claim.
See [images](docs/images.md), [validation](docs/validation.md), and
[release notes](docs/releases/0.4.0.md).

## 0.3.0 — 2026-10-07

- OpenAI Decisions adapter (`jev-openai`, `OpenAiJevClient`) with native
  text/image inputs, typed string/boolean choices, score descriptions, optional
  question names, per-request safety identifiers, and partial refusal results.
- Existing text-based Noul/Choice/Score and compatible record schemas use the
  same client; generic evaluations fail with `JevException.Kind.REFUSAL` if any
  question is refused. Exhaustive switches over the error enum need this case.
- Spring `jev.provider=openai`, typed client injection, and lazy
  `ReactorOpenAiClient` with cancellation propagation and caller-owned delegates.
- Shared async lifecycle retains transport ownership, retries, deadlines and
  cancellation across native and generic calls.
- Native DTOs preserve value types, immutable collections, complete usage and
  raw metadata. Image inputs remain inline; the SDK never fetches external images.
- Manual Central workflow gains an explicit `publish` operation for validated
  release tags. Default `verify` and manual `upload` behavior remain unchanged.

See the [OpenAI guide](docs/openai-decisions.md) and
[validation notes](docs/validation.md) for supported surfaces and evidence.

## 0.2.0 — 2026-09-21

- First-class annotated Java record outputs in `jev-core`: `JevSchema<T>` and `TypedEvaluation<T>`.
- Boolean, enum/Optional enum, raw probability, weighted score, nested records, and independent enum labels.
- Explicit thresholds, schema preflight, original answer/metadata preservation, and cancellation-aware async/Reactor mapping.
- Cloudflare `typesafe/jev` adapter, account-scoped configuration, response envelope handling and Spring integration.
- Existing manual question APIs and provider URL settings remain available.

Cloudflare live inference is unverified without credentials. The record path is validated with contract tests and the TypeSafe live example.

## 0.1.1 — 2026-09-20

- OpenRouter alpha Decisions adapter (`jev-openrouter`, `OpenRouterJevClient`).
- Full `.endpoint(URI)` override on every provider, preserving custom paths and query strings.
- Spring `jev.provider=openrouter` and `jev.endpoint`, including WebClient transport.
- Provider-specific optional probability handling and OpenRouter criteria validation.
- Existing `baseUrl` behavior remains unchanged; setting both URL options fails fast.

OpenRouter live inference is unverified without an API key; official-schema and local HTTP contract tests cover the adapter.

## 0.1.0 — 2026-09-20

- Typed Choice, Noul and Score questions, including enum choices and batched evaluation.
- TypeSafe public API and experimental Vercel AI Gateway evaluation adapters.
- Synchronous and cancellable asynchronous Java APIs.
- Spring Boot starter with provider/transport selection and configuration metadata.
- Optional WebClient transport and lazy Reactor API, preserving caller-owned resources.
- Total deadlines, bounded HTTP-status retries, strict response validation and safe error messages.
- Offline contract/lifecycle tests and opt-in live examples.

Vercel successful inference remains unverified in the development account because AI Gateway returns `403 customer_verification_required`. See [validation](docs/validation.md).
