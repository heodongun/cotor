#!/bin/bash
# Build release artifacts for GitHub Release
# Outputs:
# - build/release/cotor-<version>-all.jar
# - build/release/Cotor-<version>.dmg (macOS)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

VERSION="${1:-1.0.0}"
RELEASE_DIR="$PROJECT_ROOT/build/release"

echo "🚀 Building Cotor v$VERSION for release..."

# Clean and create release directory
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

# Set Java 17
if [ -d "/Users/heodongun/Library/Java/JavaVirtualMachines/corretto-17.0.13/Contents/Home" ]; then
  export JAVA_HOME="/Users/heodongun/Library/Java/JavaVirtualMachines/corretto-17.0.13/Contents/Home"
elif command -v /usr/libexec/java_home &> /dev/null; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi

echo "📦 Building CLI JAR..."
./gradlew clean shadowJar --no-daemon

# Ensure release directory exists
mkdir -p "$RELEASE_DIR"

# Copy JAR to release directory
cp "build/libs/cotor-${VERSION}-all.jar" "$RELEASE_DIR/"
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
  
  echo "📀 Creating DMG..."
  
  # Calculate size needed (app size + 50MB buffer)
  APP_SIZE=$(du -sm "$DESKTOP_OUTPUT/Cotor Desktop.app" | cut -f1)
  DMG_SIZE=$((APP_SIZE + 50))
  
  # Create temp DMG
  hdiutil create -size "${DMG_SIZE}m" -fs HFS+ -volname "Cotor" "$TEMP_DMG"
  
  # Mount it
  MOUNT_DIR=$(hdiutil attach "$TEMP_DMG" | grep Volumes | awk '{print $3}')
  
  # Copy app
  cp -R "$DESKTOP_OUTPUT/Cotor Desktop.app" "$MOUNT_DIR/"
  
  # Create Applications symlink
  ln -s /Applications "$MOUNT_DIR/Applications"
  
  # Unmount
  hdiutil detach "$MOUNT_DIR"
  
  # Convert to compressed DMG
  hdiutil convert "$TEMP_DMG" -format UDZO -o "$DMG_PATH"
  rm "$TEMP_DMG"
  
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
echo "2. Upload these artifacts to the release"
echo "3. Update Homebrew Formula with new URLs and SHA256"
