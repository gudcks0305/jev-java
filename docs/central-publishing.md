# Maven Central publishing

The separate **Maven Central** Actions workflow is manual. The existing GitHub
Release workflow does not upload to Central. Central deployment uses the
`central` server ID, not the literal `${server}` placeholder from a token snippet.

## Secrets

Configure these repository Actions secrets:

| Name | Value |
| --- | --- |
| `CENTRAL_TOKEN_USERNAME` | Username generated with the Central Portal user token |
| `CENTRAL_TOKEN_PASSWORD` | Password generated with that token |
| `GPG_PRIVATE_KEY` | ASCII-armored export of a passphrase-protected signing private key |
| `GPG_PASSPHRASE` | Passphrase for that key |

The Portal account must have verified access to `io.github.gudcks0305`. Publish
the corresponding GPG public key as described in the [Sonatype signing guide](https://central.sonatype.org/publish/requirements/gpg/).
The upload workflow checks that the public key is retrievable by fingerprint
from `keys.openpgp.org` before deployment. Its public bundle is retained as an
Actions artifact for seven days so validation failures can be investigated.
Do not put any credential values in the workflow, POM, repository, or logs.

`actions/setup-java` writes Maven settings that reference the token environment
variables. Maven GPG reads its passphrase from `MAVEN_GPG_PASSPHRASE`. The upload
operation checks that all four secrets exist before signing or contacting Central.

## Operations

1. Open Actions → Maven Central → Run workflow.
2. Choose **verify** to test/build the Central packaging without credentials,
   signatures, or any upload. This is the default.
3. After the namespace and signing key are ready, choose **upload**. It signs and
   uploads the parent POM and all SDK modules, then waits for Portal validation.
4. Inspect the validated deployment in [Central Portal](https://central.sonatype.com/publishing)
   and publish it there when ready. `autoPublish` is deliberately `false`.

Upload does not automatically publish. Once published, a version cannot be
replaced or removed. Use a new version for future changes. The existing `v0.1.0`
GitHub tag predates this workflow; run from a revision containing the Central
profile rather than that old tag.

## Local verification

```sh
./mvnw clean verify -Prelease,central -pl '!examples' -Dgpg.skip=true
```

`examples` is excluded from Central operations. The dependency-only starter
adds explanatory placeholder sources/Javadoc JARs in the Central profile, as
required by [Central's artifact requirements](https://central.sonatype.org/publish/requirements/).
The publishing plugin generates the repository-layout bundle and checksums.
The normal GitHub-release packaging remains separate.

References: [Central Maven plugin](https://central.sonatype.org/publish/publish-portal-maven/),
[token generation](https://central.sonatype.org/publish/generate-portal-token/).
