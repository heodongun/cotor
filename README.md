# Cotor - AI CLI Master-Agent System

[![English](https://img.shields.io/badge/Language-English-blue)](README.md)
[![한국어](https://img.shields.io/badge/Language-한국어-red)](README.ko.md)

Cotor is a Kotlin-based AI CLI orchestration system that manages multiple AI tools through a unified interface. Built with coroutines for high-performance async execution.

## 🎉 What's New in v1.0

### Enhanced CLI Experience
- ✅ **Real-Time Progress Monitoring**: Live pipeline execution tracking with visual progress bars
- ✅ **Pipeline Validation**: Validate configurations before running (`cotor validate`)
- ✅ **Dry-Run Mode**: Estimate execution time without actually running (`--dry-run`)
- ✅ **User-Friendly Errors**: Clear error messages with solutions and documentation links
- ✅ **Verbose Mode**: Detailed logging for debugging (`--verbose`)
- ✅ **Test Framework**: Built-in testing command for pipeline validation

### New Commands
```bash
cotor validate <pipeline>  # Validate pipeline configuration
cotor run <pipeline> --dry-run  # Simulate execution
cotor run <pipeline> --verbose  # Run with detailed logging
cotor test  # Run test suite
```

### Improved Developer Experience
- 📊 **Live Progress Display**: See exactly what's happening in real-time
- 🎯 **Duration Estimates**: Know how long pipelines will take before running
- 🛡️ **Better Error Messages**: Actionable solutions for every error
- 🧪 **Testing Tools**: Validate pipelines before production use

👉 **[See the Upgrade Guide](docs/UPGRADE_GUIDE.md)** for full details!

## ✨ Core Features

- 🚀 **Coroutine-Based Async**: High-performance parallel execution
- 🔌 **Plugin Architecture**: Easy integration of new AI tools
- 🔄 **Flexible Orchestration**: Sequential, parallel, and DAG-based pipelines
- 🔐 **Security First**: Whitelist-based command validation
- 📊 **Real-Time Monitoring**: Live progress tracking and metrics
- 🎯 **Multiple Formats**: JSON, CSV, and text output
- ✅ **Pipeline Validation**: Pre-flight checks before execution

## 📦 Installation

### Quick Install (Recommended)

```bash
git clone https://github.com/yourusername/cotor.git
cd cotor
./install-global.sh
```

This will:
- ✅ Build the project
- ✅ Install `cotor` command globally
- ✅ Make it available from anywhere

### Manual Install

```bash
./gradlew shadowJar
chmod +x cotor
ln -s $(pwd)/cotor /usr/local/bin/cotor
```

### Claude Code Integration (Optional)

Install global slash commands for Claude Code to use cotor seamlessly:

```bash
./install-claude-integration.sh
```

This will install:
- ✅ `/cotor-generate` - Auto-generate pipelines from goals
- ✅ `/cotor-execute` - Execute pipelines with monitoring
- ✅ `/cotor-validate` - Validate pipeline syntax
- ✅ `/cotor-template` - Create from templates
- ✅ Global knowledge base for Claude to understand cotor

**Available everywhere**: These commands work in any project once installed!

📖 **[Detailed Setup Guide](docs/CLAUDE_SETUP.md)** - Manual installation and troubleshooting

## 🤖 Built-in AI Plugins

Cotor integrates with these AI CLI tools:

| AI | Command | Description |
|----|---------|-------------|
| **Claude** | `claude --print <prompt>` | Anthropic's advanced AI |
| **Codex** | `codex exec <prompt>` | Codex AI for code generation |
| **Copilot** | `copilot -p <prompt> --allow-all-tools` | GitHub's AI assistant |
| **Gemini** | `gemini --yolo <prompt>` | Google's multimodal AI |
| **Cursor** | `cursor-cli generate <prompt>` | Cursor AI editor |
| **OpenCode** | `opencode generate <prompt>` | Open-source AI |

### Install AI CLIs

```bash
# Claude (if you have access)
# Install from Anthropic

# GitHub Copilot
# Already installed if you have Copilot CLI

# Gemini
# Install from Google AI

# OpenAI
pip install openai

# Others as needed
```

## 🚀 Quick Start

### 1. Initialize

```bash
cotor init
```

Creates a `cotor.yaml` configuration file.

### 2. Create Configuration

```yaml
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000

  - name: copilot
    pluginClass: com.cotor.data.plugin.CopilotPlugin
    timeout: 60000

  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: code-review
    description: "Multi-AI code review"
    executionMode: PARALLEL
    stages:
      - id: claude-review
        agent:
          name: claude
        input: "Review this code for best practices"

      - id: copilot-review
        agent:
          name: copilot
        input: "Review this code for bugs"

      - id: gemini-review
        agent:
          name: gemini
        input: "Review this code for performance"

security:
  useWhitelist: true
  allowedExecutables:
    - claude
    - copilot
    - gemini
  allowedDirectories:
    - /usr/local/bin
    - /opt/homebrew/bin

logging:
  level: INFO
  file: cotor.log

performance:
  maxConcurrentAgents: 10
```

### 3. Run Pipeline

```bash
# List available agents
cotor list

# Run pipeline
cotor run code-review --output-format text

# Run with specific config
cotor run code-review --config my-config.yaml
```

## 📖 Usage Examples

### Example 1: Single AI Task

```bash
# Create a simple pipeline
cat > single-ai.yaml << EOF
version: "1.0"
agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000

pipelines:
  - name: generate-code
    executionMode: SEQUENTIAL
    stages:
      - id: generate
        agent:
          name: claude
        input: "Create a Python hello world function"

security:
  useWhitelist: true
  allowedExecutables: [claude]
  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin]
EOF

# Run it
cotor run generate-code --config single-ai.yaml
```

### Example 2: Multiple AIs in Parallel (Same Task)

Get different perspectives on the same problem:

```bash
cat > multi-compare.yaml << EOF
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000
  - name: codex
    pluginClass: com.cotor.data.plugin.CodexPlugin
    timeout: 60000
  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: compare-solutions
    description: "Get 3 different implementations"
    executionMode: PARALLEL
    stages:
      - id: claude-solution
        agent:
          name: claude
        input: "Write a function to find prime numbers up to N"
      
      - id: codex-solution
        agent:
          name: codex
        input: "Write a function to find prime numbers up to N"
      
      - id: gemini-solution
        agent:
          name: gemini
        input: "Write a function to find prime numbers up to N"

security:
  useWhitelist: true
  allowedExecutables: [claude, codex, gemini]
  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin]
EOF

# Run and compare results
cotor run compare-solutions --config multi-compare.yaml --output-format text
```

**Output**: You'll get 3 different implementations simultaneously!

### Example 3: Sequential AI Pipeline (Review Chain)

One AI's output becomes the next AI's input:

```bash
cat > review-chain.yaml << EOF
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000
  - name: codex
    pluginClass: com.cotor.data.plugin.CodexPlugin
    timeout: 60000
  - name: copilot
    pluginClass: com.cotor.data.plugin.CopilotPlugin
    timeout: 60000

pipelines:
  - name: code-review-chain
    description: "Generate → Review → Optimize"
    executionMode: SEQUENTIAL
    stages:
      - id: generate
        agent:
          name: claude
        input: "Create a REST API endpoint for user authentication"
      
      - id: review
        agent:
          name: codex
        # Input will be Claude's output
      
      - id: optimize
        agent:
          name: copilot
        # Input will be Codex's reviewed code

security:
  useWhitelist: true
  allowedExecutables: [claude, codex, copilot]
  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin]
EOF

# Run the chain
cotor run code-review-chain --config review-chain.yaml --output-format text
```

**Flow**: Claude generates → Codex reviews → Copilot optimizes

### Example 4: Multi-AI Code Review

Get comprehensive feedback from multiple AIs:

```bash
cat > code-review.yaml << EOF
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000
  - name: codex
    pluginClass: com.cotor.data.plugin.CodexPlugin
    timeout: 60000
  - name: copilot
    pluginClass: com.cotor.data.plugin.CopilotPlugin
    timeout: 60000
  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: comprehensive-review
    description: "Multi-perspective code review"
    executionMode: PARALLEL
    stages:
      - id: security-review
        agent:
          name: claude
        input: "Review this code for security vulnerabilities: [your code here]"
      
      - id: performance-review
        agent:
          name: codex
        input: "Review this code for performance issues: [your code here]"
      
      - id: best-practices
        agent:
          name: copilot
        input: "Review this code for best practices: [your code here]"
      
      - id: optimization
        agent:
          name: gemini
        input: "Suggest optimizations for this code: [your code here]"

security:
  useWhitelist: true
  allowedExecutables: [claude, codex, copilot, gemini]
  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin]
EOF

# Get 4 different reviews simultaneously
cotor run comprehensive-review --config code-review.yaml --output-format text
```

**Result**: 4 AIs review your code from different angles - all at once!

### Example 5: AI Consensus Building

Use multiple AIs to reach a consensus:

```bash
cat > consensus.yaml << EOF
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000
  - name: codex
    pluginClass: com.cotor.data.plugin.CodexPlugin
    timeout: 60000
  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: architecture-decision
    description: "Get architectural recommendations"
    executionMode: PARALLEL
    stages:
      - id: claude-opinion
        agent:
          name: claude
        input: "What's the best architecture for a real-time chat app?"
      
      - id: codex-opinion
        agent:
          name: codex
        input: "What's the best architecture for a real-time chat app?"
      
      - id: gemini-opinion
        agent:
          name: gemini
        input: "What's the best architecture for a real-time chat app?"

security:
  useWhitelist: true
  allowedExecutables: [claude, codex, gemini]
  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin]
EOF

# Compare recommendations
cotor run architecture-decision --config consensus.yaml --output-format text
```

**Use Case**: Compare different AI opinions to make better decisions!

## 🎯 CLI Commands

### Basic Commands

```bash
# Initialize configuration
cotor init

# List registered agents
cotor list [--config path/to/config.yaml]

# Show version
cotor version

# Check status
cotor status
```

### Pipeline Commands

```bash
# Validate pipeline before running
cotor validate <pipeline-name> [--config path/to/config.yaml]

# Run pipeline with real-time monitoring
cotor run <pipeline-name> [options]
  --config <path>           Configuration file (default: cotor.yaml)
  --output-format <format>  Output format: json, csv, text (default: json)
  --verbose, -v             Enable verbose output with detailed logging
  --dry-run                 Simulate execution and show estimates
  --watch, -w               Enable real-time progress monitoring (default: true)
  --debug, -d               Enable debug mode

# Test cotor functionality
cotor test [--test-dir path/to/test]
```

### Command Examples

```bash
# Validate before running
./cotor validate board-implementation -c test/board-feature/board-pipeline.yaml

# Dry-run to see estimates
./cotor run board-implementation --dry-run -c test/board-feature/board-pipeline.yaml

# Run with verbose logging
./cotor run board-implementation --verbose -c test/board-feature/board-pipeline.yaml

# Test the installation
./cotor test
```

## 🔧 Creating Custom Plugins

```kotlin
package com.cotor.data.plugin

import com.cotor.data.process.ProcessManager
import com.cotor.model.*

class MyAIPlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "my-ai",
        version = "1.0.0",
        description = "My custom AI integration",
        author = "Your Name",
        supportedFormats = listOf(DataFormat.TEXT)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): String {
        val prompt = context.input ?: throw IllegalArgumentException("Input required")
        
        // Execute your AI CLI
        val command = listOf("my-ai-cli", prompt)
        
        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout
        )
        
        if (!result.isSuccess) {
            throw AgentExecutionException("Execution failed: ${result.stderr}")
        }
        
        return result.stdout
    }
}
```

Add to `cotor.yaml`:

```yaml
agents:
  - name: my-ai
    pluginClass: com.cotor.data.plugin.MyAIPlugin
    timeout: 30000

security:
  allowedExecutables:
    - my-ai-cli
```

## 🏗️ Architecture

```
┌─────────────────────────────────────┐
│      Presentation Layer             │
│  (CLI, Commands, Formatters)       │
└─────────────────────────────────────┘
              ↓
┌─────────────────────────────────────┐
│       Domain Layer                  │
│  (Orchestration, Execution)         │
└─────────────────────────────────────┘
              ↓
┌─────────────────────────────────────┐
│        Data Layer                   │
│  (Registry, Config, Process)        │
└─────────────────────────────────────┘
```

## 🔒 Security

- **Whitelist Validation**: Only approved executables can run
- **Command Injection Prevention**: Input sanitization
- **Path Validation**: Restricted to allowed directories
- **Environment Protection**: Dangerous variables blocked

## 📊 Performance

- **Parallel Execution**: Run multiple AIs simultaneously
- **Coroutine-Based**: Lightweight concurrency
- **Resource Management**: Memory monitoring and limits
- **Configurable Timeouts**: Prevent hanging processes

## 🧪 Testing

### Unit Tests
```bash
# Run tests
./gradlew test

# Generate coverage report
./gradlew jacocoTestReport

# Build
./gradlew shadowJar
```

### Pipeline Tests

Test cotor with a real-world example (Board CRUD feature):

```bash
./test-cotor-pipeline.sh
```

This will:
1. Create a test directory with a board implementation pipeline
2. Run the pipeline with Claude and Gemini
3. Generate complete CRUD implementation
4. Create tests and documentation

**Expected output:**
- `requirements.md` - Requirements and design
- `Board.kt` - Entity class
- `BoardRepository.kt` - Repository interface
- `BoardService.kt` - Service layer
- `BoardController.kt` - REST controller
- `code-review.md` - Code review feedback
- `BoardServiceTest.kt` - Unit tests
- `README.md` - Complete documentation

## 📝 Example Output

```
================================================================================
Pipeline Execution Results
================================================================================

Summary:
  Total Agents:  3
  Success Count: 3
  Failure Count: 0
  Total Duration: 26000ms

Agent Results:

  [1] claude
      Status:   ✓ SUCCESS
      Duration: 17933ms
      Output:
        I've created a Python "Hello, World!" program...

  [2] copilot
      Status:   ✓ SUCCESS
      Duration: 12963ms
      Output:
        Created `hello-world.js` with a simple console.log...

  [3] gemini
      Status:   ✓ SUCCESS
      Duration: 25800ms
      Output:
        I have created the `hello.go` file...

================================================================================
```

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## 📄 License

[Add your license here]

## 🔗 Links

- [Documentation](docs/)
- [Examples](examples/)
- [Issues](https://github.com/yourusername/cotor/issues)
- [Upgrade Recommendations](docs/UPGRADE_RECOMMENDATIONS.md) - Future improvements
- [Claude Setup Guide](docs/CLAUDE_SETUP.md) - Claude Code integration

## 💡 Tips

- Use `--debug` flag for detailed execution logs
- Set `maxConcurrentAgents` based on your system resources
- Use `PARALLEL` mode for independent tasks
- Use `SEQUENTIAL` mode when output feeds into next stage
- Use `DAG` mode for complex dependencies

## 🎨 Claude Code Integration

If you installed the Claude integration, you can use these slash commands in **any project**:

### Available Commands

| Command | Description | Example |
|---------|-------------|---------|
| `/cotor-generate` | Auto-generate pipelines from goals | `/cotor-generate "3 AIs compare sorting algorithms"` |
| `/cotor-execute` | Execute pipelines with monitoring | `/cotor-execute pipeline.yaml` |
| `/cotor-validate` | Validate pipeline syntax | `/cotor-validate pipeline.yaml` |
| `/cotor-template` | Create from templates | `/cotor-template compare-solutions my-pipeline.yaml` |

### Quick Start

**1. List available templates:**
```
/cotor-template
```

**2. Create from template:**
```
/cotor-template compare-solutions test.yaml
```

**3. Validate:**
```
/cotor-validate test.yaml
```

**4. Execute:**
```
/cotor-execute test.yaml
```

### Available Templates

- **compare-solutions**: Multiple AIs solve the same problem in parallel
- **review-chain**: Sequential code review (generate → review → optimize)
- **comprehensive-review**: Parallel multi-perspective review (security, performance, best practices)

### Knowledge Base

Claude automatically understands cotor through the global knowledge base at `~/.claude/steering/cotor-knowledge.md`:
- ✅ Cotor commands and syntax
- ✅ Pipeline patterns and best practices
- ✅ AI plugin information
- ✅ Troubleshooting guides

### Verification

Test your installation:
```bash
./test-claude-integration.sh
```

All tests should pass ✅

---

**Made with ❤️ using Kotlin and Coroutines**
