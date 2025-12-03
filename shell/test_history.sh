#!/bin/bash
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COTOR="${ROOT_DIR}/shell/cotor"

if [ ! -x "$COTOR" ]; then
  echo "⚠️  ./shell/cotor is not found. Please build first: ./gradlew shadowJar"
  exit 1
fi

echo "Clearing previous stats"
"$COTOR" stats hello-echo --clear
"$COTOR" stats compare-summaries --clear

echo "Generating execution history for hello-echo..."
for i in {1..5}; do
  echo "  Running hello-echo iteration $i"
  "$COTOR" run hello-echo -c "$ROOT_DIR/examples/single-agent.yaml" --output-format text > /dev/null
done

echo "Generating execution history for compare-summaries..."
for i in {1..5}; do
  echo "  Running compare-summaries iteration $i"
  "$COTOR" run compare-summaries -c "$ROOT_DIR/examples/parallel-compare.yaml" --output-format text > /dev/null
done

echo "Verifying history view for hello-echo"
"$COTOR" stats hello-echo --history 5

echo "Verifying history view for compare-summaries"
"$COTOR" stats compare-summaries --history 5

echo "✅ Test script finished"
