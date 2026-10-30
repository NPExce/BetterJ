#!/usr/bin/env bash
# Builds the version set in build.gradle.kts into dist/BetterJ-<version>.zip, drops the previous zip and points the
# README and INSTALL links at the new one. Commit and push the result yourself; add the CHANGELOG entry first.
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)
[ -n "$version" ] || { echo "no version in build.gradle.kts" >&2; exit 1; }
grep -q "^## $version " CHANGELOG.md || { echo "CHANGELOG.md has no entry for $version" >&2; exit 1; }

./gradlew buildPlugin --console=plain -q
mkdir -p dist
rm -f dist/BetterJ*.zip
cp "build/distributions/BetterJ-$version.zip" "dist/BetterJ-$version.zip"
for file in README.md INSTALL.md; do
  sed -E -i.bak "s/BetterJ(-[0-9][0-9.]*)?\.zip/BetterJ-$version.zip/g" "$file" && rm "$file.bak"
done
sed -i.bak "s|<!-- version -->.*<!-- /version -->|<!-- version -->$version<!-- /version -->|" README.md && rm README.md.bak
echo "dist/BetterJ-$version.zip, README.md and INSTALL.md are at $version"
