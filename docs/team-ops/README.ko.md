# 팀 운영 & 온보딩

이 패키지는 현재 Cotor 저장소를 유지보수하는 사람을 위한 운영 문서 묶음입니다.

## 먼저 할 일

1. `../README.ko.md`, `../FEATURES.md`, `../DESKTOP_APP.md`, `../TEST_PLAN.md`를 읽습니다.
2. 저장소 공통 규칙은 `../../AGENTS.md`, `../../CONTRIBUTING.md`를 따릅니다.
3. PR을 열기 전에 변경 범위에 맞는 검증 명령을 실행합니다.

## 포함 내용

- 온보딩 체크리스트
- 운영 cadence 템플릿
- 기능 담당자 핸드오프
- 리뷰어 체크리스트
- 메인터이너 체크리스트

## 현재 전달 루프

1. 현재 동작을 재현하거나 맵핑
2. 필요한 최소 변경만 적용
3. 테스트 매트릭스에 맞춰 검증
4. 현재 동작, 한계, 후속 작업을 문서화
5. 리뷰 후 병합

## Company-First 메모

- goal, issue, review queue, runtime, context persistence를 건드릴 때는 `Company`를 최상위 제품 단위로 다룹니다.
- `repository/workspace/task/run`은 company 운영 아래의 실행 인프라로 취급합니다.
- UI나 문서에서 “Linear 같은” 동작을 언급할 때는, 실제 외부 연동이 구현되지 않은 한 Cotor 앱 내부 보드 경험으로 제한합니다.

## 현재 기준 문서

- 제품 개요: `../../README.ko.md`
- 문서 라우터: `../INDEX.md`
- 검증 매트릭스: `../TEST_PLAN.md`
- 데스크톱/컴퍼니 워크플로우: `../DESKTOP_APP.md`

## 참고

- 과거 리포트와 설계 초안은 참고 자료일 뿐 현재 기준 문서는 아닙니다.
- 코드가 바뀌면 문서도 같은 변경에서 같이 업데이트합니다.
