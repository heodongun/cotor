# 2026-04-06 Company Validation And Desktop State Fix

## What problem was found

### 1. Kotlin build/test baseline was broken

- `./gradlew test` failed before the test suite could run.
- The immediate failure was in `DesktopStateStore.acquireBoundedFileLock` where a bare `throw` appeared inside `catch (_: OverlappingFileLockException)`.
- That left the repository in a state where company/runtime validation could not be trusted because the core Kotlin build did not compile.

### 2. Company workflow validation surfaced two user-visible runtime defects

Manual validation against `/Users/Projects/bssm-oss/cotor-organization/cotor-test` found:

- a CLI-created autonomous company could report runtime `RUNNING` and backend `healthy` even when no long-lived `app-server` process was attached
- OpenCode-backed company runs could fail with stdout-only NDJSON error output, while Cotor surfaced only `OpenCode execution failed (exit=1): (no stderr)` and then left issues blocked instead of treating the observed failure as retryable

## Root cause

### Fixed root cause

- The Kotlin compile failure was caused by invalid rethrow syntax in `DesktopStateStore.kt`.
- The code used `catch (_: OverlappingFileLockException) { throw }`, which is not valid Kotlin in this form because there is no named exception value to rethrow.

### Runtime / OpenCode follow-up root cause

- The company runtime/OpenCode failures did **not** reproduce as a simple `opencode run --model opencode/qwen3.6-plus-free --format json "hello"` failure.
- The exact persisted company prompts also succeeded when replayed directly through raw `opencode run` in the same worktrees.
- The durable product fix in this change is therefore not “eliminate every OpenCode failure,” but:
  - surface stdout-backed OpenCode failures honestly
  - treat the observed `DecimalError` signature as recoverable so autonomous company flow can retry instead of dead-ending in `BLOCKED`
  - report detached CLI runtime state honestly when there is no live app-server instance

## How it was fixed

### 1. Restored the Kotlin build baseline

- Replaced the invalid bare rethrow with an explicit exception rethrow in `src/main/kotlin/com/cotor/app/DesktopStateStore.kt`.

```kotlin
} catch (overlapping: OverlappingFileLockException) {
    throw overlapping
}
```

This preserves the intended behavior:

- `acquireBoundedFileLock()` rethrows same-process overlap to its caller.
- `withStateFileLock()` can still treat that overlap as an in-process re-entry case and fall back to the existing mutex protection.

### 2. Made OpenCode process failures visible instead of hiding them behind `(no stderr)`

- Updated `DefaultAgentExecutor` so `ProcessExecutionException` falls back to stdout when stderr is blank.
- This matters because OpenCode emits NDJSON error events on stdout in the failing company path.

### 3. Marked the observed OpenCode `DecimalError` signature as recoverable for autonomous company retry logic

- Updated `DesktopAppService.isRecoverableInfrastructureFailure()` to recognize:

  - `DecimalError`
  - `Invalid argument: [object Object]`

- That allows autonomous company issues to reopen/retry instead of being left in a permanently blocked state after this specific OpenCode failure.

### 4. Made CLI-only local runtime health truthful

- Added live app-server instance detection from `runtime/backend/app-server.instance.json`.
- Derived company runtime snapshots now report local backend health as `offline` when no active app-server instance is attached to that app home, even if persisted runtime intent is still `RUNNING`.

## Reproduction

### Build failure before fix

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew test
```

Observed failure before the fix:

- compile error in `DesktopStateStore.kt`

### Company validation flow used for investigation

Shared sandbox state:

```bash
"/Users/Projects/bssm-oss/cotor-organization/cotor/shell/cotor" company list
"/Users/Projects/bssm-oss/cotor-organization/cotor/shell/cotor" company runtime status --company-id 3287d6ec-7a92-43f4-96a7-9480e9832c4e
"/Users/Projects/bssm-oss/cotor-organization/cotor/shell/cotor" company execution-log --company-id 3287d6ec-7a92-43f4-96a7-9480e9832c4e
```

Fresh isolated validation flow:

```bash
export COTOR_APP_HOME=/tmp/cotor-manual-flow.XXXXXX
"/Users/Projects/bssm-oss/cotor-organization/cotor/shell/cotor" company create --name=manual-smoke --root="/Users/Projects/bssm-oss/cotor-organization/cotor-test" --base-branch=master --autonomy-enabled
"/Users/Projects/bssm-oss/cotor-organization/cotor/shell/cotor" company goal create --company-id=<id> --title="manual validation goal" --description="Create a fresh autonomous validation flow" --autonomy-enabled=true
```

Observed behavior before the runtime/reporting fix:

- CLI-only flow reported runtime `RUNNING` and backend `healthy` even though no app-server instance was attached.

Observed behavior after the runtime/reporting fix:

- CLI-only flow still preserves runtime intent as `RUNNING`, but now reports:
  - `backendHealth: offline`
  - `backendLifecycleState: STOPPED`
  - `backendMessage: No active local Cotor app-server instance is attached to this app home...`

Observed behavior after the retry/error-surfacing fixes:

- In a fresh launcher-based company flow with a live `app-server`, failed OpenCode runs now preserve stdout-backed error detail.
- In a 65-second clean-room validation run, the same company flow recovered from an earlier failed execution run and re-entered `IN_PROGRESS` with a fresh retry task instead of remaining terminally blocked.

## Verification

### Automated

Kotlin:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew test
```

Result:

- `BUILD SUCCESSFUL`

Focused Kotlin regression checks:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew test --tests "com.cotor.domain.executor.AgentExecutorTest" -x jacocoTestReport -x jacocoTestCoverageVerification
```

Result:

- passed

Full Kotlin suite in the active dirty worktree:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew test
```

Result:

- one unrelated existing failure remained in `DesktopAppServiceTest > runTask clears stale review metadata when a validation-only follow-up completes without publishing`
- this failure was already present in the active dirty branch during investigation and is outside the changed code path here

Swift:

```bash
cd macos
swift test
```

Result:

- all 6 Swift tests passed

### Manual

Verified directly:

- `./shell/cotor version` runs under the project launcher with JDK 17
- shared company state for the `cotor-test` sandbox is readable through CLI company commands
- fresh isolated company creation and goal creation still work
- raw `opencode run` works in both repo root and generated company worktrees for simple prompts and for a tool-using prompt (`Inspect README.md and report one sentence.`)
- after rebuilding `shadowJar`, a CLI-created autonomous company now reports detached local backend state honestly before `app-server` startup
- in a fresh 65-second launcher-based company run with a live `app-server`, the workflow advanced from CEO planning into execution, recovered from a failed run, and re-entered `IN_PROGRESS` with a fresh retry task

## Remaining limitations / follow-up work

These are still open after this change:

1. **CLI runtime intent vs lifecycle semantics**
   - The product still preserves runtime intent as `RUNNING` across CLI-only flows.
   - This change makes backend health truthful, but it does not redesign company runtime ownership semantics.

2. **OpenCode-backed company execution instability**
   - Fresh isolated company runs can still hit intermittent OpenCode failures.
   - The difference now is that the observed `DecimalError` path is surfaced honestly and the company loop can recover/retry instead of terminally dead-ending.
   - The underlying OpenCode-side root cause still merits a dedicated follow-up focused on execution environment and OpenCode session/tooling behavior.

3. **Validation gap**
   - The repository has strong unit/integration-style coverage for many company runtime paths, but there is still no true end-to-end autonomous company flow test that proves a fresh company can continue progressing outside an already-running `app-server` session.
