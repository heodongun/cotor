# Cotor 기능 목록

원문: [FEATURES.md](FEATURES.md)

이 문서는 현재 코드베이스에서 실제로 뒷받침되는 기능만 정리합니다.

## CLI 및 오케스트레이션

- 실행 모드: `SEQUENTIAL`, `PARALLEL`, `DAG`
- 조건 분기와 loop 스테이지
- 메인 엔트리포인트에서의 직접 파이프라인 실행 fallback
- 인자 없이 `cotor`를 실행하면 기본 표면은 `interactive` TUI
- `tui`는 `interactive`의 별칭
- 기본 `interactive` 동작은 단일 에이전트 채팅
- 필요 시 명시적으로 `auto`, `compare` 모드 사용 가능
- 인터랙티브 세션은 `.cotor/interactive/...` 아래 transcript와 `interactive.log`를 남김

## 검증 및 복구

- `validate`: 파이프라인 구조와 의존성 점검
- `lint`: YAML 및 설정 린트
- `resume`, `checkpoint gc`: 체크포인트 확인 및 정리
- `status`: 최근/실행 중 파이프라인 상태
- `stats`: 집계 실행 통계
- `doctor`: 로컬 환경 점검

## 템플릿 시스템

현재 내장 템플릿:

1. `compare`
2. `chain`
3. `review`
4. `consensus`
5. `fanout`
6. `selfheal`
7. `verified`
8. `blocked-escalation`
9. `custom`

지원 흐름:

- `template --list`
- `template --preview <type>`
- `template --interactive`
- `template --fill key=value`

## 확장 표면

- `agent add`
- `agent list`
- `plugin init`

## 웹 표면

- `cotor web` 로컬 웹 에디터
- `--port`, `--open`, `--read-only`
- 브라우저에서 파이프라인 작성, YAML 미리보기, 저장, 실행

## 데스크톱 표면

현재 macOS 셸은 아래를 포함합니다.

- 최상위 `Company`, `TUI` 모드
- 회사당 하나의 루트 폴더
- 제목, CLI, 역할 요약 기반 에이전트 정의
- 회사 목표 생성
- 회사 이슈 선택과 실행
- 리뷰 큐 확인
- 회사 활동 피드
- 런타임 상태와 로컬 런타임 제어
- 라이브 실행 컨텍스트를 전환하는 상단 세션 스트립
- 변경, 파일, 포트, 브라우저, 리뷰 메타데이터를 담는 접이식 detail drawer
- `Company` 모드의 issue board / canvas 전환
- `TUI` 모드의 라이브 TUI 작업 영역

## 자율 운영 Company 레이어

현재 구현 범위:

- 회사 생성
- 회사 에이전트 정의
- 목표 생성
- 목표를 이슈로 분해
- 이슈 위임
- 연결된 task 생성으로 이슈 실행
- 리뷰 큐 머지 액션
- 런타임 상태, 시작, 중지, 주기 tick 루프
- `.cotor/companies/...` 아래 경량 회사 컨텍스트 저장

현재 한계:

- 보드는 앱 내부의 Linear 스타일 UI일 뿐 외부 Linear 실동기화는 아닙니다.
- follow-up issue 자동 생성은 아직 없습니다.
- 정책 엔진과 풍부한 PR/CI 동기화는 아직 없습니다.
- 런타임 자동화는 의도적으로 최소 범위입니다.
