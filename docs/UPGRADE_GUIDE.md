# Cotor Upgrade Guide

## What's New in Cotor 1.0

### 🎯 Major Improvements

#### 1. Enhanced CLI Commands

**Before:**
```bash
cotor board-implementation  # Confusing, inconsistent
```

**After:**
```bash
cotor run board-implementation              # Clear and intuitive
cotor run board-implementation --verbose    # Detailed logging
cotor run board-implementation --dry-run    # Simulation mode
cotor run board-implementation --watch      # Real-time monitoring
```

#### 2. Real-Time Progress Monitoring

The new pipeline monitor shows live execution progress:

```
🚀 Running: board-implementation (5 stages)
┌──────────────────────────────────────────────┐
│ ✅ Stage 1: requirements-analysis      2.3s  │
│ 🔄 Stage 2: backend-implementation           │
│ ⏳ Stage 3: code-review                      │
│ ⏳ Stage 4: testing                          │
│ ⏳ Stage 5: documentation                    │
└──────────────────────────────────────────────┘
⏱️  Elapsed: 00:02:34 | Progress: 20% (1/5 stages completed)
```

#### 3. Pipeline Validation

Validate your pipeline before running:

```bash
cotor validate board-implementation

# Output:
✅ Pipeline structure: valid
✅ All agents defined: valid
✅ Stage dependencies: valid
⚠️  Warning: Stage 'backend-implementation' has no timeout specified
```

#### 4. User-Friendly Error Messages

**Before:**
```
Error: Failed to load config
```

**After:**
```
❌ Error: Pipeline configuration not found

📍 Problem:
   cotor.yaml file is missing in the current directory

💡 Solutions:
   1. Run 'cotor init' to create a default configuration
   2. Specify config path: cotor run -c path/to/config.yaml <pipeline>
   3. Check if you're in the correct directory

📖 Documentation: https://docs.cotor.dev/configuration
```

#### 5. Dry-Run Mode

Test your pipeline without actually executing it:

```bash
cotor run board-implementation --dry-run

# Output:
📋 Pipeline Estimate: board-implementation
   Execution Mode: SEQUENTIAL

Stages:
  ├─ requirements-analysis (claude)
  │  └─ ~30s
  ├─ backend-implementation (claude)
  │  └─ ~45s
  ├─ code-review (gemini)
  │  └─ ~30s
  ├─ testing (gemini)
  │  └─ ~40s
  ├─ documentation (claude)
  │  └─ ~25s

⏱️  Total Estimated Duration: ~2m 50s
```

### 🛠️ Technical Improvements

#### New Dependencies

Added for better terminal UI and progress tracking:

```kotlin
implementation("com.github.ajalt.mordant:mordant:2.2.0")  // Terminal UI
implementation("me.tongfei:progressbar:0.9.5")            // Progress bar
implementation("io.github.microutils:kotlin-logging-jvm:3.0.5") // Logging
```

#### New Components

1. **PipelineMonitor** (`com.cotor.monitoring.PipelineMonitor`)
   - Real-time stage tracking
   - Progress visualization
   - Duration calculation
   - Colored terminal output

2. **UserFriendlyError** (`com.cotor.error.UserFriendlyError`)
   - Structured error messages
   - Solution suggestions
   - Documentation links

3. **PipelineValidator** (`com.cotor.validation.PipelineValidator`)
   - Configuration validation
   - Dependency checking
   - Duration estimation
   - Critical path analysis

4. **Enhanced Commands** (`com.cotor.presentation.cli.EnhancedCommands`)
   - `EnhancedRunCommand` - Improved pipeline execution
   - `ValidateCommand` - Pipeline validation
   - `TestCommand` - Testing framework

### 🔁 Conditional & Loop Pipelines

- **Stage types** – `PipelineStage.type` defaults to `EXECUTION`, but can now be `DECISION` or `LOOP`.
- **Decision stages** – `condition.expression` uses the new evaluator to read previous stage metadata/output (`review.validationScore >= 0.9`) and pick an action (`CONTINUE`, `GOTO`, `ABORT`). `sharedState` assignments let you pass values to later prompts.
- **Loop stages** – `loop` config targets any stage, enforces `maxIterations`, and optionally breaks once `untilExpression` becomes true.

```yaml
  - id: review
    agent:
      name: gemini

  - id: gate
    type: DECISION
    condition:
      expression: "review.validationScore >= 0.85"
      onTrue:
        action: CONTINUE
      onFalse:
        action: GOTO
        targetStageId: improve

  - id: loop-controller
    type: LOOP
    loop:
      targetStageId: review
      maxIterations: 3
      untilExpression: "improve.validationScore >= 0.92"
```

> ℹ️ Conditional stages are supported in `SEQUENTIAL` pipelines. Parallel/DAG pipelines continue to run pure execution stages.

### 🧠 Result Intelligence

- **ResultAnalyzer** – new component (`com.cotor.analysis.ResultAnalyzer`) compares agent outputs and metadata.
- **Run Summary Enhancements** – CLI now prints a consensus badge, confidence percentage, and highlights the best agent.
- **Diagnostics** – verbose mode reveals which agents disagree and provides remediation tips (e.g., rerun strongest agent).

Example summary:

```
📦 Run Summary
   Pipeline : benchmark
   Agents   : 4/4 succeeded
   Duration : 11.4s
   Consensus: ⚠️ Divergent (48%)
   Best     : gemini - Added pagination defaults plus schema notes.
```

#### Event System Updates

New events for stage-level monitoring:

```kotlin
data class StageStartedEvent(val stageId: String, val pipelineId: String)
data class StageCompletedEvent(val stageId: String, val pipelineId: String, val result: AgentResult)
data class StageFailedEvent(val stageId: String, val pipelineId: String, val error: Throwable)
```

### 🚀 Migration Guide

#### Step 1: Update Your Scripts

Replace old command patterns:

```bash
# Old
./cotor board-implementation

# New
./cotor run board-implementation
```

#### Step 2: Add Validation

Add validation step to your CI/CD:

```bash
# Validate before running
./cotor validate my-pipeline
./cotor run my-pipeline
```

#### Step 3: Use Verbose Mode for Debugging

When debugging issues:

```bash
./cotor run my-pipeline --verbose
```

#### Step 4: Test with Dry-Run

Before running expensive pipelines:

```bash
./cotor run my-pipeline --dry-run
```

### 📊 Performance Improvements

- **Parallel Event Emission**: Events are now emitted asynchronously
- **Efficient Progress Rendering**: Terminal updates only when state changes
- **Smart Caching**: Agent registry caches loaded agents
- **Memory Management**: Proper cleanup after pipeline execution

### 🔒 Security Enhancements

- **Input Validation**: All pipeline configurations are validated
- **Timeout Enforcement**: Stages respect configured timeouts
- **Error Isolation**: Stage failures don't crash the entire system
- **Safe Execution**: Dry-run mode for testing without side effects

### 📖 New Documentation

- **UPGRADE_GUIDE.md** (this file) - Upgrade instructions
- **CLI_REFERENCE.md** - Complete CLI command reference
- **VALIDATION_GUIDE.md** - Pipeline validation guide
- **ERROR_HANDLING.md** - Error handling best practices

### 🧪 Testing

New test suite for validation:

```bash
./test-cotor-enhanced.sh

# Test specific features
./cotor test --test-dir test/board-feature
```

### 🎯 Roadmap

#### Phase 2 (Coming Soon)
- Result artifact management
- Pipeline resume from failure point
- Web dashboard enhancements
- Performance profiling

#### Phase 3 (Future)
- Parallel execution optimization
- Distributed pipeline execution
- Advanced caching strategies
- Plugin marketplace

### 📞 Support

- **Issues**: https://github.com/yourorg/cotor/issues
- **Documentation**: https://docs.cotor.dev
- **Community**: https://discord.gg/cotor

### 🙏 Acknowledgments

This upgrade incorporates feedback from the community and implements industry best practices from tools like:

- **Gradle** - Progress visualization
- **Terraform** - Plan/Apply separation
- **Kubernetes** - Error messaging
