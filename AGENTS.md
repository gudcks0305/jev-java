# Jev Java contributor instructions

## Scope and existing behavior

This repository is an unofficial Java 17+ SDK for typed Jev judgments. Read
`README.md`, `docs/protocols.md`, and the affected module before proposing work.
Check `git status` and preserve unrelated user and agent changes.

- `jev-core`: questions, answers, record schemas, wire codecs, JDK transport.
- `jev-typesafe`, `jev-vercel`, `jev-openrouter`, `jev-cloudflare`: existing
  provider clients. Do not propose these integrations as missing.
- `jev-spring-boot-autoconfigure` and `jev-spring-boot-starter`: optional Spring
  configuration; `jev-spring-webflux`: WebClient transport and Reactor facade.
- `examples`: runnable examples; `docs`: contracts, compatibility, validation,
  and release notes.

Record mapping, explicit boolean thresholds, probability/confidence access,
request-level question batching, retries, deadlines, and custom endpoints
already exist. Distinguish them from dataset evaluation, model fallback, and
telemetry. Verify a claimed gap in code and tests before suggesting additions.

## API and provider boundaries

- Preserve native Choice, Noul, and Score semantics and missing metadata. Do
  not invent confidence, normalize distributions, or equate typed output with
  correct decisions. Applications own thresholds and actions.
- Keep Java 17 compatibility. Core must not require Spring or a logging/metrics
  framework. Keep optional integrations separate.
- Preserve asynchronous cancellation, total deadlines, retry policy, and
  ownership of injected transports. Avoid extra inference calls for metrics.
- Check current official provider contracts. A model listing, authentication
  response, mock test, and successful live inference are different evidence.
- Before adding a provider module, check whether the existing TypeSafe client
  with `apiKey`, `model`, and `endpoint` suffices. `endpoint` is a full URL;
  `baseUrl` appends the provider suffix. They are mutually exclusive.
- Document compatible gateways in separate provider guides linked from README
  and `docs/protocols.md`. Put configuration and provider-specific limitations
  in the guide; summarize verification evidence in `docs/validation.md`.
- Label official examples and synthetic fixtures accurately. Never present
  fixture parsing as a live provider result. Record source URLs and check dates.

## Evaluation and observability

- Keep labeled evaluation separate from runtime inference. Define metric
  denominators, abstentions, failures, and missing metadata explicitly.
- Treat sample datasets and thresholds as examples, not accuracy claims.
- Observability is opt-in. Expose bounded metadata such as elapsed time,
  model, usage, outcome, and error kind. Do not expose API keys, headers, raw
  state, questions, responses, or exception messages in default events/logs.
- Observer failures must not change inference results or cancellation.

## Verification and changes

Use the Maven wrapper; do not assume system Maven is installed:

```sh
./mvnw -pl jev-typesafe -am test  # focused provider/core check
./mvnw verify                  # full offline suite and build
```

CI checks Java 17/21/25 with the Spring Boot versions in
`.github/workflows/ci.yml`. Add meaningful tests for changed behavior, including
protocol/error boundaries and async lifecycle when relevant. Tests must run
without credentials; use local servers or sanitized fixtures. Compile runnable
documentation examples when their API usage changes.

Use `rg` for documentation/configuration and `ast-grep` for structural source
queries where useful. Delegate independent work with explicit file ownership;
workers must preserve concurrent changes and return concise evidence.

Keep documentation and runtime feature changes in separate reviewable commits
or PRs when requested. PRs describe final behavior, compatibility limits, and
actual validation. Do not merge a feature PR, publish artifacts, or create a
release tag unless that action is authorized. Follow `CONTRIBUTING.md` and
`docs/central-publishing.md` for releases; keep generated artifacts untracked.
