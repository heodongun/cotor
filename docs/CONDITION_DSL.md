# Condition Expression DSL

This document describes the syntax and features of the condition expression DSL used in Cotor pipelines.

## Overview

The condition expression DSL allows you to define complex logical conditions for controlling pipeline flow. These expressions are evaluated against the pipeline context, which includes stage results, shared state, and other metadata.

## Syntax

Expressions are composed of literals, variables, operators, and groupings.

### Literals

- **Boolean**: `true`, `false`
- **Number**: `123`, `45.67`
- **String**: `'hello'`, `"world"`

### Variables

Variables are used to access data from the pipeline context. The following are supported:

- **Stage properties**: `stageId.property`
  - `stageId.success`: `true` if the stage succeeded, `false` otherwise.
  - `stageId.output`: The output of the stage.
  - `stageId.error`: The error message if the stage failed.
  - `stageId.metadata.key`: A value from the stage's metadata.
- **Shared state**: `context.sharedState.key`
- **Pipeline metadata**: `context.metadata.key`
- **Elapsed time**: `context.elapsedTimeMs`

### Operators

The following operators are supported, in order of precedence:

| Operator | Description | Example |
|---|---|---|
| `!` | Logical NOT | `!step1.success` |
| `>` | Greater than | `step1.tokens > 1000` |
| `>=` | Greater than or equal to | `step1.score >= 0.8` |
| `<` | Less than | `step2.retries < 3` |
| `<=` | Less than or equal to | `step3.duration <= 10000` |
| `==` | Equal to | `step1.status == 'completed'` |
| `!=` | Not equal to | `step2.reason != 'timeout'` |
| `contains` | Substring matching | `step3.output contains 'error'` |
| `matches` | Regular expression matching | `step4.log matches '.*FATAL.*'` |
| `&&` | Logical AND | `step1.success && step2.success` |
| `||` | Logical OR | `step1.success || step2.optional` |

### Grouping

Parentheses can be used to group expressions and control the order of evaluation.

`(step1.success && step2.tokens > 1000) || step3.fallback`

## Examples

### Simple Comparison

`quality-check.validationScore >= 0.8`

### Logical Expression

`review.success == true && review.metadata.severity != "HIGH"`

### Nested Expression

`(step1.success && step2.tokens > 1000) || !step3.is_critical`
