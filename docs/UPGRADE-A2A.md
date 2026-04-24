# A2A v1 Upgrade Guide

## Background

Cotor's company runtime already stores goals, issues, tasks, runs, review state, agent messages, and context notes in the local desktop state store.

A2A v1 adds a lightweight internal protocol so those agent-to-agent interactions can be exchanged through a consistent envelope instead of relying only on ad-hoc output markers.

This first version is intentionally internal-only:

- no remote federation
- no OAuth/JWT enforcement
- existing localhost app token is reused for HTTP access
- dedupe, session inboxes, and artifact metadata are file-backed under the local app home
- delivery uses explicit pull + ack semantics instead of destructive pull

## New Endpoints

- `POST /api/a2a/v1/sessions`
- `POST /api/a2a/v1/sessions/{sessionId}/heartbeat`
- `POST /api/a2a/v1/messages`
- `GET /api/a2a/v1/messages/pull`
- `POST /api/a2a/v1/messages/ack`
- `POST /api/a2a/v1/sync/snapshot`
- `POST /api/a2a/v1/artifacts`
- `GET /api/a2a/v1/artifacts`

## What It Does

- standardizes internal A2A envelopes
- supports dedupe via `dedupeKey`
- provides per-session inbox delivery and FIFO pull
- keeps pulled messages available until the client explicitly acknowledges a cursor with `/messages/ack`
- mirrors selected `message.*` envelopes into existing company state
  - `AgentMessage`
  - `AgentContextEntry`
- keeps company snapshots available for resync after app-server restart

## Message Types Implemented

- `task.assign`
- `task.accept`
- `run.update`
- `review.request`
- `review.verdict`
- `message.note`
- `message.warning`
- `message.handoff`
- `message.feedback`
- `message.escalation`
- `sync.snapshot.request`
- `sync.snapshot.response`

## Current Limits

- artifact uploads are metadata-only in v1
- session and dedupe persistence are local-file backed, not a remote durable broker
- ack is cursor-based, not per-message lease/ownership
- run heartbeat is modeled through `run.update` but not yet used as a separate durable transport channel

## CI / Runtime Stability Changes

Alongside A2A, this upgrade also hardens local and CI runtime behavior:

- CI jobs and steps now use explicit `timeout-minutes`
- test runs add `--stacktrace`
- desktop service tests gained a shared shutdown registry helper
- DesktopStateStore uses bounded file-lock acquisition and emits diagnostics on timeout

## Recommended Validation

- `./gradlew --no-daemon test --stacktrace`
- `./gradlew --no-daemon formatCheck`
- `swift test`
- `swift build`

## Troubleshooting

- `401 Unauthorized`
  - missing or invalid desktop app token on `/api/a2a/v1/*`
- `expired_message`
  - `ts + ttlMs` is already in the past
- `already_processed`
  - the same `dedupeKey` was already accepted
- `state.lock acquisition timed out`
  - another process is holding the desktop state lock too long
