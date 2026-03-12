# Cotor

Cotor is a local-first AI workflow runner that grew into a company-style AI operating system: a CEO AI delegates work to subordinate AIs, CLI agents keep costs down, workflows stay visible in macOS, and a goal can drive an always-on issue loop. The same Kotlin core powers pipeline execution, the localhost `app-server`, and the native desktop shell.

## What Is Current In This Build

- CLI/TUI orchestration with `SEQUENTIAL`, `PARALLEL`, and `DAG` pipelines
- Validation, linting, status, statistics, checkpoints, and template generation
- Local web editor with YAML export and run support
- macOS desktop shell backed by `cotor app-server`
- Multi-company operations layer with companies, agent definitions, goals, issues, review queue, activity feed, and runtime start/stop/status

## Current Command Surface

Top-level commands registered in `Main.kt`:

`init`, `run`, `dash`, `interactive`, `validate`, `test`, `template`, `resume`, `checkpoint`, `stats`, `doctor`, `status`, `list`, `web`, `app-server`, `lint`, `explain`, `plugin`, `agent`, `version`, `completion`

Important entry behavior:

- `cotor` with no args launches `interactive`
- `cotor tui` is an alias to `interactive`
- unknown first args fall back to direct pipeline execution

Current subcommand support:

- `agent add`, `agent list`
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

## Quick Start

```bash
git clone https://github.com/yourusername/cotor.git
cd cotor
./gradlew shadowJar
chmod +x shell/cotor
./shell/cotor version
```

Common first commands:

The final `./shell/cotor version` call is the quickest smoke test for the local CLI wrapper.

```bash
cotor
cotor --short
cotor init --starter-template
cotor template --list
cotor validate <pipeline> -c <config>
cotor run <pipeline> -c <config> --output-format text
cotor app-server --port 8787
```

## macOS Desktop

Build and install the local app bundle:

```bash
cotor install
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

Manage the installed app bundle directly from the CLI:

```bash
cotor install
cotor update
cotor delete
```

Current desktop model:

- top-level `Company` and `TUI` shell modes
- `Company` mode for multi-company operations, agent roster, goals, issue board/canvas, activity feed, and runtime controls
- `TUI` mode for repository/workspace execution with a live center terminal session
- top session strip for active execution contexts
- collapsible detail drawer for changes, files, ports, browser, and review metadata

## Autonomous Company Status

The current build includes a working local operations layer:

- create multiple companies, each bound to one working folder
- define company agents with only title, CLI, and role summary
- create company goals
- decompose goals into issues
- delegate and run issues
- populate a review queue
- inspect runtime status and recent company activity
- start and stop a local autonomous runtime loop per company

Current limits in this build:

- the app uses a Linear-style board inside Cotor; it is not a live external Linear sync
- runtime automation is intentionally minimal
- policy engine, rich follow-up generation, and full PR/CI sync are not implemented yet
- company context persistence exists as local `.cotor/companies/...` snapshots, but it is still a lightweight knowledge layer
- `resume` inspects checkpoints but does not resume execution yet

## Documentation

Start here:

- [Documentation Index](docs/INDEX.md)
- [English Guide](docs/README.md)
- [Korean Guide](docs/README.ko.md)
- [Quick Start](docs/QUICK_START.md)
- [Desktop App](docs/DESKTOP_APP.md)
- [Features](docs/FEATURES.md)
- [Validation Plan](docs/TEST_PLAN.md)
- [Team Ops](docs/team-ops/README.md)
- [AI Agent Rules](AGENTS.md)

Historical reports, release notes, and architecture drafts are linked from [docs/INDEX.md](docs/INDEX.md) under `Historical / design records`.

## Validation

Current baseline checks:

```bash
./gradlew --no-build-cache test -x jacocoTestCoverageVerification
cd macos && swift build
```
