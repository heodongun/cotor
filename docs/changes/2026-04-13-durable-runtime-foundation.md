# 2026-04-13 Durable Runtime Foundation

## Background
Cotor had checkpoint inspection and recovery strategies, but not a durable, replay-aware execution substrate.

## Added
- durable run snapshots
- checkpoint graph persistence
- side-effect journal persistence
- replay approval pauses for replay-unsafe git/PR actions
- resume inspect/continue/fork/approve CLI flows

## Compatibility
- existing checkpoint files are preserved
- new durable graph is additive under `.cotor/runtime/`

## Remaining work
- company issue/review durable replay
- policy engine
- provenance exports
