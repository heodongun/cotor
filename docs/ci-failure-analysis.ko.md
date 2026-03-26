> 상태: 과거 장애 분석 문서입니다. 현재 제품 동작의 소스 오브 트루스가 아닙니다.

# CI 실패 분석 (2026-03-04)

원문: [ci-failure-analysis.md](ci-failure-analysis.md)

## 범위

- 저장소: `heodonugn/cotor`
- 워크플로: `.github/workflows/ci.yml`

## 왜 `3/3 failed`가 발생했는가

1. **`formatCheck`가 Spotless 위반으로 즉시 실패**
   - CI는 `gradle test` 전에 `gradle formatCheck`를 먼저 실행합니다.
   - `spotlessKotlinCheck`에서 포맷 위반이 발생해 테스트까지 도달하지 못했습니다.

2. **`TemplateEngineTest`에 결정적 환경 변수 불일치가 있었음**
   - 테스트는 `USER` 환경 변수가 없을 때 fallback 문자열로 `unknown`을 기대했습니다.
   - 당시 `TemplateEngine`은 누락된 env var에 대해 오류 마커 문자열을 반환했습니다.
   - CI처럼 `USER`가 비어 있는 환경에서는 이 차이로 테스트가 실패했습니다.

3. **같은 원인이 각 CI 실행마다 반복**
   - 포맷 위반과 env fallback 불일치는 프로젝트 상태에 고정된 문제라 소스가 고쳐질 때까지 매번 실패했습니다.

## 적용한 변경

- `src/main/kotlin/com/cotor/presentation/web/stream/RealtimeEvents.kt`를 `RealtimeEvent.kt`로 이름 변경해 Kotlin 파일명 규칙을 맞춤
- `TemplateEngine.handleEnvScope()`를 수정해 interpolate 모드에서는 누락 env var에 `"unknown"`을 반환하고, validation 모드의 오류는 유지

## 로컬 검증 요약

- `gradle test`: env fallback 수정 후 통과
- `gradle formatCheck`: 저장소 전반의 기존 Spotless 위반 때문에 여전히 실패

> 상태: 과거 장애 분석 노트입니다. 현재 동작의 기준 문서로 읽지 마십시오.
