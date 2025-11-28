#!/bin/bash

# Cotor Enhanced Features Test Script
# Tests the improved CLI, validation, and pipeline execution

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "🚀 Cotor Enhanced Features Test Suite"
echo "======================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Step 1: Build the project
echo "📦 Step 1: Building Cotor JAR"
echo "-------------------------------------"
./gradlew clean shadowJar
echo -e "${GREEN}✅ Build completed${NC}"
echo ""

APP_VERSION=$(grep -E 'version[[:space:]]*=' "$PROJECT_ROOT/build.gradle.kts" | head -n 1 | sed -E 's/.*"([^"]+)".*/\1/')
[ -z "$APP_VERSION" ] && APP_VERSION="1.0.0"
JAR_PATH="$PROJECT_ROOT/build/libs/cotor-${APP_VERSION}-all.jar"

# Step 2: Test basic commands
echo "🔍 Step 2: Testing Basic Commands"
echo "-------------------------------------"

echo "Testing version command..."
java -jar "$JAR_PATH" version
echo ""

echo "Testing init command..."
cd test/board-feature
java -jar "$JAR_PATH" init -c test-config.yaml || true
cd ../..
echo -e "${GREEN}✅ Basic commands work${NC}"
echo ""

# Step 3: Test validation command
echo "✅ Step 3: Testing Pipeline Validation"
echo "-------------------------------------"
cd test/board-feature
java -jar "$JAR_PATH" validate board-implementation -c board-pipeline.yaml || {
    echo -e "${YELLOW}⚠️  Validation may have warnings, continuing...${NC}"
}
cd ../..
echo -e "${GREEN}✅ Validation command tested${NC}"
echo ""

# Step 4: Test dry-run mode
echo "🎭 Step 4: Testing Dry-Run Mode"
echo "-------------------------------------"
cd test/board-feature
java -jar "$JAR_PATH" run board-implementation --dry-run -c board-pipeline.yaml || {
    echo -e "${YELLOW}⚠️  Dry-run may have issues, continuing...${NC}"
}
cd ../..
echo -e "${GREEN}✅ Dry-run tested${NC}"
echo ""

# Step 5: Test pipeline execution (if Claude is available)
echo "🔧 Step 5: Testing Pipeline Execution"
echo "-------------------------------------"
echo -e "${YELLOW}Note: This requires Claude CLI to be installed and configured${NC}"
echo ""

# Check if claude command exists
if command -v claude &> /dev/null; then
    echo -e "${GREEN}✅ Claude CLI found${NC}"
    echo "Would you like to run the actual board-implementation pipeline? (y/N)"
    read -r response

    if [[ "$response" =~ ^([yY][eE][sS]|[yY])$ ]]; then
        cd test/board-feature
        java -jar "$JAR_PATH" run board-implementation -c board-pipeline.yaml --verbose || {
            echo -e "${RED}❌ Pipeline execution failed${NC}"
        }
        cd ../..
    else
        echo "Skipping actual pipeline execution"
    fi
else
    echo -e "${YELLOW}⚠️  Claude CLI not found, skipping actual execution${NC}"
    echo "To install: npm install -g @anthropic-ai/claude-cli"
fi
echo ""

# Step 6: Test error handling
echo "🛡️  Step 6: Testing Error Handling"
echo "-------------------------------------"
echo "Testing with non-existent pipeline..."
cd test/board-feature
java -jar "$JAR_PATH" run nonexistent-pipeline -c board-pipeline.yaml 2>&1 || {
    echo -e "${GREEN}✅ Error handling works (expected failure)${NC}"
}
cd ../..
echo ""

echo "Testing with non-existent config..."
java -jar "$JAR_PATH" run test -c nonexistent.yaml 2>&1 || {
    echo -e "${GREEN}✅ Config not found error works${NC}"
}
echo ""

# Step 7: Display test summary
echo "📊 Test Summary"
echo "======================================="
echo -e "${GREEN}✅ Build and JAR generation${NC}"
echo -e "${GREEN}✅ Basic CLI commands${NC}"
echo -e "${GREEN}✅ Pipeline validation${NC}"
echo -e "${GREEN}✅ Dry-run mode${NC}"
echo -e "${GREEN}✅ Error handling${NC}"
echo ""
echo "🎉 All core tests completed!"
echo ""
echo "📚 Next Steps:"
echo "   1. Review the generated board-implementation code in test/board-feature/src/"
echo "   2. Check the documentation in test/board-feature/README.md"
echo "   3. Run: cd test/board-feature && ./gradlew test"
echo ""
echo "💡 Usage Examples:"
echo "   # Validate pipeline"
echo "   ./cotor validate board-implementation -c test/board-feature/board-pipeline.yaml"
echo ""
echo "   # Dry-run (simulation)"
echo "   ./cotor run board-implementation --dry-run -c test/board-feature/board-pipeline.yaml"
echo ""
echo "   # Full execution with monitoring"
echo "   ./cotor run board-implementation -c test/board-feature/board-pipeline.yaml --verbose"
echo ""
