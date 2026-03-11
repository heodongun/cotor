# Cotor - Delivery-Grade AI Orchestration Workbench

[![English](https://img.shields.io/badge/Language-English-blue)](README.md)
[![한국어](https://img.shields.io/badge/Language-한국어-red)](README.ko.md)

Cotor is a Kotlin-based orchestration workbench for building AI-agent pipelines that can be validated, reviewed, and operated repeatedly. The core idea is simple: keep the speed of experimentation, but graduate the workflow into a delivery-ready asset.

## Positioning: Cotor vs Paperclip

Paperclip is effective when a team wants to sketch a workflow quickly in a visual environment. Cotor is the complementary tool for the stage after that:

- Cotor stores workflows as versioned YAML rather than keeping them only as canvases.
- Cotor adds validation, checkpointing, and recovery before a run becomes operational risk.
- Cotor exposes the same workflow through CLI, TUI, web, and desktop surfaces so engineering and operators can share one source of truth.
- Cotor is optimized for local repository-aware work, including desktop worktrees and execution monitoring.

If the outcome needs to be reproducible, reviewable, and production-adjacent, start from Cotor or migrate the flow into Cotor.

## Quick Install

```bash
git clone https://github.com/yourusername/cotor.git
cd cotor
./shell/install-global.sh
```

Local-only setup:
```bash
./gradlew shadowJar
chmod +x shell/cotor
./shell/cotor version
```

## Desktop App (macOS)

Build the desktop bundle, install it into `Applications`, and refresh the local download package:

```bash
./shell/install-desktop-app.sh
```

Then launch it with:

```bash
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

Full backend, workspace isolation, and browser/ports details live in `DESKTOP_APP.md`.

## Use It Fast

```bash
cotor                            # launch interactive mode (default)
cotor --short                    # 10-line cheat sheet
cotor init --interactive         # interactive bootstrap
cotor list -c cotor.yaml         # list registered agents
cotor validate <pipeline> -c <yaml>
cotor run <pipeline> -c <yaml> --dry-run
cotor run <pipeline> -c <yaml> --output-format text
cotor template --list            # list built-in templates
cotor agent add claude --yes     # create .cotor/agents preset
cotor plugin list                # inspect plugin metadata
cotor stats                      # pipeline statistics
cotor doctor                     # environment diagnostics
cotor dash -c <yaml>             # codex-style dashboard
cotor web                        # web pipeline studio
```

## Command Surface (current)

Primary subcommands: `init`, `list`, `run`, `validate`, `test`, `template`, `resume`, `checkpoint`, `stats`, `doctor`, `status`, `dash`, `interactive`, `web`, `lint`, `explain`, `plugin`, `agent`, `version`, `completion`.

## Core Highlights

- Sequential / Parallel / DAG orchestration with stage dependencies
- Decision and loop stage execution support
- Real-time timeline collection + watch mode monitoring
- Checkpoint save/resume and checkpoint garbage collection
- Output formatting in `json`, `csv`, `text`
- Template generation (`compare`, `chain`, `review`, `consensus`, `fanout`, `selfheal`, `verified`, `custom`)
- Agent preset management and plugin metadata inspection

## Docs Map

- Korean guide: `README.ko.md`
- Quick start: `QUICK_START.md`
- Desktop app: `DESKTOP_APP.md`
- Web editor: `WEB_EDITOR.md`
- Architecture: `ARCHITECTURE.md`
- Features: `FEATURES.md`
- Upgrade recommendations: `UPGRADE_RECOMMENDATIONS.md`
- Usage tips: `USAGE_TIPS.md`
- Changelog: `release/CHANGELOG.md`
- Reports: `reports/`
- Claude integration docs: `CLAUDE_SETUP.md`, `claude/`

## Notes

- If config is omitted, most commands use `cotor.yaml` by default.
- Running `cotor` with no args starts interactive mode; passing an unknown first argument runs simple direct pipeline execution.
