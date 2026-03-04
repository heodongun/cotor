# CI failure analysis (2026-03-04)

## Scope
Repository: `heodonugn/cotor`
Workflow: `.github/workflows/ci.yml`

## Why "3/3 failed" happened

1. **`formatCheck` fails immediately due to Spotless violations.**
   - CI runs `gradle formatCheck` before `gradle test`.
   - The run failed in `spotlessKotlinCheck` with formatting violations (including Kotlin filename-rule mismatch and many existing format diffs), so the workflow is marked failed before tests can proceed.

2. **`test` had a deterministic environment-variable mismatch in `TemplateEngineTest` (before fix).**
   - `TemplateEngineTest` expected fallback text `unknown` when `USER` env is missing.
   - `TemplateEngine` previously returned an error marker string (`[env variable ... not found ...]`) for missing env vars.
   - This mismatch causes `TemplateEngineTest > should interpolate environment variables()` to fail in CI-like environments where `USER` is unset.

3. **The same root causes repeat across each CI run/check.**
   - Because both are deterministic project-state issues (format violations + env fallback mismatch), each triggered CI check fails consistently until source is fixed.

## Changes applied

- Renamed `src/main/kotlin/com/cotor/presentation/web/stream/RealtimeEvents.kt` to `RealtimeEvent.kt` to satisfy Kotlin filename convention.
- Updated `TemplateEngine.handleEnvScope()` to return `"unknown"` in interpolate mode when env var is absent, while keeping validation-mode errors.

## Local verification summary

- `gradle test`: **PASS** after the env fallback fix.
- `gradle formatCheck`: **FAIL** because there are many pre-existing Spotless violations across the repository that are unrelated to this single rename.
