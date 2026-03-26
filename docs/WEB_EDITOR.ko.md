# Cotor 웹 에디터

원문: [WEB_EDITOR.md](WEB_EDITOR.md)

웹 에디터는 브라우저에서 파이프라인 YAML을 작성하고 실행하는 로컬 표면입니다.

## 시작

```bash
./gradlew run --args='web --open'
```

포트를 직접 지정하려면:

```bash
./gradlew run --args='web --port 9090 --open'
```

읽기 전용:

```bash
./gradlew run --args='web --read-only --port 9090'
```

## 할 수 있는 일

- 브라우저에서 파이프라인 생성/수정
- 내장 템플릿 적용
- 스테이지 순서, 실행 모드, 의존성 설정
- 생성된 YAML 미리보기
- `.cotor/web/*.yaml` 아래 저장
- 저장된 파이프라인 로컬 실행

## 현재 범위

웹 에디터는 **파이프라인 작성과 실행**에 집중합니다. 자율 Company의 목표/이슈/리뷰 큐 워크플로는 현재 브라우저 에디터가 아니라 macOS 데스크톱 셸과 `app-server`에 있습니다.
