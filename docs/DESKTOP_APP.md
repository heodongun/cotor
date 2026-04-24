# Desktop App

The desktop app is a native macOS shell on top of the existing Kotlin runtime and localhost `cotor app-server`.

## Install via Homebrew (Recommended)

```bash
brew tap bssm-oss/cotor https://github.com/bssm-oss/cotor.git
brew install cotor    # Installs CLI + bundled desktop asset + JDK 17
cotor install         # Copies Cotor Desktop.app into Applications
```

The Homebrew package carries a bundled `Cotor Desktop.app` asset. `cotor install`
and `cotor update` reuse that packaged bundle instead of rebuilding from the Homebrew prefix.
When `cotor` launches interactive mode with no local config in packaged installs, it writes the
starter config under `~/.cotor/interactive/default/cotor.yaml`.
See `docs/HOMEBREW_INSTALL.md` for the full packaged-install and first-run behavior.

Or one-liner:

```bash
curl -fsSL https://raw.githubusercontent.com/bssm-oss/cotor/master/shell/brew-install.sh | bash
```

## Install from Source

```bash
cotor install    # Build + install to /Applications
cotor update     # Rebuild + reinstall
cotor delete     # Remove app
```

## Components

- `cotor app-server`
  - localhost API for repositories, workspaces, tasks, goals, issues, review queue, and runtime state
- `macos/`
  - SwiftUI shell
- `src/main/kotlin/com/cotor/app/`
  - repository, workspace, task, goal, issue, review-queue, and runtime services

## Run The Backend

```bash
cotor app-server --port 8787
```

Optional local auth:

```bash
export COTOR_APP_TOKEN='your-local-token'
cotor app-server --port 8787 --token your-local-token
```

Optional MCP control auth:

```bash
export COTOR_APP_CONTROL_TOKEN='your-local-control-token'
cotor app-server --port 8787 --token your-local-token --control-token your-local-control-token
```

`/api/app/mcp` is read-only. Mutating MCP tools live under `/api/app/mcp/control` and require the control token.

## Run The macOS App

```bash
swift run --package-path macos CotorDesktopApp
```

Optional backend override:

```bash
export COTOR_APP_SERVER_URL='http://127.0.0.1:8787'
export COTOR_APP_TOKEN='your-local-token'
swift run --package-path macos CotorDesktopApp
```

## Install A Local App Bundle

```bash
cotor install
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

The bundle starts the local backend lazily when needed.
Closing the last desktop window quits the app and shuts down the bundled backend.

You can update or remove the installed bundle from the CLI:

```bash
cotor update
cotor delete
```

Behavior depends on the install layout:

- Homebrew / packaged install
  - `cotor install` and `cotor update` copy the packaged desktop bundle from the install root
  - no Gradle or Swift rebuild happens at runtime
- Source checkout
  - `cotor install` and `cotor update` rebuild the desktop bundle locally, then install it

## Current Shell Model

The current macOS shell has two top-level modes.

- `Company`
  - company selector
  - company creation bound to one root folder
  - direct `Meeting Room` navigation for a live company floor map with an event wall, active seats, and a review desk
  - agent-definition composer
  - goal list and goal creation
  - Linear-style issue board/canvas inside the app
  - right-side `Chat Control` rail that shows live messages/context, backend memory snapshot, confirmation-first proposal previews, and lightweight lead/worker agent routing controls
- company activity feed with live event-driven updates
- live company updates use the company event stream plus a focused company dashboard snapshot, not a heavyweight full refresh on every event
- issue execution detail cards now show agent CLI, selected model, backend kind, process id, assigned prompt, stdout/stderr, branch, PR link, and publish summary for each issue-linked run
- if the live company stream disconnects, the UI keeps the last company snapshot and shows `Live company updates disconnected. Re-syncing...` while it recovers
  - compact company summary banner that keeps runtime health, blocked workflows, review attention, and the latest error/action in one place
  - compact company summary and company settings now surface estimated spend plus daily/monthly cost guardrails for the selected runtime
  - scrollable issue-board lanes so tall blocked/review queues stay readable inside the fixed board surface
  - stale Cotor-managed retry PRs are reconciled and closed in batches so the review loop does not keep piling up obsolete open PRs
  - stale CEO merge-conflict blocks are reopened automatically once the linked GitHub PR reports a clean merge state again
  - legacy CEO merge-conflict blockers that left execution stuck in `BLOCKED` are pushed back to `PLANNED` so the company can rebase and republish on the next wave
  - stale execution issues that were accidentally left blocked after a PR already merged are closed automatically on the next runtime tick
  - runtime start/stop/status
  - an explicit runtime stop remains sticky across app restarts and company refreshes until the user starts that company again
  - company mode uses a focused company dashboard snapshot instead of forcing a full desktop refresh on every event
- when one wave of goal work finishes, the CEO planning lane can reopen for the next wave instead of freezing the goal after the first decomposition
- continuous improvement goals now ask for multi-issue portfolios and parallel branchable work when the roster can support it
- short high-level goal descriptions are enriched into a broader execution portfolio so larger rosters do not collapse into only one or two issues
- runtime dispatch is no longer forced to wait for a stale polling tick before reacting to new runnable work, and multiple runnable issues can start in parallel even when several company roles share the same execution CLI
- local merge completion is only recorded after GitHub confirms the refreshed pull request state is actually `MERGED`
- `TUI`
  - independent from company workflow state
  - folder or repository selection for launching standalone `cotor` sessions
  - multiple live TUI sessions can stay open in parallel
  - dominant center terminal surface focused on the currently selected session

## Repository And Run Isolation

- each agent run gets its own branch named `codex/cotor/<task-slug>/<agent-name>`
- each agent run gets its own worktree under `.cotor/worktrees/<task-id>/<agent-name>`
- re-running the same task reuses the existing isolated worktree

## Current Company API Surface

Current company-first routes:

- `GET /api/app/companies`
- `POST /api/app/companies`
- `GET /api/app/companies/{companyId}`
- `PATCH /api/app/companies/{companyId}`
- `GET /api/app/companies/{companyId}/agents`
- `POST /api/app/companies/{companyId}/agents`
- `PATCH /api/app/companies/{companyId}/agents/{agentId}`
- `GET /api/app/companies/{companyId}/projects`
- `GET /api/app/companies/{companyId}/goals`
- `POST /api/app/companies/{companyId}/goals`
- `GET /api/app/companies/{companyId}/issues`
- `GET /api/app/companies/{companyId}/review-queue`
- `GET /api/app/companies/{companyId}/activity`
- `GET /api/app/companies/{companyId}/dashboard`
- `GET /api/app/companies/{companyId}/contexts`
- `GET /api/app/companies/{companyId}/runtime`
- `POST /api/app/companies/{companyId}/runtime/start`
- `POST /api/app/companies/{companyId}/runtime/stop`
- `PATCH /api/app/companies/{companyId}/linear`
- `POST /api/app/companies/{companyId}/linear/resync`
- `PATCH /api/app/workspaces/{workspaceId}/base-branch`

Compatibility routes under `/api/app/company/*` still exist for older clients.

## What Works Today

- create multiple companies
- bind each company to one working folder
- define company agents with minimal user input
- store an optional per-agent model override alongside the provider CLI so company roles can pin Codex/OpenCode models explicitly
- create a company goal
- auto-decompose that goal into issues
- delegate and run issues
- mirror company issues and progress to Linear when company-scoped Linear sync is enabled
- inspect linked tasks and runs
- populate and merge review queue items
- inspect a dedicated Meeting Room view with synthesized runtime/backend/review/session wall events plus seat/review desk summaries
- use the Chat Control rail to preview and explicitly confirm goal creation, goal decomposition, issue creation, issue delegation, issue execution, QA/CEO verdicts, merge, runtime control, backend control, and company-agent creation
- choose the lead AI and worker roster directly from the Chat Control rail before staging a confirmed request
- inspect company activity without manual refresh in normal company mode
- inspect runtime health, blocked/review attention, and the latest runtime signal from the compact company summary banner
- inspect estimated spend and adjust daily/monthly cost guardrails without leaving the company console
- warn during company creation when GitHub PR publishing is required but the repository is not ready for `gh`/`origin` publishing
- start, stop, and inspect the local runtime loop
- keep an explicit company stop sticky until the user presses Start again, even if active autonomous goals still exist
- keep active company work on a fast monitoring cadence so stale `RUNNING` tasks/runs are reconciled sooner
- re-queue company issues that were interrupted by an app-server shutdown instead of leaving them blocked by a generic process-exit failure
- resume queued delegated company work after the desktop app and bundled backend come back, and record that recovery in the live company activity feed
- create durable run snapshots for company issue execution by default so the issue `durableRunId` can be inspected with `cotor resume inspect <run-id>`
- prefer locally installed agent CLIs for default company profiles, with `echo` as a final fallback

## Current Limits

- macOS shell only
- Linear sync is company-scoped and mirrors Cotor-managed issues outward; it does not yet import existing Linear issues back into Cotor
- runtime automation now includes a policy engine v1 for action allow/deny/approval decisions, but it is still file-backed and experimental
- review and PR sync now include a GitHub control-plane v1 with PR state, mergeability, and status-check summary syncing through `gh`
- company issue execution is inspectable through durable run snapshots by default; generic pipeline replay with `resume continue/fork/approve` and full company issue/review continuation are still incomplete
