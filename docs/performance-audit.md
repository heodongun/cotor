# Performance Audit

## Scope

This audit focuses on the Cotor macOS Company Meeting Room and the local app-server path that feeds it.

Primary files inspected:

- `macos/Sources/CotorDesktopApp/ContentView.swift`
- `macos/Sources/CotorDesktopApp/MeetingRoomView.swift`
- `macos/Sources/CotorDesktopApp/MeetingRoomProjection.swift`
- `macos/Sources/CotorDesktopApp/DesktopStore.swift`
- `src/main/kotlin/com/cotor/app/AppServer.kt`
- `src/main/kotlin/com/cotor/app/DesktopAppService.kt`

## Baseline Measurement

Environment:

- Local bundled desktop app from `/tmp/cotor-desktop-build-issue-close/Cotor Desktop.app`
- Local app-server port `56109`
- Company sandbox rooted at `/Users/Projects/bssm-oss/cotor-organization/cotor-test`
- Current visible company had 9 agents, 7 issues, 22 activity signals

Observed with `ps` over 12 seconds while Meeting Room was open:

| Process | CPU range | RSS range |
| --- | ---: | ---: |
| `CotorDesktopBinary` | 0.0% to 2.4% transient | ~233 MB to 247 MB |
| bundled app-server JVM | 0.0% to 0.6% transient | ~91 MB to 95 MB |

`sample 39594 3` showed the main thread mostly blocked in the AppKit event loop, with only isolated Core Animation/timer work. That indicates the current idle freeze risk is no longer a constant animation loop, but large snapshots and large rendered agent/issue counts could still regress.

## Root Causes And Risks Found

- Previous Meeting Room designs used decorative continuous animation patterns. The current implementation no longer uses `TimelineView` or `repeatForever`, but broad animation and offscreen rasterization still needed tightening.
- `MeetingRoomProjection` kept all issue/review summaries, which can make large companies expensive even though the room only needs a bounded overview.
- Message counts were calculated by scanning messages once per agent.
- The Company Meeting Room panel computed several derived Meeting Room arrays before passing the projection into `MeetingRoomView`, duplicating work in SwiftUI body evaluation.
- 20+ and 50+ agent cases needed deterministic simplified/grouped rendering rules so large companies do not render every sprite/card at full fidelity.

## Fixes Applied

- Removed Meeting Room `drawingGroup` offscreen rasterization from the live room.
- Added `MeetingRoomRenderPlan` to cap visible agents and flows by mode.
- Compute the render plan once per Meeting Room body pass instead of repeatedly sorting and slicing agents during the same render.
- Added automatic simplified mode for compact, Reduced Motion, Low Resource Mode, or 20+ agents.
- Added automatic grouped mode for 50+ agents.
- Added a manual Low Resource Mode toggle in the Meeting Room header.
- Changed animation to use a small render-plan key rather than the whole projection value.
- Added issue/review projection caps: 120 issue summaries and 60 review summaries.
- Replaced per-agent message scans with one message count map.
- Reduced duplicate Meeting Room projection work in the Company Meeting Room panel.
- Marked decorative office/background/sprite internals as hidden from accessibility while preserving meaningful button labels for agents, flows, and zones.
- Added desktop-side caps for retained activity, agent messages, and context entries when applying company-scoped snapshots.
- Changed company event reconnect from fixed 1-second retry to bounded exponential backoff.
- Reduced idle desktop polling/watchdog cadence so healthy idle state does not keep waking the app every few seconds.
- Added app-server event snapshot coalescing: if there are no company event subscribers, no dashboard snapshot is built; with subscribers, snapshots are cached briefly during event bursts.
- Added bounded stdout/stderr buffering for external provider processes so huge CLI output cannot grow memory unbounded.
- Added embedded backend health-check reuse so repeated scene activation/bootstrap/watchdog calls do not issue redundant localhost health requests while the managed backend is already known healthy.
- Changed embedded TUI terminal delta polling from a fixed 120 ms loop to adaptive polling: active output stays responsive, idle sessions back off up to 2 seconds, and hidden web views use the slower cadence.
- Added server-side dashboard caps for activity, signals, goal decisions, agent context entries, and agent messages so large local state does not produce oversized JSON snapshots.
- Added persistence caps for agent context entries and agent messages so long-running companies do not grow `state.json` unbounded through agent chatter.

## After Measurement

Environment:

- Local bundled desktop app from `/tmp/cotor-desktop-build-performance2/Cotor Desktop.app`
- Local app-server port `56540`
- Same visible company shape: 9 agents, 7 issues, 22 activity signals

Observed with `ps` over 12 seconds while Meeting Room was open:

| Process | CPU range | RSS |
| --- | ---: | ---: |
| `CotorDesktopBinary` | 0.0% to 0.1% | ~251 MB |
| bundled app-server JVM | 0.0% to 0.1% | ~137 MB |

`sample 58961 3` after the final render/accessibility pass showed the main threads mostly waiting in `mach_msg`. The previous `AccessibilityViewGraph` hot path did not appear in the inspected sample, and the sample report dropped from 2881 lines to 1073 lines.

## Regression Guards

Swift tests now cover:

- actual agent count projection
- running/review/blocked/done/cost visual state projection
- reconnect snapshot stability
- issue/review detail summaries
- large issue/review projection caps
- 50-agent grouped render plan and animation disablement

Kotlin tests now cover:

- huge process stdout is capped while preserving head and tail context
- process timeout cleanup and inherited-pipe handling continue to pass
- desktop state persistence caps agent context/message history
- company dashboard responses cap noisy feed and agent memory fields

Swift/build validation covers:

- bundled terminal assets still copy into the SwiftPM product after adaptive polling changes
- embedded backend launcher still compiles with health-check reuse state
