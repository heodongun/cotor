#!/bin/bash

# Cotor-Claude 통합 테스트 스크립트
# 설치된 파일과 커맨드가 올바르게 작동하는지 확인합니다.

set -e

# 색상 정의
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 테스트 카운터
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# 전역 Claude 디렉토리
CLAUDE_HOME="$HOME/.claude"
STEERING_DIR="$CLAUDE_HOME/steering"
COMMANDS_DIR="$CLAUDE_HOME/commands"
TEMPLATES_DIR="$CLAUDE_HOME/templates"
SETTINGS_DIR="$CLAUDE_HOME/settings"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🧪 Cotor-Claude 통합 테스트"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# 테스트 함수
test_file_exists() {
  local file=$1
  local description=$2
  TOTAL_TESTS=$((TOTAL_TESTS + 1))
  
  if [ -f "$file" ]; then
    echo -e "${GREEN}✓${NC} $description"
    PASSED_TESTS=$((PASSED_TESTS + 1))
    return 0
  else
    echo -e "${RED}✗${NC} $description"
    echo -e "   ${RED}파일 없음: $file${NC}"
    FAILED_TESTS=$((FAILED_TESTS + 1))
    return 1
  fi
}

test_dir_exists() {
  local dir=$1
  local description=$2
  TOTAL_TESTS=$((TOTAL_TESTS + 1))
  
  if [ -d "$dir" ]; then
    echo -e "${GREEN}✓${NC} $description"
    PASSED_TESTS=$((PASSED_TESTS + 1))
    return 0
  else
    echo -e "${RED}✗${NC} $description"
    echo -e "   ${RED}디렉토리 없음: $dir${NC}"
    FAILED_TESTS=$((FAILED_TESTS + 1))
    return 1
  fi
}

test_file_contains() {
  local file=$1
  local pattern=$2
  local description=$3
  TOTAL_TESTS=$((TOTAL_TESTS + 1))
  
  if [ ! -f "$file" ]; then
    echo -e "${RED}✗${NC} $description"
    echo -e "   ${RED}파일 없음: $file${NC}"
    FAILED_TESTS=$((FAILED_TESTS + 1))
    return 1
  fi
  
  if grep -q "$pattern" "$file"; then
    echo -e "${GREEN}✓${NC} $description"
    PASSED_TESTS=$((PASSED_TESTS + 1))
    return 0
  else
    echo -e "${RED}✗${NC} $description"
    echo -e "   ${RED}패턴 없음: $pattern${NC}"
    FAILED_TESTS=$((FAILED_TESTS + 1))
    return 1
  fi
}

# 1. 디렉토리 구조 테스트
echo -e "${BLUE}📁 디렉토리 구조 테스트${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
test_dir_exists "$STEERING_DIR" "Steering 디렉토리 존재"
test_dir_exists "$COMMANDS_DIR" "Commands 디렉토리 존재"
test_dir_exists "$TEMPLATES_DIR" "Templates 디렉토리 존재"
test_dir_exists "$SETTINGS_DIR" "Settings 디렉토리 존재"
echo ""

# 2. 지식 베이스 파일 테스트
echo -e "${BLUE}📚 지식 베이스 테스트${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
KNOWLEDGE_FILE="$STEERING_DIR/cotor-knowledge.md"
test_file_exists "$KNOWLEDGE_FILE" "지식 베이스 파일 존재"
test_file_contains "$KNOWLEDGE_FILE" "## 핵심 개념" "핵심 개념 섹션 존재"
test_file_contains "$KNOWLEDGE_FILE" "## 명령어 참조" "명령어 참조 섹션 존재"
test_file_contains "$KNOWLEDGE_FILE" "## 파이프라인 생성 규칙" "파이프라인 규칙 섹션 존재"
test_file_contains "$KNOWLEDGE_FILE" "## 성공 패턴" "성공 패턴 섹션 존재"
test_file_contains "$KNOWLEDGE_FILE" "## 사용 가능한 AI 플러그인" "AI 플러그인 섹션 존재"
test_file_contains "$KNOWLEDGE_FILE" "## 템플릿" "템플릿 섹션 존재"
echo ""

# 3. 슬래시 커맨드 파일 테스트
echo -e "${BLUE}⚡ 슬래시 커맨드 테스트${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
test_file_exists "$COMMANDS_DIR/cotor-generate.md" "/cotor-generate 커맨드 파일 존재"
test_file_exists "$COMMANDS_DIR/cotor-execute.md" "/cotor-execute 커맨드 파일 존재"
test_file_exists "$COMMANDS_DIR/cotor-validate.md" "/cotor-validate 커맨드 파일 존재"
test_file_exists "$COMMANDS_DIR/cotor-template.md" "/cotor-template 커맨드 파일 존재"
echo ""

# 4. 커맨드 메타데이터 테스트
echo -e "${BLUE}📋 커맨드 메타데이터 테스트${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
test_file_contains "$COMMANDS_DIR/cotor-generate.md" "name: cotor-generate" "/cotor-generate 메타데이터"
test_file_contains "$COMMANDS_DIR/cotor-generate.md" "category: cotor" "/cotor-generate 카테고리"
test_file_contains "$COMMANDS_DIR/cotor-execute.md" "name: cotor-execute" "/cotor-execute 메타데이터"
test_file_contains "$COMMANDS_DIR/cotor-validate.md" "name: cotor-validate" "/cotor-validate 메타데이터"
test_file_contains "$COMMANDS_DIR/cotor-template.md" "name: cotor-template" "/cotor-template 메타데이터"
echo ""

# 5. 템플릿 파일 테스트
echo -e "${BLUE}📦 템플릿 파일 테스트${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
test_file_exists "$TEMPLATES_DIR/compare-solutions.yaml" "compare-solutions 템플릿 존재"
test_file_exists "$TEMPLATES_DIR/review-chain.yaml" "review-chain 템플릿 존재"
test_file_exists "$TEMPLATES_DIR/comprehensive-review.yaml" "comprehensive-review 템플릿 존재"
echo ""

# 6. 템플릿 YAML 유효성 테스트
echo -e "${BLUE}✅ 템플릿 YAML 유효성 테스트${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
test_file_contains "$TEMPLATES_DIR/compare-solutions.yaml" "version:" "compare-solutions version 필드"
test_file_contains "$TEMPLATES_DIR/compare-solutions.yaml" "agents:" "compare-solutions agents 필드"
test_file_contains "$TEMPLATES_DIR/compare-solutions.yaml" "pipelines:" "compare-solutions pipelines 필드"
test_file_contains "$TEMPLATES_DIR/review-chain.yaml" "executionMode: SEQUENTIAL" "review-chain 순차 모드"
test_file_contains "$TEMPLATES_DIR/comprehensive-review.yaml" "executionMode: PARALLEL" "comprehensive-review 병렬 모드"
echo ""

# 7. 설정 파일 테스트
echo -e "${BLUE}⚙️  설정 파일 테스트${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
SETTINGS_FILE="$SETTINGS_DIR/cotor-settings.json"
test_file_exists "$SETTINGS_FILE" "설정 파일 존재"
test_file_contains "$SETTINGS_FILE" "globalKnowledge" "globalKnowledge 설정"
test_file_contains "$SETTINGS_FILE" "commandsDir" "commandsDir 설정"
test_file_contains "$SETTINGS_FILE" "templates" "templates 설정"
echo ""

# 8. 커맨드 구현 스크립트 테스트
echo -e "${BLUE}🔧 커맨드 구현 테스트${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
test_file_contains "$COMMANDS_DIR/cotor-generate.md" "cotor generate" "/cotor-generate 구현"
test_file_contains "$COMMANDS_DIR/cotor-execute.md" "cotor execute" "/cotor-execute 구현"
test_file_contains "$COMMANDS_DIR/cotor-validate.md" "cotor validate" "/cotor-validate 구현"
test_file_contains "$COMMANDS_DIR/cotor-template.md" "TEMPLATE_DIR" "/cotor-template 구현"
echo ""

# 9. 문서화 테스트
echo -e "${BLUE}📖 문서화 테스트${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
test_file_contains "$COMMANDS_DIR/cotor-generate.md" "## 사용법" "/cotor-generate 사용법"
test_file_contains "$COMMANDS_DIR/cotor-generate.md" "## 예시" "/cotor-generate 예시"
test_file_contains "$COMMANDS_DIR/cotor-execute.md" "## 오류 처리" "/cotor-execute 오류 처리"
test_file_contains "$COMMANDS_DIR/cotor-validate.md" "## 검증 항목" "/cotor-validate 검증 항목"
test_file_contains "$COMMANDS_DIR/cotor-template.md" "## 사용 가능한 템플릿" "/cotor-template 템플릿 목록"
echo ""

# 10. README 업데이트 테스트
echo -e "${BLUE}📝 README 업데이트 테스트${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
test_file_contains "README.md" "Claude Code Integration" "README.md Claude 섹션"
test_file_contains "README.md" "/cotor-generate" "README.md generate 커맨드"
test_file_contains "README.md" "install-claude-integration.sh" "README.md 설치 스크립트"
test_file_contains "README.ko.md" "Claude Code 통합" "README.ko.md Claude 섹션"
test_file_contains "README.ko.md" "/cotor-generate" "README.ko.md generate 커맨드"
echo ""

# 최종 결과
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📊 테스트 결과"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "총 테스트: $TOTAL_TESTS"
echo -e "${GREEN}통과: $PASSED_TESTS${NC}"
echo -e "${RED}실패: $FAILED_TESTS${NC}"
echo ""

if [ $FAILED_TESTS -eq 0 ]; then
  echo -e "${GREEN}✅ 모든 테스트 통과!${NC}"
  echo ""
  echo "다음 단계:"
  echo "1. Claude Code를 재시작하세요"
  echo "2. 아무 프로젝트에서나 /cotor-template 입력"
  echo "3. 템플릿 목록이 표시되면 성공!"
  echo ""
  exit 0
else
  echo -e "${RED}❌ 일부 테스트 실패${NC}"
  echo ""
  echo "문제 해결:"
  echo "1. 설치 스크립트 실행: ./install-claude-integration.sh"
  echo "2. 파일 권한 확인: ls -la ~/.claude/"
  echo "3. 테스트 재실행: ./test-claude-integration.sh"
  echo ""
  exit 1
fi
