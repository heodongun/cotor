---
name: cotor-validate
description: cotor 파이프라인 검증
category: cotor
---

# Cotor 파이프라인 검증

파이프라인 YAML 파일의 구문과 설정을 검증합니다.

## 사용법

```
/cotor-validate [파일경로]
```

## 동작

1. 파이프라인 파일 경로 확인
2. `cotor validate [파일]` 실행
3. 검증 결과 표시

## 예시

```
/cotor-validate pipeline.yaml
```

## 관련 커맨드

- `/cotor-generate`: 파이프라인 생성
- `/cotor-execute`: 검증 후 실행
- `/cotor-template`: 검증된 템플릿 사용
