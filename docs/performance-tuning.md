# Performance Tuning

## Meeting Room

Use the `Low` toggle in the Meeting Room header if the local machine is under load. Cotor also reduces motion automatically when:

- macOS Reduced Motion is enabled
- the window is in compact layout
- the company has 20 or more agents
- the company has 50 or more agents
- the app scene is not active

The room keeps real runtime data attached in all modes. Low Resource Mode only reduces rendering and animation work.

## Large Companies

For large companies, Meeting Room intentionally renders an overview:

- 20+ agents: simplified rendering
- 50+ agents: grouped rendering
- large issue/review sets: bounded summaries

Open issue/review detail surfaces for deeper data instead of rendering every log and every item in the room.

## Runtime And Provider Output

Cotor keeps desktop state bounded during long sessions:

- company event reconnect uses backoff instead of a tight retry loop
- idle desktop polling and backend watchdog checks use longer healthy intervals
- embedded backend health checks are reused briefly while the managed process is already healthy
- embedded TUI polling backs off when the terminal is idle or hidden instead of polling at the active-output cadence forever
- provider stdout/stderr are stored as bounded head/tail summaries when output is huge
- company activity, agent context, and agent message lists are capped in the desktop store
- dashboard responses and persisted desktop state also cap noisy agent context/message history so long-running companies do not keep increasing snapshot and decode cost

If a provider prints very large logs, the beginning and end are preserved with an explicit truncation marker. Detailed artifacts should be written to files rather than streamed forever through stdout.

## Debugging Resource Use

Useful local commands:

```bash
ps -o pid,%cpu,%mem,rss,command -p <desktop-pid>,<backend-pid>
sample <desktop-pid> 3 -file /tmp/cotor-meeting-room-sample.txt
jcmd <backend-pid> Thread.print
```

If CPU stays high while idle, check for:

- continuous SwiftUI animation loops
- duplicate event stream subscriptions
- repeated dashboard refreshes
- provider processes left running after timeout
- large stdout/stderr payloads being rendered in always-visible UI
