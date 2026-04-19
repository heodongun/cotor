# 2026-04-13 Control Plane Foundations

## Background

Cotor already had a durable pipeline runtime slice, but the surrounding control plane was still fragmented:

- agent/git/github side effects were recorded ad hoc
- policy checks were validator-centric rather than action-centric
- provenance was mostly logs and workflow state
- GitHub PR state was derived locally but not stored as a provider control-plane
- company memory stayed close to snapshots rather than structured evidence-backed records

## What changed

- Added `com.cotor.runtime.actions` for common action recording and interception.
- Added `com.cotor.policy` for file-backed action allow/deny/approval decisions and audit logging.
- Added `com.cotor.provenance` for a local evidence graph.
- Added `com.cotor.providers.github` for PR state/control-plane snapshots and sync events.
- Added `com.cotor.knowledge` for structured issue knowledge records backed by evidence references.
- Exposed read surfaces through CLI, app-server, web, and read-only MCP.
- Bound company issue/review runtime state to durable run ids, pending approval pauses, and GitHub provider block reasons.
- Added persisted verification contracts/outcomes, GitHub event feeds with dedupe keys, issue runtime projections, and risk-approval interception for high-risk actions.

## Design reasoning

- Keep every new layer local-first and file-backed.
- Reuse the existing durable runtime instead of introducing a second workflow engine.
- Prefer read/inspect surfaces before mutation surfaces.
- Keep company/runtime integration additive so existing desktop state continues to load.

## Impacted areas

- agent execution
- git / PR publishing
- app-server API
- web runtime API
- company dashboard/runtime binding
- README and architecture docs

## Validation

- `./gradlew --no-daemon compileTestKotlin`
- `./gradlew --no-daemon --no-build-cache --rerun-tasks test --tests 'com.cotor.policy.PolicyEngineTest' --tests 'com.cotor.runtime.actions.ActionExecutionServiceTest' --tests 'com.cotor.providers.github.GitHubControlPlaneServiceTest' --tests 'com.cotor.app.AppServerPlatformRoutesTest' --tests 'com.cotor.runtime.durable.*' --tests 'com.cotor.app.AppServerDurableRuntimeTest' -x jacocoTestReport -x jacocoTestCoverageVerification`

## Remaining limits

- company-wide durable continuation is still partial
- GitHub control-plane ingestion is `gh`-driven, not webhook-native
- read-only MCP exposure is available, but write-capable tools are intentionally excluded
- knowledge retrieval is inspect-first and not yet deeply wired into planner prompts
