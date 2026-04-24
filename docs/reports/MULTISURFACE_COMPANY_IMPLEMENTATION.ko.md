# 멀티서피스 회사 기능 확장 작업 메모

이 문서는 `company` 기능 확장 작업 중 현재 변경셋에 실제로 반영된 내용을 요약한다.

## 이번 변경에 포함된 내용

### 1. OpenCode 모델 지정

- `OpenCodePlugin`이 `model` 파라미터를 읽어 `opencode run --model <model> --format json <prompt>` 형태로 실행하도록 수정했다.
- 기본 OpenCode 모델은 `opencode/minimax-m2.5-free`로 정리했다.
- built-in agent catalog와 `cotor agent add opencode` preset도 같은 기본 모델을 사용하도록 맞췄다.

### 2. 회사 기본 에이전트 비용 정책

- 새 회사 시드 에이전트가 가능한 경우 `codex`보다 `opencode`를 우선 사용하도록 기본 선호 순서를 조정했다.

### 3. 조직도 다중선택 배치 수정

- 회사 에이전트 일괄 수정 API를 추가했다.
  - `PATCH /api/app/companies/{companyId}/agents/batch`
- 현재 지원 필드:
  - `enabled`
  - `agentCli`
  - `specialties`
- macOS 앱의 조직도 배치 편집 시트가 실제 저장 로직을 호출하도록 연결했다.

### 4. 회사 CLI 표면 시작

- 새 `cotor company ...` 명령 트리를 추가했다.
- 현재 포함된 하위 그룹:
  - company
  - agent
  - goal
  - issue
  - review
  - runtime
  - backend
  - linear
  - context
  - message
  - topology
  - decisions
  - issue-graph
  - execution-log

### 5. 문서/가이드 반영

- README / README.ko 명령 표면에 `company`를 추가했다.
- OpenCode 문서에 `--model` 사용법과 기본 모델 정책을 반영했다.
- `AGENTS.md`에 수동 검증, Brew/릴리스 검증, OpenCode 기본 정책, Codex `exec/oauth` 유지 원칙을 추가했다.

## 현재 확인된 검증

- `swift build` 성공
- 아래 Kotlin 테스트는 Jacoco coverage verification 제외 시 성공
  - `com.cotor.data.plugin.OpenCodePluginTest`
  - `com.cotor.app.AppServerTest`
  - `com.cotor.presentation.cli.AgentCommandTest`
  - `com.cotor.presentation.cli.CompletionCommandTest`

## 아직 남아 있는 작업

- `codex-exec` / `codex-oauth` 분리와 로그인/상태 표면
- 회사 기능의 Web / 회사용 TUI 확장
- 회사 CLI parity에 대한 전용 테스트 보강
- Homebrew 릴리스 아티팩트형 전환과 실제 릴리스/Brew 검증
- 로고 중심 정렬 수정 및 패키징 검증
- 전체 회귀 테스트 정리
