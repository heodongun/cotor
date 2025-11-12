# Cotor - AI CLI Master-Agent System

[![English](https://img.shields.io/badge/Language-English-blue)](README.md)
[![한국어](https://img.shields.io/badge/Language-한국어-red)](README.ko.md)

Cotor is a Kotlin-based AI CLI orchestration system that manages multiple AI tools through a unified interface. Built with coroutines for high-performance async execution.

## ✨ Features

- 🚀 **Coroutine-Based Async**: High-performance parallel execution
- 🔌 **Plugin Architecture**: Easy integration of new AI tools
- 🔄 **Flexible Orchestration**: Sequential, parallel, and DAG-based pipelines
- 🔐 **Security First**: Whitelist-based command validation
- 📊 **Monitoring**: Built-in logging and metrics
- 🎯 **Multiple Formats**: JSON, CSV, and text output

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

```bash
# Initialize configuration
cotor init

# List registered agents
cotor list [--config path/to/config.yaml]

# Run a pipeline
cotor run <pipeline-name> [options]
  --config <path>           Configuration file (default: cotor.yaml)
  --output-format <format>  Output format: json, csv, text (default: json)
  --debug                   Enable debug mode

# Check status
cotor status

# Show version
cotor version
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

```bash
# Run tests
./gradlew test

# Generate coverage report
./gradlew jacocoTestReport

# Build
./gradlew shadowJar
```

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

## 💡 Tips

- Use `--debug` flag for detailed execution logs
- Set `maxConcurrentAgents` based on your system resources
- Use `PARALLEL` mode for independent tasks
- Use `SEQUENTIAL` mode when output feeds into next stage
- Use `DAG` mode for complex dependencies

---

**Made with ❤️ using Kotlin and Coroutines**
