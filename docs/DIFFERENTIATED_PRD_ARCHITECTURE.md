# COT-3 차별화 PRD / 아키텍처

> Issue: HEO-67 / COT-3  
> 목적: Cotor가 일반적인 AI CLI를 넘어 **운영 가능한 멀티에이전트 실행 플랫폼**으로 차별화되기 위한 제품/아키텍처 기준선을 정의한다.

## 1. 문제 정의 (Problem)

현재 AI CLI/워크플로우 도구는 “실행”은 잘하지만, 실제 팀 운영에서 필요한 다음 요소가 약하다.

- 실행 전 품질 게이트(정적 검증, 정책, 의존성 확인)
- 실행 중 가시성(타임라인, 병목, 실패 원인)
- 실행 후 회복성(체크포인트 기반 재시도/재개)
- 표준화(템플릿/정책/지표로 재현 가능한 운영)

이로 인해 팀은 반복적인 수동 점검, 장애 시 전면 재실행, 실행 결과 신뢰성 저하를 겪는다.

## 2. 기회 (Opportunity)

멀티에이전트 사용량이 늘어나면서, 단일 모델 프롬프트 최적화보다 **파이프라인 운영 체계**가 생산성을 좌우한다. Cotor는 아래 포지션을 목표로 한다.

- 개인 실험 도구 → 팀 운영 도구
- 스크립트 기반 임시 연결 → 선언형 파이프라인 표준
- 결과 중심 사용 → 품질/성능/비용 지표 기반 운영

## 3. 타깃 사용자 및 JTBD

### Primary Persona

- AI/데이터/플랫폼 엔지니어
- 내부 자동화 파이프라인을 운영하는 개발팀

### JTBD

- “여러 에이전트 단계를 안전하게 연결하고, 실패해도 빠르게 복구하고 싶다.”
- “실행 품질을 팀 기준으로 강제하고, 변경 영향도를 빠르게 파악하고 싶다.”
- “단순 성공/실패가 아니라 성능 추세까지 보고 최적화하고 싶다.”

## 4. 차별화 가설 (Differentiation Thesis)

Cotor의 핵심 차별점은 다음 3축의 결합이다.

1. **Execution Reliability**: checkpoint/resume, 단계별 상태 추적, 재실행 전략
2. **Operational Visibility**: timeline, watch, stats, doctor를 통한 운영 가시성
3. **Governed Pipelines**: validate/lint/security 정책으로 실행 전 리스크 차단

즉, “에이전트 실행기”가 아니라 “운영 가능한 AI 파이프라인 제어면(control plane)”을 제공한다.

## 5. 제품 원칙 (Principles)

- **안전 우선**: 실패를 감추지 않고 조기 탐지/격리한다.
- **명시적 구성**: 암묵적 동작보다 선언형 설정/정책을 우선한다.
- **복구 가능성**: 재시도보다 재개(resume)를 기본값으로 설계한다.
- **관측 가능성**: 모든 실행은 최소한의 진단 정보(시간/상태/오류)를 남긴다.
- **점진적 도입**: 작은 파이프라인부터 DAG/병렬 확장까지 같은 UX로 제공한다.

## 6. 비목표 (Non-Goals)

- 자체 LLM 학습/파인튜닝 플랫폼 제공
- 대규모 데이터 웨어하우스 ETL 오케스트레이터 대체
- 벤더 종속적 GUI-only 제품화
- 모든 외부 플러그인의 런타임 문제를 자동 해결

## 7. 핵심 사용자 플로우 (Core Flows)

### Flow A: 초기 도입

1. `cotor init --interactive`로 기본 설정 생성
2. `cotor template --list`로 템플릿 선택
3. `cotor validate`로 실행 전 검증
4. `cotor run --dry-run`으로 의도 확인 후 실제 실행

### Flow B: 장애 대응

1. 실행 실패 감지 (`status`, watch/timeline)
2. 실패 단계 원인 확인 (로그/오류 메타데이터)
3. `cotor resume`로 체크포인트 기준 재개
4. 재발 방지를 위해 `lint`/정책 업데이트

### Flow C: 운영 최적화

1. `cotor stats`로 실행 추세 수집
2. 병목 단계 식별
3. 병렬화/타임아웃/리소스 정책 조정
4. 개선 결과를 릴리즈 노트/리포트에 반영

## 8. 기능 범위 (MVP → Next)

### MVP (현재 기준)

- 선언형 파이프라인(Sequential/Parallel/DAG)
- validate/test/lint 기반 실행 전 품질 게이트
- checkpoint/resume 기반 실패 복구
- timeline/watch/stats/doctor 기반 운영 가시성

### Next

- 파이프라인 수준 SLO 알림
- 실행 이력 기반 추천(타임아웃/병렬도/재시도)
- 정책 템플릿 레지스트리

## 9. 시스템 컨텍스트

```text
[CLI/TUI/Web]
    -> [Application Layer: command handlers]
        -> [Execution Engine]
            -> [Stage Orchestrator: SEQ/PAR/DAG]
                -> [Agent Plugins / External Tools]
        -> [Validation & Policy Layer]
        -> [Checkpoint/State Store]
        -> [Telemetry: timeline, logs, stats]
```

## 10. 아키텍처 결정 (ADR-Style)

1. **선언형 YAML 파이프라인 유지**
   - Why: 코드 배포 없이 파이프라인 변경 가능
   - Trade-off: 복잡한 동적 로직 표현의 한계

2. **엔진 중심 오케스트레이션**
   - Why: CLI/TUI/Web 채널에서 동일 실행 모델 공유
   - Trade-off: 엔진 추상화 비용 증가

3. **체크포인트 우선 복구 모델**
   - Why: 전체 재실행 대비 비용/시간 절감
   - Trade-off: 상태 관리 복잡성 증가

4. **정책 기반 사전 검증**
   - Why: 런타임 장애를 사전에 감축
   - Trade-off: 초기 설정 난이도 증가

## 11. 성공 지표 (Success Metrics)

- 실행 성공률 (Pipeline Success Rate)
- 평균 복구 시간 (MTTR for pipeline failures)
- 체크포인트 재개 활용률
- validate/lint 선제 탐지 건수
- P95 실행 시간 및 단계별 병목 개선율
- 사용자 기준: “실패 시 전면 재실행 필요 비율” 감소

## 12. 로드맵 (Roadmap)

### Phase 1 — 기준선 확립

- 차별화 PRD/아키텍처 문서화
- 문서 인덱스/README discoverability 확보

### Phase 2 — 운영 강화

- 정책 프리셋 및 팀 템플릿 배포
- 모니터링/리포팅 자동화

### Phase 3 — 최적화 자동화

- 실행 데이터 기반 튜닝 가이드
- 실패 패턴 기반 대응 플레이북 자동 생성

## 13. 리스크 및 대응

- **리스크**: 기능 증가로 학습 곡선 상승  
  **대응**: `--short`, quick start, 템플릿 중심 온보딩 강화

- **리스크**: 플러그인 다양성으로 예외 케이스 증가  
  **대응**: 플러그인 계약(타임아웃/에러 표준) 명문화

- **리스크**: 운영 지표 수집 비용 증가  
  **대응**: 기본 경량 수집 + 상세 수집 opt-in

## 14. 오픈 쟁점 (Open Questions)

- 팀 단위 정책 버전 관리(프로젝트별 vs 중앙 관리) 경계는?
- Web UI에서 어디까지 편집/실행 권한을 노출할 것인가?
- 장기적으로 원격 실행 노드(분산 실행)까지 확장할 것인가?

## 15. 문서/의사결정 운영 규칙

- 본 문서는 COT-3 기준 아키텍처/제품 의도 기준선이다.
- 아키텍처 변경 시 `ARCHITECTURE.md`와 함께 동시 갱신한다.
- 릴리즈 단위로 Success Metrics 추이를 `docs/reports/`에 반영한다.
