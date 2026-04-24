#!/bin/bash
# Cotor installation script

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/resolve-gradle-java.sh"

echo "🚀 Installing Cotor..."
echo ""

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo "❌ Error: Java is not installed"
    echo "Please install JDK 17 or higher from:"
    echo "  - https://adoptium.net/"
    echo "  - https://www.oracle.com/java/technologies/downloads/"
    exit 1
fi

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "❌ Error: Java 17 or higher is required (found Java $JAVA_VERSION)"
    exit 1
fi

echo "✅ Java $JAVA_VERSION detected"
echo ""

# Build the project
echo "📦 Building Cotor..."
if [ -f "$PROJECT_ROOT/gradlew" ]; then
    configure_gradle_java
    (cd "$PROJECT_ROOT" && ./gradlew shadowJar --no-daemon)
else
    echo "❌ Error: gradlew not found. Are you in the Cotor directory?"
    exit 1
fi

echo ""
echo "✅ Build successful!"
echo ""

# Make cotor script executable
COTOR_PATH="$PROJECT_ROOT/shell/cotor"
chmod +x "$COTOR_PATH"

# Install a direct launcher into a common user bin directory when possible.
USER_BIN_DIR="$HOME/.local/bin"
mkdir -p "$USER_BIN_DIR"
if [ -e "$USER_BIN_DIR/cotor" ] && [ "$(readlink "$USER_BIN_DIR/cotor" 2>/dev/null || true)" != "$COTOR_PATH" ]; then
    echo "⚠️  Replacing existing launcher at $USER_BIN_DIR/cotor"
fi
ln -sf "$COTOR_PATH" "$USER_BIN_DIR/cotor"

# Determine shell config file
if [ -n "$ZSH_VERSION" ]; then
    SHELL_CONFIG="$HOME/.zshrc"
elif [ -n "$BASH_VERSION" ]; then
    SHELL_CONFIG="$HOME/.bashrc"
else
    SHELL_CONFIG="$HOME/.profile"
fi

PATH_EXPORT="export PATH=\"$USER_BIN_DIR:\$PATH\""
if ! printf '%s' ":${PATH}:" | grep -q ":$USER_BIN_DIR:"; then
    touch "$SHELL_CONFIG"
    if ! grep -Fq "$PATH_EXPORT" "$SHELL_CONFIG"; then
        printf '\n%s\n' "$PATH_EXPORT" >> "$SHELL_CONFIG"
    fi
fi

echo "📝 Installation complete!"
echo ""
echo "A direct launcher was installed at: $USER_BIN_DIR/cotor"
echo ""
echo "$USER_BIN_DIR was added to $SHELL_CONFIG when it was missing from PATH."
echo ""
echo "  cotor version"
echo ""
echo "Then run: source $SHELL_CONFIG"
echo ""
echo "🎉 You can now use Cotor!"
echo ""
echo "Try these commands:"
echo "  ./shell/cotor version"
echo "  ./shell/cotor init"
echo "  ./shell/cotor list --config test-ai-models.yaml"
echo "  ./shell/cotor run test-all-models --config test-ai-models.yaml"
