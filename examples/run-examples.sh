#!/bin/bash
# Cotor example runner – 예제 3개를 바로 실행하는 스크립트

set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COTOR="${ROOT_DIR}/shell/cotor"

if [ ! -x "$COTOR" ]; then
  echo "⚠️  ./shell/cotor 가 없습니다. 먼저 빌드해주세요: ./gradlew shadowJar"
  exit 1
fi

echo "➡️  단일 에이전트 예제"
echo "    $COTOR run hello-echo -c examples/single-agent.yaml"
"$COTOR" run hello-echo -c "$ROOT_DIR/examples/single-agent.yaml" --output-format text || true
echo

echo "➡️  병렬 비교 예제"
echo "    $COTOR run compare-summaries -c examples/parallel-compare.yaml"
"$COTOR" run compare-summaries -c "$ROOT_DIR/examples/parallel-compare.yaml" --output-format text || true
echo

echo "➡️  조건/루프 예제"
echo "    $COTOR run iterate-summary -c examples/decision-loop.yaml"
"$COTOR" run iterate-summary -c "$ROOT_DIR/examples/decision-loop.yaml" --output-format text || true
echo

echo "✅ 예제 실행 완료"
