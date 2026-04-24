#!/bin/bash
# Delete the installed native macOS desktop app bundle and local download artifacts.

set -euo pipefail

APP_NAME="Cotor Desktop"
BUNDLE_NAME="$APP_NAME.app"
DOWNLOADS_DIR="$HOME/Downloads"
REMOVED=0

remove_path() {
    local target="$1"
    if [[ -e "$target" ]]; then
        rm -rf "$target"
        echo "🗑️  Removed $target"
        REMOVED=1
    fi
}

remove_path "/Applications/$BUNDLE_NAME"
remove_path "$HOME/Applications/$BUNDLE_NAME"
remove_path "$DOWNLOADS_DIR/$BUNDLE_NAME"
remove_path "$DOWNLOADS_DIR/Cotor-Desktop-macOS.dmg"

if [[ "$REMOVED" -eq 0 ]]; then
    echo "ℹ️  No installed Cotor Desktop app or download artifacts were found."
else
    echo "✅ Cotor Desktop artifacts were removed."
fi
