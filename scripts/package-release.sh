#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version="${1:?Usage: scripts/package-release.sh VERSION (after ./mvnw verify -Prelease)}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Expected a stable x.y.z version' >&2; exit 1; }
out="dist/release/$version"
mkdir -p "$out"
modules=(jev-core jev-typesafe jev-vercel jev-openrouter jev-spring-webflux jev-spring-boot-autoconfigure jev-spring-boot-starter)
for module in "${modules[@]}"; do
  cp "$module/target/$module-$version.jar" "$out/"
  # The dependency-only starter has no Java source or Javadoc.
  if [[ "$module" != jev-spring-boot-starter ]]; then
    cp "$module/target/$module-$version-sources.jar" "$out/"
    cp "$module/target/$module-$version-javadoc.jar" "$out/"
  fi
  cp "$module/pom.xml" "$out/$module-$version.pom"
done
cp pom.xml "$out/jev-java-$version.pom"
cp LICENSE "$out/LICENSE"
(
  cd "$out"
  shasum -a 256 -- *.jar *.pom LICENSE > SHA256SUMS
)
echo "Release assets: $out"
