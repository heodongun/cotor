#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

resolve_gradle_cmd() {
  if [ -x "./gradlew" ] && [ -f "./gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "./gradlew"
  elif command -v gradle >/dev/null 2>&1; then
    echo "gradle"
  else
    echo ""
  fi
}

GRADLE_CMD="$(resolve_gradle_cmd)"

if [ -z "$GRADLE_CMD" ]; then
  echo "❌ Gradle executable not found."
  echo "- Ensure gradle is installed, or"
  echo "- Add gradle wrapper jar: gradle/wrapper/gradle-wrapper.jar"
  exit 1
fi

echo "🔧 Running Spotless auto-fix with: $GRADLE_CMD spotlessApply"
"$GRADLE_CMD" spotlessApply

echo "✅ Spotless formatting applied."
