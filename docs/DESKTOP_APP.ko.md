# 데스크톱 앱

원문: [DESKTOP_APP.md](DESKTOP_APP.md)

데스크톱 앱은 기존 Kotlin 런타임과 로컬호스트 `cotor app-server` 위에 올라가는 macOS 네이티브 셸입니다.

## Homebrew 설치 (권장)

```bash
brew tap bssm-oss/cotor https://github.com/bssm-oss/cotor.git
brew install cotor
cotor install
```

정리:

- Homebrew 패키지에는 `Cotor Desktop.app` 번들이 함께 들어 있습니다.
- `cotor install`, `cotor update`는 Homebrew prefix 안에서 재빌드하지 않고, 패키지된 번들을 그대로 재사용합니다.
- packaged install에서 로컬 config가 없을 때 `cotor` 인터랙티브 starter config는 `~/.cotor/interactive/default/cotor.yaml` 아래에 생성됩니다.
- packaged 설치와 첫 실행 규칙은 [HOMEBREW_INSTALL.md](HOMEBREW_INSTALL.md)를 보면 됩니다.

원라이너 설치도 지원합니다.

```bash
curl -fsSL https://raw.githubusercontent.com/bssm-oss/cotor/master/shell/brew-install.sh | bash
```

## 소스에서 설치

```bash
cotor install
cotor update
cotor delete
```

소스 체크아웃에서는 로컬에서 번들을 빌드한 뒤 설치합니다.

## 구성 요소

- `cotor app-server`
  - 저장소, 워크스페이스, 태스크, 목표, 이슈, 리뷰 큐, 런타임 상태를 제공하는 localhost API
- `macos/`
  - SwiftUI 셸
- `src/main/kotlin/com/cotor/app/`
  - 저장소/워크스페이스/태스크/목표/이슈/리뷰 큐/런타임 서비스

## 백엔드 실행

```bash
cotor app-server --port 8787
```

로컬 인증 토큰을 붙이려면:

```bash
export COTOR_APP_TOKEN='your-local-token'
cotor app-server --port 8787 --token your-local-token
```

## macOS 앱 실행

```bash
swift run --package-path macos CotorDesktopApp
```

백엔드 URL을 직접 지정하려면:

```bash
export COTOR_APP_SERVER_URL='http://127.0.0.1:8787'
export COTOR_APP_TOKEN='your-local-token'
swift run --package-path macos CotorDesktopApp
```

## 로컬 앱 번들 설치

```bash
cotor install
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

설치된 번들은 필요할 때 로컬 백엔드를 지연 시작합니다.
마지막 데스크톱 창을 닫으면 앱도 종료되고 번들 백엔드도 같이 내려갑니다.

```bash
cotor update
cotor delete
```

### 설치 레이아웃별 차이

- **Homebrew / packaged install**
  - 패키지 안에 들어 있는 데스크톱 번들을 복사합니다.
  - 런타임 시점에 Gradle/Swift 재빌드는 하지 않습니다.
- **소스 체크아웃**
  - 로컬에서 번들을 다시 빌드한 뒤 설치합니다.

## 현재 셸 모델

현재 macOS 셸에는 두 가지 최상위 모드가 있습니다.

### `Company`

- 회사 선택기
- 하나의 루트 폴더에 묶이는 회사 생성
- 이벤트 월, 실행 좌석, 리뷰 데스크를 한 화면에서 보는 실시간 회사 플로어 맵용 `미팅룸` 직접 탐색
- 에이전트 정의 작성
- 목표 목록과 목표 생성
- 앱 내부의 Linear 스타일 이슈 보드/캔버스
- 라이브 메시지/컨텍스트, 백엔드 메모리 스냅샷, 확인 우선 proposal preview, 리더/워커 AI 라우팅 제어를 함께 보여주는 오른쪽 `채팅 컨트롤` 레일
- 이벤트 기반으로 바로 갱신되는 회사 활동 피드
- 회사 live update는 무거운 전체 refresh 대신 company event stream + 회사 전용 dashboard snapshot으로 상태를 반영
- 이슈 실행 상세 카드는 이제 각 issue-linked run마다 에이전트 CLI, 선택 모델, 백엔드 종류, 프로세스 ID, 할당 프롬프트, stdout/stderr, 브랜치, PR 링크, 퍼블리시 요약을 함께 보여줌
- 회사 실시간 stream이 끊기면 마지막 snapshot은 유지한 채 `회사 실시간 업데이트 연결이 끊어졌습니다. 다시 동기화하는 중...` 메시지를 보여주며 복구
- 런타임 건강도, 차단 워크플로우 수, 리뷰 주의 수, 최근 오류/동작을 한곳에 모아 둔 압축형 회사 요약 배너
- 압축형 회사 요약과 회사 설정에서 선택한 런타임의 추정 비용과 일/월 비용 상한도 함께 표시
- 고정된 보드 surface 안에서도 lane 내부 스크롤로 차단/리뷰 카드가 길게 쌓여도 읽을 수 있는 이슈 보드
- stale한 Cotor retry PR은 배치 정리로 닫아서 리뷰 루프가 오래된 open PR을 계속 쌓아 두지 않게 함
- 연결된 GitHub PR이 다시 clean 상태가 되면 stale CEO merge-conflict 차단도 자동으로 다시 열림
- 예전 CEO merge-conflict 때문에 execution 이슈가 `BLOCKED`에 남아 있던 경우도 다시 `PLANNED`로 되돌려 rebase와 republish를 이어서 할 수 있게 함
- PR이 이미 머지됐는데 stale execution sync 때문에 막혀 남은 execution 이슈도 다음 runtime tick에서 자동으로 닫힘
- 런타임 시작/중지/상태
- 회사 런타임을 명시적으로 중지하면 앱 재실행이나 회사 refresh 뒤에도 사용자가 다시 시작할 때까지 그대로 유지
- 회사 모드 이벤트마다 전체 데스크톱 새로고침을 돌리지 않고, 회사 전용 dashboard snapshot으로 상태를 바로 패치
- 한 wave의 goal work가 끝나면 CEO planning lane을 다시 열어서 첫 decomposition 이후 goal이 얼어붙지 않게 함
- continuous improvement goal은 roster가 허용하면 여러 branchable issue와 병렬 slice를 만들도록 유도
- 짧은 고수준 goal 설명도 더 넓은 execution portfolio로 보강해서, 큰 roster가 한두 개 이슈로만 줄어들지 않게 함
- 새 runnable work가 생기면 stale polling tick을 기다리지 않고 런타임이 즉시 깨어나며, 여러 회사 역할이 같은 execution CLI를 써도 runnable issue를 병렬로 시작할 수 있음
- 로컬 merge 완료 표시는 GitHub 새로고침 결과가 실제 `MERGED`일 때만 기록됨

### `TUI`

- 회사 워크플로 상태와 독립적
- 폴더/저장소를 골라 standalone `cotor` 세션 실행
- 여러 개의 live TUI 세션 병렬 유지
- 선택한 세션 중심의 터미널 작업 영역

## 저장소와 실행 격리

- 각 agent run은 `codex/cotor/<task-slug>/<agent-name>` 브랜치를 사용합니다.
- 각 agent run은 `.cotor/worktrees/<task-id>/<agent-name>` 아래 독립 worktree를 가집니다.
- 같은 task를 다시 실행하면 기존 격리 worktree를 재사용합니다.

## 현재 Company API 표면

현재 company-first 라우트:

- `GET /api/app/companies`
- `POST /api/app/companies`
- `GET /api/app/companies/{companyId}`
- `PATCH /api/app/companies/{companyId}`
- `GET /api/app/companies/{companyId}/agents`
- `POST /api/app/companies/{companyId}/agents`
- `PATCH /api/app/companies/{companyId}/agents/{agentId}`
- `GET /api/app/companies/{companyId}/projects`
- `GET /api/app/companies/{companyId}/goals`
- `POST /api/app/companies/{companyId}/goals`
- `GET /api/app/companies/{companyId}/issues`
- `GET /api/app/companies/{companyId}/review-queue`
- `GET /api/app/companies/{companyId}/activity`
- `GET /api/app/companies/{companyId}/dashboard`
- `GET /api/app/companies/{companyId}/contexts`
- `GET /api/app/companies/{companyId}/runtime`
- `POST /api/app/companies/{companyId}/runtime/start`
- `POST /api/app/companies/{companyId}/runtime/stop`
- `PATCH /api/app/companies/{companyId}/linear`
- `POST /api/app/companies/{companyId}/linear/resync`
- `PATCH /api/app/workspaces/{workspaceId}/base-branch`

오래된 클라이언트를 위한 `/api/app/company/*` 호환 라우트도 여전히 남아 있습니다.

## 현재 실제로 되는 것

- 여러 회사 생성
- 회사당 하나의 작업 폴더 바인딩
- 최소 입력 기반 회사 에이전트 정의
- 회사 에이전트별 provider 모델 override 저장
- 회사 목표 생성
- 목표를 이슈로 자동 분해
- 이슈 위임 및 실행
- 회사 단위 Linear sync가 켜져 있으면 바깥 Linear로 이슈/진행 상태 미러링
- 연결된 태스크와 실행 이력 조회
- 리뷰 큐 아이템 생성 및 머지 처리
- runtime/backend/review/session 상태를 합성한 이벤트 월과 좌석/리뷰 데스크 요약이 포함된 전용 미팅룸 보기
- 채팅 컨트롤 레일에서 목표 생성, 목표 분해, 이슈 생성, 이슈 위임, 이슈 실행, QA/CEO 판정, 머지, 런타임 제어, 백엔드 제어, 회사 에이전트 생성을 미리 보고 명시적으로 확인한 뒤 적용
- 확인용 요청을 준비하기 전에 채팅 컨트롤 레일에서 리더 AI와 워커 roster를 직접 선택
- 정상적인 회사 모드에서는 수동 새로고침 없이 회사 활동 조회
- 압축형 회사 요약 배너에서 런타임 건강도, 차단/리뷰 주의, 최근 런타임 신호 조회
- 회사 콘솔 안에서 추정 비용을 확인하고 일/월 비용 상한을 조정
- GitHub PR 발행이 필요한데 `gh`/`origin` 준비가 안 된 저장소는 회사 생성 시 경고
- 로컬 런타임 루프의 시작/중지/상태 확인
- active autonomous goal이 남아 있어도, 수동으로 중지한 회사 런타임은 사용자가 다시 시작할 때까지 유지
- active task/run이 남아 있으면 빠른 monitoring cadence를 유지해서 stale `RUNNING` 상태를 더 빨리 정리
- app-server 종료로 끊긴 회사 작업은 일반 process-exit 실패로 남기지 않고 다시 큐에 올려 재개 가능하게 복구
- 그 후 데스크톱 앱과 번들 backend가 다시 올라오면 queued delegated 회사 작업을 다시 시작하고, 회사 활동 로그에도 그 복구 흐름을 남김
- 기본 회사 프로필은 로컬 설치된 agent CLI를 우선 사용하고, 끝까지 없으면 `echo` fallback 사용

## 현재 한계

- macOS 셸만 지원합니다.
- Linear sync는 회사 단위 outward mirror이며, 기존 Linear 이슈를 다시 Cotor로 가져오지는 않습니다.
- 런타임 자동화에는 action 단위 allow/deny/approval를 다루는 정책 엔진 v1이 들어갔지만, 아직 file-backed 실험 기능입니다.
- 리뷰/PR 동기화에는 `gh` 기반 GitHub control-plane v1이 들어가서 PR 상태, mergeability, status-check summary를 읽어옵니다.
- `resume`은 이제 실험적 durable inspect/continue/fork/approve 흐름을 지원하지만, 회사 전체 issue/review 재개는 아직 완전하지 않습니다.
