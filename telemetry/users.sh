#!/usr/bin/env bash
# Lists the installs whose users typed in a nick: product, nick, version and the last day they pinged.
# The key lives in .stats-key next to this script (not committed); `wrangler secret put STATS_KEY` holds the same value.
set -euo pipefail
cd "$(dirname "$0")"
curl -fsS -H "Authorization: Bearer $(cat .stats-key)" https://adrros-telemetry.adrros.workers.dev/users
echo
