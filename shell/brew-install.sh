#!/bin/bash
# Quick install script for Cotor via Homebrew
# Usage: curl -fsSL https://raw.githubusercontent.com/bssm-oss/cotor/master/shell/brew-install.sh | bash
#
# This installs:
#   - JDK 17 (automatic dependency)
#   - Cotor CLI (`cotor` command)
#   - Cotor Desktop App (/Applications/Cotor Desktop.app)

set -euo pipefail

echo "🚀 Installing Cotor via Homebrew..."
echo "   This will install: JDK 17 + CLI + Desktop App"
echo ""

# Check for Homebrew
if ! command -v brew &>/dev/null; then
    echo "❌ Homebrew is required. Install it first:"
    echo '   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"'
    exit 1
fi

# Add Cotor tap (uses the main repo as the tap source)
echo "📦 Adding bssm-oss/cotor tap..."
brew tap bssm-oss/cotor https://github.com/bssm-oss/cotor.git 2>/dev/null || true

# Install everything: JDK 17 + CLI + Desktop App
echo "☕ Installing Cotor (JDK 17 + CLI + Desktop App)..."
brew install bssm-oss/cotor/cotor

echo ""
echo "✅ Cotor installed successfully!"
echo ""
echo "   cotor version                          # Check CLI"
echo "   cotor app-server                       # Start API server"
echo "   open '/Applications/Cotor Desktop.app' # Open desktop app"
echo ""
echo "🔄 To update later:"
echo "   brew upgrade cotor"
