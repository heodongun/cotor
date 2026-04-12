# Durable Runtime Foundation

## Problem
Existing checkpoints preserve stage summaries, but not execution lineage or replay safety. That is enough for inspection, not enough for a trustworthy operator-grade runtime.

## Scope of this slice
- checkpoint graph
- side-effect journal
- replay approval primitive
- resume inspect / continue / fork / approve

## Design
- Durable runs live under `.cotor/runtime/runs/<run>.json`
- Legacy `.cotor/checkpoints/*.json` remain supported and are lazily imported
- Replay-unsafe git/PR actions require approval before replay or fork continues
- The orchestrator records checkpoint nodes as stages start/complete/fail

## Why this shape
- Reuses existing pipeline context, checkpoint manager, agent execution metadata, and git isolation layer
- Adds inspectability without forcing a destructive migration
- Creates a shared substrate for policy, provenance, and provider-native control planes later
