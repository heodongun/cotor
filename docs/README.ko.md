# Cotor - 프로덕션 지향 AI 오케스트레이션 워크벤치

[![English](https://img.shields.io/badge/Language-English-blue)](README.md)
[![한국어](https://img.shields.io/badge/Language-한국어-red)](README.ko.md)

Cotor는 여러 AI 에이전트 파이프라인을 검증·실행·모니터링·복구까지 이어서 운영하기 위한 Kotlin 기반 오케스트레이션 워크벤치입니다. 핵심은 실험의 속도를 유지하되, 그 결과를 팀이 재사용하고 리뷰할 수 있는 전달 자산으로 바꾸는 것입니다.

## 포지셔닝: Cotor vs Paperclip

Paperclip이 빠르게 워크플로우를 스케치하는 데 적합하다면, Cotor는 그 워크플로우를 실제 전달/운영 가능한 형태로 굳히는 단계에 맞춰져 있습니다.

- 워크플로우를 캔버스에만 남기지 않고 YAML로 버전 관리합니다.
- 검증, 체크포인트, 복구를 통해 실행 전에 운영 리스크를 줄입니다.
- CLI, TUI, 웹, 데스크톱에서 같은 흐름을 다뤄 엔지니어링과 운영의 기준점을 통일합니다.
- 로컬 리포지토리, 워크트리, 실행 모니터링 중심의 작업 방식에 강합니다.

재현 가능성, 코드 리뷰, 반복 실행, 운영 가시성이 중요하다면 Cotor 쪽이 더 잘 맞습니다.

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

## 데스크톱 앱 (macOS)

데스크톱 번들을 빌드하고 `응용 프로그램`에 설치하면서 로컬 배포본까지 갱신하려면:

```bash
./shell/install-desktop-app.sh
```

실행:

```bash
open "/Applications/Cotor Desktop.app" || open "$HOME/Applications/Cotor Desktop.app"
```

백엔드 실행, 워크트리 격리, 브라우저/포트 탭 설명은 `DESKTOP_APP.md`에 정리되어 있습니다.

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
- 데스크톱 앱: `DESKTOP_APP.md`
- 웹 에디터: `WEB_EDITOR.md`
- 아키텍처: `ARCHITECTURE.md`
- 기능 목록: `FEATURES.md`
- 업그레이드 권장사항: `UPGRADE_RECOMMENDATIONS.md`
- 사용 팁: `USAGE_TIPS.md`
- 변경 이력: `release/CHANGELOG.md`
- 리포트: `reports/`
- Claude 연동: `CLAUDE_SETUP.md`, `claude/`

## 참고

- `--config`를 생략하면 대부분 `cotor.yaml`을 기본으로 사용합니다.
- 인자 없이 `cotor`를 실행하면 interactive 모드가 시작됩니다.
