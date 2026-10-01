# Validation

Recorded on 2026-09-20. Test judgments are examples, not an accuracy benchmark.

## Compatible services and decision models (2026-10-01)

`./mvnw -pl jev-typesafe -am test` passed **66 tests**. The full
`./mvnw verify` passed **124 tests, 0 failures/errors/skips**, including nine
new compatibility test invocations. Local runtime: OpenJDK 25.0.2, Java
source/target release 17, Spring Boot 3.5.16. These are local results; no new
remote CI run or release is claimed.

The [DigitalOcean](digitalocean.md), [OpenCode Zen](opencode-zen.md),
[LLM Gateway](llm-gateway.md), and [Eden AI](edenai.md) guides were checked
against their linked official documentation. They configure the existing
TypeSafe client with a provider key, model, and full endpoint. These four
routes have documentation review and Java example compilation only; no
provider-specific response fixture or successful live inference was tested.

[Solar Decide](solar-decide.md) and [Liquid d1](liquid-d1.md) are different
models with a TypeSafe-shaped API. Credential-free loopback HTTP tests check:

- Solar: its published three-primitive response and 27-option overflow error.
- Liquid: published answer fields in a synthetic combined response envelope,
  and a complete published Noul response including zero output tokens.
- Vercel's [public TypeSafe-compatible route](vercel-ai-gateway.md): a synthetic
  native response, separate from the existing v4 adapter tests.

Fixture provenance and adaptations are listed in the
[test resource notes](../jev-typesafe/src/test/resources/compatible-providers/README.md).
Assertions preserve the provider examples' numeric values, including rounding;
they do not recompute scores or calibrate confidence. Mutated test fixtures
check missing optional metadata and rejection of missing required distributions.
Those mutations do not imply that a provider emits those responses.

The seven new guides' Java blocks compile with `javac --release 17` when
wrapped in a `main` method using the project's core and TypeSafe classes.
The examples were not executed against providers. No credentials or billable
requests were used for this change. Successful live Java inference through
all seven routes remains **unverified**.

## Venice and AI/ML API compatibility (2026-09-28)

The existing TypeSafe client can be configured with each provider's explicit
API key, model, and complete Decisions endpoint. The focused
`./mvnw -pl jev-typesafe -am test` run passed **57 tests**. Those tests cover
the existing client and local HTTP contracts; they are not live inference on
either new endpoint.

- Venice: unauthenticated `GET /api/v1/models?type=decision` returned HTTP 200
  with `jev-latest` in the model list. Unauthenticated POST
  `/api/v1/decisions` returned HTTP 402 `Authentication required`. The model
  listing does not establish authenticated access or successful inference.
- AI/ML API: its [published Jev response example](https://docs.aimlapi.com/api-references/decision-models/typesafe/jev)
  was parsed offline through the TypeSafe response parser. Noul `0.96`, Choice
  `billing`, Score `1.3`, input tokens `403`, and raw
  `meta.usage.credits_used=47` were retained. Unauthenticated POST
  `/v1/decisions` returned HTTP 401. The example is a documentation fixture,
  not a live response.

No provider key was available for either endpoint; successful live Java
inference remains **unverified**. See the [Venice](venice.md) and
[AI/ML API](aimlapi.md) guides for configuration and contract limits.

## 0.2.0 records and Cloudflare (2026-09-21)

`./mvnw clean verify -Prelease` passed **115 tests, 0 failures/errors/skips**.
Record tests cover every supported field type, explicit threshold boundaries,
nested/reused records, immutable multi-label collections, Optional no-match,
invalid/recursive/generic schemas rejected before HTTP, question identity,
concurrent schema reuse, constructor rejection and async/Reactor cancellation.
Cloudflare tests cover account-scoped URLs, exact endpoint overrides, request
envelopes, direct/enveloped responses, error envelopes and Spring configuration.

The live `RecordExample typesafe` call returned a `Triage` record containing
`BILLING`, `urgent=true`, refund probability `0.99` and score `1.19`; the same
evaluation retained model `jev-1.13.0`, four questions and 409 input tokens.
This confirms record construction from a real response, not classification accuracy.
Cloudflare live inference was not tested: account ID and API token were unavailable.

## 0.1.1 OpenRouter and endpoint support

`./mvnw clean verify -Prelease` passed **78 tests, 0 failures/errors/skips**.
Coverage adds the OpenRouter default route, exact custom path/query over a local
HTTP server, request and response contract mapping, optional distributions and
metadata, invalid criteria/legend rejection, all three Spring provider endpoint
bindings, and mutual exclusion with `baseUrl`. Existing TypeSafe/Vercel tests
remain in the suite. The prior boolean internal constructors/codec overloads
remain present for binary compatibility with 0.1.0 provider clients.

OpenRouter live inference is **not verified**: `OPENROUTER_API_KEY` was unavailable.
Contract sources are linked in [provider protocols](protocols.md).

## 0.1.0 offline baseline

`./mvnw clean verify -Prelease` passed **55 tests, 0 failures/errors/skips** locally. The suite covers:

- Direct/Gateway request headers, paths, wire types and metadata mapping.
- Enum result types, question identity, defensive JSON copies and invalid responses.
- JDK/WebClient timeouts, HTTP retries, cancellation and close behavior.
- Large error responses and very large Retry-After values in WebClient.
- Lazy Reactor subscriptions and future cancellation propagation.
- Boot properties, custom-client backoff, actual injected WebClient use, disabled configuration, imports discovery and a classpath without WebFlux/Reactor.

Local development runtime: OpenJDK 25.0.2, source/target release 17. Boot 3.5.16 and 4.1.1 were tested. GitHub CI separately runs Java 17/21/25 against both Boot versions; consult the commit's workflow checks for their status.

## Live

The following opt-in examples returned successful results using environment credentials, with synthetic input only:

| Path | Evidence |
| --- | --- |
| TypeSafe + JDK HttpClient | Resolved `jev-1.13.0`; enum Choice, Noul and Score received |
| TypeSafe + WebClient | User WebClient filter observed one exchange; Noul received |
| TypeSafe + Spring Boot/WebClient | Auto-configuration started; reactive bean returned Noul |
| Vercel Gateway | HTTP 403 `customer_verification_required`; no inference result |

The Vercel result confirms reaching the Gateway account check, not successful Jev inference. Offline tests cover its adapter using the pinned official schema. No credentials, actual customer data or account identifiers are included in fixtures or release assets.

## Release verification

```sh
./mvnw clean verify -Prelease
./scripts/package-release.sh 0.1.0
cd dist/release/0.1.0
shasum -a 256 -c SHA256SUMS
```

Javadoc and sources are built with the binaries. The dependency-only starter contains no Java API documentation. GitHub release publication runs the release build again from the pushed tag.

## Maven Central 0.1.0

Central deployment `3bea6bf0-b85f-4771-a1e3-f1f61af9681b` reached `PUBLISHED`.
The parent POM and all six SDK modules are published under `io.github.gudcks0305`.
The Central build uses explanatory sources/Javadoc placeholders for the dependency-only starter.

- [Signed upload and validation workflow](https://github.com/gudcks0305/jev-java/actions/runs/35454546753)
- 25 signatures verified; public-key fingerprint `0E33C3E5CAE942F0B98F4C34E4C08305A1115D16`.
- Public key available from [keys.openpgp.org](https://keys.openpgp.org/vks/v1/by-fingerprint/0E33C3E5CAE942F0B98F4C34E4C08305A1115D16).
- README's five standalone Java examples compile with release 17.
- A separate consumer project, empty Maven cache and clean settings resolved
  the published starter/WebFlux dependencies (all six SDK JARs and seven POMs)
  from Central and compiled with release 17. No local SDK installation was used.

The initial two uploads failed Central's public-key lookup. After registering the
public key on keys.openpgp.org and allowing time for lookup availability, the
subsequent deployment validated. A specific Central cache policy was not established.
