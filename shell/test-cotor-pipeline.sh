#!/bin/bash

# Cotor 파이프라인 테스트 스크립트
# 게시판 기능 구현을 테스트합니다.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"
COTOR_CMD="cotor"

# 색상 정의
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🧪 Cotor 파이프라인 테스트"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# 테스트 디렉토리 생성
TEST_DIR="test/board-feature"
mkdir -p "$TEST_DIR"
cd "$TEST_DIR"

echo -e "${BLUE}📁 테스트 디렉토리: $TEST_DIR${NC}"
echo ""

# 1. cotor 설치 확인
echo -e "${BLUE}1️⃣  Cotor 설치 확인${NC}"
if command -v cotor &> /dev/null; then
    echo -e "${GREEN}✓${NC} cotor 명령어 발견"
    COTOR_CMD="cotor"
elif [ -x "$PROJECT_ROOT/shell/cotor" ]; then
    COTOR_CMD="$PROJECT_ROOT/shell/cotor"
    echo -e "${YELLOW}⚠${NC}  전역 설치된 cotor를 찾을 수 없습니다. 로컬 스크립트를 사용합니다: $COTOR_CMD"
else
    echo -e "${RED}✗${NC} cotor 명령어를 찾을 수 없습니다"
    echo "설치 방법: ./shell/install-global.sh"
    exit 1
fi
"$COTOR_CMD" version
echo ""

# 2. 파이프라인 YAML 생성
echo -e "${BLUE}2️⃣  게시판 파이프라인 YAML 생성${NC}"
cat > board-pipeline.yaml << 'EOF'
version: "1.0"

# 게시판 기능 구현을 위한 AI 에이전트
agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 240000
    tags:
      - backend
      - design

  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 240000
    tags:
      - review
      - testing

pipelines:
  - name: board-implementation
    description: "게시판 CRUD 기능 구현 파이프라인"
    executionMode: SEQUENTIAL
    stages:
      # 1단계: 요구사항 분석 및 설계
      - id: requirements-analysis
        agent:
          name: claude
        input: |
          게시판 기능의 요구사항을 분석하고 설계해주세요.
          
          필수 기능:
          - 게시글 작성 (Create)
          - 게시글 목록 조회 (Read - List)
          - 게시글 상세 조회 (Read - Detail)
          - 게시글 수정 (Update)
          - 게시글 삭제 (Delete)
          
          다음 내용을 포함해주세요:
          1. 데이터베이스 스키마 설계 (테이블 구조)
          2. REST API 엔드포인트 설계
          3. 주요 비즈니스 로직
          
          결과를 requirements.md 파일로 작성해주세요.

      # 2단계: 백엔드 구현
      - id: backend-implementation
        agent:
          name: claude
        input: |
          위의 설계를 바탕으로 게시판 백엔드를 구현해주세요.
          
          구현 내용:
          1. Entity 클래스 (Board.kt)
          2. Repository 인터페이스 (BoardRepository.kt)
          3. Service 클래스 (BoardService.kt)
          4. Controller 클래스 (BoardController.kt)
          
          기술 스택: Kotlin + Spring Boot + JPA
          
          각 파일을 생성하고 주석으로 설명을 추가해주세요.

      # 3단계: 코드 리뷰 및 개선
      - id: code-review
        agent:
          name: gemini
        input: |
          위에서 구현된 게시판 백엔드 코드를 리뷰해주세요.
          
          리뷰 항목:
          1. 코드 품질 (가독성, 유지보수성)
          2. 보안 취약점 (SQL Injection, XSS 등)
          3. 성능 최적화 가능성
          4. 에러 처리
          5. 테스트 가능성
          
          개선 사항을 code-review.md 파일로 작성해주세요.

      # 4단계: 테스트 코드 작성
      - id: testing
        agent:
          name: gemini
        input: |
          게시판 기능에 대한 테스트 코드를 작성해주세요.
          
          테스트 종류:
          1. 단위 테스트 (Service 레이어)
          2. 통합 테스트 (Controller + Service + Repository)
          3. API 테스트 (REST API 엔드포인트)
          
          JUnit 5와 MockK를 사용해주세요.
          
          테스트 파일:
          - BoardServiceTest.kt
          - BoardControllerTest.kt
          - BoardApiTest.kt

      # 5단계: 문서화
      - id: documentation
        agent:
          name: claude
        input: |
          게시판 기능에 대한 종합 문서를 작성해주세요.
          
          문서 내용:
          1. API 문서 (엔드포인트, 요청/응답 예시)
          2. 데이터베이스 스키마
          3. 설치 및 실행 방법
          4. 테스트 실행 방법
          5. 알려진 이슈 및 제한사항
          
          README.md 파일로 작성해주세요.

# 보안 설정
security:
  useWhitelist: true
  allowedExecutables:
    - claude
    - gemini
  allowedDirectories:
    - /usr/local/bin
    - /opt/homebrew/bin

# 로깅 설정
logging:
  level: INFO
  file: board-pipeline.log
  format: json

# 성능 설정
performance:
  maxConcurrentAgents: 5
  coroutinePoolSize: 4
EOF

echo -e "${GREEN}✓${NC} board-pipeline.yaml 생성 완료"
echo ""

# 3. 파이프라인 구조 확인
echo -e "${BLUE}3️⃣  파이프라인 구조 확인${NC}"
echo "파이프라인: board-implementation"
echo "실행 모드: SEQUENTIAL"
echo "스테이지 수: 5"
echo "  1. requirements-analysis (claude)"
echo "  2. backend-implementation (claude)"
echo "  3. code-review (gemini)"
echo "  4. testing (gemini)"
echo "  5. documentation (claude)"
echo ""

# 4. cotor 초기화
echo -e "${BLUE}4️⃣  Cotor 초기화${NC}"
if [ ! -f "cotor.yaml" ]; then
    cp board-pipeline.yaml cotor.yaml
    echo -e "${GREEN}✓${NC} cotor.yaml 생성 완료"
else
    echo -e "${YELLOW}⚠${NC}  cotor.yaml이 이미 존재합니다"
fi
echo ""

# 5. 에이전트 목록 확인
echo -e "${BLUE}5️⃣  등록된 에이전트 확인${NC}"
"$COTOR_CMD" list || echo -e "${YELLOW}⚠${NC}  에이전트 목록을 가져올 수 없습니다"
echo ""

# 6. 파이프라인 실행
echo -e "${BLUE}6️⃣  파이프라인 실행${NC}"
echo -e "${YELLOW}주의: 이 작업은 시간이 오래 걸릴 수 있습니다 (약 5-10분)${NC}"
echo ""
echo ""
echo "🚀 파이프라인 실행 중..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# 시작 시간 기록
START_TIME=$(date +%s)

# 파이프라인 실행
"$COTOR_CMD" run board-implementation --config board-pipeline.yaml --output-format text || {
    echo ""
    echo -e "${RED}❌ 파이프라인 실행 실패${NC}"
    echo ""
    echo "로그 확인:"
    echo "  cat board-pipeline.log"
    exit 1
}

# 종료 시간 기록
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${GREEN}✅ 파이프라인 실행 완료!${NC}"
echo -e "⏱️  실행 시간: ${DURATION}초"
echo ""

# 7. 결과 확인
echo -e "${BLUE}7️⃣  생성된 파일 확인${NC}"
echo ""
ls -lah
echo ""

# 8. 결과 요약
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${GREEN}✅ 테스트 완료!${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "생성된 파일:"
echo "  📄 requirements.md - 요구사항 및 설계"
echo "  📄 Board.kt - Entity 클래스"
echo "  📄 BoardRepository.kt - Repository"
echo "  📄 BoardService.kt - Service"
echo "  📄 BoardController.kt - Controller"
echo "  📄 code-review.md - 코드 리뷰"
echo "  📄 BoardServiceTest.kt - 테스트"
echo "  📄 README.md - 문서"
echo ""
echo "다음 단계:"
echo "  1. 생성된 파일 검토"
echo "  2. 필요시 수정 및 개선"
echo "  3. 실제 프로젝트에 통합"
echo ""
echo "로그 파일: board-pipeline.log"
echo ""
