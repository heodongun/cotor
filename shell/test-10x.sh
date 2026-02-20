#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

RUNS="${1:-10}"
if ! [[ "$RUNS" =~ ^[0-9]+$ ]] || [ "$RUNS" -le 0 ]; then
  echo "Usage: $0 [runs]"
  echo "  runs must be a positive integer (default: 10)"
  exit 2
fi

TS="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$ROOT_DIR/test-results/test-10x/$TS"
mkdir -p "$OUT_DIR"

echo "Running Gradle tests $RUNS time(s)"
echo "Results directory: $OUT_DIR"
echo

{
  echo "timestamp=$TS"
  echo "runs=$RUNS"
  echo "pwd=$(pwd)"
  echo "root=$ROOT_DIR"
  echo "branch=$(cd "$ROOT_DIR" && git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  echo "commit=$(cd "$ROOT_DIR" && git rev-parse HEAD 2>/dev/null || true)"
  echo
  echo "java:"
  (java -version 2>&1 || true)
  echo
  echo "gradle:"
  (cd "$ROOT_DIR" && ./gradlew --version 2>&1 || true)
} > "$OUT_DIR/env.txt"

failures=0

for i in $(seq 1 "$RUNS"); do
  idx="$(printf "%02d" "$i")"
  run_dir="$OUT_DIR/run-$idx"
  mkdir -p "$run_dir"

  echo "[$idx/$RUNS] ./gradlew test"

  # Force the test task to execute (avoid UP-TO-DATE) without re-running compilation tasks.
  rm -rf "$ROOT_DIR/build/test-results/test" \
         "$ROOT_DIR/build/reports/tests/test" \
         "$ROOT_DIR/build/reports/jacoco/test" 2>/dev/null || true

  set +e
  (cd "$ROOT_DIR" && ./gradlew test --no-build-cache --console=plain) >"$run_dir/gradle-test.log" 2>&1
  code=$?
  set -e

  echo "$code" > "$run_dir/exit_code.txt"
  if [ "$code" -ne 0 ]; then
    failures=$((failures + 1))
    echo "FAIL" > "$run_dir/status.txt"
    echo "  result: FAIL (exit=$code)"
  else
    echo "OK" > "$run_dir/status.txt"
    echo "  result: OK"
  fi

  if [ -d "$ROOT_DIR/build/test-results/test" ]; then
    mkdir -p "$run_dir/junit-xml"
    cp -R "$ROOT_DIR/build/test-results/test/." "$run_dir/junit-xml/"
  fi

  echo
done

summary="$OUT_DIR/summary.md"
{
  echo "# Gradle Test Repeat Results"
  echo
  echo "- Timestamp: $TS"
  echo "- Runs: $RUNS"
  echo "- Failures: $failures"
  echo "- Branch: $(cd "$ROOT_DIR" && git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  echo "- Commit: $(cd "$ROOT_DIR" && git rev-parse HEAD 2>/dev/null || true)"
  echo
  echo "## Per-Run Status"
  for i in $(seq 1 "$RUNS"); do
    idx="$(printf "%02d" "$i")"
    run_dir="$OUT_DIR/run-$idx"
    status="$(cat "$run_dir/status.txt" 2>/dev/null || echo "UNKNOWN")"
    exit_code="$(cat "$run_dir/exit_code.txt" 2>/dev/null || echo "?")"
    echo "- run-$idx: $status (exit=$exit_code)"
  done
} > "$summary"

echo "Summary: $summary"

if [ "$failures" -ne 0 ]; then
  echo "One or more runs failed: $failures/$RUNS"
  exit 1
fi

echo "All runs passed: $RUNS/$RUNS"

