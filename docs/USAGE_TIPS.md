# Cotor Usage Tips

## Fast Discovery

- `cotor --short`: 10-line cheat sheet
- `cotor --help`: top-level command help
- `cotor template --list`: inspect current template inventory
- `cotor app-server --help`: verify local desktop backend options

## Safe Execution Loop

- run `cotor validate <pipeline> -c <config>` before `run`
- use `--dry-run` when changing execution mode or dependencies
- check `cotor doctor` before blaming pipeline logic for missing CLIs
- use `status` and `stats` together after a failed run

## Current Command Caveats

- `resume` lists and inspects checkpoints; it does not resume execution yet
- `plugin` currently exposes `plugin init` only
- `cotor` with no args opens the interactive TUI
- `cotor tui` is just an alias to `interactive`

## Desktop Operator Tips

- treat the session strip as the fast context switcher between issue/run contexts
- keep the detail drawer collapsed unless you need diffs, files, ports, browser, or review metadata
- use board/canvas as an operations view, not the default live execution surface

## Autonomous Company Tips

- create one focused goal instead of a vague multi-goal brief
- review the generated issues before starting the runtime
- use runtime start/stop/status to verify the loop is active
- treat `Linear` sync as non-live until the adapter is implemented
