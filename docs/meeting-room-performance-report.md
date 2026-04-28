# Meeting Room Performance Report

## What Changed

The Meeting Room remains a live Company Runtime projection, but its rendering is now bounded:

- normal mode for small companies
- simplified mode for compact/reduced-motion/low-resource/20+ agent cases
- grouped mode for 50+ agents

The UI still shows real company agents, runtime state, issues, review queue, cost state, activity count, and PR state. It does not switch to mock animation.

The follow-up resource pass also reduced non-rendering costs that affect Meeting Room during long sessions:

- desktop event-stream reconnect now backs off instead of retrying every second indefinitely
- healthy desktop polling/watchdog loops sleep longer
- company-scoped activity/context/message arrays are capped when snapshots are applied
- backend event snapshot generation is skipped without subscribers and briefly cached during bursts
- provider stdout/stderr output is bounded so large logs cannot grow memory without limit

## Before

Manual baseline from the current local app before this optimization pass:

| Scenario | Result |
| --- | --- |
| Meeting Room open, 9 agents | Desktop CPU usually 0.0% to 0.1%, transient 2.4% during refresh |
| app-server idle | 0.0% to 0.6% transient |
| Main-thread sample | Mostly waiting in AppKit run loop |

The main remaining risk was not constant CPU at small scale; it was unbounded work as agent/issue/review counts grow.

## After

Manual measurement from the optimized local bundle at `/tmp/cotor-desktop-build-performance2/Cotor Desktop.app`:

| Scenario | Result |
| --- | --- |
| Meeting Room open, 9 agents | Desktop CPU 0.0% to 0.1% over 12 seconds |
| app-server idle while room open | 0.0% to 0.1% over 12 seconds |
| RSS while room open | Desktop ~251 MB, bundled app-server ~137 MB, stable during the sample window |
| Main-thread sample | Mostly waiting in `mach_msg`; no visible Meeting Room or accessibility-tree hot path |
| sample output size | reduced from 2881 lines before the final render/accessibility pass to 1073 lines after |

Structural budgets now enforced in Swift:

| Scenario | Guard |
| --- | --- |
| 20+ agents | simplified render mode |
| 50+ agents | grouped render mode, 12 visible agents |
| many issues | Meeting Room issue summaries capped at 120 |
| many review items | Meeting Room review summaries capped at 60 |
| reduced motion | animation disabled |
| low resource mode | animation disabled |
| background scene | animation disabled |

## Validation

Commands run:

```bash
cd macos && swift test --filter MeetingRoomProjectionTests
cd macos && swift build
./gradlew --no-daemon test --tests 'com.cotor.data.process.ProcessManagerTest' --stacktrace -x jacocoTestReport -x jacocoTestCoverageVerification
COTOR_DESKTOP_BUILD_OUTPUT_ROOT=/tmp/cotor-desktop-build-performance2 /bin/bash shell/build-desktop-app-bundle.sh
```

Result:

- 9 Meeting Room projection/render-plan tests passed.
- Swift build passed.
- Desktop bundle build passed.
- Native app smoke validated with Computer Use by opening Company mode and Meeting Room.

Manual baseline tools used:

```bash
ps -o pid,%cpu,%mem,rss,command -p <desktop-pid>,<backend-pid>
sample <desktop-pid> 3 -file /tmp/cotor-meeting-room-sample-before.txt
sample <desktop-pid> 3 -file /tmp/cotor-meeting-room-sample-after2.txt
```

## Remaining Work

- Add app-server metrics for dashboard snapshot generation duration and SSE subscriber count.
- Add deterministic Kotlin tests for event stream subscriber lifecycle and provider output caps.
- Add a larger fake-company UI smoke scenario that opens 50 agents and 500 issues without external provider/GitHub access.
