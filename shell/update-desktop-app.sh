#!/bin/bash
# Rebuild and reinstall the native macOS desktop app bundle.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "🔄 Updating Cotor Desktop..."
exec "$SCRIPT_DIR/install-desktop-app.sh" "$@"
