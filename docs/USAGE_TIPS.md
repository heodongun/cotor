# Cotor 사용 편의 팁

## 바로 확인/점검
- `cotor --short` : 10줄 치트시트
- `cotor doctor` : Java/빌드/JAR/예제/CLI 설치 여부 점검
- `cotor completion zsh|bash|fish` : 자동완성 스크립트 출력 (추천 alias: `co`)

## 빠른 시작
- `cotor init --interactive` : 질문에 답해 YAML 자동 생성
- `cotor template --list` / `--preview <type>` / `--fill key=value` : 템플릿 브라우저 + 부분 치환
- `examples/run-examples.sh` : 단일/병렬/조건·루프 샘플 즉시 실행

## 실행 전 안전장치
- `cotor validate <pipeline> -c <yaml>` : 실행 전에 구조 검증
- 실패하면 `--dry-run`으로 흐름만 확인, 로그는 `logging.file` 참고

## 문제 해결
- 에러 메시지에 제시된 제안(“💡 Suggestions”)을 우선 확인
- 필요한 CLI가 없으면 `cotor doctor` 결과를 참고해 설치
- 상세 로그: `--debug`, 또는 `logging.file` 확인

## 편의
- 자동완성 적용: `cotor completion zsh > ~/.zshrc_snippets && source ~/.zshrc_snippets`
- 자주 쓰는 단축키: `alias co="cotor"`
