# Condition Expression DSL

This document describes the syntax and features of the condition expression DSL.

## Expressions

The DSL supports a variety of expressions, including:

*   **Literals:** `true`, `false`, numbers, and strings.
*   **Variables:** References to stage results and context variables.
*   **Function Calls:** `success(stageId)`, `tokens(stageId)`, `output(stageId)`, and `reason(stageId)`.
*   **Logical Operators:** `&&` (and), `||` (or), `!` (not).
*   **Comparison Operators:** `==`, `!=`, `>`, `>=`, `<`, `<=`.
*   **Grouping:** `(...)`.

## Examples

Here are some examples of valid expressions:

*   `success(step1) && tokens(step1) > 1000`
*   `!success(step2) || reason(step2) == 'timeout'`
*   `(a.x > 5 && b.y == 20) || (c.output == 'done' && d.z > 25)`
