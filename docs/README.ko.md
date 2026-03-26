# Cotor 문서 안내

이 문서는 현재 코드베이스 기준의 문서 진입점입니다. 현재 문서와 과거 기록을 구분하려면 [INDEX.md](INDEX.md) 또는 [INDEX.ko.md](INDEX.ko.md)를 먼저 보십시오.

한글 동반 문서가 있는 경우 같은 경로에서 `.ko.md` 이름을 사용합니다.

## 먼저 볼 문서

- [QUICK_START.ko.md](QUICK_START.ko.md): 빠른 설치와 첫 실행
- [FEATURES.ko.md](FEATURES.ko.md): 코드 기준 기능 목록
- [DESKTOP_APP.ko.md](DESKTOP_APP.ko.md): `app-server`와 macOS 셸
- [TEST_PLAN.ko.md](TEST_PLAN.ko.md): 자동/수동 검증 매트릭스
- [team-ops/README.ko.md](team-ops/README.ko.md): 온보딩과 유지보수 운영

## 현재 제품 스냅샷

Cotor는 하나의 Kotlin 코어 위에 세 가지 운영 표면을 제공합니다.

- CLI/TUI 실행기
- 로컬 웹 에디터
- `cotor app-server` 기반 macOS 데스크톱 셸

데스크톱 셸에는 현재 company-first 운영 레이어도 포함됩니다.

- 작업 폴더에 매핑된 여러 회사
- 회사 에이전트 정의
- 목표
- 이슈
- 리뷰 큐
- 활동 피드
- 런타임 시작/중지/상태

## 실제 CLI 명령 체계

현재 최상위 명령:

`init`, `run`, `dash`, `interactive`, `validate`, `test`, `template`, `resume`, `checkpoint`, `stats`, `doctor`, `status`, `list`, `web`, `app-server`, `lint`, `explain`, `plugin`, `agent`, `version`, `completion`

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

## 현재 알려진 한계

- `resume`은 체크포인트 확인 기능이며 전체 실행 재개는 아직 아닙니다.
- `plugin`은 현재 `plugin init`만 실제 명령입니다.
- 보드는 `Linear 같은` UI이지만 외부 Linear 실동기화는 현재 빌드 범위가 아닙니다.
- 자율 런타임은 최소 루프 수준이며 정책 엔진과 풍부한 후속 이슈 자동화는 아직 구현되지 않았습니다.

## 참고 문서

- [ARCHITECTURE.ko.md](ARCHITECTURE.ko.md): 공통 런타임 구조
- [WEB_EDITOR.ko.md](WEB_EDITOR.ko.md): 웹 에디터 사용법
- [USAGE_TIPS.ko.md](USAGE_TIPS.ko.md): 운영 팁
- [CONDITION_DSL.ko.md](CONDITION_DSL.ko.md): 조건 DSL
- [cookbook.md](cookbook.md): 예제 워크플로우
- [CLAUDE_SETUP.md](CLAUDE_SETUP.md): Claude 연동
- [templates/temp-cotor-template.md](templates/temp-cotor-template.md): 템플릿 관련 문서
