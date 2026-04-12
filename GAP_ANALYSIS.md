# Gap Analysis

## Highest leverage missing capabilities

### Durable Runtime
- Missing: checkpoint graph, side-effect journal, replay-safe continuation, forked execution, approval pauses, dead-letter path.

### Policy Engine
- Missing: explicit action policy scopes, explainable denials, dry-run policy simulation, audit trail.

### GitHub Control Plane
- Missing: real PR/CI/check/merge-queue provider model and durable state linkage.

### Provenance / Evidence
- Missing: goal -> issue -> run -> artifact -> branch -> PR -> CI lineage graph.

### Knowledge Layer
- Missing: attributable, queryable, freshness-aware memory substrate.

## Strong existing seams to build on
- `PipelineContext` and orchestrator lifecycle hooks
- `CheckpointManager`
- `GitWorkspaceService` git/PR touch points
- `DesktopAppService` company runtime lifecycle
- internal A2A message plumbing
- event bus / run tracker / structured logging
