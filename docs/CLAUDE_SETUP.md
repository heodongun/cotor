# Claude Code 통합 설정 가이드

이 가이드는 Claude Code에서 cotor 슬래시 커맨드를 사용하기 위한 수동 설정 방법을 설명합니다.

## 📋 목차

1. [자동 설치](#자동-설치)
2. [수동 설치](#수동-설치)
3. [파일 구조](#파일-구조)
4. [커맨드 파일 생성](#커맨드-파일-생성)
5. [검증](#검증)
6. [문제 해결](#문제-해결)

## 🚀 자동 설치

가장 간단한 방법은 설치 스크립트를 사용하는 것입니다:

```bash
cd cotor
./shell/install-claude-integration.sh
```

이 스크립트는 모든 필요한 파일을 자동으로 설치합니다.

## 🔧 수동 설치

자동 설치가 작동하지 않거나 수동으로 설정하고 싶다면 다음 단계를 따르세요.

### 1단계: 디렉토리 생성

```bash
mkdir -p ~/.claude/steering
mkdir -p ~/.claude/commands
mkdir -p ~/.claude/templates
mkdir -p ~/.claude/settings
```

### 2단계: 지식 베이스 파일 복사

프로젝트의 지식 베이스 파일을 전역 위치로 복사합니다:

```bash
# 프로젝트 루트에서 실행
cp docs/claude/cotor-knowledge.md ~/.claude/steering/
```

또는 직접 생성:

```bash
cat > ~/.claude/steering/cotor-knowledge.md << 'EOF'
# Cotor CLI 지식 베이스

## 핵심 개념
[내용은 docs/claude/cotor-knowledge.md 참조]
EOF
```

### 3단계: 슬래시 커맨드 파일 생성

각 커맨드 파일을 `~/.claude/commands/` 디렉토리에 생성합니다.

#### cotor-generate.md

```bash
cat > ~/.claude/commands/cotor-generate.md << 'EOF'
---
name: cotor-generate
description: 목표 설명에서 cotor 파이프라인 자동 생성
category: cotor
---

# Cotor 파이프라인 생성

[전체 내용은 프로젝트의 해당 파일 참조]
EOF
```

#### cotor-execute.md

```bash
cat > ~/.claude/commands/cotor-execute.md << 'EOF'
---
name: cotor-execute
description: cotor 파이프라인 실행 및 모니터링
category: cotor
---

# Cotor 파이프라인 실행

[전체 내용은 프로젝트의 해당 파일 참조]
EOF
```

#### cotor-validate.md

```bash
cat > ~/.claude/commands/cotor-validate.md << 'EOF'
---
name: cotor-validate
description: cotor 파이프라인 검증
category: cotor
---

# Cotor 파이프라인 검증

[전체 내용은 프로젝트의 해당 파일 참조]
EOF
```

#### cotor-template.md

```bash
cat > ~/.claude/commands/cotor-template.md << 'EOF'
---
name: cotor-template
description: 템플릿에서 cotor 파이프라인 생성
category: cotor
---

# Cotor 템플릿 사용

[전체 내용은 프로젝트의 해당 파일 참조]
EOF
```

### 4단계: 템플릿 파일 복사

```bash
# 프로젝트 루트에서 실행
cp docs/claude/templates/*.yaml ~/.claude/templates/
```

또는 개별 복사:

```bash
cp docs/claude/templates/compare-solutions.yaml ~/.claude/templates/
cp docs/claude/templates/review-chain.yaml ~/.claude/templates/
cp docs/claude/templates/comprehensive-review.yaml ~/.claude/templates/
```

### 5단계: 설정 파일 생성

```bash
cat > ~/.claude/settings/cotor-settings.json << 'EOF'
{
  "globalKnowledge": "~/.claude/steering/cotor-knowledge.md",
  "commandsDir": "~/.claude/commands",
  "defaultOutputFormat": "text",
  "autoLoadKnowledge": true,
  "templates": {
    "compare-solutions": "~/.claude/templates/compare-solutions.yaml",
    "review-chain": "~/.claude/templates/review-chain.yaml",
    "comprehensive-review": "~/.claude/templates/comprehensive-review.yaml"
  },
  "cotor": {
    "defaultTimeout": 60000,
    "maxConcurrentAgents": 10,
    "logLevel": "INFO",
    "logFile": "cotor.log"
  }
}
EOF
```

### 6단계: 권한 설정

```bash
chmod -R 755 ~/.claude/commands
chmod -R 644 ~/.claude/steering/*.md
chmod -R 644 ~/.claude/templates/*.yaml
```

## 📁 파일 구조

설치 후 다음과 같은 구조가 생성됩니다:

```
~/.claude/
├── steering/
│   └── cotor-knowledge.md          # 전역 지식 베이스
├── commands/
│   ├── cotor-generate.md           # 파이프라인 생성 커맨드
│   ├── cotor-execute.md            # 파이프라인 실행 커맨드
│   ├── cotor-validate.md           # 파이프라인 검증 커맨드
│   └── cotor-template.md           # 템플릿 사용 커맨드
├── templates/
│   ├── compare-solutions.yaml      # 멀티 AI 비교 템플릿
│   ├── review-chain.yaml           # 순차 리뷰 체인 템플릿
│   └── comprehensive-review.yaml   # 종합 리뷰 템플릿
└── settings/
    └── cotor-settings.json         # 전역 설정
```

## 📝 커맨드 파일 생성

각 커맨드 파일의 전체 내용은 프로젝트의 다음 위치에서 확인할 수 있습니다:

- `docs/claude/commands/cotor-generate.md`
- `docs/claude/commands/cotor-execute.md`
- `docs/claude/commands/cotor-validate.md`
- `docs/claude/commands/cotor-template.md`

이 파일들을 복사하여 `~/.claude/commands/` 디렉토리에 붙여넣으세요.

### 빠른 복사 명령어

프로젝트 루트에서 실행:

```bash
# 커맨드 파일 복사
cp docs/claude/commands/*.md ~/.claude/commands/

# 지식 베이스 복사
cp docs/claude/cotor-knowledge.md ~/.claude/steering/

# 템플릿 복사 (아직 생성되지 않은 경우)
cp docs/claude/templates/*.yaml ~/.claude/templates/

# 권한 설정
chmod 644 ~/.claude/commands/*.md
chmod 644 ~/.claude/steering/cotor-knowledge.md
chmod 644 ~/.claude/templates/*.yaml
```

## ✅ 검증

설치가 완료되면 테스트 스크립트로 검증합니다:

```bash
./test-claude-integration.sh
```

모든 테스트가 통과하면 설치가 성공한 것입니다!

### 수동 검증

테스트 스크립트 없이 수동으로 확인:

```bash
# 1. 파일 존재 확인
ls -la ~/.claude/steering/cotor-knowledge.md
ls -la ~/.claude/commands/cotor-*.md
ls -la ~/.claude/templates/*.yaml
ls -la ~/.claude/settings/cotor-settings.json

# 2. 파일 내용 확인
head -20 ~/.claude/steering/cotor-knowledge.md
head -10 ~/.claude/commands/cotor-generate.md

# 3. 권한 확인
ls -la ~/.claude/commands/
```

## 🎯 사용 방법

설치 후 Claude Code를 재시작하고 아무 프로젝트에서나 다음 커맨드를 사용할 수 있습니다:

### 템플릿 목록 보기
```
/cotor-template
```

### 파이프라인 생성
```
/cotor-generate "3개의 AI로 소수 찾기 함수 비교"
```

### 파이프라인 검증
```
/cotor-validate pipeline.yaml
```

### 파이프라인 실행
```
/cotor-execute pipeline.yaml
```

### 템플릿에서 생성
```
/cotor-template compare-solutions my-pipeline.yaml
```

## 🔍 문제 해결

### 커맨드가 표시되지 않음

**증상**: `/cotor-` 입력 시 자동완성에 커맨드가 나타나지 않음

**해결 방법**:
1. Claude Code 완전히 재시작
2. 파일 위치 확인: `ls ~/.claude/commands/cotor-*.md`
3. 파일 권한 확인: `ls -la ~/.claude/commands/`
4. 파일 형식 확인: 각 파일이 `---` 메타데이터로 시작하는지 확인

### 지식 베이스가 로드되지 않음

**증상**: Claude가 cotor 명령어를 이해하지 못함

**해결 방법**:
1. 파일 위치 확인: `ls ~/.claude/steering/cotor-knowledge.md`
2. 파일 내용 확인: `head -50 ~/.claude/steering/cotor-knowledge.md`
3. Claude Code 재시작

### 템플릿을 찾을 수 없음

**증상**: `/cotor-template` 실행 시 템플릿이 표시되지 않음

**해결 방법**:
1. 템플릿 파일 확인: `ls ~/.claude/templates/*.yaml`
2. 파일 권한 확인: `ls -la ~/.claude/templates/`
3. 설정 파일 확인: `cat ~/.claude/settings/cotor-settings.json`

### 권한 오류

**증상**: 파일을 읽을 수 없다는 오류

**해결 방법**:
```bash
chmod -R 755 ~/.claude/commands
chmod -R 644 ~/.claude/steering/*.md
chmod -R 644 ~/.claude/templates/*.yaml
chmod 644 ~/.claude/settings/cotor-settings.json
```

### 파일이 생성되지 않음

**증상**: 설치 스크립트 실행 후 파일이 없음

**해결 방법**:
1. 홈 디렉토리 확인: `echo $HOME`
2. 수동으로 디렉토리 생성: `mkdir -p ~/.claude/{steering,commands,templates,settings}`
3. 파일 수동 복사 (위의 수동 설치 단계 참조)

## 🔄 업데이트

새 버전으로 업데이트하려면:

```bash
# 1. 백업 (선택사항)
cp -r ~/.claude ~/.claude.backup

# 2. 새 파일 설치
./shell/install-claude-integration.sh

# 3. 검증
./test-claude-integration.sh
```

## 🗑️ 제거

Claude 통합을 제거하려면:

```bash
# 전체 제거
rm -rf ~/.claude/steering/cotor-knowledge.md
rm -rf ~/.claude/commands/cotor-*.md
rm -rf ~/.claude/templates/compare-solutions.yaml
rm -rf ~/.claude/templates/review-chain.yaml
rm -rf ~/.claude/templates/comprehensive-review.yaml
rm -rf ~/.claude/settings/cotor-settings.json

# 또는 전체 Claude 설정 제거 (주의!)
# rm -rf ~/.claude
```

## 📚 추가 리소스

- [Cotor 메인 README](../README.md)
- [Cotor 한글 README](../README.ko.md)
- [지식 베이스 파일](claude/cotor-knowledge.md)
- [커맨드 파일들](claude/commands/)
- [템플릿 파일들](claude/templates/)

## 💡 팁

1. **전역 설정**: 한 번 설치하면 모든 프로젝트에서 사용 가능
2. **커스터마이징**: `~/.claude/` 파일들을 직접 수정하여 커스터마이징 가능
3. **백업**: 중요한 커스터마이징이 있다면 정기적으로 백업
4. **버전 관리**: 프로젝트별 설정은 `.claude/` 디렉토리에 추가 가능

## 🤝 도움이 필요하신가요?

문제가 해결되지 않으면:
1. [GitHub Issues](https://github.com/yourusername/cotor/issues)에 문제 보고
2. 테스트 스크립트 결과 첨부: `./test-claude-integration.sh > test-results.txt`
3. 파일 구조 첨부: `ls -laR ~/.claude/ > file-structure.txt`

## CI/린트 가드레일

새로운 CI 기준으로 아래 두 명령이 기본 게이트입니다.

```bash
gradle formatCheck
gradle test
```

파이프라인 YAML을 생성/수정하는 슬래시 명령(`/cotor-generate`, `/cotor-template`)을 썼다면 실행 전에 아래를 권장합니다.

```bash
cotor validate <pipeline.yaml>
cotor lint <pipeline.yaml>
```

