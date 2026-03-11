# QA Test Generation Fixture

이 디렉터리는 QA 테스트 생성 에이전트 파이프라인을 재현 가능한 형태로 묶은 실행용 fixture 입니다.

## 파일 구성

- `qa-test-generation.yaml`: 테스트 생성 + QA 리뷰 + 자동 보정 루프 파이프라인
- `target-feature.md`: 테스트 대상 기능 요구사항
- `sample/UserService.kt`: 테스트 생성 프롬프트에 포함할 코드 맥락 샘플

## 실행 예시

```bash
./shell/cotor validate qa-test-generation -c test/qa-test-generation/qa-test-generation.yaml
./shell/cotor run qa-test-generation -c test/qa-test-generation/qa-test-generation.yaml --output-format text
```

## 기대 동작

1. `analyze-target` 가 테스트 전략을 정리합니다.
2. `generate-tests` 가 Kotlin/JUnit5/MockK 테스트 초안을 작성합니다.
3. `review-tests` 가 QA 관점에서 PASS/RETRY 를 판정합니다.
4. `quality-gate` 와 `qa-loop` 가 RETRY 인 경우 최대 2회까지 테스트 초안을 다시 생성합니다.
