> 상태: 과거 개선 추적 문서입니다. 여기의 항목은 현재 제품 표면과 다를 수 있습니다.

# 개선 이슈

원문: [IMPROVEMENT_ISSUES.md](IMPROVEMENT_ISSUES.md)

## `feature/improvements-batch`에서 완료된 항목

- [x] 이슈 1: 파이프라인 실행 추적과 상태 보고
  - `PipelineRunTracker`를 추가했고, `status` CLI가 활성 실행과 최근 실행, 실행 시간을 보여줍니다.
- [x] 이슈 2: recovery fallback agent 조기 검증
  - 파이프라인 검증 단계에서 fallback agent 존재 여부를 확인하고, fallback 전략만 있고 대상이 없으면 경고합니다.
- [x] 이슈 3: config repository UX 강화
  - 설정 파일이 없으면 빠르게 명확한 오류로 실패하고, 저장 시 부모 디렉터리를 자동 생성합니다.
- [x] 이슈 4: `stats` CLI 유지보수
  - `stats`가 DI를 사용하고, `--clear` 정리 옵션과 통계 저장 테스트를 추가했습니다.

## 백로그 아이디어

- [ ] 프로세스 재시작 후에도 `status`가 이어지도록 run history를 디스크에 저장
- [ ] tracker 데이터를 TUI/Web 라이브 대시보드에 노출

> 상태: 과거 추적용 노트입니다. 현재 제품 계약 문서로 읽으면 안 됩니다.
