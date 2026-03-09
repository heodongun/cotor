---
name: ui-production-design
description: Build production-grade product UI/UX systems in apps, dashboards, and admin tools. Use when Codex is asked to design or implement screens/components, improve visual polish, enforce consistency, create design tokens, or raise UI quality to ship-ready level.
---

# UI Production Design

Design and implement interface work as a **system**, not one-off styling.

## Workflow

1. Define product context in 1-2 lines (users, primary task, risk level).
2. Choose one design direction and state it before coding.
3. Create/update tokens first (color, spacing, radius, typography, shadow, motion, z-index).
4. Build primitives before feature components:
   - Button, Input, Select, Checkbox/Switch, Card, Badge, Modal, Toast, Table, Tabs
5. Implement feature UI with explicit states:
   - default, hover, active, focus-visible, disabled, loading, empty, error, success
6. Run quality gate (visual + accessibility + responsive + performance).
7. Save key decisions to `.interface-design/system.md` if project supports memory.

## Non-negotiables

- Use a spacing scale (4 or 8 base). No random spacing values.
- Keep interaction targets >= 40x40 (mobile >= 44x44 recommended).
- Enforce contrast minimums (WCAG AA baseline).
- Use `:focus-visible` styles on all interactive controls.
- Avoid hard-coded magic colors in components; route through tokens.
- Keep visual depth strategy consistent (borders-only OR soft-elevation, not random mix).
- Keep density consistent per product area.

## Decision Template (print before coding)

State these explicitly:

- Direction: Precision & Density | Warmth & Approachability | Sophistication & Trust | Boldness & Clarity | Utility & Function | Data & Analysis
- Foundation palette (neutral + accent + semantic)
- Depth strategy (borders-only / layered soft shadow)
- Corner language (sharp / subtle / rounded)
- Spacing base and scale
- Type scale and weight map
- Motion profile (fast-subtle / expressive)

## Implementation Rules

- Prefer component variants over copy-paste style blocks.
- Add dark/light compatibility unless project forbids it.
- Prioritize layout resilience:
  - long labels, narrow containers, i18n text growth, empty datasets
- For data-heavy pages: preserve scanability with hierarchy and alignment first.
- For forms: always design validation and recovery flow, not only happy path.

## Quality Gate (must pass)

- Visual consistency: tokens and spacing rhythm are coherent across all components.
- Accessibility: keyboard navigation, focus visibility, ARIA labels/roles, contrast.
- Responsive: 320px, 768px, 1024px, 1440px layouts validated.
- States: loading/skeleton, empty, and error UI exist for each async module.
- UX clarity: primary action is obvious; destructive actions are guarded.

## Output Format

When asked to design/implement, output in this order:

1. Design decisions (short bullet list)
2. Token diff (new/changed tokens)
3. Component plan
4. Implementation (code)
5. QA checklist results
6. Optional: save/update `.interface-design/system.md`

## References

- Read `references/design-directions.md` when selecting direction and density.
- Read `references/system-template.md` when creating `.interface-design/system.md`.
- Read `references/production-checklist.md` before finalizing UI tasks.
