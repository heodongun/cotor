> Status: Historical recommendation note. Validate current behavior against `README.md` and `docs/INDEX.md` first.

# Cotor 업그레이드 권장사항

## 📋 현재 상태 분석

### 발견된 문제점

1. **파이프라인 실행 모호성**: `cotor run` 명령어 구문이 불명확
2. **상태 모니터링 부족**: 실시간 진행 상황 파악 어려움
3. **플러그인 실행 확인 불가**: ClaudePlugin 로딩 후 실제 실행 여부 미확인
4. **에러 메시지 불명확**: 실패 원인 파악이 어려운 에러 메시지

### 작동하는 부분

✅ `cotor init` - 프로젝트 초기화
✅ `cotor list` - 에이전트 목록 표시
✅ `cotor [pipeline-name]` - 파이프라인 직접 실행
✅ ClaudePlugin 로딩
✅ 내부 HTTP 서버 (port 8080)

---

## 🎯 핵심 업그레이드 방향

### 1. CLI 명령어 개선 (우선순위: 🔴 높음)

#### 현재 문제
```bash
cotor run board-implementation  # ❌ 작동하지 않음
cotor board-implementation      # ✅ 작동하지만 비직관적
```

#### 권장 개선
```bash
# 명확한 명령어 구조
cotor run <pipeline-name> [options]
cotor exec <pipeline-name> [options]

# 옵션 추가
cotor run board-implementation --watch        # 실시간 모니터링
cotor run board-implementation --verbose      # 상세 로그
cotor run board-implementation --dry-run      # 시뮬레이션
```

#### 구현 방향
```kotlin
// MainCommand.kt
@Command(name = "run", description = "Run a pipeline")
class RunCommand : CliktCommand() {
    private val pipelineName by argument(help = "Pipeline name to execute")
    private val watch by option("--watch", "-w", help = "Watch mode").flag()
    private val verbose by option("--verbose", "-v", help = "Verbose output").flag()
    private val dryRun by option("--dry-run", help = "Simulation mode").flag()
    
    override fun run() {
        if (dryRun) {
            simulatePipeline(pipelineName)
        } else {
            executePipeline(pipelineName, watch, verbose)
        }
    }
}
```

---

### 2. 실시간 모니터링 시스템 (우선순위: 🔴 높음)

#### 문제점
- 파이프라인 실행 중 진행 상황 알 수 없음
- 각 스테이지 상태 확인 불가
- 에러 발생 시점 파악 어려움

#### 권장 개선
```bash
# 실시간 프로그레스 표시
🚀 Running: board-implementation (7 stages)

┌─────────────────────────────────────────┐
│ [✅] Stage 1: requirements-analysis      │
│ [🔄] Stage 2: database-design            │
│ [⏳] Stage 3: api-design                 │
│ [⏳] Stage 4: backend-implementation     │
│ [⏳] Stage 5: frontend-implementation    │
│ [⏳] Stage 6: testing                    │
│ [⏳] Stage 7: documentation              │
└─────────────────────────────────────────┘

⏱️  Elapsed: 00:02:34 | ETA: 00:12:00
📊 Progress: 14% (1/7 stages completed)
```

#### 구현 방향
```kotlin
// PipelineMonitor.kt
class PipelineMonitor(private val pipeline: Pipeline) {
    private val stageStates = mutableMapOf<String, StageState>()
    
    fun updateStageState(stageId: String, state: StageState) {
        stageStates[stageId] = state
        renderProgress()
    }
    
    private fun renderProgress() {
        clearScreen()
        println("🚀 Running: ${pipeline.name} (${pipeline.stages.size} stages)")
        println("┌${"─".repeat(45)}┐")
        
        pipeline.stages.forEach { stage ->
            val state = stageStates[stage.id] ?: StageState.PENDING
            val icon = when(state) {
                StageState.COMPLETED -> "✅"
                StageState.RUNNING -> "🔄"
                StageState.FAILED -> "❌"
                StageState.PENDING -> "⏳"
            }
            println("│ [$icon] Stage ${stage.id.padEnd(30)} │")
        }
        
        println("└${"─".repeat(45)}┘")
        printStats()
    }
}

enum class StageState {
    PENDING, RUNNING, COMPLETED, FAILED
}
```

---

### 3. 플러그인 실행 피드백 (우선순위: 🟡 중간)

#### 문제점
- ClaudePlugin 로딩 후 실제 실행 여부 불명확
- 플러그인 에러가 조용히 실패
- 플러그인 출력이 로그에만 기록되고 콘솔에 표시되지 않음

#### 권장 개선
```bash
# 플러그인 실행 피드백
🔌 Loading plugin: ClaudePlugin
✅ Plugin loaded successfully

🚀 Executing stage: requirements-analysis
📤 Input sent to Claude (245 tokens)
⏳ Waiting for Claude response...
📥 Response received (1,823 tokens)
✅ Stage completed in 23.4s
```

---

### 4. 에러 처리 및 복구 (우선순위: 🟡 중간)

#### 문제점
- 에러 메시지가 불명확 ("Failed to load config")
- 실패 시 복구 방법 제시 없음
- 스테이지 실패 시 전체 파이프라인 중단

#### 권장 개선
```bash
# 명확한 에러 메시지
❌ Error: Pipeline configuration not found

📍 Problem:
   cotor.yaml file is missing in the current directory

💡 Solutions:
   1. Run 'cotor init' to create a default configuration
   2. Specify config path: cotor run -c path/to/config.yaml <pipeline>
   3. Check if you're in the correct directory

📖 Documentation: https://docs.cotor.dev/configuration
```

---

## 🚀 구현 우선순위 로드맵

### Phase 1: 기본 사용성 개선 (1-2주)
1. ✅ CLI 명령어 구조 개선 (`cotor run` 명령어 수정)
2. ✅ 실시간 프로그레스 표시
3. ✅ 플러그인 실행 피드백
4. ✅ 에러 메시지 개선

### Phase 2: 안정성 및 디버깅 (2-3주)
1. ✅ 파이프라인 검증 시스템
2. ✅ 상세 로깅 옵션 (`--verbose`)
3. ✅ Dry-run 모드
4. ✅ 스테이지별 타임아웃 관리

### Phase 3: 고급 기능 (3-4주)
1. ✅ 결과 아티팩트 자동 저장
2. ✅ 파이프라인 재개 기능 (실패 지점부터 다시 시작)
3. ✅ 병렬 실행 최적화 (PARALLEL 모드 개선)
4. ✅ 웹 대시보드 (http://localhost:8080에서 진행 상황 확인)

---

## 📊 예상 효과

### 사용자 경험 개선
- ⏱️ 디버깅 시간 **60% 감소**
- 📈 파이프라인 성공률 **40% 증가**
- 🎯 첫 실행 성공률 **80%** 달성

### 개발 생산성
- 🔧 설정 오류 사전 발견으로 개발 시간 단축
- 📝 명확한 에러 메시지로 문제 해결 시간 감소
- 🚀 실시간 피드백으로 반복 주기 단축

---

## 🛠️ 기술 스택 권장사항

### UI/Progress 라이브러리
```kotlin
dependencies {
    implementation("com.github.ajalt.mordant:mordant:2.2.0")  // 터미널 UI
    implementation("me.tongfei:progressbar:0.9.5")            // 프로그레스 바
}
```

### 로깅 개선
```kotlin
dependencies {
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.14")
}
```

### 검증 프레임워크
```kotlin
dependencies {
    implementation("org.valiktor:valiktor-core:0.12.0")
}
```

---

## 🎯 결론

cotor의 핵심 기능은 작동하지만, 사용자 경험과 디버깅 가능성을 크게 개선할 수 있는 여지가 많습니다.

위 권장사항을 단계적으로 구현하면 **프로덕션급 AI 파이프라인 도구**로 발전할 수 있습니다.

### 즉시 시작 가능한 개선
1. CLI 명령어 구조 수정 (가장 큰 사용성 개선)
2. 콘솔 출력에 실시간 스테이지 상태 표시
3. 에러 메시지에 해결 방법 포함

이 세 가지만 구현해도 사용자 경험이 크게 개선될 것입니다.

---

## 📖 참고 자료

- [테스트 스크립트](../test-cotor-pipeline.sh) - 게시판 기능 구현 테스트
- [Claude 통합 가이드](CLAUDE_SETUP.md) - Claude Code 슬래시 커맨드
- [메인 README](../README.md) - 전체 문서
> Status: Recommendation note. This file is historical planning context, not the source of truth for the current product.
