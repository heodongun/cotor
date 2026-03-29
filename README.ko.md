# Cotor

Cotor는 로컬 우선 AI 워크플로우 실행기에서 출발해, CEO AI가 하위 AI에게 일을 분배하고 CLI 기반 실행으로 비용을 낮추며 macOS에서 운영 상태를 보는 “회사형 AI 운영체제”로 확장된 도구입니다. 파이프라인 실행, localhost `app-server`, 네이티브 데스크톱 셸이 같은 Kotlin 기반을 공유합니다.

## 현재 빌드에서 실제로 되는 것

- `SEQUENTIAL`, `PARALLEL`, `DAG` 파이프라인 실행
- 검증, 린트, 상태 조회, 통계, 체크포인트, 템플릿 생성
- 로컬 웹 에디터와 YAML 저장/실행
- `cotor app-server` 기반 macOS 데스크톱 셸
- 회사, 에이전트 정의, 목표, 이슈, 리뷰 큐, 활동 피드, 런타임 상태를 포함한 다중 컴퍼니 운영 레이어
- 회사별 추정 AI 비용 집계와 일/월 비용 상한 설정

## 현재 CLI 명령 체계

`Main.kt` 기준 최상위 명령:

`init`, `run`, `dash`, `interactive`, `validate`, `test`, `template`, `resume`, `checkpoint`, `stats`, `doctor`, `status`, `list`, `web`, `app-server`, `lint`, `explain`, `plugin`, `agent`, `version`, `completion`

중요한 진입 규칙:

- 인자 없이 `cotor`를 실행하면 `interactive`가 시작됩니다.
- `cotor tui`는 `interactive` 별칭입니다.
- `interactive`는 기본적으로 선호 단일 에이전트 채팅으로 시작하고, `--mode auto|compare` 또는 `:mode ...`로 멀티 에이전트에 전환할 수 있습니다.
- interactive transcript는 `.cotor/interactive/...` 아래에 저장되며, 각 세션은 transcript 옆에 `interactive.log`도 기록합니다.
- packaged install에서 로컬 config가 없으면 interactive starter config는 현재 디렉터리 대신 `~/.cotor/interactive/default/cotor.yaml` 아래에 생성됩니다.
- packaged first-run interactive는 즉시 응답 가능한 AI starter만 자동 선택하고, 인증되지 않은 CLI 때문에 깨지는 대신 안전한 `example-agent` Echo starter로 내려갑니다.
- 첫 인자가 알 수 없는 명령이면 직접 파이프라인 실행으로 폴백합니다.

현재 서브커맨드:

- `agent add`, `agent list`
- `plugin init`
- `checkpoint gc`

현재 템플릿 종류:

- `compare`
- `chain`
- `review`
- `consensus`
- `fanout`
- `selfheal`
- `verified`
- `blocked-escalation`
- `custom`

## 빠른 시작

```bash
git clone https://github.com/yourusername/cotor.git
cd cotor
./gradlew shadowJar
chmod +x shell/cotor
./shell/cotor version
```

처음 많이 쓰는 명령:

```bash
cotor
cotor help
cotor help --lang en
cotor --short
cotor init --starter-template
cotor template --list
cotor validate <pipeline> -c <config>
cotor run <pipeline> -c <config> --output-format text
cotor app-server --port 8787
```

## macOS 데스크톱

로컬 앱 번들을 빌드하고 설치:

```bash
cotor install
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

CLI에서 설치된 앱 번들을 직접 관리할 수도 있습니다:

```bash
cotor install
cotor update
cotor delete
```

Homebrew 설치에서는 `cotor install` / `cotor update`가 패키지에 포함된 번들을 복사합니다.
소스 체크아웃에서는 같은 명령이 로컬에서 데스크톱 앱을 다시 빌드한 뒤 설치합니다.
`cotor install`은 실제 설치된 앱 경로를 출력하고, `/Applications`에 쓸 수 없으면 자동으로 `~/Applications`를 사용합니다.
즉 `brew install cotor` 다음에는 `cotor install`을 한 번 실행해 앱 번들을 Applications로 복사해야 합니다.
packaged install의 실제 첫 실행 경로와 문제 해결은 [docs/HOMEBREW_INSTALL.ko.md](docs/HOMEBREW_INSTALL.ko.md)를 보면 됩니다.

현재 폴더에 이미 `./cotor.yaml`이 있으면 packaged starter 대신 그 로컬 config가 우선합니다.

현재 데스크톱 셸 구조:

- 최상위 `Company` / `TUI` 모드 분리
- `Company` 모드에서 회사 목록, 에이전트 정의, 목표, 이슈 보드/캔버스, 활동 피드, 런타임 제어
- `Company` 요약은 별도 긴 상태 카드 대신 메인 요약 배너 안에서 런타임 건강도, 차단 수, 리뷰 주의 수, 최근 오류/동작을 함께 보여줌
- `Company` 요약은 선택한 회사의 추정 비용과 일/월 비용 상한도 함께 보여줌
- `Company` 모드는 기본적으로 이벤트 기반 live update를 사용해서, 정상 동작 중에는 수동 새로고침 없이 활동 로그, 이슈, 리뷰 상태, 런타임 상태가 바로 반영됨
- 회사 실시간 stream이 잠깐 끊겨도 현재 company snapshot은 유지하고, generic decode 오류 대신 회사 전용 재동기화 메시지를 보여줌
- 이슈 보드는 lane 내부 스크롤을 써서 차단/리뷰 카드가 많아져도 상단만 잘린 채 보이지 않게 함
- stale한 Cotor retry PR은 배치 정리로 닫아서 같은 리뷰 루프가 수백 개의 오래된 open PR을 계속 남기지 않게 함
- GitHub PR이 다시 clean 상태가 되면 stale merge-conflict 차단도 자동으로 CEO lane으로 되돌려서, rebase 후 수동 리셋 없이 흐름을 이어감
- 오래된 CEO merge-conflict 차단 상태는 다시 execution으로 되돌려 rebase, republish, 후속 진행이 가능하게 함
- 연결된 PR이 이미 머지됐는데 stale execution sync 때문에 `BLOCKED`로 남은 이슈는 다음 runtime tick에서 자동으로 `DONE`으로 정규화됨
- `TUI` 모드에서 폴더 기반 단독 `cotor` 터미널을 여러 개 병렬로 유지
- 활성 실행 컨텍스트를 옮기는 상단 세션 스트립
- 변경점, 파일, 포트, 브라우저, 리뷰 메타데이터를 담는 접이식 상세 드로어

## 자율 운영 컴퍼니 상태

현재 빌드에서 실제로 가능한 흐름:

- 작업 폴더별로 여러 회사 생성
- GitHub PR 모드인데 `gh` 인증이나 `origin` 연결이 없으면 회사 생성 직후 바로 경고 표시
- 직함/CLI/역할 설명만으로 회사 에이전트 정의
- 회사 목표 생성
- 목표를 이슈로 분해
- 이슈 위임 및 실행
- 리뷰 큐 생성
- 한 wave가 끝나면 CEO planning lane을 다시 열어서 active goal이 첫 batch 이후에도 다음 이슈 wave를 이어서 만들 수 있음
- continuous improvement goal은 한 개의 좁은 후속 이슈보다 여러 branchable issue와 병렬 slice를 우선 만들도록 유도
- 짧은 고수준 goal 설명도 더 넓은 execution portfolio로 보강해서, 큰 roster가 한두 개 이슈로만 수렴하지 않게 함
- 회사 런타임을 수동으로 중지하면 앱 재실행, dashboard 조회, 실시간 재연결 뒤에도 시작을 다시 누르기 전까지 그대로 중지 상태 유지
- 회사 요약 페이지에서 압축된 런타임 상태, 차단/리뷰 주의, 활동 피드 조회
- 회사별 로컬 자율 런타임 시작/중지
- active task/run이 남아 있으면 회사 런타임이 느린 idle backoff로 내려가지 않고 빠른 monitoring cadence를 유지해서 죽은 `RUNNING` 상태를 더 빨리 정리
- app-server가 active company work 도중 종료되면, 현재 빌드는 일반 process-exit 실패로 굳히지 않고 해당 이슈를 다시 큐에 올려 재개 가능하게 유지
- 그 뒤 데스크톱 앱을 다시 열면 실행 중이던 회사 런타임이 queued delegated work를 다시 태우고, 회사 활동 로그에도 복구 흐름이 바로 반영됨

현재 한계:

- 앱 안의 보드는 `Linear 같은` 운영 UI일 뿐, 외부 Linear 실동기화는 이번 빌드 범위가 아닙니다.
- 런타임 자동화는 최소 루프 수준입니다.
- 정책 엔진과 실제 PR/CI 동기화는 아직 구현되지 않았습니다.
- 회사 컨텍스트는 `.cotor/companies/...` 아래 로컬 스냅샷으로 유지되지만 아직 경량 지식 레이어 수준입니다.
- `resume`은 체크포인트를 보여주지만 실제 재개는 아직 지원하지 않습니다.

## 문서

시작점:

- [문서 인덱스](docs/INDEX.md)
- [영문 가이드](docs/README.md)
- [한글 가이드](docs/README.ko.md)
- [빠른 시작](docs/QUICK_START.md)
- [문제 해결](docs/TROUBLESHOOTING.ko.md)
- [데스크톱 앱](docs/DESKTOP_APP.md)
- [기능 목록](docs/FEATURES.md)
- [검증 계획](docs/TEST_PLAN.md)
- [팀 운영](docs/team-ops/README.ko.md)
- [AI 에이전트 규칙](AGENTS.md)

과거 리포트, 릴리스 기록, 설계 초안은 [docs/INDEX.md](docs/INDEX.md)의 `Historical / design records` 섹션에서 찾을 수 있습니다.

## 검증 기준선

```bash
./gradlew --no-build-cache test -x jacocoTestCoverageVerification
cd macos && swift build
```
