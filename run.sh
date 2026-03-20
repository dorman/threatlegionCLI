#!/usr/bin/env bash
# Build and run ThreatLegion with a real terminal (not via gradle run,
# which wraps I/O and prevents JLine from getting a PTY).
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/build/libs/cline-cli-1.0.0.jar"

# Build the fat jar if it doesn't exist or sources are newer
echo "Building..."
"$SCRIPT_DIR/gradlew" jar -q

# Run with direct terminal access
exec java --enable-native-access=ALL-UNNAMED -jar "$JAR" "$@"
