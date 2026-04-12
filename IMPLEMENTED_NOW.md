# Implemented Now

## Completed in this slice
- Durable run storage under `.cotor/runtime/runs/*.json`
- Checkpoint graph model
- Side-effect journal model with replay-unsafe approval pauses
- Legacy checkpoint import bridge
- `cotor resume inspect|continue|fork|approve`
- app-server durable runtime endpoints for list/inspect/continue/fork/approve
- web read-only durable runtime inspect endpoints

## Still intentionally incomplete
- full company issue/review durable continuation
- dead-letter/quarantine UI
- provider-native GitHub control plane
- policy DSL and simulation engine
- provenance graph visual UI/export

## Operator note
- Automatic journaling is experimental.
- Enable it with `COTOR_EXPERIMENTAL_DURABLE_RUNTIME_V2=1`.
