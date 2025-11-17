name: cotor-generate

description: 목표 설명에서 cotor 파이프라인 YAML 생성

category: cotor

---

# Cotor 파이프라인 생성

사용자의 목표 설명을 받아 cotor 파이프라인 YAML을 생성합니다.

## 사용법

```

/cotor-generate [목표 설명]

```

## 동작

1. 사용자의 목표 설명 분석

2. 적절한 AI 에이전트 선택

3. 파이프라인 YAML 파일 생성

4. 생성된 파일 경로 표시

## 예시

```

/cotor-generate "게시판 기능 구현 - CRUD 작업"

```

이 명령어는 게시판 CRUD 기능을 구현하기 위한 파이프라인 YAML을 생성합니다.

## 구현

cotor는 설정 파일 기반 시스템이므로, 목표에 맞는 YAML 파일을 직접 생성합니다:

```yaml

version: "1.0"

agents:

  - name: claude

    pluginClass: com.cotor.data.plugin.ClaudePlugin

    timeout: 60000

pipelines:

  - name: board-crud

    description: "게시판 CRUD 기능 구현"

    executionMode: SEQUENTIAL

    stages:

      - id: design

        agent:

          name: claude

        input: "게시판 CRUD API 설계 (생성, 조회, 수정, 삭제)"

      

      - id: implement

        agent:

          name: claude

        input: "위 설계를 바탕으로 코드 구현"

security:

  useWhitelist: true

  allowedExecutables: [claude]

  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin]

```

## 빠른 시작

대신 템플릿을 사용하는 것을 권장합니다:

```

/cotor-template compare-solutions board-crud.yaml

```

그 다음 파일을 편집하여 목표에 맞게 수정하세요.

## 관련 커맨드

- `/cotor-template`: 템플릿에서 파이프라인 생성 (권장)

- `/cotor-validate`: 생성된 파이프라인 검증

- `/cotor-execute`: 파이프라인 실행

