# Cotor Quick Start

This page keeps the fastest current setup path only.

## Homebrew Install (Recommended)

```bash
# One-liner install (JDK 17 + CLI + bundled desktop app asset)
curl -fsSL https://raw.githubusercontent.com/bssm-oss/cotor/master/shell/brew-install.sh | bash
```

Or install manually:

```bash
brew tap bssm-oss/cotor https://github.com/bssm-oss/cotor.git
brew install cotor
```

Verify the install:

```bash
cotor version
cotor install
open "/Applications/Cotor Desktop.app"
```

Notes:

- The Homebrew formula installs the packaged desktop bundle, but the actual copy into Applications happens only when you explicitly run `cotor install` in your shell.
- `cotor install` prints the installed app path and automatically falls back to `~/Applications` if `/Applications` is not writable.
- On the first `cotor` run after `brew install cotor`, if there is no local `cotor.yaml`, the starter config is created under `~/.cotor/interactive/default/cotor.yaml`.
- For full packaged-install behavior and troubleshooting, see `docs/HOMEBREW_INSTALL.md` / `docs/HOMEBREW_INSTALL.ko.md`.
- The starter config only auto-selects AI CLIs or API-backed agents that can actually respond at first run; unavailable CLIs are excluded from the starter profile.

Update:

```bash
brew upgrade cotor
```

## Install From Source

1. `git clone https://github.com/bssm-oss/cotor.git`
2. `cd cotor`
3. `./shell/cotor version`  (auto-detects JDK 17 and builds `shadowJar` as needed)

## macOS Desktop App

```bash
cotor install    # packaged install: copy bundled app / source checkout: build then install
cotor update     # packaged install: reinstall bundle / source checkout: rebuild then reinstall
cotor delete     # remove the installed app
```

## app-server Fast Path

```bash
./gradlew run --args='app-server --port 8787'
```

In a separate shell:

```bash
swift run --package-path macos CotorDesktopApp
```

## Autonomous Company Quick Check

Minimal validation flow for the current build:

1. Launch the desktop app or `app-server`
2. Create a company goal
3. Inspect the generated issues
4. Run an issue
5. Inspect the review queue
6. Start the runtime and confirm its status

## Frequently Used Commands

```bash
cotor
cotor help
cotor help --lang en
cotor --short
cotor list -c cotor.yaml
cotor status
cotor stats
cotor checkpoint gc --dry-run
cotor lint cotor.yaml
cotor explain cotor.yaml <pipeline>
cotor web --open
cotor app-server --port 8787
```

## Caveats

- `resume` is currently a checkpoint inspection flow, not full execution resumption.
- `plugin` currently exposes `plugin init` only.
- `Linear` sync is still a placeholder in this build.
