# Cotor - AI CLI Master-Agent System

Cotor is a Kotlin-based AI CLI master-agent system that orchestrates multiple independent AI CLI tools through a unified command-line interface. Built with coroutines for high-performance asynchronous execution, Cotor provides a flexible and extensible framework for managing AI workflows.

## Features

- **🚀 Coroutine-Based Async Execution**: All I/O operations and agent executions use Kotlin coroutines for optimal performance
- **🔌 Plugin Architecture**: Easily add new AI tools through a simple plugin interface
- **🔄 Flexible Orchestration**: Support for sequential, parallel, and DAG-based pipeline execution
- **🔐 Security First**: Whitelist-based command validation and injection attack prevention
- **📊 Monitoring & Metrics**: Built-in logging, metrics collection, and performance monitoring
- **⚙️ Configuration Management**: YAML and JSON configuration file support
- **🎯 Multiple Output Formats**: JSON, CSV, and human-readable text output

## Requirements

- JDK 17 or higher
- Gradle 8.0 or higher
- Kotlin 1.9+

## Quick Start

### 1. Build the Project

```bash
./gradlew build
```

### 2. Create Shadow JAR

```bash
./gradlew shadowJar
```

The executable JAR will be created at `build/libs/cotor-1.0.0.jar`.

### 3. Initialize Configuration

```bash
java -jar build/libs/cotor-1.0.0.jar init
```

This creates a default `cotor.yaml` configuration file in the current directory.

### 4. Run a Pipeline

```bash
java -jar build/libs/cotor-1.0.0.jar run example-pipeline
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

### Example 7: Creating an Alias for Easy Access

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
cotor version
```

## Creating Custom Plugins

Implement the `AgentPlugin` interface:

```kotlin
class MyCustomPlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "my-plugin",
        version = "1.0.0",
        description = "My custom agent",
        author = "Your Name",
        supportedFormats = listOf(DataFormat.JSON)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): String {
        // Your implementation here
        return "output"
    }
}
```

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  Presentation Layer                      │
│  (CLI Interface, Command Handlers, Output Formatters)   │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                    Domain Layer                          │
│  (Business Logic, Orchestration, Pipeline Management)   │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                     Data Layer                           │
│  (Agent Registry, Config Repository, Process Executor)  │
└─────────────────────────────────────────────────────────┘
```

## Execution Modes

### Sequential
Executes stages one after another, passing output as input to the next stage.

### Parallel
Executes all stages concurrently.

### DAG (Dependency Graph)
Executes stages based on dependency relationships.

## Security

- **Whitelist Validation**: Only explicitly allowed executables can run
- **Command Injection Prevention**: Detects and blocks injection patterns
- **Path Validation**: Ensures file operations stay within allowed directories
- **Environment Variable Protection**: Blocks dangerous environment variables

## Performance

- **Coroutine-Based**: Lightweight concurrency for thousands of concurrent operations
- **Resource Management**: Memory monitoring and automatic garbage collection
- **Configurable Limits**: Control max concurrent agents and thread pool sizes

## Testing

Run all tests:
```bash
./gradlew test
```

Generate coverage report:
```bash
./gradlew jacocoTestReport
```

## Development

### Project Structure

```
src/main/kotlin/com/cotor/
├── model/                  # Domain models and data classes
├── domain/                 # Business logic
│   ├── orchestrator/       # Pipeline orchestration
│   ├── executor/           # Agent execution
│   └── aggregator/         # Result aggregation
├── data/                   # Data access layer
│   ├── registry/           # Agent registry
│   ├── config/             # Configuration management
│   ├── process/            # Process execution
│   └── plugin/             # Plugin system
├── security/               # Security validation
├── event/                  # Event system
├── monitoring/             # Logging and metrics
├── presentation/           # CLI interface
│   ├── cli/                # Commands
│   └── formatter/          # Output formatters
└── di/                     # Dependency injection
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## License

[Add your license here]

## Contact

[Add contact information]
