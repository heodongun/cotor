#!/usr/bin/env bash

# Cotor 실패 케이스 통합 테스트
# - validation 실패
# - agent 실행 실패
# - decision 조건 분기 실패(ABORT)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COTOR_CMD="$PROJECT_ROOT/shell/cotor"

if [[ ! -x "$COTOR_CMD" ]]; then
  echo "❌ cotor 실행 스크립트를 찾을 수 없습니다: $COTOR_CMD"
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

PASS=0
FAIL=0

run_expect_fail() {
  local name="$1"
  local config="$2"
  local expected="$3"
  local allow_zero_rc="${4:-false}"

  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "🧪 $name"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

  set +e
  local output
  output="$($COTOR_CMD run "$name" -c "$config" --output-format text 2>&1)"
  local rc=$?
  set -e

  if [[ "$allow_zero_rc" != "true" && $rc -eq 0 ]]; then
    echo "❌ 실패 기대 케이스인데 종료코드가 0입니다."
    echo "$output"
    ((FAIL+=1))
    return
  fi

  if [[ "$allow_zero_rc" == "true" && $rc -eq 0 ]]; then
    if ! grep -Fq "❌ Failed:" <<< "$output"; then
      echo "❌ 종료코드는 0이지만 파이프라인 실패 요약이 없습니다."
      echo "$output"
      ((FAIL+=1))
      return
    fi
  fi

  if ! grep -Fq "$expected" <<< "$output"; then
    echo "❌ 실패 메시지 검증 실패"
    echo "   expected: $expected"
    echo "   output:"
    echo "$output"
    ((FAIL+=1))
    return
  fi

  echo "✅ 예상대로 실패했습니다 ($expected)"
  ((PASS+=1))
}

cat > "$WORK_DIR/fail-validation.yaml" <<'YAML'
version: "1.0"
agents:
  - name: ok
    pluginClass: com.cotor.data.plugin.EchoPlugin
pipelines:
  - name: fail-validation
    executionMode: SEQUENTIAL
    stages:
      - id: step1
        agent:
          name: ghost-agent
security:
  useWhitelist: false
logging:
  level: WARN
YAML

cat > "$WORK_DIR/fail-agent.yaml" <<'YAML'
version: "1.0"
agents:
  - name: bad
    pluginClass: com.cotor.data.plugin.CommandPlugin
    parameters:
      argvJson: '["/usr/bin/false"]'
pipelines:
  - name: fail-agent
    executionMode: SEQUENTIAL
    stages:
      - id: step1
        agent:
          name: bad
security:
  useWhitelist: false
logging:
  level: WARN
YAML

cat > "$WORK_DIR/fail-condition.yaml" <<'YAML'
version: "1.0"
agents:
  - name: ok
    pluginClass: com.cotor.data.plugin.EchoPlugin
pipelines:
  - name: fail-condition
    executionMode: SEQUENTIAL
    stages:
      - id: produce
        agent:
          name: ok
        input: "hello"
      - id: gate
        type: DECISION
        condition:
          expression: "produce.output == 'world'"
          onTrue:
            action: CONTINUE
          onFalse:
            action: ABORT
security:
  useWhitelist: false
logging:
  level: WARN
YAML

run_expect_fail "fail-validation" "$WORK_DIR/fail-validation.yaml" "Agent 'ghost-agent' not defined"
run_expect_fail "fail-agent" "$WORK_DIR/fail-agent.yaml" "Command failed (exit=1):" true
run_expect_fail "fail-condition" "$WORK_DIR/fail-condition.yaml" "Pipeline aborted by decision stage 'gate'"

echo ""
echo "결과: PASS=$PASS, FAIL=$FAIL"
if [[ $FAIL -ne 0 ]]; then
  exit 1
fi

echo "✅ 실패 케이스 통합 테스트 통과"
