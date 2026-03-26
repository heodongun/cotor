# Cotor 아키텍처

원문: [ARCHITECTURE.md](ARCHITECTURE.md)

Cotor는 **설정 기반 파이프라인 오케스트레이터**입니다. 현재 코드 기준 핵심 흐름은 아래와 같습니다.

`설정 로드 → 검증 → 오케스트레이션 → 모니터링/체크포인트 → 출력`

## 1) 상위 구성 요소

```mermaid
graph TD
    A["CLI / TUI / Web"] --> B["Pipeline Loader"]
    B --> C["Validator"]
    C --> D["Orchestrator"]

    D --> E["Stage Executor"]
    E --> F["Agent Runners"]

    D --> G["Condition / Loop Engine"]
    D --> H["Checkpoint Store"]
    D --> I["Realtime Monitor"]

    I --> J["Terminal / Web Dashboard"]
    H --> K["Resume / Recovery"]
```

## 2) 런타임 흐름

```mermaid
sequenceDiagram
    participant U as User
    participant P as CLI(Web/TUI)
    participant V as Validator
    participant O as Orchestrator
    participant E as Executor
    participant M as Monitor
    participant C as Checkpoint

    U->>P: cotor run <pipeline> -c <config>
    P->>V: YAML 파싱 + 검증
    V-->>P: Valid
    P->>O: 파이프라인 시작

    loop 각 스테이지 반복
      O->>E: 스테이지 실행
      E-->>O: 결과 / 오류
      O->>M: 이벤트 발행
      O->>C: 체크포인트 저장
    end

    O-->>P: 최종 출력
    P-->>U: 텍스트/JSON 요약
```

## 3) 코드 기준 모듈 맵

- `src/main/kotlin/com/cotor/domain/`
  - orchestrator, executor, condition 엔진
- `src/main/kotlin/com/cotor/presentation/`
  - CLI, web, formatter
- `src/main/kotlin/com/cotor/monitoring/`
  - 런타임 이벤트와 모니터링
- `src/main/kotlin/com/cotor/checkpoint/`
  - 체크포인트 저장과 조회
- `src/main/kotlin/com/cotor/validation/`
  - 파이프라인과 설정 검증

## 4) 왜 이렇게 나뉘는가

- **관심사 분리**
  - 파싱, 검증, 실행, 표시를 분리해 변경 영향 범위를 줄입니다.
- **복구 가능성**
  - 체크포인트와 resume 계층을 통해 중단 후 분석 또는 재개 기반을 제공합니다.
- **관측 가능성**
  - 동일한 모니터 이벤트를 CLI, TUI, Web이 공유해 일관된 상태 표시가 가능합니다.

## 관련 문서

- [QUICK_START.md](QUICK_START.md)
- [FEATURES.md](FEATURES.md)
- [MULTI_WORKSPACE_REMOTE_RUNNER.md](MULTI_WORKSPACE_REMOTE_RUNNER.md)
- [WEB_EDITOR.md](WEB_EDITOR.md)
- [USAGE_TIPS.md](USAGE_TIPS.md)
