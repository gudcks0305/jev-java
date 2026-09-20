# Jev Java

[![CI](https://github.com/gudcks0305/jev-java/actions/workflows/ci.yml/badge.svg)](https://github.com/gudcks0305/jev-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.gudcks0305/jev-typesafe)](https://central.sonatype.com/artifact/io.github.gudcks0305/jev-typesafe)

Unofficial Java SDK for turning application state into typed Jev judgments through TypeSafe, OpenRouter, or Vercel AI Gateway. Define `Choice`, `Noul`, and `Score` questions in Java, submit them together, and receive typed results instead of parsing generated text.

Jev Java requires Java 17 or newer. The plain SDK uses JDK `HttpClient` and Jackson 2; Spring is optional. This project is not affiliated with TypeSafe AI or Vercel.

**Version 0.1.1 adds OpenRouter and full endpoint overrides.** Add a dependency to get started; no local source installation or custom Maven repository is required.

## Installation

Choose the module that matches your application:

| Artifact | Use it for |
| --- | --- |
| `jev-typesafe` | Plain Java client for TypeSafe's public Jev API |
| `jev-openrouter` | Plain Java client for OpenRouter's alpha Decisions API |
| `jev-vercel` | Plain Java adapter for Vercel AI Gateway's experimental evaluation protocol |
| `jev-spring-boot-starter` | Spring Boot auto-configuration with the default JDK transport |
| `jev-spring-webflux` | Optional WebClient transport and lazy Reactor facade |

For direct TypeSafe access:

```xml
<dependency>
  <groupId>io.github.gudcks0305</groupId>
  <artifactId>jev-typesafe</artifactId>
  <version>0.1.1</version>
</dependency>
```

Use `jev-openrouter` for OpenRouter or `jev-vercel` for Vercel AI Gateway. Each provider module brings in `jev-core`; do not add it separately.

Gradle Kotlin DSL:

```kotlin
repositories { mavenCentral() }

dependencies {
    implementation("io.github.gudcks0305:jev-typesafe:0.1.1")
}
```

## Quick start

Set the key for your provider:

```sh
export TYPESAFE_API_KEY=...
# or: export OPENROUTER_API_KEY=...
# or: export AI_GATEWAY_API_KEY=...
```

One `evaluate` call can batch different typed questions:

```java
import io.github.gudcks0305.jev.ChoiceQuestion;
import io.github.gudcks0305.jev.JevClient;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.ScoreQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.util.List;

public final class Quickstart {
    enum Department { BILLING, TECHNICAL, SALES }

    public static void main(String[] args) {
        var route = ChoiceQuestion.of(
                "route", "Which department should handle this?", Department.class);
        var urgent = NoulQuestion.of(
                "urgent", "Does this require an urgent response?");
        var severity = ScoreQuestion.of(
                "severity", "How severe is the problem?",
                List.of("No problem", "Minor problem", "Major problem"));

        try (JevClient client = TypeSafeJevClient.builder().build()) {
            var result = client.evaluate(
                    "I was charged twice and need a refund today.",
                    route, urgent, severity);

            Department department = result.answer(route).choice();
            double urgentProbability = result.answer(urgent).probability();
            boolean escalate = result.answer(urgent).atLeast(0.90);
            double severityIndex = result.answer(severity).score();

            System.out.printf("%s urgent=%.2f escalate=%s severity=%.2f%n",
                    department, urgentProbability, escalate, severityIndex);
        }
    }
}
```

`NoulAnswer.probability()` is the probability of `true`; the application owns its threshold. A `Score` is a weighted zero-based level index, so three levels span 0–2. `ChoiceAnswer.probabilities()`, `ScoreAnswer.probabilities()`, and their `confidence()` values preserve provider data; a missing distribution or confidence remains absent rather than being invented.

String choices can include plain or structured descriptions:

```java
import io.github.gudcks0305.jev.ChoiceQuestion;
import java.util.Map;

final class StringChoiceExample {
    static final ChoiceQuestion<String> ROUTE = ChoiceQuestion.of(
            "route", "Choose a team", Map.of(
                    "billing", "Payments and refunds",
                    "technical", Map.of("handles", "Bugs and outages")));
}
```

Enum labels use `Enum.name()`. Add enum descriptions with `withDescriptions(Map.of(...))`. Keep the exact question instance passed to `evaluate`; `result.answer(question)` checks its identity to preserve the generic answer type.

`evaluateAsync(state, questions...)` returns a cancellable `CompletableFuture<Evaluation>`. Reuse clients and close them when the application stops. Use blocking `evaluate()` only from blocking code.

To use Vercel, construct `VercelJevClient.builder().build()` from `io.github.gudcks0305.jev.vercel`. Default models are `jev-latest` for direct TypeSafe and `typesafe-ai/jev` for Vercel; override either with `.model("...")`.

## OpenRouter and custom endpoints

`OpenRouterJevClient` uses `OPENROUTER_API_KEY`, model `typesafe/jev-1.13`, and
`https://openrouter.ai/api/alpha/decisions`. This is the alpha **Decisions** API,
not Chat Completions. Choose `jev-openrouter` for plain Java or set
`jev.provider=openrouter` with the Spring Boot starter. JDK and WebClient
transports are both supported.

```java
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.openrouter.OpenRouterJevClient;

public final class OpenRouterQuickstart {
    public static void main(String[] args) {
        var question = NoulQuestion.of("refund", "Is the customer requesting a refund?");
        try (var client = OpenRouterJevClient.builder().build()) {
            var result = client.evaluate("Please refund the duplicate charge.", question);
            System.out.println(result.answer(question).probability());
        }
    }
}
```

For proxies, `.baseUrl(URI)` changes the origin/path prefix and still appends the
provider's endpoint suffix. `.endpoint(URI)` instead uses the **complete URL
exactly as supplied**, including its path and optional query. Do not configure
both. These options apply to all three clients and do not change the selected
provider's authentication or wire format.

| Client | Default model | Path appended to `baseUrl` |
| --- | --- | --- |
| `TypeSafeJevClient` | `jev-latest` | `/v1/systemone` |
| `OpenRouterJevClient` | `typesafe/jev-1.13` | `/api/alpha/decisions` |
| `VercelJevClient` | `typesafe-ai/jev` | `/v4/ai/evaluation-model` |

Example Spring configuration for an OpenRouter-compatible proxy:

```yaml
jev:
  provider: openrouter
  transport: webclient
  endpoint: "https://proxy.example.com/evaluate?api-version=1"
```

OpenRouter may omit probability distributions, confidence, legend, model, and
usage metadata. Missing values stay absent (score legends can fall back to the
request's levels). Returned `id`, `provider`, and `usage.cost` remain available
in `rawResponse()`. OpenRouter criteria support strings/objects/arrays; choice
descriptions may be null, score levels may not, and Noul descriptions must be
provided for both true and false or omitted together.

## Spring Boot

The starter includes all three provider adapters and defaults to TypeSafe over JDK `HttpClient`:

```xml
<dependency>
  <groupId>io.github.gudcks0305</groupId>
  <artifactId>jev-spring-boot-starter</artifactId>
  <version>0.1.1</version>
</dependency>
```

```yaml
jev:
  provider: typesafe # or openrouter / vercel
  transport: jdk
  timeout: 30s
  max-retries: 2
```

Inject `JevClient` into blocking services. The starter reads `TYPESAFE_API_KEY`, `OPENROUTER_API_KEY`, or `AI_GATEWAY_API_KEY` when `jev.api-key` is absent, performs no network call during startup, backs off when the application defines its own `JevClient`, and can be disabled with `jev.enabled=false`.

### WebClient and Reactor

The base starter does not pull in WebFlux. Add the optional module and select its transport:

```xml
<dependency>
  <groupId>io.github.gudcks0305</groupId>
  <artifactId>jev-spring-webflux</artifactId>
  <version>0.1.1</version>
</dependency>
```

```yaml
jev:
  provider: typesafe
  transport: webclient
```

The starter now exposes a lazy `ReactorJevClient`:

```java
import io.github.gudcks0305.jev.ChoiceQuestion;
import io.github.gudcks0305.jev.webflux.ReactorJevClient;
import reactor.core.publisher.Mono;

final class RoutingService {
    enum Department { BILLING, TECHNICAL, SALES }

    private final ReactorJevClient jev;

    RoutingService(ReactorJevClient jev) {
        this.jev = jev;
    }

    Mono<Department> route(String message) {
        var question = ChoiceQuestion.of(
                "route", "Which department should handle this?", Department.class);
        return jev.evaluate(message, question)
                .map(result -> result.answer(question).choice());
    }
}
```

Each subscription starts one request, and cancellation reaches the underlying future, HTTP request, and pending retry delay. The SDK does not call `.block()`.

For a command-line or other non-web Boot application, set `spring.main.web-application-type=none` when adding WebFlux.

To retain application filters, observability, connector, and connection-pool settings, expose the `WebClient` you want Jev to use. Mark it primary when multiple clients exist:

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
class JevWebClientConfiguration {
    @Bean
    @Primary
    WebClient jevWebClient(WebClient.Builder builder) {
        return builder.build();
    }
}
```

Auto-configuration chooses a unique/primary `WebClient`, then a unique/primary `WebClient.Builder`, then a default builder. The SDK does not shut down caller-supplied WebClient connector resources.

Outside Spring Boot, wire the transport explicitly:

```java
import io.github.gudcks0305.jev.JevClient;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import io.github.gudcks0305.jev.webflux.ReactorJevClient;
import io.github.gudcks0305.jev.webflux.WebClientJevTransport;
import java.time.Duration;
import org.springframework.web.reactive.function.client.WebClient;

public final class JevClients implements AutoCloseable {
    private final WebClientJevTransport transport;
    private final JevClient client;
    private final ReactorJevClient reactive;

    public JevClients() {
        WebClient webClient = WebClient.builder().build();
        transport = new WebClientJevTransport(
                webClient, Duration.ofSeconds(30), 2);
        client = TypeSafeJevClient.builder().transport(transport).build();
        reactive = new ReactorJevClient(client);
    }

    public ReactorJevClient reactive() {
        return reactive;
    }

    @Override
    public void close() {
        client.close();
        transport.close();
    }
}
```

`ReactorJevClient` does not own its delegate. Close `client` and `transport` during shutdown, then close any connector resources your application owns. When a transport is injected, it owns timeout/retry policy; builder `.timeout()` and `.maxRetries()` apply only to the default JDK transport.

### Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `jev.enabled` | `true` | Enable auto-configuration |
| `jev.provider` | `typesafe` | `typesafe`, `openrouter`, or `vercel` |
| `jev.transport` | `jdk` | `jdk` or `webclient` |
| `jev.api-key` | provider environment variable | Explicit key override |
| `jev.model` | provider default | Model ID |
| `jev.base-url` | provider origin | Custom origin/path prefix; provider endpoint suffix is appended |
| `jev.endpoint` | unset | Full request URL, including path/query; mutually exclusive with `jev.base-url` |
| `jev.timeout` | `30s` | Total deadline across attempts and retry delays |
| `jev.max-retries` | `2` | Retries after the first attempt, from 0 to 10 |

## Errors and response semantics

`JevException.kind()` separates authentication, validation, rate limiting, server/HTTP, connection, timeout, protocol, and closed-client failures. `statusCode()` is the HTTP status, or 0 when none is available. Error messages exclude response bodies and credentials by default.

Only explicit **429, 529, 502, 503, and 504** responses retry. Connection failures and ambiguous timeouts do not automatically retry because the service may already have processed and billed the request. There is no automatic provider fallback.

The SDK preserves missing probabilities, confidence, and token counts as missing. It does not normalize scores or probability distributions. Provider metadata, warnings, and rounding information remain available through `Evaluation.rawResponse()`. Typed output prevents schema mismatch; it does not guarantee a correct judgment. Validate thresholds against your own labeled data.

## Provider status and validation

TypeSafe direct calls have been exercised with the JDK transport, WebClient transport, and Spring Boot auto-configuration. OpenRouter uses the official Decisions contract and local HTTP tests; live inference has not been verified because no OpenRouter API key was available. Vercel Gateway returned `403 customer_verification_required` during live verification, so its adapter is covered by offline protocol tests but successful live inference has not been confirmed. See [provider contracts and protocol limits](docs/protocols.md) and [validation evidence](docs/validation.md).

The test matrix covers Java 17, 21, and 25 with Spring Boot 3.5.16 and 4.1.1. Offline tests use local servers and fakes and require no API keys. The six Java examples in this README were also compiled with `--release 17`.

## Build and run examples from source

The source build runs offline tests and installs the modules locally. The optional live example requires your provider environment key and can make billable requests:

```sh
git clone https://github.com/gudcks0305/jev-java.git
cd jev-java
./mvnw install

./mvnw -q -pl examples exec:java \
  -Dexec.mainClass=io.github.gudcks0305.jev.examples.Quickstart \
  -Dexec.args=typesafe
```

Use `WebClientExample` or `SpringBootExample` for those integration paths. Passing `openrouter` selects OpenRouter and `vercel` selects Vercel Gateway; a `403 customer_verification_required` response confirms only the account check, not successful Jev inference.

## Project links

- [Contributing](CONTRIBUTING.md)
- [Provider protocols](docs/protocols.md)
- [Validation notes](docs/validation.md)
- [MIT License](LICENSE)
- [Maven Central publishing guide](docs/central-publishing.md) for maintainers
