#!/usr/bin/env bash
# Builds the version set in build.gradle.kts into dist/BetterCodeReview.zip and puts it in the README.
# Commit and push the result yourself; add the CHANGELOG entry first.
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)
[ -n "$version" ] || { echo "no version in build.gradle.kts" >&2; exit 1; }
grep -q "^## $version " CHANGELOG.md || { echo "CHANGELOG.md has no entry for $version" >&2; exit 1; }

./gradlew buildPlugin --console=plain -q
mkdir -p dist
cp "build/distributions/BetterCodeReview-$version.zip" dist/BetterCodeReview.zip
sed -i.bak "s|<!-- version -->.*<!-- /version -->|<!-- version -->$version<!-- /version -->|" README.md && rm README.md.bak
echo "dist/BetterCodeReview.zip and README.md are at $version"
