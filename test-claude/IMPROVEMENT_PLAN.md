# Cotor 개선 방안 종합 문서

## 🎯 목표
test-claude 폴더에서 실험을 통해 cotor의 실용성과 확장성을 개선

## 📊 현재 상태 분석

### 강점
- ✅ 코루틴 기반 비동기 실행 (고성능)
- ✅ 플러그인 아키텍처 (확장 가능)
- ✅ 3가지 실행 모드 (Sequential, Parallel, DAG)
- ✅ 보안 검증 시스템
- ✅ 실시간 모니터링 (Phase 1 구현 완료)

### 약점
- ❌ AI 출력 품질 검증 부재
- ❌ 실패 시 복구 전략 미흡
- ❌ 파이프라인 간 컨텍스트 공유 불가
- ❌ 조건부 실행 및 반복 로직 없음
- ❌ 결과 비교/분석 도구 부족

## 🔧 핵심 개선 방향

### 1. 컨텍스트 관리 시스템 (우선순위: 높음)

**문제**: 현재는 Sequential 모드에서 이전 stage 출력만 다음 stage로 전달

**해결책**:
```kotlin
// src/main/kotlin/com/cotor/context/PipelineContext.kt
data class PipelineContext(
    val pipelineId: String,
    val pipelineName: String,
    val startTime: Long,
    val currentStage: Int,
    val totalStages: Int,

    // 모든 이전 결과 접근 가능
    val stageResults: MutableMap<String, AgentResult>,

    // stage 간 공유 상태
    val sharedState: MutableMap<String, Any>,

    // 메타데이터
    val metadata: Map<String, Any>
)

// ExecutionContext에 추가
data class EnhancedExecutionContext(
    val stageId: String,
    val input: String?,
    val environment: Map<String, String>,
    val timeout: Long,
    val pipelineContext: PipelineContext  // 전체 컨텍스트
)
```

**장점**:
- 모든 stage가 전체 파이프라인 상태 접근 가능
- stage 간 데이터 공유 용이
- 복잡한 의존성 처리 가능

### 2. 조건부 실행 및 반복 시스템 (우선순위: 높음)

**문제**: 파이프라인이 한번 시작하면 무조건 끝까지 실행

**해결책**:
```yaml
# 예: 품질이 기준 미달이면 재시도
pipelines:
  - name: iterative-improvement
    executionMode: CONDITIONAL
    maxIterations: 5

    stages:
      - id: implementation
        agent:
          name: claude
        input: "기능 구현"

      - id: quality-check
        agent:
          name: gemini
        input: "품질 검사하고 점수(0-100) 출력"

      - id: decide-retry
        type: DECISION
        condition:
          expression: "quality-check.score < 80"
          onTrue:
            action: GOTO
            target: implementation
            message: "품질 미달. 재구현 필요"
          onFalse:
            action: CONTINUE
            message: "품질 통과"
```

**구현**:
```kotlin
// src/main/kotlin/com/cotor/model/ConditionalStage.kt
data class ConditionalStage(
    val id: String,
    val type: StageType,  // EXECUTION, DECISION, LOOP
    val agent: AgentConfig?,
    val condition: Condition?,
    val actions: Map<String, Action>
)

enum class StageType {
    EXECUTION,  // 일반 실행
    DECISION,   // 조건 분기
    LOOP        // 반복
}

data class Condition(
    val expression: String,  // "stage.score < 80"
    val parser: ConditionParser
)

sealed class Action {
    data class Continue(val message: String) : Action()
    data class Goto(val target: String, val message: String) : Action()
    data class Abort(val reason: String) : Action()
    data class Retry(val maxAttempts: Int) : Action()
}
```

### 3. AI 출력 품질 검증 시스템 (우선순위: 중간)

**문제**: AI 출력이 요구사항을 만족하는지 자동 검증 불가

**해결책**:
```kotlin
// src/main/kotlin/com/cotor/validation/OutputValidator.kt
interface OutputValidator {
    fun validate(output: String, criteria: ValidationCriteria): ValidationResult
}

data class ValidationCriteria(
    val requiresCodeBlock: Boolean = false,
    val minLength: Int = 0,
    val maxLength: Int = Int.MAX_VALUE,
    val requiredKeywords: List<String> = emptyList(),
    val forbiddenKeywords: List<String> = emptyList(),
    val customValidators: List<(String) -> Boolean> = emptyList()
)

data class ValidationResult(
    val isValid: Boolean,
    val score: Double,  // 0.0 - 1.0
    val violations: List<String>,
    val suggestions: List<String>
)
```

**설정 예시**:
```yaml
stages:
  - id: code-generation
    agent:
      name: claude
    input: "Python 함수 작성"
    validation:
      requiresCodeBlock: true
      minLength: 100
      requiredKeywords: ["def", "return"]
      customValidator: "check_python_syntax"
```

### 4. 결과 비교 및 분석 도구 (우선순위: 중간)

**문제**: 여러 AI 출력을 비교/분석하는 도구 없음

**해결책**:
```kotlin
// src/main/kotlin/com/cotor/analysis/ResultAnalyzer.kt
interface ResultAnalyzer {
    // 출력 유사도 계산
    fun calculateSimilarity(output1: String, output2: String): Double

    // 합의(consensus) 검출
    fun detectConsensus(results: List<AgentResult>): ConsensusResult

    // 최적 결과 추천
    fun recommendBest(results: List<AgentResult>, criteria: SelectionCriteria): AgentResult

    // 차이점 분석
    fun analyzeDifferences(results: List<AgentResult>): DifferenceReport
}

data class ConsensusResult(
    val hasConsensus: Boolean,
    val confidence: Double,
    val commonPoints: List<String>,
    val divergentPoints: List<String>
)

data class DifferenceReport(
    val structuralDifferences: List<String>,
    val contentDifferences: List<String>,
    val qualityComparison: Map<String, Double>,
    val recommendations: List<String>
)
```

### 5. 에러 복구 전략 (우선순위: 높음)

**문제**: Agent 실패 시 재시도나 대체 전략 없음

**해결책**:
```kotlin
// src/main/kotlin/com/cotor/recovery/RecoveryStrategy.kt
sealed class RecoveryStrategy {
    data class Retry(
        val maxAttempts: Int = 3,
        val delayMs: Long = 1000,
        val backoffMultiplier: Double = 2.0
    ) : RecoveryStrategy()

    data class FallbackAgent(
        val agents: List<String>,
        val strategy: FallbackSelectionStrategy
    ) : RecoveryStrategy()

    data class Skip(
        val continueOnSkip: Boolean = true
    ) : RecoveryStrategy()

    object Abort : RecoveryStrategy()

    data class ManualIntervention(
        val notificationChannel: String
    ) : RecoveryStrategy()
}

enum class FallbackSelectionStrategy {
    SEQUENTIAL,  // 순서대로 시도
    BEST_MATCH,  // 가장 적합한 agent 선택
    FASTEST      // 가장 빠른 agent 선택
}
```

**설정 예시**:
```yaml
stages:
  - id: code-review
    agent:
      name: codex  # 대화형이라 실패 가능
    input: "코드 리뷰"
    recovery:
      strategy: FALLBACK_AGENT
      fallbackAgents:
        - claude
        - gemini
      selectionStrategy: BEST_MATCH
```

### 6. 파이프라인 템플릿 라이브러리 (우선순위: 낮음)

**문제**: 자주 사용하는 패턴을 매번 작성해야 함

**해결책**:
```kotlin
// src/main/kotlin/com/cotor/templates/PipelineTemplates.kt
object PipelineTemplates {
    // 여러 AI로 같은 작업 실행 후 비교
    fun multiAgentComparison(
        task: String,
        agents: List<String>,
        compareStrategy: CompareStrategy = CompareStrategy.CONSENSUS
    ): Pipeline

    // 생성 → 리뷰 → 개선 체인
    fun reviewChain(
        task: String,
        generator: String,
        reviewers: List<String>,
        maxIterations: Int = 3
    ): Pipeline

    // 투표 기반 합의
    fun votingConsensus(
        task: String,
        agents: List<String>,
        minVotes: Int
    ): Pipeline

    // 반복적 개선
    fun iterativeImprovement(
        task: String,
        agent: String,
        qualityThreshold: Double,
        maxIterations: Int
    ): Pipeline
}
```

**사용 예시**:
```kotlin
// CLI에서 사용
cotor template multi-agent-comparison \
    --task "Python 정렬 알고리즘 구현" \
    --agents claude,gemini,codex \
    --output my-pipeline.yaml
```

## 📋 우선순위별 구현 순서

### Phase 1: 핵심 기능 강화 (1-2주)
1. ✅ **컨텍스트 관리 시스템**
   - PipelineContext 구현
   - ExecutionContext 확장
   - Stage 간 데이터 공유

2. ✅ **에러 복구 전략**
   - RecoveryStrategy 구현
   - Fallback agent 지원
   - 자동 재시도 로직

3. ✅ **조건부 실행**
   - Condition parser
   - Decision stage
   - GOTO/CONTINUE/ABORT actions

### Phase 2: 품질 및 분석 (2-3주)
4. **출력 품질 검증**
   - OutputValidator 구현
   - 다양한 validation criteria
   - Custom validator 지원

5. **결과 비교 도구**
   - ResultAnalyzer 구현
   - 유사도 계산
   - 합의 검출

### Phase 3: 사용성 개선 (1-2주)
6. **템플릿 라이브러리**
   - 재사용 가능한 패턴 구현
   - CLI 템플릿 명령어
   - 템플릿 문서화

## 🧪 실험 계획

### 실험 1: 컨텍스트 공유 테스트
**목표**: 여러 stage가 전체 파이프라인 상태에 접근

```yaml
# experiments/01-context-sharing/config.yaml
stages:
  - id: gather-requirements
    agent: claude
    input: "요구사항 수집"

  - id: design
    agent: gemini
    input: |
      이전 요구사항을 참고하여 설계:
      {{context.stageResults.gather-requirements.output}}

  - id: implement
    agent: claude
    input: |
      요구사항과 설계 모두 참고하여 구현:
      요구사항: {{context.stageResults.gather-requirements.output}}
      설계: {{context.stageResults.design.output}}
```

### 실험 2: 품질 기반 반복
**목표**: 품질이 기준에 도달할 때까지 재시도

```yaml
# experiments/02-quality-iteration/config.yaml
stages:
  - id: implement
    agent: claude
    input: "함수 구현"

  - id: test
    agent: gemini
    input: "테스트하고 통과율(%) 출력"

  - id: check-quality
    type: DECISION
    condition: "test.passRate >= 80"
    onTrue: CONTINUE
    onFalse: GOTO implement
```

### 실험 3: 멀티 에이전트 합의
**목표**: 3개 AI가 합의에 도달

```yaml
# experiments/03-consensus/config.yaml
stages:
  - id: parallel-solve
    type: PARALLEL
    agents: [claude, gemini, codex]
    input: "알고리즘 구현"

  - id: analyze-consensus
    agent: claude
    input: |
      다음 3가지 구현을 분석하고 최선의 접근법 선택:
      {{context.stageResults.*.output}}
```

## 📈 성공 지표

### 정량적 지표
- **복구율**: 실패한 stage 중 자동 복구 성공률 > 70%
- **품질 개선**: 반복 실행으로 출력 품질 향상 > 30%
- **개발 속도**: 템플릿 사용 시 설정 시간 단축 > 50%

### 정성적 지표
- 복잡한 워크플로우 표현 가능
- 사용자 개입 없이 자동 복구
- 일관된 고품질 출력 생성

## 🚀 실행 방법

### 1. test-claude 환경 구축
```bash
cd /Users/Projects/cotor
mkdir -p test-claude/{experiments,scenarios,results,templates,tools}
chmod +x test-claude/tools/run-experiment.sh
```

### 2. 실험 실행
```bash
cd test-claude/tools
./run-experiment.sh 01-basic
```

### 3. 결과 분석
```bash
cat results/01-basic_*/REPORT.md
```

## 📚 참고 자료
- [현재 구현 상태](../../IMPLEMENTATION_SUMMARY.md)
- [업그레이드 권장사항](../../docs/UPGRADE_RECOMMENDATIONS.md)
- [빠른 시작 가이드](../../docs/QUICK_START.md)
