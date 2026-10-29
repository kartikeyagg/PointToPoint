#!/usr/bin/env bash
# One-click conference: opens the launcher window (host a meeting / join with an invite code).
# Direct start:  ./conference.sh host "Team sync" --name Alice   or   ./conference.sh join <invite> --name Bob
# Add --cli for the terminal version.
set -euo pipefail
exec "$(dirname "$0")/run.sh" --conference "$@"
