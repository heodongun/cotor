# Runner Architecture

This document designs the next execution layer for Cotor desktop/app flows so the
current local git-worktree model can grow into a multi-workspace and remote
execution system without rewriting the UI contract again.

## Why this exists

Today the desktop stack already supports:

- repositories registered from a local path or cloned URL,
- multiple branch-pinned workspaces per repository,
- fan-out task execution across agents,
- one local git worktree plus branch per agent run.

That baseline is implemented directly inside:

- `DesktopAppService`, which creates tasks, schedules runs, and passes cwd metadata,
- `GitWorkspaceService`, which creates worktrees and computes diffs/files,
- `AgentExecutor`, which executes an agent once it receives repo/workspace metadata.

This is enough for local execution, but it bakes in assumptions that stop the next
platform step:

- a run always has a local `worktreePath`,
- workspace isolation is always implemented by local git worktrees,
- run inspection assumes local filesystem and local git diff access,
- port discovery assumes a locally running process,
- there is no runner abstraction for remote workers, leased sandboxes, or queued jobs.

## Goals

- Preserve the current desktop UX around repositories, workspaces, tasks, runs,
  diffs, files, and browser inspection.
- Generalize execution so one workspace can be backed by either local or remote
  infrastructure.
- Keep multi-workspace semantics explicit: a workspace is the user-facing branch
  context, not an implementation detail of local git worktrees.
- Allow future runners to expose artifacts, logs, diff data, and ports without the
  desktop service needing to know whether the run was local or remote.
- Support phased migration with the current local worktree flow as runner v1.

## Non-goals

- Shipping remote infrastructure in this ticket.
- Replacing the existing agent/plugin execution model.
- Changing the desktop UI information architecture.
- Solving distributed scheduling for every environment up front.

## Current baseline

### Workspace model

`Workspace` currently means:

- one repository,
- one pinned base branch,
- one user-visible name.

This is the correct user concept and should stay stable.

### Execution model

`DesktopAppService.executeAgentRun()` currently does all of the following in one
path:

1. resolve the workspace and repository,
2. call `GitWorkspaceService.ensureWorktree(...)`,
3. create or reuse a local branch/worktree binding,
4. pass that binding into `AgentExecutor` as cwd metadata,
5. persist the run with `branchName` and `worktreePath`,
6. later derive diff/files/ports directly from the local process and filesystem.

This is the coupling we need to break.

## Design principles

- Keep the control plane stable. The app server should continue talking in terms of
  repositories, workspaces, tasks, and runs.
- Move isolation details behind runner interfaces.
- Treat local execution as one concrete runner implementation, not the default
  architecture.
- Persist enough runner metadata that runs remain inspectable after process exit.
- Prefer additive schema changes so existing local state can migrate in place.

## Target model

Separate four concerns that are currently blended together:

1. `Workspace`
   The user-facing branch context and task home.
2. `WorkspaceBinding`
   The code snapshot/binding that a runner needs in order to execute work for a
   workspace.
3. `ExecutionRunner`
   The component that turns a binding plus task into a real run.
4. `RunArtifacts`
   Portable outputs used for diff/file/log/browser inspection after or during a run.

### Proposed domain objects

```kotlin
enum class RunnerKind {
    LOCAL_WORKTREE,
    REMOTE_SANDBOX
}

@Serializable
data class WorkspaceExecutionTarget(
    val runnerKind: RunnerKind,
    val bindingRef: String,
    val baseBranch: String,
    val repositoryRevision: String? = null
)

@Serializable
data class RunnerDescriptor(
    val id: String,
    val kind: RunnerKind,
    val displayName: String,
    val capabilities: Set<String>,
    val status: String
)

@Serializable
data class RunArtifactRefs(
    val diffRef: String? = null,
    val fileIndexRef: String? = null,
    val logRef: String? = null,
    val browserBaseUrl: String? = null
)
```

### Workspace semantics

A workspace should remain the stable user-facing unit:

- "Cotor / master / platform experiments"
- "Cotor / release-2026q2 / hotfix lane"

What changes is how that workspace is bound for execution:

- local runner: binding resolves to a local repository root plus per-run worktree,
- remote runner: binding resolves to a remote snapshot, clone URL, or workspace
  mirror managed by a remote service.

## Runner interface

Introduce a dedicated execution interface between `DesktopAppService` and the
implementation-specific isolation layer.

```kotlin
interface ExecutionRunner {
    val kind: RunnerKind

    suspend fun prepareBinding(workspace: Workspace, repository: ManagedRepository): WorkspaceExecutionTarget

    suspend fun startRun(
        task: AgentTask,
        workspace: Workspace,
        repository: ManagedRepository,
        agent: AgentConfig,
        target: WorkspaceExecutionTarget
    ): RunnerStartResult

    suspend fun inspectRun(run: AgentRun): RunnerInspection

    suspend fun cancelRun(run: AgentRun): RunnerCancelResult
}
```

`RunnerStartResult` should return a runner-owned run handle rather than assuming a
local `worktreePath`:

```kotlin
data class RunnerStartResult(
    val runnerRunId: String,
    val branchName: String,
    val status: AgentRunStatus,
    val processId: Long? = null,
    val workingDirectory: Path? = null,
    val artifacts: RunArtifactRefs = RunArtifactRefs()
)
```

The local runner can still populate `workingDirectory`; remote runners can leave it
null and supply artifact references instead.

## Local runner v1

The current behavior becomes `LocalWorktreeRunner`.

Responsibilities:

- create/reuse `.cotor/worktrees/<task-id>/<agent-name>`,
- create/reuse `codex/cotor/<task-slug>/<agent-name>` branches,
- execute the agent locally with cwd metadata,
- collect local diff/file/port inspection data,
- persist any reusable artifact refs for later UI inspection.

This keeps current behavior intact while moving it behind the new runner boundary.

## Remote runner v1

`RemoteSandboxRunner` is the first remote implementation target.

Responsibilities:

- materialize or lease a remote repo snapshot for the workspace,
- inject the task prompt, agent config, and execution metadata,
- stream status/log updates back to the control plane,
- publish diff/file/browser artifacts through stable references,
- expose browser/port endpoints through a proxy-safe public or tunneled URL,
- release or retain the remote sandbox according to policy.

The desktop app should not know whether this came from SSH, Kubernetes, Nomad, or
another provider. It should only consume the runner contract.

## Lifecycle

### 1. Workspace creation

When a workspace is created, store:

- repository id,
- base branch,
- runner preference,
- optional execution policy metadata.

The workspace does not eagerly create a worktree or remote sandbox. Binding stays
lazy until the first run.

### 2. Task creation

Task creation remains unchanged for the UI: one task targets one workspace and one
or more agents.

### 3. Run dispatch

For each selected agent:

1. resolve the workspace execution target,
2. choose the runner from workspace policy,
3. start the run,
4. persist a runner-owned run handle and artifact refs,
5. update run state through a shared status pipeline.

### 4. Inspection

`getChanges`, `listFiles`, and `listPorts` should stop calling local git/process
helpers directly. Instead:

- ask the runner for inspection data,
- fall back to local helper logic only inside the local runner,
- treat artifact refs as the durable source for completed remote runs.

### 5. Retry and rerun

Rerun should reuse the workspace and runner policy, but it must request a fresh run
lease from the runner. Local worktree reuse can remain a local-runner policy.

## State model changes

`AgentRun` currently stores local-only fields (`worktreePath`, local `processId`
semantics). Extend it so the run record is runner-aware:

```kotlin
@Serializable
data class AgentRun(
    val id: String,
    val taskId: String,
    val workspaceId: String,
    val repositoryId: String,
    val runnerKind: RunnerKind = RunnerKind.LOCAL_WORKTREE,
    val runnerId: String? = null,
    val runnerRunId: String? = null,
    val branchName: String,
    val worktreePath: String? = null,
    val artifactRefs: RunArtifactRefs = RunArtifactRefs(),
    ...
)
```

Guidance:

- keep `worktreePath` nullable for backward compatibility,
- add runner identity fields before introducing remote execution,
- treat `artifactRefs` as the cross-runner inspection contract.

## App server/API implications

The existing HTTP surface can stay mostly stable if we change how the service layer
fulfills it.

Recommended additions:

- workspace create/update request accepts `runnerKind`,
- run payload exposes runner metadata and capability flags,
- inspection endpoints return a normalized payload regardless of runner type,
- optional endpoint to list available runners/capabilities for settings UI.

This keeps the macOS client focused on rendering state instead of branching on
runner-specific implementation details.

## Failure handling

The local implementation mostly fails synchronously. Remote execution requires
broader failure states:

- binding failed,
- queued but not yet leased,
- leased but agent bootstrap failed,
- running but heartbeat lost,
- completed but artifact publication incomplete.

Recommendation:

- keep `AgentRunStatus` coarse for current UI compatibility,
- add machine-readable substatus or failure reason fields,
- preserve enough runner error metadata to tell whether retry is safe.

## Security and isolation

- Local runner keeps today's git worktree isolation.
- Remote runner must treat repository materialization, secrets injection, and
  artifact access as explicit policy layers.
- Runner contracts should accept a filtered environment instead of full process
  inheritance.
- Browser URLs returned by remote runners should be scoped to the current run and
  expire by policy.

## Migration plan

### Phase 1: Extract interfaces around current local behavior

- Introduce `ExecutionRunner`.
- Move current worktree logic behind `LocalWorktreeRunner`.
- Keep existing persistence compatible.

### Phase 2: Make inspection runner-aware

- Route diff/file/port inspection through runner interfaces.
- Add artifact refs to `AgentRun`.
- Preserve local fallback behavior only inside the local runner.

### Phase 3: Add remote runner control plane

- Add remote runner registry/configuration.
- Implement remote run start, status sync, and artifact publication.
- Support workspace-level runner selection.

### Phase 4: Harden operations

- cancellation,
- heartbeat timeouts,
- artifact retention,
- audit logging,
- quota and concurrency controls.

## Validation strategy for implementation tickets

When this design is implemented, acceptance should prove both compatibility and the
new abstraction boundary:

- local workspace runs still create isolated branches/worktrees and produce diffs,
- the desktop API can list runs without assuming a local `worktreePath`,
- runner-aware inspection works for local runs before remote runs ship,
- remote runner implementation can be introduced without changing the desktop task
  creation UX.

## Open questions

- Should a workspace own one runner preference, or should tasks override runner
  choice per run?
- Do we need persistent remote workspaces for warm caches, or are immutable
  per-run snapshots sufficient?
- Should diff/file artifacts be stored in the state file, local disk cache, or an
  external object store once remote runs exist?
