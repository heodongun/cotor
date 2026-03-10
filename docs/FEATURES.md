# Cotor 기능 요약 (코드 기준 최신)

**버전**: 1.0.0

## 실행/오케스트레이션

- 실행 모드: `SEQUENTIAL`, `PARALLEL`, `DAG`
- 조건 분기/루프 스테이지 지원
- 스테이지 의존성 해석 및 검증
- 실행 중 이벤트 기반 타임라인 수집

## 검증/품질

- `validate`: 파이프라인 구조/의존성/에이전트 정의 검증
- `lint`: YAML/설정 정적 점검
- 출력 검증기(`output` validation) 및 템플릿 검증

## 모니터링/복구

- `run --watch`: 진행 상황 모니터링
- `run --dry-run`: 실행 없이 예상 흐름/시간 확인
- 체크포인트 저장/복원(`resume`, `checkpoint gc`)
- 최근/실행중 파이프라인 상태 조회(`status`)
- 통계 누적 및 추세 표시(`stats`)

## 템플릿 시스템

`cotor template --list` 기준 내장 템플릿:

1. `compare`
2. `chain`
3. `review`
4. `consensus`
5. `fanout`
6. `selfheal`
7. `verified`
8. `custom`

지원 기능:
- 템플릿 미리보기(`--preview`)
- 파일 출력
- 플레이스홀더 치환(`--fill key=value`)

## CLI 명령 (현재 Main 등록 기준)

- 기본: `init`, `list`, `run`, `validate`, `test`, `version`, `completion`
- 운영/관측: `status`, `stats`, `doctor`, `dash`, `interactive`, `web`
- 유지보수: `resume`, `checkpoint`
- 생산성: `template`, `lint`, `explain`
- 확장: `plugin`, `agent`

## 확장 기능

- Agent preset 추가/조회: `agent add`, `agent list`
- Plugin 점검/스캐폴딩: `plugin list`, `plugin validate`, `plugin init`

## 출력

`run --output-format`:
- `json` (기본)
- `csv`
- `text`

## 보안

- 실행파일/디렉토리 화이트리스트 기반 검증
- 위험 명령 패턴 차단을 위한 보안 검증 계층

## 인터페이스

- 기본 실행: 인자 없이 `cotor` → interactive 모드
- 별칭: `cotor tui` (interactive alias)
- 단축 안내: `cotor --short`

## 데스크톱 앱 (macOS)

- `cotor app-server` 로컬 API 서버
- `macos/` SwiftUI 네이티브 셸
- 좌측 리포지토리/워크스페이스, 중앙 태스크 실행, 우측 변경점/파일/포트/브라우저 3패널 구조
- 에이전트별 `codex/cotor/<task-slug>/<agent-name>` 브랜치와 `.cotor/worktrees/<task-id>/<agent-name>` 격리 실행
- `shell/install-desktop-app.sh` 로 `.app` 번들 생성, `Applications` 설치, `~/Downloads` 배포본 동시 갱신
