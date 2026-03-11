# Team Operations & Onboarding

This package is the maintainer-facing operations layer for the current Cotor repo.

## Start Here

1. Read `../README.md`, `../FEATURES.md`, `../DESKTOP_APP.md`, and `../TEST_PLAN.md`
2. Follow repo-wide AI and contribution rules in `../../AGENTS.md` and `../../CONTRIBUTING.md`
3. Validate the repo with the scope-appropriate commands before opening a PR

## What This Package Covers

- onboarding checklist
- operating cadence
- feature owner handoff
- reviewer checklist
- maintainer stewardship checklist

## Current Delivery Loop

1. reproduce or map current behavior
2. make the smallest sufficient change
3. validate with the matching test matrix
4. document current behavior, known limits, and follow-up work
5. review and merge

## Company-First Notes

- Treat `Company` as the top-level product unit when the change touches goals, issues, review queue, runtime, or context persistence.
- Treat `repository/workspace/task/run` as execution infrastructure underneath company operations.
- When a UI or docs change mentions “Linear-like” behavior, keep it scoped to the Cotor app unless a real external integration is implemented.

## Current Truth Sources

- product snapshot: `../../README.md`
- docs router: `../INDEX.md`
- validation matrix: `../TEST_PLAN.md`
- desktop and company workflow: `../DESKTOP_APP.md`

## Notes

- historical reports and design drafts are context only; they are not the source of truth for current behavior
- when docs drift from code, update the docs in the same change
