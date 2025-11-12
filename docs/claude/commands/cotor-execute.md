---
name: cotor-execute
description: cotor 파이프라인 실행 및 모니터링
category: cotor
---

# Cotor 파이프라인 실행

파이프라인 파일을 실행하고 실시간 모니터링을 제공합니다.

## 사용법

```
/cotor-execute [파일경로]
```

## 동작

1. 파이프라인 파일 경로 확인
2. `cotor execute [파일] --monitor` 실행
3. 실행 출력 실시간 스트리밍
4. 완료 시 최종 상태 및 결과 표시

## 예시

```
/cotor-execute test/multi-compare.yaml
```

## 관련 커맨드

- `/cotor-generate`: 파이프라인 생성
- `/cotor-validate`: 실행 전 검증
- `/cotor-template`: 템플릿에서 파이프라인 생성
