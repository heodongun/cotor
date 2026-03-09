# Production UI Checklist

## Accessibility
- Keyboard-only navigation works end-to-end
- Visible `focus-visible` state on all interactive elements
- WCAG AA contrast for text and controls
- Semantic roles/labels for form controls and dialogs
- Error messages linked to inputs

## States
- Loading state (skeleton/spinner) per async block
- Empty state with clear next action
- Error state with retry/help path
- Disabled and destructive states clearly differentiated

## Responsive
- 320 / 768 / 1024 / 1440 breakpoints checked
- No clipped text or overflowing controls
- Tables have strategy (stack, horizontal scroll, or condensed mode)

## Visual Consistency
- Spacing aligns to chosen scale only
- Typography uses tokenized sizes/weights
- Color usage follows semantic + hierarchy rules
- Single depth strategy used across screens

## UX Quality
- Primary action is obvious at each screen
- Confirmation for destructive operations
- Form validation is immediate but not noisy
- Success feedback is explicit

## Performance
- Avoid expensive blur/shadow stacks on list-heavy pages
- Avoid layout shift during loading
- Keep animation subtle and short
