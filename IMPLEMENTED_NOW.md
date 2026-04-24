# Implemented Now

## Completed in this slice
- Durable run storage under `.cotor/runtime/runs/*.json`
- Checkpoint graph model
- Side-effect journal model with replay-unsafe approval pauses
- Action interceptor substrate and action log storage under `.cotor/runtime/actions/*.json`
- Policy engine v1 with validate/simulate/enforcement and decision audit log under `.cotor/policies/`
- Provenance / evidence graph v1 under `.cotor/provenance/`
- GitHub control-plane v1 state under `.cotor/providers/github/`
- Structured knowledge store v1 under `.cotor/knowledge/`
- Read-only MCP runtime exposure through the localhost app-server
- Company runtime binding now maps company issues and review queue items to durable run ids, approval pauses, and provider block reasons
- Company issue execution now writes durable run snapshots by default so bound `durableRunId` values are inspectable
- GitHub sync now pushes failing/passing check transitions back into review queue and execution issue state
- GitHub sync now also settles merged and closed pull requests back into review queue, approval, and execution issue state
- Verification bundles now persist contract/outcome state and stamp verification status back into issue/review workflow records
- GitHub control-plane now exposes event feeds with dedupe keys
- Runtime projection read surfaces expose issue-level runtime disposition
- Risk approval interceptor v1 can require approval for high-risk action classes
- Knowledge retrieval now has execution/review/approval-specific ranking paths
- Legacy checkpoint import bridge
- `cotor resume inspect|continue|fork|approve`
- `cotor policy`, `cotor evidence`, `cotor github`, `cotor knowledge`, `cotor mcp`
- `cotor verification inspect`
- app-server durable runtime endpoints for list/inspect/continue/fork/approve
- app-server/web read-only inspect endpoints for policy, evidence, GitHub provider state, and knowledge

## Still intentionally incomplete
- full company issue/review durable continuation beyond default issue-run inspection
- dead-letter/quarantine UI
- provider-native webhook/app GitHub control plane
- richer policy DSL beyond the v1 file-backed scope matcher
- provenance graph visual UI/export
- write-capable MCP tools

## Operator note
- Automatic journaling is experimental.
- Enable it with `COTOR_EXPERIMENTAL_DURABLE_RUNTIME_V2=1`.
