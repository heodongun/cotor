#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

echo "🔧 Running Spotless auto-fix (ktlint + Gradle Kotlin formatting)..."
if [[ -x "$PROJECT_ROOT/gradlew" && -f "$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.jar" ]]; then
  "$PROJECT_ROOT/gradlew" --no-daemon format
else
  gradle --no-daemon format
fi

echo "✅ Lint/format auto-fix completed."
