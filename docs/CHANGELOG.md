# Changelog

## Unreleased

- Added internal A2A v1 routes under `/api/a2a/v1/*` with session open, message post, FIFO pull, snapshot, and artifact metadata registration.
- Added in-memory dedupe handling via `dedupeKey` and lightweight A2A envelope/session models.
- Added company dashboard exposure of `AgentContextEntry` and `AgentMessage` so desktop issue detail can render recent A2A handoffs and thread activity.
- Added CI job and step timeouts plus `--stacktrace` on Gradle test execution.
- Added shared test shutdown registry support for `DesktopAppService`.
- Hardened `DesktopStateStore` lock acquisition with bounded retries and lock-holder diagnostics.
