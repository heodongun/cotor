# Cotor Quick Start

이 문서는 현재 코드 기준으로 가장 빠른 시작 경로만 정리합니다.

## Homebrew 설치 (추천)

```bash
# 원라이너 설치 (JDK 17 + CLI 설치, 데스크톱 앱 번들 포함)
curl -fsSL https://raw.githubusercontent.com/bssm-oss/cotor/master/shell/brew-install.sh | bash
```

또는 수동으로:

```bash
brew tap bssm-oss/cotor https://github.com/bssm-oss/cotor.git
brew install cotor
```

설치 확인:

```bash
cotor version
cotor install
open "/Applications/Cotor Desktop.app"
```

참고:
- Homebrew formula는 packaged desktop bundle을 함께 설치하지만, Applications 복사는 `cotor install`을 사용자 셸에서 명시적으로 실행할 때 수행됩니다.
- `cotor install`은 실제 설치된 앱 경로를 출력하고, `/Applications`에 쓸 수 없으면 자동으로 `~/Applications`를 사용합니다.
- `brew install cotor` 뒤 첫 `cotor` 실행에서 로컬 `cotor.yaml`이 없으면 starter config는 `~/.cotor/interactive/default/cotor.yaml` 아래에 생성됩니다.
- packaged install의 자세한 첫 실행 규칙과 문제 해결은 `docs/HOMEBREW_INSTALL.md` / `docs/HOMEBREW_INSTALL.ko.md`를 참고하세요.
- 이 starter config는 실제로 바로 응답 가능한 AI CLI 또는 API 키만 자동 채택하고, 준비되지 않은 CLI는 starter 후보에서 제외합니다.

업데이트:

```bash
brew upgrade cotor
```

## 소스에서 설치

1. `git clone https://github.com/bssm-oss/cotor.git`
2. `cd cotor`
3. `./shell/cotor version`  (JDK 17 자동 감지, shadowJar 자동 빌드)

## macOS Desktop App

```bash
cotor install    # Homebrew면 번들 앱 설치, 소스 체크아웃이면 로컬 빌드 후 설치
cotor update     # Homebrew면 번들 재설치, 소스 체크아웃이면 리빌드 후 재설치
cotor delete     # 삭제
```

## app-server Fast Path

```bash
./gradlew run --args='app-server --port 8787'
```

별도 셸에서:

```bash
swift run --package-path macos CotorDesktopApp
```

## 자율 운영 컴퍼니 빠른 확인

현재 빌드 기준 최소 확인 흐름:

1. 데스크톱 앱 또는 `app-server` 실행
2. 회사 목표 생성
3. 생성된 이슈 확인
4. 이슈 실행
5. 리뷰 큐 확인
6. 런타임 시작/상태 확인

## 자주 쓰는 명령

```bash
cotor
cotor help
cotor help --lang en
cotor --short
cotor list -c cotor.yaml
cotor status
cotor stats
cotor checkpoint gc --dry-run
cotor lint cotor.yaml
cotor explain cotor.yaml <pipeline>
cotor web --open
cotor app-server --port 8787
```

## 주의할 점

- `resume`은 현재 체크포인트 확인 기능입니다. 실제 재개는 아직 아닙니다.
- `plugin`은 현재 `plugin init`만 실제 동작합니다.
- `Linear` 동기화는 현재 플레이스홀더입니다.
