# Cotor Cookbook: 실전 시나리오 파이프라인 모음

`examples/`에 있는 샘플을 바로 실무 맥락에 적용할 수 있도록 정리한 문서입니다.  
아래 패턴은 **코드 리뷰 자동화**, **레포 전체 리팩토링**, **테스트 자동 생성**, **대량 파일 처리**, **빠른 시작** 흐름을 기준으로 구성했습니다.

---

## 빠른 실행 준비

```bash
./gradlew shadowJar
java -jar build/libs/cotor-1.0.0-all.jar list
```

> 전역 설치를 쓰는 경우 `java -jar ...` 대신 `cotor` 명령으로 동일하게 실행할 수 있습니다.

---

## 1) 코드 리뷰 자동화 (PARALLEL)

- **시나리오**: 같은 변경사항을 두 모델이 동시에 리뷰하고, 결과를 비교해 코멘트 품질을 높입니다.
- **기반 예제**: `examples/parallel-compare.yaml`
- **핵심 패턴**: `executionMode: PARALLEL`

### 실행 커맨드

```bash
java -jar build/libs/cotor-1.0.0-all.jar validate compare-summaries -c examples/parallel-compare.yaml
java -jar build/libs/cotor-1.0.0-all.jar run compare-summaries -c examples/parallel-compare.yaml --output-format text
```

### 실무 적용 팁

- PR diff 핵심만 입력으로 넣고, 두 모델에게 **서로 다른 관점**(성능/보안)을 부여하면 리뷰 중복을 줄일 수 있습니다.
- 결과를 후속 stage(요약/통합 코멘트)로 연결하려면 `SEQUENTIAL` 파이프라인으로 merge 단계를 추가하세요.

---

## 2) 레포 전체 리팩토링 오케스트레이션 (DAG)

- **시나리오**: 생성/리뷰/테스트/배포 단계를 의존성 기반으로 묶어 대규모 리팩토링 작업을 자동화합니다.
- **기반 예제**: `examples/complex_dag_pipeline.yaml`
- **핵심 패턴**: `executionMode: DAG`, `dependencies`, `recovery.maxRetries`

### 실행 커맨드

```bash
java -jar build/libs/cotor-1.0.0-all.jar validate complex-dag-pipeline -c examples/complex_dag_pipeline.yaml
java -jar build/libs/cotor-1.0.0-all.jar run complex-dag-pipeline --dry-run -c examples/complex_dag_pipeline.yaml
java -jar build/libs/cotor-1.0.0-all.jar run complex-dag-pipeline -c examples/complex_dag_pipeline.yaml --verbose
```

### 실무 적용 팁

- `run-unit-tests`, `run-integration-tests`, `review-code`를 병렬 fan-in 구조로 유지하면 전체 소요 시간을 단축할 수 있습니다.
- 프로덕션 배포 전 단계에 정적 분석 stage를 추가하면 리팩토링 리스크를 더 낮출 수 있습니다.

---

## 3) 테스트 자동 생성 + 품질 보정 루프 (DECISION/LOOP)

- **시나리오**: 테스트 초안을 만들고 품질 점수가 기준 미만이면 자동 개선 루프를 돌립니다.
- **기반 예제**: `examples/decision-loop.yaml`
- **핵심 패턴**: `type: DECISION`, `type: LOOP`, `GOTO`, `untilExpression`

### 실행 커맨드

```bash
java -jar build/libs/cotor-1.0.0-all.jar validate iterate-summary -c examples/decision-loop.yaml
java -jar build/libs/cotor-1.0.0-all.jar run iterate-summary -c examples/decision-loop.yaml --output-format text
```

### 실무 적용 팁

- `score` 기반 평가 규칙을 커버리지/flake rate/테스트 실행시간 같은 팀 KPI로 바꾸면 테스트 생성 파이프라인으로 바로 전환 가능합니다.
- `maxIterations`를 작게 유지해 무한 개선 루프를 방지하세요.

---

## 4) 대량 파일 처리 (MAP/Fan-out)

- **시나리오**: 다수 파일을 동일 규칙으로 분석/변환할 때 stage를 파일 목록 기준으로 확장 실행합니다.
- **기반 예제**: `examples/map-fanout.yaml`
- **핵심 패턴**: `executionMode: MAP`, `fanout.source`

### 실행 커맨드

```bash
java -jar build/libs/cotor-1.0.0-all.jar validate map-fanout-pipeline -c examples/map-fanout.yaml
java -jar build/libs/cotor-1.0.0-all.jar run map-fanout-pipeline -c examples/map-fanout.yaml --dry-run
```

### 실무 적용 팁

- `files` 입력을 `src/**/*.ts`, `docs/**/*.md`처럼 분리하면 도메인별 fan-out 전략을 만들 수 있습니다.
- 대량 처리 시 `performance.maxConcurrentAgents`를 환경 리소스에 맞게 제한하세요.

---

## 5) 빠른 시작 / 스모크 체크 (Single Agent)

- **시나리오**: 설치 직후 실행 경로와 출력 포맷이 정상인지 가장 빠르게 확인합니다.
- **기반 예제**: `examples/single-agent.yaml`
- **핵심 패턴**: 단일 stage + 최소 설정

### 실행 커맨드

```bash
java -jar build/libs/cotor-1.0.0-all.jar validate hello-echo -c examples/single-agent.yaml
java -jar build/libs/cotor-1.0.0-all.jar run hello-echo -c examples/single-agent.yaml --output-format text
```

### 실무 적용 팁

- CI의 preflight 단계에 넣으면 환경 깨짐(실행 파일 경로, 권한, JDK 버전)을 조기 탐지할 수 있습니다.
- 신규 파이프라인을 만들 때 이 예제를 복사해 stage를 하나씩 늘리면 학습 비용이 낮습니다.

---

## 패턴 선택 가이드

- **여러 모델의 결과를 동시에 비교**: `PARALLEL`
- **의존성이 있는 대규모 작업 오케스트레이션**: `DAG`
- **조건 분기 + 반복 개선**: `DECISION` + `LOOP`
- **동일 작업을 다건 데이터에 반복**: `MAP`
- **설치/동작 확인 및 최소 재현**: `SEQUENTIAL`(single stage)

---

## 참고: 예제 일괄 실행

```bash
bash examples/run-examples.sh
```

실행 스크립트는 로컬 환경에 설치된 에이전트/플러그인 상태에 따라 일부 예제가 실패할 수 있으므로, 먼저 `validate` + `--dry-run`으로 점검하는 것을 권장합니다.
