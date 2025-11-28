# Improvement Issues

## Completed in feature/improvements-batch
- [x] Issue 1: Implement pipeline run tracking and status reporting  
  Added `PipelineRunTracker` that listens to pipeline events and the `status` CLI now shows active and recent runs with durations.
- [x] Issue 2: Validate recovery fallback agents early  
  Pipeline validation checks fallback agents exist and warns when a fallback strategy is configured without targets.
- [x] Issue 3: Harden config repository UX  
  Loading now fails fast with a clear message when config files are missing, and saving creates parent directories automatically.
- [x] Issue 4: Stats CLI maintenance  
  `stats` uses DI, gains `--clear` for cleanup, and has new tests covering stats persistence.

## Backlog ideas
- [ ] Persist run history to disk so status survives process restarts.
- [ ] Surface tracker data in the TUI/Web views for live dashboards.
