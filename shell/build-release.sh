#!/bin/bash
# Build release artifacts for GitHub Release
# Outputs:
# - build/release/cotor-<version>-all.jar
# - build/release/Cotor-<version>.dmg (macOS)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

source "$SCRIPT_DIR/resolve-gradle-java.sh"

VERSION="${1:-1.0.0}"
RELEASE_DIR="$PROJECT_ROOT/build/release"

echo "🚀 Building Cotor v$VERSION for release..."

# Clean and create release directory
if [[ -z "$RELEASE_DIR" || "$RELEASE_DIR" == "/" ]]; then
  echo "❌ Refusing to remove invalid release directory: '$RELEASE_DIR'"
  exit 1
fi
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

# Set a Gradle-compatible JDK for the current machine.
configure_gradle_java

echo "📦 Building CLI JAR..."
./gradlew clean shadowJar --no-daemon

# Ensure release directory exists
mkdir -p "$RELEASE_DIR"

# Copy JAR to release directory
cp "build/libs/cotor-${VERSION}-all.jar" "$RELEASE_DIR/"
shasum -a 256 "$RELEASE_DIR/cotor-${VERSION}-all.jar" > "$RELEASE_DIR/cotor-${VERSION}-all.jar.sha256"
echo "✅ JAR built: $RELEASE_DIR/cotor-${VERSION}-all.jar"

# Build macOS DMG if on macOS
if [[ "$OSTYPE" == "darwin"* ]]; then
  echo "🍎 Building macOS Desktop App DMG..."
  
  # Build the app bundle
  DESKTOP_OUTPUT="$PROJECT_ROOT/build/desktop-release"
  rm -rf "$DESKTOP_OUTPUT"
  mkdir -p "$DESKTOP_OUTPUT"
  
  export COTOR_PROJECT_ROOT="$PROJECT_ROOT"
  export COTOR_DESKTOP_BUILD_OUTPUT_ROOT="$DESKTOP_OUTPUT"
  
  bash "$SCRIPT_DIR/build-desktop-app-bundle.sh"
  
  if [ ! -d "$DESKTOP_OUTPUT/Cotor Desktop.app" ]; then
    echo "❌ Failed to build Desktop app"
    exit 1
  fi
  
  # Create DMG
  DMG_NAME="Cotor-${VERSION}.dmg"
  DMG_PATH="$RELEASE_DIR/$DMG_NAME"
  TEMP_DMG="$RELEASE_DIR/temp.dmg"
  MOUNT_DIR="$RELEASE_DIR/mount"
  
  echo "📀 Creating DMG..."
  
  # Calculate size needed (app size + 50MB buffer)
  APP_SIZE=$(du -sm "$DESKTOP_OUTPUT/Cotor Desktop.app" | cut -f1)
  DMG_SIZE=$((APP_SIZE + 50))
  
  # Create temp DMG
  hdiutil create -size "${DMG_SIZE}m" -fs HFS+ -volname "Cotor" "$TEMP_DMG"
  
  # Mount it
  rm -rf "$MOUNT_DIR"
  mkdir -p "$MOUNT_DIR"
  hdiutil attach "$TEMP_DMG" -mountpoint "$MOUNT_DIR" -nobrowse >/dev/null
  
  # Copy app
  cp -R "$DESKTOP_OUTPUT/Cotor Desktop.app" "$MOUNT_DIR/"
  
  # Create Applications symlink
  ln -s /Applications "$MOUNT_DIR/Applications"
  
  # Unmount
  hdiutil detach "$MOUNT_DIR"
  rm -rf "$MOUNT_DIR"
  
  # Convert to compressed DMG
  hdiutil convert "$TEMP_DMG" -format UDZO -o "$DMG_PATH"
  rm "$TEMP_DMG"
  shasum -a 256 "$DMG_PATH" > "$DMG_PATH.sha256"
  
  echo "✅ DMG built: $DMG_PATH"
fi

echo ""
echo "🎉 Release build complete!"
echo ""
echo "📦 Artifacts in $RELEASE_DIR:"
ls -lh "$RELEASE_DIR"
echo ""
echo "Next steps:"
echo "1. Create GitHub Release v$VERSION"
echo "2. Upload the artifacts and SHA256 files to the release"
echo "3. Update Homebrew Formula with new URLs and SHA256"
