# Cotor Features

This page lists features that are backed by the current codebase.

## CLI And Orchestration

- execution modes: `SEQUENTIAL`, `PARALLEL`, `DAG`
- conditional and loop stages
- direct pipeline execution fallback from the main entrypoint
- `interactive` TUI as the default surface when `cotor` is launched with no args
- `tui` alias for `interactive`

## Validation And Recovery

- `validate` for pipeline structure and dependency checks
- `lint` for YAML and config linting
- checkpoint inspection and cleanup via `resume` and `checkpoint gc`
- `status` for recent and running pipeline state
- `stats` for aggregated execution metrics
- `doctor` for local environment checks

## Template System

Current built-in template types:

1. `compare`
2. `chain`
3. `review`
4. `consensus`
5. `fanout`
6. `selfheal`
7. `verified`
8. `blocked-escalation`
9. `custom`

Supported template flows:

- `template --list`
- `template --preview <type>`
- `template --interactive`
- `template --fill key=value`

## Extension Surface

- `agent add`
- `agent list`
- `plugin init`

## Web Surface

- local web editor via `cotor web`
- `--port`, `--open`, `--read-only` options
- browser editor for pipeline authoring, YAML export, save, and run

## Desktop Surface

The current macOS shell includes:

- top-level `Company` and `TUI` modes
- company creation bound to one root folder per company
- company agent definition with title, CLI, and role summary
- company goal creation
- company issue selection and execution
- review queue inspection
- company activity feed
- runtime status and local runtime controls
- top session strip with live execution context switching
- collapsible detail drawer for changes, files, ports, browser, and review metadata
- switchable issue board and canvas in `Company` mode
- live TUI work area in `TUI` mode

## Autonomous Company Layer

The current implementation supports:

- create companies
- define company agents
- create goals
- decompose goals into issues
- delegate issues
- run issues by creating linked tasks
- review queue merge action
- runtime status, start, stop, and periodic tick loop
- lightweight company context persistence under `.cotor/companies/...`

Current limitations:

- the board is Linear-style inside the app; external Linear sync is not implemented
- follow-up issue generation is not implemented
- policy engine and rich PR/CI synchronization are not implemented
- runtime automation is intentionally minimal
