# Jev Java

[![CI](https://github.com/gudcks0305/jev-java/actions/workflows/ci.yml/badge.svg)](https://github.com/gudcks0305/jev-java/actions/workflows/ci.yml)

Unofficial Java SDK for **TypeSafe Jev** and **Vercel AI Gateway**, with typed decisions, a Spring Boot starter, and optional Spring WebClient/Reactor support. Not affiliated with TypeSafe AI or Vercel.

- Java 17+. JDK `HttpClient` by default; no Spring dependency in the core SDK.
- Choice → enum/string + probabilities; Noul → yes probability; Score → weighted level index.
- Shared synchronous and `CompletableFuture` APIs across both providers.
- Actual WebClient transport, user-supplied clients/filters, and lazy `Mono<Evaluation>`.
- Bounded retries, total deadline, cancellation, immutable question/result data.

**Status:** `0.1.0`, source-first; not published to Maven Central. Vercel support uses an experimental AI SDK protocol, not a stable public Java REST contract. See [protocol notes](docs/protocols.md).

## Build and install locally

```sh
git clone https://github.com/gudcks0305/jev-java.git
cd jev-java
./mvnw verify
./mvnw install
```

Tests use local servers/fakes and need no API keys. Live examples make billable requests and are opt-in.

## Plain Java

After installing locally, add the provider you need:

```xml
<dependency>
  <groupId>io.github.gudcks0305</groupId>
  <artifactId>jev-typesafe</artifactId> <!-- or jev-vercel -->
  <version>0.1.0</version>
</dependency>
```

```java
import io.github.gudcks0305.jev.*;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;

enum Department { BILLING, TECHNICAL, SALES }

var route = ChoiceQuestion.of("route", "Which department should handle this?", Department.class);
var urgent = NoulQuestion.of("urgent", "Is this time-sensitive?");

try (JevClient client = TypeSafeJevClient.builder().build()) { // TYPESAFE_API_KEY
    var result = client.evaluate("I was charged twice. Please refund me.", route, urgent);
    Department department = result.answer(route).choice();
    double probability = result.answer(urgent).probability();
    boolean escalate = result.answer(urgent).atLeast(0.9); // application-owned threshold
}
```

For Vercel, replace construction with `VercelJevClient.builder().build()` (package `io.github.gudcks0305.jev.vercel`), using `AI_GATEWAY_API_KEY`. Default models: `jev-latest` and `typesafe-ai/jev`. Override with `.model("...")`; pin a direct TypeSafe version when repeatability matters.

`evaluateAsync(state, questions...)` returns a cancellable `CompletableFuture<Evaluation>`. Reuse clients; close them when the application stops. Blocking `evaluate()` is for blocking callers, never a reactive event loop.

String choices can carry descriptions, including structured JSON:

```java
var route = ChoiceQuestion.of("route", "Choose a team",
    Map.of("billing", "Payments and refunds", "technical", "Bugs and outages"));
var score = ScoreQuestion.of("severity", "How severe is this?",
    List.of("No problem", "Minor problem", "Major problem"));
```

State and instructions accept strings, maps, lists, Jackson `JsonNode`, or serializable POJOs/records. Enum wire labels use `Enum.name()`. Use `.withDescriptions(Map.of(...))` to describe enum choices. Keep the returned question instance: `result.answer(question)` checks identity as well as the generic result type.

## Spring Boot starter

```xml
<dependency>
  <groupId>io.github.gudcks0305</groupId>
  <artifactId>jev-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```yaml
jev:
  provider: typesafe # or vercel
  transport: jdk
  timeout: 30s
  max-retries: 2
```

Inject `JevClient` into your service. The starter reads the selected provider's environment key when `jev.api-key` is unset. It makes no network calls during initialization. A user-defined `JevClient` bean takes precedence; `jev.enabled=false` disables auto-configuration.

| Property | Default | Meaning |
| --- | --- | --- |
| `jev.enabled` | `true` | Enable auto-configuration |
| `jev.provider` | `typesafe` | `typesafe` or `vercel` |
| `jev.transport` | `jdk` | `jdk` or `webclient` |
| `jev.api-key` | provider environment variable | Explicit key override |
| `jev.model` | provider default | Model ID |
| `jev.base-url` | provider origin | Custom HTTP(S) origin/path prefix, without the endpoint suffix |
| `jev.timeout` | `30s` | Total HTTP deadline across retries, positive and at most one day |
| `jev.max-retries` | `2` | Retries after initial attempt, 0–10 |

Environment variables exported in `.zshrc` are inherited only by processes started from that shell; IDEs/containers need their own environment configuration. Do not commit keys to configuration files.

## WebClient and Reactor

Add `jev-spring-webflux` alongside the starter. The base starter deliberately does not pull in WebFlux.

```xml
<dependency>
  <groupId>io.github.gudcks0305</groupId>
  <artifactId>jev-spring-webflux</artifactId>
  <version>0.1.0</version>
</dependency>
```

```yaml
jev:
  provider: typesafe
  transport: webclient
```

Inject `ReactorJevClient` (package `io.github.gudcks0305.jev.webflux`):

```java
public Mono<Department> route(String message) {
    var question = ChoiceQuestion.of("route", "Which department?", Department.class);
    return jev.evaluate(message, question)
        .map(result -> result.answer(question).choice());
}
```

Each subscription starts a new call. Cancellation reaches the HTTP request and pending retries; the SDK never calls `.block()`. A unique/primary user `WebClient` is preferred, then a unique/primary `WebClient.Builder`, then a default builder. Declare a primary bean if several clients exist. Your connector, filters, observability and pool configuration stay in use. Caller-supplied WebClient/connector resources are not shut down by the SDK.

Outside Boot:

```java
var transport = new WebClientJevTransport(webClient, Duration.ofSeconds(30), 2);
var client = TypeSafeJevClient.builder().transport(transport).build();
var reactive = new ReactorJevClient(client);
// Subscribe to reactive.evaluate(state, questions...).
// At shutdown: client.close(); transport.close(); then close resources you own.
```

An injected transport owns its timeout/retry policy; builder `.timeout()` / `.maxRetries()` configure only the default JDK transport. For Boot-managed WebClient transport, `jev.*` settings configure the transport bean. `ReactorJevClient` itself does not own the delegate.

## Errors and result semantics

`JevException.kind()` distinguishes authentication, validation, rate limiting, server/HTTP, connection, timeout, protocol, and closed-client failures. `statusCode()` is the HTTP status or 0 when unavailable. Invalid local arguments fail before transport. Default errors exclude request/response bodies and credentials; inspect the provider dashboard for account-specific diagnostics.

Only explicit **429, 529, 502, 503, 504** responses retry, respecting `Retry-After` within the total deadline. Connection failures and ambiguous timeouts do not automatically retry, because the service may already have processed/billed the call. No automatic fallback between providers.

- Noul is a yes probability, not a separate confidence score.
- Score is a weighted **zero-based level index**; three levels span 0–2, not 0–1.
- Choice/Score confidence remains optional. It is not fabricated when missing.
- Vercel may omit probabilities/usage: empty distributions and optional counts preserve that absence.
- Displayed probabilities may be rounded; the SDK preserves them without renormalization.
- Full returned metadata, warnings and rounding information are available in `rawResponse()`.
- Typed output does not guarantee a correct judgment. Evaluate application thresholds against your own labeled data.

## Live examples

Export `TYPESAFE_API_KEY` and/or `AI_GATEWAY_API_KEY` in your shell, then:

```sh
./mvnw -q -pl examples exec:java \
  -Dexec.mainClass=io.github.gudcks0305.jev.examples.Quickstart \
  -Dexec.args=typesafe

./mvnw -q -pl examples exec:java \
  -Dexec.mainClass=io.github.gudcks0305.jev.examples.WebClientExample \
  -Dexec.args=typesafe

./mvnw -q -pl examples exec:java \
  -Dexec.mainClass=io.github.gudcks0305.jev.examples.SpringBootExample \
  '-Dexec.args=--jev.provider=typesafe --jev.transport=webclient'
```

Use `vercel` to exercise Gateway. Your Vercel account must be allowed to use AI Gateway; `403 customer_verification_required` means the account requires verification, not a retryable SDK error.

## Modules

| Artifact | Purpose |
| --- | --- |
| `jev-core` | Typed questions/results, client SPI, JDK HTTP transport |
| `jev-typesafe` | TypeSafe public API adapter |
| `jev-vercel` | Experimental Vercel evaluation adapter |
| `jev-spring-webflux` | WebClient transport + Reactor facade |
| `jev-spring-boot-autoconfigure` | Optional Spring auto-configuration |
| `jev-spring-boot-starter` | Dependency starter, no WebFlux dependency |

CI tests Java 17/21/25 with Spring Boot 3.5.16 and 4.1.1. See [validation notes](docs/validation.md) for local/live evidence and limitations. Licensed under [MIT](LICENSE).
