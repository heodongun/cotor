# Cotor Web Editor

The web editor is the local browser surface for authoring and running pipeline YAML.

## Start

```bash
./gradlew run --args='web --open'
```

Or choose a port explicitly:

```bash
./gradlew run --args='web --port 9090 --open'
```

Read-only mode:

```bash
./gradlew run --args='web --read-only --port 9090'
```

## What It Does

- create and edit pipelines in the browser
- apply current built-in templates
- configure stage order, execution mode, and dependencies
- preview generated YAML
- save under `.cotor/web/*.yaml`
- run saved pipelines locally

## Current Scope

The web editor is for pipeline authoring and execution. The autonomous-company goal/issue/review-queue workflow currently lives in the macOS desktop shell and `app-server`, not in the browser editor.
