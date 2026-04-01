# 문제 해결

이 문서는 Cotor가 예상과 다르게 동작할 때 현재 코드 기준으로 바로 확인하고 복구할 수 있는 운영 문서입니다.

이 문서는 단순 과거 기록이 아닙니다. 실제 제품 표면에서 발생했던 문제 패턴을 기준으로 아래를 정리합니다.

- 증상
- 보통의 근본 원인
- 어디서 확인할지
- 다음에 무엇을 할지

영문 문서는 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)입니다.

## 1. 첫 확인

추측하기 전에 가장 작은 기본 확인부터 합니다.

```bash
cotor version
cotor help
cotor app-server --port 8787
curl http://127.0.0.1:8787/health
```

`/health`가 응답하지 않으면 그 문제를 먼저 해결하세요. 데스크톱이나 회사 런타임 증상 중 상당수는 죽어 있거나 꼬인 로컬 백엔드에서 시작됩니다.

## 2. 어디를 봐야 하나

### 2.1 데스크톱 앱 로그와 상태

- 데스크톱 런타임 로그:
  - `~/Library/Application Support/CotorDesktop/runtime/desktop-app.log`
- 회사/런타임 백엔드 오류:
  - `~/Library/Application Support/CotorDesktop/runtime/backend/company-runtime-errors.log`
- 데스크톱 영속 상태:
  - `~/Library/Application Support/CotorDesktop/state.json`
- 런타임 port/token/pid 파일:
  - `~/Library/Application Support/CotorDesktop/runtime/app-server.port`
  - `~/Library/Application Support/CotorDesktop/runtime/app-server.token`
  - `~/Library/Application Support/CotorDesktop/runtime/app-server.pid`

### 2.2 인터랙티브 CLI / TUI 세션 로그

- 인터랙티브 transcript와 세션 로그:
  - `.cotor/interactive/...`
- 각 인터랙티브 세션은 현재 아래 파일을 남깁니다.
  - `interactive.log`

### 2.3 회사 실행 상태

- 회사 스냅샷:
  - `.cotor/companies/...`
- worktree:
  - `.cotor/worktrees/<task-id>/<agent-name>/...`

## 3. 증상별 빠른 표

| 증상 | 보통의 근본 원인 | 먼저 볼 곳 |
| --- | --- | --- |
| `Cotor Desktop could not start its bundled app server.` | stale launcher/backend 상태, 오래된 설치 앱, packaged 앱 불일치 | `desktop-app.log`, `/health`, runtime pid/port 파일 |
| 회사 `시작` / `중지`를 누르면 앱 전체가 끊긴 것처럼 보임 | 정상 request cancellation을 오프라인으로 잘못 해석 | `desktop-app.log`의 `cancelled` refresh 기록 |
| 회사 런타임이 계속 실패하거나 같은 이슈가 반복 흔들림 | 영구적인 GitHub readiness 실패, merge conflict, blocked review 상태 | `state.json`, `company-runtime-errors.log`, 리뷰 큐 |
| 회사 런타임은 `RUNNING`인데 오래 멈춘 것처럼 보임 | 죽었거나 stale인 `RUNNING` task/run 상태와 느린 idle backoff가 겹침 | `state.json`의 runtime `lastAction`, `adaptiveTickMs`, task/run `processId` |
| 회사는 연결돼 있는데 비용이 오른 뒤 새 작업이 시작되지 않음 | 설정한 일/월 예상 비용 상한에 도달해서 회사 런타임이 스스로 pause 됨 | 회사 요약 배지, `state.json`의 `todaySpentCents` / `monthSpentCents` / `budgetPausedAt` |
| 회사 모드에 `The data is missing.`가 뜨고 live 업데이트가 멈춤 | 회사 dashboard/event payload를 너무 엄격하게 decode했거나, 설치된 앱/app-server가 현재 wire contract보다 오래됨 | 데스크톱 상태 배너, `desktop-app.log`, company dashboard/event 응답 |
| 회사 이슈가 시작 직후 다시 `BLOCKED`로 떨어지고 Codex `model_not_found`가 보임 | 런타임이 `gpt-5.3-codex-spark` 같은 은퇴된 Codex 모델 id를 아직 호출하고 있음 | `state.json`의 run `error`, company automation trace, live `codex exec --model ...` 프로세스 |
| QA 이슈가 `BLOCKED`가 됨 | QA가 `CHANGES_REQUESTED`를 반환했고, 보통 PR 증거/검증 문서가 실제 상태와 안 맞음 | GitHub PR review, `state.json`, 연결된 worktree 파일 |
| CEO 승인 이후 머지가 끝나지 않음 | self-approval 제한, 실제 merge conflict, stale approval 상태 | GitHub PR 상태, `gh pr view`, runtime error log |
| 로컬 `master`에 머지 결과가 안 보임 | 원격에서 merge는 됐지만 로컬 branch가 뒤처짐, 또는 실제 merge가 안 됨 | `git status -sb`, `git log --oneline --decorate -5`, `gh pr view` |
| `cotor` 인터랙티브/TUI가 시작은 되는데 응답이 이상함 | 얇은 PATH, 인증되지 않은 AI CLI, 잘못된 starter 선택 | `interactive.log`, shell PATH, provider 인증 상태 |
| `brew install cotor`는 됐는데 데스크톱 설치/첫 실행이 이상함 | packaged install 레이아웃 오인, HOME 경로 해석 문제, stale local override | `docs/HOMEBREW_INSTALL.ko.md`, packaged config 경로, `interactive.log` |

## 4. 데스크톱 앱 시작과 종료 문제

### 4.1 증상

- 데스크톱 앱이 번들 app-server를 시작하지 못한다고 나옴
- 앱은 꺼졌는데 backend가 계속 남아 있음
- 마지막 창을 닫아도 Cotor 프로세스가 살아 있음

### 4.2 흔한 근본 원인

- 이전 실행의 stale runtime 파일
- 현재 CLI/런타임 동작과 맞지 않는 오래된 설치 앱
- packaged 앱 launcher와 앱 내부 backend 관리 주체가 서로 충돌

### 4.3 확인

아래를 확인합니다.

- `~/Library/Application Support/CotorDesktop/runtime/desktop-app.log`
- `ps`에서 `Cotor Desktop`, `cotor`, `cotor-backend.jar`, `com.cotor.MainKt`
- `curl http://127.0.0.1:<port>/health`

### 4.4 복구

Homebrew / packaged install:

```bash
cotor update
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

소스 체크아웃:

```bash
bash ./shell/install-desktop-app.sh
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

앱이 이미 떠 있다면 완전히 종료한 뒤 재설치하세요.

## 5. 앱은 연결되어 있는데 회사 런타임이 이상함

### 5.1 증상

- 앱 상단은 연결됨
- 그런데 선택한 회사는 실패, 차단, 리뷰 주의 상태가 남아 있음

### 5.2 중요한 구분

이건 보통 아래를 뜻합니다.

- app-server는 정상
- 회사 런타임도 살아 있음
- 실제 병목은 GitHub readiness, QA, CEO 승인, merge 후속 처리 쪽

모든 회사 실패를 데스크톱 연결 문제로 보면 안 됩니다.

### 5.3 확인

아래를 봅니다.

- `state.json`
- 회사 review queue 항목
- `company-runtime-errors.log`
- 선택한 회사의 blocked issue와 최신 PR

런타임이 `RUNNING/healthy`인데도 회사 task/run이 여전히 `RUNNING` 상태로 남아 있고 `lastAction`이 `idle-no-work`에 머무르면, 그건 진짜 idle이 아니라 stale 실행 상태 증상입니다.

### 5.4 현재 기대 동작

현재 빌드는 아래처럼 동작해야 합니다.

- 회사 모드는 수동 전체 새로고침 대신 이벤트 기반 company snapshot으로 live 갱신
- 회사 활동 로그는 이벤트가 들어오면 바로 추가
- active task/run이 남아 있으면 런타임이 빠른 monitoring cadence 유지
- 죽었거나 stale인 `RUNNING` run은 더 빨리 정리해서 긴 idle backoff 동안 멈춘 것처럼 보이지 않게 함
- 회사 런타임을 명시적으로 중지하면 앱 재실행이나 dashboard 조회 뒤에도 바로 다시 켜지지 않고 그대로 유지
- app-server 종료로 끊긴 이슈는 일반 `Agent process exited before Cotor recorded a final result` 차단으로 남기지 않고 다시 큐에 올려 재개 가능하게 복구
- 데스크톱 앱과 번들 backend가 다시 올라오면 queued delegated work를 다시 시작하고, 그 복구 흐름을 회사 활동 로그에도 남김
- 연결된 PR이 이미 머지된 뒤 stale no-op 실행 동기화 때문에 잘못 `BLOCKED`로 남은 execution 이슈도 자동으로 닫음
- stale한 Cotor retry PR은 배치 정리로 닫아서 같은 lineage에 오래된 open PR이 계속 쌓이지 않게 함
- 예전 CEO merge-conflict 때문에 execution 이슈가 `BLOCKED`에 남아 있던 경우도 다시 `PLANNED`로 돌려 rebase와 republish를 이어갈 수 있게 함
- root issue 또는 root goal이 이미 `DONE` / `MERGED`인 stale follow-up goal은 legacy 상태라도 자동으로 archive 됨
- merge conflict follow-up은 기존 PR lineage 위의 deterministic remediation으로 처리되고, generic handoff execution issue를 새로 만들지 않음
- no-diff remediation run 뒤에는 기존 PR metadata를 다시 읽어서 CEO approval 재개, merge-conflict remediation 유지, 이미 merge된 PR의 loop 종료 중 하나로 바로 정규화함

### 5.5 회사 모드에 `The data is missing.`가 보이면

이 증상은 예전에는 desktop client가 회사 dashboard/event payload decode에 실패하면서 live company stream을 끊었다는 뜻이었습니다.

현재 빌드는 대신 아래처럼 복구해야 합니다.

- 마지막 회사 snapshot은 그대로 유지
- `회사 실시간 업데이트 연결이 끊어졌습니다. 다시 동기화하는 중...` 배너 표시
- 회사 전용 refresh 1회 수행
- 전체 dashboard reload 없이 company event stream 재연결

그래도 raw `The data is missing.` 문구가 그대로 보이면 아래를 확인하세요.

- `cotor update`로 설치 앱 번들을 최신으로 맞춥니다
- `GET /api/app/companies/{companyId}/dashboard` 와 `GET /api/app/companies/{companyId}/events` 가 둘 다 회사 payload를 주는지 확인합니다
- payload 안에 `tasks`, `issueDependencies`, `reviewQueue`, `workflowTopologies`, `goalDecisions`, `runningAgentSessions`, `signals`, `activity`, `runtime` 키가 실제로 들어 있는지 확인합니다

### 5.6 회사 이슈가 시작 직후 Codex `model_not_found`로 다시 막히면

이 경우는 런타임이 은퇴된 Codex 모델 id를 아직 호출하고 있다는 뜻인 경우가 많습니다.

실제 라이브 회사 자동화에서 확인한 예시는 아래였습니다.

- 요청 모델: `gpt-5.3-codex-spark`
- provider 응답: `The requested model 'gpt-5.3-codex-spark' does not exist.`

현재 빌드는 아래처럼 동작해야 합니다.

- 은퇴된 `gpt-5.3-codex-spark` 별칭을 현재 기본 Codex 모델로 정규화
- 새 built-in Codex 에이전트는 `gpt-5.4`를 기본값으로 사용
- 그 은퇴된 모델 id 때문에 막혔던 회사 이슈는 다시 열어서 교정된 모델로 재시도

확인 위치:

- `~/Library/Application Support/CotorDesktop/state.json`의 최신 failed run
- `~/Library/Application Support/CotorDesktop/runtime/backend/company-automation-trace.log`
- live `codex exec --model ...` 프로세스를 보는 `ps`

업데이트 후에도 live process가 여전히 오래된 모델 id를 쓰면:

- `cotor update` 실행
- 데스크톱 앱 또는 `cotor app-server` 재시작
- 새 프로세스 명령줄이 `--model gpt-5.4`를 쓰는지 확인

### 5.7 비용 상한 때문에 회사가 pause 되는 경우

이건 런타임이 죽은 경우와 다릅니다.

현재 빌드는 회사의 일간 또는 월간 예상 AI 비용이 설정한 상한에 도달하면, 새 autonomous work를 멈추고 회사 모드에 그 상태를 명시적으로 보여줘야 합니다.

아래에서 확인하세요.

- 데스크톱 앱의 회사 요약 배지
- `~/Library/Application Support/CotorDesktop/state.json`의 `todaySpentCents`, `monthSpentCents`, `budgetPausedAt`
- 선택한 회사 설정 패널의 현재 상한과 현재 예상 비용 표시

복구 방법은 의도에 따라 다릅니다.

- pause가 예상된 것이라면 회사 설정에서 상한을 올리거나 비우고 `Save Guardrails`를 누릅니다
- 회사를 계속 멈춰 두고 싶다면 상한을 그대로 두고 `Start`를 누르지 않습니다
- 숫자가 이상해 보이면 먼저 `cotor update`로 최신 앱을 맞추고, 최신 빌드인지 확인한 뒤에 regression인지 판단합니다

## 6. GitHub readiness 와 publish 실패

### 6.1 증상

- execution issue가 publish 단계에서 막힘
- PR 생성이 실패함
- 예전에는 같은 이슈가 반복 재시도되곤 했음

### 6.2 흔한 근본 원인

- `gh` 인증이 안 됨
- 저장소에 쓸 수 있는 `origin`이 없음
- 로컬 base branch와 원격 base branch가 공통 history를 가지지 않음
- 로컬 bootstrap 방식 때문에 현재 remote로는 PR을 열 수 없음

### 6.3 확인

```bash
gh auth status
git remote -v
git branch --show-current
git fetch origin master
git merge-base master origin/master
```

`git merge-base master origin/master`가 아무것도 반환하지 않으면 로컬과 원격 history가 이어지지 않는 것입니다. 이건 일시적 런타임 오류가 아니라 readiness/configuration 문제입니다.

### 6.4 현재 기대 동작

현재 빌드는 아래처럼 동작해야 합니다.

- GitHub PR 모드가 필요한데 readiness가 깨져 있으면 회사 생성 시 경고
- 영구적인 publish-readiness 실패는 retry loop가 아니라 blocking infra 문제로 분류

현재 빌드에서도 같은 blocked issue가 끝없이 다시 열리면 regression으로 봐야 합니다.

## 7. QA `BLOCKED` 상태

### 7.1 증상

- QA 이슈가 `BLOCKED`
- 연결된 PR에 `CHANGES_REQUESTED`

### 7.2 보통의 근본 원인

QA는 보통 PR 안의 증거가 실제 저장소 상태와 다를 때 막습니다.

대표 예시:

- validation note가 실제 커밋과 다른 command output을 적음
- validation-only 후속 PR이 진짜 검증 대신 placeholder 증거를 담음
- “변경 없음”이라고 적었는데 tracked file은 실제로 존재함

### 7.3 확인

아래를 확인합니다.

- GitHub PR review 코멘트
- `.cotor/worktrees/...` 아래 연결된 worktree
- `VALIDATION.md` 같은 검증 문서
- 그 worktree 내부의 실제 git 상태

예시 명령:

```bash
git status --short
git ls-tree --name-only HEAD
```

### 7.4 대응

- 증거나 validation note를 실제 상태와 맞게 고칩니다.
- 브랜치 증거가 진실해진 뒤 QA를 다시 돌립니다.

## 8. CEO 승인과 merge가 끝나지 않음

### 8.1 증상

- 이슈는 `READY_FOR_CEO`까지 감
- PR은 계속 열려 있음
- 작업이 `master`에 안 보임

### 8.2 흔한 근본 원인

- GitHub가 self-authored PR의 self-approval을 막음
- PR에 실제 merge conflict가 있음
- GitHub에서는 PR이 이미 clean인데 로컬 review queue 상태가 아직 갱신되지 않음
- 원격 merge는 됐는데 로컬 base branch가 뒤처져 있음

### 8.3 확인

```bash
gh pr view <number>
gh pr checks <number>
git status -sb
git log --oneline --decorate -5
```

`gh pr view`에서 다시 `mergeStateStatus: CLEAN`으로 보인다면, 최신 Cotor 빌드는 다음 runtime tick에서 stale CEO merge-conflict 차단을 자동으로 다시 열고 CEO lane으로 되돌립니다.

원격에서 PR 상태가 `MERGED`인데 로컬 저장소에 안 보이면 로컬 base branch를 fast-forward 하거나 pull 하세요.

### 8.4 현재 기대 동작

현재 빌드는 아래처럼 동작해야 합니다.

- GitHub가 막는 self-approval은 건너뜀
- 가능하면 그대로 merge 단계로 진행
- `DIRTY`가 명확한 PR은 pointless한 merge 시도 대신 바로 remediation 상태로 내림
- 이미 merge된 PR은 실패가 아니라 성공으로 취급
- merge conflict가 clean으로 풀리거나 오래된 blocked execution 상태가 감지되면 CEO/실행 lane을 다시 이어서 복구
- 런타임이 현재 중지 상태여도 회사 상태 정리 과정에서 legacy CEO merge-conflict blocker를 다시 `PLANNED`로 되돌림
- stale retry PR 정리는 백그라운드에서 계속 진행되고, 겹치는 정리 때문에 이미 닫힌 PR을 다시 만나도 전체 배치를 죽이지 않음
- 안전한 경우 merge 후 로컬 base branch sync

### 8.5 Review lineage 보호 장치

현재 빌드는 QA와 CEO review 상태를 issue id나 prompt text의 느슨한 재사용이 아니라 명시적인 workflow lineage로 다룹니다.

- 각 PR review cycle은 review queue 항목, QA 이슈, CEO 이슈, workflow task, workflow run에 같은 lineage metadata를 가집니다
- QA나 CEO 결과는 lineage가 현재 review queue lineage와 정확히 일치할 때만 다시 적용됩니다
- 같은 execution 이슈에서 더 새로운 PR이 다시 publish되면, Cotor는 예전 lineage를 supersede 처리하고 stale verdict를 지우고 필요하면 downstream QA/CEO 이슈를 다시 만들며 오래된 retry PR도 opportunistic하게 닫습니다
- startup healing, 회사 dashboard read, 회사 runtime tick은 예전 task에 explicit lineage metadata가 없던 legacy 상태도 자동으로 복구합니다
- lineage가 없는 legacy QA/CEO task는 현재 queue 항목에 아직 속하고 그 task 완료 이후 이슈가 다시 열리지 않았을 때만 받아들입니다

현재 빌드에서 옛 QA 코멘트나 옛 CEO verdict가 더 새로운 PR에 다시 적용된다면 새 regression으로 취급하세요.

## 9. 인터랙티브 / TUI가 일반 채팅처럼 안 느껴짐

### 9.1 증상

- `cotor`는 뜨는데 체감상 멈춘 것 같음
- 첫 응답이 너무 느림
- 예상치 않게 여러 agent CLI가 동시에 도는 것 같음

### 9.2 흔한 근본 원인

- 예전 빌드는 기본이 멀티 에이전트 fan-out에 가까웠음
- PATH가 너무 얇아서 wrapper가 표준 유틸리티를 못 찾음
- 선택된 AI CLI가 설치는 되어 있지만 인증되지 않음

### 9.3 확인

아래를 봅니다.

- `.cotor/interactive/.../interactive.log`
- 세션 모드가 `SINGLE`, `AUTO`, `COMPARE` 중 무엇인지
- `codex`, `claude`, `gemini` 같은 provider CLI 인증 상태

### 9.4 현재 기대 동작

현재 빌드는 아래처럼 동작해야 합니다.

- interactive 기본 모드는 단일 선호 에이전트 채팅
- transcript 옆에 `interactive.log` 기록
- 인증된 AI CLI가 없으면 안전한 starter로 fallback

## 10. Homebrew 첫 실행 문제

전체 packaged install 모델은 [HOMEBREW_INSTALL.ko.md](HOMEBREW_INSTALL.ko.md)를 보십시오. 흔한 문제는 아래와 같습니다.

- packaged install을 source checkout처럼 취급
- starter config가 무관한 local `.cotor` 런타임 파일에 오염
- 현재 작업 디렉터리가 이상해서 쓰기 경로가 홈 대신 꼬임

현재 packaged install은 아래처럼 동작해야 합니다.

- starter config는 `~/.cotor/interactive/default/cotor.yaml`
- 런타임 시점에 Gradle/Swift 재빌드 안 함
- `cotor install` / `cotor update`로 packaged desktop bundle 사용

## 11. 언제 새 regression으로 봐야 하나

현재 빌드에서 아래 중 하나가 다시 보이면 새 버그로 다뤄야 합니다.

- 회사 `시작` / `중지`가 앱 전체 연결 상태를 흔듦
- 영구적인 publish-readiness 실패 뒤 같은 blocked issue가 무한 재오픈
- QA나 CEO 결과가 stale task sync 때문에 반복 재적용
- 마지막 데스크톱 창을 닫았는데 번들 backend가 남아 있음
- 현재 interactive 모드가 명시적 선택 없이 여러 에이전트로 fan-out

리포트할 때는 아래를 같이 남기세요.

- 정확한 증상
- 관련 로그 경로
- 관련 회사 또는 PR id
- 재현한 정확한 명령이나 UI 동작
