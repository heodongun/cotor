# Troubleshooting

Use this page for current, code-backed recovery steps when Cotor does not behave as expected.

This is not a historical changelog. It focuses on failure patterns that actually showed up in the current product surfaces and explains:

- the symptom
- the usual root cause
- where to confirm it
- what to do next

Use the Korean companion at [TROUBLESHOOTING.ko.md](TROUBLESHOOTING.ko.md) when needed.

## 1. First Triage

Start with the smallest set of checks before guessing.

```bash
cotor version
cotor help
cotor app-server --port 8787
curl http://127.0.0.1:8787/health
```

If the app-server cannot answer `/health`, solve that first. Many desktop and company-level symptoms are downstream of a dead or stale local backend.

## 2. Where To Look

### 2.1 Desktop app logs and state

- Desktop runtime log:
  - `~/Library/Application Support/CotorDesktop/runtime/desktop-app.log`
- Company/runtime backend errors:
  - `~/Library/Application Support/CotorDesktop/runtime/backend/company-runtime-errors.log`
- Desktop persisted state:
  - `~/Library/Application Support/CotorDesktop/state.json`
- Runtime port/token/pid files:
  - `~/Library/Application Support/CotorDesktop/runtime/app-server.port`
  - `~/Library/Application Support/CotorDesktop/runtime/app-server.token`
  - `~/Library/Application Support/CotorDesktop/runtime/app-server.pid`

### 2.2 Interactive CLI / TUI session logs

- Interactive transcript and session log:
  - `.cotor/interactive/...`
- Each interactive session now writes:
  - `interactive.log`

### 2.3 Company execution state

- Company snapshots:
  - `.cotor/companies/...`
- Worktrees:
  - `.cotor/worktrees/<task-id>/<agent-name>/...`

## 3. Symptom Matrix

| Symptom | Usual root cause | First place to confirm |
| --- | --- | --- |
| `Cotor Desktop could not start its bundled app server.` | stale launcher/backend state, old install, or packaged app mismatch | `desktop-app.log`, `/health`, runtime pid/port files |
| Clicking company `Start` / `Stop` makes the app look globally disconnected | benign request cancellation was misread as offline | `desktop-app.log` with `cancelled` refresh entries |
| Company runtime keeps failing or the same issue keeps bouncing | permanent GitHub readiness failure, merge conflict, or blocked review state | `state.json`, `company-runtime-errors.log`, review queue |
| Company runtime says `RUNNING` but the company looks stuck for a long time | dead or stale `RUNNING` task/run state combined with idle backoff | `state.json` runtime `lastAction`, `adaptiveTickMs`, task/run `processId` |
| Company mode shows `The data is missing.` and live updates stop | company dashboard/event payload was decoded too strictly, or the installed app/app-server is older than the current wire contract | desktop status pill, `desktop-app.log`, company dashboard/event responses |
| Company issues start and immediately fall back to `BLOCKED` with Codex `model_not_found` | the runtime is still trying to call a retired Codex model id such as `gpt-5.3-codex-spark` | `state.json` run `error`, company automation trace, `ps` for live `codex exec --model ...` |
| QA issue becomes `BLOCKED` | QA returned `CHANGES_REQUESTED`, usually because proof or validation output does not match the PR state | GitHub PR review, `state.json`, linked worktree files |
| CEO approval never reaches merge | self-approval restriction, real merge conflict, or stale approval state | GitHub PR status, `gh pr view`, runtime error log |
| Local `master` does not show merged work | PR merged remotely but local branch is behind, or merge never actually happened | `git status -sb`, `git log --oneline --decorate -5`, `gh pr view` |
| `cotor` interactive/TUI starts but does not answer well | thin PATH, unauthenticated AI CLI, or wrong starter selection | `interactive.log`, shell PATH, provider auth status |
| `brew install cotor` works but desktop install/first run behaves strangely | packaged install layout mismatch, bad HOME resolution, or stale local overrides | `docs/HOMEBREW_INSTALL.md`, packaged config path, `interactive.log` |

## 4. Desktop App Startup And Shutdown

### 4.1 Symptom

- Desktop app says the bundled app-server could not start
- app quits but the backend keeps running
- last window closes but Cotor processes remain alive

### 4.2 Common root causes

- stale bundled runtime files from a previous launch
- old installed app bundle that does not match the current CLI/runtime behavior
- packaged app launcher and in-app backend management disagreeing about who owns the backend

### 4.3 Confirm

Check:

- `~/Library/Application Support/CotorDesktop/runtime/desktop-app.log`
- `ps` for `Cotor Desktop`, `cotor`, `cotor-backend.jar`, `com.cotor.MainKt`
- `curl http://127.0.0.1:<port>/health`

### 4.4 Recover

Homebrew / packaged install:

```bash
cotor update
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

Source checkout:

```bash
bash ./shell/install-desktop-app.sh
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

If the app is already open, quit it fully before reinstalling.

## 5. Company Runtime Looks Broken Even Though The App Is Connected

### 5.1 Symptom

- the top app shell is connected
- the selected company still shows failures, blocked issues, or review attention

### 5.2 Important distinction

This usually means:

- the app-server is healthy
- the company runtime is running
- the bottleneck is in GitHub readiness, QA, CEO approval, or merge follow-up

Do not treat every company failure as a desktop connectivity problem.

### 5.3 Confirm

Check:

- `state.json`
- company review queue items
- `company-runtime-errors.log`
- the selected company’s blocked issues and latest PRs

If the runtime is `RUNNING/healthy` but `lastAction` sits on `idle-no-work` while company tasks or runs are still `RUNNING`, the company is not truly idle. That is a stale execution-state symptom, not a successful idle loop.

### 5.4 Current behavior

Current builds should:

- keep company mode live through event-driven company snapshots instead of requiring a full manual refresh
- append company activity as events arrive
- hold the runtime on a faster monitoring cadence while active tasks/runs still exist
- reconcile dead or stale `RUNNING` runs sooner so the company does not look frozen for a full idle backoff window
- keep an explicit company stop sticky across app restarts and dashboard reads instead of immediately auto-starting again
- re-queue issues that were interrupted by an app-server shutdown instead of leaving them blocked with a generic `Agent process exited before Cotor recorded a final result`
- resume queued delegated work after the desktop app and bundled backend come back, and surface that recovery in the company activity feed
- close stale execution issues that only look blocked because a no-op follow-up run replayed after the linked PR had already merged

### 5.5 If company mode shows `The data is missing.`

This symptom used to mean the desktop client dropped the live company stream after a decode mismatch.

Current builds should recover like this instead:

- keep the last company snapshot visible
- show `Live company updates disconnected. Re-syncing...`
- perform a company-only refresh
- reconnect the company event stream without forcing a full dashboard reload

If you still see the raw `The data is missing.` text:

- update the installed bundle with `cotor update`
- confirm `GET /api/app/companies/{companyId}/dashboard` and `GET /api/app/companies/{companyId}/events` both return the company payload
- confirm the payload includes `tasks`, `issueDependencies`, `reviewQueue`, `workflowTopologies`, `goalDecisions`, `runningAgentSessions`, `signals`, `activity`, and `runtime`

### 5.6 If company issues start and then immediately block with Codex `model_not_found`

This usually means the runtime is still trying to call a retired Codex model id.

One concrete live example was:

- requested model: `gpt-5.3-codex-spark`
- provider response: `The requested model 'gpt-5.3-codex-spark' does not exist.`

Current builds should:

- normalize the retired `gpt-5.3-codex-spark` alias to the current default Codex model
- seed new built-in Codex agents with `gpt-5.4`
- re-open blocked issues caused by that retired model id so the company can retry with the corrected model

Confirm with:

- the latest failed run in `~/Library/Application Support/CotorDesktop/state.json`
- `~/Library/Application Support/CotorDesktop/runtime/backend/company-automation-trace.log`
- `ps` output for live `codex exec --model ...` commands

If live processes still show a retired model id after updating:

- run `cotor update`
- restart the desktop app or `cotor app-server`
- confirm the new process command line now uses `--model gpt-5.4`

## 6. GitHub Readiness And Publish Failures

### 6.1 Symptom

- execution issues get blocked during publish
- PR creation fails
- same issue used to retry forever

### 6.2 Common root causes

- `gh` is not authenticated
- repository has no usable `origin`
- local base branch and remote base branch have no common history
- repository was locally bootstrapped in a way that cannot publish PRs to the configured remote

### 6.3 Confirm

```bash
gh auth status
git remote -v
git branch --show-current
git fetch origin master
git merge-base master origin/master
```

If `git merge-base master origin/master` returns nothing, the local and remote branch histories do not share a base. That is a readiness/configuration problem, not a transient runtime error.

### 6.4 Current behavior

Current builds should:

- warn during company creation when GitHub PR mode is required but readiness is broken
- classify permanent publish-readiness failures as blocking infra problems instead of retry loops

If the same blocked issue still reopens forever on a current build, treat that as a regression.

## 7. QA `BLOCKED` State

### 7.1 Symptom

- a QA issue shows `BLOCKED`
- the linked PR received `CHANGES_REQUESTED`

### 7.2 Usual root cause

QA usually blocks when the proof in the PR does not match the actual repository state.

Common examples:

- validation notes claim a command output that does not match the commit
- a validation-only follow-up PR carries placeholder evidence instead of real verification
- the PR says nothing changed, but tracked files prove otherwise

### 7.3 Confirm

Check:

- the GitHub PR review comments
- the linked worktree under `.cotor/worktrees/...`
- validation files such as `VALIDATION.md`
- the actual git state inside that worktree

Example checks:

```bash
git status --short
git ls-tree --name-only HEAD
```

### 7.4 What to do

- fix the proof or validation note so it matches the actual state
- rerun QA after the branch evidence is truthful

## 8. CEO Approval And Merge Never Finish

### 8.1 Symptom

- issue reaches `READY_FOR_CEO`
- PR stays open
- work never appears on `master`

### 8.2 Common root causes

- GitHub forbids self-approval of a self-authored PR
- the PR has a real merge conflict
- the PR is already clean again on GitHub, but local review-queue state has not refreshed yet
- merge happened remotely but local base branch is still behind

### 8.3 Confirm

```bash
gh pr view <number>
gh pr checks <number>
git status -sb
git log --oneline --decorate -5
```

If `gh pr view` shows `mergeStateStatus: CLEAN` again, recent builds of Cotor reopen the stale CEO merge-conflict block on the next runtime tick and move the item back to the CEO lane automatically.

If the PR is `MERGED` remotely but the local repo still does not show the change, fast-forward or pull the local base branch.

### 8.4 Current behavior

Current builds should:

- skip self-approval when GitHub forbids it
- continue into merge when appropriate
- treat already-merged PRs as success instead of failure
- sync the local base branch after merge when safe

## 9. Interactive / TUI Does Not Feel Like A Normal Chat

### 9.1 Symptom

- `cotor` starts but feels stuck
- the first response takes too long
- multiple agent CLIs appear to run unexpectedly

### 9.2 Common root causes

- old builds defaulted to multi-agent fan-out
- PATH was too thin for the wrapper to resolve standard utilities
- the selected AI CLI was installed but not authenticated

### 9.3 Confirm

Check:

- `.cotor/interactive/.../interactive.log`
- whether the session mode is `SINGLE`, `AUTO`, or `COMPARE`
- provider auth status for `codex`, `claude`, `gemini`, and similar CLIs

### 9.4 Current behavior

Current builds should:

- default interactive mode to a single preferred agent chat
- write `interactive.log` beside transcript files
- fall back to a safe starter when no authenticated AI CLI is ready

## 10. Homebrew First-Run Problems

See [HOMEBREW_INSTALL.md](HOMEBREW_INSTALL.md) for the full packaged-install model. The common issues are:

- packaged install being treated like a source checkout
- starter config being polluted by unrelated local `.cotor` runtime files
- writes going into a bad current working directory instead of the user home path

Current packaged installs should:

- keep starter config under `~/.cotor/interactive/default/cotor.yaml`
- avoid rebuilding Gradle/Swift assets at runtime
- use the packaged desktop bundle through `cotor install` / `cotor update`

## 11. When To Treat It As A Real Regression

Treat the problem as a fresh bug if a current build still shows any of these:

- company `Start` / `Stop` flips the global app connection state
- the same blocked issue reopens forever after a permanent publish-readiness failure
- QA or CEO results get reapplied repeatedly from stale task sync
- last desktop window closes but bundled backend stays alive
- current interactive mode silently fans out to multiple agents without explicit user choice

When reporting it, include:

- the exact symptom
- the relevant log path
- the relevant company or PR id
- the exact command or UI action that reproduced it
