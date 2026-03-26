# Homebrew 설치 및 첫 실행 가이드

이 문서는 `brew install cotor`로 설치했을 때 실제로 어떻게 동작하는지 설명합니다.

Homebrew 기준 첫 실행을 안정적으로 이해하고 싶을 때, 어떤 파일이 어디에 만들어지는지 보고 싶을 때, 소스 빌드 없이 로컬 설치를 복구하고 싶을 때 이 문서를 기준으로 보면 됩니다.

## `brew install cotor`가 설치하는 것

`brew install cotor`는 아래를 설치합니다.

- `cotor` CLI
- JDK 17
- Homebrew prefix 안에 들어가는 packaged `Cotor Desktop.app` asset

이 단계만으로 Desktop 앱이 Applications에 복사되지는 않습니다.

설치 직후에는 아래를 한 번 더 실행해야 합니다.

```bash
cotor install
```

이 명령은 packaged desktop bundle을 아래 위치 중 하나로 복사합니다.

- `/Applications`에 쓸 수 있으면 `/Applications`
- 쓸 수 없으면 `~/Applications`

그 다음 앱은 아래처럼 열 수 있습니다.

```bash
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

## 첫 `cotor` 실행에서 일어나는 일

현재 디렉터리에 로컬 `cotor.yaml`이 없으면, packaged install은 홈 디렉터리 기반 starter config를 사용합니다.

- config 경로: `~/.cotor/interactive/default/cotor.yaml`
- transcript 경로: `~/.cotor/interactive/default/`
- 세션 디버그 로그: `~/.cotor/interactive/default/interactive.log`

첫 실행 시 이 starter config는 자동으로 생성됩니다.

starter 선택 규칙은 아래와 같습니다.

- 실제로 바로 응답 가능한 AI starter가 있으면 그걸 우선 사용
- 그렇지 않으면 안전한 `example-agent` echo starter로 폴백

즉, AI CLI가 설치만 되어 있고 인증이 안 된 상태 때문에 fresh install이 처음부터 깨지지 않도록 설계되어 있습니다.

## 로컬 config가 있으면 그게 우선입니다

이미 `cotor.yaml`이 있는 폴더에서 `cotor`를 실행하면 packaged home starter 대신 그 로컬 config를 사용합니다.

이건 의도된 동작입니다.

예를 들면:

- 빈 폴더에서 실행하면 `~/.cotor/interactive/default` 아래 starter가 생성됨
- `./cotor.yaml`이 있는 저장소에서 실행하면 그 저장소 config를 바로 사용함

## packaged install과 source checkout의 차이

### Homebrew / packaged install

- `cotor install`은 packaged app bundle을 복사합니다.
- `cotor update`는 packaged app bundle을 다시 복사합니다.
- `cotor delete`는 설치된 앱 산출물을 삭제합니다.
- 런타임에 Gradle 재빌드는 하지 않습니다.
- 런타임에 Swift 재빌드는 하지 않습니다.

### source checkout

- `cotor install`은 데스크톱 앱을 로컬에서 다시 빌드한 뒤 설치합니다.
- `cotor update`도 다시 빌드 후 재설치합니다.

## fresh install에서 막아둔 위험 경로

현재 packaged 동작은 아래 같은 상황에서도 첫 실행이 깨지지 않도록 맞춰져 있습니다.

- 현재 작업 디렉터리에 쓰기 권한이 없는 경우
- Homebrew나 sandbox가 `HOME`을 재지정한 경우
- `.cotor/worktrees/...` 아래에 큰 런타임 데이터나 관련 없는 YAML이 많은 경우
- 아직 인증된 AI CLI가 전혀 없는 경우

config loader는 아래처럼 “설정 override용 루트”만 설정으로 취급합니다.

- `~/.cotor/*.yaml`
- `~/.cotor/agents/*.yaml`
- `<project>/.cotor/*.yaml`
- `<project>/.cotor/agents/*.yaml`

반대로 runtime snapshot, worktree 복사본, editor asset, 그 외 큰 `.cotor` 하위 트리는 config로 보지 않습니다.

## 자주 쓰는 명령

데스크톱 앱 설치:

```bash
cotor install
```

packaged 앱 재설치:

```bash
cotor update
```

설치된 앱 산출물 삭제:

```bash
cotor delete
```

CLI 도움말:

```bash
cotor help
cotor help --lang ko
cotor help --lang en
```

한 번만 interactive 실행:

```bash
cotor interactive --prompt "hello"
```

## 문제 해결

### `cotor`를 실행했는데 packaged starter 대신 저장소 config를 쓴다

원인:

- 현재 폴더에 이미 `cotor.yaml`이 있습니다.

대응:

- packaged starter 흐름을 보려면 빈 폴더에서 `cotor`를 실행하세요.
- 그 저장소가 원래 작업 대상이면 로컬 `cotor.yaml`을 수정하세요.

### 앱이 설치됐는데 `/Applications`에는 없다

원인:

- `/Applications`에 쓰기 권한이 없었습니다.

대응:

- `~/Applications/Cotor Desktop.app`를 확인하세요.
- `cotor install` 출력의 `Installed:` 줄을 기준으로 보세요.

### 첫 실행이 `example-agent`로 시작한다

원인:

- 바로 응답 가능한 AI CLI나 API key가 없었습니다.

대응:

- 원하는 AI CLI를 인증하거나
- 필요한 API key를 export 한 뒤
- 다시 `cotor`를 실행하세요. 실제 AI 경로가 준비되면 starter는 다시 갱신될 수 있습니다.

### packaged starter config를 초기화하고 싶다

아래를 지우고:

```bash
rm -rf ~/.cotor/interactive/default
```

다시 실행하세요:

```bash
cotor
```

### interactive 실패 원인을 보고 싶다

아래를 확인하세요.

- packaged 기본 세션이면 `~/.cotor/interactive/default/interactive.log`
- 아니면 직접 넘긴 `--save-dir`

## packaged install 확인용 명령

간단한 smoke check:

```bash
cotor version
cotor interactive --help
cotor install
```

starter 경로를 확인하려면:

```bash
ls ~/.cotor/interactive/default
```

설치된 데스크톱 앱 경로를 확인하려면:

```bash
ls "/Applications/Cotor Desktop.app" "$HOME/Applications/Cotor Desktop.app"
```
