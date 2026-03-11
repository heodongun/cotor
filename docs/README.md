# Cotor Documentation

This guide reflects the current codebase. Use [INDEX.md](INDEX.md) as the router for current docs vs historical records.

## Start Here

- [QUICK_START.md](QUICK_START.md): fast setup and first commands
- [FEATURES.md](FEATURES.md): code-backed capability inventory
- [DESKTOP_APP.md](DESKTOP_APP.md): `app-server` and macOS shell
- [TEST_PLAN.md](TEST_PLAN.md): automated and manual validation matrix
- [team-ops/README.md](team-ops/README.md): onboarding and maintainer workflows

## Product Snapshot

Cotor currently ships three operator surfaces on top of the same Kotlin core:

- CLI/TUI workflow execution
- local web editor
- macOS desktop shell backed by `cotor app-server`

The desktop shell also exposes the current company-first operations layer:

- multiple companies mapped to working folders
- company agent definitions
- goals
- issues
- review queue
- activity feed
- runtime start/stop/status

## Actual CLI Surface

Top-level commands:

`init`, `run`, `dash`, `interactive`, `validate`, `test`, `template`, `resume`, `checkpoint`, `stats`, `doctor`, `status`, `list`, `web`, `app-server`, `lint`, `explain`, `plugin`, `agent`, `version`, `completion`

Current subcommands:

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

## Current Known Limits

- `resume` is checkpoint inspection, not full execution resume
- `plugin` currently scaffolds plugins with `plugin init`; list/validate flows are not live commands
- the board is intentionally Linear-like, but this build does not depend on external Linear sync
- the autonomous runtime is intentionally minimal and does not include the full policy engine or rich follow-up automation plan

## Reference Map

- [ARCHITECTURE.md](ARCHITECTURE.md): shared runtime architecture
- [WEB_EDITOR.md](WEB_EDITOR.md): web editor usage
- [USAGE_TIPS.md](USAGE_TIPS.md): operator shortcuts
- [CONDITION_DSL.md](CONDITION_DSL.md): condition DSL
- [cookbook.md](cookbook.md): example workflows
- [CLAUDE_SETUP.md](CLAUDE_SETUP.md): Claude integration setup
- [templates/temp-cotor-template.md](templates/temp-cotor-template.md): template prompt/source note
