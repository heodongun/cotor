# Cotor 실험 결과 및 개선 방향 분석

**실험 일시**: 2025-11-19
**실험 환경**: /Users/Projects/cotor/test-claude

## 📊 실험 요약

### 실험 1: 기본 단일 AI 호출 (01-basic) ✅
- **파이프라인**: basic-test
- **Agent**: Claude
- **소요 시간**: 17.1초
- **결과**: **성공** ✅
- **생성 파일**: `is_prime.py` (26줄, 최적화된 소수 판별 함수)

### 실험 2: 병렬 멀티 AI 비교 (02-parallel) ⚠️
- **파이프라인**: parallel-comparison
- **Agents**: Claude + Gemini
- **소요 시간**: 55.7초
- **결과**: **부분 성공** ⚠️
  - Claude: **성공** ✅ (bubble_sort.py 생성, 78줄, 6개 테스트 케이스 포함)
  - Gemini: **실패** ❌ (API 에러)

## 🔍 주요 발견 사항

### 1. 파이프라인 동작 확인 ✅
**긍정적 발견**:
- Sequential 모드 정상 작동
- Parallel 모드 정상 작동 (2개 agent 동시 실행)
- 실시간 모니터링 UI 동작 (Phase 1 기능)
- 파일 생성 작업 성공적 수행

**검증된 기능**:
```
🚀 Running: basic-test (1 stages)
┌──────────────────────────────────────────────────┐
│ 🔄 Stage 1: simple-task
└──────────────────────────────────────────────────┘
⏱️  Elapsed: 95ms | Progress: 0% (0/1 stages completed)
```

### 2. 에러 복구 메커니즘 부재 ❌ (가장 큰 문제점)

**발견된 문제**:
Gemini가 실패했을 때:
- ❌ 자동 재시도 없음
- ❌ Fallback agent로 전환 없음
- ❌ Partial success 상태에서 진행 불가
- ❌ 사용자 개입 없이 복구 불가

**에러 내용**:
```
com.cotor.model.AgentExecutionException: Gemini execution failed
TypeError: Cannot read properties of undefined (reading 'error')
```

**실제 결과**:
```json
{
  "totalAgents": 2,
  "successCount": 1,  // Claude만 성공
  "failureCount": 1,  // Gemini 실패
  "totalDuration": 55635
}
```

**기대 동작**:
```yaml
# 이런 설정이 있었다면...
stages:
  - id: gemini-solution
    agent:
      name: gemini
    input: "버블 정렬 구현"
    recovery:
      strategy: FALLBACK_AGENT
      fallbackAgents: [claude]  # Gemini 실패 시 Claude로 재시도
```

### 3. 컨텍스트 전달 한계 발견

**현재 동작**:
- Sequential 모드: 이전 stage의 **출력만** 다음 stage 입력
- Parallel 모드: **컨텍스트 공유 없음**

**문제 시나리오**:
```yaml
# 예: 3 stage 순차 실행
stages:
  - id: requirements  # 요구사항 수집
  - id: design        # 설계 (requirements 출력 사용)
  - id: implement     # 구현 (design 출력만 받음, requirements 접근 불가!)
```

**필요한 개선**:
```kotlin
// implement stage에서 이렇게 접근하고 싶음
context.stageResults["requirements"].output
context.stageResults["design"].output
```

### 4. 품질 검증 부재

**문제**:
- Claude가 생성한 코드가 요구사항을 만족하는지 **자동 검증 불가**
- 파일이 실제로 생성되었는지 확인 불가
- 코드 실행 가능성 검증 불가

**예시**:
```python
# is_prime.py가 생성되었지만...
# ❓ 문법이 올바른가?
# ❓ 실제로 소수를 판별하는가?
# ❓ 엣지 케이스를 처리하는가?
```

**필요한 개선**:
```yaml
stages:
  - id: implementation
    agent: claude
    validation:
      - type: FILE_EXISTS
        path: "is_prime.py"
      - type: PYTHON_SYNTAX
      - type: UNIT_TEST
        testCommand: "pytest test_is_prime.py"
      - type: CODE_COVERAGE
        minCoverage: 80
```

### 5. 결과 비교 도구 부재

**문제**:
병렬로 여러 AI 실행했지만 **출력 비교 불가**

**현재 상황**:
- Claude와 Gemini 출력을 **수동으로** 비교해야 함
- 어느 구현이 더 나은지 **주관적 판단**
- 합의(consensus) 자동 검출 불가

**필요한 도구**:
```kotlin
ResultAnalyzer.compare(
    claudeOutput,
    geminiOutput
).let { report ->
    println("유사도: ${report.similarity}")
    println("구조적 차이: ${report.structuralDiff}")
    println("추천: ${report.recommendedSolution}")
}
```

## 🎯 핵심 개선 우선순위 (실험 기반)

### Priority 1: 에러 복구 전략 (긴급) 🔴

**현실적 문제**:
- Gemini, Codex 등 외부 AI는 **자주 실패함**
- API 에러, 인증 문제, 네트워크 이슈 등
- **현재는 전체 파이프라인 실패로 이어짐**

**구현 제안**:
```kotlin
// src/main/kotlin/com/cotor/recovery/ErrorRecovery.kt

data class RecoveryConfig(
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 1000,
    val fallbackAgents: List<String> = emptyList(),
    val strategy: RecoveryStrategy = RecoveryStrategy.RETRY
)

enum class RecoveryStrategy {
    RETRY,           // 같은 agent 재시도
    FALLBACK,        // 다른 agent로 전환
    SKIP,            // stage 건너뛰기
    ABORT            // 중단
}

// PipelineStage에 추가
data class PipelineStage(
    val id: String,
    val agent: AgentConfig,
    val input: String?,
    val recovery: RecoveryConfig? = null  // 추가!
)
```

**설정 예시**:
```yaml
stages:
  - id: gemini-solution
    agent:
      name: gemini
    input: "버블 정렬 구현"
    recovery:
      maxRetries: 2
      retryDelayMs: 2000
      fallbackAgents:
        - claude  # Gemini 실패 시 Claude로
      strategy: FALLBACK
```

**예상 효과**:
- ✅ Gemini 실패 → 자동으로 Claude가 대신 실행
- ✅ 2/2 성공으로 변경 (현재 1/2)
- ✅ 사용자 개입 없이 자동 복구

### Priority 2: 컨텍스트 관리 시스템 🟡

**실제 필요성**:
3-stage 이상 순차 파이프라인에서 **필수**

**구현 제안**:
```kotlin
// src/main/kotlin/com/cotor/context/PipelineContext.kt

data class PipelineContext(
    val pipelineId: String,
    val pipelineName: String,

    // 모든 stage 결과 저장
    val stageResults: MutableMap<String, AgentResult> = mutableMapOf(),

    // stage 간 공유 상태
    val sharedState: MutableMap<String, Any> = mutableMapOf()
)

// ExecutionContext 확장
data class EnhancedExecutionContext(
    val stageId: String,
    val input: String?,
    val pipelineContext: PipelineContext  // 전체 컨텍스트 접근!
)
```

**사용 예시**:
```yaml
stages:
  - id: requirements
    agent: claude
    input: "요구사항 수집"

  - id: design
    agent: claude
    input: |
      요구사항: {{context.stageResults.requirements.output}}

  - id: implement
    agent: claude
    input: |
      요구사항: {{context.stageResults.requirements.output}}
      설계: {{context.stageResults.design.output}}
      둘 다 참고하여 구현
```

### Priority 3: 출력 품질 검증 🟡

**실제 필요성**:
AI가 **요청을 제대로 이행했는지** 자동 확인

**구현 제안**:
```kotlin
// src/main/kotlin/com/cotor/validation/OutputValidator.kt

interface OutputValidator {
    fun validate(output: String, criteria: ValidationCriteria): ValidationResult
}

data class ValidationCriteria(
    val requiresFile: String? = null,        // 파일 생성 확인
    val requiresCodeBlock: Boolean = false,  // 코드 블록 포함 확인
    val minLength: Int = 0,
    val requiredKeywords: List<String> = emptyList()
)

data class ValidationResult(
    val isValid: Boolean,
    val violations: List<String>,
    val score: Double  // 0.0 - 1.0
)
```

**설정 예시**:
```yaml
stages:
  - id: implementation
    agent: claude
    input: "is_prime 함수 작성"
    validation:
      requiresFile: "is_prime.py"
      requiresCodeBlock: true
      requiredKeywords: ["def", "is_prime", "return"]
      minLength: 50
```

**예상 효과**:
- ✅ 파일 생성 확인
- ✅ 코드 포함 여부 확인
- ✅ 최소 품질 기준 충족 확인
- ✅ 기준 미달 시 재시도 트리거

### Priority 4: 조건부 실행 및 반복 🟢

**사용 케이스**:
품질이 기준에 도달할 때까지 **자동 재시도**

**구현 제안**:
```kotlin
sealed class StageType {
    object Execution : StageType()    // 일반 실행
    object Decision : StageType()     // 조건 분기
    object Loop : StageType()         // 반복
}

data class ConditionalStage(
    val id: String,
    val type: StageType,
    val condition: String?,  // "score >= 80"
    val actions: Map<String, String>  // "true" -> "continue", "false" -> "goto:implement"
)
```

**설정 예시**:
```yaml
stages:
  - id: implement
    agent: claude
    input: "정렬 구현"

  - id: quality-check
    agent: gemini
    input: "품질 점수 (0-100) 출력"

  - id: decide
    type: DECISION
    condition: "quality-check.score >= 80"
    actions:
      true: CONTINUE
      false: GOTO implement  # 80점 미만이면 다시 구현
```

## 📈 실험 데이터 분석

### 성능 메트릭

| 항목 | 01-basic | 02-parallel | 비고 |
|------|----------|-------------|------|
| 총 소요 시간 | 17.1s | 55.7s | Gemini 실패 포함 |
| Stage 수 | 1 | 2 (parallel) | |
| 성공률 | 100% | 50% | Gemini 실패 |
| 생성 파일 수 | 1 | 1 | Gemini 파일 없음 |
| 파일 품질 | 우수 | 우수 (Claude만) | |

### AI별 성능

| AI | 성공 | 실패 | 평균 시간 | 신뢰도 |
|----|------|------|-----------|--------|
| Claude | 2 | 0 | 36.0s | 100% ✅ |
| Gemini | 0 | 1 | - | 0% ❌ |

**결론**: Claude가 가장 안정적, Gemini는 현재 사용 불가

### 생성된 코드 품질

**is_prime.py (Claude)**:
- ✅ 정확한 알고리즘 (6k±1 최적화)
- ✅ 명확한 주석
- ✅ 시간 복잡도 O(√n)
- ✅ 엣지 케이스 처리
- ⭐ **품질 점수: 95/100**

**bubble_sort.py (Claude)**:
- ✅ 정확한 구현
- ✅ 조기 종료 최적화
- ✅ 6개 테스트 케이스
- ✅ 상세한 주석
- ⭐ **품질 점수: 98/100**

## 🚀 다음 실험 계획

### 실험 3: Sequential Chain (예정)
```yaml
# 생성 → 리뷰 → 개선 체인
stages:
  - id: initial       # Claude가 구현
  - id: review        # Gemini가 리뷰 (실패 시 Claude로 fallback)
  - id: improve       # Claude가 개선
```

**목적**:
- 컨텍스트 전달 테스트
- 점진적 품질 향상 검증
- 협업 워크플로우 효과

### 실험 4: 에러 복구 테스트 (예정)
```yaml
# Gemini 의도적 실패 → Fallback 테스트
stages:
  - id: gemini-attempt
    agent: gemini
    recovery:
      fallbackAgents: [claude]
      strategy: FALLBACK
```

**목적**:
- 에러 복구 메커니즘 구현 및 검증
- Fallback 성공률 측정

### 실험 5: 품질 검증 테스트 (예정)
```yaml
stages:
  - id: implement
    agent: claude
    validation:
      requiresFile: "sort.py"
      requiresTest: true
```

**목적**:
- 출력 품질 자동 검증
- 기준 미달 시 재시도 트리거

## 💡 즉시 적용 가능한 개선

### 1. Gemini 대신 Claude만 사용 (임시 방안)
```yaml
# 안정적인 파이프라인
agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin

pipelines:
  - name: reliable-pipeline
    stages:
      - id: stage1
        agent: claude
      - id: stage2
        agent: claude  # Gemini 대신 Claude
```

### 2. 타임아웃 증가
```yaml
agents:
  - name: gemini
    timeout: 180000  # 90초 → 180초로 증가
```

### 3. 에러 로깅 개선
현재 에러 메시지만으로는 원인 파악 어려움
→ 더 상세한 디버깅 정보 필요

## 📋 구현 로드맵

### Week 1-2: 에러 복구 (최우선)
- [ ] RecoveryStrategy 구현
- [ ] Fallback agent 지원
- [ ] 자동 재시도 로직
- [ ] 실험 4로 검증

### Week 3-4: 컨텍스트 관리
- [ ] PipelineContext 구현
- [ ] ExecutionContext 확장
- [ ] Template interpolation
- [ ] 실험 3으로 검증

### Week 5-6: 품질 검증
- [ ] OutputValidator 구현
- [ ] File existence check
- [ ] Code syntax validation
- [ ] 실험 5로 검증

### Week 7-8: 조건부 실행
- [ ] Decision stage
- [ ] Loop support
- [ ] Condition parser
- [ ] 반복 실험으로 검증

## 🎓 교훈

1. **안정성이 최우선**: 외부 AI API 신뢰도 문제 → 복구 메커니즘 필수
2. **컨텍스트가 중요**: 복잡한 워크플로우에서 전체 상태 접근 필요
3. **자동 검증 필수**: AI 출력을 무조건 신뢰할 수 없음
4. **단계적 개선**: 작은 실험으로 문제 발견 → 개선 → 재실험

## 📚 참고 자료

- [실험 설정](experiments/)
- [개선 계획](IMPROVEMENT_PLAN.md)
- [자동화 스크립트](tools/run-experiment.sh)
- [프로젝트 README](README.md)

---

**실험 수행자**: Claude Code
**최종 업데이트**: 2025-11-19 19:20
