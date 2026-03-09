# Local Stack Control App

로컬에서 OpenClaw / Symphony / Jagalchi 서버를 버튼으로 켜고 끄는 컨트롤 앱.

## 왜 `127.0.0.1:18777` 이 안 보였나?

이 페이지는 **서버가 실행 중일 때만** 보입니다. 즉, 먼저 아래 명령으로 앱을 띄워야 해요.

## 웹 대시보드 실행

```bash
cd ~/.openclaw/workspace
python3 experiments/local-control-app/control_app.py
```

브라우저에서 열기:

- http://127.0.0.1:18777

## 이번 버전에 추가된 기능

- **서비스 자동탐지**: 템플릿별 상태를 한 번에 확인
- **템플릿 추가/삭제 UI**: 프로세스/도커 이름 패턴 등록
- **원클릭 전체 정리(cleanup)** 버튼
- OpenClaw gateway: start / status / stop / restart
- Symphony docker 컨테이너(name에 `symphony` 포함): status / stop / kill
- Jagalchi runserver(`manage.py runserver`): status / stop / kill

## Menubar 앱(상단바)

### 실행(개발 모드)

```bash
python3 experiments/local-control-app/tray_app.py
```

- 상단바에 🧰 아이콘 표시
- `Open Dashboard` 로 웹 UI 열기
- Quick Action으로 즉시 stop/kill 가능

## .app 빌드 (진짜 앱처럼)

```bash
cd ~/.openclaw/workspace/experiments/local-control-app
chmod +x build_macos_app.sh
./build_macos_app.sh
```

결과물:

- `experiments/local-control-app/dist/LocalStackControl.app`

원하면 Finder에서 `/Applications`로 옮겨서 Docker Desktop처럼 앱으로 실행 가능.

## 참고

- Docker Desktop이 안 켜져 있으면 Symphony 쪽은 에러 출력
- 웹 서버는 `0.0.0.0:18777`로 바인딩 (로컬 네트워크 접근 가능)
