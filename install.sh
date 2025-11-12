#!/bin/bash
# Cotor installation script

set -e

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
if [ -f "./gradlew" ]; then
    ./gradlew shadowJar --no-daemon
else
    echo "❌ Error: gradlew not found. Are you in the Cotor directory?"
    exit 1
fi

echo ""
echo "✅ Build successful!"
echo ""

# Make cotor script executable
chmod +x cotor

# Determine shell config file
if [ -n "$ZSH_VERSION" ]; then
    SHELL_CONFIG="$HOME/.zshrc"
elif [ -n "$BASH_VERSION" ]; then
    SHELL_CONFIG="$HOME/.bashrc"
else
    SHELL_CONFIG="$HOME/.profile"
fi

# Get absolute path
COTOR_PATH="$(cd "$(dirname "$0")" && pwd)/cotor"

echo "📝 Installation complete!"
echo ""
echo "To use Cotor from anywhere, add this to your $SHELL_CONFIG:"
echo ""
echo "  alias cotor='$COTOR_PATH'"
echo ""
echo "Or add Cotor to your PATH:"
echo ""
echo "  export PATH=\"\$PATH:$(dirname "$COTOR_PATH")\"" 
echo ""
echo "Then run: source $SHELL_CONFIG"
echo ""
echo "🎉 You can now use Cotor!"
echo ""
echo "Try these commands:"
echo "  ./cotor version"
echo "  ./cotor init"
echo "  ./cotor list --config test-ai-models.yaml"
echo "  ./cotor run test-all-models --config test-ai-models.yaml"
