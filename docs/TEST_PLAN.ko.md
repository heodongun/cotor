# Cotor 검증 계획

원문: [TEST_PLAN.md](TEST_PLAN.md)

이 문서는 현재 코드 기준 검증 계획과 실행 매트릭스를 정리합니다.

## 1. 자동화 기준선

아래 순서로 실행합니다.

```bash
./gradlew --no-build-cache test -x jacocoTestCoverageVerification
cd macos && swift build
```

목적:

- Kotlin 코어와 app-server 회귀 점검
- macOS 셸 컴파일 점검

## 2. CLI 스모크 매트릭스

| 표면 | 선행 조건 | 실행 | 기대 결과 | 모드 |
| --- | --- | --- | --- | --- |
| `init` | repo checkout | `./gradlew run --args='init --help'` | help 출력 | automated |
| `list` | sample config | `./gradlew run --args='list --help'` | help 출력 | automated |
| `run` | sample config | `./gradlew run --args='run --help'` | help 출력 | automated |
| `validate` | sample config | `./gradlew run --args='validate --help'` | help 출력 | automated |
| `test` | repo checkout | `./gradlew run --args='test --help'` | help 출력 | automated |
| `version` | repo checkout | `./gradlew run --args='version'` | 버전 출력 | automated |
| `completion` | repo checkout | `./gradlew run --args='completion zsh'` | completion 스크립트 출력 | semi-automated |
| `status` | repo checkout | `./gradlew run --args='status --help'` | help 출력 | automated |
| `stats` | repo checkout | `./gradlew run --args='stats --help'` | help 출력 | automated |
| `doctor` | Java 설치 | `./gradlew run --args='doctor'` | 환경 리포트 출력 | semi-automated |
| `dash` | repo checkout | `./gradlew run --args='dash --help'` | help 출력 | automated |
| `interactive` / `tui` | 터미널 연결 | `./gradlew run --args='interactive --help'` | help 출력 | automated |
| `web` | repo checkout | `./gradlew run --args='web --help'` | help 출력 | automated |
| `template` | repo checkout | `./gradlew run --args='template --list'` | 템플릿 목록 출력 | automated |
| `lint` | repo checkout | `./gradlew run --args='lint --help'` | help 출력 | automated |
| `explain` | repo checkout | `./gradlew run --args='explain --help'` | help 출력 | automated |
| `plugin` | repo checkout | `./gradlew run --args='plugin --help'` | help 출력 | automated |
| `agent` | repo checkout | `./gradlew run --args='agent --help'` | help 출력 | automated |
| `app-server` | repo checkout | `./gradlew run --args='app-server --help'` | help 출력 | automated |

실패를 기록할 때는 다음을 남깁니다.

- 실행한 명령
- stdout/stderr
- 문서 불일치인지, 런타임 실패인지, 환경 특이 문제인지

## 3. 데스크톱 / 수동 스모크 매트릭스

| 시나리오 | 기대 결과 |
| --- | --- |
| 로컬 백엔드와 함께 앱 부팅 | dashboard가 로드되거나 명확히 offline 진입 |
| company 기본 모드 | 설정이 다르지 않으면 `Company` 모드로 시작 |
| 회사 생성 | 새 회사가 루트 경로와 기본 브랜치와 함께 보임 |
| 에이전트 생성 | 회사 roster에 새 정의가 보임 |
| 저장소 선택 | 선택한 저장소와 workspaces가 동기화 유지 |
| 목표 생성 | 선택 회사 아래 새 목표가 나타남 |
| 이슈 선택 | session strip과 detail drawer 컨텍스트 갱신 |
| TUI 모드 | 라이브 세션 콘솔이 중앙 표면 유지 |
| detail drawer 토글 | changes/files/ports/browser/review 정보 토글 |
| board/canvas 전환 | 선택 이슈를 잃지 않고 보드 전환 |
| session card 클릭 | 카드 어느 곳이든 클릭 시 선택 전환 |
| base branch 업데이트 | backend workspace 갱신 후 TUI 세션 재시작 |

## 4. 자율 Company 검증 매트릭스

| 시나리오 | 기대 결과 | 현재 메모 |
| --- | --- | --- |
| 회사 생성 | 루트 경로와 기본 브랜치로 저장 | live |
| 에이전트 정의 | title/CLI/role summary 저장 및 표시 | live |
| 목표 생성 | dashboard에 목표 저장/반환 | live |
| 목표 분해 | 선택 목표의 이슈 생성 | live |
| 이슈 위임 | assignee/status 반영 | live |
| 이슈 실행 | 연결된 task/run 시작 | live |
| task/run linkage | 선택 이슈가 최신 linked task를 가리킴 | live |
| 리뷰 큐 채움 | 조건 충족 시 리뷰 큐 아이템 생성 | local state model |
| 활동 피드 | 회사 활동이 표시됨 | live |
| 런타임 상태 | status/start/stop 응답 | live |
| ready-to-merge 루프 | `READY_TO_MERGE` 항목 머지 가능 | live |
| 다중 회사 분리 | A 회사 상태가 B 회사로 새지 않음 | live in state model |
| 후속 이슈 생성 | n/a | 미구현 |
| 정책 엔진 | n/a | 미구현 |
| 외부 Linear sync | n/a | 현재 빌드 미구현 |

## 5. 이슈 로그 템플릿

```md
Title:
Area:
Command / Flow:
Expected:
Actual:
Impact:
Repro steps:
Logs / Output:
Decision:
- fixed now
- documented as known limitation
- deferred historical note only
```

## 6. 결함 처리 규칙

- 현재 문서화된 동작을 막는 실패는 같은 변경 안에서 바로 고칩니다.
- 실제 gap이지만 치명적이지 않으면 known limitation으로 문서화합니다.
- 과거 문서나 초안 문서는 막혀 있거나 미완성인 동작을 live처럼 적으면 안 됩니다.
