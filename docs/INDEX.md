# Cotor 문서 인덱스

**버전**: 1.0.0
**최종 업데이트**: 2025-11-28

---

## 📖 시작하기

### 빠른 시작
- [README (영문)](../README.md) - 프로젝트 개요
- [README (한글)](../README.ko.md) - 프로젝트 개요 (한글)
- [Quick Start](QUICK_START.md) - 빠른 시작 가이드
- [Usage Tips](USAGE_TIPS.md) - 사용 팁

### 상세 가이드
- [English Guide](README.md) - 영문 상세 가이드
- [한글 가이드](README.ko.md) - 한글 상세 가이드

---

## 🎯 기능 및 사용법

### 전체 기능
- [**Features**](FEATURES.md) - 전체 기능 상세 목록 (550줄)
  - 핵심 기능
  - CLI 명령어 전체
  - 템플릿 시스템
  - 보안 기능
  - 통합 방법
  - 사용 사례

### Claude 통합
- [Claude Setup](CLAUDE_SETUP.md) - Claude Code 통합 설정

---

## 🧪 테스트 및 검증

### 테스트 리포트
- [**Live Test Results**](../test-results/LIVE_TEST_RESULTS.md) - 실제 실행 테스트 (14KB, 신규)
  - 전역 설치 테스트
  - 13개 명령어 실행 결과
  - 실제 출력 캡처
  - 성능 측정

- [**Test Results Summary**](../test-results/README.md) - 테스트 요약 (3.4KB)

- [**Feature Test Report v1.0.0**](reports/FEATURE_TEST_REPORT_v1.0.0.md) - 상세 기능 테스트 (400줄)
  - 빌드 및 설치
  - 모든 기능 검증
  - 통합 테스트
  - 프로덕션 준비도

- [Test Report](reports/TEST_REPORT.md) - 기존 테스트 리포트

---

## 📋 릴리스 및 변경사항

### 릴리스 노트
- [Changelog](release/CHANGELOG.md) - 변경 이력
- [Features v1.1](release/FEATURES_v1.1.md) - 향후 버전 계획

### 업그레이드
- [Upgrade Guide](UPGRADE_GUIDE.md) - 업그레이드 가이드
- [Upgrade Recommendations](UPGRADE_RECOMMENDATIONS.md) - 업그레이드 권장사항

---

## 📊 리포트 및 분석

### 구현 리포트
- [Implementation Summary](reports/IMPLEMENTATION_SUMMARY.md) - 구현 요약
- [Implementation Complete](reports/IMPLEMENTATION_COMPLETE.md) - 구현 완료 상세
- [Summary](reports/SUMMARY.md) - 전체 요약
- [Improvements](reports/IMPROVEMENTS.md) - 개선 사항

### 문서 리포트
- [**Documentation Summary**](reports/DOCUMENTATION_SUMMARY.md) - 문서화 요약 (신규)
  - 전체 문서 구조
  - 신규 문서 목록
  - 문서 통계
  - 향후 계획

---

## 📦 예제

### 준비된 예제
- [examples/single-agent.yaml](../examples/single-agent.yaml) - 단일 에이전트
- [examples/parallel-compare.yaml](../examples/parallel-compare.yaml) - 병렬 비교
- [examples/decision-loop.yaml](../examples/decision-loop.yaml) - 조건/루프
- [examples/run-examples.sh](../examples/run-examples.sh) - 일괄 실행

---

## 🛠️ 설치 및 스크립트

### Shell 스크립트
- [shell/install-global.sh](../shell/install-global.sh) - 전역 설치
- [shell/install.sh](../shell/install.sh) - 로컬 설치
- [shell/cotor](../shell/cotor) - CLI 실행 파일
- [shell/install-claude-integration.sh](../shell/install-claude-integration.sh) - Claude 통합

### 테스트 스크립트
- [shell/test-cotor-enhanced.sh](../shell/test-cotor-enhanced.sh) - 향상된 테스트
- [shell/test-cotor-pipeline.sh](../shell/test-cotor-pipeline.sh) - 파이프라인 테스트
- [shell/test-claude-integration.sh](../shell/test-claude-integration.sh) - Claude 통합 테스트

---

## 📚 문서 카테고리

### 난이도별
- **초급**: README, Quick Start, Usage Tips
- **중급**: Features, Claude Setup, Examples
- **고급**: Test Reports, Implementation Summary, Upgrade Guide

### 목적별
- **설치**: README, Quick Start, shell 스크립트
- **사용**: Features, Usage Tips, Examples
- **개발**: Test Reports, Implementation Summary
- **통합**: Claude Setup, install-claude-integration.sh
- **유지보수**: Upgrade Guide, Changelog

### 언어별
- **영문**: 15+ 문서
- **한글**: README.ko.md, 한글 가이드, 주요 문서 한글 번역

---

## 🔍 빠른 검색

### "설치하고 싶어요"
→ [README](../README.md) → [Quick Start](QUICK_START.md)

### "기능이 궁금해요"
→ [Features](FEATURES.md) → [Test Results](../test-results/LIVE_TEST_RESULTS.md)

### "실제로 작동하나요?"
→ [**Live Test Results**](../test-results/LIVE_TEST_RESULTS.md) - 실제 실행 결과!

### "어떻게 사용하나요?"
→ [Usage Tips](USAGE_TIPS.md) → [Examples](../examples/)

### "Claude와 연동하려면?"
→ [Claude Setup](CLAUDE_SETUP.md)

### "업그레이드하고 싶어요"
→ [Upgrade Guide](UPGRADE_GUIDE.md) → [Changelog](release/CHANGELOG.md)

---

## 📊 문서 통계

- **총 문서 수**: 25+
- **신규 작성**: 6 (Features, Test Reports, Documentation Summary 등)
- **업데이트**: 2 (README.md, README.ko.md)
- **언어**: 영문 + 한글
- **총 라인 수**: 약 3,000줄

---

## 🎯 추천 읽기 순서

### 처음 사용자
1. [README](../README.md)
2. [Quick Start](QUICK_START.md)
3. [Live Test Results](../test-results/LIVE_TEST_RESULTS.md) ← **실제 동작 확인!**
4. [Examples](../examples/)

### 고급 사용자
1. [Features](FEATURES.md)
2. [Feature Test Report](reports/FEATURE_TEST_REPORT_v1.0.0.md)
3. [Usage Tips](USAGE_TIPS.md)
4. [Claude Setup](CLAUDE_SETUP.md)

### 개발자
1. [Implementation Summary](reports/IMPLEMENTATION_SUMMARY.md)
2. [Test Reports](reports/)
3. [Documentation Summary](reports/DOCUMENTATION_SUMMARY.md)
4. [Upgrade Guide](UPGRADE_GUIDE.md)

---

## 🆕 최신 추가 문서

**2025-11-28 추가**:
- ✨ [Live Test Results](../test-results/LIVE_TEST_RESULTS.md) - 실제 실행 테스트 (14KB)
- ✨ [Test Results Summary](../test-results/README.md) - 빠른 확인용
- ✨ [Features](FEATURES.md) - 전체 기능 상세 (550줄)
- ✨ [Feature Test Report](reports/FEATURE_TEST_REPORT_v1.0.0.md) - 체계적 테스트 (400줄)
- ✨ [Documentation Summary](reports/DOCUMENTATION_SUMMARY.md) - 문서화 현황
- ✨ [INDEX](INDEX.md) - 본 문서

---

## 📞 도움이 필요하신가요?

- 📧 Email: support@cotor.io
- 💬 Discord: [커뮤니티](https://discord.gg/cotor)
- 🐛 Issues: [GitHub](https://github.com/yourusername/cotor/issues)
- 📖 Wiki: [문서](https://github.com/yourusername/cotor/wiki)

---

**마지막 업데이트**: 2025-11-28
**문서 버전**: 1.0.0
**상태**: ✅ 완료
