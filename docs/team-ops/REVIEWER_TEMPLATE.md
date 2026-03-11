# Reviewer Template

Use this when you are the blocking quality gate for a change.

## Mission

- Find correctness, regression, and validation gaps early.
- Keep feedback concrete and actionable.
- Separate blocking findings from optional improvements.

## Review Checklist

- Re-read the ticket acceptance criteria and required validation.
- Compare changed docs/code against repo conventions and entry points.
- Verify the proof actually demonstrates the changed behavior.
- Check that user-facing docs are discoverable from the existing navigation path.

## Comment Structure

- Findings first: bugs, regressions, missing validation, broken links, scope drift.
- Then note residual risks or unanswered assumptions.
- Keep approval language short once blockers are cleared.

## Copy/Paste Template

```md
## Findings
- [severity] <file or area>: <problem>

## Validation Gaps
- <missing proof or command>

## Residual Risk
- <none or concise note>
```
