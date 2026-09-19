# Validation

Recorded on 2026-09-20. Test judgments are examples, not an accuracy benchmark.

## Offline

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
