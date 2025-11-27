#!/bin/bash
# Cotor global installation script

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "🚀 Installing Cotor globally..."
echo ""

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo "❌ Error: Java is not installed"
    echo "Please install JDK 17 or higher"
    exit 1
fi

# Build the project
echo "📦 Building Cotor..."
(cd "$PROJECT_ROOT" && ./gradlew shadowJar --no-daemon)

# Make cotor script executable
COTOR_SCRIPT="$PROJECT_ROOT/shell/cotor"
chmod +x "$COTOR_SCRIPT"

# Determine installation directory
if [ -w "/usr/local/bin" ]; then
    INSTALL_DIR="/usr/local/bin"
elif [ -w "$HOME/.local/bin" ]; then
    INSTALL_DIR="$HOME/.local/bin"
    mkdir -p "$INSTALL_DIR"
else
    INSTALL_DIR="$HOME/bin"
    mkdir -p "$INSTALL_DIR"
fi

echo ""
echo "📝 Installing to $INSTALL_DIR..."

# Create symlink
ln -sf "$COTOR_SCRIPT" "$INSTALL_DIR/cotor"

echo ""
echo "✅ Installation complete!"
echo ""
echo "Cotor is now available globally as 'cotor'"
echo ""

# Check if directory is in PATH
if [[ ":$PATH:" != *":$INSTALL_DIR:"* ]]; then
    echo "⚠️  Warning: $INSTALL_DIR is not in your PATH"
    echo ""
    echo "Add this to your ~/.bashrc or ~/.zshrc:"
    echo "  export PATH=\"\$PATH:$INSTALL_DIR\""
    echo ""
    echo "Then run: source ~/.bashrc  (or source ~/.zshrc)"
    echo ""
else
    echo "🎉 You can now use 'cotor' from anywhere!"
    echo ""
fi

echo "Try these commands:"
echo "  cotor version"
echo "  cotor init"
echo "  cotor list"
