# Cotor - AI CLI 마스터-에이전트 시스템

[![English](https://img.shields.io/badge/Language-English-blue)](README.md)
[![한국어](https://img.shields.io/badge/Language-한국어-red)](README.ko.md)

Cotor는 여러 독립적인 AI CLI 툴을 통합 CLI 인터페이스로 오케스트레이션하는 Kotlin 기반 시스템입니다. 코루틴을 활용한 고성능 비동기 실행으로 AI 워크플로우를 관리하는 유연하고 확장 가능한 프레임워크를 제공합니다.

## 주요 기능

- **🚀 코루틴 기반 비동기 실행**: 모든 I/O 작업과 에이전트 실행에 Kotlin 코루틴 사용으로 최적의 성능 제공
- **🔌 플러그인 아키텍처**: 간단한 플러그인 인터페이스로 새로운 AI 툴을 쉽게 추가
- **🔄 유연한 오케스트레이션**: 순차, 병렬, DAG 기반 파이프라인 실행 지원
- **🔐 보안 우선**: Whitelist 기반 명령 검증 및 인젝션 공격 방지
- **📊 모니터링 & 메트릭**: 내장된 로깅, 메트릭 수집, 성능 모니터링
- **⚙️ 설정 관리**: YAML 및 JSON 설정 파일 지원
- **🎯 다양한 출력 형식**: JSON, CSV, 사람이 읽기 쉬운 텍스트 출력

## 요구사항

- JDK 17 이상
- Gradle 8.0 이상
- Kotlin 1.9+

## 빠른 시작

### 간편 설치 (권장)

```bash
# 저장소 클론
git clone https://github.com/yourusername/cotor.git
cd cotor

# 설치 스크립트 실행
./install.sh
```

설치 스크립트가 자동으로:
- ✅ Java 설치 확인
- ✅ 프로젝트 빌드
- ✅ 실행 스크립트 생성
- ✅ PATH 추가 방법 안내

### 수동 설치

**1. 프로젝트 빌드**

```bash
./gradlew shadowJar
```

**2. 스크립트 실행 권한 부여**

```bash
chmod +x cotor
```

**3. Cotor 사용**

```bash
# 직접 실행
./cotor version

# 또는 PATH에 추가 (~/.bashrc 또는 ~/.zshrc에 추가)
export PATH="$PATH:/path/to/cotor"

# 이후 어디서나 사용 가능
cotor version
```

### 빠른 테스트

```bash
# 설정 초기화
./cotor init

# 사용 가능한 에이전트 목록
./cotor list

# 예제 파이프라인 실행
./cotor run example-pipeline

# 모든 AI 모델 테스트 (Claude, Codex, Copilot, Gemini, Cursor, OpenCode)
./cotor run test-all-models --config test-ai-models.yaml --output-format text
```

## 내장 AI 모델 플러그인

Cotor는 6개의 사전 구성된 AI 모델 플러그인을 제공합니다:

| 플러그인 | 설명 | 모델 | 제공자 |
|---------|------|------|--------|
| **Claude** | 고급 추론 및 코드 생성 | claude-3-opus | Anthropic |
| **Codex** | OpenAI의 코드 생성 모델 | gpt-4 | OpenAI |
| **Copilot** | GitHub의 AI 페어 프로그래머 | copilot | GitHub |
| **Gemini** | Google의 멀티모달 AI | gemini-pro | Google |
| **Cursor** | 지능형 코드 편집 | cursor | Cursor |
| **OpenCode** | 오픈소스 코드 생성 | opencode | Community |

### 모든 AI 모델 테스트

```bash
# 모든 모델을 병렬로 테스트
./cotor run test-all-models --config test-ai-models.yaml --output-format text

# 개별 모델 테스트
./cotor run test-claude --config test-ai-models.yaml
./cotor run test-codex --config test-ai-models.yaml
./cotor run test-copilot --config test-ai-models.yaml
./cotor run test-gemini --config test-ai-models.yaml
./cotor run test-cursor --config test-ai-models.yaml
./cotor run test-opencode --config test-ai-models.yaml

# 순차 워크플로우 테스트 (Claude → Codex → Gemini)
./cotor run test-sequential --config test-ai-models.yaml
```

### 예제 출력

```
================================================================================
Pipeline Execution Results
================================================================================

Summary:
  Total Agents:  6
  Success Count: 6
  Failure Count: 0
  Total Duration: 12ms
  Timestamp:     2025-11-12T11:23:00.000000Z

Agent Results:

  [1] claude
      Status:   ✓ SUCCESS
      Duration: 2ms
      Output:
        [Claude Response]
        Model: claude-3-opus-20240229
        Input: Generate a hello world function in Python
        ...

  [2] codex
      Status:   ✓ SUCCESS
      Duration: 2ms
      ...

  [3] copilot
      Status:   ✓ SUCCESS
      Duration: 2ms
      ...

  [4] gemini
      Status:   ✓ SUCCESS
      Duration: 2ms
      ...

  [5] cursor
      Status:   ✓ SUCCESS
      Duration: 2ms
      ...

  [6] opencode
      Status:   ✓ SUCCESS
      Duration: 2ms
      ...

================================================================================
```

## 사용자 플로우 예제

### 예제 1: 간단한 Echo 파이프라인

**1단계: 프로젝트 초기화**
```bash
# 새 디렉토리 생성
mkdir my-cotor-project
cd my-cotor-project

# Cotor 설정 초기화
java -jar /path/to/cotor-1.0.0.jar init
```

**2단계: 생성된 설정 확인**
```bash
cat cotor.yaml
```

기본 echo 에이전트와 파이프라인이 포함된 설정을 확인할 수 있습니다.

**3단계: 예제 파이프라인 실행**
```bash
# JSON 출력으로 실행 (기본값)
java -jar /path/to/cotor-1.0.0.jar run example-pipeline

# 가독성 좋은 텍스트 출력으로 실행
java -jar /path/to/cotor-1.0.0.jar run example-pipeline --output-format text

# CSV 출력으로 실행
java -jar /path/to/cotor-1.0.0.jar run example-pipeline --output-format csv
```

**예상 출력 (JSON 형식):**
```json
{
  "totalAgents": 1,
  "successCount": 1,
  "failureCount": 0,
  "totalDuration": 1,
  "timestamp": "2025-11-12T10:35:24.022014Z",
  "results": [
    {
      "agentName": "example-agent",
      "isSuccess": true,
      "output": "test input",
      "error": null,
      "duration": 1,
      "metadata": { "executedAt": "2025-11-12T10:35:24.021553Z" }
    }
  ]
}
```

### 예제 2: 커스텀 다단계 파이프라인

**1단계: 커스텀 설정 생성**

`cotor.yaml` 편집:

```yaml
version: "1.0"

agents:
  - name: data-processor
    pluginClass: com.cotor.data.plugin.EchoPlugin
    timeout: 30000
    parameters:
      mode: process
    tags:
      - data

  - name: data-analyzer
    pluginClass: com.cotor.data.plugin.EchoPlugin
    timeout: 30000
    parameters:
      mode: analyze
    tags:
      - analysis

pipelines:
  - name: data-workflow
    description: "데이터 처리 및 분석"
    executionMode: SEQUENTIAL
    stages:
      - id: process
        agent:
          name: data-processor
          pluginClass: com.cotor.data.plugin.EchoPlugin
        input: "raw data"
        
      - id: analyze
        agent:
          name: data-analyzer
          pluginClass: com.cotor.data.plugin.EchoPlugin
        # 이전 단계의 출력이 입력으로 사용됨

security:
  useWhitelist: false
  allowedExecutables: []
  allowedDirectories: []

logging:
  level: INFO
  file: cotor.log
  format: json

performance:
  maxConcurrentAgents: 10
  coroutinePoolSize: 8
```

**2단계: 사용 가능한 에이전트 목록 확인**
```bash
java -jar /path/to/cotor-1.0.0.jar list
```

**출력:**
```
Registered Agents (2):
  - data-processor (com.cotor.data.plugin.EchoPlugin)
    Timeout: 30000ms
    Tags: data
  - data-analyzer (com.cotor.data.plugin.EchoPlugin)
    Timeout: 30000ms
    Tags: analysis
```

**3단계: 다단계 파이프라인 실행**
```bash
java -jar /path/to/cotor-1.0.0.jar run data-workflow --output-format text
```

**출력:**
```
================================================================================
Pipeline Execution Results
================================================================================

Summary:
  Total Agents:  2
  Success Count: 2
  Failure Count: 0
  Total Duration: 5ms
  Timestamp:     2025-11-12T10:40:15.123456Z

Agent Results:

  [1] data-processor
      Status:   ✓ SUCCESS
      Duration: 2ms
      Output:
        raw data

  [2] data-analyzer
      Status:   ✓ SUCCESS
      Duration: 3ms
      Output:
        raw data

================================================================================
```

### 예제 3: 병렬 실행

**1단계: 병렬 파이프라인 설정 생성**

```yaml
pipelines:
  - name: parallel-analysis
    description: "여러 분석을 병렬로 실행"
    executionMode: PARALLEL
    stages:
      - id: analysis1
        agent:
          name: data-analyzer
          pluginClass: com.cotor.data.plugin.EchoPlugin
        input: "dataset 1"
        
      - id: analysis2
        agent:
          name: data-analyzer
          pluginClass: com.cotor.data.plugin.EchoPlugin
        input: "dataset 2"
        
      - id: analysis3
        agent:
          name: data-analyzer
          pluginClass: com.cotor.data.plugin.EchoPlugin
        input: "dataset 3"
```

**2단계: 병렬 파이프라인 실행**
```bash
java -jar /path/to/cotor-1.0.0.jar run parallel-analysis
```

세 개의 분석이 동시에 실행되어 전체 실행 시간이 크게 단축됩니다.

### 예제 4: DAG 기반 워크플로우

**1단계: 의존성이 있는 DAG 파이프라인 생성**

```yaml
pipelines:
  - name: dag-workflow
    description: "의존성이 있는 복잡한 워크플로우"
    executionMode: DAG
    stages:
      - id: fetch-data
        agent:
          name: data-processor
          pluginClass: com.cotor.data.plugin.EchoPlugin
        input: "fetch from source"
        
      - id: process-a
        agent:
          name: data-processor
          pluginClass: com.cotor.data.plugin.EchoPlugin
        dependencies:
          - fetch-data
          
      - id: process-b
        agent:
          name: data-processor
          pluginClass: com.cotor.data.plugin.EchoPlugin
        dependencies:
          - fetch-data
          
      - id: merge-results
        agent:
          name: data-analyzer
          pluginClass: com.cotor.data.plugin.EchoPlugin
        dependencies:
          - process-a
          - process-b
```

**2단계: DAG 파이프라인 실행**
```bash
java -jar /path/to/cotor-1.0.0.jar run dag-workflow --output-format text
```

실행 순서:
1. `fetch-data`가 먼저 실행
2. `fetch-data` 완료 후 `process-a`와 `process-b`가 병렬로 실행
3. `process-a`와 `process-b` 모두 완료 후 `merge-results` 실행

### 예제 5: 다른 설정 파일 사용

**1단계: 여러 설정 파일 생성**
```bash
# 개발 환경 설정
cp cotor.yaml cotor-dev.yaml

# 프로덕션 환경 설정
cp cotor.yaml cotor-prod.yaml
```

**2단계: 특정 설정으로 실행**
```bash
# 개발 설정 사용
java -jar /path/to/cotor-1.0.0.jar run example-pipeline --config cotor-dev.yaml

# 프로덕션 설정 사용
java -jar /path/to/cotor-1.0.0.jar run example-pipeline --config cotor-prod.yaml
```

### 예제 6: 모니터링 및 디버깅

**1단계: 디버그 모드 활성화**
```bash
java -jar /path/to/cotor-1.0.0.jar run example-pipeline --debug
```

상세한 실행 정보와 에러 발생 시 스택 트레이스를 확인할 수 있습니다.

**2단계: 로그 확인**
```bash
# 로그 파일 보기
cat cotor.log

# 실시간 로그 확인
tail -f cotor.log
```

**3단계: 파이프라인 상태 확인 (다른 터미널에서)**
```bash
java -jar /path/to/cotor-1.0.0.jar status
```

### 예제 7: 멀티 AI 모델 파이프라인 (Claude, Codex, Gemini, Copilot)

여러 AI 모델을 하나의 파이프라인에서 오케스트레이션하여 종합적인 코드 생성 및 리뷰를 수행하는 고급 예제입니다.

**사용 사례**: 여러 AI 모델로 코드를 생성하고 결과를 비교/병합

**1단계: AI 모델 에이전트 플러그인 생성**

각 AI 모델을 위한 래퍼 플러그인을 생성합니다:

```kotlin
// ClaudePlugin.kt
class ClaudePlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "claude-code-generator",
        version = "1.0.0",
        description = "코드 생성을 위한 Claude AI",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.JSON, DataFormat.TEXT)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): String {
        // Claude API 또는 CLI 호출
        val command = listOf(
            "claude-cli",
            "generate",
            "--prompt", context.input ?: ""
        )
        
        val result = processManager.executeProcess(
            command = command,
            input = context.input,
            environment = context.environment,
            timeout = context.timeout
        )
        
        return result.stdout
    }
}

// Codex, Gemini, Copilot을 위한 유사한 플러그인
class CodexPlugin : AgentPlugin { /* ... */ }
class GeminiPlugin : AgentPlugin { /* ... */ }
class CopilotPlugin : AgentPlugin { /* ... */ }
```

**2단계: 멀티 AI 파이프라인 설정**

`multi-ai-pipeline.yaml` 생성:

```yaml
version: "1.0"

agents:
  - name: claude-agent
    pluginClass: com.cotor.plugins.ClaudePlugin
    timeout: 60000
    parameters:
      model: claude-3-opus
      temperature: "0.7"
    tags:
      - ai
      - code-generation
      - claude

  - name: codex-agent
    pluginClass: com.cotor.plugins.CodexPlugin
    timeout: 60000
    parameters:
      model: gpt-4
      temperature: "0.5"
    tags:
      - ai
      - code-generation
      - openai

  - name: gemini-agent
    pluginClass: com.cotor.plugins.GeminiPlugin
    timeout: 60000
    parameters:
      model: gemini-pro
      temperature: "0.6"
    tags:
      - ai
      - code-generation
      - google

  - name: copilot-agent
    pluginClass: com.cotor.plugins.CopilotPlugin
    timeout: 60000
    parameters:
      model: copilot
    tags:
      - ai
      - code-generation
      - github

  - name: code-merger
    pluginClass: com.cotor.plugins.CodeMergerPlugin
    timeout: 30000
    tags:
      - utility

pipelines:
  # 병렬 실행 - 모든 AI 모델이 동시에 코드 생성
  - name: multi-ai-parallel
    description: "여러 AI 모델로 병렬 코드 생성"
    executionMode: PARALLEL
    stages:
      - id: claude-generation
        agent:
          name: claude-agent
          pluginClass: com.cotor.plugins.ClaudePlugin
        input: "JWT를 사용한 사용자 인증을 위한 REST API 엔드포인트 생성"

      - id: codex-generation
        agent:
          name: codex-agent
          pluginClass: com.cotor.plugins.CodexPlugin
        input: "JWT를 사용한 사용자 인증을 위한 REST API 엔드포인트 생성"

      - id: gemini-generation
        agent:
          name: gemini-agent
          pluginClass: com.cotor.plugins.GeminiPlugin
        input: "JWT를 사용한 사용자 인증을 위한 REST API 엔드포인트 생성"

      - id: copilot-generation
        agent:
          name: copilot-agent
          pluginClass: com.cotor.plugins.CopilotPlugin
        input: "JWT를 사용한 사용자 인증을 위한 REST API 엔드포인트 생성"

  # 순차 실행 - 리뷰 체인
  - name: multi-ai-review-chain
    description: "여러 AI 모델을 통한 코드 생성 및 리뷰"
    executionMode: SEQUENTIAL
    stages:
      - id: initial-generation
        agent:
          name: claude-agent
          pluginClass: com.cotor.plugins.ClaudePlugin
        input: "JWT를 사용한 사용자 인증을 위한 REST API 엔드포인트 생성"

      - id: codex-review
        agent:
          name: codex-agent
          pluginClass: com.cotor.plugins.CodexPlugin
          parameters:
            task: review
        # Claude의 출력이 입력으로 사용됨

      - id: gemini-optimization
        agent:
          name: gemini-agent
          pluginClass: com.cotor.plugins.GeminiPlugin
          parameters:
            task: optimize
        # Codex의 리뷰된 코드가 입력으로 사용됨

      - id: copilot-final-check
        agent:
          name: copilot-agent
          pluginClass: com.cotor.plugins.CopilotPlugin
          parameters:
            task: security-check
        # Gemini의 최적화된 코드가 입력으로 사용됨

  # DAG 기반 워크플로우 - 복잡한 의존성
  - name: multi-ai-dag
    description: "의존성이 있는 복잡한 AI 워크플로우"
    executionMode: DAG
    stages:
      - id: requirement-analysis
        agent:
          name: claude-agent
          pluginClass: com.cotor.plugins.ClaudePlugin
        input: "사용자 인증 시스템 요구사항 분석"

      - id: architecture-design
        agent:
          name: gemini-agent
          pluginClass: com.cotor.plugins.GeminiPlugin
        dependencies:
          - requirement-analysis

      - id: backend-code
        agent:
          name: codex-agent
          pluginClass: com.cotor.plugins.CodexPlugin
        dependencies:
          - architecture-design

      - id: frontend-code
        agent:
          name: copilot-agent
          pluginClass: com.cotor.plugins.CopilotPlugin
        dependencies:
          - architecture-design

      - id: integration-code
        agent:
          name: claude-agent
          pluginClass: com.cotor.plugins.ClaudePlugin
        dependencies:
          - backend-code
          - frontend-code

      - id: final-review
        agent:
          name: gemini-agent
          pluginClass: com.cotor.plugins.GeminiPlugin
        dependencies:
          - integration-code

security:
  useWhitelist: true
  allowedExecutables:
    - claude-cli
    - openai
    - gemini-cli
    - gh
  allowedDirectories:
    - /usr/local/bin
    - /opt/ai-tools

logging:
  level: INFO
  file: multi-ai.log
  format: json

performance:
  maxConcurrentAgents: 4
  coroutinePoolSize: 8
```

**3단계: 병렬 AI 생성 실행**

```bash
# 4개의 AI 모델로 동시에 코드 생성
java -jar cotor-1.0.0.jar run multi-ai-parallel \
  --config multi-ai-pipeline.yaml \
  --output-format text
```

**예상 출력:**
```
================================================================================
Pipeline Execution Results
================================================================================

Summary:
  Total Agents:  4
  Success Count: 4
  Failure Count: 0
  Total Duration: 8500ms
  Timestamp:     2025-11-12T11:00:00.000000Z

Agent Results:

  [1] claude-agent
      Status:   ✓ SUCCESS
      Duration: 8200ms
      Output:
        // Claude의 구현
        @RestController
        @RequestMapping("/api/auth")
        public class AuthController {
            @PostMapping("/login")
            public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request) {
                // JWT 인증 로직
                ...
            }
        }

  [2] codex-agent
      Status:   ✓ SUCCESS
      Duration: 7800ms
      Output:
        // Codex의 구현
        class AuthController {
            async login(req, res) {
                // Express를 사용한 JWT 인증
                ...
            }
        }

  [3] gemini-agent
      Status:   ✓ SUCCESS
      Duration: 8100ms
      Output:
        // Gemini의 구현
        func LoginHandler(w http.ResponseWriter, r *http.Request) {
            // Go에서의 JWT 인증
            ...
        }

  [4] copilot-agent
      Status:   ✓ SUCCESS
      Duration: 7500ms
      Output:
        // Copilot의 구현
        def login(request):
            # Python에서의 JWT 인증
            ...

================================================================================
```

**4단계: 순차 리뷰 체인 실행**

```bash
# Claude로 생성 후 다른 모델들을 통해 리뷰
java -jar cotor-1.0.0.jar run multi-ai-review-chain \
  --config multi-ai-pipeline.yaml \
  --output-format json
```

**5단계: 복잡한 DAG 워크플로우 실행**

```bash
# 의존성이 있는 복잡한 워크플로우 실행
java -jar cotor-1.0.0.jar run multi-ai-dag \
  --config multi-ai-pipeline.yaml \
  --output-format text
```

**멀티 AI 파이프라인의 장점:**

1. **다양한 관점**: 각 AI 모델은 서로 다른 강점을 가짐
2. **품질 보증**: 여러 리뷰를 통해 더 많은 이슈 발견
3. **모범 사례**: 각 모델의 최선의 솔루션을 결합
4. **병렬 처리**: 동시 실행으로 전체 시간 단축
5. **합의 도출**: 출력을 비교하여 최적의 솔루션 찾기

**실제 사용 사례:**

- **코드 생성**: 여러 구현을 생성하고 최선을 선택
- **코드 리뷰**: 다른 AI 모델에 의한 순차적 리뷰
- **문서화**: 각 AI가 문서를 생성하고 최선의 부분을 병합
- **테스트**: 여러 관점에서 테스트 케이스 생성
- **리팩토링**: 여러 소스에서 리팩토링 제안 받기
- **아키텍처 설계**: 여러 AI 어드바이저와 협업 설계

### 예제 8: 편리한 사용을 위한 별칭 생성

**Unix/Linux/macOS:**
```bash
# ~/.bashrc 또는 ~/.zshrc에 추가
alias cotor='java -jar /path/to/cotor-1.0.0.jar'

# 쉘 설정 다시 로드
source ~/.bashrc  # 또는 source ~/.zshrc

# 이제 직접 사용 가능
cotor init
cotor run example-pipeline
cotor list
```

**Windows (PowerShell):**
```powershell
# PowerShell 프로필에 추가
function cotor { java -jar C:\path\to\cotor-1.0.0.jar $args }

# 이제 직접 사용 가능
cotor init
cotor run example-pipeline
cotor list
```

## 설정

### 예제 `cotor.yaml`

```yaml
version: "1.0"

# 에이전트 정의
agents:
  - name: nlp-processor
    pluginClass: com.cotor.data.plugin.NaturalLanguageProcessorPlugin
    timeout: 30000
    parameters:
      mode: analyze
    tags:
      - nlp

# 파이프라인 정의
pipelines:
  - name: text-to-code
    description: "자연어를 코드로 변환"
    executionMode: SEQUENTIAL
    stages:
      - id: understand
        agent:
          name: nlp-processor
          pluginClass: com.cotor.data.plugin.NaturalLanguageProcessorPlugin
        input: "사용자 관리를 위한 REST API 생성"

# 보안 설정
security:
  useWhitelist: true
  allowedExecutables:
    - python3
    - node
  allowedDirectories:
    - /usr/local/bin

# 로깅 설정
logging:
  level: INFO
  file: cotor.log
  format: json

# 성능 설정
performance:
  maxConcurrentAgents: 10
  coroutinePoolSize: 8
```

## CLI 명령어

### 설정 초기화
```bash
cotor init
```

### 파이프라인 실행
```bash
cotor run <pipeline-name> [--output-format json|csv|text]
```

### 에이전트 목록
```bash
cotor list [--config path/to/config.yaml]
```

### 상태 확인
```bash
cotor status
```

### 버전 정보
```bash
cotor version
```

## 커스텀 플러그인 생성

`AgentPlugin` 인터페이스를 구현하세요:

```kotlin
class MyCustomPlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "my-plugin",
        version = "1.0.0",
        description = "나만의 커스텀 에이전트",
        author = "Your Name",
        supportedFormats = listOf(DataFormat.JSON)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): String {
        // 구현 내용
        return "output"
    }
}
```

## 아키텍처

```
┌─────────────────────────────────────────────────────────┐
│                  Presentation Layer                      │
│  (CLI 인터페이스, 명령 핸들러, 출력 포맷터)              │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                    Domain Layer                          │
│  (비즈니스 로직, 오케스트레이션, 파이프라인 관리)        │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                     Data Layer                           │
│  (에이전트 레지스트리, 설정 저장소, 프로세스 실행기)     │
└─────────────────────────────────────────────────────────┘
```

## 실행 모드

### Sequential (순차)
단계를 하나씩 실행하며, 출력을 다음 단계의 입력으로 전달합니다.

### Parallel (병렬)
모든 단계를 동시에 실행합니다.

### DAG (의존성 그래프)
의존성 관계에 따라 단계를 실행합니다.

## 보안

- **Whitelist 검증**: 명시적으로 허용된 실행 파일만 실행 가능
- **명령 인젝션 방지**: 인젝션 패턴 감지 및 차단
- **경로 검증**: 파일 작업이 허용된 디렉토리 내에서만 수행되도록 보장
- **환경 변수 보호**: 위험한 환경 변수 차단

## 성능

- **코루틴 기반**: 수천 개의 동시 작업을 위한 경량 동시성
- **리소스 관리**: 메모리 모니터링 및 자동 가비지 컬렉션
- **설정 가능한 제한**: 최대 동시 에이전트 수 및 스레드 풀 크기 제어

## 테스트

모든 테스트 실행:
```bash
./gradlew test
```

커버리지 리포트 생성:
```bash
./gradlew jacocoTestReport
```

## 개발

### 프로젝트 구조

```
src/main/kotlin/com/cotor/
├── model/                  # 도메인 모델 및 데이터 클래스
├── domain/                 # 비즈니스 로직
│   ├── orchestrator/       # 파이프라인 오케스트레이션
│   ├── executor/           # 에이전트 실행
│   └── aggregator/         # 결과 집계
├── data/                   # 데이터 접근 레이어
│   ├── registry/           # 에이전트 레지스트리
│   ├── config/             # 설정 관리
│   ├── process/            # 프로세스 실행
│   └── plugin/             # 플러그인 시스템
├── security/               # 보안 검증
├── event/                  # 이벤트 시스템
├── monitoring/             # 로깅 및 메트릭
├── presentation/           # CLI 인터페이스
│   ├── cli/                # 명령어
│   └── formatter/          # 출력 포맷터
└── di/                     # 의존성 주입
```

## 기여하기

1. 저장소 포크
2. 기능 브랜치 생성
3. 변경사항 작성
4. 테스트 추가
5. Pull Request 제출

## 라이선스

[라이선스 정보 추가]

## 연락처

[연락처 정보 추가]
