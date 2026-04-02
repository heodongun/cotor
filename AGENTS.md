# Cotor Agent Guide

[한국어 안내는 아래로 이동](#cotor-에이전트-가이드)

This document is the repository-specific operating manual for AI coding agents that modify code, docs, tests, packaging, or workflows in this repo.

It is intentionally detailed. Use it as the working contract for how to inspect, change, validate, and describe Cotor without drifting away from the current product model.

## 1. Purpose

- Treat Cotor as a local-first multi-agent orchestration product.
- Preserve the shared execution core across CLI, Web, and macOS Desktop.
- Assume the desktop app is a native shell on top of localhost `cotor app-server`.
- Do not invent a separate cloud control plane, remote backend, or alternative runtime model unless the repository already implements it.

## 2. Product Model To Preserve

### 2.1 Core product shape

- `Company` is the top-level product unit in the current desktop product model.
- Repositories, workspaces, tasks, runs, review items, and activity are execution infrastructure underneath a company.
- The desktop app is not a second product; it is a shell over the same Kotlin runtime.
- The standalone TUI is independent from company workflow state. It should behave like the direct `cotor` terminal, only embedded inside the app when needed.

### 2.2 Current user-facing surfaces

- CLI / interactive TUI
- local web editor
- localhost `app-server`
- macOS desktop app

### 2.3 Current desktop shell model

Preserve this information architecture unless the repo intentionally changes it:

- top-level `Company` mode
- top-level `TUI` mode
- `Company` mode for company operations, goals, issues, activity, review queue, and runtime controls
- `TUI` mode for independent folder-backed interactive `cotor` sessions

## 3. Source Of Truth Map

Read these before making changes in the related area.

- `README.md`
  - top-level behavior
  - current commands
  - install flow
  - current product limits
- `README.ko.md`
  - Korean-facing equivalent of user-facing behavior
- `docs/ARCHITECTURE.md`
  - architecture boundaries
  - execution flow
  - subsystem map
- `docs/DESKTOP_APP.md`
  - desktop shell model
  - app-server contract
  - install/update/delete behavior
- `docs/DESKTOP_APP.ko.md`
  - Korean equivalent of desktop behavior
- `CONTRIBUTING.md`
  - PR and validation expectations
- `docs/team-ops/README.md`
  - workflow, templates, and team operating cadence

When behavior changes, do not update only one of these and leave the rest stale.

## 4. Repository Map

### 4.1 Kotlin entrypoints

Start here before changing product behavior:

- `src/main/kotlin/com/cotor/Main.kt`
- `src/main/kotlin/com/cotor/presentation/cli/Commands.kt`
- `src/main/kotlin/com/cotor/presentation/cli/InteractiveCommand.kt`
- `src/main/kotlin/com/cotor/app/AppServer.kt`
- `src/main/kotlin/com/cotor/app/DesktopAppService.kt`
- `src/main/kotlin/com/cotor/app/DesktopModels.kt`
- `src/main/kotlin/com/cotor/app/GitWorkspaceService.kt`
- `src/main/kotlin/com/cotor/app/DesktopTuiSessionService.kt`

### 4.2 Pipeline and orchestration core

- `src/main/kotlin/com/cotor/domain/orchestrator/`
- `src/main/kotlin/com/cotor/domain/executor/`
- `src/main/kotlin/com/cotor/validation/`
- `src/main/kotlin/com/cotor/checkpoint/`
- `src/main/kotlin/com/cotor/monitoring/`
- `src/main/kotlin/com/cotor/data/config/`

### 4.3 macOS desktop entrypoints

- `macos/Sources/CotorDesktopApp/DesktopStore.swift`
- `macos/Sources/CotorDesktopApp/ContentView.swift`
- `macos/Sources/CotorDesktopApp/DesktopAPI.swift`
- `macos/Sources/CotorDesktopApp/Models.swift`
- `macos/Sources/CotorDesktopApp/EmbeddedBackendLauncher.swift`
- `macos/Packaging/CotorDesktopLauncher.sh.template`

### 4.4 Packaging and install flow

- `Formula/cotor.rb`
- `shell/cotor`
- `shell/install-desktop-app.sh`
- `shell/build-desktop-app-bundle.sh`

## 5. Runtime And Data Boundaries

### 5.1 Desktop boundary

- The desktop app talks to the local Kotlin backend through HTTP.
- Keep Swift DTOs and Kotlin API payloads aligned.
- If an app-server response changes, update Kotlin models, Swift models, and the consuming store/view in the same change.

### 5.2 Company boundary

- Company state is the product-facing state.
- Repository/workspace/task/run objects are implementation-level execution data under the company layer.
- If changing company workflows, keep `.cotor/companies/...` snapshots aligned with lifecycle changes.

### 5.3 TUI boundary

- The direct CLI/TUI and the embedded desktop TUI should stay behaviorally consistent.
- The desktop TUI should not silently become company-coupled again.
- Folder-backed TUI sessions should stay independent and multiplexable.

### 5.4 Packaging boundary

- Source checkout installs and Homebrew packaged installs are different runtime layouts.
- Packaged installs must not assume `gradlew`, `build.gradle.kts`, or `macos/Package.swift` exist at runtime.
- Source installs may rebuild locally.

## 6. Change Rules

- Prefer the smallest change that fully solves the problem.
- Do not do unrelated refactors while fixing a bug or shipping a feature.
- Respect a dirty worktree.
- Do not revert user changes unless explicitly asked.
- Keep docs and behavior aligned in the same change when behavior changes.
- Do not describe planned or hypothetical product states as if they already exist.
- Prefer additive API changes unless a breaking change is explicitly required.
- If a change affects install flow, verify both source-checkout and packaged/Homebrew assumptions.

## 7. Common Invariants

### 7.1 Company workflow invariants

- A company is bound to one working folder.
- Company runtime controls should affect the company runtime, not global app connectivity.
- GitHub readiness failures should surface as readiness/infra problems, not infinite execution retries.
- Review and approval state should not flap because stale task sync re-applies already-consumed outcomes.

### 7.2 Git and publish invariants

- Delegated runs use isolated branches and worktrees.
- Publishing failures caused by permanent readiness/configuration problems should block, not spin forever.
- Merged PRs should not require self-approval when GitHub forbids it.
- Local base branch sync after merge must not clobber real user work.

### 7.3 Desktop UX invariants

- `Company` and `TUI` remain top-level shell modes.
- The desktop app should shut down its bundled backend when the app exits.
- Company summary should stay scanable and compact; do not introduce tall status slabs without a strong reason.
- Global connection state should reflect app-server reachability, not company runtime start/stop.

### 7.4 Interactive CLI invariants

- `cotor` with no args launches interactive mode.
- `cotor tui` aliases interactive mode.
- Default interactive behavior should feel like a normal single-agent chat unless the user explicitly selects multi-agent fan-out.
- Interactive sessions should write transcript data and `interactive.log`.

### 7.5 Docs invariants

- English and Korean docs must stay functionally aligned when behavior changes.
- Root README and `docs/` entry pages must not drift apart on installation or command behavior.

## 8. Generated And Local State Files

Be careful around local artifacts.

- `.cotor/`
  - contains runtime state, companies, worktrees, transcripts, checkpoints, and generated files
  - do not casually treat all of it as source-controlled config
- `.cotor/stats/*`
  - often user/generated runtime artifacts
- `.omc/`
  - local/generated workspace content

Do not “clean up” these files unless the task explicitly requires it.

## 9. Recommended Change Sequence

For non-trivial product changes, follow this order:

1. Confirm current behavior from code, logs, and docs.
2. Identify the smallest state/model change needed.
3. Update the service/orchestration layer.
4. Update app-server contracts if needed.
5. Update clients after the contract is clear.
6. Update English and Korean docs.
7. Run focused validation first, then repo-level validation if warranted.

## 10. Area-Specific Guidance

### 10.1 Kotlin core and app-server

- Start with:
  - `DesktopAppService.kt`
  - `AppServer.kt`
  - `DesktopModels.kt`
- Keep API changes explicit.
- Prefer contract-first changes over hidden behavior drift.
- Add or update tests near the changed behavior.

### 10.2 macOS desktop

- Start with:
  - `DesktopStore.swift`
  - `ContentView.swift`
  - `DesktopAPI.swift`
- Keep `DesktopStore`, Swift DTOs, and Kotlin responses aligned.
- After UI changes, verify:
  - selection state
  - shell mode switching
  - network state assumptions
  - start/stop behavior
  - scrolling behavior when relevant

### 10.3 Interactive CLI / TUI

- Start with:
  - `InteractiveCommand.kt`
  - `Commands.kt`
  - `shell/cotor`
- Be careful with:
  - PATH resolution
  - packaged installs
  - starter agent selection
  - transcript/log locations

### 10.4 Packaging / Homebrew

- Start with:
  - `Formula/cotor.rb`
  - `DesktopInstallLayout.kt`
  - `DesktopLifecycleCommand.kt`
  - `shell/build-desktop-app-bundle.sh`
  - `shell/install-desktop-app.sh`
- Verify packaged installs without assuming repo-relative build files at runtime.

### 10.5 Pipeline and config

- Validate changed pipeline/config files with:
  - `cotor validate <file>`
  - `cotor lint <file>`
- Prefer validation-driven fixes over speculative YAML restructuring.

### 10.6 Docs-only changes

- Keep English and Korean docs functionally equivalent.
- Update linked index/entry docs in the same change if discoverability would break.
- Confirm referenced commands, files, and paths actually exist.

## 11. Validation Matrix

| Change type | Minimum validation |
| --- | --- |
| Kotlin core or app-server | `./gradlew test` |
| macOS Desktop SwiftUI | `swift build` in `macos/` |
| CLI / interactive / install flow | focused CLI repro plus relevant tests |
| Pipeline or config | `cotor validate <file>` and `cotor lint <file>` |
| Docs only | verify paths/commands/links; add smoke validation if docs describe changed behavior |

## 12. Validation Expectations By Scenario

### 12.1 If you changed app-server behavior

At minimum:

- `./gradlew test`

Prefer also:

- a focused HTTP repro if the behavior is user-facing

### 12.2 If you changed desktop UI behavior

At minimum:

- `swift build`

Prefer also:

- a live desktop smoke test when the change affects shell mode, runtime control, scrolling, startup, or shutdown

### 12.3 If you changed install or packaging behavior

Prefer validating both:

- source-checkout path
- packaged/Homebrew path

### 12.4 If you changed company workflow behavior

Prefer validating:

- state transition tests
- runtime tick behavior
- a live repro if the bug was observed in actual desktop state

## 13. PR And Review Expectations

Every final summary or PR description should state:

- what changed
- why it changed
- which files or subsystems were touched
- exactly which validation commands ran
- whether English and Korean docs were updated
- known risks, deferred work, or follow-ups

Do not hide important residual risk behind a generic “done”.

## 14. Common Mistakes To Avoid

- Treating packaged installs like a source checkout
- Changing Kotlin payloads without updating Swift models
- Changing Swift UI without checking `DesktopStore`
- Re-coupling the desktop TUI to company workflow state
- Marking the app offline on benign request cancellation
- Turning permanent GitHub readiness failures into retry loops
- Re-opening already-advanced review/approval issues from stale task sync
- Updating only English docs or only Korean docs
- Editing generated `.cotor` runtime files as if they were source documents

## 15. Quick Command Reference

General:

- `cotor version`
- `cotor help`
- `cotor help --lang ko`
- `cotor app-server --port 8787`
- `./gradlew test`
- `swift build`

Packaging:

- `cotor install`
- `cotor update`
- `cotor delete`

Pipeline/config:

- `cotor validate <file>`
- `cotor lint <file>`

## 16. Final Rule

Keep Cotor grounded in what the repository actually implements today.

Do not optimize for a hypothetical future product at the cost of breaking:

- the local-first runtime model
- the shared Kotlin core
- the current `Company` / `TUI` desktop shell split
- the documented installation and validation flow

## 17. Delivery And Release Requirements

- When a task changes company workflows, company UI, agent defaults, Codex auth flows, packaging, or release behavior, validate both automated tests and hands-on manual flows before calling it done.
- Manual validation should include the company sandbox workspace at `/Users/Projects/bssm-oss/cotor-organization/cotor-test` whenever company creation, goal/issue/review/runtime flows, or packaging behavior are involved.
- Company defaults should bias toward cost control: new company-seeded execution agents should prefer OpenCode with the default model `opencode/qwen3.6-plus-free` unless the repo intentionally changes that product policy.
- If Codex support changes, keep `codex-exec` and `codex-oauth` behavior explicit in code, docs, and validation notes; authentication flows exposed in CLI or app must remain testable and documented.
- Before shipping packaging changes, verify the Homebrew/release path that end users follow, not only source-checkout installs. Do not assume repo-local build files exist at runtime in packaged installs.
- If the user requests a full ship flow, keep commits intentional and descriptive, open a PR with validation notes, and only merge after the documented checks and manual validation pass.

---

# Cotor 에이전트 가이드

이 문서는 이 저장소에서 코드, 문서, 테스트, 패키징, 워크플로를 변경하는 AI 코딩 에이전트를 위한 저장소 전용 운영 매뉴얼입니다.

의도적으로 자세하게 작성되어 있습니다. Cotor의 현재 제품 모델에서 벗어나지 않고 어떻게 확인하고, 수정하고, 검증하고, 설명해야 하는지의 작업 기준으로 사용하세요.

## 1. 목적

- Cotor를 로컬 우선 멀티 에이전트 오케스트레이션 제품으로 다룹니다.
- CLI, Web, macOS Desktop이 공유하는 실행 코어를 보존합니다.
- 데스크톱 앱은 localhost `cotor app-server` 위에 올라가는 네이티브 셸이라고 가정합니다.
- 저장소가 실제로 구현하지 않은 클라우드 컨트롤 플레인, 원격 전용 백엔드, 별도 런타임 모델을 임의로 도입하지 않습니다.

## 2. 보존해야 하는 제품 모델

### 2.1 현재 제품 형태

- 현재 데스크톱 제품 모델에서 최상위 단위는 `Company`입니다.
- 저장소, 워크스페이스, 태스크, 런, 리뷰 아이템, 액티비티는 회사 아래의 실행 인프라입니다.
- 데스크톱 앱은 별도 제품이 아니라 같은 Kotlin 런타임 위의 셸입니다.
- standalone TUI는 회사 워크플로 상태와 독립적이어야 합니다. 앱 안에 임베드되더라도 직접 `cotor` 터미널과 같은 성격을 유지해야 합니다.

### 2.2 현재 사용자 표면

- CLI / 인터랙티브 TUI
- 로컬 웹 에디터
- localhost `app-server`
- macOS 데스크톱 앱

### 2.3 현재 데스크톱 셸 모델

저장소가 의도적으로 바꾸지 않는 한 아래 정보 구조를 보존합니다.

- 최상위 `Company` 모드
- 최상위 `TUI` 모드
- `Company` 모드에서 회사 운영, 목표, 이슈, 액티비티, 리뷰 큐, 런타임 제어
- `TUI` 모드에서 독립적인 폴더 기반 인터랙티브 `cotor` 세션

## 3. 소스 오브 트루스 맵

관련 영역을 바꾸기 전에 아래 문서를 먼저 확인합니다.

- `README.md`
  - 최상위 동작
  - 현재 명령 표면
  - 설치 흐름
  - 현재 제품 한계
- `README.ko.md`
  - 한국어 사용자 대상 동작 설명
- `docs/ARCHITECTURE.md`
  - 아키텍처 경계
  - 실행 흐름
  - 서브시스템 맵
- `docs/DESKTOP_APP.md`
  - 데스크톱 셸 모델
  - app-server 계약
  - install/update/delete 동작
- `docs/DESKTOP_APP.ko.md`
  - 데스크톱 동작의 한국어 설명
- `CONTRIBUTING.md`
  - PR 및 검증 기대치
- `docs/team-ops/README.md`
  - 워크플로, 템플릿, 운영 리듬

동작이 바뀌었는데 이 문서들 중 일부만 바꾸고 나머지를 방치하지 마세요.

## 4. 저장소 맵

### 4.1 Kotlin 진입점

제품 동작을 바꾸기 전에 먼저 여기서 시작합니다.

- `src/main/kotlin/com/cotor/Main.kt`
- `src/main/kotlin/com/cotor/presentation/cli/Commands.kt`
- `src/main/kotlin/com/cotor/presentation/cli/InteractiveCommand.kt`
- `src/main/kotlin/com/cotor/app/AppServer.kt`
- `src/main/kotlin/com/cotor/app/DesktopAppService.kt`
- `src/main/kotlin/com/cotor/app/DesktopModels.kt`
- `src/main/kotlin/com/cotor/app/GitWorkspaceService.kt`
- `src/main/kotlin/com/cotor/app/DesktopTuiSessionService.kt`

### 4.2 파이프라인과 오케스트레이션 코어

- `src/main/kotlin/com/cotor/domain/orchestrator/`
- `src/main/kotlin/com/cotor/domain/executor/`
- `src/main/kotlin/com/cotor/validation/`
- `src/main/kotlin/com/cotor/checkpoint/`
- `src/main/kotlin/com/cotor/monitoring/`
- `src/main/kotlin/com/cotor/data/config/`

### 4.3 macOS 데스크톱 진입점

- `macos/Sources/CotorDesktopApp/DesktopStore.swift`
- `macos/Sources/CotorDesktopApp/ContentView.swift`
- `macos/Sources/CotorDesktopApp/DesktopAPI.swift`
- `macos/Sources/CotorDesktopApp/Models.swift`
- `macos/Sources/CotorDesktopApp/EmbeddedBackendLauncher.swift`
- `macos/Packaging/CotorDesktopLauncher.sh.template`

### 4.4 패키징과 설치 흐름

- `Formula/cotor.rb`
- `shell/cotor`
- `shell/install-desktop-app.sh`
- `shell/build-desktop-app-bundle.sh`

## 5. 런타임과 데이터 경계

### 5.1 데스크톱 경계

- 데스크톱 앱은 로컬 Kotlin 백엔드와 HTTP로 통신합니다.
- Swift DTO와 Kotlin API payload를 항상 맞춰야 합니다.
- app-server 응답이 바뀌면 Kotlin 모델, Swift 모델, 이를 소비하는 store/view를 한 변경 안에서 같이 갱신합니다.

### 5.2 회사 경계

- 회사 상태가 제품 중심 상태입니다.
- 저장소/워크스페이스/태스크/런은 회사 아래 실행 데이터입니다.
- 회사 워크플로를 바꿀 때는 `.cotor/companies/...` 스냅샷도 lifecycle과 맞게 유지합니다.

### 5.3 TUI 경계

- 직접 CLI/TUI와 데스크톱 내장 TUI는 행동상 일치해야 합니다.
- 데스크톱 TUI를 다시 회사 워크플로에 몰래 결합시키지 않습니다.
- 폴더 기반 TUI 세션은 서로 독립적이고 다중 유지가 가능해야 합니다.

### 5.4 패키징 경계

- 소스 체크아웃 설치와 Homebrew packaged install은 다른 레이아웃입니다.
- packaged install은 런타임에 `gradlew`, `build.gradle.kts`, `macos/Package.swift`가 있다고 가정하면 안 됩니다.
- source install은 로컬 재빌드를 사용할 수 있습니다.

## 6. 변경 규칙

- 문제를 완전히 해결하는 가장 작은 변경을 우선합니다.
- 버그 수정이나 기능 추가 중에 관련 없는 리팩터를 하지 않습니다.
- 더티한 워크트리를 존중합니다.
- 명시적 요청이 없으면 사용자의 변경을 되돌리지 않습니다.
- 동작이 바뀌면 같은 변경 안에서 문서도 맞춥니다.
- 아직 구현되지 않은 상태를 이미 존재하는 것처럼 설명하지 않습니다.
- 명시적 이유가 없다면 브레이킹 변경보다 additive 변경을 우선합니다.
- 설치 흐름을 건드리면 source-checkout과 packaged/Homebrew 가정을 둘 다 확인합니다.

## 7. 자주 깨지면 안 되는 불변 조건

### 7.1 회사 워크플로 불변 조건

- 회사 하나는 하나의 작업 폴더에 묶입니다.
- 회사 런타임 시작/중지는 회사 런타임에만 영향을 줘야 하고, 전역 연결 상태를 흔들면 안 됩니다.
- GitHub readiness 실패는 readiness/infra 문제로 드러나야지, 실행 이슈 무한 재시도로 바뀌면 안 됩니다.
- 이미 소비된 QA/CEO 결과가 stale task sync 때문에 다시 적용되면 안 됩니다.

### 7.2 Git 및 publish 불변 조건

- delegated run은 격리된 브랜치와 worktree를 사용합니다.
- 영구적인 readiness/config 실패는 block 되어야 하고, 무한 반복되면 안 됩니다.
- GitHub가 self-approval을 막는 경우 merged PR이 self-approval 때문에 멈추면 안 됩니다.
- merge 후 로컬 base branch sync는 실제 사용자 작업을 덮어쓰면 안 됩니다.

### 7.3 데스크톱 UX 불변 조건

- `Company`와 `TUI`는 최상위 셸 모드로 유지합니다.
- 앱 종료 시 번들 백엔드도 같이 내려가야 합니다.
- 회사 요약 화면은 훑어보기 쉬운 밀도를 유지해야 하며, 강한 이유 없이 높은 상태 카드 덩어리를 늘리지 않습니다.
- 전역 연결 상태는 app-server 도달 가능성을 나타내야 하며, 회사 런타임 시작/중지와 혼동되면 안 됩니다.

### 7.4 인터랙티브 CLI 불변 조건

- 인자 없는 `cotor`는 interactive를 엽니다.
- `cotor tui`는 interactive alias입니다.
- 기본 interactive는 사용자가 명시적으로 멀티 에이전트를 선택하지 않는 한 일반 단일 에이전트 채팅처럼 느껴져야 합니다.
- 인터랙티브 세션은 transcript와 `interactive.log`를 남겨야 합니다.

### 7.5 문서 불변 조건

- 동작이 바뀌면 영문/국문 문서가 기능적으로 맞아야 합니다.
- 루트 README와 `docs/` 진입 문서가 설치/명령 동작에서 서로 어긋나면 안 됩니다.

## 8. 생성물과 로컬 상태 파일

로컬 산출물을 함부로 다루지 마세요.

- `.cotor/`
  - 런타임 상태, 회사 스냅샷, worktree, transcript, checkpoint, 생성 파일이 들어 있습니다.
  - 이 전체를 소스 설정처럼 취급하면 안 됩니다.
- `.cotor/stats/*`
  - 사용자/런타임 생성물인 경우가 많습니다.
- `.omc/`
  - 로컬/생성 작업물입니다.

명시적으로 필요한 작업이 아니면 이런 파일을 “정리”하지 마세요.

## 9. 권장 변경 순서

중간 이상 규모의 제품 변경은 아래 순서를 권장합니다.

1. 코드, 로그, 문서에서 현재 동작을 확인합니다.
2. 가장 작은 상태/모델 변경을 찾습니다.
3. 서비스 또는 오케스트레이션 레이어를 갱신합니다.
4. 필요하면 app-server 계약을 갱신합니다.
5. 계약이 명확해진 뒤 클라이언트를 갱신합니다.
6. 영문/국문 문서를 갱신합니다.
7. 먼저 집중 검증을 돌리고, 필요하면 저장소 단위 검증으로 확장합니다.

## 10. 영역별 가이드

### 10.1 Kotlin 코어와 app-server

먼저 아래 파일에서 시작합니다.

- `DesktopAppService.kt`
- `AppServer.kt`
- `DesktopModels.kt`

원칙:

- API 변경은 명시적으로 만듭니다.
- 보이지 않는 동작 드리프트보다 계약 중심 변경을 우선합니다.
- 변경된 동작 옆에 테스트를 추가하거나 수정합니다.

### 10.2 macOS 데스크톱

먼저 아래 파일에서 시작합니다.

- `DesktopStore.swift`
- `ContentView.swift`
- `DesktopAPI.swift`

원칙:

- `DesktopStore`, Swift DTO, Kotlin 응답을 같이 맞춥니다.
- UI를 바꾼 뒤에는 아래를 다시 확인합니다.
  - selection state
  - shell mode 전환
  - 네트워크 상태 가정
  - start/stop 동작
  - 스크롤 동작이 바뀐 경우 스크롤 사용성

### 10.3 인터랙티브 CLI / TUI

먼저 아래 파일에서 시작합니다.

- `InteractiveCommand.kt`
- `Commands.kt`
- `shell/cotor`

특히 조심할 부분:

- PATH 해석
- packaged install
- starter agent 선택
- transcript/log 저장 경로

### 10.4 패키징 / Homebrew

먼저 아래 파일에서 시작합니다.

- `Formula/cotor.rb`
- `DesktopInstallLayout.kt`
- `DesktopLifecycleCommand.kt`
- `shell/build-desktop-app-bundle.sh`
- `shell/install-desktop-app.sh`

packaged install에서는 런타임에 repo-relative build file이 있다고 가정하지 않도록 검증하세요.

### 10.5 파이프라인과 설정

변경한 pipeline/config는 아래로 검증합니다.

- `cotor validate <file>`
- `cotor lint <file>`

추측성 YAML 재구성보다 검증 결과에 근거한 수정이 우선입니다.

### 10.6 문서 전용 변경

- 영문/국문 문서를 기능적으로 맞춥니다.
- 발견 경로가 끊기지 않도록 링크되는 진입 문서도 함께 갱신합니다.
- 참조한 명령, 파일, 경로가 실제로 존재하는지 확인합니다.

## 11. 검증 매트릭스

| 변경 유형 | 최소 검증 |
| --- | --- |
| Kotlin 코어 또는 app-server | `./gradlew test` |
| macOS Desktop SwiftUI | `macos/`에서 `swift build` |
| CLI / interactive / install flow | 집중 CLI 재현 + 관련 테스트 |
| Pipeline 또는 config | `cotor validate <file>` 와 `cotor lint <file>` |
| 문서 전용 | 경로/명령/링크 확인, 문서가 변경된 동작을 설명하면 smoke 검증 추가 |

## 12. 상황별 검증 기대치

### 12.1 app-server 동작을 바꿨다면

최소:

- `./gradlew test`

가급적 추가:

- 사용자 노출 동작이면 집중 HTTP 재현

### 12.2 데스크톱 UI 동작을 바꿨다면

최소:

- `swift build`

가급적 추가:

- shell mode, runtime control, scrolling, startup, shutdown에 영향이 있으면 실제 데스크톱 smoke test

### 12.3 설치나 패키징 동작을 바꿨다면

가능하면 둘 다 확인합니다.

- source-checkout 경로
- packaged/Homebrew 경로

### 12.4 회사 워크플로 동작을 바꿨다면

가능하면 아래를 확인합니다.

- 상태 전이 테스트
- runtime tick 동작
- 실제 데스크톱 상태에서 관찰된 버그라면 live repro

## 13. PR 및 리뷰 기대치

최종 요약이나 PR 설명에는 항상 아래를 포함합니다.

- 무엇이 바뀌었는지
- 왜 바꿨는지
- 어떤 파일이나 서브시스템을 건드렸는지
- 어떤 검증 명령을 실제로 실행했는지
- 영문/국문 문서를 갱신했는지
- 남아 있는 리스크, 후속 작업, 보류 사항

중요한 리스크를 그냥 “완료” 한 줄로 덮지 마세요.

## 14. 피해야 할 흔한 실수

- packaged install을 source checkout처럼 취급하는 것
- Kotlin payload를 바꾸고 Swift 모델을 안 맞추는 것
- `DesktopStore`를 확인하지 않고 Swift UI만 바꾸는 것
- 데스크톱 TUI를 다시 회사 워크플로에 결합시키는 것
- 단순 request cancellation을 오프라인으로 처리하는 것
- 영구적인 GitHub readiness 실패를 retry loop로 만드는 것
- 이미 진전된 review/approval 이슈를 stale task sync로 다시 여는 것
- 영문 문서만 또는 국문 문서만 갱신하는 것
- 생성된 `.cotor` 런타임 파일을 소스 문서처럼 편집하는 것

## 15. 빠른 명령 참고

일반:

- `cotor version`
- `cotor help`
- `cotor help --lang ko`
- `cotor app-server --port 8787`
- `./gradlew test`
- `swift build`

패키징:

- `cotor install`
- `cotor update`
- `cotor delete`

파이프라인/설정:

- `cotor validate <file>`
- `cotor lint <file>`

## 16. 마지막 원칙

Cotor를 저장소가 오늘 실제로 구현한 모습에 맞춰 유지하세요.

아래를 깨뜨리면서 가상의 미래 제품에 최적화하지 마세요.

- 로컬 우선 런타임 모델
- 공유 Kotlin 코어
- 현재의 `Company` / `TUI` 데스크톱 셸 분리
- 문서화된 설치 및 검증 흐름

## 17. 전달 및 릴리스 요구사항

- 회사 워크플로, 회사 UI, 에이전트 기본값, Codex 인증 흐름, 패키징, 릴리스 동작을 바꾸는 작업은 완료 처리 전에 자동 테스트와 수동 조작 테스트를 둘 다 통과해야 합니다.
- 회사 생성, goal/issue/review/runtime 흐름, 패키징 동작을 검증할 때는 가능하면 `/Users/Projects/bssm-oss/cotor-organization/cotor-test` 샌드박스를 사용합니다.
- 회사 기본 에이전트 정책은 비용 통제를 우선합니다. 저장소가 의도적으로 바꾸지 않는 한 새 회사에 시드되는 실행 에이전트는 기본 모델 `opencode/qwen3.6-plus-free`를 사용하는 OpenCode를 우선합니다.
- Codex 지원을 바꿀 때는 `codex-exec`, `codex-oauth` 구분을 코드, 문서, 검증 기록에 모두 명시하고, CLI나 앱에 노출된 인증 흐름이 실제로 테스트 가능해야 합니다.
- 패키징을 바꾸면 source-checkout 설치만 보지 말고 실제 사용자가 따르는 Homebrew/릴리스 경로를 검증합니다. packaged install에서 repo-local build file이 있다고 가정하지 않습니다.
- 사용자가 전체 ship 흐름을 요청했다면 커밋은 의도를 드러내게 잘게 나누고, 검증 메모를 포함한 PR을 연 뒤, 문서화된 체크와 수동 검증이 끝난 다음에만 머지합니다.
