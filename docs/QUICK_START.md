# Cotor Quick Start

5분 안에 Cotor 파이프라인을 실행하는 최소 경로입니다.

## 10줄 요약

1. `./shell/install-global.sh` 또는 `./gradlew shadowJar && ./shell/cotor version`
2. `cotor init --interactive`
3. `cotor template --list`
4. `cotor template compare my-pipeline.yaml --fill prompt="Write tests"`
5. `cotor validate compare-solutions -c my-pipeline.yaml`
6. `cotor run compare-solutions -c my-pipeline.yaml --dry-run`
7. `cotor run compare-solutions -c my-pipeline.yaml --output-format text`
8. `cotor stats`
9. `cotor doctor`
10. `cotor dash -c my-pipeline.yaml` 또는 `cotor web`

## Prerequisites

- Java 17+
- 사용하려는 AI CLI(claude, gemini, codex, copilot 등)

## 설치

```bash
git clone https://github.com/yourorg/cotor.git
cd cotor
./gradlew shadowJar
chmod +x shell/cotor
./shell/cotor version
```

## macOS 앱을 바로 내려받기

```bash
./shell/install-desktop-app.sh
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

이 명령은 `.app` 번들을 빌드해 `/Applications` 또는 `~/Applications`에 설치하고, `~/Downloads/Cotor Desktop.app`와 `~/Downloads/Cotor-Desktop-macOS.zip`도 갱신합니다.

## 첫 파이프라인 만들기

### 1) 템플릿 생성

```bash
cotor template compare my-pipeline.yaml --fill prompt="Summarize Kotlin coroutines"
```

### 2) 유효성 검사

```bash
cotor validate compare-solutions -c my-pipeline.yaml
```

### 3) 드라이런 (예상 시간/흐름 확인)

```bash
cotor run compare-solutions -c my-pipeline.yaml --dry-run
```

### 4) 실제 실행

```bash
cotor run compare-solutions -c my-pipeline.yaml --watch --output-format text
```

## 자주 쓰는 명령

```bash
cotor list -c my-pipeline.yaml
cotor status
cotor stats
cotor stats compare-solutions
cotor checkpoint gc --dry-run
cotor resume <checkpoint-id> -c my-pipeline.yaml
cotor lint my-pipeline.yaml
cotor explain my-pipeline.yaml compare-solutions
```

## 에이전트/플러그인 관리

```bash
cotor agent add claude --yes
cotor agent list
cotor plugin list
cotor plugin validate
cotor plugin init my-plugin
```

## 문제 해결

- 환경 점검: `cotor doctor`
- 상세 에러 확인: 명령 뒤에 `--debug`
- 빠른 도움말: `cotor --short`, `cotor --help`

## 다음 단계

- 데스크톱 앱 상세: `docs/DESKTOP_APP.md`
- 다양한 템플릿: `docs/FEATURES.md`
- 실전 예제: `examples/`
- 아키텍처: `docs/ARCHITECTURE.md`
