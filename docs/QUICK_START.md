# Cotor Quick Start

이 문서는 현재 코드 기준으로 가장 빠른 시작 경로만 정리합니다.

## 10-Step Fast Path

1. `git clone https://github.com/yourusername/cotor.git`
2. `cd cotor`
3. `./gradlew shadowJar`
4. `chmod +x shell/cotor`
5. `./shell/cotor version`
6. `cotor init --starter-template`
7. `cotor template --list`
8. `cotor validate <pipeline> -c <config>`
9. `cotor run <pipeline> -c <config> --output-format text`
10. `cotor doctor`

## macOS Desktop Fast Path

```bash
./shell/install-desktop-app.sh
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

이 경로는:

- `.app` 번들을 빌드하고
- `/Applications` 또는 `~/Applications`에 설치하고
- `Downloads`용 zip도 함께 갱신합니다

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
