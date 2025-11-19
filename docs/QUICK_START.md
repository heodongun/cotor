# Cotor Quick Start Guide

Get up and running with Cotor in 5 minutes!

## Prerequisites

- Java 17 or higher
- At least one AI CLI tool installed (Claude, Gemini, Copilot, etc.)

## Installation

### Option 1: Quick Install (Recommended)

```bash
git clone https://github.com/yourorg/cotor.git
cd cotor
./gradlew shadowJar
chmod +x cotor
```

### Option 2: Global Install

```bash
./install-global.sh
```

This makes `cotor` available system-wide.

## Your First Pipeline

### Step 1: Create a Configuration File

```bash
cat > my-pipeline.yaml << 'EOF'
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000

pipelines:
  - name: hello-world
    description: "My first cotor pipeline"
    executionMode: SEQUENTIAL
    stages:
      - id: greeting
        agent:
          name: claude
        input: "Say hello and introduce yourself"

security:
  useWhitelist: true
  allowedExecutables:
    - claude
  allowedDirectories:
    - /usr/local/bin
    - /opt/homebrew/bin
EOF
```

### Step 2: Validate the Pipeline

```bash
java -jar build/libs/cotor-1.0.0.jar validate hello-world -c my-pipeline.yaml
```

**Expected output:**
```
✅ Pipeline structure: valid
✅ All agents defined: valid
✅ Stage dependencies: valid

🎉 No warnings found!
```

### Step 3: Dry-Run (Optional)

Test without actually running:

```bash
java -jar build/libs/cotor-1.0.0.jar run hello-world --dry-run -c my-pipeline.yaml
```

**Expected output:**
```
📋 Pipeline Estimate: hello-world
   Execution Mode: SEQUENTIAL

Stages:
  ├─ greeting (claude)
  │  └─ ~30s

⏱️  Total Estimated Duration: ~30s
```

### Step 4: Run the Pipeline

```bash
java -jar build/libs/cotor-1.0.0.jar run hello-world -c my-pipeline.yaml --verbose
```

**What you'll see:**

```
🚀 Running: hello-world (1 stages)
┌──────────────────────────────────────────────┐
│ 🔄 Stage 1: greeting                         │
└──────────────────────────────────────────────┘
⏱️  Elapsed: 00:00:15 | Progress: 0% (0/1 stages completed)

... (pipeline executes) ...

📊 Pipeline Execution Summary
──────────────────────────────────────────────
Pipeline: hello-world
Execution Mode: SEQUENTIAL

Results:
  ✅ Completed: 1/1
  ⏱️  Total Duration: 15.2s
──────────────────────────────────────────────

📄 Results:
{
  "summary": {
    "totalAgents": 1,
    "successCount": 1,
    "failureCount": 0
  },
  "results": [
    {
      "agentName": "claude",
      "isSuccess": true,
      "output": "Hello! I'm Claude..."
    }
  ]
}
```

## Next Steps

### Try Multi-Agent Pipelines

Create a pipeline with multiple AI agents:

```yaml
version: "1.0"

agents:
  - name: claude
    pluginClass: com.cotor.data.plugin.ClaudePlugin
    timeout: 60000
  - name: gemini
    pluginClass: com.cotor.data.plugin.GeminiPlugin
    timeout: 60000

pipelines:
  - name: compare-solutions
    description: "Get multiple perspectives"
    executionMode: PARALLEL  # Run simultaneously!
    stages:
      - id: claude-solution
        agent:
          name: claude
        input: "Write a Python function to reverse a string"

      - id: gemini-solution
        agent:
          name: gemini
        input: "Write a Python function to reverse a string"

security:
  useWhitelist: true
  allowedExecutables:
    - claude
    - gemini
  allowedDirectories:
    - /usr/local/bin
    - /opt/homebrew/bin
```

Run it:

```bash
java -jar build/libs/cotor-1.0.0.jar run compare-solutions -c my-pipeline.yaml
```

You'll get 2 different implementations simultaneously!

### Try Sequential Pipelines

Create a pipeline where output flows through stages:

```yaml
pipelines:
  - name: code-review-chain
    description: "Generate → Review → Optimize"
    executionMode: SEQUENTIAL  # Output feeds to next stage
    stages:
      - id: generate
        agent:
          name: claude
        input: "Create a simple REST API endpoint"

      - id: review
        agent:
          name: gemini
        # Input will be Claude's output

      - id: optimize
        agent:
          name: claude
        # Input will be Gemini's reviewed code
```

### Test with the Board Example

Try the complete board implementation example:

```bash
cd test/board-feature
java -jar ../../build/libs/cotor-1.0.0.jar validate board-implementation -c board-pipeline.yaml
java -jar ../../build/libs/cotor-1.0.0.jar run board-implementation --dry-run -c board-pipeline.yaml
```

## Troubleshooting

### Error: "Configuration file not found"

Make sure you're in the correct directory:

```bash
ls -la my-pipeline.yaml  # Should exist
pwd  # Check current directory
```

### Error: "Agent not found"

Make sure the AI CLI is installed:

```bash
which claude  # Should show path
claude --version  # Should work
```

Add the path to your security whitelist in the YAML file.

### Error: "Pipeline validation failed"

Run validation to see specific errors:

```bash
java -jar build/libs/cotor-1.0.0.jar validate <pipeline-name> -c <config-file>
```

Fix the reported errors and try again.

## Common Patterns

### Pattern 1: Compare Multiple Solutions

```yaml
executionMode: PARALLEL
stages:
  - id: solution-1
    agent: { name: claude }
    input: "<same problem>"
  - id: solution-2
    agent: { name: gemini }
    input: "<same problem>"
```

### Pattern 2: Sequential Processing

```yaml
executionMode: SEQUENTIAL
stages:
  - id: step-1
    agent: { name: claude }
    input: "<initial input>"
  - id: step-2
    agent: { name: gemini }
    # Automatically uses step-1 output
```

### Pattern 3: Multi-Perspective Review

```yaml
executionMode: PARALLEL
stages:
  - id: security-review
    agent: { name: claude }
    input: "Review for security: <code>"
  - id: performance-review
    agent: { name: gemini }
    input: "Review for performance: <code>"
```

## Best Practices

1. **Always Validate First**
   ```bash
   cotor validate <pipeline> -c <config>
   ```

2. **Use Dry-Run for Estimates**
   ```bash
   cotor run <pipeline> --dry-run -c <config>
   ```

3. **Enable Verbose Mode for Debugging**
   ```bash
   cotor run <pipeline> --verbose -c <config>
   ```

4. **Set Appropriate Timeouts**
   - Simple tasks: 30-60 seconds
   - Complex tasks: 120-240 seconds
   - Long-running: 300+ seconds

5. **Use PARALLEL for Independence**
   - When tasks don't depend on each other
   - When you want multiple perspectives
   - When speed matters

6. **Use SEQUENTIAL for Dependencies**
   - When output feeds to next stage
   - When order matters
   - When building on previous results

## Need Help?

- **Documentation**: [docs/](../docs/)
- **Examples**: [examples/](../examples/)
- **Upgrade Guide**: [docs/UPGRADE_GUIDE.md](./UPGRADE_GUIDE.md)
- **Issues**: [GitHub Issues](https://github.com/yourorg/cotor/issues)

## What's Next?

1. Read the [Upgrade Guide](./UPGRADE_GUIDE.md) for advanced features
2. Check out [example pipelines](../examples/)
3. Create your own custom plugins
4. Integrate with CI/CD pipelines

Happy orchestrating! 🎉
