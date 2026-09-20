# Changelog

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
