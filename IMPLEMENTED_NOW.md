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
- Legacy checkpoint import bridge
- `cotor resume inspect|continue|fork|approve`
- `cotor policy`, `cotor evidence`, `cotor github`, `cotor knowledge`, `cotor mcp`
- app-server durable runtime endpoints for list/inspect/continue/fork/approve
- app-server/web read-only inspect endpoints for policy, evidence, GitHub provider state, and knowledge

## Still intentionally incomplete
- full company issue/review durable continuation
- dead-letter/quarantine UI
- provider-native webhook/app GitHub control plane
- richer policy DSL beyond the v1 file-backed scope matcher
- provenance graph visual UI/export
- write-capable MCP tools

## Operator note
- Automatic journaling is experimental.
- Enable it with `COTOR_EXPERIMENTAL_DURABLE_RUNTIME_V2=1`.
