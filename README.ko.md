# Cotor - AI CLI 마스터-에이전트 시스템

[![Version](https://img.shields.io/badge/version-1.0.0-blue)](https://github.com/yourusername/cotor)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.0-purple)](https://kotlinlang.org/)
[![JVM](https://img.shields.io/badge/jvm-23-orange)](https://openjdk.org/)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

Cotor는 여러 AI 에이전트를 하나의 CLI로 오케스트레이션하는 Kotlin 기반 도구입니다. 순차, 병렬, DAG 실행 모드를 지원하며, 실시간 모니터링과 포괄적인 검증 기능을 제공합니다.

## ✨ 주요 기능

- 🚀 **다중 모드 실행**: 순차, 병렬, DAG 워크플로우
- 📊 **실시간 모니터링**: 진행 상황 추적 및 타임라인 시각화
- ✅ **검증 시스템**: 실행 전 파이프라인 검증 및 보안 검사
- 🔖 **체크포인트 & 재개**: 파이프라인 실행 상태 저장 및 복원
- 📈 **통계 & 분석**: 자동 성능 추적 및 트렌드 분석
- 📝 **템플릿 시스템**: 5가지 내장 템플릿
- 🩺 **Doctor 명령**: 환경 진단 및 상태 점검
- 🌐 **웹 & TUI**: 브라우저 기반 UI 및 터미널 대시보드
- 🔒 **보안**: 화이트리스트 기반 실행 제어
- 🎨 **사용자 친화적**: 컬러 출력, 도움이 되는 오류 메시지 및 제안

## 📚 문서

### 빠른 링크
- [📖 영문 가이드](docs/README.md)
- [📖 한글 가이드](docs/README.ko.md)
- [🚀 빠른 시작](docs/QUICK_START.md)
- [🖥️ 데스크톱 앱](docs/DESKTOP_APP.md)
- [🧭 팀 운영 온보딩](docs/team-ops/README.ko.md)
- [🗂️ 역할별 운영 템플릿](docs/team-ops/templates.ko.md)
- [⚡ 기능 목록](docs/FEATURES.md)
- [📑 문서 인덱스](docs/INDEX.md)

### 테스트 리포트
- [✅ **실제 실행 테스트**](test-results/LIVE_TEST_RESULTS.md) - 실제 동작 확인 (신규!)
- [📊 테스트 요약](test-results/README.md) - 빠른 확인
- [🧪 상세 테스트](docs/reports/FEATURE_TEST_REPORT_v1.0.0.md) - 포괄적 테스트

### 추가 자료
- [📋 릴리스 노트](docs/release/CHANGELOG.md)
- [🔧 업그레이드 가이드](docs/UPGRADE_GUIDE.md)
- [🤖 Claude 연동](docs/CLAUDE_SETUP.md)
- [💡 사용 팁](docs/USAGE_TIPS.md)
- [📦 예제](examples/)

## 🖥️ macOS 앱 다운로드

네이티브 macOS 셸을 빌드하고 `응용 프로그램`에 설치하면서 `Downloads`용 배포본까지 같이 만들려면:

```bash
./shell/install-desktop-app.sh
```

스크립트가 하는 일:

- 아이콘과 번들 메타데이터가 들어간 `Cotor Desktop.app` 생성
- 쓰기 권한이 있으면 `/Applications`, 아니면 `~/Applications`에 설치
- `~/Downloads/Cotor Desktop.app`와 `~/Downloads/Cotor-Desktop-macOS.zip` 갱신

설치된 앱 실행:

```bash
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

앱은 필요할 때 로컬 `cotor app-server`를 자동으로 띄웁니다. 자세한 데스크톱 사용법은 [docs/DESKTOP_APP.md](docs/DESKTOP_APP.md)를 참고하세요.

## 🚀 빠른 시작

### 방법 1: 전역 설치 (권장)

```bash
git clone https://github.com/yourusername/cotor.git
cd cotor
./shell/install-global.sh
cotor version
```

### 방법 2: 로컬 사용

```bash
./gradlew shadowJar
chmod +x shell/cotor
./shell/cotor version
```

### 방법 3: Docker

```bash
docker run -it cotor/cli version
```

### 방법 4: 로컬 macOS 데스크톱 다운로드

```bash
./shell/install-desktop-app.sh
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

## 📖 기본 사용법

```bash
# 1. 설정 파일 생성
cotor init

# 2. 사용 가능한 에이전트 목록 확인
cotor list

# 3. 파이프라인 검증
cotor validate example-pipeline

# 4. 파이프라인 실행
cotor run example-pipeline --output-format text

# 5. 통계 확인
cotor stats

# 6. 환경 점검
cotor doctor
```

## 💻 CLI 명령어

### 핵심 명령어
| 명령어 | 설명 | 예제 |
|--------|------|------|
| `init` | 설정 파일 생성 | `cotor init --interactive` |
| `list` | 등록된 에이전트 표시 | `cotor list -c cotor.yaml` |
| `run` | 파이프라인 실행 | `cotor run my-pipeline --verbose` |
| `validate` | 파이프라인 검증 | `cotor validate my-pipeline` |
| `version` | 버전 정보 표시 | `cotor version` |

### 고급 명령어
| 명령어 | 설명 | 예제 |
|--------|------|------|
| `doctor` | 환경 진단 | `cotor doctor` |
| `stats` | 통계 표시 | `cotor stats my-pipeline` |
| `template` | 템플릿 관리 | `cotor template compare out.yaml` |
| `checkpoint` | 체크포인트 관리 | `cotor checkpoint` |
| `resume` | 체크포인트에서 재개 | `cotor resume <id>` |
| `dash` | TUI 대시보드 | `cotor dash` |
| `interactive` | 마스터 에이전트 대화형 채팅 (TUI) | `cotor interactive` |
| `tui` | `interactive` 별칭 | `cotor tui` |
| `web` | 웹 인터페이스 | `cotor web` |
| `completion` | 쉘 자동완성 | `cotor completion zsh` |

### 빠른 도움말
```bash
cotor              # 기본 TUI(interactive) 실행
cotor --short      # 10줄 치트시트
cotor --help       # 전체 명령어 도움말
```

### 기본 TUI 진입
- 인자 없이 `cotor`를 실행하면 interactive TUI로 바로 진입합니다.
- 현재 폴더에 `cotor.yaml`이 없으면 starter 설정 파일을 자동 생성합니다.
  (기본 우선순위: codex → gemini → claude → openai → echo)
- Interactive TUI에서 `:model <name>`으로 모델(에이전트) 전환 가능
- `cotor tui`도 `cotor interactive`와 동일하게 동작합니다.

## 📦 예제

`examples/`에서 바로 실행 가능한 예제:

```bash
# 단일 에이전트 예제
./shell/cotor run single-agent -c examples/single-agent.yaml

# 병렬 비교
./shell/cotor run parallel-compare -c examples/parallel-compare.yaml

# 의사결정 및 루프
./shell/cotor run decision-loop -c examples/decision-loop.yaml

# 모든 예제 실행
./examples/run-examples.sh
```

## 🔧 설정

`cotor.yaml` 생성:

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
    description: "나의 첫 파이프라인"
    executionMode: SEQUENTIAL
    stages:
      - id: step1
        agent:
          name: my-agent
        input: "이 코드를 분석해줘"

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

### 다중 설정 파일 병합

Cotor는 여러 설정 파일을 병합하여 최종 설정을 구성하는 기능을 지원합니다. 이를 통해 기본 설정을 유지하면서 특정 환경에 맞는 설정을 덮어쓸 수 있습니다.

#### 병합 규칙

1.  **기본 설정 파일**: `cotor.yaml` 파일을 가장 먼저 읽어 기본 설정으로 사용합니다.
2.  **오버라이드 파일**: `.cotor/` 디렉토리 내의 모든 `*.yaml` 또는 `*.yml` 파일을 이름순으로 정렬하여 순서대로 읽어옵니다.
3.  **병합 우선순위**: 나중에 읽어온 파일의 설정이 이전에 읽어온 파일의 설정을 덮어씁니다. 예를 들어, `a.yaml`과 `b.yaml`이 있다면, `b.yaml`의 설정이 `a.yaml`의 설정을 덮어씁니다.

#### 병합 방식

-   **`agents` 및 `pipelines`**: 이름(`name`)을 기준으로 병합됩니다. 같은 이름의 에이전트나 파이프라인이 여러 파일에 정의된 경우, 가장 나중에 읽어온 파일의 정의가 사용됩니다.
-   **`logging`, `security`, `performance`**: 객체 내부의 각 필드별로 병합됩니다. 예를 들어, `logging.level`만 오버라이드 파일에 정의하면, `logging.file`과 같은 다른 필드들은 기본 설정 파일의 값을 그대로 유지합니다.
-   **`version`**: 가장 나중에 읽어온 파일의 `version` 값이 최종적으로 사용됩니다.

## 🧪 테스트

```bash
# 단위 테스트 실행
./gradlew test

# 통합 테스트 실행
./shell/test-cotor-enhanced.sh
./shell/test-cotor-pipeline.sh
./shell/test-claude-integration.sh

# Dry run (시뮬레이션)
cotor run my-pipeline --dry-run
```

## 🤝 통합

### Claude Code 통합

```bash
./shell/install-claude-integration.sh
```

Claude Code용 슬래시 커맨드와 지식 베이스를 추가합니다.

### CI/CD 통합

```yaml
# GitHub Actions 예제
- name: Cotor 파이프라인 실행
  run: |
    ./shell/cotor run build-and-test \
      -c .cotor/ci-pipeline.yaml \
      --output-format json
```

## 📊 아키텍처

```
cotor/
├── src/main/kotlin/com/cotor/
│   ├── Main.kt                          # 진입점
│   ├── domain/
│   │   ├── orchestrator/                # 파이프라인 실행
│   │   ├── executor/                    # 에이전트 실행
│   │   └── condition/                   # 조건부 로직
│   ├── presentation/
│   │   ├── cli/                         # CLI 명령어
│   │   ├── web/                         # 웹 UI
│   │   └── formatter/                   # 출력 포매팅
│   ├── monitoring/                      # 실시간 모니터링
│   ├── checkpoint/                      # 체크포인트 시스템
│   ├── stats/                           # 통계
│   └── validation/                      # 파이프라인 검증
├── examples/                            # 예제 파이프라인
├── docs/                                # 문서
└── shell/                               # 쉘 스크립트
```

## 🛠️ 개발

### 필수 요구사항
- JDK 17 이상
- Kotlin 2.1.0
- Gradle 8.5

### 빌드

```bash
./gradlew clean build shadowJar
```

### 테스트 실행

```bash
./gradlew test
./gradlew jacocoTestReport  # 커버리지 리포트
```

### 코드 포맷팅

```bash
./gradlew format       # Spotless + ktlint 포맷 적용
./gradlew formatCheck  # 포맷 위반이 있으면 실패
./shell/auto-fix-lint.sh     # 린트/포맷 자동 수정 스크립트
./shell/install-git-hooks.sh # 커밋 시 자동 린트 수정을 위한 pre-commit 훅 설치
```

## 📈 로드맵

### v1.1.0 (다음 버전)
- [ ] Resume 기능 완성
- [ ] 향상된 웹 UI
- [ ] 추가 템플릿
- [ ] 성능 최적화

### v1.2.0
- [ ] 클라우드 실행 지원
- [ ] 고급 조건부 로직
- [ ] 동적 파이프라인 생성
- [ ] 더 많은 AI CLI 통합

### v2.0.0 (장기 계획)
- [ ] 분산 실행
- [ ] ML 통합
- [ ] 고급 시각화
- [ ] 엔터프라이즈 기능

## 🤝 기여하기

기여를 환영합니다! 먼저 [기여 가이드](CONTRIBUTING.md)를 읽어주세요.

## 📄 라이선스

이 프로젝트는 MIT 라이선스 하에 있습니다 - 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.

## 🙏 감사의 말

- [Kotlin](https://kotlinlang.org/)으로 제작
- [Clikt](https://ajalt.github.io/clikt/) CLI 프레임워크 사용
- [Mordant](https://ajalt.github.io/mordant/) 터미널 UI 사용
- [Koin](https://insert-koin.io/) 의존성 주입 사용

## 📞 지원

- 📧 이메일: support@cotor.io
- 💬 Discord: [커뮤니티 참여](https://discord.gg/cotor)
- 🐛 이슈: [GitHub Issues](https://github.com/yourusername/cotor/issues)
- 📖 Wiki: [문서](https://github.com/yourusername/cotor/wiki)

---

**Cotor 팀이 ❤️를 담아 만들었습니다**
