# Cotor PATH 설정 가이드

설치 후 `cotor` 명령어가 작동하지 않으면 PATH 설정이 필요합니다.

---

## 🔍 문제 확인

```bash
$ cotor version
zsh: command not found: cotor
```

위와 같은 오류가 발생하면 PATH 설정이 필요합니다.

---

## ✅ 해결 방법

### 1. PATH 확인

```bash
echo $PATH | grep ".local/bin"
```

결과가 없으면 PATH에 추가가 필요합니다.

### 2. Bash 사용자

`~/.bashrc` 또는 `~/.bash_profile`에 추가:

```bash
export PATH="$PATH:$HOME/.local/bin"
```

적용:
```bash
source ~/.bashrc
# 또는
source ~/.bash_profile
```

### 3. Zsh 사용자 (macOS 기본)

`~/.zshrc`에 추가:

```bash
export PATH="$PATH:$HOME/.local/bin"
```

적용:
```bash
source ~/.zshrc
```

### 4. Fish 사용자

```fish
fish_add_path $HOME/.local/bin
```

---

## 🚀 확인

설정 후 다시 실행:

```bash
$ cotor version
Cotor version 1.0.0
Kotlin 2.1.0
JVM 23
```

---

## 💡 대안 방법

PATH 설정 없이 직접 실행:

```bash
# 절대 경로로 실행
/Users/YOUR_USERNAME/.local/bin/cotor version

# 또는 프로젝트 내에서
./shell/cotor version
```

---

## 📝 자동완성 추가 (선택사항)

### Zsh

```bash
cotor completion zsh > ~/.cotor-completion.zsh
echo "source ~/.cotor-completion.zsh" >> ~/.zshrc
source ~/.zshrc
```

### Bash

```bash
cotor completion bash > ~/.cotor-completion.bash
echo "source ~/.cotor-completion.bash" >> ~/.bashrc
source ~/.bashrc
```

### Fish

```bash
cotor completion fish > ~/.config/fish/completions/cotor.fish
```

---

## 🎯 별칭 추가 (추천)

더 빠른 실행을 위해:

```bash
# ~/.zshrc 또는 ~/.bashrc에 추가
alias co='cotor'
```

사용:
```bash
co version
co init
co run example-pipeline
```

---

**문제가 계속되면**: [GitHub Issues](https://github.com/yourusername/cotor/issues)
