# Cotor 사용 팁

원문: [USAGE_TIPS.md](USAGE_TIPS.md)

## 빠른 탐색

- `cotor --short`: 10줄 치트시트
- `cotor --help`: 최상위 명령 도움말
- `cotor template --list`: 현재 템플릿 목록 확인
- `cotor app-server --help`: 로컬 데스크톱 백엔드 옵션 확인

## 안전한 실행 루프

- `run` 전에 항상 `cotor validate <pipeline> -c <config>` 실행
- 실행 모드나 의존성을 바꿨다면 `--dry-run` 사용
- CLI 누락을 파이프라인 버그로 보기 전에 `cotor doctor` 확인
- 실패한 실행 뒤에는 `status`와 `stats`를 같이 확인

## 현재 명령 주의점

- `resume`은 체크포인트 조회 기능이고 아직 실행 재개는 아님
- `plugin`은 현재 `plugin init`만 실제 동작
- 인자 없이 `cotor`를 실행하면 interactive TUI 시작
- `cotor tui`는 `interactive`의 alias

## 데스크톱 운영 팁

- session strip을 issue/run 컨텍스트를 오가는 빠른 전환기로 사용
- diff, 파일, 포트, 브라우저, 리뷰 메타데이터가 필요할 때만 detail drawer를 펼침
- board/canvas는 운영 관점 뷰로 보고, 기본 live execution 표면으로 생각하지 않기

## 자율 Company 운영 팁

- 한 번에 모호한 다중 목표보다, 집중된 목표 하나를 생성
- runtime 시작 전에 생성된 이슈를 먼저 검토
- runtime start/stop/status로 루프 실제 동작 여부 확인
- `Linear` sync는 어댑터가 완성되기 전까지는 non-live로 취급
