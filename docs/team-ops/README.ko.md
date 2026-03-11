# 팀 운영 / 온보딩 패키지

이 문서는 Cotor 저장소에서 신규 기여자와 유지보수자가 같은 방식으로 일하도록 맞추기 위한 DX 운영 핸드북입니다. 새 팀원 온보딩, 변경 작업 시작, 릴리스/문서 점검 때 기준 문서로 사용합니다.

## 이 패키지에 포함된 내용

- 첫 접근에 필요한 30분 온보딩 체크리스트
- 공통 소유권 확보를 위한 첫 주 체크리스트
- 저장소의 실제 검증 루틴에 맞춘 운영 리듬
- 진행 공유, 변경 계획, 릴리스 점검용 복사용 템플릿

## 30분 온보딩 체크리스트

- [ ] 저장소를 클론하고 기본 브랜치가 최신 상태인지 확인합니다.
- [ ] `./gradlew test`를 1회 실행해 로컬 Kotlin/Gradle 도구 체인을 검증합니다.
- [ ] `./gradlew formatCheck`로 CI와 같은 포맷 규칙을 통과하는지 확인합니다.
- [ ] `./shell/install-git-hooks.sh`로 로컬 Git hook을 설치합니다.
- [ ] `./shell/cotor version`으로 CLI 진입점이 동작하는지 확인합니다.
- [ ] [docs/README.md](../README.md), [docs/INDEX.md](../INDEX.md), [CONTRIBUTING.md](../../CONTRIBUTING.md)를 읽습니다.
- [ ] [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)의 CI 계약을 확인합니다.
- [ ] 파일을 수정하기 전에 담당 이슈 1개를 고르고 검증 경로를 먼저 적어 둡니다.

## 첫 주 소유권 체크리스트

- [ ] 동작 변경이 있으면 영문/국문 문서를 함께 갱신하는 작은 변경을 직접 완료합니다.
- [ ] 사용자 체감 워크플로가 바뀌면 [`docs/release/CHANGELOG.md`](../release/CHANGELOG.md)를 함께 업데이트합니다.
- [ ] `macos/` 또는 `shell/install-desktop-app.sh`를 건드릴 때는 데스크톱/macOS 문서도 같이 재검토합니다.
- [ ] 리뷰 요청 전 [CONTRIBUTING.md](../../CONTRIBUTING.md)의 PR 체크리스트를 다시 확인합니다.

## 팀 운영 리듬

- 일일 트리아지
  - 구현 전에 이슈 신호, 목표 검증, 문서 영향 범위를 먼저 확정합니다.
- 브랜치 푸시 전
  - `./gradlew formatCheck`와 변경을 직접 증명하는 최소 검증을 실행합니다.
- 리뷰 요청 전
  - 한/영 문서 동기화, changelog 반영 여부, CI 민감 파일 변경 여부를 다시 확인합니다.
- 릴리스 위생
  - `docs/release/CHANGELOG.md`, 상단 문서 링크, 영향받는 설치/설정 가이드가 현재 워크플로를 가리키는지 확인합니다.

## 재사용 템플릿

### 비동기 진행 공유 템플릿

```md
## Async Update

- Yesterday:
- Today:
- Risks / blockers:
- Validation evidence:
- Docs or release impact:
```

### 변경 계획 템플릿

```md
## Change Plan

- Problem / signal:
- Scope:
- Validation:
- Docs to update:
- Risks:
```

### 릴리스 준비 체크리스트

```md
## Release Readiness

- [ ] `./gradlew formatCheck`
- [ ] `./gradlew test`
- [ ] 사용자 영향 변경이면 `docs/release/CHANGELOG.md` 반영
- [ ] 영문/국문 문서 동시 업데이트
- [ ] 패키징/실행 흐름 변경 시 desktop/macOS 문서 재검토
- [ ] 수동 스모크 경로를 이슈 또는 PR에 기록
```
