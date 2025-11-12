# Cotor - AI CLI 마스터-에이전트 시스템

[![English](https://img.shields.io/badge/Language-English-blue)](README.md)
[![한국어](https://img.shields.io/badge/Language-한국어-red)](README.ko.md)

Cotor는 여러 AI 도구를 통합 인터페이스로 관리하는 Kotlin 기반 AI CLI 오케스트레이션 시스템입니다. 코루틴을 활용한 고성능 비동기 실행을 제공합니다.

## ✨ 주요 기능

- 🚀 **코루틴 기반 비동기**: 고성능 병렬 실행
- 🔌 **플러그인 아키텍처**: 새로운 AI 도구 쉽게 통합
- 🔄 **유연한 오케스트레이션**: 순차, 병렬, DAG 기반 파이프라인
- 🔐 **보안 우선**: Whitelist 기반 명령 검증
- 📊 **모니터링**: 내장 로깅 및 메트릭
- 🎯 **다양한 형식**: JSON, CSV, 텍스트 출력

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

## 🤖 내장 AI 플러그인

Cotor는 다음 AI CLI 도구들과 통합됩니다:

| AI | 명령어 | 설명 |
|----|--------|------|
| **Claude** | `claude --print <prompt>` | Anthropic의 고급 AI |
| **Codex** | `codex exec <prompt>` | Codex AI 코드 생성 |
| **Copilot** | `copilot -p <prompt> --allow-all-tools` | GitHub AI 어시스턴트 |
| **Gemini** | `gemini --yolo <prompt>` | Google 멀티모달 AI |
| **Cursor** | `cursor-cli generate <prompt>` | Cursor AI 에디터 |
| **OpenCode** | `opencode generate <prompt>` | 오픈소스 AI |

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
```

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

```bash
# 테스트 실행
./gradlew test

# 커버리지 리포트 생성
./gradlew jacocoTestReport

# 빌드
./gradlew shadowJar
```

## 📝 예제 출력

```
================================================================================
Pipeline Execution Results
================================================================================

Summary:
  Total Agents:  3
  Success Count: 3
  Failure Count: 0
  Total Duration: 26000ms

Agent Results:

  [1] claude
      Status:   ✓ SUCCESS
      Duration: 17933ms
      Output:
        Python "Hello, World!" 프로그램을 생성했습니다...

  [2] copilot
      Status:   ✓ SUCCESS
      Duration: 12963ms
      Output:
        간단한 console.log로 `hello-world.js`를 생성했습니다...

  [3] gemini
      Status:   ✓ SUCCESS
      Duration: 25800ms
      Output:
        `hello.go` 파일을 생성했습니다...

================================================================================
```

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

## 💡 팁

- 상세한 실행 로그를 보려면 `--debug` 플래그 사용
- 시스템 리소스에 맞춰 `maxConcurrentAgents` 설정
- 독립적인 작업에는 `PARALLEL` 모드 사용
- 출력이 다음 단계의 입력이 되는 경우 `SEQUENTIAL` 모드 사용
- 복잡한 의존성이 있는 경우 `DAG` 모드 사용

---

**Kotlin과 Coroutines로 만든 ❤️**
