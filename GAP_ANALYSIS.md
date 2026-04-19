# Gap Analysis

## Highest leverage missing capabilities

### Durable Runtime
- Delivered: checkpoint graph, action/side-effect logging, replay-safe continuation, forked execution, approval pauses.
- Remaining: full company issue/review durable continuation, dead-letter/quarantine UI, stronger deterministic replay boundaries.

### Policy Engine
- Delivered: explicit action policy scopes, explainable denials, dry-run policy simulation, audit trail.
- Remaining: richer DSL, runtime windows, secret scopes, budget-native policy clauses, stronger per-company/goal authoring UX.

### GitHub Control Plane
- Delivered: file-backed PR state/control-plane store with mergeability and status-check summary sync.
- Remaining: webhook/App-native ingestion, merge queue awareness, required-check semantics, branch protection modeling.

### Provenance / Evidence
- Delivered: run/action/file/pr evidence graph and inspect bundles.
- Remaining: richer goal/issue/ci lineage and visual/export surfaces.

### Knowledge Layer
- Delivered: structured, attributable, freshness-aware issue knowledge store.
- Remaining: planner/reviewer prompt retrieval depth, vector retrieval, conflict resolution workflow.

## Strong existing seams to build on
- `PipelineContext` and orchestrator lifecycle hooks
- `CheckpointManager`
- `GitWorkspaceService` git/PR touch points
- `DesktopAppService` company runtime lifecycle
- internal A2A message plumbing
- event bus / run tracker / structured logging
