# Team Operations & Onboarding Package

This package is the repo-specific DX handbook for contributors and maintainers. Use it when onboarding a new teammate, preparing a change, or running release and documentation hygiene for Cotor.

## What This Package Covers

- A 30-minute setup checklist for first access
- A first-week checklist for shared ownership
- A lightweight operating rhythm tied to the repository's actual checks
- Copy/paste templates for updates, change plans, and release readiness

## 30-Minute Onboarding Checklist

- [ ] Clone the repository and confirm the default branch is up to date.
- [ ] Run `./gradlew test` once to verify the local Kotlin/Gradle toolchain.
- [ ] Run `./gradlew formatCheck` to confirm formatting matches CI expectations.
- [ ] Install local hooks with `./shell/install-git-hooks.sh`.
- [ ] Verify the local CLI entrypoint with `./shell/cotor version`.
- [ ] Read [docs/README.md](../README.md), [docs/INDEX.md](../INDEX.md), and [CONTRIBUTING.md](../../CONTRIBUTING.md).
- [ ] Review the CI contract in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml).
- [ ] Pick one issue and write down the exact validation path before editing files.

## First-Week Ownership Checklist

- [ ] Land one small change with matching English and Korean documentation when behavior changes.
- [ ] Update [`docs/release/CHANGELOG.md`](../release/CHANGELOG.md) whenever a user-facing workflow changes.
- [ ] Confirm whether desktop or macOS packaging docs need review when touching `macos/` or `shell/install-desktop-app.sh`.
- [ ] Reuse the PR checklist in [CONTRIBUTING.md](../../CONTRIBUTING.md) before opening review.

## Team Operating Rhythm

- Daily triage
  - Confirm the issue signal, target validation, and any doc impact before implementation starts.
- Before pushing a branch
  - Run `./gradlew formatCheck` and the narrowest proof that demonstrates the change.
- Before requesting review
  - Recheck EN/KR documentation sync, changelog impact, and CI-sensitive files.
- Release hygiene
  - Ensure `docs/release/CHANGELOG.md`, top-level docs links, and affected setup guides still point to the current workflow.

## Reusable Templates

### Async Update Template

```md
## Async Update

- Yesterday:
- Today:
- Risks / blockers:
- Validation evidence:
- Docs or release impact:
```

### Change Plan Template

```md
## Change Plan

- Problem / signal:
- Scope:
- Validation:
- Docs to update:
- Risks:
```

### Release Readiness Checklist

```md
## Release Readiness

- [ ] `./gradlew formatCheck`
- [ ] `./gradlew test`
- [ ] `docs/release/CHANGELOG.md` updated for user-facing changes
- [ ] English and Korean docs updated together
- [ ] Desktop/macOS docs reviewed if packaging or launch flow changed
- [ ] Manual smoke path recorded in the issue or PR
```
