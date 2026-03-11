# Team Operations & Onboarding Package

This package helps maintainers run the project consistently, gives new contributors a predictable onboarding path, and provides reusable role templates for delivery work.

## What's Included

- `onboarding-checklist.md`: Day-0 to Day-14 onboarding checklist.
- `operating-cadence-template.md`: Weekly/biweekly operating rhythm template.
- `handoff-template.md`: Reusable handoff template for incidents, release ownership, and vacation coverage.
- `FEATURE_OWNER_TEMPLATE.md`: Ticket-owner checklist and handoff template.
- `REVIEWER_TEMPLATE.md`: Review structure, blocking-findings format, and sign-off expectations.
- `MAINTAINER_TEMPLATE.md`: Repo stewardship checklist covering docs, release flow, and operating rhythm.
- `README.ko.md`: Korean mirror of this package.

## Who This Covers

- Feature owners shipping a ticket end to end.
- Reviewers running code, documentation, and QA passes.
- Maintainers keeping docs, release flow, and operating rhythm aligned.

## Start Here

1. Read `../README.md`, `../QUICK_START.md`, `../FEATURES.md`, and `../DESKTOP_APP.md`.
2. Build the repo once with `./gradlew test` or a scope-appropriate validation command.
3. Follow the delivery loop: reproduce -> plan -> edit -> validate -> review -> merge.
4. Complete the onboarding checklist, then pick the role template that matches your current responsibility.

## Recommended Usage

1. **Assign an onboarding buddy** for each new contributor.
2. **Copy templates into your issue/PR/wiki tools** and adjust only project-specific fields.
3. **Run a monthly retro** on this package and update sections that drift from real team practice.

## Ownership

- Primary owner: repository maintainers.
- Review cadence: once per month (or after major process changes).
- Update trigger examples:
  - New release train/cadence.
  - On-call rotation updates.
  - CI/CD or branching policy changes.

---

## Quick Start for New Maintainers

- [ ] Read repository-level docs (`README.md`, `CONTRIBUTING.md`, docs index).
- [ ] Complete the onboarding checklist.
- [ ] Propose your first documentation or small tooling PR within 1 week.
- [ ] Shadow at least one release/triage cycle.
- [ ] Lead one cycle using the cadence template and handoff template.

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
