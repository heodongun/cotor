# Cotor Test-Claude: 실험 및 개선 환경

test-claude 폴더는 cotor의 기능을 실험하고 개선 방향을 찾기 위한 테스트 환경입니다.

## 📁 디렉토리 구조

```
test-claude/
├── experiments/          # 실험 케이스들
│   ├── 01-basic/        # 기본 단일 AI 테스트
│   ├── 02-parallel/     # 병렬 실행 비교 테스트
│   ├── 03-sequential/   # 순차 체인 테스트 (생성→리뷰→개선)
│   ├── 04-error/        # 에러 핸들링 테스트
│   └── 05-complex/      # 복잡한 시나리오
│
├── scenarios/           # 실제 사용 시나리오
│   ├── code-review/     # 코드 리뷰 시나리오
│   ├── refactoring/     # 리팩토링 시나리오
│   └── feature-dev/     # 기능 개발 시나리오
│
├── results/             # 실험 결과 저장
│   └── [experiment]_[timestamp]/
│       ├── outputs/     # AI 출력물
│       ├── logs/        # 실행 로그
│       └── metrics/     # 성능 메트릭
│
├── templates/           # 재사용 가능한 파이프라인 템플릿
│
└── tools/              # 테스트 도구
    ├── run-experiment.sh      # 실험 자동화 스크립트
    ├── analyze-results.py     # 결과 분석 도구 (예정)
    └── compare-outputs.sh     # 출력 비교 도구 (예정)
```

## 🚀 빠른 시작

### 1. 기본 테스트 실행

```bash
cd test-claude/tools
./run-experiment.sh 01-basic
```

### 2. 병렬 비교 테스트

```bash
./run-experiment.sh 02-parallel
```

### 3. 순차 체인 테스트

```bash
./run-experiment.sh 03-sequential
```

## 📊 현재 구현된 실험

### 01-basic: 기본 단일 AI 테스트
- **목적**: Claude 단일 호출 테스트
- **작업**: Python is_prime 함수 생성
- **검증**: 기본 파이프라인 동작 확인

### 02-parallel: 병렬 실행 비교
- **목적**: 2개 AI가 같은 문제를 병렬로 해결
- **작업**: Claude와 Gemini가 버블 정렬 구현
- **검증**:
  - 병렬 실행 성능 측정
  - 두 구현 비교
  - 각 AI의 접근 방식 차이 분석

### 03-sequential: 순차 체인
- **목적**: 생성 → 리뷰 → 개선 워크플로우
- **작업**: Claude가 퀵소트 구현 → Gemini 리뷰 → Claude 개선
- **검증**:
  - 이전 stage 출력이 다음 stage 입력으로 전달
  - 점진적 품질 향상
  - 협업 워크플로우 효과

## 📈 예상 결과

### 병렬 실행 (02-parallel)
```
claude_bubble_sort.py     # Claude의 구현
gemini_bubble_sort.py     # Gemini의 구현
comparison.md             # 두 구현 비교 (예정)
```

### 순차 체인 (03-sequential)
```
quick_sort_v1.py          # 초기 구현
review.md                 # Gemini의 리뷰
quick_sort_v2.py          # 개선된 구현
improvement_analysis.md   # v1 vs v2 비교 (예정)
```

## 🔍 개선 방안 (IMPROVEMENT_PLAN.md 참고)

### 우선순위 높음
1. **컨텍스트 관리 시스템**
   - 모든 stage가 전체 파이프라인 상태 접근
   - stage 간 데이터 공유 용이

2. **조건부 실행 및 반복**
   - 품질 검증 후 재시도
   - 조건에 따른 분기
   - 반복 루프 지원

3. **에러 복구 전략**
   - 자동 재시도
   - Fallback agent
   - 실패 시 대체 전략

### 우선순위 중간
4. **출력 품질 검증**
   - 자동 품질 검사
   - 요구사항 준수 확인
   - 품질 점수화

5. **결과 비교 도구**
   - 여러 AI 출력 비교
   - 유사도 계산
   - 합의 검출

### 우선순위 낮음
6. **템플릿 라이브러리**
   - 자주 사용하는 패턴 템플릿화
   - 재사용성 증대

## 🧪 실험 실행 방법

### 수동 실행
```bash
cd /Users/Projects/cotor

# 빌드
./gradlew shadowJar

# 실험 실행
./cotor run basic-test --config test-claude/experiments/01-basic/config.yaml --verbose
```

### 자동화 스크립트 사용
```bash
cd test-claude/tools
./run-experiment.sh 01-basic
```

스크립트는 자동으로:
1. ✅ 환경 체크
2. ✅ 결과 디렉토리 생성
3. ✅ 파이프라인 실행
4. ✅ 결과 분석 (예정)
5. ✅ 리포트 생성

## 📊 결과 확인

```bash
# 최신 실험 결과
ls -lt results/ | head -5

# 특정 실험 리포트
cat results/01-basic_20241117_120000/REPORT.md

# 생성된 파일 확인
ls -la results/01-basic_20241117_120000/outputs/
```

## 🎯 다음 단계

### 단기 (1-2주)
- [ ] 실험 01-03 실행 및 결과 분석
- [ ] 발견된 문제점 문서화
- [ ] 컨텍스트 관리 시스템 프로토타입

### 중기 (2-4주)
- [ ] 조건부 실행 구현
- [ ] 에러 복구 전략 구현
- [ ] 품질 검증 시스템

### 장기 (1-2개월)
- [ ] 결과 비교 도구 완성
- [ ] 템플릿 라이브러리 구축
- [ ] 문서 및 예제 확충

## 📚 관련 문서

- [개선 계획 상세](IMPROVEMENT_PLAN.md) - 전체 개선 방향 및 구현 계획
- [프로젝트 README](../README.ko.md) - Cotor 전체 개요
- [구현 요약](../IMPLEMENTATION_SUMMARY.md) - Phase 1 완료 내역
- [업그레이드 가이드](../docs/UPGRADE_GUIDE.md) - v1.0 신규 기능

## 💡 팁

1. **실험 전 빌드 확인**
   ```bash
   ./gradlew clean shadowJar
   ```

2. **상세 로그 보기**
   ```bash
   tail -f test-claude/experiments/01-basic/basic-test.log
   ```

3. **결과 비교하기**
   ```bash
   diff -u results/experiment1/outputs/ results/experiment2/outputs/
   ```

4. **성능 측정**
   - 각 실험 결과의 `metrics/performance.json` 확인
   - 소요 시간, 성공률 등 비교

## 🤝 기여하기

새로운 실험 아이디어나 개선 제안이 있다면:
1. `experiments/` 에 새 폴더 생성
2. `config.yaml` 작성
3. 실험 실행 및 결과 분석
4. 발견 사항을 이슈로 등록

---

**Happy Experimenting! 🚀**
