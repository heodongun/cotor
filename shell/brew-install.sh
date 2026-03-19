#!/bin/bash
# Quick install script for Cotor via Homebrew
# Usage: curl -fsSL https://raw.githubusercontent.com/bssm-oss/cotor/master/shell/brew-install.sh | bash

set -euo pipefail

echo "🚀 Installing Cotor via Homebrew..."
echo ""

# Check for Homebrew
if ! command -v brew &>/dev/null; then
    echo "❌ Homebrew is required. Install it first:"
    echo '   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"'
    exit 1
fi

# Add Cotor tap
echo "📦 Adding bssm-oss/cotor tap..."
brew tap bssm-oss/cotor https://github.com/bssm-oss/cotor.git 2>/dev/null || true

# Install JDK 17 + CLI
echo "☕ Installing JDK 17 and Cotor CLI..."
brew install bssm-oss/cotor/cotor

echo ""
echo "✅ Cotor installed successfully!"
echo ""
echo "   cotor version     # Check installation"
echo "   cotor --help      # See all commands"
echo "   cotor app-server  # Start the API server"
echo ""
echo "🖥️  To install the Desktop App:"
echo "   brew install --cask bssm-oss/cotor/cotor-desktop"
