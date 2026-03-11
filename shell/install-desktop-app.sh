#!/bin/bash
# Build, package, download, and install the native macOS desktop app bundle.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PACKAGE_ROOT="$PROJECT_ROOT/macos"
PACKAGING_ROOT="$PACKAGE_ROOT/Packaging"
TOOLS_ROOT="$PACKAGE_ROOT/Tools"
BRANDING_ROOT="$PACKAGE_ROOT/Branding"
DOWNLOADS_DIR="$HOME/Downloads"
APP_NAME="Cotor Desktop"
BUNDLE_NAME="$APP_NAME.app"
DOWNLOAD_APP="$DOWNLOADS_DIR/$BUNDLE_NAME"
ZIP_PATH="$DOWNLOADS_DIR/Cotor-Desktop-macOS.zip"
STAGING_ROOT="${TMPDIR%/}/cotor-desktop-build"
STAGING_APP="$STAGING_ROOT/$BUNDLE_NAME"
VERSION="$(grep -E '^version = ' "$PROJECT_ROOT/build.gradle.kts" | head -n 1 | cut -d '"' -f 2)"
BUILD_NUMBER="$(date +%Y%m%d%H%M%S)"

if [[ -z "$VERSION" ]]; then
    VERSION="dev"
fi

mkdir -p "$DOWNLOADS_DIR"
rm -rf "$STAGING_ROOT"
mkdir -p "$STAGING_ROOT"

echo "📦 Building bundled Cotor backend..."
(cd "$PROJECT_ROOT" && ./gradlew shadowJar --no-daemon)

echo "🖥️  Building native macOS shell..."
swift build --package-path "$PACKAGE_ROOT" -c release

APP_BINARY="$(find "$PACKAGE_ROOT/.build" -path '*/release/CotorDesktopApp' -type f | head -n 1)"
BACKEND_JAR="$(find "$PROJECT_ROOT/build/libs" -name 'cotor-*-all.jar' -type f | head -n 1)"
RESOURCE_BUNDLES="$(find "$PACKAGE_ROOT/.build" -path '*/release/*.bundle' -type d)"

if [[ -z "$APP_BINARY" || ! -f "$APP_BINARY" ]]; then
    echo "❌ Could not locate the built CotorDesktopApp binary."
    exit 1
fi

if [[ -z "$BACKEND_JAR" || ! -f "$BACKEND_JAR" ]]; then
    echo "❌ Could not locate the bundled backend jar."
    exit 1
fi

APP_CONTENTS="$STAGING_APP/Contents"
APP_MACOS="$APP_CONTENTS/MacOS"
APP_RESOURCES="$APP_CONTENTS/Resources"
APP_BACKEND="$APP_RESOURCES/backend"
ICONSET_DIR="$STAGING_ROOT/AppIcon.iconset"
ICON_PNG="$STAGING_ROOT/AppIcon-1024.png"
ICON_ICNS="$APP_RESOURCES/AppIcon.icns"
ICON_SVG="$BRANDING_ROOT/cotor.svg"
SVG_RENDER_PNG="$STAGING_ROOT/cotor.svg.png"

# Build the bundle in a temporary staging area first so interrupted runs never
# leave behind a half-written app in Downloads or Applications.
mkdir -p "$APP_MACOS" "$APP_BACKEND" "$APP_RESOURCES" "$ICONSET_DIR"

echo "🎨 Generating app icon..."
if [[ -f "$ICON_SVG" ]]; then
    # The SVG under `macos/Branding` is the checked-in source of truth for the
    # desktop app mark. We rasterize it here so the Swift compositor can place
    # the official brand symbol on a Dock-friendly background without depending
    # on the user's Desktop or the original checkout path.
    qlmanage -t -s 1024 -o "$STAGING_ROOT" "$ICON_SVG" >/dev/null 2>&1

    if [[ ! -f "$SVG_RENDER_PNG" ]]; then
        echo "❌ Failed to rasterize $ICON_SVG via Quick Look."
        exit 1
    fi

    swift "$TOOLS_ROOT/generate-desktop-icon.swift" "$SVG_RENDER_PNG" "$ICON_PNG"
else
    # Keep the synthetic fallback so local builds still succeed if the branding
    # asset is removed from a future branch or partial checkout.
    swift "$TOOLS_ROOT/generate-desktop-icon.swift" "$ICON_PNG"
fi

resize_icon() {
    local pixels="$1"
    local name="$2"
    sips -z "$pixels" "$pixels" "$ICON_PNG" --out "$ICONSET_DIR/$name" >/dev/null
}

resize_icon 16 icon_16x16.png
resize_icon 32 icon_16x16@2x.png
resize_icon 32 icon_32x32.png
resize_icon 64 icon_32x32@2x.png
resize_icon 128 icon_128x128.png
resize_icon 256 icon_128x128@2x.png
resize_icon 256 icon_256x256.png
resize_icon 512 icon_256x256@2x.png
resize_icon 512 icon_512x512.png
resize_icon 1024 icon_512x512@2x.png

iconutil -c icns "$ICONSET_DIR" -o "$ICON_ICNS"

cp "$APP_BINARY" "$APP_MACOS/CotorDesktopBinary"
chmod +x "$APP_MACOS/CotorDesktopBinary"
# The installed `.app` ships with the backend jar inside the bundle, which keeps
# the launcher independent from the original repository checkout.
cp "$BACKEND_JAR" "$APP_BACKEND/cotor-backend.jar"

# SwiftPM emits resource bundles beside the executable. Copy them into the app
# bundle so local HTML/CSS/JS assets like the embedded terminal are available
# after installation, not only while running from `.build/`.
if [[ -n "$RESOURCE_BUNDLES" ]]; then
    while IFS= read -r bundle_path; do
        [[ -z "$bundle_path" ]] && continue
        cp -R "$bundle_path" "$APP_RESOURCES/"
    done <<< "$RESOURCE_BUNDLES"
fi

sed \
    -e "s#__EXECUTABLE__#CotorDesktopLauncher#g" \
    -e "s#__VERSION__#$VERSION#g" \
    -e "s#__BUILD__#$BUILD_NUMBER#g" \
    "$PACKAGING_ROOT/Info.plist.template" > "$APP_CONTENTS/Info.plist"

cp "$PACKAGING_ROOT/CotorDesktopLauncher.sh.template" "$APP_MACOS/CotorDesktopLauncher"
chmod +x "$APP_MACOS/CotorDesktopLauncher"

# Ad-hoc signing is enough for local installs and helps Finder treat the bundle
# more consistently than an unsigned folder of executables.
codesign --force --deep --sign - "$STAGING_APP" >/dev/null 2>&1 || true

rm -rf "$DOWNLOAD_APP"
ditto "$STAGING_APP" "$DOWNLOAD_APP"

rm -f "$ZIP_PATH"
# `--norsrc` keeps Finder metadata out of the user-facing zip archive.
ditto -c -k --norsrc --keepParent "$DOWNLOAD_APP" "$ZIP_PATH"

INSTALL_ROOT="/Applications"
if [[ ! -w "$INSTALL_ROOT" ]]; then
    INSTALL_ROOT="$HOME/Applications"
    mkdir -p "$INSTALL_ROOT"
fi

INSTALL_APP="$INSTALL_ROOT/$BUNDLE_NAME"
rm -rf "$INSTALL_APP"
ditto "$STAGING_APP" "$INSTALL_APP"

echo "✅ Cotor Desktop is ready."
echo "   Download: $DOWNLOAD_APP"
echo "   Zip:      $ZIP_PATH"
echo "   Installed: $INSTALL_APP"
