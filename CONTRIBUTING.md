# Contributing

Thanks for helping improve Jev Java. Bug reports, focused fixes, documentation,
and compatibility improvements are welcome.

## Before opening an issue

- Search existing issues and discussions first.
- Remove API keys, authorization headers, and real customer state from examples.
- Include the SDK version, Java version, module, provider, transport, and a small
  reproducible example when reporting a bug.
- Use GitHub Security Advisories for vulnerabilities instead of a public issue.

## Local development

Jev Java requires Java 17 or newer. Clone the repository and run:

```sh
./mvnw verify
```

The regular test suite uses local fakes and does not require provider
credentials. Live examples are optional and may make billable requests. Run
them only when you intend to, using `TYPESAFE_API_KEY` or
`AI_GATEWAY_API_KEY` from your local environment.

Keep changes focused. Add tests for behavior changes, preserve Java 17
compatibility, and update documentation when public behavior changes. Provider
wire formats and transport behavior need sanitized fixtures; never commit live
credentials or real request state.

## Pull requests

Explain the problem and resulting behavior, list validation commands, and call
out compatibility effects for providers, transports, Java, or Spring Boot.
Keep unrelated formatting and refactoring out of the change. By contributing,
you agree that your contribution is licensed under the repository's MIT
license.

## Releases

Update all Maven versions and the changelog, add `docs/releases/VERSION.md`,
and run `./mvnw clean verify -Prelease`. Test packaging with
`./scripts/package-release.sh VERSION`. After main's CI checks pass, pushing
an annotated `vVERSION` tag triggers the release workflow. It verifies the
tag matches the POM, rebuilds/tests, and publishes JARs, sources, Javadoc,
POMs and SHA-256 checksums to GitHub Releases. It does not publish to Maven
Central. Never move or reuse a published release tag.
