# Cotor - AI CLI 마스터-에이전트 시스템

[![English](https://img.shields.io/badge/Language-English-blue)](README.md)
[![한국어](https://img.shields.io/badge/Language-한국어-red)](README.ko.md)

Cotor는 여러 AI 도구를 통합 인터페이스로 관리하는 Kotlin 기반 AI CLI 오케스트레이션 시스템입니다. 코루틴을 활용한 고성능 비동기 실행과 타임라인 기반 모니터링을 제공합니다.

## 🎉 새로워진 점 (v1.0.1 - 2025-11-20)

### 🚀 최신 개선사항
- ✨ **파이프라인 템플릿** – 새로운 `cotor template` 커맨드로 몇 초 만에 즉시 사용 가능한 파이프라인 생성!
  - 5가지 내장 템플릿: `compare`, `chain`, `review`, `consensus`, `custom`
  - 파이프라인 작성 시간 83% 단축 (30분 → 5분)
  - 초보자와 빠른 프로토타이핑에 최적
- 🔄 **모니터링 개선** – 스마트 상태 추적으로 중복 출력 감소
- 📚 **문서 강화** – IMPROVEMENTS.md, TEST_REPORT.md, CHANGELOG.md 추가

### CLI & Codex 대시보드
- ✅ **스테이지 타임라인** – `cotor run`이 단계별 진행 상황과 요약을 컬러로 출력합니다.
- ✅ **Codex 스타일 TUI** – `cotor dash -c <config>`에서 파이프라인을 골라 실행하고, 타임라인을 즉시 확인할 수 있습니다.
- ✅ **구독 관리 자동화** – 실행 후 이벤트 핸들러가 자동으로 해제되어 모니터가 안정적으로 동작합니다.

### 웹 파이프라인 스튜디오
- 🧱 **새 대시보드** – 히어로 배너, 통계 카드, 검색 가능한 카드 그리드 등 모던 UI로 재구성했습니다.
- 🪄 **AI 작업 빌더** – 브라우저에서 AI 작업을 쌓아 YAML을 생성하고 `test/` 폴더에 저장합니다.
- 📡 **실행 피드백** – `/api/run/<pipeline>`이 스테이지 타임라인까지 반환해 웹 UI에서도 실행 과정을 시각화합니다.

### 안정성 및 검증
- 🔁 **TimelineCollector** – CLI, 대시보드, 웹에서 동일한 실행 기록을 공유합니다.
- ♻️ **DAG 검증 개선** – 실제 순환 검출과 크리티컬 경로 기반 드라이런 시간을 제공합니다.
- ✅ **단위 테스트** – Validator, Recovery, Output Validator, Timeline 등이 테스트로 보호됩니다.

👉 **[업그레이드 가이드](docs/UPGRADE_GUIDE.md)** - 마이그레이션 상세 정보
👉 **[변경 이력](CHANGELOG.md)** - 상세 버전 히스토리
👉 **[테스트 리포트](TEST_REPORT.md)** - 검증 결과

## ✨ 주요 기능

- 🚀 **코루틴 기반 비동기**: 고성능 병렬 실행
- 🔌 **플러그인 아키텍처**: 새로운 AI 도구 쉽게 통합
- 🔄 **유연한 오케스트레이션**: 순차, 병렬, DAG 기반 파이프라인
- 🔐 **보안 우선**: Whitelist 기반 명령 검증
- 📊 **모니터링**: 내장 로깅 및 메트릭
- 🎯 **다양한 형식**: JSON, CSV, 텍스트 출력
- 🌐 **웹 UI**: 브라우저에서 파이프라인 실행 및 관리
- ⚡ **간단한 CLI**: `codex` 스타일의 직관적인 명령어
- 🤖 **자동 권한**: AI 도구별 자동 승인 플래그 지원

## 📦 설치

### 빠른 설치 (권장)

```bash
git clone https://github.com/yourusername/cotor.git
cd cotor
./install-global.sh
```

자동으로:
- ✅ 프로젝트 빌드
- ✅ `cotor` 명령어 전역 설치
- ✅ 어디서나 사용 가능

### 수동 설치

```bash
./gradlew shadowJar
chmod +x cotor
ln -s $(pwd)/cotor /usr/local/bin/cotor
```

### Claude Code 통합 (선택사항)

Claude Code에서 cotor를 원활하게 사용할 수 있도록 전역 슬래시 커맨드를 설치합니다:

```bash
./install-claude-integration.sh
```

설치되는 항목:
- ✅ `/cotor-generate` - 목표에서 파이프라인 자동 생성
- ✅ `/cotor-execute` - 모니터링과 함께 파이프라인 실행
- ✅ `/cotor-validate` - 파이프라인 구문 검증
- ✅ `/cotor-template` - 템플릿에서 파이프라인 생성
- ✅ Claude가 cotor를 이해하도록 하는 전역 지식 베이스

**모든 프로젝트에서 사용 가능**: 한 번 설치하면 어떤 프로젝트에서든 이 커맨드들을 사용할 수 있습니다!

📖 **[상세 설정 가이드](docs/CLAUDE_SETUP.md)** - 수동 설치 및 문제 해결

## 🤖 내장 AI 플러그인

Cotor는 다음 AI CLI 도구들과 통합되며, **자동 권한 승인**을 지원합니다:

| AI | 명령어 | 자동 승인 플래그 | 상태 |
|----|--------|------------------|------|
| **Claude** | `claude --dangerously-skip-permissions --print` | ✅ | ✅ 작동 확인 |
| **Codex** | `codex --dangerously-bypass-approvals-and-sandbox` | ⚠️ | ⚠️ 터미널 필요 (비대화형 모드 미지원) |
| **Copilot** | `copilot -p --allow-all-tools` | ⚠️ | ⚠️ 세션 기반 인증 필요 |
| **Gemini** | `gemini --yolo` | ✅ | ✅ 작동 확인 |
| **Cursor** | `cursor-cli generate --auto-run` | ✅ | 🔄 테스트 필요 |
| **OpenCode** | `opencode generate` | ✅ | 🔄 테스트 필요 |

> **⚠️ 주의**: 
> - 자동 승인 플래그는 신뢰된 환경에서만 사용하세요. 
> - Claude와 Gemini는 파일 생성이 확인되었습니다.
> - Codex는 대화형 터미널이 필요하여 자동화 파이프라인에서 사용이 제한됩니다.

### AI CLI 설치

```bash
# Claude (접근 권한이 있는 경우)
# Anthropic에서 설치

# GitHub Copilot
# Copilot CLI가 있으면 이미 설치됨

# Gemini
# Google AI에서 설치

# OpenAI
pip install openai

# 필요에 따라 다른 도구들
```

## 🚀 빠른 시작

### 1. 초기화

```bash
cotor init
```

`cotor.yaml` 설정 파일이 생성됩니다.

### 2. 설정 파일 작성

```yaml
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000

  - name: copilot
    pluginClass: com.cotor.data.plugin.CopilotPlugin
    timeout: 60000

  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: code-review
    description: "멀티 AI 코드 리뷰"
    executionMode: PARALLEL
    stages:
      - id: claude-review
        agent:
          name: claude
        input: "이 코드의 모범 사례를 검토해주세요"

      - id: copilot-review
        agent:
          name: copilot
        input: "이 코드의 버그를 검토해주세요"

      - id: gemini-review
        agent:
          name: gemini
        input: "이 코드의 성능을 검토해주세요"

security:
  useWhitelist: true
  allowedExecutables:
    - claude
    - copilot
    - gemini
  allowedDirectories:
    - /usr/local/bin
    - /opt/homebrew/bin

logging:
  level: INFO
  file: cotor.log

performance:
  maxConcurrentAgents: 10
```

### 3. 파이프라인 실행

```bash
# 사용 가능한 에이전트 목록
cotor list

# 파이프라인 실행
cotor run code-review --output-format text

# 특정 설정 파일로 실행
cotor run code-review --config my-config.yaml

# Codex 대시보드 실행
cotor dash -c test/test-codex/config/codex-demo.yaml

# 웹 파이프라인 스튜디오 실행
cotor web
# 브라우저에서 http://localhost:8080
```

### Codex 실험 샌드박스

- `test/test-codex/config/codex-demo.yaml` – Echo 에이전트로 구성된 순차/그래프 데모 파이프라인.
- `test/test-codex/{runs,artifacts,templates}` – 결과물과 템플릿, 실행 메타데이터를 보관하는 디렉터리.
- `./cotor dash -c test/test-codex/config/codex-demo.yaml` – 반복 실행하며 타임라인을 확인하는 Codex 전용 UI.

### 웹 파이프라인 스튜디오

```
cotor web
# http://localhost:8080 접속
```

좌측 빌더에서 AI 작업을 추가하고 “생성 후 실행”을 누르면 YAML이 저장되고 실행 결과가 실시간으로 표시됩니다. 우측 카드 목록은 자동으로 탐지된 YAML 파일을 검색/정렬해 보여줍니다.

## 📖 사용 예제

### 예제 1: 단일 AI 작업

```bash
# 간단한 파이프라인 생성
cat > single-ai.yaml << EOF
version: "1.0"
agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000

pipelines:
  - name: generate-code
    executionMode: SEQUENTIAL
    stages:
      - id: generate
        agent:
          name: claude
        input: "Python hello world 함수를 만들어주세요"

security:
  useWhitelist: true
  allowedExecutables: [claude]
  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin]
EOF

# 실행
cotor run generate-code --config single-ai.yaml
```

### 예제 2: 여러 AI 병렬 실행 (같은 작업)

같은 문제에 대해 다양한 관점 얻기:

```bash
cat > multi-compare.yaml << EOF
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000
  - name: codex
    pluginClass: com.cotor.data.plugin.CodexPlugin
    timeout: 60000
  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: compare-solutions
    description: "3가지 다른 구현 받기"
    executionMode: PARALLEL
    stages:
      - id: claude-solution
        agent:
          name: claude
        input: "N까지의 소수를 찾는 함수를 작성해주세요"
      
      - id: codex-solution
        agent:
          name: codex
        input: "N까지의 소수를 찾는 함수를 작성해주세요"
      
      - id: gemini-solution
        agent:
          name: gemini
        input: "N까지의 소수를 찾는 함수를 작성해주세요"

security:
  useWhitelist: true
  allowedExecutables: [claude, codex, gemini]
  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin]
EOF

# 실행하고 결과 비교
cotor run compare-solutions --config multi-compare.yaml --output-format text
```

**결과**: 3가지 다른 구현을 동시에 받습니다!

### 예제 3: 순차 AI 파이프라인 (리뷰 체인)

한 AI의 출력이 다음 AI의 입력이 됩니다:

```bash
cat > review-chain.yaml << EOF
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
  - name: code-review-chain
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
        # Claude의 출력이 입력으로 사용됨
      
      - id: optimize
        agent:
          name: copilot
        # Codex의 리뷰된 코드가 입력으로 사용됨

security:
  useWhitelist: true
  allowedExecutables: [claude, codex, copilot]
  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin]
EOF

# 체인 실행
cotor run code-review-chain --config review-chain.yaml --output-format text
```

**흐름**: Claude 생성 → Codex 리뷰 → Copilot 최적화

### 예제 4: 멀티 AI 코드 리뷰

여러 AI로부터 종합적인 피드백 받기:

```bash
cat > code-review.yaml << EOF
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
EOF

# 4가지 다른 리뷰를 동시에 받기
cotor run comprehensive-review --config code-review.yaml --output-format text
```

**결과**: 4개의 AI가 다른 관점에서 코드를 리뷰 - 모두 동시에!

### 예제 5: AI 합의 도출

여러 AI를 사용하여 합의 도출:

```bash
cat > consensus.yaml << EOF
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000
  - name: codex
    pluginClass: com.cotor.data.plugin.CodexPlugin
    timeout: 60000
  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: architecture-decision
    description: "아키텍처 추천 받기"
    executionMode: PARALLEL
    stages:
      - id: claude-opinion
        agent:
          name: claude
        input: "실시간 채팅 앱을 위한 최적의 아키텍처는?"
      
      - id: codex-opinion
        agent:
          name: codex
        input: "실시간 채팅 앱을 위한 최적의 아키텍처는?"
      
      - id: gemini-opinion
        agent:
          name: gemini
        input: "실시간 채팅 앱을 위한 최적의 아키텍처는?"

security:
  useWhitelist: true
  allowedExecutables: [claude, codex, gemini]
  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin]
EOF

# 추천 비교
cotor run architecture-decision --config consensus.yaml --output-format text
```

**활용**: 다양한 AI 의견을 비교하여 더 나은 결정을 내리세요!

## 🎯 CLI 명령어

### 템플릿으로 빠른 시작 ⭐ 신규

```bash
# 사용 가능한 템플릿 목록
cotor template

# 템플릿에서 파이프라인 생성 (수동 작성보다 83% 빠름!)
cotor template compare my-pipeline.yaml
cotor template chain review-flow.yaml
cotor template review code-review.yaml
cotor template consensus decision.yaml
cotor template custom my-custom.yaml

# 사용 가능한 템플릿:
#   compare   - 여러 AI가 같은 문제를 병렬로 해결
#   chain     - 순차적 처리 (생성 → 리뷰 → 최적화)
#   review    - 병렬 다각도 코드 리뷰
#   consensus - 여러 AI의 의견으로 의사 결정
#   custom    - 커스터마이징 가능한 기본 템플릿
```

### 간단한 모드 (codex 스타일)

```bash
# 파이프라인 직접 실행
cotor <pipeline-name> [config-file]

# 예시
cotor compare-solutions                    # cotor.yaml 사용
cotor creative-collab test/creative.yaml   # 특정 설정 파일 사용

# 웹 UI 시작
cotor web
```

### 전통적인 모드

```bash
# 설정 초기화
cotor init

# 등록된 에이전트 목록
cotor list [--config path/to/config.yaml]

# 파이프라인 실행
cotor run <pipeline-name> [options]
  --config <path>           설정 파일 (기본값: cotor.yaml)
  --output-format <format>  출력 형식: json, csv, text (기본값: json)
  --debug                   디버그 모드 활성화

# 상태 확인
cotor status

# 버전 정보
cotor version
```

## 🔧 커스텀 플러그인 생성

```kotlin
package com.cotor.data.plugin

import com.cotor.data.process.ProcessManager
import com.cotor.model.*

class MyAIPlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "my-ai",
        version = "1.0.0",
        description = "나만의 AI 통합",
        author = "Your Name",
        supportedFormats = listOf(DataFormat.TEXT)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): String {
        val prompt = context.input ?: throw IllegalArgumentException("입력 필요")
        
        // AI CLI 실행
        val command = listOf("my-ai-cli", prompt)
        
        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout
        )
        
        if (!result.isSuccess) {
            throw AgentExecutionException("실행 실패: ${result.stderr}")
        }
        
        return result.stdout
    }
}
```

`cotor.yaml`에 추가:

```yaml
agents:
  - name: my-ai
    pluginClass: com.cotor.data.plugin.MyAIPlugin
    timeout: 30000

security:
  allowedExecutables:
    - my-ai-cli
```

## 🏗️ 아키텍처

```
┌─────────────────────────────────────┐
│      Presentation Layer             │
│  (CLI, 명령어, 포맷터)              │
└─────────────────────────────────────┘
              ↓
┌─────────────────────────────────────┐
│       Domain Layer                  │
│  (오케스트레이션, 실행)             │
└─────────────────────────────────────┘
              ↓
┌─────────────────────────────────────┐
│        Data Layer                   │
│  (레지스트리, 설정, 프로세스)       │
└─────────────────────────────────────┘
```

## 🔒 보안

- **Whitelist 검증**: 승인된 실행 파일만 실행
- **명령 인젝션 방지**: 입력 검증
- **경로 검증**: 허용된 디렉토리로 제한
- **환경 보호**: 위험한 변수 차단

## 📊 성능

- **병렬 실행**: 여러 AI 동시 실행
- **코루틴 기반**: 경량 동시성
- **리소스 관리**: 메모리 모니터링 및 제한
- **타임아웃 설정**: 프로세스 중단 방지

## 🧪 테스트

### 단위 테스트
```bash
# 테스트 실행
./gradlew test

# 커버리지 리포트 생성
./gradlew jacocoTestReport

# 빌드
./gradlew shadowJar
```

### 파이프라인 테스트

실제 예제(게시판 CRUD 기능)로 cotor 테스트:

```bash
./test-cotor-pipeline.sh
```

이 스크립트는:
1. 게시판 구현 파이프라인이 있는 테스트 디렉토리 생성
2. Claude와 Gemini로 파이프라인 실행
3. 완전한 CRUD 구현 생성
4. 테스트 및 문서 생성

**예상 결과물:**
- `requirements.md` - 요구사항 및 설계
- `Board.kt` - Entity 클래스
- `BoardRepository.kt` - Repository 인터페이스
- `BoardService.kt` - Service 레이어
- `BoardController.kt` - REST 컨트롤러
- `code-review.md` - 코드 리뷰 피드백
- `BoardServiceTest.kt` - 단위 테스트
- `README.md` - 완전한 문서

## 📝 예제 출력

### 간단한 CLI 출력

```bash
$ cotor compare-solutions test/multi-compare.yaml

🚀 Running: compare-solutions

✅ Completed in 48237ms
   Success: 3/3

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📦 claude (28400ms)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
N까지의 소수를 찾는 JavaScript 함수를 작성했습니다.
[코드 출력...]

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📦 codex (4781ms)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
소수 판별을 위해 에라토스테네스 체를 사용합니다.
[코드 출력...]

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📦 gemini (13881ms)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Python으로 소수 찾기 함수를 구현했습니다.
[코드 출력...]
```

### 전통적인 CLI 출력

```
================================================================================
Pipeline Execution Results
================================================================================

Summary:
  Total Agents:  3
  Success Count: 3
  Failure Count: 0
  Total Duration: 48237ms

Agent Results:

  [1] claude
      Status:   ✓ SUCCESS
      Duration: 28400ms
      Output:
        N까지의 소수를 찾는 JavaScript 함수를 작성했습니다...

  [2] codex
      Status:   ✓ SUCCESS
      Duration: 4781ms
      Output:
        소수 판별을 위해 에라토스테네스 체를 사용합니다...

  [3] gemini
      Status:   ✓ SUCCESS
      Duration: 13881ms
      Output:
        Python으로 소수 찾기 함수를 구현했습니다...

================================================================================
```

## 🌐 웹 UI

```bash
$ cotor web
🌐 Starting Cotor Web UI...
   Open http://localhost:8080 in your browser
```

브라우저에서 파이프라인을 시각적으로 관리하고 실행할 수 있습니다:
- 📋 파이프라인 목록 보기
- ▶️ 클릭 한 번으로 실행
- 📊 실시간 결과 확인
- 🎨 깔끔한 UI

## 🤝 기여하기

1. 저장소 포크
2. 기능 브랜치 생성
3. 변경사항 작성
4. 테스트 추가
5. Pull Request 제출

## 📄 라이선스

[라이선스 정보 추가]

## 🔗 링크

- [문서](docs/)
- [예제](examples/)
- [이슈](https://github.com/yourusername/cotor/issues)
- [업그레이드 권장사항](docs/UPGRADE_RECOMMENDATIONS.md) - 향후 개선 사항
- [Claude 설정 가이드](docs/CLAUDE_SETUP.md) - Claude Code 통합

## 💡 팁

- 상세한 실행 로그를 보려면 `--debug` 플래그 사용
- 시스템 리소스에 맞춰 `maxConcurrentAgents` 설정
- 독립적인 작업에는 `PARALLEL` 모드 사용
- 출력이 다음 단계의 입력이 되는 경우 `SEQUENTIAL` 모드 사용
- 복잡한 의존성이 있는 경우 `DAG` 모드 사용
- 빠른 실행에는 간단한 CLI 사용: `cotor <pipeline-name>`
- 시각적 관리에는 웹 UI 사용: `cotor web`

## 🎨 Claude Code 통합

Claude 통합을 설치했다면, **모든 프로젝트**에서 다음 슬래시 커맨드를 사용할 수 있습니다:

### 사용 가능한 커맨드

| 커맨드 | 설명 | 예시 |
|--------|------|------|
| `/cotor-generate` | 목표에서 파이프라인 자동 생성 | `/cotor-generate "3개의 AI로 정렬 알고리즘 비교"` |
| `/cotor-execute` | 모니터링과 함께 파이프라인 실행 | `/cotor-execute pipeline.yaml` |
| `/cotor-validate` | 파이프라인 구문 검증 | `/cotor-validate pipeline.yaml` |
| `/cotor-template` | 템플릿에서 파이프라인 생성 | `/cotor-template compare-solutions my-pipeline.yaml` |

### 빠른 시작

**1. 사용 가능한 템플릿 목록:**
```
/cotor-template
```

**2. 템플릿에서 생성:**
```
/cotor-template compare-solutions test.yaml
```

**3. 검증:**
```
/cotor-validate test.yaml
```

**4. 실행:**
```
/cotor-execute test.yaml
```

### 사용 가능한 템플릿

- **compare-solutions**: 여러 AI가 같은 문제를 병렬로 해결
- **review-chain**: 순차적 코드 리뷰 (생성 → 리뷰 → 최적화)
- **comprehensive-review**: 병렬 다각도 리뷰 (보안, 성능, 모범 사례)

### 지식 베이스

Claude는 `~/.claude/steering/cotor-knowledge.md`의 전역 지식 베이스를 통해 cotor를 자동으로 이해합니다:
- ✅ Cotor 명령어와 구문
- ✅ 파이프라인 패턴과 모범 사례
- ✅ AI 플러그인 정보
- ✅ 문제 해결 가이드

### 검증

설치 테스트:
```bash
./test-claude-integration.sh
```

모든 테스트가 통과해야 합니다 ✅

## 🧪 테스트 결과

### Compare Solutions (소수 찾기)
- **실행 시간**: 48.2초
- **성공률**: 67% (2/3)
- **결과**: 
  - ✅ Claude: `findPrimes.js` 생성 완료
  - ❌ Codex: 터미널 필요 (비대화형 모드 미지원)
  - ✅ Gemini: `primes.py` 생성 완료
- **상세**: [테스트 결과 보기](test/results/compare-solutions-result.md)

### Creative Collaboration (소설 창작)
- **실행 시간**: 125초
- **성공률**: 67% (2/3)
- **결과**:
  - ✅ Claude: `claude-story.md` - "침묵의 메시지" (SF)
  - ❌ Codex: 터미널 필요
  - ✅ Gemini: `gemini-story.md` - "프로젝트 제미니" (AI 감성)
- **상세**: [테스트 결과 보기](test/results/creative-collab-result.md)

### 생성된 파일 확인
```bash
ls -la test/results/
# findPrimes.js    - Claude가 생성한 JavaScript 소수 찾기
# primes.py        - Gemini가 생성한 Python 소수 찾기
# claude-story.md  - Claude의 SF 단편 소설
# gemini-story.md  - Gemini의 AI 감성 소설
```

---

**Kotlin과 Coroutines로 만든 ❤️**
