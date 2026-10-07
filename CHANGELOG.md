# Changelog

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
