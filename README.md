# Cotor

Cotor is a local-first AI workflow runner that grew into a company-style AI operating system: a CEO AI delegates work to subordinate AIs, CLI agents keep costs down, workflows stay visible in macOS, and a goal can drive an always-on issue loop. The same Kotlin core powers pipeline execution, the localhost `app-server`, and the native desktop shell.
Smoke test: `cotor version`

## What Is Current In This Build

- CLI/TUI orchestration with `SEQUENTIAL`, `PARALLEL`, and `DAG` pipelines
- Validation, linting, status, statistics, checkpoints, and template generation
- Local web editor with YAML export and run support
- macOS desktop shell backed by `cotor app-server`
- Multi-company operations layer with companies, agent definitions, goals, issues, review queue, activity feed, and runtime start/stop/status
- Estimated AI spend tracking per company with configurable daily and monthly cost guardrails
- Per-agent git branch and worktree isolation for delegated execution

## Current Command Surface

Top-level commands registered in `Main.kt`:

`init`, `run`, `dash`, `interactive`, `validate`, `test`, `template`, `resume`, `checkpoint`, `stats`, `doctor`, `status`, `list`, `web`, `app-server`, `lint`, `explain`, `plugin`, `agent`, `company`, `auth`, `version`, `completion`

Important entry behavior:

- `cotor` with no args launches `interactive`
- `cotor tui` is an alias to `interactive`
- `interactive` defaults to a single preferred agent chat; use `--mode auto|compare` or `:mode ...` to fan out to multiple agents
- interactive transcripts live under `.cotor/interactive/...`, and each session now writes `interactive.log` beside the transcript files
- packaged first-run interactive writes its auto-generated starter config under `~/.cotor/interactive/default/cotor.yaml` when no local config exists
- packaged first-run interactive only auto-selects AI starters that are actually ready to answer immediately; otherwise it falls back to the safe `example-agent` echo starter instead of failing on an unauthenticated CLI
- unknown first args fall back to direct pipeline execution

Current subcommand support:

- `agent add`, `agent list`
- `auth codex-oauth login|status|logout`
- `company ...` for company/agent/goal/issue/review/runtime/backend/linear/context/message operations
- `plugin init`
- `checkpoint gc`

Current template types:

- `compare`
- `chain`
- `review`
- `consensus`
- `fanout`
- `selfheal`
- `verified`
- `blocked-escalation`
- `custom`

## Install

### Homebrew (Recommended)

```bash
brew tap bssm-oss/cotor https://github.com/bssm-oss/cotor.git
brew install bssm-oss/cotor/cotor
```

This installs JDK 17 + the CLI and packages a bundled desktop app asset.
Run `cotor install` after `brew install` to copy `Cotor Desktop.app` into Applications.
`cotor install` / `cotor update` reuse the packaged app instead of rebuilding from the Homebrew prefix.
`cotor install` prints the exact installed app path and falls back to `~/Applications` when `/Applications` is not writable.

Update:

```bash
brew upgrade bssm-oss/cotor/cotor
```

### Direct DMG Download

Download the latest DMG from [GitHub Releases](https://github.com/bssm-oss/cotor/releases/latest):

1. Download `Cotor-<version>.dmg`
2. Open the DMG file
3. Drag `Cotor Desktop.app` to `/Applications`

### From Source

```bash
git clone https://github.com/bssm-oss/cotor.git
cd cotor
./shell/cotor version   # JDK 17 auto-detected, shadowJar auto-built
```

## Quick Start

```bash
cotor version
cotor help
cotor help --lang ko
cotor init --starter-template
cotor install
cotor app-server --port 8787
open "/Applications/Cotor Desktop.app"
```

## macOS Desktop

After `brew install cotor`, install the packaged desktop app with:

```bash
cotor install    # Install bundled app from Homebrew package
cotor update     # Reinstall bundled app from Homebrew package
cotor delete     # Remove app
```

If no local `cotor.yaml` exists, the first packaged `cotor` run writes a starter config under
`~/.cotor/interactive/default/cotor.yaml`. If no authenticated AI CLI or API key is ready yet,
that starter intentionally falls back to `example-agent` echo mode instead of failing immediately.
If you run `cotor` inside a repo that already contains `./cotor.yaml`, that local config still wins.

From a source checkout, the same commands still rebuild the desktop app locally before installing it.

Current desktop model:

- top-level `Company` and `TUI` shell modes
- `Company` mode for multi-company operations, agent roster, goals, issue board/canvas, activity feed, and runtime controls
- `Company` summary keeps runtime health, blocked workflow count, review attention, and the latest error/action inside the main summary banner instead of a separate tall status card
- `Company` summary now also shows estimated spend plus daily/monthly cost guardrails for the selected company runtime
- `Company` mode now uses event-driven live updates as the primary path, so activity, issues, review state, and runtime status update without a manual refresh in normal operation
- stale Cotor-managed retry PRs are reconciled and closed in batches so repeated review loops do not keep hundreds of obsolete open PRs around
- legacy CEO merge-conflict blockers are pushed back into execution so the company can rebase, republish, and continue instead of staying stuck in a blocked approval lane
- if the live company stream drops, the desktop shell keeps the current company snapshot on screen and shows a company-specific re-sync message instead of a generic decode error
- the issue board keeps each lane scrollable inside a fixed board surface so long blocked/review lanes stay readable instead of clipping at the top
- stale merge-conflict blocks are re-opened automatically when the linked GitHub PR becomes clean again, so resolved rebases flow back into the CEO lane without a manual reset
- stale execution issues that were accidentally left `BLOCKED` after the linked PR already merged are normalized back to `DONE` on the next runtime tick
- `TUI` mode for standalone folder-backed `cotor` terminals, with multiple live sessions in parallel
- top session strip for active execution contexts
- collapsible detail drawer for changes, files, ports, browser, and review metadata

## Autonomous Company Status

The current build includes a working local operations layer:

- create multiple companies, each bound to one working folder
- surface a GitHub readiness warning during company creation when GitHub PR mode is enabled but `gh` auth/origin setup is missing
- define company agents with only title, CLI, and role summary
- create company goals
- decompose goals into issues
- delegate and run issues
- populate and merge ready review queue items
- inspect compact runtime status, blocked/review attention, and recent company activity from the company summary page
- inspect estimated company spend and adjust daily/monthly runtime guardrails without leaving the company console
- start and stop a local autonomous runtime loop per company
- let the CEO reopen planning after one wave finishes so active goals can generate the next wave instead of freezing after the first batch of issues
- bias autonomous continuous-improvement goals toward multi-issue portfolios and parallel branchable slices instead of a single narrow follow-up
- enrich short high-level goal descriptions into a broader execution portfolio so larger rosters do not collapse into only one or two issues
- keep an explicit company stop sticky across app restarts, dashboard reads, and live reconnects until the user presses Start again
- keep the company runtime in a fast monitoring cadence while active tasks/runs still exist, so dead or stale `RUNNING` runs reconcile sooner instead of looking idle for a long backoff window
- when the app-server shuts down during active company work, current builds re-queue interrupted issues instead of leaving them permanently blocked by a generic process-exit failure
- when the desktop app comes back after that shutdown, a running company runtime resumes queued delegated work and the company activity feed reflects the recovery without a full manual refresh

Current limits in this build:

- the app uses a Linear-style board inside Cotor; it is not a live external Linear sync
- runtime automation is intentionally minimal
- policy engine and full PR/CI sync are not implemented yet
- company context persistence exists as local `.cotor/companies/...` snapshots, but it is still a lightweight knowledge layer
- `resume` inspects checkpoints but does not resume execution yet

Inspect `.cotor/companies/` in the working folder to review the persisted company state.

## Documentation

Start here:

- [Documentation Index](docs/INDEX.md)
- [Architecture Overview](docs/ARCHITECTURE.md)
- [English Guide](docs/README.md)
- [Korean Guide](docs/README.ko.md)
- [Quick Start](docs/QUICK_START.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Desktop App](docs/DESKTOP_APP.md)
- [Features](docs/FEATURES.md)
- [Validation Plan](docs/TEST_PLAN.md)
- [Contributing Guide](CONTRIBUTING.md)
- [Team Ops](docs/team-ops/README.md)
- [AI Agent Rules](AGENTS.md)

Historical reports, release notes, and architecture drafts are linked from [docs/INDEX.md](docs/INDEX.md) under `Historical / design records`.

## Validation

```bash
cotor version
./gradlew test
```
