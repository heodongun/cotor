# Desktop App

The desktop app is a native macOS shell on top of the existing Kotlin runtime and localhost `cotor app-server`.

## Components

- `cotor app-server`
  - localhost API for repositories, workspaces, tasks, goals, issues, review queue, and runtime state
- `macos/`
  - SwiftUI shell
- `src/main/kotlin/com/cotor/app/`
  - repository, workspace, task, goal, issue, review-queue, and runtime services

## Run The Backend

```bash
./gradlew run --args='app-server --port 8787'
```

Optional local auth:

```bash
export COTOR_APP_TOKEN='your-local-token'
./gradlew run --args='app-server --port 8787 --token your-local-token'
```

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

You can update or remove the installed bundle from the CLI:

```bash
cotor update
cotor delete
```

## Current Shell Model

The current macOS shell has two top-level modes.

- `Company`
  - company selector
  - company creation bound to one root folder
  - agent-definition composer
  - goal list and goal creation
  - Linear-style issue board/canvas inside the app
  - company activity feed
  - runtime start/stop/status
- `TUI`
  - repository and workspace context
  - live terminal session strip
  - dominant center TUI surface
  - bottom detail drawer for changes, files, ports, browser, and review metadata

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
- `GET /api/app/companies/{companyId}/contexts`
- `GET /api/app/companies/{companyId}/runtime`
- `POST /api/app/companies/{companyId}/runtime/start`
- `POST /api/app/companies/{companyId}/runtime/stop`
- `PATCH /api/app/workspaces/{workspaceId}/base-branch`

Compatibility routes under `/api/app/company/*` still exist for older clients.

## What Works Today

- create multiple companies
- bind each company to one working folder
- define company agents with minimal user input
- create a company goal
- auto-decompose that goal into issues
- delegate and run issues
- inspect linked tasks and runs
- populate and merge review queue items
- inspect company activity
- start, stop, and inspect the local runtime loop
- prefer locally installed agent CLIs for default company profiles, with `echo` as a final fallback

## Current Limits

- macOS shell only
- the issue board is Linear-style inside Cotor; this build does not perform external Linear sync
- runtime automation does not yet include the planned policy engine or follow-up issue generation
- review and PR sync are local-state driven in this build, not full live GitHub/CI orchestration
- `resume` remains a checkpoint inspection flow, not full run resumption
