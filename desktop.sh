#!/usr/bin/env bash
# One-click remote desktop: opens the launcher window (share this screen / control the other one).
# Direct start:  ./desktop.sh host --peer 203.0.113.7 --key "secret"   or   ./desktop.sh view --peer ...
set -euo pipefail
exec "$(dirname "$0")/run.sh" --desktop "$@"
