# RFC: Control Plane Foundations

## Summary

This RFC describes the local-first control-plane layers added on top of Cotor's durable runtime:

- action substrate
- policy engine v1
- provenance graph v1
- GitHub control plane v1
- knowledge layer v1
- read-only MCP exposure

## Why this exists

The durable runtime slice introduced replayable pipeline state, but it still lacked:

- one action contract for critical side effects
- explainable pre-execution policy decisions
- attributable evidence for file/PR outcomes
- a persisted GitHub provider state model
- a structured memory layer for repeated issue/review outcomes

Without these layers, Cotor could execute work but not fully explain, govern, or inspect it.

## Architecture

### Action substrate

`com.cotor.runtime.actions`

- `ActionRequest`
- `ActionExecutionRecord`
- `ActionExecutionService`
- `ActionInterceptor`

The substrate wraps key agent/git/github side effects and emits file-backed action logs under `.cotor/runtime/actions/`.

### Policy engine

`com.cotor.policy`

- file-backed `PolicyDocument`
- scope-aware `PolicyRule`
- explainable `PolicyDecision`
- audit logging

The v1 engine is intentionally simple: it matches action kind, scope, command prefix, path prefix, and network target.

### Provenance

`com.cotor.provenance`

- `EvidenceNode`
- `EvidenceEdge`
- `EvidenceBundle`

Action execution records feed the evidence graph so operators can inspect run/file/PR lineage.

### GitHub control plane

`com.cotor.providers.github`

- `PullRequestSnapshot`
- `GitHubProviderEvent`
- file-backed sync state

The v1 implementation syncs PR state, mergeability, and status-check summaries through `gh`.

### Knowledge

`com.cotor.knowledge`

Structured knowledge records keep review and mergeability outcomes attributable to issues.

### MCP exposure

Read-only JSON-RPC endpoints expose company summary, issues, durable runs, approval queues, and evidence bundles.

## Backward compatibility

- existing company snapshots remain readable
- existing durable runtime files remain valid
- all new state is additive and file-backed under new directories
- mutation-capable MCP tools are intentionally not exposed

## Deferred work

- webhook-native GitHub ingestion
- richer policy DSL and secret/runtime window rules
- full company issue/review durable continuation
- write-capable MCP tools
- stronger knowledge retrieval integration in planner/reviewer prompts
