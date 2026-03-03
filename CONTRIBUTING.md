# Contributing Guide

[한국어 안내는 아래로 이동](#기여-가이드)

Thank you for contributing to Cotor.

## Pull Request Checklist

- [ ] Describe **what changed** and **why**.
- [ ] Run relevant tests or checks locally.
- [ ] Keep docs aligned with behavior changes.

## Documentation Synchronization Rule (EN/KR)

When adding or changing any **feature, option, command, flag, workflow, or behavior**, you **must** update both English and Korean guides in the same PR.

### Required Sync Checklist

- [ ] `README.md` and `README.ko.md` contain equivalent feature/usage information.
- [ ] `docs/README.md` and `docs/README.ko.md` are both updated when onboarding/overview changes.
- [ ] Any newly introduced command/option appears in both language docs (or is clearly marked as pending translation with follow-up issue).
- [ ] Examples/snippets reflect the same capabilities in both languages.
- [ ] If one language intentionally differs, the PR description explains why and links a follow-up task.

## Suggested Process

1. Make code changes.
2. Update English docs.
3. Mirror the same functional content in Korean docs.
4. Self-review using the checklist above before opening/merging the PR.

---

## 기여 가이드

Cotor에 기여해 주셔서 감사합니다.

## PR 체크리스트

- [ ] **무엇이** 변경되었는지와 **왜** 변경했는지 설명합니다.
- [ ] 관련 테스트/점검을 로컬에서 실행합니다.
- [ ] 동작 변경 시 문서도 함께 맞춰 갱신합니다.

## 문서 동기화 규칙 (한/영)

새로운 **기능, 옵션, 명령어, 플래그, 워크플로, 동작**을 추가하거나 변경할 때는, 같은 PR에서 영문/국문 가이드를 **반드시 함께** 업데이트해야 합니다.

### 필수 동기화 체크리스트

- [ ] `README.md` / `README.ko.md`에 기능/사용법 정보가 기능적으로 동일하게 반영되어 있습니다.
- [ ] 온보딩/개요 변경 시 `docs/README.md` / `docs/README.ko.md`를 함께 업데이트했습니다.
- [ ] 새 명령어/옵션이 양쪽 언어 문서에 모두 반영되어 있습니다. (불가 시 번역 예정임을 명시하고 후속 이슈를 연결)
- [ ] 예제/스니펫이 양쪽 문서에서 동일한 기능 범위를 설명합니다.
- [ ] 의도적으로 언어별 차이가 있는 경우, PR 설명에 이유와 후속 작업 링크를 남겼습니다.

## 권장 작업 순서

1. 코드 변경
2. 영문 문서 업데이트
3. 국문 문서에 동일한 기능 정보 반영
4. PR 전 체크리스트로 자체 점검
