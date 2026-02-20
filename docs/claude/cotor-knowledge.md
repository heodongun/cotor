# Cotor CLI 프로젝트 메모리

## 핵심 개념

### Cotor란?
Cotor는 여러 AI 도구를 통합 인터페이스로 관리하는 Kotlin 기반 AI CLI 오케스트레이션 시스템입니다. 코루틴을 활용한 고성능 비동기 실행을 제공합니다.

### 파이프라인 개념
파이프라인은 AI 에이전트 작업 시퀀스를 정의하는 YAML 설정 파일입니다. 각 파이프라인은 하나 이상의 스테이지로 구성되며, 각 스테이지는 특정 AI 에이전트를 실행합니다.

### 실행 모드
- **SEQUENTIAL**: 순차 실행 - 한 스테이지가 완료된 후 다음 스테이지 실행
- **PARALLEL**: 병렬 실행 - 모든 스테이지를 동시에 실행
- **DAG**: 의존성 기반 실행 - 의존성 그래프에 따라 실행

## 명령어 참조

### cotor (기본 진입)
인자 없이 실행하면 interactive TUI로 바로 진입합니다.
`cotor.yaml`이 없으면 현재 디렉터리에 starter 설정을 자동 생성한 뒤 진입합니다.

**구문:**
```bash
cotor
```

**별칭:**
```bash
cotor tui    # cotor interactive와 동일
```

### cotor generate
목표 설명에서 파이프라인을 자동 생성합니다.

**구문:**
```bash
cotor generate "목표 설명" --dry-run
```

**옵션:**
- `--dry-run`: 파이프라인을 생성만 하고 실행하지 않음

**예시:**
```bash
cotor generate "Claude와 Gemini로 소수 찾기 함수 비교" --dry-run
```

### cotor execute
파이프라인을 실행하고 모니터링합니다.

**구문:**
```bash
cotor execute <pipeline-file> --monitor
```

**옵션:**
- `--monitor`: 실행 중 실시간 모니터링 활성화

**예시:**
```bash
cotor execute test/multi-compare.yaml --monitor
```

### cotor validate
파이프라인 YAML 파일의 구문과 설정을 검증합니다.

**구문:**
```bash
cotor validate <pipeline-file>
```

**예시:**
```bash
cotor validate pipeline.yaml
```

### cotor run
파이프라인을 실행합니다 (전통적인 방식).

**구문:**
```bash
cotor run <pipeline-name> --config <config-file> --output-format <format>
```

**옵션:**
- `--config`: 설정 파일 경로 (기본값: cotor.yaml)
- `--output-format`: 출력 형식 (json, csv, text)

**예시:**
```bash
cotor run code-review --output-format text
```

### cotor list
등록된 에이전트 목록을 표시합니다.

**구문:**
```bash
cotor list [--config <config-file>]
```

### cotor init
기본 설정 파일을 생성합니다.

**구문:**
```bash
cotor init
```

## 파이프라인 생성 규칙

### YAML 구조 요구사항

#### 1. 필수 필드
```yaml
version: "1.0"              # 필수: 설정 버전
agents: []                  # 필수: 사용할 AI 에이전트 정의
pipelines: []               # 필수: 파이프라인 정의
security: {}                # 필수: 보안 설정
```

#### 2. 에이전트 정의
```yaml
agents:
  - name: string            # 필수: 에이전트 이름
    pluginClass: string     # 필수: 플러그인 클래스 전체 경로
    timeout: number         # 필수: 타임아웃 (밀리초)
    parameters: {}          # 선택: 추가 파라미터
    tags: []                # 선택: 태그 목록
```

#### 3. 파이프라인 정의
```yaml
pipelines:
  - name: string            # 필수: 파이프라인 이름
    description: string     # 선택: 설명
    executionMode: string   # 필수: SEQUENTIAL, PARALLEL, DAG
    stages:                 # 필수: 스테이지 목록
      - id: string          # 필수: 스테이지 ID
        agent:              # 필수: 사용할 에이전트
          name: string      # 필수: 에이전트 이름
          pluginClass: string  # 선택: 플러그인 클래스 (에이전트 정의 재정의)
        input: string       # 선택: 입력 프롬프트
        dependencies: []    # 선택: 의존성 (DAG 모드용)
```

#### 4. 보안 설정
```yaml
security:
  useWhitelist: boolean     # 필수: whitelist 사용 여부
  allowedExecutables: []    # 필수: 허용된 실행 파일 목록
  allowedDirectories: []    # 필수: 허용된 디렉토리 목록
```

#### 5. 로깅 설정 (선택)
```yaml
logging:
  level: string             # DEBUG, INFO, WARN, ERROR
  file: string              # 로그 파일 경로
  format: string            # json, text
```

#### 6. 성능 설정 (선택)
```yaml
performance:
  maxConcurrentAgents: number    # 최대 동시 실행 에이전트 수
  coroutinePoolSize: number      # 코루틴 풀 크기
```

## 성공 패턴

### 패턴 1: 멀티 AI 비교
여러 AI에게 같은 작업을 주고 결과를 비교합니다.

**사용 사례**: 다양한 관점의 솔루션 비교, 최적 답변 선택

**YAML 예제:**
```yaml
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000
  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: compare-solutions
    description: "여러 AI로 같은 문제 해결"
    executionMode: PARALLEL
    stages:
      - id: claude-solution
        agent:
          name: claude
        input: "N까지의 소수를 찾는 함수를 작성해주세요"
      
      - id: gemini-solution
        agent:
          name: gemini
        input: "N까지의 소수를 찾는 함수를 작성해주세요"

security:
  useWhitelist: true
  allowedExecutables: [claude, gemini]
  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin]
```

### 패턴 2: 순차 리뷰 체인
생성 → 리뷰 → 최적화 흐름으로 코드 품질을 향상시킵니다.

**사용 사례**: 코드 생성 후 단계별 개선, 품질 보증

**YAML 예제:**
```yaml
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000
  - name: codex
    pluginClass: com.cotor.data.plugin.CodexPlugin
    timeout: 60000
  - name: copilot
    pluginClass: com.cotor.data.plugin.CopilotPlugin
    timeout: 60000

pipelines:
  - name: review-chain
    description: "생성 → 리뷰 → 최적화"
    executionMode: SEQUENTIAL
    stages:
      - id: generate
        agent:
          name: claude
        input: "사용자 인증을 위한 REST API 엔드포인트를 만들어주세요"
      
      - id: review
        agent:
          name: codex
        # 이전 스테이지 출력이 자동으로 입력됨
      
      - id: optimize
        agent:
          name: copilot
        # 이전 스테이지 출력이 자동으로 입력됨

security:
  useWhitelist: true
  allowedExecutables: [claude, codex, copilot]
  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin]
```

### 패턴 3: 종합 코드 리뷰
다양한 관점에서 동시에 코드를 리뷰합니다.

**사용 사례**: 보안, 성능, 모범 사례 등 다각도 분석

**YAML 예제:**
```yaml
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000
  - name: codex
    pluginClass: com.cotor.data.plugin.CodexPlugin
    timeout: 60000
  - name: copilot
    pluginClass: com.cotor.data.plugin.CopilotPlugin
    timeout: 60000
  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: comprehensive-review
    description: "다각도 코드 리뷰"
    executionMode: PARALLEL
    stages:
      - id: security-review
        agent:
          name: claude
        input: "이 코드의 보안 취약점을 검토해주세요: [코드]"
      
      - id: performance-review
        agent:
          name: codex
        input: "이 코드의 성능 문제를 검토해주세요: [코드]"
      
      - id: best-practices
        agent:
          name: copilot
        input: "이 코드의 모범 사례를 검토해주세요: [코드]"
      
      - id: optimization
        agent:
          name: gemini
        input: "이 코드의 최적화 방안을 제안해주세요: [코드]"

security:
  useWhitelist: true
  allowedExecutables: [claude, codex, copilot, gemini]
  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin]
```

## 사용 가능한 AI 플러그인

### ClaudePlugin
- **클래스**: `com.cotor.data.plugin.ClaudePlugin`
- **명령어**: `claude --dangerously-skip-permissions --print`
- **상태**: ✅ 작동 확인
- **특징**: 파일 생성 지원, 자동 권한 승인

### GeminiPlugin
- **클래스**: `com.cotor.data.plugin.GeminiPlugin`
- **명령어**: `gemini --yolo`
- **상태**: ✅ 작동 확인
- **특징**: 파일 생성 지원, 자동 권한 승인

### CodexPlugin
- **클래스**: `com.cotor.data.plugin.CodexPlugin`
- **명령어**: `codex --dangerously-bypass-approvals-and-sandbox`
- **상태**: ⚠️ 터미널 필요 (비대화형 모드 미지원)
- **특징**: 대화형 터미널 필요

### CopilotPlugin
- **클래스**: `com.cotor.data.plugin.CopilotPlugin`
- **명령어**: `copilot -p --allow-all-tools`
- **상태**: ⚠️ 세션 기반 인증 필요
- **특징**: GitHub 인증 필요

### CursorPlugin
- **클래스**: `com.cotor.data.plugin.CursorPlugin`
- **명령어**: `cursor-cli generate --auto-run`
- **상태**: 🔄 테스트 필요

### OpenCodePlugin
- **클래스**: `com.cotor.data.plugin.OpenCodePlugin`
- **명령어**: `opencode generate`
- **상태**: 🔄 테스트 필요

### OpenAIPlugin
- **클래스**: `com.cotor.data.plugin.OpenAIPlugin`
- **연동**: OpenAI HTTP API (Chat Completions)
- **상태**: 🔄 API 키 필요 (`OPENAI_API_KEY`)
- **특징**: 외부 CLI 설치 없이 사용 가능 (네트워크 필요)

### CommandPlugin (Generic Sub-Agent)
- **클래스**: `com.cotor.data.plugin.CommandPlugin`
- **연동**: 임의 CLI 실행 (argvJson으로 설정)
- **상태**: ✅ 로컬 커맨드 기반 서브 에이전트 확장
- **특징**: Kotlin 코드 없이 서브 에이전트를 무제한 추가 가능

## 템플릿

### compare-solutions
**설명**: 여러 AI로부터 같은 문제에 대한 다른 해결책을 받습니다.

**사용 시기**:
- 다양한 구현 방법 비교
- 최적 솔루션 선택
- AI 성능 벤치마크

**파일**: `~/.claude/templates/compare-solutions.yaml`

### review-chain
**설명**: 순차적 코드 리뷰 및 개선 체인입니다.

**사용 시기**:
- 코드 생성 후 단계별 개선
- 품질 보증 프로세스
- 점진적 최적화

**파일**: `~/.claude/templates/review-chain.yaml`

### comprehensive-review
**설명**: 다각도 병렬 코드 리뷰입니다.

**사용 시기**:
- 종합적인 코드 분석
- 보안, 성능, 모범 사례 동시 검토
- 프로덕션 배포 전 검증

**파일**: `~/.claude/templates/comprehensive-review.yaml`

## 모범 사례

### 1. 타임아웃 설정
- 간단한 작업: 30000ms (30초)
- 일반 작업: 60000ms (60초)
- 복잡한 작업: 120000ms (2분)

### 2. 보안 설정
- 항상 `useWhitelist: true` 사용
- 필요한 실행 파일만 `allowedExecutables`에 추가
- 신뢰할 수 있는 디렉토리만 `allowedDirectories`에 추가

### 3. 실행 모드 선택
- 독립적인 작업: `PARALLEL` (빠른 실행)
- 순차적 의존성: `SEQUENTIAL` (이전 출력이 다음 입력)
- 복잡한 의존성: `DAG` (세밀한 제어)

### 4. 에러 처리
- 각 에이전트에 적절한 타임아웃 설정
- 실패 시 로그 확인: `cotor.log`
- `--debug` 플래그로 상세 정보 확인

## 문제 해결

### 파이프라인 실행 실패
1. `cotor validate pipeline.yaml`로 구문 확인
2. 로그 파일 확인: `cat cotor.log`
3. 에이전트 타임아웃 증가
4. 보안 설정에서 실행 파일 허용 확인

### AI 에이전트 응답 없음
1. AI CLI 도구가 설치되어 있는지 확인
2. 인증이 필요한 경우 로그인 상태 확인
3. 타임아웃 설정 증가
4. 수동으로 명령어 실행하여 테스트

### YAML 구문 오류
1. 들여쓰기 확인 (스페이스 2칸)
2. 필수 필드 누락 확인
3. 문자열에 특수문자가 있으면 따옴표 사용
4. `cotor validate`로 검증

## 추가 리소스

- **공식 문서**: README.md, README.ko.md
- **예제**: `test/` 디렉토리
- **템플릿**: `~/.claude/templates/`
- **로그**: `cotor.log`
