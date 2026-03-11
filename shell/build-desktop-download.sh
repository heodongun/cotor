#!/bin/bash
# Backward-compatible wrapper for the install-first desktop bundle flow.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/install-desktop-app.sh" "$@"
