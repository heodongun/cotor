# Cotor Quick Start

이 문서는 현재 코드 기준으로 가장 빠른 시작 경로만 정리합니다.

## Homebrew 설치 (추천)

```bash
# 원라이너 설치 (JDK 17 + CLI + Desktop App 전부 설치)
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
open "/Applications/Cotor Desktop.app"
```

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
cotor install    # 빌드 + /Applications에 설치
cotor update     # 리빌드 + 재설치
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
