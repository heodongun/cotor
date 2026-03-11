# Cotor Agent Guide

[한국어 안내는 아래로 이동](#cotor-에이전트-가이드)

This guide is for AI coding agents that change code, docs, tests, or workflows in this repository.

## Project Mission And Scope

- Treat Cotor as a local-first multi-agent orchestration product.
- Preserve the shared core across CLI, Web, and macOS Desktop surfaces.
- Assume the desktop app talks to the local Kotlin backend through `cotor app-server`; do not invent a separate runtime model unless the repo already supports it.

## Source Of Truth Map

- Read `README.md` for top-level product behavior, install flow, and current user-facing commands.
- Read `docs/ARCHITECTURE.md` before changing core architecture, execution flow, or subsystem boundaries.
- Read `docs/DESKTOP_APP.md` before changing the localhost app-server contract or macOS desktop behavior.
- Read `CONTRIBUTING.md` before changing docs, PR expectations, or validation habits.
- Read `docs/team-ops/README.md` when the change affects team workflow, templates, handoff, or operating cadence.

## Change Rules

- Prefer the smallest change that fully solves the problem.
- Do not do unrelated refactors while implementing a feature or fix.
- Respect an existing dirty worktree; do not revert user changes unless explicitly asked.
- Keep docs and behavior aligned in the same change when behavior changes.
- Do not describe planned or unimplemented product states as if they already exist.
- When a change touches the company layer, keep `Company` as the top-level product unit and treat repository/workspace/task/run as execution infrastructure below it.
- Keep company context snapshots under `.cotor/companies/...` aligned with company, project, goal, and issue lifecycle events.

## Feature Addition Checklist

1. Confirm the current behavior and entrypoints from the repo before designing the change.
2. Update the smallest necessary model or state layer first.
3. Update the service or orchestration layer next.
4. Update public API contracts before or alongside UI/API consumers.
5. Update the UI only after the backing contract is clear.
6. Update English and Korean docs when feature behavior, usage, or workflow changes.
7. Run the smallest validation set that proves the change works, then expand to repo-level checks when appropriate.

## Area-Specific Guidance

### Kotlin Core And App Server

- Check the main entrypoints first:
  - `src/main/kotlin/com/cotor/app/DesktopAppService.kt`
  - `src/main/kotlin/com/cotor/app/AppServer.kt`
  - `src/main/kotlin/com/cotor/app/DesktopModels.kt`
- Keep app-server contract changes explicit and synchronized with client-facing models.
- Prefer additive API changes unless a breaking change is explicitly required.

### macOS Desktop

- Check these entrypoints before editing UI behavior:
  - `macos/Sources/CotorDesktopApp/DesktopStore.swift`
  - `macos/Sources/CotorDesktopApp/ContentView.swift`
  - `macos/Sources/CotorDesktopApp/DesktopAPI.swift`
- Keep `DesktopStore`, Swift models, and Kotlin API payloads aligned.
- Verify selection state, inspector state, and network contract assumptions after UI changes.
- Preserve the top-level `Company` vs `TUI` shell split unless the repo intentionally changes that information architecture.

### Pipeline And Config Changes

- Validate changed pipeline/config files with:
  - `cotor validate <file>`
  - `cotor lint <file>`
- Prefer validation-driven fixes over speculative YAML restructuring.

### Docs-Only Changes

- Keep English and Korean docs functionally equivalent when behavior or usage changes.
- Update linked entry documents in the same change if discoverability would otherwise break.
- Confirm referenced files, commands, and paths still exist.

## Validation Matrix

| Change type | Minimum validation |
| --- | --- |
| Kotlin core or app-server | `./gradlew test` |
| macOS Desktop SwiftUI | `swift build` in `macos/` |
| Pipeline or config | `cotor validate <file>` and `cotor lint <file>` |
| Docs only | Verify referenced paths/commands/links; run a repo smoke test when docs describe changed behavior |

## PR And Review Expectations

- Summarize what changed and why.
- State exactly which validation commands were run.
- Call out doc sync status for English and Korean when behavior changed.
- Call out known risks, deferred work, or follow-up items instead of hiding them.

---

# Cotor 에이전트 가이드

이 문서는 이 저장소에서 코드, 문서, 테스트, 워크플로를 변경하는 AI 코딩 에이전트를 위한 공통 지침입니다.

## 프로젝트 목적과 범위

- Cotor를 로컬 우선 멀티 에이전트 오케스트레이션 제품으로 다룹니다.
- CLI, Web, macOS Desktop이 공유하는 실행 코어를 보존합니다.
- 데스크톱 앱은 `cotor app-server`를 통해 로컬 Kotlin 백엔드와 통신한다고 가정하고, 저장소에 없는 별도 런타임 모델을 임의로 도입하지 않습니다.

## 소스 오브 트루스 맵

- 최상위 제품 동작, 설치 흐름, 현재 사용자 명령은 `README.md`를 먼저 확인합니다.
- 코어 아키텍처, 실행 흐름, 서브시스템 경계를 바꿀 때는 `docs/ARCHITECTURE.md`를 먼저 읽습니다.
- localhost app-server 계약이나 macOS Desktop 동작을 바꿀 때는 `docs/DESKTOP_APP.md`를 먼저 읽습니다.
- 문서 규칙, PR 기대치, 검증 습관을 바꿀 때는 `CONTRIBUTING.md`를 확인합니다.
- 팀 워크플로, 템플릿, 핸드오프, 운영 리듬을 건드릴 때는 `docs/team-ops/README.md`를 확인합니다.

## 변경 규칙

- 문제를 완전히 해결하는 가장 작은 변경을 우선합니다.
- 기능 추가나 수정 중에는 관련 없는 리팩터를 하지 않습니다.
- 이미 더티한 워크트리는 존중하고, 명시적 요청 없이는 사용자의 변경을 되돌리지 않습니다.
- 동작이 바뀌면 같은 변경 안에서 문서도 함께 맞춥니다.
- 아직 구현되지 않은 상태를 이미 존재하는 것처럼 문서화하지 않습니다.

## 기능 추가 체크리스트

1. 변경 설계 전에 저장소에서 현재 동작과 진입점을 먼저 확인합니다.
2. 가장 작은 모델 또는 상태 레이어부터 갱신합니다.
3. 그다음 서비스 또는 오케스트레이션 레이어를 갱신합니다.
4. 공개 API 계약은 UI/API 소비자와 함께 또는 그보다 먼저 갱신합니다.
5. 백킹 계약이 명확해진 뒤에 UI를 수정합니다.
6. 기능 동작, 사용법, 워크플로가 바뀌면 영문/국문 문서를 함께 갱신합니다.
7. 변경을 증명하는 최소 검증부터 실행하고, 필요하면 저장소 수준 검증으로 확장합니다.

## 영역별 가이드

### Kotlin 코어와 App Server

- 먼저 아래 진입점을 확인합니다.
  - `src/main/kotlin/com/cotor/app/DesktopAppService.kt`
  - `src/main/kotlin/com/cotor/app/AppServer.kt`
  - `src/main/kotlin/com/cotor/app/DesktopModels.kt`
- app-server 계약 변경은 명시적으로 만들고, 클라이언트 모델과 함께 동기화합니다.
- 명시적 요구가 없는 한 브레이킹 변경보다 additive 변경을 우선합니다.

### macOS Desktop

- UI 동작 수정 전 아래 진입점을 먼저 확인합니다.
  - `macos/Sources/CotorDesktopApp/DesktopStore.swift`
  - `macos/Sources/CotorDesktopApp/ContentView.swift`
  - `macos/Sources/CotorDesktopApp/DesktopAPI.swift`
- `DesktopStore`, Swift 모델, Kotlin API payload를 항상 함께 맞춥니다.
- UI 변경 후에는 selection state, inspector state, 네트워크 계약 가정을 다시 확인합니다.

### 파이프라인과 설정 변경

- 변경한 pipeline/config 파일은 아래 명령으로 검증합니다.
  - `cotor validate <file>`
  - `cotor lint <file>`
- YAML을 추측으로 크게 재구성하기보다 검증 결과에 근거해 수정합니다.

### 문서 전용 변경

- 동작이나 사용법이 바뀌면 영문/국문 문서를 기능적으로 동일하게 유지합니다.
- 발견 경로가 끊기지 않도록, 링크되는 진입 문서도 같은 변경 안에서 같이 갱신합니다.
- 참조한 파일, 명령, 경로가 실제로 존재하는지 확인합니다.

## 검증 매트릭스

| 변경 유형 | 최소 검증 |
| --- | --- |
| Kotlin 코어 또는 app-server | `./gradlew test` |
| macOS Desktop SwiftUI | `macos/`에서 `swift build` |
| Pipeline 또는 config | `cotor validate <file>` 와 `cotor lint <file>` |
| 문서 전용 | 참조 경로/명령/링크 확인, 문서가 변경된 동작을 설명하면 repo smoke test 추가 |

## PR 및 리뷰 기대치

- 무엇이 바뀌었고 왜 바꿨는지 요약합니다.
- 어떤 검증 명령을 실행했는지 정확히 적습니다.
- 동작 변경이 있으면 영문/국문 문서 동기화 상태를 같이 적습니다.
- 알려진 리스크, 후속 작업, 미완료 사항은 숨기지 말고 명시합니다.
