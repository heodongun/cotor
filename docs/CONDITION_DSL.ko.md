# 조건식 DSL

원문: [CONDITION_DSL.md](CONDITION_DSL.md)

이 문서는 Cotor 파이프라인에서 사용하는 조건식 DSL의 구문과 지원 기능을 설명합니다.

## 개요

조건식 DSL은 파이프라인 흐름을 제어하기 위한 논리 조건을 정의할 때 사용합니다. 식은 파이프라인 컨텍스트를 기준으로 평가되며, 여기에는 스테이지 결과, shared state, 메타데이터가 포함됩니다.

## 구문

표현식은 리터럴, 변수, 연산자, 그룹핑으로 구성됩니다.

### 리터럴

- **불리언**: `true`, `false`
- **숫자**: `123`, `45.67`
- **문자열**: `'hello'`, `"world"`

### 변수

파이프라인 컨텍스트 값을 읽기 위한 변수 형식입니다.

- **스테이지 속성**: `stageId.property`
  - `stageId.success`: 스테이지 성공 여부
  - `stageId.output`: 스테이지 출력
  - `stageId.error`: 실패 시 오류 메시지
  - `stageId.metadata.key`: 스테이지 메타데이터 값
- **공유 상태**: `context.sharedState.key`
- **파이프라인 메타데이터**: `context.metadata.key`
- **경과 시간**: `context.elapsedTimeMs`

### 연산자

우선순위 순서대로 아래 연산자를 지원합니다.

| 연산자 | 설명 | 예시 |
| --- | --- | --- |
| `!` | 논리 NOT | `!step1.success` |
| `>` | 초과 | `step1.tokens > 1000` |
| `>=` | 이상 | `step1.score >= 0.8` |
| `<` | 미만 | `step2.retries < 3` |
| `<=` | 이하 | `step3.duration <= 10000` |
| `==` | 같음 | `step1.status == 'completed'` |
| `!=` | 다름 | `step2.reason != 'timeout'` |
| `contains` | 부분 문자열 포함 | `step3.output contains 'error'` |
| `matches` | 정규식 매칭 | `step4.log matches '.*FATAL.*'` |
| `&&` | 논리 AND | `step1.success && step2.success` |
| `||` | 논리 OR | `step1.success || step2.optional` |

### 그룹핑

괄호로 우선순위를 명시할 수 있습니다.

`(step1.success && step2.tokens > 1000) || step3.fallback`

## 예시

### 단순 비교

`quality-check.validationScore >= 0.8`

### 논리식

`review.success == true && review.metadata.severity != "HIGH"`

### 중첩 표현식

`(step1.success && step2.tokens > 1000) || !step3.is_critical`
