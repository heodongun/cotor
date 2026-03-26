# Cotor 문서 인덱스

원문: [INDEX.md](INDEX.md)

이 문서는 **현재 제품 문서**와 **과거 기록/설계 기록**을 구분해서 찾기 위한 라우터입니다.

## 현재 제품 문서

- `README.md` / `README.ko.md`: 최상위 제품 스냅샷
- `docs/README.md` / `docs/README.ko.md`: 문서 진입 가이드
- `docs/QUICK_START.md`: 첫 설치와 첫 실행
- `docs/HOMEBREW_INSTALL.md` / `docs/HOMEBREW_INSTALL.ko.md`: packaged 설치, 첫 실행 경로, Homebrew 문제 해결
- `docs/FEATURES.md` / `docs/FEATURES.ko.md`: 코드 기준 기능 목록
- `docs/DESKTOP_APP.md` / `docs/DESKTOP_APP.ko.md`: `app-server`, Company/TUI 셸, 다중 회사 UI
- `docs/TEST_PLAN.md` / `docs/TEST_PLAN.ko.md`: 자동/CLI/데스크톱/자율 회사 검증 계획
- `docs/USAGE_TIPS.md` / `docs/USAGE_TIPS.ko.md`: 운영 팁과 복구 습관
- `docs/WEB_EDITOR.md` / `docs/WEB_EDITOR.ko.md`: 웹 에디터 사용법
- `docs/ARCHITECTURE.md` / `docs/ARCHITECTURE.ko.md`: 공용 런타임 아키텍처
- `docs/CONDITION_DSL.md` / `docs/CONDITION_DSL.ko.md`: 조건 DSL 참조
- `docs/cookbook.md`: 시나리오 패턴과 예제 워크플로우
- `docs/CLAUDE_SETUP.md`: Claude 연동 설정
- `docs/team-ops/README.md` / `docs/team-ops/README.ko.md`: 온보딩과 전달 운영
- `docs/templates/temp-cotor-template.md`: 템플릿 메모

## 과거 기록 / 설계 문서

- `docs/reports/*`: 과거 보고서와 벤치마크 노트
- `docs/release/CHANGELOG.md`: 릴리스 이력
- `docs/release/FEATURES_v1.1.md`: 버전 시점 기능 스냅샷
- `docs/DIFFERENTIATED_PRD_ARCHITECTURE.md`: 전략/아키텍처 초안
- `docs/MULTI_WORKSPACE_REMOTE_RUNNER.md`: 러너 설계 초안
- `docs/UPGRADE_RECOMMENDATIONS.md`: 업그레이드 제안 메모
- `docs/IMPROVEMENT_ISSUES.md` / `docs/IMPROVEMENT_ISSUES.ko.md`: 과거 개선 이슈 추적
- `docs/ci-failure-analysis.md` / `docs/ci-failure-analysis.ko.md`: CI 장애 분석 메모

## 현재 진실 규칙

- 명령어 가용성은 `src/main/kotlin/com/cotor/Main.kt`와 일치해야 합니다.
- 데스크톱/회사 워크플로 동작은 `src/main/kotlin/com/cotor/app/*`, `macos/Sources/CotorDesktopApp/*`와 일치해야 합니다.
- “Linear 스타일 보드” 같은 표현은 Cotor 내부 UI 모양을 뜻하며, 외부 제품 실동기화를 뜻하지 않습니다.
- 과거 기록은 맥락용으로만 읽고, 현재 동작의 소스 오브 트루스로 취급하면 안 됩니다.
