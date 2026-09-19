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
