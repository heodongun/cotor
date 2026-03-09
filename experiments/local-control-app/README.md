# Local Stack Control App

로컬에서 OpenClaw / Symphony / Jagalchi 서버를 버튼으로 켜고 끄는 간단한 컨트롤 앱.

## 실행

```bash
cd ~/.openclaw/workspace
python3 experiments/local-control-app/control_app.py
```

브라우저에서 열기:

- http://127.0.0.1:18777

## 제공 기능

- OpenClaw gateway: start / status / stop / restart
- Symphony docker 컨테이너(name에 `symphony` 포함): status / stop / kill
- Jagalchi runserver(`manage.py runserver`): status / stop / kill

## 참고

- Docker Desktop이 안 켜져 있으면 Symphony 쪽은 에러 출력됨
- 보안상 localhost(127.0.0.1)에서만 바인딩
