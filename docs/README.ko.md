# Cotor - AI CLI 마스터-에이전트 시스템

[![English](https://img.shields.io/badge/Language-English-blue)](README.md)
[![한국어](https://img.shields.io/badge/Language-한국어-red)](README.ko.md)

Cotor는 Kotlin 기반 CLI/TUI 오케스트레이터로, 여러 AI 에이전트 파이프라인의 검증·실행·모니터링·복구를 한 도구에서 제공합니다.

## 빠른 설치

```bash
git clone https://github.com/yourusername/cotor.git
cd cotor
./shell/install-global.sh
```

로컬 전용 실행:
```bash
./gradlew shadowJar
chmod +x shell/cotor
./shell/cotor version
```

## 바로 쓰는 명령

```bash
cotor                            # 기본 interactive 모드 실행
cotor --short                    # 10줄 치트시트
cotor init --interactive         # 대화형 초기 설정
cotor list -c cotor.yaml         # 등록 에이전트 확인
cotor validate <pipeline> -c <yaml>
cotor run <pipeline> -c <yaml> --dry-run
cotor run <pipeline> -c <yaml> --output-format text
cotor template --list            # 내장 템플릿 목록
cotor agent add claude --yes     # .cotor/agents 프리셋 추가
cotor plugin list                # 플러그인 메타데이터 확인
cotor stats                      # 실행 통계
cotor doctor                     # 환경 점검
cotor dash -c <yaml>             # Codex 스타일 대시보드
cotor web                        # 웹 파이프라인 스튜디오
```

## 현재 CLI 명령 체계

기본 서브커맨드:
`init`, `list`, `run`, `validate`, `test`, `template`, `resume`, `checkpoint`, `stats`, `doctor`, `status`, `dash`, `interactive`, `web`, `lint`, `explain`, `plugin`, `agent`, `version`, `completion`

## 핵심 기능

- 순차/병렬/DAG 실행 및 스테이지 의존성 처리
- 조건(분기)·루프 스테이지 지원
- 실행 타임라인 수집 + watch 모니터링
- 체크포인트 저장/재개 및 체크포인트 정리
- 결과 출력 포맷(`json`, `csv`, `text`)
- 템플릿 생성 (`compare`, `chain`, `review`, `consensus`, `fanout`, `selfheal`, `verified`, `custom`)
- 에이전트 프리셋 관리, 플러그인 점검

## 문서 안내

- 빠른 시작: `QUICK_START.md`
- 아키텍처: `ARCHITECTURE.md`
- 기능 목록: `FEATURES.md`
- 사용 팁: `USAGE_TIPS.md`
- 변경 이력: `release/CHANGELOG.md`
- 리포트: `reports/`
- Claude 연동: `CLAUDE_SETUP.md`, `claude/`

## 참고

- `--config`를 생략하면 대부분 `cotor.yaml`을 기본으로 사용합니다.
- 인자 없이 `cotor`를 실행하면 interactive 모드가 시작됩니다.
