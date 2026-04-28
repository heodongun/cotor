# Resource Budget

Cotor is a local-first desktop shell on top of a localhost Kotlin runtime. These budgets keep the Company Meeting Room usable on normal developer laptops while preserving live runtime projection.

## Desktop

| Area | Budget |
| --- | ---: |
| Desktop idle, Meeting Room closed | average CPU <= 3% |
| Meeting Room idle, <= 10 agents | average CPU <= 8% |
| Meeting Room idle, 20 agents | average CPU <= 12% |
| Meeting Room grouped mode, 50 agents | average CPU <= 12% |
| Repeated main-thread hitches | no repeated > 200 ms hitches during idle |

## Meeting Room Rendering

- No `TimelineView`, `Timer`, or `repeatForever` loop may drive decorative idle animation.
- Animation is snapshot/state-change driven only.
- System Reduced Motion disables Meeting Room motion.
- Manual Low Resource Mode disables Meeting Room motion.
- Compact layout or 20+ agents switches to simplified rendering.
- 50+ agents switches to grouped rendering.
- Visible work cards are capped by render mode.
- Issue details and review details are capped in the projection and loaded from existing dashboard state.

## Snapshot Bounds

| Projection field | Cap |
| --- | ---: |
| visible flow cards | 10 normal, 6 simplified, 4 grouped |
| issue summaries retained for Meeting Room | 120 |
| review summaries retained for Meeting Room | 60 |
| grouped-mode visible agents | 12 |

## Backend Runtime

- app-server idle CPU should stay close to zero.
- Company runtime polling should stay bounded and event-driven where possible.
- Provider stdout/stderr and activity history must be capped or summarized before becoming always-rendered desktop state.
- Reconnect must not create duplicate event stream subscriptions.
- Desktop company event reconnect uses bounded backoff instead of a fixed tight loop.
- Healthy desktop polling/watchdog loops should sleep at least 30 seconds between checks.
- Embedded backend launcher health checks should be reused for at least 10 seconds while the managed backend process is already running and healthy.
- Provider stdout and stderr are each capped to a bounded head/tail summary.
- Company event snapshots are not generated when there are no subscribers and are briefly coalesced during bursts.
- Embedded TUI terminal delta polling should use a fast cadence only while output is active; idle sessions should back off to at least 500 ms and may slow to 2 seconds when quiet or hidden.

## Retention Bounds

| State | Cap |
| --- | ---: |
| Desktop-retained company activity items | 160 |
| Desktop-retained agent messages | 240 |
| Desktop-retained agent context entries | 160 |
| Server dashboard company activity items | 160 |
| Server dashboard agent messages | 240 |
| Server dashboard agent context entries | 160 |
| Persisted agent messages | 240 |
| Persisted agent context entries | 160 |
| Process stdout retained per command | bounded head/tail around 1 MB |
| Process stderr retained per command | bounded head/tail around 1 MB |
