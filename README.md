# Cotor - AI CLI Master-Agent System

[![Version](https://img.shields.io/badge/version-1.0.0-blue)](https://github.com/yourusername/cotor)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.0-purple)](https://kotlinlang.org/)
[![JVM](https://img.shields.io/badge/jvm-23-orange)](https://openjdk.org/)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

Cotor is a Kotlin-based AI CLI for orchestrating multi-agent workflows with a single command. Execute complex AI pipelines with sequential, parallel, or DAG execution modes, real-time monitoring, and comprehensive validation.

## ✨ Key Features

- 🚀 **Multi-Mode Execution**: Sequential, Parallel, and DAG workflows
- 📊 **Real-time Monitoring**: Live progress tracking with timeline visualization
- ✅ **Validation System**: Pre-execution pipeline validation with security checks
- 🔖 **Checkpoint & Resume**: Save and restore pipeline execution state
- 📈 **Statistics & Analytics**: Automatic performance tracking and trend analysis
- 📝 **Template System**: 5 built-in templates for common patterns
- 🩺 **Doctor Command**: Environment diagnostics and health checks
- 🌐 **Web & TUI**: Browser-based UI and terminal dashboard
- 🔒 **Security**: Whitelist-based execution control
- 🎨 **User-Friendly**: Colored output, helpful error messages, and suggestions

## 📚 Documentation

### Quick Links
- [📖 English Guide](docs/README.md)
- [📖 한글 가이드](docs/README.ko.md)
- [🚀 Quick Start](docs/QUICK_START.md)
- [⚡ Features](docs/FEATURES.md)
- [📑 Documentation Index (English)](docs/INDEX.en.md)
- [📑 문서 색인 (Korean)](docs/INDEX.md)

### Test Reports
- [✅ **Live Test Results**](test-results/LIVE_TEST_RESULTS.md) - Real execution test (NEW!)
- [📊 Test Summary](test-results/README.md) - Quick overview
- [🧪 Feature Test Report](docs/reports/FEATURE_TEST_REPORT_v1.0.0.md) - Comprehensive test

### Additional Resources
- [📋 Release Notes](docs/release/CHANGELOG.md)
- [🔧 Upgrade Guide](docs/UPGRADE_GUIDE.md)
- [🤖 Claude Integration](docs/CLAUDE_SETUP.md)
- [💡 Usage Tips](docs/USAGE_TIPS.md)
- [📦 Examples](examples/)

## 🚀 Quick Start

### Option 1: Global Installation (Recommended)

```bash
git clone https://github.com/yourusername/cotor.git
cd cotor
./shell/install-global.sh
cotor version
```

### Option 2: Local Usage

```bash
./gradlew shadowJar
chmod +x shell/cotor
./shell/cotor version
```

### Option 3: Docker (Coming Soon)

```bash
docker run -it cotor/cli version
```

## User Flow Examples

### Example 1: Simple Echo Pipeline

**Step 1: Initialize project**
```bash
# Create a new directory for your project
mkdir my-cotor-project
cd my-cotor-project

# Initialize Cotor configuration
java -jar /path/to/cotor-1.0.0.jar init
```

**Step 2: Review the generated configuration**
```bash
cat cotor.yaml
```

You'll see a default configuration with an example echo agent and pipeline.

**Step 3: Run the example pipeline**
```bash
# Run with JSON output (default)
java -jar /path/to/cotor-1.0.0.jar run example-pipeline

# Run with text output for better readability
java -jar /path/to/cotor-1.0.0.jar run example-pipeline --output-format text

# Run with CSV output
java -jar /path/to/cotor-1.0.0.jar run example-pipeline --output-format csv
```

**Expected Output (JSON format):**
```json
{
  "totalAgents": 1,
  "successCount": 1,
  "failureCount": 0,
  "totalDuration": 1,
  "timestamp": "2025-11-12T10:35:24.022014Z",
  "results": [
    {
      "agentName": "example-agent",
      "isSuccess": true,
      "output": "test input",
      "error": null,
      "duration": 1,
      "metadata": { "executedAt": "2025-11-12T10:35:24.021553Z" }
    }
  ]
}
```

### Example 2: Custom Multi-Stage Pipeline

**Step 1: Create a custom configuration**

Edit `cotor.yaml`:

```yaml
version: "1.0"

agents:
  - name: data-processor
    pluginClass: com.cotor.data.plugin.EchoPlugin
    timeout: 30000
    parameters:
      mode: process
    tags:
      - data

  - name: data-analyzer
    pluginClass: com.cotor.data.plugin.EchoPlugin
    timeout: 30000
    parameters:
      mode: analyze
    tags:
      - analysis

pipelines:
  - name: data-workflow
    description: "Process and analyze data"
    executionMode: SEQUENTIAL
    stages:
      - id: process
        agent:
          name: data-processor
          pluginClass: com.cotor.data.plugin.EchoPlugin
        input: "raw data"
        
      - id: analyze
        agent:
          name: data-analyzer
          pluginClass: com.cotor.data.plugin.EchoPlugin
        # Input will be the output from previous stage

security:
  useWhitelist: false
  allowedExecutables: []
  allowedDirectories: []

logging:
  level: INFO
  file: cotor.log
  format: json

performance:
  maxConcurrentAgents: 10
  coroutinePoolSize: 8
```

**Step 2: List available agents**
```bash
java -jar /path/to/cotor-1.0.0.jar list
```

**Output:**
```
Registered Agents (2):
  - data-processor (com.cotor.data.plugin.EchoPlugin)
    Timeout: 30000ms
    Tags: data
  - data-analyzer (com.cotor.data.plugin.EchoPlugin)
    Timeout: 30000ms
    Tags: analysis
```

**Step 3: Run the multi-stage pipeline**
```bash
java -jar /path/to/cotor-1.0.0.jar run data-workflow --output-format text
```

**Output:**
```
================================================================================
Pipeline Execution Results
================================================================================

Summary:
  Total Agents:  2
  Success Count: 2
  Failure Count: 0
  Total Duration: 5ms
  Timestamp:     2025-11-12T10:40:15.123456Z

Agent Results:

  [1] data-processor
      Status:   ✓ SUCCESS
      Duration: 2ms
      Output:
        raw data

  [2] data-analyzer
      Status:   ✓ SUCCESS
      Duration: 3ms
      Output:
        raw data

================================================================================
```

### Example 3: Parallel Execution

**Step 1: Create a parallel pipeline configuration**

```yaml
pipelines:
  - name: parallel-analysis
    description: "Run multiple analyses in parallel"
    executionMode: PARALLEL
    stages:
      - id: analysis1
        agent:
          name: data-analyzer
          pluginClass: com.cotor.data.plugin.EchoPlugin
        input: "dataset 1"
        
      - id: analysis2
        agent:
          name: data-analyzer
          pluginClass: com.cotor.data.plugin.EchoPlugin
        input: "dataset 2"
        
      - id: analysis3
        agent:
          name: data-analyzer
          pluginClass: com.cotor.data.plugin.EchoPlugin
        input: "dataset 3"
```

**Step 2: Run parallel pipeline**
```bash
java -jar /path/to/cotor-1.0.0.jar run parallel-analysis
```

All three analyses will run concurrently, significantly reducing total execution time.

### Example 4: DAG-Based Workflow

**Step 1: Create a DAG pipeline with dependencies**

```yaml
pipelines:
  - name: dag-workflow
    description: "Complex workflow with dependencies"
    executionMode: DAG
    stages:
      - id: fetch-data
        agent:
          name: data-processor
          pluginClass: com.cotor.data.plugin.EchoPlugin
        input: "fetch from source"
        
      - id: process-a
        agent:
          name: data-processor
          pluginClass: com.cotor.data.plugin.EchoPlugin
        dependencies:
          - fetch-data
          
      - id: process-b
        agent:
          name: data-processor
          pluginClass: com.cotor.data.plugin.EchoPlugin
        dependencies:
          - fetch-data
          
      - id: merge-results
        agent:
          name: data-analyzer
          pluginClass: com.cotor.data.plugin.EchoPlugin
        dependencies:
          - process-a
          - process-b
```

**Step 2: Run DAG pipeline**
```bash
java -jar /path/to/cotor-1.0.0.jar run dag-workflow --output-format text
```

Execution order:
1. `fetch-data` runs first
2. `process-a` and `process-b` run in parallel after `fetch-data` completes
3. `merge-results` runs after both `process-a` and `process-b` complete

### Example 5: Using Different Configuration Files

**Step 1: Create multiple configuration files**
```bash
# Development configuration
cp cotor.yaml cotor-dev.yaml

# Production configuration
cp cotor.yaml cotor-prod.yaml
```

**Step 2: Run with specific configuration**
```bash
# Use development config
java -jar /path/to/cotor-1.0.0.jar run example-pipeline --config cotor-dev.yaml

# Use production config
java -jar /path/to/cotor-1.0.0.jar run example-pipeline --config cotor-prod.yaml
```

### Example 6: Monitoring and Debugging

**Step 1: Enable debug mode**
```bash
java -jar /path/to/cotor-1.0.0.jar run example-pipeline --debug
```

This will show detailed execution information and stack traces if errors occur.

**Step 2: Check logs**
```bash
# View the log file
cat cotor.log

# Follow logs in real-time
tail -f cotor.log
```

**Step 3: Check pipeline status (in another terminal)**
```bash
java -jar /path/to/cotor-1.0.0.jar status
```

### Example 7: Multi-AI Model Pipeline (Claude, Codex, Gemini, Copilot)

This advanced example demonstrates how to orchestrate multiple AI models in a single pipeline for comprehensive code generation and review.

**Use Case**: Generate code with multiple AI models and compare/merge results

**Step 1: Create AI model agent plugins**

First, create wrapper plugins for each AI model:

```kotlin
// ClaudePlugin.kt
class ClaudePlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "claude-code-generator",
        version = "1.0.0",
        description = "Claude AI for code generation",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.JSON, DataFormat.TEXT)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): String {
        // Call Claude API or CLI
        val command = listOf(
            "claude-cli",
            "generate",
            "--prompt", context.input ?: ""
        )
        
        val result = processManager.executeProcess(
            command = command,
            input = context.input,
            environment = context.environment,
            timeout = context.timeout
        )
        
        return result.stdout
    }
}

// Similar plugins for Codex, Gemini, and Copilot
class CodexPlugin : AgentPlugin { /* ... */ }
class GeminiPlugin : AgentPlugin { /* ... */ }
class CopilotPlugin : AgentPlugin { /* ... */ }
```

**Step 2: Configure multi-AI pipeline**

Create `multi-ai-pipeline.yaml`:

```yaml
version: "1.0"

agents:
  - name: claude-agent
    pluginClass: com.cotor.plugins.ClaudePlugin
    timeout: 60000
    parameters:
      model: claude-3-opus
      temperature: "0.7"
    tags:
      - ai
      - code-generation
      - claude

  - name: codex-agent
    pluginClass: com.cotor.plugins.CodexPlugin
    timeout: 60000
    parameters:
      model: gpt-4
      temperature: "0.5"
    tags:
      - ai
      - code-generation
      - openai

  - name: gemini-agent
    pluginClass: com.cotor.plugins.GeminiPlugin
    timeout: 60000
    parameters:
      model: gemini-pro
      temperature: "0.6"
    tags:
      - ai
      - code-generation
      - google

  - name: copilot-agent
    pluginClass: com.cotor.plugins.CopilotPlugin
    timeout: 60000
    parameters:
      model: copilot
    tags:
      - ai
      - code-generation
      - github

  - name: code-merger
    pluginClass: com.cotor.plugins.CodeMergerPlugin
    timeout: 30000
    tags:
      - utility

pipelines:
  # Parallel execution - all AI models generate code simultaneously
  - name: multi-ai-parallel
    description: "Generate code with multiple AI models in parallel"
    executionMode: PARALLEL
    stages:
      - id: claude-generation
        agent:
          name: claude-agent
          pluginClass: com.cotor.plugins.ClaudePlugin
        input: "Create a REST API endpoint for user authentication with JWT"

      - id: codex-generation
        agent:
          name: codex-agent
          pluginClass: com.cotor.plugins.CodexPlugin
        input: "Create a REST API endpoint for user authentication with JWT"

      - id: gemini-generation
        agent:
          name: gemini-agent
          pluginClass: com.cotor.plugins.GeminiPlugin
        input: "Create a REST API endpoint for user authentication with JWT"

      - id: copilot-generation
        agent:
          name: copilot-agent
          pluginClass: com.cotor.plugins.CopilotPlugin
        input: "Create a REST API endpoint for user authentication with JWT"

  # Sequential execution with review chain
  - name: multi-ai-review-chain
    description: "Generate code and review through multiple AI models"
    executionMode: SEQUENTIAL
    stages:
      - id: initial-generation
        agent:
          name: claude-agent
          pluginClass: com.cotor.plugins.ClaudePlugin
        input: "Create a REST API endpoint for user authentication with JWT"

      - id: codex-review
        agent:
          name: codex-agent
          pluginClass: com.cotor.plugins.CodexPlugin
          parameters:
            task: review
        # Input will be Claude's output

      - id: gemini-optimization
        agent:
          name: gemini-agent
          pluginClass: com.cotor.plugins.GeminiPlugin
          parameters:
            task: optimize
        # Input will be Codex's reviewed code

      - id: copilot-final-check
        agent:
          name: copilot-agent
          pluginClass: com.cotor.plugins.CopilotPlugin
          parameters:
            task: security-check
        # Input will be Gemini's optimized code

  # DAG-based workflow - complex dependencies
  - name: multi-ai-dag
    description: "Complex AI workflow with dependencies"
    executionMode: DAG
    stages:
      - id: requirement-analysis
        agent:
          name: claude-agent
          pluginClass: com.cotor.plugins.ClaudePlugin
        input: "Analyze requirements for user authentication system"

      - id: architecture-design
        agent:
          name: gemini-agent
          pluginClass: com.cotor.plugins.GeminiPlugin
        dependencies:
          - requirement-analysis

      - id: backend-code
        agent:
          name: codex-agent
          pluginClass: com.cotor.plugins.CodexPlugin
        dependencies:
          - architecture-design

      - id: frontend-code
        agent:
          name: copilot-agent
          pluginClass: com.cotor.plugins.CopilotPlugin
        dependencies:
          - architecture-design

      - id: integration-code
        agent:
          name: claude-agent
          pluginClass: com.cotor.plugins.ClaudePlugin
        dependencies:
          - backend-code
          - frontend-code

      - id: final-review
        agent:
          name: gemini-agent
          pluginClass: com.cotor.plugins.GeminiPlugin
        dependencies:
          - integration-code

security:
  useWhitelist: true
  allowedExecutables:
    - claude-cli
    - openai
    - gemini-cli
    - gh
  allowedDirectories:
    - /usr/local/bin
    - /opt/ai-tools

logging:
  level: INFO
  file: multi-ai.log
  format: json

performance:
  maxConcurrentAgents: 4
  coroutinePoolSize: 8
```

**Step 3: Run parallel AI generation**

```bash
# Generate code with all 4 AI models simultaneously
java -jar cotor-1.0.0.jar run multi-ai-parallel \
  --config multi-ai-pipeline.yaml \
  --output-format text
```

**Expected Output:**
```
================================================================================
Pipeline Execution Results
================================================================================

Summary:
  Total Agents:  4
  Success Count: 4
  Failure Count: 0
  Total Duration: 8500ms
  Timestamp:     2025-11-12T11:00:00.000000Z

Agent Results:

  [1] claude-agent
      Status:   ✓ SUCCESS
      Duration: 8200ms
      Output:
        // Claude's implementation
        @RestController
        @RequestMapping("/api/auth")
        public class AuthController {
            @PostMapping("/login")
            public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request) {
                // JWT authentication logic
                ...
            }
        }

  [2] codex-agent
      Status:   ✓ SUCCESS
      Duration: 7800ms
      Output:
        // Codex's implementation
        class AuthController {
            async login(req, res) {
                // JWT authentication with Express
                ...
            }
        }

  [3] gemini-agent
      Status:   ✓ SUCCESS
      Duration: 8100ms
      Output:
        // Gemini's implementation
        func LoginHandler(w http.ResponseWriter, r *http.Request) {
            // JWT authentication in Go
            ...
        }

  [4] copilot-agent
      Status:   ✓ SUCCESS
      Duration: 7500ms
      Output:
        // Copilot's implementation
        def login(request):
            # JWT authentication in Python
            ...

================================================================================
```

**Step 4: Run sequential review chain**

```bash
# Generate with Claude, then review through other models
java -jar cotor-1.0.0.jar run multi-ai-review-chain \
  --config multi-ai-pipeline.yaml \
  --output-format json
```

**Step 5: Run complex DAG workflow**

```bash
# Execute complex workflow with dependencies
java -jar cotor-1.0.0.jar run multi-ai-dag \
  --config multi-ai-pipeline.yaml \
  --output-format text
```

**Benefits of Multi-AI Pipeline:**

1. **Diverse Perspectives**: Each AI model has different strengths
2. **Quality Assurance**: Multiple reviews catch more issues
3. **Best Practices**: Combine best solutions from each model
4. **Parallel Processing**: Reduce total time with concurrent execution
5. **Consensus Building**: Compare outputs to find optimal solution

**Real-World Use Cases:**

- **Code Generation**: Generate multiple implementations and choose the best
- **Code Review**: Sequential review by different AI models
- **Documentation**: Each AI generates docs, merge the best parts
- **Testing**: Generate test cases from multiple perspectives
- **Refactoring**: Get refactoring suggestions from multiple sources
- **Architecture Design**: Collaborative design with multiple AI advisors

### Example 8: Creating an Alias for Easy Access

**For Unix/Linux/macOS:**
```bash
# Add to ~/.bashrc or ~/.zshrc
alias cotor='java -jar /path/to/cotor-1.0.0.jar'

# Reload shell configuration
source ~/.bashrc  # or source ~/.zshrc

# Now you can use it directly
cotor init
cotor run example-pipeline
cotor list
```

**For Windows (PowerShell):**
```powershell
# Add to PowerShell profile
function cotor { java -jar C:\path\to\cotor-1.0.0.jar $args }

# Now you can use it directly
cotor init
cotor run example-pipeline
cotor list
```

## Configuration

### Example `cotor.yaml`

```yaml
version: "1.0"

# Agent definitions
agents:
  - name: nlp-processor
    pluginClass: com.cotor.data.plugin.NaturalLanguageProcessorPlugin
    timeout: 30000
    parameters:
      mode: analyze
    tags:
      - nlp

# Pipeline definitions
pipelines:
  - name: text-to-code
    description: "Convert natural language to code"
    executionMode: SEQUENTIAL
    stages:
      - id: understand
        agent:
          name: nlp-processor
          pluginClass: com.cotor.data.plugin.NaturalLanguageProcessorPlugin
        input: "Create a REST API for user management"

# Security settings
security:
  useWhitelist: true
  allowedExecutables:
    - python3
    - node
  allowedDirectories:
    - /usr/local/bin

# Logging settings
logging:
  level: INFO
  file: cotor.log
  format: json

# Performance settings
performance:
  maxConcurrentAgents: 10
  coroutinePoolSize: 8
```

## CLI Commands

### Initialize Configuration
```bash
cotor init
```

### Run Pipeline
```bash
cotor run <pipeline-name> [--output-format json|csv|text]
```

### List Agents
```bash
cotor list [--config path/to/config.yaml]
```

### Check Status
```bash
cotor status
```

### Show Version
```bash
>>>>>>> 005b200 (Add web editor, templates, and stub plugins)
cotor version
```

### Option 2: Local Usage

```bash
./gradlew shadowJar
chmod +x shell/cotor
./shell/cotor version
```

### Option 3: Docker (Coming Soon)

```bash
docker run -it cotor/cli version
```

## 📖 Basic Usage

```bash
# 1. Create configuration
cotor init

# 2. List available agents
cotor list

# 3. Validate pipeline
cotor validate example-pipeline

# 4. Run pipeline
cotor run example-pipeline --output-format text

# 5. View statistics
cotor stats

# 6. Check environment
cotor doctor

# 7. Launch web/no-code editor
cotor web --open
```

## 💻 CLI Commands

### Core Commands
| Command | Description | Example |
|---------|-------------|---------|
| `init` | Create configuration file | `cotor init --interactive` |
| `list` | Show registered agents | `cotor list -c cotor.yaml` |
| `run` | Execute pipeline | `cotor run my-pipeline --verbose` |
| `validate` | Validate pipeline | `cotor validate my-pipeline` |
| `version` | Show version info | `cotor version` |

### Advanced Commands
| Command | Description | Example |
|---------|-------------|---------|
| `doctor` | Environment diagnostics | `cotor doctor` |
| `stats` | Show statistics | `cotor stats my-pipeline` |
| `template` | Manage templates | `cotor template compare out.yaml` |
| `checkpoint` | Checkpoint management | `cotor checkpoint` |
| `resume` | Resume from checkpoint | `cotor resume <id>` |
| `dash` | TUI dashboard | `cotor dash` |
| `web` | Web interface | `cotor web` |
| `completion` | Shell completion | `cotor completion zsh` |

### Quick Help
```bash
cotor --short      # 10-line cheat sheet
cotor --help       # Full command help
```

## 📦 Examples

Ready-to-run examples in `examples/`:

```bash
# Single agent example
./shell/cotor run single-agent -c examples/single-agent.yaml

# Parallel comparison
./shell/cotor run parallel-compare -c examples/parallel-compare.yaml

# Decision and loop
./shell/cotor run decision-loop -c examples/decision-loop.yaml

# Run all examples
./examples/run-examples.sh
```

## 🔧 Configuration

Create `cotor.yaml`:

```yaml
version: "1.0"

agents:
  - name: my-agent
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000
    parameters:
      model: claude-3-sonnet
    tags:
      - ai
      - claude

pipelines:
  - name: my-pipeline
    description: "My first pipeline"
    executionMode: SEQUENTIAL
    stages:
      - id: step1
        agent:
          name: my-agent
        input: "Analyze this code"

security:
  useWhitelist: true
  allowedExecutables:
    - claude
    - gemini
  allowedDirectories:
    - /usr/local/bin

logging:
  level: INFO
  file: cotor.log
  format: json

performance:
  maxConcurrentAgents: 10
  coroutinePoolSize: 8
```

## 🧪 Testing

```bash
# Run unit tests
./gradlew test

# Run integration tests
./shell/test-cotor-enhanced.sh
./shell/test-cotor-pipeline.sh
./shell/test-claude-integration.sh

# Dry run (simulation)
cotor run my-pipeline --dry-run
```

## 🤝 Integration

### Claude Code Integration

```bash
./shell/install-claude-integration.sh
```

Adds slash commands and knowledge base for Claude Code.

### CI/CD Integration

```yaml
# GitHub Actions example
- name: Run Cotor Pipeline
  run: |
    ./shell/cotor run build-and-test \
      -c .cotor/ci-pipeline.yaml \
      --output-format json
```

## 📊 Architecture

```
cotor/
├── src/main/kotlin/com/cotor/
│   ├── Main.kt                          # Entry point
│   ├── domain/
│   │   ├── orchestrator/                # Pipeline execution
│   │   ├── executor/                    # Agent execution
│   │   └── condition/                   # Conditional logic
│   ├── presentation/
│   │   ├── cli/                         # CLI commands
│   │   ├── web/                         # Web UI
│   │   └── formatter/                   # Output formatting
│   ├── monitoring/                      # Real-time monitoring
│   ├── checkpoint/                      # Checkpoint system
│   ├── stats/                           # Statistics
│   └── validation/                      # Pipeline validation
├── examples/                            # Example pipelines
├── docs/                                # Documentation
└── shell/                               # Shell scripts
```

## 🛠️ Development

### Prerequisites
- JDK 17 or higher
- Kotlin 2.1.0
- Gradle 8.5

### Build

```bash
./gradlew clean build shadowJar
```

### Run Tests

```bash
./gradlew test
./gradlew jacocoTestReport  # Coverage report
```

## 📈 Roadmap

### v1.1.0 (Next)
- [ ] Complete resume functionality
- [ ] Enhanced web UI
- [ ] Additional templates
- [ ] Performance optimizations

### v1.2.0
- [ ] Cloud execution support
- [ ] Advanced conditional logic
- [ ] Dynamic pipeline generation
- [ ] More AI CLI integrations

### v2.0.0 (Long-term)
- [ ] Distributed execution
- [ ] ML integration
- [ ] Advanced visualizations
- [ ] Enterprise features

## 🤝 Contributing

Contributions are welcome! Please read our [Contributing Guide](CONTRIBUTING.md) first.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Built with [Kotlin](https://kotlinlang.org/)
- CLI powered by [Clikt](https://ajalt.github.io/clikt/)
- Terminal UI with [Mordant](https://ajalt.github.io/mordant/)
- Dependency injection via [Koin](https://insert-koin.io/)

## 📞 Support

- 📧 Email: support@cotor.io
- 💬 Discord: [Join our community](https://discord.gg/cotor)
- 🐛 Issues: [GitHub Issues](https://github.com/yourusername/cotor/issues)
- 📖 Wiki: [Documentation](https://github.com/yourusername/cotor/wiki)

---

**Made with ❤️ by the Cotor Team**
