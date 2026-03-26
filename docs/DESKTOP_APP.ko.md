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
- 에이전트 정의 작성
- 목표 목록과 목표 생성
- 앱 내부의 Linear 스타일 이슈 보드/캔버스
- 회사 활동 피드
- 런타임 건강도, 차단 워크플로우 수, 리뷰 주의 수, 최근 오류/동작을 한곳에 모아 둔 압축형 회사 요약 배너
- 런타임 시작/중지/상태

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
- 회사 목표 생성
- 목표를 이슈로 자동 분해
- 이슈 위임 및 실행
- 회사 단위 Linear sync가 켜져 있으면 바깥 Linear로 이슈/진행 상태 미러링
- 연결된 태스크와 실행 이력 조회
- 리뷰 큐 아이템 생성 및 머지 처리
- 회사 활동 조회
- 압축형 회사 요약 배너에서 런타임 건강도, 차단/리뷰 주의, 최근 런타임 신호 조회
- GitHub PR 발행이 필요한데 `gh`/`origin` 준비가 안 된 저장소는 회사 생성 시 경고
- 로컬 런타임 루프의 시작/중지/상태 확인
- 기본 회사 프로필은 로컬 설치된 agent CLI를 우선 사용하고, 끝까지 없으면 `echo` fallback 사용

## 현재 한계

- macOS 셸만 지원합니다.
- Linear sync는 회사 단위 outward mirror이며, 기존 Linear 이슈를 다시 Cotor로 가져오지는 않습니다.
- 런타임 자동화에는 정책 엔진과 후속 이슈 생성이 아직 없습니다.
- 리뷰/PR 동기화는 현재 로컬 상태 중심이며, 완전한 GitHub/CI live orchestration은 아닙니다.
- `resume`은 여전히 체크포인트 조회 성격이고 전체 실행 재개는 아닙니다.
