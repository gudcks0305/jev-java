# Netlify AI Gateway

Netlify AI Gateway serves Jev through TypeSafe's API. Use the existing
`jev-typesafe` module or `jev-spring-boot-starter` with `jev.provider=typesafe`;
there is no separate Netlify client or provider setting. Netlify documents
`TYPESAFE_API_KEY` and `TYPESAFE_BASE_URL` in its supported compute contexts.

## Configure a Java process

Start with a [Netlify project that can use AI Gateway](https://docs.netlify.com/build/ai-gateway/overview/).
Netlify documents availability on credit-based plans, with AI Features enabled
and at least one production deploy. Check that neither TypeSafe environment
variable has been set by your team or project if you expect Netlify to inject
its Gateway values.

The Java client reads `TYPESAFE_API_KEY` automatically. Unlike the TypeSafe
JavaScript SDK, it does **not** read `TYPESAFE_BASE_URL` automatically. Pass the
injected base URL explicitly:

```java
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import java.net.URI;

String baseUrl = System.getenv("TYPESAFE_BASE_URL");
if (baseUrl == null || baseUrl.isBlank()) {
    throw new IllegalStateException("TYPESAFE_BASE_URL is required for Netlify AI Gateway");
}
try (var client = TypeSafeJevClient.builder().baseUrl(URI.create(baseUrl)).build()) {
    // Use client.evaluate(...) as in the README quickstart.
}
```

Add `io.github.gudcks0305:jev-typesafe:0.2.0` for plain Java. For Spring Boot,
add `io.github.gudcks0305:jev-spring-boot-starter:0.2.0` and configure:

```yaml
jev:
  provider: typesafe
  base-url: ${TYPESAFE_BASE_URL}
```

The starter also reads `TYPESAFE_API_KEY` when `jev.api-key` is unset. The
client appends `/v1/systemone` to `baseUrl`. If you have a **complete request
URL** instead of a base URL, use `.endpoint(URI)` or `jev.endpoint` in place of
`baseUrl` / `jev.base-url`; do not set both.

## Runtime and verification limits

Netlify documents automatic Gateway variables in Functions, Edge Functions,
Preview Server, and other supported compute contexts. Its Functions and Edge
Functions documentation does not describe a Java runtime; Java listed in
Netlify's build software is a build tool, not a documented Java Functions
runtime. This configuration applies when a Java process actually receives
both TypeSafe variables. An external Java service does not receive them
automatically, and Netlify does not document exporting its generated Gateway
credentials for an external Java service. For code running in Netlify Functions
or Edge Functions, follow Netlify's TypeSafe JavaScript SDK example instead.

Netlify's TypeSafe example uses `systemOne` with its injected base URL. The
official TypeSafe JavaScript SDK appends `/v1/systemone` to that URL, matching
this Java client's path handling. Netlify does not publish the injected URL
value or a Java example. Our TypeSafe request format and `baseUrl` path joining
have local tests; a live Netlify Gateway call with this Java SDK has **not**
been verified. Confirm the injected URL and a real request in your Netlify
environment before relying on this path in production.

Sources: [Netlify AI Gateway overview](https://docs.netlify.com/build/ai-gateway/overview/),
[Functions overview](https://docs.netlify.com/build/functions/overview/),
[Edge Functions overview](https://docs.netlify.com/build/edge-functions/overview/),
[build software](https://docs.netlify.com/build/configure-builds/available-software-at-build-time/),
[TypeSafe JavaScript SDK request path](https://github.com/typesafe-ai/typesafe-sdk-js/blob/66880ccded6cb642dc1809620c2b108c33730214/src/client.ts).
Netlify documentation checked on 2026-09-26.
