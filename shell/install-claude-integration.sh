#!/bin/bash

# Cotor-Claude 통합 설치 스크립트
# 전역 Claude 설정에 cotor 커맨드와 지식 베이스를 설치합니다.

set -e

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🚀 Cotor-Claude 통합 설치"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# 색상 정의
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 전역 Claude 디렉토리
CLAUDE_HOME="$HOME/.claude"
STEERING_DIR="$CLAUDE_HOME/steering"
COMMANDS_DIR="$CLAUDE_HOME/commands"
TEMPLATES_DIR="$CLAUDE_HOME/templates"
SETTINGS_DIR="$CLAUDE_HOME/settings"

# 현재 스크립트 디렉토리
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1. 디렉토리 생성
echo "📁 디렉토리 구조 생성 중..."
mkdir -p "$STEERING_DIR"
mkdir -p "$COMMANDS_DIR"
mkdir -p "$TEMPLATES_DIR"
mkdir -p "$SETTINGS_DIR"
echo -e "${GREEN}✓${NC} 디렉토리 생성 완료"
echo ""

# 2. 지식 베이스 파일 확인
echo "📚 지식 베이스 확인 중..."
KNOWLEDGE_FILE="$STEERING_DIR/cotor-knowledge.md"

if [ -f "$KNOWLEDGE_FILE" ]; then
  echo -e "${YELLOW}⚠${NC}  지식 베이스 파일이 이미 존재합니다: $KNOWLEDGE_FILE"
  echo "   기존 파일을 유지합니다."
else
  echo -e "${GREEN}✓${NC} 지식 베이스 파일이 설치되어 있습니다."
fi
echo ""

# 3. 슬래시 커맨드 확인
echo "⚡ 슬래시 커맨드 확인 중..."
COMMANDS=(
  "cotor-generate.md"
  "cotor-execute.md"
  "cotor-validate.md"
  "cotor-template.md"
)

for cmd in "${COMMANDS[@]}"; do
  if [ -f "$COMMANDS_DIR/$cmd" ]; then
    echo -e "${GREEN}✓${NC} $cmd"
  else
    echo -e "${RED}✗${NC} $cmd (누락)"
  fi
done
echo ""

# 4. 템플릿 파일 확인
echo "📋 템플릿 파일 확인 중..."
TEMPLATES=(
  "compare-solutions.yaml"
  "review-chain.yaml"
  "comprehensive-review.yaml"
)

for tmpl in "${TEMPLATES[@]}"; do
  if [ -f "$TEMPLATES_DIR/$tmpl" ]; then
    echo -e "${GREEN}✓${NC} $tmpl"
  else
    echo -e "${RED}✗${NC} $tmpl (누락)"
  fi
done
echo ""

# 5. 설정 파일 확인
echo "⚙️  설정 파일 확인 중..."
SETTINGS_FILE="$SETTINGS_DIR/cotor-settings.json"

if [ -f "$SETTINGS_FILE" ]; then
  echo -e "${GREEN}✓${NC} cotor-settings.json"
else
  echo -e "${RED}✗${NC} cotor-settings.json (누락)"
fi
echo ""

# 6. 권한 설정
echo "🔐 권한 설정 중..."
chmod -R 755 "$COMMANDS_DIR"
chmod -R 644 "$STEERING_DIR"/*.md 2>/dev/null || true
chmod -R 644 "$TEMPLATES_DIR"/*.yaml 2>/dev/null || true
echo -e "${GREEN}✓${NC} 권한 설정 완료"
echo ""

# 7. 설치 검증
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ 설치 완료!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "설치된 파일:"
echo "  📚 지식 베이스: $KNOWLEDGE_FILE"
echo "  ⚡ 슬래시 커맨드: $COMMANDS_DIR/"
echo "  📋 템플릿: $TEMPLATES_DIR/"
echo "  ⚙️  설정: $SETTINGS_FILE"
echo ""
echo "사용 가능한 커맨드:"
echo "  /cotor-generate [목표]     - 파이프라인 자동 생성"
echo "  /cotor-execute [파일]      - 파이프라인 실행"
echo "  /cotor-validate [파일]     - 파이프라인 검증"
echo "  /cotor-template [템플릿]   - 템플릿에서 생성"
echo ""
echo "다음 단계:"
echo "  1. Claude Code를 재시작하세요"
echo "  2. 아무 프로젝트에서나 /cotor-template 입력하여 테스트"
echo "  3. 템플릿 목록이 표시되면 설치 성공!"
echo ""
echo "문서:"
echo "  - docs/README.md: 전체 가이드"
echo "  - docs/README.ko.md: 한글 가이드"
echo "  - ~/.claude/steering/cotor-knowledge.md: 지식 베이스"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
