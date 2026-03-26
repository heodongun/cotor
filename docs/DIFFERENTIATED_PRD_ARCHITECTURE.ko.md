# Cotor 차별화 PRD / 아키텍처 요약

원문: [DIFFERENTIATED_PRD_ARCHITECTURE.md](DIFFERENTIATED_PRD_ARCHITECTURE.md)

## 문서 목적

이 문서는 Cotor의 문제 정의, 타깃 사용자, 차별화 포인트, 핵심 원칙, 아키텍처 의도를 하나의 제품/설계 초안으로 묶은 자료입니다.

## 핵심 구성

- 문제와 기회
- 타깃 사용자와 JTBD
- 차별화 가설
- 제품 원칙과 비목표
- CLI / Desktop / Recovery 핵심 플로우
- 기능 맵
- 아키텍처 개요
- 주요 설계 결정과 이유
- 비기능 요구사항
- 성공 지표와 검증 질문
- 단계별 로드맵
- 리스크와 오픈 질문

## 핵심 메시지

Cotor를 단순 CLI가 아니라, 로컬 우선 멀티 에이전트 오케스트레이션 제품으로 차별화하려는 설계 의도를 담고 있습니다. 특히 localhost `app-server`, repository -> workspace -> task -> run 모델, worktree 격리, validation-first 실행이 중요한 축입니다.
