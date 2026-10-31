#!/usr/bin/env bash
# Builds the plugin and installs it into the local IntelliJ IDEA in one go: quits the IDE the way Cmd+Q does,
# replaces the BetterJ folder in its plugins directory and starts it again with the projects it had open.
# Override IDEA_APP or IDEA_CONFIG for another IDE or version; pass --no-build to install the zip built last.
set -euo pipefail
cd "$(dirname "$0")/.."

IDEA_APP="${IDEA_APP:-$HOME/Applications/IntelliJ IDEA.app}"
IDEA_CONFIG="${IDEA_CONFIG:-$HOME/Library/Application Support/JetBrains/IntelliJIdea2026.1}"
PLUGINS="$IDEA_CONFIG/plugins"

[ -d "$IDEA_APP" ] || { echo "no IDE at $IDEA_APP (set IDEA_APP)" >&2; exit 1; }
[ -d "$PLUGINS" ] || { echo "no plugins directory at $PLUGINS (set IDEA_CONFIG)" >&2; exit 1; }

version=$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)
zip="build/distributions/BetterJ-$version.zip"
if [ "${1:-}" != "--no-build" ]; then
  ./gradlew buildPlugin --console=plain -q
fi
[ -f "$zip" ] || { echo "no $zip, build it first" >&2; exit 1; }

running() { pgrep -f "$IDEA_APP/Contents/MacOS/" >/dev/null; }
if running; then
  echo "Quitting IntelliJ IDEA... if it asks whether to exit, confirm it there."
  # The IDE answers the quit request with "cancelled" while it shows its own exit confirmation, so only its exit counts.
  osascript -e "tell application \"$IDEA_APP\" to quit" >/dev/null 2>&1 || true
  for _ in $(seq 1 120); do running || break; sleep 1; done
  running && { echo "IntelliJ IDEA is still running after 2 minutes; nothing was replaced" >&2; exit 1; }
fi

staging=$(mktemp -d)
unzip -q "$zip" -d "$staging"
rm -rf "$PLUGINS/BetterJ"
mv "$staging/BetterJ" "$PLUGINS/BetterJ"
rm -rf "$staging"
echo "Installed BetterJ $version into $PLUGINS"

open "$IDEA_APP"
