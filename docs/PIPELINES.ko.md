# 파이프라인 메모

원문: [PIPELINES.md](PIPELINES.md)

이 문서는 `cotor-project`용 starter scaffold를 설명합니다.

## 기본 워크플로

- 파이프라인 이름: `cotor-project-starter`
- 실행 모드: `SEQUENTIAL`
- 에이전트: `codex`
- 스테이지:
  - `brief`: 프로젝트를 위한 짧은 계획 생성
  - `refine`: 계획을 실행 가능한 체크리스트로 정리

## 편집 가이드

1. 스테이지 프롬프트를 실제 작업에 맞게 바꿉니다.
2. fan-out이나 review 흐름이 필요하면 실행 모드를 바꿉니다.
3. starter 워크플로를 넘어서면 스테이지를 더 추가합니다.

## 검증 안내

scaffold를 바꾼 뒤에는 아래 명령으로 검증합니다.

```bash
cotor validate cotor-project-starter -c cotor.yaml
```
