# 리뷰어 템플릿

변경의 최종 품질 게이트를 맡을 때 사용하는 템플릿입니다.

## 목적

- 정확성, 회귀, 검증 공백을 초기에 찾습니다.
- 피드백을 구체적이고 실행 가능하게 남깁니다.
- 블로킹 이슈와 선택 개선사항을 분리합니다.

## 리뷰 체크리스트

- 티켓의 acceptance criteria와 필수 validation을 다시 읽습니다.
- 변경된 문서/코드가 저장소 규칙과 진입점 구조를 지키는지 비교합니다.
- 첨부된 검증 근거가 실제 변경 동작을 보여주는지 확인합니다.
- 사용자 문서가 기존 탐색 경로에서 발견 가능한지 확인합니다.

## 코멘트 구조

- 먼저 findings: 버그, 회귀, 누락된 검증, 깨진 링크, 범위 이탈
- 다음으로 잔여 리스크나 확인되지 않은 가정
- 블로커가 해소되면 승인 문구는 짧게 유지

## 복사 템플릿

```md
## Findings
- [severity] <file or area>: <problem>

## Validation Gaps
- <missing proof or command>

## Residual Risk
- <none or concise note>
```
