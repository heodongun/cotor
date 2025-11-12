---
name: cotor-generate
description: 목표 설명에서 cotor 파이프라인 자동 생성
category: cotor
---

# Cotor 파이프라인 생성

이 커맨드는 사용자의 목표 설명을 받아 cotor 파이프라인 YAML을 자동으로 생성합니다.

## 사용법

```
/cotor-generate [목표 설명]
```

## 동작

1. 사용자로부터 목표 설명 입력 받기
2. `cotor generate "[목표]" --dry-run` 실행
3. 생성된 YAML 내용 표시
4. 오류 발생 시 상세한 오류 메시지와 해결 방법 표시

## 예시

```
/cotor-generate 3개의 AI로 소수 찾기 함수 비교
```

이 명령어는 Claude, Gemini, Codex를 사용하여 소수 찾기 함수를 생성하고 비교하는 파이프라인을 자동으로 생성합니다.

## 구현

```bash
#!/bin/bash

# 사용자 목표 받기
USER_GOAL="$1"

if [ -z "$USER_GOAL" ]; then
  echo "❌ 오류: 목표 설명을 입력해주세요."
  echo "사용법: /cotor-generate [목표 설명]"
  exit 1
fi

# cotor generate 실행 (dry-run 모드)
echo "🚀 파이프라인 생성 중: $USER_GOAL"
echo ""

cotor generate "$USER_GOAL" --dry-run

# 실행 결과 확인
if [ $? -eq 0 ]; then
  echo ""
  echo "✅ 파이프라인이 성공적으로 생성되었습니다!"
  echo ""
  echo "다음 단계:"
  echo "1. 생성된 YAML을 파일로 저장"
  echo "2. /cotor-validate로 검증"
  echo "3. /cotor-execute로 실행"
else
  echo ""
  echo "❌ 파이프라인 생성 실패"
  echo ""
  echo "해결 방법:"
  echo "- 목표를 더 구체적으로 작성해보세요"
  echo "- cotor CLI가 설치되어 있는지 확인하세요"
  echo "- 로그 확인: cat cotor.log"
fi
```

## 오류 처리

### 목표 설명 없음
```
❌ 오류: 목표 설명을 입력해주세요.
사용법: /cotor-generate [목표 설명]
```

### Cotor CLI 없음
```
❌ 파이프라인 생성 실패
cotor 명령어를 찾을 수 없습니다.

해결 방법:
1. cotor 설치 확인: which cotor
2. PATH 설정 확인
3. 설치 가이드: README.md 참조
```

### 생성 실패
```
❌ 파이프라인 생성 실패

해결 방법:
- 목표를 더 구체적으로 작성해보세요
- 사용할 AI 에이전트를 명시해보세요
- 예: "Claude와 Gemini로 Python 정렬 알고리즘 비교"
```

## 팁

- **구체적인 목표**: "코드 리뷰"보다 "Claude와 Gemini로 보안 취약점 검토"가 더 좋습니다
- **AI 명시**: 사용할 AI를 명시하면 더 정확한 파이프라인이 생성됩니다
- **작업 유형**: "비교", "순차 리뷰", "병렬 분석" 등의 키워드를 포함하세요

## 관련 커맨드

- `/cotor-validate`: 생성된 파이프라인 검증
- `/cotor-execute`: 파이프라인 실행
- `/cotor-template`: 템플릿에서 파이프라인 생성
