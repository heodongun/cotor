---
name: brand-safe-ui
description: Apply third-party brand assets and product marks safely in UI work. Use when Codex adds logos/wordmarks (e.g., Vercel, Next.js, v0, Turbo), builds partner sections, or needs brand-compliant placement, spacing, and naming in production interfaces.
---

# Brand Safe UI

Use this skill whenever logo/wordmark usage appears in product UI.

## Rules

- Use official marks only; do not redraw, distort, recolor, or remix brand assets.
- Prefer wordmark when space allows; symbol-only only in tight spaces or multi-brand icon rows.
- Preserve clear space around marks.
- Avoid implying sponsorship/endorsement.
- Keep our product branding visually dominant over third-party marks.

## v0 / Vercel Family Notes

- Treat Vercel, Next.js, Turbo, v0 marks as protected trademarks.
- Use official naming and spelling (`Next.js`, `v0`, `Vercel`).
- If uncertain about allowed commercial usage, add a review note for human/legal check.

## Implementation Pattern

1. Identify all third-party marks in scope.
2. Replace ad-hoc SVG/image files with official assets/components.
3. Apply consistent mark sizing rules and clear-space utility class.
4. Add alt text and accessible labels.
5. Add a brand usage note in PR summary.

## Reference

- Read `references/vercel-brand-notes.md` for quick legal-safe guidelines.
