# Homebrew Install And First-Run Guide

This guide describes the actual packaged install behavior for `brew install cotor`.

Use this document when you want a predictable first run from Homebrew, when you need to understand where files are written, or when you want to recover from a broken local setup without rebuilding from source.

## What `brew install cotor` Installs

`brew install cotor` installs:

- the `cotor` CLI
- JDK 17
- a packaged `Cotor Desktop.app` asset inside the Homebrew prefix

It does not copy the desktop app into Applications automatically.

After install, run:

```bash
cotor install
```

That command copies the packaged desktop bundle into:

- `/Applications` when writable
- `~/Applications` when `/Applications` is not writable

You can then launch it with:

```bash
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

## What Happens On The First `cotor` Run

If you run `cotor` with no local `cotor.yaml` in the current directory, packaged installs use a home-backed starter config:

- config path: `~/.cotor/interactive/default/cotor.yaml`
- transcript path: `~/.cotor/interactive/default/`
- session debug log: `~/.cotor/interactive/default/interactive.log`

The first run writes that starter config automatically.

Starter selection rules:

- prefer a real AI starter only if it is actually ready to answer
- otherwise fall back to the safe `example-agent` echo starter

That means a fresh install should not fail just because an AI CLI is installed but not authenticated.

## Local Config Still Wins

If you run `cotor` inside a folder that already has `cotor.yaml`, Cotor uses that local config instead of the packaged home starter.

This is intentional.

Examples:

- in an empty folder: packaged starter config is created under `~/.cotor/interactive/default`
- in a repo with `./cotor.yaml`: that repo config is used immediately

## Packaged Install Behavior vs Source Checkout

### Homebrew / packaged install

- `cotor install` copies the packaged app bundle
- `cotor update` recopies the packaged app bundle
- `cotor delete` removes installed app artifacts
- no Gradle rebuild happens at runtime
- no Swift rebuild happens at runtime

### Source checkout

- `cotor install` rebuilds the desktop app locally, then installs it
- `cotor update` rebuilds and reinstalls

## Fresh-Install Safety Guarantees

Current packaged behavior is designed so these cases do not break first run:

- current working directory is not writable
- `HOME` is redirected by Homebrew or a sandbox
- `.cotor/worktrees/...` contains large runtime data or unrelated YAML files
- no authenticated AI CLI is ready yet

The config loader only treats dedicated config override roots as config sources:

- `~/.cotor/*.yaml`
- `~/.cotor/agents/*.yaml`
- `<project>/.cotor/*.yaml`
- `<project>/.cotor/agents/*.yaml`

Runtime snapshots, worktree copies, editor assets, and other large `.cotor` subtrees are not treated as configuration.

## Common Commands

Install desktop app:

```bash
cotor install
```

Reinstall packaged app:

```bash
cotor update
```

Remove installed app artifacts:

```bash
cotor delete
```

See CLI help:

```bash
cotor help
cotor help --lang ko
cotor help --lang en
```

Run one interactive turn:

```bash
cotor interactive --prompt "hello"
```

## Troubleshooting

### `cotor` starts in a repo and does not use the packaged starter

Cause:

- the current directory already contains `cotor.yaml`

What to do:

- run `cotor` from an empty folder if you want the packaged starter flow
- or edit the local `cotor.yaml` if that repo is the intended working config

### The desktop app is installed but not in `/Applications`

Cause:

- `/Applications` was not writable

What to do:

- check `~/Applications/Cotor Desktop.app`
- use the `Installed:` line from `cotor install`

### First run falls back to `example-agent`

Cause:

- no authenticated AI CLI or API key was ready

What to do:

- authenticate your preferred AI CLI
- or export the required API key
- then rerun `cotor`; the starter can be regenerated when a real AI path becomes ready

### You want to reset the packaged starter config

Remove:

```bash
rm -rf ~/.cotor/interactive/default
```

Then run:

```bash
cotor
```

### You want to inspect a failed interactive session

Check:

- `~/.cotor/interactive/default/interactive.log` for packaged default sessions
- or the explicit `--save-dir` you passed

## Validation Commands We Use For Packaged Installs

Useful smoke checks:

```bash
cotor version
cotor interactive --help
cotor install
```

If you want to confirm the starter path:

```bash
ls ~/.cotor/interactive/default
```

If you want to verify the installed desktop app path:

```bash
ls "/Applications/Cotor Desktop.app" "$HOME/Applications/Cotor Desktop.app"
```
