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
- `resume` inspects checkpoints but does not yet behave like a durable execution engine.
- `CheckpointManager` stores flat stage summaries rather than a causal execution graph.
- policy / approval / guardrail logic is scattered across validators and workflow code.
- GitHub workflow sync exists as local reconciliation logic, not as a full provider-native control plane.
- company messages/context exist, but not provenance-aware memory.

## Architectural bottlenecks
- `DesktopAppService.kt` remains a large concentration point.
- Side effects are distributed across shell/process/git/PR actions without one shared idempotency model.
- Draft docs and current code still disagree in some places, especially around follow-up generation and remote runners.
