# OpenCode 에이전트

OpenCode는 Cotor에서 사용 가능한 내장 AI 에이전트 중 하나입니다. [OpenCode](https://github.com/opencode-ai/opencode) CLI를 자식 프로세스로 통합하여 Cotor의 오케스트레이션 레이어를 통해 논인터랙티브 프롬프트 실행을 가능하게 합니다.

## 빠른 시작

### 사전 요구사항

- OpenCode 설치됨 (`opencode`가 PATH에 있어야 함)
- OpenCode에 유효한 provider와 model이 설정되어 있어야 함
- 자동 승인: OpenCode를 yolo 모드로 설정

### 사용법

#### CLI / 인터랙티브 모드

```bash
cotor interactive
# :use opencode 또는 :model opencode 사용
```

#### 비교 모드

```bash
cotor interactive
# :mode compare
# opencode가 다른 설정된 에이전트와 함께 실행됨
```

#### 파이프라인 설정

`cotor.yaml`에 OpenCode를 에이전트로 추가:

```yaml
agents:
  - name: opencode
    plugin_class: com.cotor.data.plugin.OpenCodePlugin
    timeout: 900000  # 15분
```

## 작동 방식

`OpenCodePlugin`은 다음을 실행합니다:

```bash
opencode run --model <model> --format json "<prompt>"
```

이는 주어진 프롬프트로 OpenCode를 논인터랙티브 모드로 실행합니다. 플러그인은 stdout을 캡처하여 Cotor의 오케스트레이션 레이어에 반환합니다.

### 명령 흐름

1. Cotor가 프롬프트 수신 (파이프라인, 인터랙티브 세션, 또는 회사 워크플로우에서)
2. `OpenCodePlugin.execute()`가 명령어 빌드: `["opencode", "run", "--model", "<model>", "--format", "json", "<prompt>"]`
3. `ProcessManager`가 설정된 env/작업 디렉토리로 자식 프로세스 생성
4. 프로세스 출력이 캡처되어 `PluginExecutionOutput`으로 반환
5. 프로세스가 0이 아닌 종료 코드로 종료되면 캡처된 stdout/stderr와 함께 `ProcessExecutionException` 발생

## 설정

### 에이전트 파라미터

OpenCode는 선택적 `model` 파라미터를 받습니다. 지정하지 않으면 Cotor는 기본값 `opencode/minimax-m2.5-free`를 사용합니다.

회사 생성 시 시드되는 기본 에이전트도 실행 파일이 존재하면 OpenCode를 우선 사용하도록 바뀌었기 때문에, 사용자가 명시적으로 바꾸지 않는 한 회사 워크플로우는 이 저비용 기본 모델을 우선 사용합니다.

### 타임아웃

기본 타임아웃은 15분(900,000ms)입니다. 작업 복잡도에 따라 조정:

```yaml
agents:
  - name: opencode
    timeout: 1800000  # 30분
```

### 보안

OpenCode는 `KoinModules.kt`의 허용 실행 파일 화이트리스트에 포함되어 있습니다. 표준 사용에는 추가 보안 설정이 필요하지 않습니다.

## 회사 기능

OpenCode는 모든 회사 워크플로우 표면에서 사용 가능합니다:

- **BuiltinAgentCatalog**: 내장 에이전트 옵션으로 나열됨
- **DesktopAppService**: 회사 작업을 위한 선호 에이전트 목록에 포함됨
- **WebServer**: 웹 기반 에이전트 선택을 위한 플러그인 맵에 등록됨
- **DesktopTuiSessionService**: PATH에 `~/.opencode/bin` 포함 (번들 설치용)

## 문제 해결

### "OpenCode execution failed"

**원인**: 실행 중인 명령어가 유효하지 않음. 이전에는 존재하지 않는 서브커맨드 `opencode generate` 사용으로 인해 발생했습니다.

**해결**: `opencode run`을 사용하는 최신 버전의 Cotor를 사용 중인지 확인하세요. 명령어가 수동으로 작동하는지 확인:

```bash
opencode run "say hello"
```

OpenCode 자체가 실패하는 경우(예: 모델을 찾을 수 없음)는 Cotor의 통합 문제가 아닌 OpenCode의 설정 문제입니다.

### 모델을 찾을 수 없음

유효한 provider/model이 설정되어 있지 않으면 OpenCode가 실패합니다. OpenCode 설정을 확인:

```bash
opencode providers
opencode models
```

### 프로세스 멈춤

OpenCode의 `run` 명령어는 프롬프트 완료 후 종료되어야 합니다. 멈춘다면:

1. OpenCode가 사용자 입력을 기다리고 있지 않은지 확인 (`run` 모드에서는 발생하지 않음)
2. OpenCode가 인터랙티브 TUI 모드가 아닌지 확인
3. 작업이 실제로 오래 걸리는 경우 타임아웃 증가

## 다른 에이전트와의 비교

| 에이전트 | 명령어 | 자동 승인 | 비고 |
|---------|--------|----------|------|
| opencode | `opencode run <prompt>` | yolo 모드 설정 | 오픈소스, 설정 가능 |
| codex | `codex exec --full-auto` | `--full-auto` 플래그 | OpenAI |
| claude | `claude --dangerously-skip-permissions` | `--dangerously-skip-permissions` | Anthropic |
| gemini | `gemini --yolo` | `--yolo` 플래그 | Google |
| copilot | `copilot -p <prompt>` | 사전 인증 필요 | GitHub |
