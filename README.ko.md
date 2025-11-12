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

### 1. 프로젝트 빌드

```bash
./gradlew build
```

### 2. Shadow JAR 생성

```bash
./gradlew shadowJar
```

실행 가능한 JAR 파일이 `build/libs/cotor-1.0.0.jar`에 생성됩니다.

### 3. 설정 초기화

```bash
java -jar build/libs/cotor-1.0.0.jar init
```

현재 디렉토리에 기본 `cotor.yaml` 설정 파일이 생성됩니다.

### 4. 파이프라인 실행

```bash
java -jar build/libs/cotor-1.0.0.jar run example-pipeline
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

### 예제 7: 편리한 사용을 위한 별칭 생성

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
