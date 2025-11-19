# Changelog

## [Unreleased] - 2025-11-20

### Added
- **Template Generation Command**: New `cotor template` command for quickly creating pipeline configurations
  - 5 pre-built templates: compare, chain, review, consensus, custom
  - Automatic YAML generation with best practices
  - Clear next-steps guidance after template creation
  - Usage: `cotor template <type> [output-file]`

### Improved
- **Duplicate Output Prevention**: Added state hash tracking to `PipelineMonitor`
  - Prevents identical progress bars from being rendered multiple times
  - Reduces console spam during pipeline execution
  - Smart rendering only when actual state changes occur

### Fixed
- Import issues in template generation module

### Documentation
- Created `IMPROVEMENTS.md` - Comprehensive improvement roadmap
- Created `TEST_REPORT.md` - Detailed testing results and analysis
- Added usage examples for new template command

---

## [1.0.0] - 2025-11-19

### Added
- Initial release with core features:
  - Sequential, Parallel, and DAG execution modes
  - Real-time pipeline monitoring
  - Timeline tracking
  - Result aggregation and consensus analysis
  - Web UI for pipeline management
  - Codex-style dashboard
  - Multiple AI integrations (Claude, Gemini, etc.)
  - Comprehensive security features
  - Validation system
  - Recovery mechanisms

### AI Plugins
- Claude Plugin
- Gemini Plugin
- Codex Plugin (terminal required)
- Copilot Plugin
- Cursor Plugin
- OpenCode Plugin
- Echo Plugin (for testing)

### Commands
- `cotor init` - Initialize configuration
- `cotor run` - Execute pipeline
- `cotor dash` - Codex-style dashboard
- `cotor validate` - Validate pipeline
- `cotor test` - Run tests
- `cotor list` - List agents
- `cotor status` - Show status
- `cotor version` - Version info
- `cotor web` - Start web UI

---

## Upcoming Features

### Phase 1: Immediate Improvements
- [ ] Progress bar debouncing for smoother updates
- [ ] Enhanced error messages with actionable suggestions
- [ ] Interactive template generation

### Phase 2: User Experience
- [ ] Pipeline resume/checkpoint functionality
- [ ] Spinner animations for long-running tasks
- [ ] Execution statistics dashboard

### Phase 3: Advanced Features
- [ ] ML-based execution time prediction
- [ ] Pipeline comparison tools
- [ ] Enhanced web UI with real-time monitoring
- [ ] Advanced dry-run estimates

---

## Migration Guide

### From v1.0.0 to Unreleased

No breaking changes. New features are fully backward compatible.

**New Commands Available:**
```bash
# List available templates
cotor template

# Create from template
cotor template compare my-pipeline.yaml
cotor template chain review-flow.yaml
cotor template review code-review.yaml
```

**Existing Workflows Continue to Work:**
```bash
# All existing commands unchanged
cotor run my-pipeline --config cotor.yaml
cotor validate my-pipeline
cotor dash -c config.yaml
```

---

## Contributors

- heodongun - Initial implementation and improvements
- Claude (Anthropic) - Testing, analysis, and improvement suggestions

---

## License

[Your License Here]
