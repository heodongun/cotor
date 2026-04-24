# Current State

## What Cotor already is
- Kotlin-based local-first workflow runner with shared runtime across CLI/TUI, localhost web editor, localhost app-server, and macOS shell.
- Company-mode operating model with companies, goals, issues, review queue, runtime loop, activity feed, and git worktree isolation.
- Partial A2A layer, checkpoint snapshots, recovery strategies, event bus, stats, and desktop runtime visibility.

## Implemented core
- `src/main/kotlin/com/cotor/domain/orchestrator/PipelineOrchestrator.kt`
- `src/main/kotlin/com/cotor/recovery/RecoveryExecutor.kt`
- `src/main/kotlin/com/cotor/app/DesktopAppService.kt`
- `src/main/kotlin/com/cotor/app/GitWorkspaceService.kt`
- `src/main/kotlin/com/cotor/app/AppServer.kt`
- `src/main/kotlin/com/cotor/presentation/web/WebServer.kt`

## Partial / shallow layers
- Company issue execution now creates inspectable durable run snapshots by default; generic pipeline `resume continue/fork/approve` and company-wide issue/review continuation are still partial.
- `CheckpointManager` stores flat stage summaries rather than a causal execution graph.
- policy / approval / guardrail logic now has a file-backed v1 engine, but richer policy DSL features are still missing.
- GitHub workflow sync now has a provider state store and sync surface, but it is still `gh`-driven rather than webhook-native.
- company messages/context now have provenance and knowledge stores, but planner/reviewer retrieval is still shallow.

## Architectural bottlenecks
- `DesktopAppService.kt` remains a large concentration point.
- Side effects now flow through an action store/interceptor substrate for key agent/git/github paths, but not every mutating path is migrated yet.
- Draft docs and current code still disagree in some places, especially around follow-up generation and remote runners.
