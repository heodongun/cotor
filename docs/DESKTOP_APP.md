# Desktop App

The desktop foundation adds a native macOS shell on top of the existing Kotlin runtime.

## Components

- `cotor app-server`
  Serves the localhost HTTP API consumed by the desktop client.
- `macos/`
  SwiftUI package for the macOS shell.
- `src/main/kotlin/com/cotor/app/`
  Desktop-specific repository, workspace, task, and worktree services.

## Run The Backend

```bash
./gradlew run --args='app-server --port 8787'
```

Optional auth:

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

## Build And Download A Local Copy

Build the release app bundle, install it into `Applications`, and refresh the download archive:

```bash
./shell/install-desktop-app.sh
```

The script refreshes:

- `/Applications/Cotor Desktop.app` when writable, otherwise `~/Applications/Cotor Desktop.app`
- `~/Downloads/Cotor Desktop.app`
- `~/Downloads/Cotor-Desktop-macOS.zip`

Launch the installed app:

```bash
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

The installed bundle checks whether `cotor app-server` is already listening on `127.0.0.1:8787`. If not, it starts the bundled backend jar before opening the native SwiftUI shell. The launcher expects Java 17 or newer to be available locally.

## Isolation Model

- Each task fans out into one isolated agent run per selected agent.
- Each agent run gets its own git branch named `codex/cotor/<task-slug>/<agent-name>`.
- Each agent run gets its own worktree under `.cotor/worktrees/<task-id>/<agent-name>`.
- Re-running an existing task reuses the same worktree instead of creating duplicates.

## Current Scope

- Repository registration from a local path or by cloning a Git URL.
- Repository-level base branch discovery and branch picker for workspace creation.
- Workspace creation pinned to a selected base branch.
- Multi-agent task creation and launch.
- Diff, file tree, run-state inspection, and an embedded browser in the macOS client.
- PID-based local port discovery for agent runs via `lsof`.
- Read-only settings screen for app paths, available agents, and current keyboard shortcuts.
