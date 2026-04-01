# OpenCode Agent

OpenCode is one of the built-in AI agents available in Cotor. It integrates the [OpenCode](https://github.com/opencode-ai/opencode) CLI as a child process, enabling non-interactive prompt execution through Cotor's orchestration layer.

## Quick Start

### Prerequisites

- OpenCode installed (`opencode` available in PATH)
- OpenCode configured with a valid provider and model
- For auto-approval: set OpenCode to yolo mode (`opencode` configuration)

### Usage

#### CLI / Interactive Mode

```bash
cotor interactive
# Then use :use opencode or :model opencode
```

#### Compare Mode

```bash
cotor interactive
# :mode compare
# opencode will run alongside other configured agents
```

#### Pipeline Configuration

Add OpenCode as an agent in your `cotor.yaml`:

```yaml
agents:
  - name: opencode
    plugin_class: com.cotor.data.plugin.OpenCodePlugin
    timeout: 900000  # 15 minutes
```

## How It Works

The `OpenCodePlugin` executes:

```bash
opencode run "<prompt>"
```

This runs OpenCode in non-interactive mode with the given prompt. The plugin captures stdout and returns it to Cotor's orchestration layer.

### Command Flow

1. Cotor receives a prompt (from pipeline, interactive session, or company workflow)
2. `OpenCodePlugin.execute()` builds the command: `["opencode", "run", "<prompt>"]`
3. `ProcessManager` spawns the child process with configured env/working directory
4. Process output is captured and returned as `PluginExecutionOutput`
5. If the process exits with a non-zero code, a `ProcessExecutionException` is thrown with captured stdout/stderr

## Configuration

### Agent Parameters

OpenCode does not require additional parameters beyond the prompt. Model selection is handled through OpenCode's own configuration (`~/.opencode/`).

### Timeout

Default timeout is 15 minutes (900,000ms). Adjust based on expected task complexity:

```yaml
agents:
  - name: opencode
    timeout: 1800000  # 30 minutes
```

### Security

OpenCode is included in the allowed executables whitelist in `KoinModules.kt`. No additional security configuration is needed for standard usage.

## Company Features

OpenCode is available in all company workflow surfaces:

- **BuiltinAgentCatalog**: Listed as a built-in agent option
- **DesktopAppService**: Included in the preferred agent list for company operations
- **WebServer**: Registered in the plugin map for web-based agent selection
- **DesktopTuiSessionService**: PATH includes `~/.opencode/bin` for bundled installs

## Troubleshooting

### "OpenCode execution failed"

**Cause**: The command being executed is not valid. This was previously caused by using `opencode generate` (non-existent subcommand).

**Fix**: Ensure you're using a recent version of Cotor that uses `opencode run`. Verify the command works manually:

```bash
opencode run "say hello"
```

If OpenCode itself fails (e.g., model not found), the error is from OpenCode's configuration, not Cotor's integration.

### Model Not Found

OpenCode will fail if no valid provider/model is configured. Check your OpenCode setup:

```bash
opencode providers
opencode models
```

### Process Hangs

OpenCode's `run` command should exit after completing the prompt. If it hangs:

1. Check if OpenCode is waiting for user input (should not happen with `run`)
2. Verify OpenCode is not in interactive TUI mode
3. Increase the timeout if the task is legitimately long-running

## Comparison With Other Agents

| Agent | Command | Auto-Approval | Notes |
|-------|---------|---------------|-------|
| opencode | `opencode run <prompt>` | Via yolo mode config | Open-source, configurable |
| codex | `codex exec --full-auto` | `--full-auto` flag | OpenAI |
| claude | `claude --dangerously-skip-permissions` | `--dangerously-skip-permissions` | Anthropic |
| gemini | `gemini --yolo` | `--yolo` flag | Google |
| copilot | `copilot -p <prompt>` | Requires pre-auth | GitHub |
