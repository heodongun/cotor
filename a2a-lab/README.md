# A2A Workflow Lab

A2A(Agent-to-Agent) 실습용 웹 앱입니다.

## 포함 기능
- 기본 에이전트 5개
  - Research Agent
  - Planner Agent
  - Coder Agent
  - Tester Agent
  - Reviewer Agent
- 웹에서 워크플로우(DAG) 직접 구성
  - 노드 추가
  - 의존성(엣지) 설정
- 워크플로우 실행
  - 위상 정렬로 순서 계산
  - 노드별 결과 및 실행 로그 출력

## 실행
```bash
cd a2a-lab
npm install
npm run dev
```

브라우저: `http://localhost:4310`

## 빠른 실습 예시
1. `n1` / `researcher` / 입력: "A2A 개념 조사"
2. `n2` / `planner` / 의존: `n1` / 입력: "실습 계획 수립"
3. `n3` / `coder` / 의존: `n2` / 입력: "간단한 API 코드 작성"
4. `n4` / `tester` / 의존: `n3` / 입력: "테스트 시나리오 점검"
5. `n5` / `reviewer` / 의존: `n2,n4` / 입력: "최종 리뷰"

실행 후 JSON 결과에서 `order`, `results`, `logs`를 확인하세요.

## 다음 확장 아이디어
- 실제 LLM API 연동 (OpenAI/Gemini/Claude)
- 병렬 실행 최적화 (동일 depth 노드 동시 실행)
- 노드 타입 확장 (조건 분기, 루프, 승인 게이트)
- 워크플로우 저장/불러오기
- 드래그앤드롭 캔버스 UI

## 문서
자세한 설명은 `docs/A2A_DEEP_DIVE.md` 참고.
