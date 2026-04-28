# 성능 튜닝

## 미팅룸

컴퓨터가 무거울 때는 미팅룸 헤더의 `Low` 토글을 켜세요. Cotor는 아래 상황에서도 자동으로 렌더링을 가볍게 줄입니다.

- macOS 동작 줄이기 설정이 켜짐
- 창이 compact layout임
- 회사 에이전트가 20명 이상임
- 회사 에이전트가 50명 이상임
- 앱 scene이 비활성 상태임

모든 모드에서 실제 runtime 데이터 연결은 유지됩니다. Low Resource Mode는 표시와 애니메이션 비용만 줄입니다.

## 큰 회사

큰 회사에서는 미팅룸이 전체 상세 화면이 아니라 overview로 동작합니다.

- 20명 이상: simplified rendering
- 50명 이상: grouped rendering
- 이슈/리뷰가 많을 때: bounded summary만 렌더링

상세 로그와 긴 이슈/리뷰 데이터는 항상 보이는 미팅룸에 직접 렌더링하지 말고, 클릭해서 상세 화면에서 확인합니다.

## 런타임과 Provider 출력

Cotor는 장시간 실행 중에도 데스크톱 상태가 무한히 커지지 않도록 제한합니다.

- 회사 event reconnect는 촘촘한 고정 재시도 대신 backoff를 사용합니다.
- idle 상태의 desktop polling과 backend watchdog은 더 긴 healthy interval을 사용합니다.
- embedded backend health check는 관리 중인 backend process가 이미 healthy일 때 짧은 시간 재사용합니다.
- embedded TUI polling은 출력이 없거나 web view가 숨겨진 상태에서는 active-output 주기보다 느리게 backoff합니다.
- provider stdout/stderr가 매우 클 때는 head/tail summary만 bounded하게 보존합니다.
- 회사 activity, agent context, agent message 목록은 desktop store에서 cap을 적용합니다.
- dashboard 응답과 persisted desktop state도 noisy agent context/message history를 cap으로 제한해서 장시간 실행 후 snapshot/decode 비용이 계속 커지지 않게 합니다.

provider가 큰 로그를 출력하면 처음과 끝은 보존하고 중간은 명시적인 truncation marker로 표시합니다. 큰 산출물은 stdout으로 계속 흘리지 말고 파일 artifact로 남기는 편이 안전합니다.

## 리소스 문제 확인

로컬에서 유용한 명령:

```bash
ps -o pid,%cpu,%mem,rss,command -p <desktop-pid>,<backend-pid>
sample <desktop-pid> 3 -file /tmp/cotor-meeting-room-sample.txt
jcmd <backend-pid> Thread.print
```

idle 상태에서도 CPU가 계속 높으면 아래를 먼저 확인합니다.

- 지속 SwiftUI animation loop
- 중복 event stream subscription
- 반복 dashboard refresh
- timeout 뒤 남아 있는 provider process
- 항상 보이는 UI에 렌더링되는 큰 stdout/stderr payload
