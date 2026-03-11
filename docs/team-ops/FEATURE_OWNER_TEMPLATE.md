# Feature Owner Template

Use this when one person owns the full delivery loop for a ticket.

## Mission

- Keep scope tight and explicit.
- Record evidence before and after changes.
- Leave the branch, workpad, and PR in a reviewable state.

## Entry Checklist

- Confirm issue state, acceptance criteria, and validation requirements.
- Record the current behavior with a command, screenshot, or deterministic doc gap.
- Sync from `origin/master` before edits.
- Decide the smallest file set that solves the ticket.

## Working Checklist

- Update the workpad plan before implementation.
- Make the smallest viable change.
- Validate after each meaningful milestone.
- Reflect discoveries back into the workpad instead of keeping them local.
- Re-check top-level docs or indexes if discoverability changed.

## Exit Checklist

- Validation commands are green on the final diff.
- The workpad reflects the final plan, notes, and validation evidence.
- PR summary explains user-facing impact, changed files, and proof.

## Copy/Paste Template

```md
## Summary
- scope:
- changed files:
- user/reviewer impact:

## Validation
- <command>
- <manual proof>

## Risks
- <none or concise note>
```
