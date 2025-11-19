#!/bin/bash

# Cotor 실험 자동화 스크립트
# Usage: ./run-experiment.sh <experiment-name> [options]

set -e

EXPERIMENT_NAME=$1
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULTS_DIR="./results/${EXPERIMENT_NAME}_${TIMESTAMP}"

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 헬퍼 함수
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 실험 환경 체크
check_environment() {
    log_info "실험 환경 체크 중..."

    # cotor 실행 파일 확인
    if [ ! -f "../../cotor" ]; then
        log_error "cotor 실행 파일을 찾을 수 없습니다."
        exit 1
    fi

    # 실험 설정 파일 확인
    if [ ! -f "../experiments/${EXPERIMENT_NAME}/config.yaml" ]; then
        log_error "실험 설정 파일을 찾을 수 없습니다: ${EXPERIMENT_NAME}/config.yaml"
        exit 1
    fi

    log_success "환경 체크 완료"
}

# 결과 디렉토리 생성
setup_results_dir() {
    log_info "결과 디렉토리 생성: ${RESULTS_DIR}"
    mkdir -p "${RESULTS_DIR}"/{outputs,logs,metrics}
}

# 실험 실행
run_experiment() {
    log_info "실험 실행: ${EXPERIMENT_NAME}"

    local config_file="../experiments/${EXPERIMENT_NAME}/config.yaml"
    local pipeline_name=$(grep "name:" "$config_file" | head -1 | awk '{print $2}')

    # 시작 시간 기록
    local start_time=$(date +%s)

    # cotor 실행
    log_info "파이프라인 실행: ${pipeline_name}"

    ../../cotor run "$pipeline_name" \
        --config "$config_file" \
        --verbose \
        --output-format json \
        > "${RESULTS_DIR}/outputs/execution.json" \
        2> "${RESULTS_DIR}/logs/execution.log"

    local exit_code=$?
    local end_time=$(date +%s)
    local duration=$((end_time - start_time))

    # 메트릭 저장
    cat > "${RESULTS_DIR}/metrics/performance.json" << EOF
{
    "experiment": "${EXPERIMENT_NAME}",
    "timestamp": "${TIMESTAMP}",
    "duration_seconds": ${duration},
    "exit_code": ${exit_code},
    "success": $([ $exit_code -eq 0 ] && echo "true" || echo "false")
}
EOF

    if [ $exit_code -eq 0 ]; then
        log_success "실험 완료 (${duration}초)"
    else
        log_error "실험 실패 (exit code: ${exit_code})"
        return $exit_code
    fi
}

# 결과 분석
analyze_results() {
    log_info "결과 분석 중..."

    # AI 출력 추출
    if [ -f "${RESULTS_DIR}/outputs/execution.json" ]; then
        log_info "AI 출력 파싱..."

        # JSON에서 각 agent의 출력 추출
        python3 ../tools/analyze-results.py \
            "${RESULTS_DIR}/outputs/execution.json" \
            "${RESULTS_DIR}/outputs/"

        log_success "출력 파싱 완료"
    fi

    # 비교 리포트 생성
    if [ -f "../tools/compare-outputs.sh" ]; then
        log_info "비교 리포트 생성..."
        bash ../tools/compare-outputs.sh "${RESULTS_DIR}/outputs/"
    fi
}

# 리포트 생성
generate_report() {
    log_info "실험 리포트 생성 중..."

    local report_file="${RESULTS_DIR}/REPORT.md"

    cat > "$report_file" << EOF
# 실험 리포트: ${EXPERIMENT_NAME}

## 📊 실행 정보
- **실험명**: ${EXPERIMENT_NAME}
- **실행 시간**: ${TIMESTAMP}
- **소요 시간**: $(jq -r '.duration_seconds' "${RESULTS_DIR}/metrics/performance.json")초
- **실행 결과**: $(jq -r '.success' "${RESULTS_DIR}/metrics/performance.json")

## 📁 생성된 파일
\`\`\`
$(find "${RESULTS_DIR}" -type f | sed 's|'"${RESULTS_DIR}"'/||')
\`\`\`

## 📝 실행 로그
\`\`\`
$(tail -n 50 "${RESULTS_DIR}/logs/execution.log")
\`\`\`

## 🔍 다음 단계
1. 출력 결과 확인: \`${RESULTS_DIR}/outputs/\`
2. 상세 로그 확인: \`${RESULTS_DIR}/logs/\`
3. 메트릭 분석: \`${RESULTS_DIR}/metrics/\`
EOF

    log_success "리포트 생성 완료: ${report_file}"

    # 리포트 미리보기
    echo ""
    cat "$report_file"
}

# 메인 실행
main() {
    if [ -z "$EXPERIMENT_NAME" ]; then
        log_error "사용법: $0 <experiment-name>"
        echo ""
        echo "사용 가능한 실험:"
        ls -1 ../experiments/
        exit 1
    fi

    log_info "=== Cotor 실험 자동화 시작 ==="
    echo ""

    check_environment
    setup_results_dir
    run_experiment
    analyze_results
    generate_report

    echo ""
    log_success "=== 실험 완료 ==="
    log_info "결과 위치: ${RESULTS_DIR}"
}

main
