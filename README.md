# Cotor

Cotor is a local-first AI workflow runner that grew into a company-style AI operating system: a CEO AI delegates work to subordinate AIs, CLI agents keep costs down, workflows stay visible in macOS, and a goal can drive an always-on issue loop. The same Kotlin core powers pipeline execution, the localhost `app-server`, and the native desktop shell.
Smoke test: `cotor version`

## What Is Current In This Build

- CLI/TUI orchestration with `SEQUENTIAL`, `PARALLEL`, and `DAG` pipelines
- Validation, linting, status, statistics, checkpoints, and template generation
- Local web editor with YAML export and run support
- macOS desktop shell backed by `cotor app-server`
- Multi-company operations layer with companies, agent definitions, goals, issues, review queue, activity feed, and runtime start/stop/status
- Per-agent git branch and worktree isolation for delegated execution

## Current Command Surface

Top-level commands registered in `Main.kt`:

`init`, `run`, `dash`, `interactive`, `validate`, `test`, `template`, `resume`, `checkpoint`, `stats`, `doctor`, `status`, `list`, `web`, `app-server`, `lint`, `explain`, `plugin`, `agent`, `version`, `completion`

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
brew install cotor
```

This installs JDK 17 + the CLI and packages a bundled desktop app asset.
Run `cotor install` after `brew install` to copy `Cotor Desktop.app` into Applications.
`cotor install` / `cotor update` reuse the packaged app instead of rebuilding from the Homebrew prefix.
`cotor install` prints the exact installed app path and falls back to `~/Applications` when `/Applications` is not writable.
For the full packaged-install behavior, first-run paths, and troubleshooting flow, see [docs/HOMEBREW_INSTALL.md](docs/HOMEBREW_INSTALL.md).

Or use the one-liner:

```bash
curl -fsSL https://raw.githubusercontent.com/bssm-oss/cotor/master/shell/brew-install.sh | bash
```

Update:

```bash
brew upgrade cotor
```

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
- start and stop a local autonomous runtime loop per company

Current limits in this build:

- the app uses a Linear-style board inside Cotor; it is not a live external Linear sync
- runtime automation is intentionally minimal
- policy engine, rich follow-up generation, and full PR/CI sync are not implemented yet
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
