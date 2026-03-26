# 멀티워크스페이스 / 원격실행 러너 설계

원문: [MULTI_WORKSPACE_REMOTE_RUNNER.md](MULTI_WORKSPACE_REMOTE_RUNNER.md)

이 문서는 이미 한국어 원문으로 작성된 설계 문서입니다. 이 `.ko.md` 파일은 문서 쌍 규칙을 맞추기 위한 한국어 동반 문서입니다.

## 다루는 범위

- 배경, 문제 정의, 목표/비목표
- 현재 기준선과 제안 아키텍처
- Workspace / AgentRun 모델 확장
- LocalRunner / RemoteRunner 계약
- materialization 전략
- 실행 시퀀스, 스케줄링, lease
- API 변경 방향
- 보안, 장애 처리, TUI 범위
- 단계별 도입 계획과 검증 전략

## 읽는 방법

설계 결정과 단계별 제안은 원문 문서를 그대로 읽는 것이 가장 정확합니다. 이 동반 문서는 한국어 대응 파일 경로를 제공하기 위한 목적입니다.
