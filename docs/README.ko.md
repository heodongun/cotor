# Cotor - AI CLI 마스터-에이전트 시스템

[![English](https://img.shields.io/badge/Language-English-blue)](README.md)
[![한국어](https://img.shields.io/badge/Language-한국어-red)](README.ko.md)

여러 AI 에이전트를 하나의 CLI로 오케스트레이션하는 Kotlin 기반 도구입니다. 실시간 모니터링, 검증, 웹/TUI 대시보드를 제공합니다.

## 빠른 설치

```bash
git clone https://github.com/yourusername/cotor.git
cd cotor
./shell/install-global.sh
```

로컬 전용(심볼릭 링크 없이):
```bash
./gradlew shadowJar
chmod +x shell/cotor
./shell/cotor version
```

Claude Code 통합:
```bash
./shell/install-claude-integration.sh
```

## 바로 사용하기

```bash
cotor init                         # cotor.yaml 생성
cotor list                         # 등록된 에이전트 확인
cotor validate <pipeline> -c <yaml>
cotor run <pipeline> -c <yaml> --output-format text
cotor template                     # 내장 템플릿 목록
cotor dash -c <yaml>               # TUI 대시보드
cotor web                          # 웹 파이프라인 스튜디오 실행
cotor completion zsh|bash|fish     # 쉘 자동완성
alias co="cotor"                   # 짧은 별칭
```

### 바로 실행 가능한 예제
- `examples/single-agent.yaml` – 단일 에이전트 Hello
- `examples/parallel-compare.yaml` – 병렬 비교
- `examples/decision-loop.yaml` – 조건/루프
- `examples/run-examples.sh` – 예제 일괄 실행

## 핵심 포인트

- 순차/병렬/DAG를 아우르는 코루틴 기반 비동기 실행
- 의사결정/루프 스테이지, 체크포인트, 복구 전략 지원
- 타임라인 모니터와 요약, 결과 집계
- YAML 친화적 설정 + 검증/구문 체크
- 웹 대시보드와 CLI/TUI 실행 환경

## 문서 맵

- 개요: `README.md` · `README.ko.md`
- 업그레이드: `UPGRADE_GUIDE.md` · `UPGRADE_RECOMMENDATIONS.md`
- 릴리스: `release/CHANGELOG.md` · `release/FEATURES_v1.1.md`
- 리포트: `reports/TEST_REPORT.md` · `reports/IMPLEMENTATION_SUMMARY.md`
- 빠른 시작: `QUICK_START.md`
- Claude 설정: `CLAUDE_SETUP.md`
- Claude Code 통합: `shell/install-claude-integration.sh`
- 사용 팁: `USAGE_TIPS.md`
- 템플릿: `templates/`

## 점검하기

```bash
./gradlew test
./shell/cotor version
```

선택적 스모크 테스트(레포 루트에서 실행):
```bash
./shell/test-cotor-enhanced.sh
./shell/test-cotor-pipeline.sh
./shell/test-claude-integration.sh
```

10줄 치트시트가 필요하면 `cotor --short` 를 실행하세요.

## 실행 스크립트

- `./shell/cotor` – CLI 엔트리포인트(그레이들 빌드 자동 트리거)
- `./shell/install-global.sh` – 빌드 후 전역 설치(심볼릭 링크)
- `./shell/install.sh` – 로컬 설치 + alias 안내
- `./shell/install-claude-integration.sh` – Claude Code 명령/지식 베이스 설치
- `./shell/test-*` – 스모크/통합 테스트 스크립트

## 필요한 AI CLI 설치

필요한 제공자별로 설치하세요:
```bash
# Claude CLI (npm)
# Copilot CLI
# Gemini / OpenAI / 기타
pip install openai
```
