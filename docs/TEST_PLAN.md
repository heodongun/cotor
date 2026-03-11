# Cotor Validation Plan

이 문서는 현재 코드 기준의 검증 계획과 실행 매트릭스를 정리합니다.

## 1. Automated Baseline

Run in this order:

```bash
./gradlew --no-build-cache test -x jacocoTestCoverageVerification
cd macos && swift build
```

Purpose:

- Kotlin core and app-server regression check
- macOS shell compile check

## 2. CLI Smoke Matrix

| Surface | Preconditions | Invocation | Expected result | Mode |
| --- | --- | --- | --- | --- |
| `init` | repo checkout | `./gradlew run --args='init --help'` | help renders | automated |
| `list` | sample config available | `./gradlew run --args='list --help'` | help renders | automated |
| `run` | sample config available | `./gradlew run --args='run --help'` | help renders | automated |
| `validate` | sample config available | `./gradlew run --args='validate --help'` | help renders | automated |
| `test` | repo checkout | `./gradlew run --args='test --help'` | help renders | automated |
| `version` | repo checkout | `./gradlew run --args='version'` | version prints | automated |
| `completion` | repo checkout | `./gradlew run --args='completion zsh'` | completion script prints | semi-automated |
| `status` | repo checkout | `./gradlew run --args='status --help'` | help renders | automated |
| `stats` | repo checkout | `./gradlew run --args='stats --help'` | help renders | automated |
| `doctor` | Java installed | `./gradlew run --args='doctor'` | environment report prints | semi-automated |
| `dash` | repo checkout | `./gradlew run --args='dash --help'` | help renders | automated |
| `interactive` / `tui` | terminal attached | `./gradlew run --args='interactive --help'` | help renders | automated |
| `web` | repo checkout | `./gradlew run --args='web --help'` | help renders | automated |
| `template` | repo checkout | `./gradlew run --args='template --list'` | template inventory prints | automated |
| `lint` | repo checkout | `./gradlew run --args='lint --help'` | help renders | automated |
| `explain` | repo checkout | `./gradlew run --args='explain --help'` | help renders | automated |
| `plugin` | repo checkout | `./gradlew run --args='plugin --help'` | help renders | automated |
| `agent` | repo checkout | `./gradlew run --args='agent --help'` | help renders | automated |
| `app-server` | repo checkout | `./gradlew run --args='app-server --help'` | help renders | automated |

Failure capture:

- copy the command
- capture stderr/stdout
- note whether the issue is docs drift, runtime failure, or environment-specific

## 3. Desktop / Manual Smoke Matrix

| Scenario | Expected result |
| --- | --- |
| app boot with local backend | dashboard loads or clearly enters offline mode |
| company mode default | app opens in `Company` unless settings say otherwise |
| company creation | new company appears with root path and default branch |
| agent creation | new company agent definition appears in roster |
| repository selection | selected repository and workspaces stay in sync |
| goal creation | new goal appears under the selected company |
| issue selection | issue context updates in session strip and detail drawer |
| TUI mode | live session console remains the dominant center view |
| detail drawer toggle | changes/files/ports/browser/review details expand and collapse |
| board/canvas switch | company board/canvas changes without losing selected issue |
| session card click | clicking anywhere on a session/run card changes selection |
| base branch update | applying `Current workspace base branch` updates the backend workspace and restarts the TUI session |

## 4. Autonomous-Company Validation Matrix

| Scenario | Expected result | Current note |
| --- | --- | --- |
| create company | company stored with root path and default branch | live |
| define agents | title/CLI/role summary stored and shown | live |
| create goal | goal stored and returned by dashboard | live |
| decompose goal into issues | issues created for selected goal | live |
| delegate issue | issue gets delegated assignee/status | live |
| run issue | linked task/run path starts | live |
| task/run linkage | selected issue resolves to latest linked task | live |
| review queue population | review queue item appears for qualifying state | live in local state model |
| activity feed | company activity appears in company mode | live |
| runtime status | status/start/stop endpoints respond | live |
| ready-to-merge loop | runtime can merge `READY_TO_MERGE` queue items | live |
| multi-company separation | company A state does not leak into company B lists | live in state model |
| follow-up issue generation | n/a | not implemented |
| policy engine | n/a | not implemented |
| external Linear sync | n/a | not implemented in this build |

## 5. Issue Logging Template

```md
Title:
Area:
Command / Flow:
Expected:
Actual:
Impact:
Repro steps:
Logs / Output:
Decision:
- fixed now
- documented as known limitation
- deferred historical note only
```

## 6. Defect Handling Rule

- If a failure blocks documented current behavior, fix it in the same change.
- If a gap is real but non-blocking, document it as a known limitation in current docs.
- Historical or draft docs must not present blocked or incomplete behavior as live.
