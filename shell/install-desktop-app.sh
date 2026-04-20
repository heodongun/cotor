#!/bin/bash
# Build, package, download, and install the native macOS desktop app bundle.

set -euo pipefail

export PATH="/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:/usr/local/bin${PATH:+:$PATH}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${COTOR_PROJECT_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
APP_NAME="Cotor Desktop"
BUNDLE_NAME="$APP_NAME.app"
DOWNLOADS_DIR="$HOME/Downloads"
DOWNLOAD_APP="$DOWNLOADS_DIR/$BUNDLE_NAME"
DMG_PATH="$DOWNLOADS_DIR/Cotor-Desktop-macOS.dmg"
TMPDIR_VALUE="${TMPDIR:-/tmp}"
STAGING_ROOT="${COTOR_DESKTOP_BUILD_OUTPUT_ROOT:-${TMPDIR_VALUE%/}/cotor-desktop-build}"
STAGING_APP="$STAGING_ROOT/$BUNDLE_NAME"
MOUNT_DIR="$STAGING_ROOT/dmg-mount"

mkdir -p "$DOWNLOADS_DIR"

bash "$SCRIPT_DIR/build-desktop-app-bundle.sh"

rm -rf "$DOWNLOAD_APP"
ditto "$STAGING_APP" "$DOWNLOAD_APP"

rm -f "$DMG_PATH"
rm -rf "$MOUNT_DIR"
mkdir -p "$MOUNT_DIR"
APP_SIZE=$(du -sm "$STAGING_APP" | cut -f1)
DMG_SIZE=$((APP_SIZE + 50))
hdiutil create -size "${DMG_SIZE}m" -fs HFS+ -volname "Cotor Desktop" "$DMG_PATH"
hdiutil attach "$DMG_PATH" -mountpoint "$MOUNT_DIR" -nobrowse >/dev/null
cp -R "$STAGING_APP" "$MOUNT_DIR/"
hdiutil detach "$MOUNT_DIR" >/dev/null
rm -rf "$MOUNT_DIR"

INSTALL_ROOT="${COTOR_DESKTOP_INSTALL_ROOT:-/Applications}"
if [[ -z "${COTOR_DESKTOP_INSTALL_ROOT:-}" && ! -w "$INSTALL_ROOT" ]]; then
    INSTALL_ROOT="$HOME/Applications"
fi
mkdir -p "$INSTALL_ROOT"

INSTALL_APP="$INSTALL_ROOT/$BUNDLE_NAME"
rm -rf "$INSTALL_APP"
ditto "$STAGING_APP" "$INSTALL_APP"

echo "✅ Cotor Desktop is ready."
echo "   Download:  $DOWNLOAD_APP"
echo "   Disk image:$DMG_PATH"
echo "   Installed: $INSTALL_APP"
