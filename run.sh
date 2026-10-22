#!/usr/bin/env bash
# Builds (first time only) and launches P2P Chat. Extra args are passed through,
# e.g.  ./run.sh --peer 203.0.113.7 --key secret     or   ./run.sh --cli --peer ...
set -euo pipefail
cd "$(dirname "$0")"

# Maven may default to an older JDK; prefer a Java 17+ install if one exists.
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE 'version "(1[7-9]|[2-9][0-9])'; then
  for d in /usr/lib/jvm/java-2[0-9]-openjdk* /usr/lib/jvm/java-17-openjdk*; do
    [ -x "$d/bin/java" ] && export JAVA_HOME="$d" && break
  done
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

if [ ! -f target/p2p-chat.jar ] || [ -n "$(find src pom.xml -newer target/p2p-chat.jar -print -quit)" ]; then
  mvn -q -DskipTests package
fi

# Build the fast screen capture helper for the remote desktop host: ScreenCaptureKit on macOS,
# MIT-SHM on Linux X11. Optional: without it (or without a compiler) the host uses Java capture.
case "$(uname -s)" in
  Darwin) HELPER_SRC=native/macos/ScreenStream.swift; BUILD=(swiftc -O -o target/screenstream "$HELPER_SRC") ;;
  Linux)  HELPER_SRC=native/linux/screenstream.c;    BUILD=(cc -O2 -o target/screenstream "$HELPER_SRC" -lX11 -lXext) ;;
  *)      HELPER_SRC= ;;
esac
if [ -n "$HELPER_SRC" ] && [ -f "$HELPER_SRC" ] && command -v "${BUILD[0]}" >/dev/null 2>&1; then
  if [ ! -x target/screenstream ] || [ "$HELPER_SRC" -nt target/screenstream ]; then
    "${BUILD[@]}" \
      || echo "warning: could not build the capture helper; remote desktop will use Java capture" >&2
  fi
fi
exec "$JAVA" -jar target/p2p-chat.jar "$@"
