# Cotor - AI CLI Master-Agent System

[![English](https://img.shields.io/badge/Language-English-blue)](README.md)
[![한국어](https://img.shields.io/badge/Language-한국어-red)](README.ko.md)

Cotor is a Kotlin-based CLI/TUI tool for orchestrating AI-agent pipelines with validation, timeline monitoring, checkpoints, and recovery.

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
- Template generation (`compare`, `chain`, `review`, `consensus`, `fanout`, `selfheal`, `verified`, `release`, `custom`)
- `release` template placeholders: `{{verify_argv_json}}`, `{{verify_executable}}`, `{{commit_message}}`, `{{release_branch}}`, `{{release_tag}}`, `{{release_tag_message}}`, `{{release_title}}`, `{{release_notes_file}}`
- `release` template prerequisites: `git`, `gh`, verification command/toolchain, and a release notes file
- Agent preset management and plugin metadata inspection

## Docs Map

- Korean guide: `README.ko.md`
- Quick start: `QUICK_START.md`
- Desktop app: `DESKTOP_APP.md`
- Architecture: `ARCHITECTURE.md`
- Differentiated PRD / architecture: `DIFFERENTIATED_PRD_ARCHITECTURE.md`
- Features: `FEATURES.md`
- Team ops / onboarding: `team-ops/README.md`
- Usage tips: `USAGE_TIPS.md`
- Changelog: `release/CHANGELOG.md`
- Team ops/onboarding package: `team-ops/README.md`
- Reports: `reports/`
- Claude integration docs: `CLAUDE_SETUP.md`, `claude/`

## Notes

- If config is omitted, most commands use `cotor.yaml` by default.
- Running `cotor` with no args starts interactive mode; passing an unknown first argument runs simple direct pipeline execution.
