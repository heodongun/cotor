# Cotor Docs Index

Use this page as the router for current product docs vs historical or design records.

## Current Product Docs

Korean companion docs use the same path with `.ko.md` when available.

- `README.md` / `README.ko.md`: top-level product snapshot
- `docs/README.md` / `docs/README.ko.md`: docs entry guide
- `docs/QUICK_START.md` / `docs/QUICK_START.ko.md`: first setup and first run
- `docs/HOMEBREW_INSTALL.md` / `docs/HOMEBREW_INSTALL.ko.md`: packaged install, first-run paths, and Homebrew troubleshooting
- `docs/TROUBLESHOOTING.md` / `docs/TROUBLESHOOTING.ko.md`: desktop, company runtime, GitHub, QA/CEO, and interactive recovery paths
- `docs/FEATURES.md` / `docs/FEATURES.ko.md`: code-backed capability inventory
- `docs/DESKTOP_APP.md` / `docs/DESKTOP_APP.ko.md`: `app-server`, Company/TUI shell, and multi-company operations UI
- `docs/TEST_PLAN.md` / `docs/TEST_PLAN.ko.md`: automated, CLI, desktop, and autonomous-company validation plan
- `docs/USAGE_TIPS.md` / `docs/USAGE_TIPS.ko.md`: operator shortcuts and recovery habits
- `docs/WEB_EDITOR.md` / `docs/WEB_EDITOR.ko.md`: web editor usage
- `docs/ARCHITECTURE.md` / `docs/ARCHITECTURE.ko.md`: shared runtime architecture
- `docs/CONDITION_DSL.md` / `docs/CONDITION_DSL.ko.md`: condition DSL reference
- `docs/cookbook.md`: scenario patterns and example workflows
- `docs/CLAUDE_SETUP.md`: Claude integration setup
- `docs/team-ops/README.md` / `docs/team-ops/README.ko.md`: onboarding and delivery operations
- `docs/templates/temp-cotor-template.md`: template note

## Historical / Design Records

- `docs/reports/*`: historical reports and benchmark notes
- `docs/release/CHANGELOG.md` / `docs/release/CHANGELOG.ko.md`: release history
- `docs/release/FEATURES_v1.1.md` / `docs/release/FEATURES_v1.1.ko.md`: versioned historical feature snapshot
- `docs/DIFFERENTIATED_PRD_ARCHITECTURE.md`: strategy and architecture draft
- `docs/MULTI_WORKSPACE_REMOTE_RUNNER.md`: runner design draft
- `docs/UPGRADE_RECOMMENDATIONS.md`: recommendation note
- `docs/IMPROVEMENT_ISSUES.md` / `docs/IMPROVEMENT_ISSUES.ko.md`: historical improvement tracker
- `docs/ci-failure-analysis.md` / `docs/ci-failure-analysis.ko.md`: incident analysis note

## Current Truth Rules

- Command availability must match `src/main/kotlin/com/cotor/Main.kt`
- Desktop and company workflow behavior must match `src/main/kotlin/com/cotor/app/*` and `macos/Sources/CotorDesktopApp/*`
- External-product metaphors such as “Linear-style board” describe the UI shape inside Cotor, not a required external sync
- Historical records are useful context, but they are not the source of truth for current behavior
