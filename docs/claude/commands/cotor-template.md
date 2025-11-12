---
name: cotor-template
description: 템플릿에서 cotor 파이프라인 생성
category: cotor
---

# Cotor 템플릿 사용

검증된 템플릿에서 새 파이프라인을 생성합니다.

## 사용법

```
/cotor-template [템플릿명] [출력파일]
```

템플릿 목록 보기:
```
/cotor-template
```

## 사용 가능한 템플릿

### compare-solutions
여러 AI로부터 같은 문제에 대한 다른 해결책을 받습니다.

### review-chain
순차적 코드 리뷰 및 개선 체인

### comprehensive-review
다각도 병렬 코드 리뷰

## 예시

```
/cotor-template compare-solutions my-comparison.yaml
```

## 관련 커맨드

- `/cotor-generate`: AI로 파이프라인 자동 생성
- `/cotor-validate`: 생성된 파이프라인 검증
- `/cotor-execute`: 파이프라인 실행
