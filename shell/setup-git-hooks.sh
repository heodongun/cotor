#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

git config core.hooksPath .githooks
chmod +x .githooks/pre-commit shell/auto-fix-lint.sh

echo "✅ Git hooks configured."
echo "- hooksPath: $(git config --get core.hooksPath)"
echo "- pre-commit will run shell/auto-fix-lint.sh and re-stage formatted Kotlin files."
