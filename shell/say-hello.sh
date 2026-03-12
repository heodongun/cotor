#!/bin/bash

# Builder (gemini) 'test' 목표 수행을 위한 스크립트
# '안녕이라 말해보세'

set -e

# 1. 빌드 확인 (shadowJar)
echo "🔨 Cotor 빌드 상태를 확인합니다..."
./gradlew shadowJar -q

# 2. Hello 명령어 실행
echo -e "\n--- [CLI Hello Command] ---"
./shell/cotor hello

# 3. Hello-Test 파이프라인 실행
echo -e "\n--- [Pipeline Hello Test] ---"
./shell/cotor run hello-test -c test/hello-test.yaml --output-format text

echo -e "\n✨ 'test' 목표 수행 완료!"
