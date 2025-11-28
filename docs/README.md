# Cotor - AI CLI Master-Agent System

[![English](https://img.shields.io/badge/Language-English-blue)](README.md)
[![한국어](https://img.shields.io/badge/Language-한국어-red)](README.ko.md)

Kotlin-based CLI for orchestrating multi-agent AI pipelines with realtime monitoring, validation, and dashboards.

## Quick Install

```bash
git clone https://github.com/yourusername/cotor.git
cd cotor
./shell/install-global.sh
```

Local-only (no symlink):
```bash
./gradlew shadowJar
chmod +x shell/cotor
./shell/cotor version
```

Claude Code integration (slash commands + knowledge base):
```bash
./shell/install-claude-integration.sh
```

## Use It Fast

```bash
cotor init                         # create cotor.yaml
cotor list                         # view registered agents
cotor validate <pipeline> -c <yaml>
cotor run <pipeline> -c <yaml> --output-format text
cotor template                     # list built-in templates
cotor dash -c <yaml>               # TUI dashboard
cotor web                          # launch web pipeline studio
cotor completion zsh|bash|fish     # shell autocompletion
alias co="cotor"                   # faster typing
```

### Ready-to-run examples
- `examples/single-agent.yaml` – 단일 에이전트 Hello
- `examples/parallel-compare.yaml` – 병렬 비교
- `examples/decision-loop.yaml` – 조건/루프
- `examples/run-examples.sh` – 위 3개를 한 번에 실행

## Core Highlights

- Coroutine-based async execution across sequential, parallel, and DAG flows
- Decision/loop stages, checkpoints, and recovery strategies
- Timeline monitor with summaries and aggregated outputs
- YAML-friendly configs with validation and syntax checks
- Web dashboard + CLI/TUI for discovery and execution

## Docs Map

- Overview: `README.md` (this file) · `README.ko.md`
- Upgrades: `UPGRADE_GUIDE.md` · `UPGRADE_RECOMMENDATIONS.md`
- Releases: `release/CHANGELOG.md` · `release/FEATURES_v1.1.md`
- Reports: `reports/TEST_REPORT.md` · `reports/IMPLEMENTATION_SUMMARY.md`
- Quick start: `QUICK_START.md`
- Claude setup: `CLAUDE_SETUP.md`
- Claude Code integration: `shell/install-claude-integration.sh`
- Usage tips: `USAGE_TIPS.md`
- Templates: `templates/`

## Run Checks

```bash
./gradlew test
./shell/cotor version
```

Optional smoke scripts (from repo root):
```bash
./shell/test-cotor-enhanced.sh
./shell/test-cotor-pipeline.sh
./shell/test-claude-integration.sh
```

Need a 10-line cheat sheet? Run `cotor --short`.

## Shell Scripts

- `./shell/cotor` – CLI entrypoint (builds the shaded JAR if missing)
- `./shell/install-global.sh` – build + global install (symlink)
- `./shell/install.sh` – local install with alias instructions
- `./shell/install-claude-integration.sh` – Claude Code commands/knowledge base
- `./shell/test-*` – smoke and integration checks

## Need AI CLIs?

Install per provider as needed:
```bash
# Claude CLI (npm)
# Copilot CLI
# Gemini / OpenAI / others
pip install openai
```
