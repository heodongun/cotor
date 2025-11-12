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
| **Copilot** | `copilot -p <prompt> --allow-all-tools` | GitHub's AI assistant |
| **Gemini** | `gemini --yolo <prompt>` | Google's multimodal AI |
| **Codex** | `openai chat --model gpt-4 --message <prompt>` | OpenAI's code model |
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

### Single AI

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
EOF

# Run it
cotor run generate-code --config single-ai.yaml
```

### Multiple AIs in Parallel

```bash
# All AIs work on the same task simultaneously
cotor run multi-ai-parallel --config cotor.yaml --output-format text
```

### Sequential Pipeline

```bash
# Claude generates → Copilot reviews → Gemini optimizes
cotor run sequential-workflow --config cotor.yaml
```

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
