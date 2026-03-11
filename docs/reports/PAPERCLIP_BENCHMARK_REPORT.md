# [COT-10] Paperclip 대비 벤치마크 리포트

- 이슈: HEO-74 / COT-10
- 작성일: 2026-03-11
- 목적: 저장소 내 확인 가능한 실험/통계 산출물을 기준으로 Cotor 성능 신호를 정리하고, Paperclip 기준선 부재를 명시한다.

---

## 1) 범위와 제약

이 리포트는 **저장소에 체크인된 로컬 산출물만** 사용한다.

- 사용 산출물
  - `.cotor/stats/conditional.json`
  - `.cotor/stats/looping.json`
  - `test/results/compare-solutions-result.md`
  - `test-results/README.md`
  - `test-claude/EXPERIMENT_RESULTS.md`
- 제약 사항
  - 저장소 내 `Paperclip` 키워드/리포트/기준선 데이터가 확인되지 않아, 수치 기반 정량 비교는 현재 불가

---

## 2) Cotor 측정 결과 (체크인 데이터 기반)

### 2.1 `.cotor/stats` 집계

아래는 각 파이프라인의 `executions[*].totalDuration` 기준 집계다.

| Pipeline | 실행 수 | 성공(실패 0) | 평균(totalDuration) | 최소 | 최대 |
|---|---:|---:|---:|---:|---:|
| conditional | 29 | 29 | 33.31ms | 15ms | 85ms |
| looping | 29 | 29 | 7.72ms | 3ms | 21ms |

해석:
- 두 파이프라인 모두 체크인된 표본에서 실패가 없다.
- looping 파이프라인이 conditional 대비 짧은 지연 분포를 보인다.

### 2.2 보조 실험 신호

- `test/results/compare-solutions-result.md`
  - 병렬 비교 실험 요약: Claude 28.4초, Codex 7.5초, Gemini 12.3초(총 3/3 성공으로 보고)
- `test-claude/EXPERIMENT_RESULTS.md`
  - 단일 호출 성공 사례(17.1초)와 병렬 실행에서 외부 AI 실패 사례(Claude 성공 / Gemini 실패)를 함께 기록
- `test-results/README.md`
  - CLI 기능 검증 13/13 성공 요약 제공

위 보조 신호는 런타임 품질/안정성 맥락을 제공하지만, Paperclip 대비 직접 비교치로 사용되지는 않는다.

---

## 3) Paperclip 기준선 현황

현재 저장소에서는 다음 항목을 찾을 수 없다.

- Paperclip 벤치마크 원시 데이터
- Paperclip 실행 로그/통계 파일
- Paperclip 대비 비교 리포트

따라서 본 문서는 **Cotor 실측 요약 + 기준선 부재 리스크 명시** 형태로 제공한다.

---

## 4) 결론

1. Cotor 체크인 통계에서 `conditional`/`looping` 파이프라인은 각 29회 모두 실패 없이 기록되어 있다.
2. 평균 지연은 `conditional 33.31ms`, `looping 7.72ms`다.
3. Paperclip 기준선 산출물이 현재 저장소에 없어 정량적 우열 비교(예: 속도 배수, 성공률 차이)는 산출 불가다.

---

## 5) 후속 액션 제안

- Paperclip 동일 시나리오(`conditional`, `looping`에 대응되는 워크로드) 실행 로그를 동일 포맷(JSON)으로 수집
- 최소 표본 수(예: N=30)와 동일 실행 환경(머신/모델/네트워크 조건) 고정
- 동일 집계 스크립트로 평균/분산/실패율을 재계산해 본 문서에 병합
