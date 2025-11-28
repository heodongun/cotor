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
- [📑 Documentation Index](docs/INDEX.md)

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
