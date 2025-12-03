# Cotor Web / No-Code Editor

웹 에디터는 파이프라인을 드래그·드롭으로 구성하고, 템플릿을 불러와 바로 저장/실행할 수 있는 UI입니다.

## 시작하기

```bash
# 서버 실행 (기본 8080 포트)
./gradlew shadowJar
java -jar build/libs/cotor-1.0.0.jar web --open

# 포트 변경
java -jar build/libs/cotor-1.0.0.jar web --port 9090 --open
```

브라우저가 자동으로 열리지 않으면 `http://localhost:8080/editor` 로 접속하세요.

## 주요 기능

- 템플릿 바로 적용 (비교, 체인, 리뷰, DAG 팬아웃/머지, 자기치유 루프)
- 스테이지 카드 드래그·드롭으로 순서 변경
- 병렬/SEQUENTIAL/DAG 모드 설정 및 스테이지 간 dependencies 설정
- YAML 프리뷰/내보내기, 저장, 실행 결과 타임라인 확인
- 저장된 파이프라인 목록 불러오기 및 재실행

## 파일 저장 위치

- 에디터에서 저장된 파이프라인은 `.cotor/web/<pipeline>.yaml` 에 저장됩니다.
- 생성된 YAML은 `cotor run <pipeline> -c .cotor/web/<pipeline>.yaml` 로 CLI에서도 실행할 수 있습니다.

## 팁

- 에이전트 이름을 `claude`, `gemini`, `codex`, `copilot`, `cursor`, `opencode` 로 입력하면 자동으로 스텁 플러그인이 매핑됩니다(외부 CLI 없이도 동작).
- 종속성(dependencies)은 `DAG` 모드에서만 사용됩니다.
- 저장 후 바로 실행하면 실행 결과/소요 시간이 오른쪽 패널에 표시됩니다.
