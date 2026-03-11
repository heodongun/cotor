# Team Ops & Onboarding Package

This package is a first draft for contributors who need a predictable way to join the repo, pick a role, and leave reusable delivery artifacts behind.

## Who This Covers

- Feature owners shipping a ticket end to end
- Reviewers running code/documentation/QA passes
- Maintainers keeping docs, release flow, and operating rhythm aligned

## Start Here

1. Read `../README.md`, `../QUICK_START.md`, `../FEATURES.md`, and `../DESKTOP_APP.md`.
2. Build the repo once with `./gradlew test` or a scope-appropriate validation command.
3. Follow the delivery loop: reproduce -> plan -> edit -> validate -> review -> merge.
4. Pick the role template below and use its checklist as the operating baseline.

## First-Week Onboarding Checklist

- Confirm local build, shell scripts, and docs entry points work on your machine.
- Learn where execution evidence lives: Linear workpad, validation commands, PR summary, release notes.
- Review the current branch/PR policy and keep work scoped to one ticket at a time.
- Read one merged PR and one active PR to understand the repo's review style.

## Package Contents

- `FEATURE_OWNER_TEMPLATE.md`: ticket owner checklist and handoff template
- `REVIEWER_TEMPLATE.md`: review pass structure, blocking findings, and sign-off format
- `MAINTAINER_TEMPLATE.md`: weekly/release cadence and repo stewardship checklist
- `README.ko.md` and `*.ko.md`: Korean versions of the same package

## Shared Operating Rhythm

- Daily: leave a short async status note with scope, validation, and blocker state.
- Per ticket: capture reproduction evidence before edits and record validation after edits.
- Per review: enumerate blocking findings first, then residual risks and validation gaps.
- Per release/doc update: sync top-level entry points so new contributors can discover the workflow.

## Shared Copy/Paste Snippets

### Async status update

```md
Scope: <ticket or document area>
Changed: <what moved today>
Validated: <commands or walkthrough>
Risk/Blocker: <none or concise note>
```

### Risk note

```md
Risk: <specific failure mode>
Impact: <who/what is affected>
Mitigation: <validation, rollback, or scope reduction>
Owner: <role>
```
