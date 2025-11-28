# Cotor 개선점 상세 가이드

## 목차
1. [에러 복구 전략](#1-에러-복구-전략-최우선)
2. [컨텍스트 관리 시스템](#2-컨텍스트-관리-시스템)
3. [출력 품질 검증](#3-출력-품질-검증)
4. [조건부 실행 및 반복](#4-조건부-실행-및-반복)
5. [결과 비교 및 분석](#5-결과-비교-및-분석)
6. [성능 최적화](#6-성능-최적화)

---

# 1. 에러 복구 전략 (최우선)

## 1.1 현재 문제점

### 문제 상황
```kotlin
// 현재 PipelineOrchestrator.kt
private suspend fun executeSequential(pipeline: Pipeline): AggregatedResult {
    val results = mutableListOf<AgentResult>()

    for (stage in pipeline.stages) {
        val agentConfig = agentRegistry.getAgent(stage.agent.name)
            ?: throw IllegalArgumentException("Agent not found")

        val result = agentExecutor.executeAgent(agentConfig, input)
        results.add(result)

        // ❌ 문제: 실패하면 그대로 예외 발생, 복구 시도 없음
        if (!result.isSuccess && stage.failureStrategy == FailureStrategy.ABORT) {
            break
        }
    }

    return resultAggregator.aggregate(results)
}
```

### 실제 발생한 문제
```
실험 2 (병렬 실행):
- Claude: ✅ 성공 (bubble_sort.py 생성)
- Gemini: ❌ API 에러로 실패
- 결과: 전체 파이프라인 50% 성공

기대했던 동작:
- Gemini 실패 → Claude로 자동 재시도
- 또는 Gemini 3회 재시도 후 성공
- 결과: 100% 성공
```

## 1.2 해결 방안

### Phase 1: 기본 재시도 메커니즘

#### 1.2.1 RecoveryConfig 데이터 클래스
```kotlin
// src/main/kotlin/com/cotor/recovery/RecoveryConfig.kt

package com.cotor.recovery

/**
 * Stage 실패 시 복구 전략 설정
 */
data class RecoveryConfig(
    // 재시도 횟수
    val maxRetries: Int = 3,

    // 재시도 간 대기 시간 (밀리초)
    val retryDelayMs: Long = 1000,

    // 재시도마다 대기 시간 증가 배수 (exponential backoff)
    val backoffMultiplier: Double = 2.0,

    // 대체 agent 목록
    val fallbackAgents: List<String> = emptyList(),

    // 복구 전략
    val strategy: RecoveryStrategy = RecoveryStrategy.RETRY,

    // 특정 에러만 재시도
    val retryableErrors: List<String> = listOf(
        "timeout",
        "connection",
        "api",
        "rate_limit"
    )
)

/**
 * 복구 전략 타입
 */
enum class RecoveryStrategy {
    /**
     * 같은 agent로 재시도
     * 예: Gemini 실패 → Gemini 재시도 (최대 maxRetries회)
     */
    RETRY,

    /**
     * 다른 agent로 전환
     * 예: Gemini 실패 → Claude로 시도
     */
    FALLBACK,

    /**
     * RETRY 후 실패하면 FALLBACK 시도
     * 예: Gemini 3회 재시도 → 여전히 실패 → Claude로 전환
     */
    RETRY_THEN_FALLBACK,

    /**
     * Stage 건너뛰기 (선택적 stage인 경우)
     * 예: 선택적 최적화 stage 실패 → 건너뛰고 계속
     */
    SKIP,

    /**
     * 파이프라인 중단
     * 예: 필수 stage 실패 → 전체 중단
     */
    ABORT
}
```

#### 1.2.2 PipelineStage 확장
```kotlin
// src/main/kotlin/com/cotor/model/Models.kt (기존 파일 수정)

data class PipelineStage(
    val id: String,
    val agent: AgentConfig,
    val input: String?,
    val dependencies: List<String> = emptyList(),
    val failureStrategy: FailureStrategy = FailureStrategy.CONTINUE,

    // ✅ 추가: 복구 설정
    val recovery: RecoveryConfig? = null,

    // ✅ 추가: Stage가 필수인지 선택적인지
    val optional: Boolean = false
)
```

#### 1.2.3 복구 로직 구현
```kotlin
// src/main/kotlin/com/cotor/recovery/RecoveryExecutor.kt (신규 파일)

package com.cotor.recovery

import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.*
import kotlinx.coroutines.delay
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Stage 실행 및 복구를 담당하는 Executor
 */
class RecoveryExecutor(
    private val agentExecutor: AgentExecutor,
    private val agentRegistry: AgentRegistry
) {
    private val logger: Logger = LoggerFactory.getLogger(RecoveryExecutor::class.java)

    /**
     * 복구 전략을 적용하여 stage 실행
     */
    suspend fun executeWithRecovery(
        stage: PipelineStage,
        context: ExecutionContext
    ): AgentResult {
        val recovery = stage.recovery ?: RecoveryConfig()

        return when (recovery.strategy) {
            RecoveryStrategy.RETRY -> executeWithRetry(stage, context, recovery)
            RecoveryStrategy.FALLBACK -> executeWithFallback(stage, context, recovery)
            RecoveryStrategy.RETRY_THEN_FALLBACK -> executeRetryThenFallback(stage, context, recovery)
            RecoveryStrategy.SKIP -> executeWithSkip(stage, context, recovery)
            RecoveryStrategy.ABORT -> executeWithAbort(stage, context)
        }
    }

    /**
     * 재시도 전략
     */
    private suspend fun executeWithRetry(
        stage: PipelineStage,
        context: ExecutionContext,
        recovery: RecoveryConfig
    ): AgentResult {
        var lastException: Exception? = null
        var currentDelay = recovery.retryDelayMs

        repeat(recovery.maxRetries) { attempt ->
            try {
                logger.info("Executing stage: ${stage.id} (attempt ${attempt + 1}/${recovery.maxRetries})")

                val agentConfig = agentRegistry.getAgent(stage.agent.name)
                    ?: throw IllegalArgumentException("Agent not found: ${stage.agent.name}")

                val result = agentExecutor.executeAgent(agentConfig, context.input)

                if (result.isSuccess) {
                    if (attempt > 0) {
                        logger.info("✅ Stage ${stage.id} succeeded after ${attempt + 1} attempts")
                    }
                    return result
                }

                // 실패했지만 재시도 가능한 에러인지 확인
                if (!isRetryableError(result.error, recovery.retryableErrors)) {
                    logger.warn("⚠️ Non-retryable error: ${result.error}")
                    return result
                }

                lastException = AgentExecutionException(result.error ?: "Unknown error")

            } catch (e: Exception) {
                lastException = e
                logger.warn("❌ Stage ${stage.id} failed (attempt ${attempt + 1}): ${e.message}")

                if (!isRetryableError(e.message, recovery.retryableErrors)) {
                    throw e
                }
            }

            // 마지막 시도가 아니면 대기
            if (attempt < recovery.maxRetries - 1) {
                logger.info("⏳ Waiting ${currentDelay}ms before retry...")
                delay(currentDelay)
                currentDelay = (currentDelay * recovery.backoffMultiplier).toLong()
            }
        }

        // 모든 재시도 실패
        logger.error("❌ Stage ${stage.id} failed after ${recovery.maxRetries} attempts")
        throw lastException ?: Exception("All retries failed")
    }

    /**
     * Fallback agent 전략
     */
    private suspend fun executeWithFallback(
        stage: PipelineStage,
        context: ExecutionContext,
        recovery: RecoveryConfig
    ): AgentResult {
        // 먼저 원래 agent 시도
        try {
            logger.info("Executing stage: ${stage.id} with agent: ${stage.agent.name}")

            val agentConfig = agentRegistry.getAgent(stage.agent.name)
                ?: throw IllegalArgumentException("Agent not found: ${stage.agent.name}")

            val result = agentExecutor.executeAgent(agentConfig, context.input)

            if (result.isSuccess) {
                return result
            }

            logger.warn("⚠️ Primary agent ${stage.agent.name} failed: ${result.error}")

        } catch (e: Exception) {
            logger.warn("❌ Primary agent ${stage.agent.name} threw exception: ${e.message}")
        }

        // Fallback agents 시도
        for ((index, fallbackName) in recovery.fallbackAgents.withIndex()) {
            try {
                logger.info("🔄 Trying fallback agent ${index + 1}/${recovery.fallbackAgents.size}: $fallbackName")

                val fallbackConfig = agentRegistry.getAgent(fallbackName)
                    ?: continue

                val result = agentExecutor.executeAgent(fallbackConfig, context.input)

                if (result.isSuccess) {
                    logger.info("✅ Fallback agent $fallbackName succeeded for stage ${stage.id}")
                    return result
                }

                logger.warn("⚠️ Fallback agent $fallbackName failed: ${result.error}")

            } catch (e: Exception) {
                logger.warn("❌ Fallback agent $fallbackName threw exception: ${e.message}")
            }
        }

        // 모든 fallback 실패
        throw Exception("All fallback agents failed for stage ${stage.id}")
    }

    /**
     * 재시도 후 Fallback 전략
     */
    private suspend fun executeRetryThenFallback(
        stage: PipelineStage,
        context: ExecutionContext,
        recovery: RecoveryConfig
    ): AgentResult {
        // 먼저 재시도
        try {
            return executeWithRetry(stage, context, recovery)
        } catch (e: Exception) {
            logger.warn("⚠️ All retries failed, trying fallback agents...")

            // 재시도 실패하면 fallback
            return executeWithFallback(stage, context, recovery)
        }
    }

    /**
     * 건너뛰기 전략 (선택적 stage)
     */
    private suspend fun executeWithSkip(
        stage: PipelineStage,
        context: ExecutionContext,
        recovery: RecoveryConfig
    ): AgentResult {
        try {
            val agentConfig = agentRegistry.getAgent(stage.agent.name)
                ?: throw IllegalArgumentException("Agent not found: ${stage.agent.name}")

            return agentExecutor.executeAgent(agentConfig, context.input)

        } catch (e: Exception) {
            if (stage.optional) {
                logger.warn("⏭️ Optional stage ${stage.id} failed, skipping...")
                return AgentResult(
                    agentName = stage.agent.name,
                    isSuccess = false,
                    output = null,
                    error = "Stage skipped: ${e.message}",
                    duration = 0,
                    metadata = mapOf("skipped" to true)
                )
            }
            throw e
        }
    }

    /**
     * 중단 전략
     */
    private suspend fun executeWithAbort(
        stage: PipelineStage,
        context: ExecutionContext
    ): AgentResult {
        val agentConfig = agentRegistry.getAgent(stage.agent.name)
            ?: throw IllegalArgumentException("Agent not found: ${stage.agent.name}")

        return agentExecutor.executeAgent(agentConfig, context.input)
        // 실패하면 예외 발생, 복구 시도 없음
    }

    /**
     * 재시도 가능한 에러인지 확인
     */
    private fun isRetryableError(error: String?, retryableErrors: List<String>): Boolean {
        if (error == null) return false

        val errorLower = error.lowercase()
        return retryableErrors.any { retryable ->
            errorLower.contains(retryable.lowercase())
        }
    }
}
```

#### 1.2.4 PipelineOrchestrator에 통합
```kotlin
// src/main/kotlin/com/cotor/domain/orchestrator/PipelineOrchestrator.kt (수정)

class DefaultPipelineOrchestrator(
    private val agentExecutor: AgentExecutor,
    private val resultAggregator: ResultAggregator,
    private val eventBus: EventBus,
    private val logger: Logger,
    private val agentRegistry: AgentRegistry
) : PipelineOrchestrator {

    // ✅ 추가: RecoveryExecutor
    private val recoveryExecutor = RecoveryExecutor(agentExecutor, agentRegistry)

    private suspend fun executeSequential(pipeline: Pipeline): AggregatedResult {
        val results = mutableListOf<AgentResult>()
        var previousOutput: String? = null
        val pipelineId = UUID.randomUUID().toString()

        for (stage in pipeline.stages) {
            try {
                eventBus.emit(StageStartedEvent(stage.id, pipelineId))

                val input = previousOutput ?: stage.input
                val context = ExecutionContext(
                    input = input,
                    environment = emptyMap(),
                    timeout = stage.agent.timeout ?: 60000L
                )

                // ✅ 변경: RecoveryExecutor 사용
                val result = recoveryExecutor.executeWithRecovery(stage, context)
                results.add(result)

                eventBus.emit(StageCompletedEvent(stage.id, pipelineId, result))

                if (!result.isSuccess && stage.failureStrategy == FailureStrategy.ABORT) {
                    break
                }

                previousOutput = result.output

            } catch (e: Exception) {
                eventBus.emit(StageFailedEvent(stage.id, pipelineId, e))

                // Optional stage는 건너뛰기
                if (stage.optional) {
                    logger.warn("Optional stage ${stage.id} failed, continuing...")
                    continue
                }

                throw e
            }
        }

        return resultAggregator.aggregate(results)
    }
}
```

### 1.3 YAML 설정 예시

#### 예시 1: 간단한 재시도
```yaml
# experiments/04-error/retry-simple.yaml

version: "1.0"

agents:
  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 90000

pipelines:
  - name: retry-test
    description: "Gemini 실패 시 3회 재시도"
    executionMode: SEQUENTIAL
    stages:
      - id: unstable-task
        agent:
          name: gemini
        input: "버블 정렬 구현"

        # ✅ 복구 설정
        recovery:
          maxRetries: 3
          retryDelayMs: 2000
          backoffMultiplier: 2.0
          strategy: RETRY
          retryableErrors:
            - "api"
            - "timeout"
            - "connection"
```

#### 예시 2: Fallback agent
```yaml
# experiments/04-error/fallback-test.yaml

version: "1.0"

agents:
  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 90000
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 90000

pipelines:
  - name: fallback-test
    description: "Gemini 실패 시 Claude로 전환"
    executionMode: SEQUENTIAL
    stages:
      - id: generate-code
        agent:
          name: gemini
        input: "퀵소트 구현"

        # ✅ Fallback 설정
        recovery:
          strategy: FALLBACK
          fallbackAgents:
            - claude  # Gemini 실패 시 Claude 사용
```

#### 예시 3: 재시도 후 Fallback
```yaml
# experiments/04-error/retry-fallback-test.yaml

version: "1.0"

agents:
  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 90000
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 90000

pipelines:
  - name: retry-then-fallback-test
    description: "Gemini 2회 재시도 → 실패하면 Claude로"
    executionMode: SEQUENTIAL
    stages:
      - id: implementation
        agent:
          name: gemini
        input: "머지 정렬 구현"

        # ✅ 재시도 후 Fallback
        recovery:
          strategy: RETRY_THEN_FALLBACK
          maxRetries: 2
          retryDelayMs: 1000
          backoffMultiplier: 2.0
          fallbackAgents:
            - claude
```

#### 예시 4: 선택적 Stage (Skip)
```yaml
pipelines:
  - name: optional-stages-test
    stages:
      - id: essential-task
        agent:
          name: claude
        input: "핵심 기능 구현"
        # 필수 stage, 실패하면 중단

      - id: optimization
        agent:
          name: gemini
        input: "성능 최적화"
        optional: true  # ✅ 선택적 stage
        recovery:
          strategy: SKIP  # 실패해도 계속

      - id: documentation
        agent:
          name: claude
        input: "문서 작성"
        # 필수 stage
```

### 1.4 예상 효과

#### Before (현재)
```
파이프라인 실행
├─ Stage 1: Claude ✅
├─ Stage 2: Gemini ❌ (API 에러)
└─ 결과: 50% 성공, 전체 실패
```

#### After (개선 후)
```
파이프라인 실행
├─ Stage 1: Claude ✅
├─ Stage 2: Gemini 시도 1 ❌
│           Gemini 시도 2 ❌
│           Fallback → Claude ✅
└─ 결과: 100% 성공!
```

### 1.5 테스트 계획

```bash
# 1. 재시도 테스트
cd test-claude
java -jar ../build/libs/cotor-1.0.0-all.jar run retry-test \
  --config experiments/04-error/retry-simple.yaml \
  --verbose

# 2. Fallback 테스트
java -jar ../build/libs/cotor-1.0.0-all.jar run fallback-test \
  --config experiments/04-error/fallback-test.yaml \
  --verbose

# 3. 복합 테스트
java -jar ../build/libs/cotor-1.0.0-all.jar run retry-then-fallback-test \
  --config experiments/04-error/retry-fallback-test.yaml \
  --verbose
```

---

# 2. 컨텍스트 관리 시스템

## 2.1 현재 문제점

### 문제 상황
```kotlin
// 현재: Sequential 모드에서
// Stage 3은 Stage 1의 결과에 접근할 수 없음

private suspend fun executeSequential(pipeline: Pipeline): AggregatedResult {
    var previousOutput: String? = null  // ❌ 오직 직전 출력만!

    for (stage in pipeline.stages) {
        val input = previousOutput ?: stage.input  // ❌ Stage 1 결과 손실
        val result = agentExecutor.executeAgent(agentConfig, input)
        previousOutput = result.output  // 덮어쓰기
    }
}
```

### 실제 문제 시나리오
```yaml
# 예: 복잡한 개발 워크플로우
stages:
  - id: requirements
    input: "게시판 CRUD 요구사항 수집"

  - id: design
    input: "위 요구사항을 바탕으로 설계"
    # ✅ requirements 출력 사용 가능

  - id: implement
    input: "설계를 바탕으로 구현"
    # ✅ design 출력 사용 가능
    # ❌ requirements 출력 접근 불가! (필요한데...)

  - id: test
    input: "구현 테스트"
    # ✅ implement 출력 사용 가능
    # ❌ requirements, design 접근 불가!
    # → 테스트 시 요구사항 확인 못함
```

## 2.2 해결 방안

### Phase 1: PipelineContext 시스템

#### 2.2.1 PipelineContext 데이터 구조
```kotlin
// src/main/kotlin/com/cotor/context/PipelineContext.kt (신규 파일)

package com.cotor.context

import com.cotor.model.AgentResult
import java.util.UUID

/**
 * 파이프라인 전체 컨텍스트
 * 모든 stage가 전체 파이프라인 상태에 접근 가능
 */
data class PipelineContext(
    // 파이프라인 메타정보
    val pipelineId: String = UUID.randomUUID().toString(),
    val pipelineName: String,
    val startTime: Long = System.currentTimeMillis(),

    // 현재 진행 상태
    var currentStageIndex: Int = 0,
    val totalStages: Int,

    // ✅ 핵심: 모든 stage 결과 저장
    val stageResults: MutableMap<String, AgentResult> = mutableMapOf(),

    // ✅ Stage 간 공유 상태
    val sharedState: MutableMap<String, Any> = mutableMapOf(),

    // ✅ 메타데이터 (사용자 정의 데이터)
    val metadata: MutableMap<String, Any> = mutableMapOf()
) {
    /**
     * Stage 결과 추가
     */
    fun addStageResult(stageId: String, result: AgentResult) {
        stageResults[stageId] = result
    }

    /**
     * 특정 stage 결과 조회
     */
    fun getStageResult(stageId: String): AgentResult? {
        return stageResults[stageId]
    }

    /**
     * 특정 stage 출력 조회
     */
    fun getStageOutput(stageId: String): String? {
        return stageResults[stageId]?.output
    }

    /**
     * 모든 stage 출력 결합
     */
    fun getAllOutputs(): String {
        return stageResults.values
            .mapNotNull { it.output }
            .joinToString("\n\n---\n\n")
    }

    /**
     * 성공한 stage 출력만 결합
     */
    fun getSuccessfulOutputs(): String {
        return stageResults.values
            .filter { it.isSuccess }
            .mapNotNull { it.output }
            .joinToString("\n\n---\n\n")
    }

    /**
     * 실행 시간 계산
     */
    fun getElapsedTime(): Long {
        return System.currentTimeMillis() - startTime
    }
}
```

#### 2.2.2 ExecutionContext 확장
```kotlin
// src/main/kotlin/com/cotor/model/Models.kt (수정)

/**
 * 개선된 실행 컨텍스트
 */
data class ExecutionContext(
    // Stage별 입력
    val input: String?,

    // 환경 변수
    val environment: Map<String, String>,

    // 타임아웃
    val timeout: Long,

    // ✅ 추가: 전체 파이프라인 컨텍스트
    val pipelineContext: PipelineContext? = null,

    // ✅ 추가: 현재 stage ID
    val currentStageId: String? = null
)
```

#### 2.2.3 Template Interpolation 엔진
```kotlin
// src/main/kotlin/com/cotor/context/TemplateEngine.kt (신규 파일)

package com.cotor.context

/**
 * 템플릿 문자열에서 컨텍스트 값을 치환
 *
 * 지원 패턴:
 * - {{context.stageResults.stage_id.output}}
 * - {{context.sharedState.key}}
 * - {{context.metadata.key}}
 */
class TemplateEngine {

    companion object {
        private val STAGE_OUTPUT_PATTERN =
            """\\{\\{context\\.stageResults\\.([^.]+)\\.output\\}\\}""".toRegex()

        private val SHARED_STATE_PATTERN =
            """\\{\\{context\\.sharedState\\.([^}]+)\\}\\}""".toRegex()

        private val METADATA_PATTERN =
            """\\{\\{context\\.metadata\\.([^}]+)\\}\\}""".toRegex()

        private val ALL_OUTPUTS_PATTERN =
            """\\{\\{context\\.allOutputs\\}\\}""".toRegex()

        private val SUCCESSFUL_OUTPUTS_PATTERN =
            """\\{\\{context\\.successfulOutputs\\}\\}""".toRegex()
    }

    /**
     * 템플릿 문자열 치환
     */
    fun interpolate(template: String, context: PipelineContext): String {
        var result = template

        // {{context.stageResults.requirements.output}} 치환
        result = STAGE_OUTPUT_PATTERN.replace(result) { matchResult ->
            val stageId = matchResult.groupValues[1]
            context.getStageOutput(stageId) ?: "[Stage $stageId output not found]"
        }

        // {{context.sharedState.key}} 치환
        result = SHARED_STATE_PATTERN.replace(result) { matchResult ->
            val key = matchResult.groupValues[1]
            context.sharedState[key]?.toString() ?: "[Shared state $key not found]"
        }

        // {{context.metadata.key}} 치환
        result = METADATA_PATTERN.replace(result) { matchResult ->
            val key = matchResult.groupValues[1]
            context.metadata[key]?.toString() ?: "[Metadata $key not found]"
        }

        // {{context.allOutputs}} 치환
        result = ALL_OUTPUTS_PATTERN.replace(result) {
            context.getAllOutputs()
        }

        // {{context.successfulOutputs}} 치환
        result = SUCCESSFUL_OUTPUTS_PATTERN.replace(result) {
            context.getSuccessfulOutputs()
        }

        return result
    }
}
```

#### 2.2.4 PipelineOrchestrator 통합
```kotlin
// src/main/kotlin/com/cotor/domain/orchestrator/PipelineOrchestrator.kt (수정)

class DefaultPipelineOrchestrator(
    private val agentExecutor: AgentExecutor,
    private val resultAggregator: ResultAggregator,
    private val eventBus: EventBus,
    private val logger: Logger,
    private val agentRegistry: AgentRegistry
) : PipelineOrchestrator {

    private val recoveryExecutor = RecoveryExecutor(agentExecutor, agentRegistry)

    // ✅ 추가: 템플릿 엔진
    private val templateEngine = TemplateEngine()

    private suspend fun executeSequential(pipeline: Pipeline): AggregatedResult {
        val results = mutableListOf<AgentResult>()
        var previousOutput: String? = null
        val pipelineId = UUID.randomUUID().toString()

        // ✅ 추가: PipelineContext 생성
        val pipelineContext = PipelineContext(
            pipelineId = pipelineId,
            pipelineName = pipeline.name,
            totalStages = pipeline.stages.size
        )

        for ((index, stage) in pipeline.stages.withIndex()) {
            try {
                pipelineContext.currentStageIndex = index
                eventBus.emit(StageStartedEvent(stage.id, pipelineId))

                // ✅ 템플릿 치환: stage.input에서 {{context.xxx}} 패턴 처리
                val interpolatedInput = if (stage.input != null && pipelineContext != null) {
                    templateEngine.interpolate(stage.input, pipelineContext)
                } else {
                    stage.input
                }

                // Sequential 모드: 이전 출력 우선, 없으면 interpolated input 사용
                val input = previousOutput ?: interpolatedInput

                val context = ExecutionContext(
                    input = input,
                    environment = emptyMap(),
                    timeout = stage.agent.timeout ?: 60000L,
                    pipelineContext = pipelineContext,  // ✅ 컨텍스트 전달
                    currentStageId = stage.id
                )

                val result = recoveryExecutor.executeWithRecovery(stage, context)
                results.add(result)

                // ✅ 결과를 컨텍스트에 저장
                pipelineContext.addStageResult(stage.id, result)

                eventBus.emit(StageCompletedEvent(stage.id, pipelineId, result))

                if (!result.isSuccess && stage.failureStrategy == FailureStrategy.ABORT) {
                    break
                }

                previousOutput = result.output

            } catch (e: Exception) {
                eventBus.emit(StageFailedEvent(stage.id, pipelineId, e))

                if (stage.optional) {
                    logger.warn("Optional stage ${stage.id} failed, continuing...")
                    continue
                }

                throw e
            }
        }

        return resultAggregator.aggregate(results)
    }
}
```

### 2.3 YAML 설정 예시

#### 예시 1: 모든 이전 결과 참조
```yaml
# experiments/05-context/multi-stage-context.yaml

version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 90000

pipelines:
  - name: context-test
    description: "Stage 3에서 Stage 1, 2 모두 참조"
    executionMode: SEQUENTIAL
    stages:
      - id: requirements
        agent:
          name: claude
        input: |
          게시판 CRUD 요구사항을 수집해주세요.
          - 게시글 작성, 조회, 수정, 삭제
          - 페이징 지원
          - 검색 기능

      - id: design
        agent:
          name: claude
        input: |
          다음 요구사항을 바탕으로 데이터베이스 스키마와 API를 설계해주세요:

          {{context.stageResults.requirements.output}}

      - id: implement
        agent:
          name: claude
        input: |
          다음 요구사항과 설계를 모두 참고하여 구현해주세요:

          # 요구사항
          {{context.stageResults.requirements.output}}

          # 설계
          {{context.stageResults.design.output}}

          위 내용을 바탕으로 Spring Boot + Kotlin으로 구현해주세요.

      - id: test
        agent:
          name: claude
        input: |
          다음 구현에 대한 테스트 코드를 작성해주세요:

          # 구현 코드
          {{context.stageResults.implement.output}}

          # 원래 요구사항 (테스트 시나리오 작성에 필요)
          {{context.stageResults.requirements.output}}

          JUnit 5와 MockK를 사용해주세요.
```

#### 예시 2: 성공한 결과만 참조
```yaml
pipelines:
  - name: successful-only-test
    stages:
      - id: task1
        agent: claude
        input: "구현 1"

      - id: task2
        agent: gemini
        input: "구현 2"
        optional: true  # 실패 가능

      - id: task3
        agent: claude
        input: "구현 3"

      - id: summary
        agent: claude
        input: |
          다음 성공한 구현들을 통합해주세요:

          {{context.successfulOutputs}}
```

#### 예시 3: Shared State 사용
```yaml
pipelines:
  - name: shared-state-test
    stages:
      - id: analyze
        agent: claude
        input: |
          코드 복잡도를 분석하고 점수를 매겨주세요.
          결과 형식: "복잡도 점수: XX"

      - id: decide
        agent: claude
        input: |
          이전 분석 결과:
          {{context.stageResults.analyze.output}}

          복잡도 점수가 80 이상이면 "리팩토링 필요"
          80 미만이면 "통과"라고 출력해주세요.

      - id: refactor
        agent: claude
        input: |
          복잡도가 높으니 리팩토링해주세요.

          원본 코드:
          {{context.stageResults.analyze.output}}
```

### 2.4 예상 효과

#### Before (현재)
```
Stage 1: requirements ✅
Stage 2: design ✅ (requirements 참조 가능)
Stage 3: implement ✅ (design만 참조 가능, requirements 접근 불가)
Stage 4: test ✅ (implement만 참조, requirements/design 접근 불가)

→ 정보 손실, 불완전한 작업
```

#### After (개선 후)
```
Stage 1: requirements ✅
Stage 2: design ✅ (requirements 참조)
Stage 3: implement ✅ (requirements + design 모두 참조)
Stage 4: test ✅ (requirements + design + implement 모두 참조)

→ 완전한 컨텍스트, 고품질 작업
```

---

# 3. 출력 품질 검증

## 3.1 현재 문제점

AI가 작업을 제대로 수행했는지 자동으로 검증할 방법이 없음:
- ❌ 파일 생성 확인 불가
- ❌ 코드 문법 검사 불가
- ❌ 요구사항 준수 확인 불가
- ❌ 품질 점수 측정 불가

## 3.2 해결 방안

### Phase 1: OutputValidator 시스템

#### 3.2.1 검증 인터페이스
```kotlin
// src/main/kotlin/com/cotor/validation/OutputValidator.kt (신규 파일)

package com.cotor.validation

import com.cotor.model.AgentResult

/**
 * AI 출력 품질 검증 인터페이스
 */
interface OutputValidator {
    /**
     * 출력 검증
     */
    fun validate(output: String, criteria: ValidationCriteria): ValidationResult

    /**
     * Agent 결과 전체 검증
     */
    fun validateResult(result: AgentResult, criteria: ValidationCriteria): ValidationResult {
        if (!result.isSuccess || result.output == null) {
            return ValidationResult(
                isValid = false,
                score = 0.0,
                violations = listOf("Agent execution failed"),
                suggestions = emptyList()
            )
        }
        return validate(result.output, criteria)
    }
}

/**
 * 검증 기준
 */
data class ValidationCriteria(
    // 파일 생성 확인
    val requiresFile: String? = null,

    // 코드 블록 포함 확인
    val requiresCodeBlock: Boolean = false,

    // 최소/최대 길이
    val minLength: Int = 0,
    val maxLength: Int = Int.MAX_VALUE,

    // 필수 키워드
    val requiredKeywords: List<String> = emptyList(),

    // 금지 키워드
    val forbiddenKeywords: List<String> = emptyList(),

    // 커스텀 검증 함수
    val customValidators: List<CustomValidator> = emptyList(),

    // 최소 품질 점수
    val minQualityScore: Double = 0.0
)

/**
 * 검증 결과
 */
data class ValidationResult(
    // 검증 통과 여부
    val isValid: Boolean,

    // 품질 점수 (0.0 - 1.0)
    val score: Double,

    // 위반 사항
    val violations: List<String>,

    // 개선 제안
    val suggestions: List<String>
)

/**
 * 커스텀 검증 함수
 */
interface CustomValidator {
    val name: String
    fun validate(output: String): CustomValidationResult
}

data class CustomValidationResult(
    val isValid: Boolean,
    val message: String?
)
```

#### 3.2.2 기본 검증 구현
```kotlin
// src/main/kotlin/com/cotor/validation/DefaultOutputValidator.kt (신규 파일)

package com.cotor.validation

import org.slf4j.LoggerFactory
import java.io.File

class DefaultOutputValidator : OutputValidator {
    private val logger = LoggerFactory.getLogger(DefaultOutputValidator::class.java)

    override fun validate(output: String, criteria: ValidationCriteria): ValidationResult {
        val violations = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        var score = 1.0

        // 1. 파일 생성 확인
        if (criteria.requiresFile != null) {
            val file = File(criteria.requiresFile)
            if (!file.exists()) {
                violations.add("Required file not found: ${criteria.requiresFile}")
                suggestions.add("Ensure the AI creates the file at the specified path")
                score -= 0.3
            } else {
                logger.info("✅ File exists: ${criteria.requiresFile}")
            }
        }

        // 2. 코드 블록 확인
        if (criteria.requiresCodeBlock) {
            if (!hasCodeBlock(output)) {
                violations.add("Output does not contain code block")
                suggestions.add("Request the AI to include code in markdown code blocks")
                score -= 0.2
            }
        }

        // 3. 길이 확인
        if (output.length < criteria.minLength) {
            violations.add("Output too short: ${output.length} < ${criteria.minLength}")
            suggestions.add("Request more detailed output")
            score -= 0.15
        }

        if (output.length > criteria.maxLength) {
            violations.add("Output too long: ${output.length} > ${criteria.maxLength}")
            suggestions.add("Request more concise output")
            score -= 0.1
        }

        // 4. 필수 키워드 확인
        for (keyword in criteria.requiredKeywords) {
            if (!output.contains(keyword, ignoreCase = true)) {
                violations.add("Missing required keyword: $keyword")
                suggestions.add("Ensure output includes: $keyword")
                score -= 0.1
            }
        }

        // 5. 금지 키워드 확인
        for (keyword in criteria.forbiddenKeywords) {
            if (output.contains(keyword, ignoreCase = true)) {
                violations.add("Contains forbidden keyword: $keyword")
                suggestions.add("Remove or replace: $keyword")
                score -= 0.15
            }
        }

        // 6. 커스텀 검증
        for (validator in criteria.customValidators) {
            val result = validator.validate(output)
            if (!result.isValid) {
                violations.add("${validator.name}: ${result.message}")
                score -= 0.1
            }
        }

        // 점수 범위 제한
        score = score.coerceIn(0.0, 1.0)

        val isValid = violations.isEmpty() && score >= criteria.minQualityScore

        return ValidationResult(
            isValid = isValid,
            score = score,
            violations = violations,
            suggestions = suggestions
        )
    }

    private fun hasCodeBlock(text: String): Boolean {
        return text.contains("```")
    }
}
```

#### 3.2.3 언어별 문법 검증
```kotlin
// src/main/kotlin/com/cotor/validation/SyntaxValidator.kt (신규 파일)

package com.cotor.validation

import java.io.File

/**
 * 언어별 문법 검증
 */
class SyntaxValidator {

    /**
     * Python 문법 검증
     */
    fun validatePython(filePath: String): SyntaxValidationResult {
        return try {
            val process = ProcessBuilder("python", "-m", "py_compile", filePath)
                .redirectErrorStream(true)
                .start()

            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()

            if (exitCode == 0) {
                SyntaxValidationResult(true, "Python syntax valid", emptyList())
            } else {
                SyntaxValidationResult(false, "Python syntax errors", listOf(output))
            }
        } catch (e: Exception) {
            SyntaxValidationResult(false, "Validation failed", listOf(e.message ?: "Unknown error"))
        }
    }

    /**
     * JavaScript/TypeScript 문법 검증
     */
    fun validateJavaScript(filePath: String): SyntaxValidationResult {
        return try {
            // Node.js로 구문 검사
            val process = ProcessBuilder("node", "--check", filePath)
                .redirectErrorStream(true)
                .start()

            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()

            if (exitCode == 0) {
                SyntaxValidationResult(true, "JavaScript syntax valid", emptyList())
            } else {
                SyntaxValidationResult(false, "JavaScript syntax errors", listOf(output))
            }
        } catch (e: Exception) {
            SyntaxValidationResult(false, "Validation failed", listOf(e.message ?: "Unknown error"))
        }
    }

    /**
     * Kotlin 문법 검증 (kotlinc 필요)
     */
    fun validateKotlin(filePath: String): SyntaxValidationResult {
        return try {
            val process = ProcessBuilder("kotlinc", "-script", filePath)
                .redirectErrorStream(true)
                .start()

            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()

            if (exitCode == 0 || output.contains("warning")) {
                // 경고는 허용
                SyntaxValidationResult(true, "Kotlin syntax valid", emptyList())
            } else {
                SyntaxValidationResult(false, "Kotlin syntax errors", listOf(output))
            }
        } catch (e: Exception) {
            SyntaxValidationResult(false, "Validation failed", listOf(e.message ?: "Unknown error"))
        }
    }
}

data class SyntaxValidationResult(
    val isValid: Boolean,
    val message: String,
    val errors: List<String>
)
```

### 3.3 YAML 설정 예시

#### 예시 1: 파일 생성 검증
```yaml
# experiments/06-validation/file-validation.yaml

pipelines:
  - name: file-validation-test
    stages:
      - id: create-function
        agent:
          name: claude
        input: |
          Python 퀵소트 함수를 작성하고
          experiments/06-validation/quick_sort.py 에 저장해주세요.

        # ✅ 검증 설정
        validation:
          requiresFile: "experiments/06-validation/quick_sort.py"
          requiresCodeBlock: true
          requiredKeywords:
            - "def"
            - "quick_sort"
            - "return"
          minLength: 100
          minQualityScore: 0.8
```

#### 예시 2: 코드 품질 검증
```yaml
pipelines:
  - name: code-quality-test
    stages:
      - id: implement
        agent:
          name: claude
        input: "REST API 엔드포인트 구현"

        validation:
          requiresCodeBlock: true
          requiredKeywords:
            - "@RestController"
            - "@GetMapping"
            - "ResponseEntity"
          forbiddenKeywords:
            - "TODO"
            - "FIXME"
            - "hack"
          customValidators:
            - syntaxCheck: "kotlin"
          minQualityScore: 0.85
```

#### 예시 3: 품질 기반 재시도
```yaml
pipelines:
  - name: quality-retry-test
    stages:
      - id: generate
        agent:
          name: claude
        input: "사용자 인증 시스템 구현"

        validation:
          requiresCodeBlock: true
          minQualityScore: 0.9

        recovery:
          strategy: RETRY
          maxRetries: 3
          # 품질 점수 0.9 미만이면 자동 재시도
```

## 3.4 예상 효과

**Before (현재)**:
- AI가 파일 생성 안해도 모름
- 문법 오류 있어도 모름
- 품질 낮아도 수용

**After (개선 후)**:
- 파일 생성 자동 확인 ✅
- 문법 자동 검증 ✅
- 품질 기준 미달 시 재시도 ✅
- 일관된 고품질 출력 ✅

---

계속해서 나머지 개선점들을 작성할까요?
- 4. 조건부 실행 및 반복
- 5. 결과 비교 및 분석
- 6. 성능 최적화

아니면 특정 부분을 더 상세하게 설명해드릴까요?
