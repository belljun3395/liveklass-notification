#!/usr/bin/env bash
# ============================================================
# 서버 실행 + 서버 로그 + 통합 테스트 스크립트
# ============================================================
# 사용법:
#   chmod +x scripts/test-with-server.sh
#   ./scripts/test-with-server.sh              # 기본 실행
#   VERBOSE=1 ./scripts/test-with-server.sh    # 요청/응답 로그 포함
#
# 이 스크립트는:
#   1. Docker Compose로 PostgreSQL을 시작합니다
#   2. Spring Boot 서버를 local 프로파일로 시작합니다
#   3. scripts/test.sh를 실행합니다
#   4. StuckProcessingRecoveryScheduler 시나리오를 재기동 기반으로 검증합니다
#   5. 테스트 완료 후 서버 유지 여부를 선택합니다
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
LOG_FILE="/tmp/notification-server-test.log"
SERVER_PID=""
APP_PID=""
LOG_TAIL_PID=""
KEEP_SERVER="${KEEP_SERVER:-n}"
RUN_BASE_TESTS="${RUN_BASE_TESTS:-y}"
STUCK_PHASE_BOOT_ARGS="${STUCK_PHASE_BOOT_ARGS:---notification.stuck-recovery.fixed-delay-ms=2000 --notification.stuck-recovery.stuck-timeout-seconds=5 --notification.handler.execution-idempotency-ttl=6s --notification.retry.max-send-try-count=1}"
STUCK_RECOVERY_BOOT_ARGS="${STUCK_RECOVERY_BOOT_ARGS:-${STUCK_PHASE_BOOT_ARGS} --notification.relay.execution.fixed-delay-ms=60000}"
STUCK_HANG_TIMEOUT_MS="${STUCK_HANG_TIMEOUT_MS:-60000}"
STUCK_RESTORE_UPDATED_AT_SECONDS="${STUCK_RESTORE_UPDATED_AT_SECONDS:-10}"

# -- 색상 --------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
DIM='\033[2m'
BOLD='\033[1m'
NC='\033[0m'

log()      { echo -e "${CYAN}[runner]${NC} $1"; }
log_ok()   { echo -e "${GREEN}[runner]${NC} $1"; }
log_fail() { echo -e "${RED}[runner]${NC} $1"; }

future_time() {
    if [[ "$(uname)" == "Darwin" ]]; then
        date -u -v+"$1"S '+%Y-%m-%dT%H:%M:%SZ'
    else
        date -u -d "+$1 seconds" '+%Y-%m-%dT%H:%M:%SZ'
    fi
}

api() {
    local method=$1 url=$2 body=${3:-}
    local full_url="${BASE_URL}${url}"
    if [[ -n "$body" ]]; then
        curl -s -w "\n%{http_code}" -X "$method" "$full_url" \
            -H 'Content-Type: application/json' -d "$body"
    else
        curl -s -w "\n%{http_code}" -X "$method" "$full_url"
    fi
}

http_code_from_response() {
    echo "$1" | tail -1
}

body_from_response() {
    echo "$1" | sed '$d'
}

set_mock_email() {
    local fail_rate=$1 fail_type=${2:-TRANSIENT} latency=${3:-50} hang_rate=${4:-0.0} hang_timeout_ms=${5:-30000}
    curl -sf -X POST "${BASE_URL}/actuator/mock-email" \
        -H 'Content-Type: application/json' \
        -d "{\"failRate\": ${fail_rate}, \"failType\": \"${fail_type}\", \"latencyMs\": ${latency}, \"hangRate\": ${hang_rate}, \"hangTimeoutMs\": ${hang_timeout_ms}}" > /dev/null
}

ensure_postgres_ready() {
    if ! docker compose ps --services --status running 2>/dev/null | grep -qx "postgres"; then
        log "PostgreSQL 컨테이너 시작 중..."
        docker compose up -d postgres > /dev/null
    fi

    log "PostgreSQL 준비 대기 (최대 30초)"
    for i in $(seq 1 30); do
        if docker compose exec -T postgres pg_isready -U notification -d notification > /dev/null 2>&1; then
            log_ok "PostgreSQL 준비 완료 (${i}초)"
            return 0
        fi
        sleep 1
    done

    log_fail "PostgreSQL 준비 시간 초과"
    return 1
}

psql_exec() {
    local sql=$1
    docker compose exec -T postgres psql -U notification -d notification -v ON_ERROR_STOP=1 -c "$sql" > /dev/null
}

prepare_job_for_stuck_recovery_restart() {
    local job_id=$1

    log "Stuck 복구용으로 Job ${job_id} 숨김 및 미완료 실행 이벤트 정리"
    psql_exec "UPDATE notification_jobs SET deleted = true WHERE id = ${job_id};"
    psql_exec "DELETE FROM event_publication WHERE event_type = 'com.notification.notification.event.NotificationJobExecutionEvent' AND serialized_event LIKE '%\\\"jobId\\\":${job_id},%';"
}

restore_job_for_stuck_recovery() {
    local job_id=$1

    log "Stuck 복구 대상으로 Job ${job_id} 재노출"
    psql_exec "UPDATE notification_jobs SET deleted = false, updated_at = now() - interval '${STUCK_RESTORE_UPDATED_AT_SECONDS} seconds' WHERE id = ${job_id};"
}

wait_for_port_release() {
    local timeout=${1:-15}
    for _ in $(seq 1 "$timeout"); do
        if ! lsof -ti:8080 >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    return 1
}

refresh_app_pid() {
    APP_PID=$(lsof -ti:8080 2>/dev/null | head -1 || true)
    if [[ -n "$APP_PID" ]]; then
        log "애플리케이션 PID=${APP_PID}"
    fi
}

start_log_tail() {
    if [[ -n "$LOG_TAIL_PID" ]] && kill -0 "$LOG_TAIL_PID" 2>/dev/null; then
        return
    fi

    log "서버 로그 실시간 출력 시작"
    echo -e "${YELLOW}────────────────────────────────────────────────────────${NC}"
    echo -e "${YELLOW}  서버 로그 (주요 이벤트 필터링)${NC}"
    echo -e "${YELLOW}────────────────────────────────────────────────────────${NC}"

    tail -f "$LOG_FILE" 2>/dev/null | while IFS= read -r line; do
        if echo "$line" | grep -qE '\[(UC:|Handler:|Relay:|Orchestrator|SendExecutor|Classifier|Recovery(:[^]]+)?|StatusResolver)\]'; then
            echo -e "${DIM}  [LOG] $line${NC}"
        fi
    done &
    LOG_TAIL_PID=$!
}

stop_log_tail() {
    if [[ -n "$LOG_TAIL_PID" ]] && kill -0 "$LOG_TAIL_PID" 2>/dev/null; then
        kill "$LOG_TAIL_PID" 2>/dev/null || true
        wait "$LOG_TAIL_PID" 2>/dev/null || true
    fi
    LOG_TAIL_PID=""
}

wait_for_server_ready() {
    local timeout=${1:-60}
    log "서버 준비 대기 (최대 ${timeout}초)"
    for i in $(seq 1 "$timeout"); do
        if curl -sf "${BASE_URL}/actuator/health" > /dev/null 2>&1; then
            log_ok "서버 준비 완료 (${i}초)"
            refresh_app_pid
            return 0
        fi
        if [[ -n "$SERVER_PID" ]] && ! kill -0 "$SERVER_PID" 2>/dev/null; then
            log_fail "서버가 시작 중 종료되었습니다:"
            echo ""
            tail -30 "$LOG_FILE"
            return 1
        fi
        sleep 1
    done

    log_fail "서버 시작 시간 초과 (${timeout}초)"
    tail -30 "$LOG_FILE"
    return 1
}

start_server() {
    local extra_args=${1:-}
    local boot_args="--spring.profiles.active=local"
    if [[ -n "$extra_args" ]]; then
        boot_args="${boot_args} ${extra_args}"
    fi

    ensure_postgres_ready

    echo "" >> "$LOG_FILE"
    echo "===== bootRun ${boot_args} =====" >> "$LOG_FILE"

    log "서버 시작 (${boot_args})"
    cd "$PROJECT_DIR"
    ./gradlew bootRun --args="${boot_args}" >> "$LOG_FILE" 2>&1 &
    SERVER_PID=$!
    log "Gradle PID=${SERVER_PID}"
    log "로그 파일: ${LOG_FILE}"

    wait_for_server_ready 60
}

stop_server() {
    if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
        log "애플리케이션 종료 (PID=${APP_PID})"
        kill "$APP_PID" 2>/dev/null || true
        wait "$APP_PID" 2>/dev/null || true
    fi
    APP_PID=""

    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        log "Gradle 실행 종료 (PID=${SERVER_PID})"
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    SERVER_PID=""

    local port_pids
    port_pids=$(lsof -ti:8080 2>/dev/null || true)
    if [[ -n "$port_pids" ]]; then
        echo "$port_pids" | xargs kill 2>/dev/null || true
    fi

    wait_for_port_release 15 || true
}

crash_server() {
    log "Stuck 시나리오를 위해 애플리케이션 강제 종료"

    if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
        kill -9 "$APP_PID" 2>/dev/null || true
        wait "$APP_PID" 2>/dev/null || true
    fi
    APP_PID=""

    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    SERVER_PID=""

    local port_pids
    port_pids=$(lsof -ti:8080 2>/dev/null || true)
    if [[ -n "$port_pids" ]]; then
        echo "$port_pids" | xargs kill -9 2>/dev/null || true
    fi

    wait_for_port_release 15 || true
}

cleanup() {
    echo ""
    log "정리 중..."
    stop_log_tail

    if [[ "$KEEP_SERVER" == "y" ]]; then
        log "서버 유지 요청으로 애플리케이션 종료는 건너뜁니다."
        return
    fi

    stop_server
    log_ok "정리 완료"
}
trap cleanup EXIT INT TERM

run_stuck_processing_test() {
    local ts template_code template_id key job_id notification_id
    local result http_code body job_status notif_status failure_reason ready_to_crash=false recovered=false

    ts=$(date +%s)
    template_code="stuck-${ts}"

    echo ""
    echo -e "${BOLD}${CYAN}============================================================${NC}"
    echo -e "${BOLD}${CYAN}  [추가] Stuck Processing Recovery${NC}"
    echo -e "${BOLD}${CYAN}============================================================${NC}"
    echo ""

    log "Stuck 시나리오용 템플릿 생성"
    result=$(api POST "/api/templates" "{
        \"code\": \"${template_code}\",
        \"channel\": \"EMAIL\",
        \"locale\": \"ko\",
        \"titleTemplate\": \"Stuck {{name}}\",
        \"bodyTemplate\": \"{{name}}님, 주문 {{orderId}} 알림입니다.\",
        \"description\": \"Stuck recovery 검증용 템플릿\",
        \"variables\": [
            {\"name\": \"name\", \"dataType\": \"STRING\", \"required\": true, \"exampleValue\": \"홍길동\", \"description\": \"수신자 이름\"},
            {\"name\": \"orderId\", \"dataType\": \"STRING\", \"required\": true, \"exampleValue\": \"ORD-001\", \"description\": \"주문번호\"}
        ]
    }")
    http_code=$(http_code_from_response "$result")
    body=$(body_from_response "$result")
    if [[ "$http_code" != "201" ]]; then
        log_fail "Stuck 템플릿 생성 실패: HTTP ${http_code}"
        return 1
    fi
    template_id=$(echo "$body" | jq -r '.data.id' 2>/dev/null || echo "")
    if [[ -z "$template_id" || "$template_id" == "null" ]]; then
        log_fail "Stuck 템플릿 ID 추출 실패"
        return 1
    fi
    log_ok "Stuck 템플릿 생성 완료 (ID=${template_id})"

    log "Mock email hang 활성화"
    set_mock_email 0.0 TRANSIENT 0 1.0 "$STUCK_HANG_TIMEOUT_MS"

    result=$(api POST "/api/notification-jobs/key")
    http_code=$(http_code_from_response "$result")
    body=$(body_from_response "$result")
    if [[ "$http_code" != "200" ]]; then
        log_fail "Stuck 멱등키 발급 실패: HTTP ${http_code}"
        return 1
    fi
    key=$(echo "$body" | jq -r '.data.idempotencyKey' 2>/dev/null || echo "")
    if [[ -z "$key" || "$key" == "null" ]]; then
        log_fail "Stuck 멱등키 추출 실패"
        return 1
    fi

    log "Stuck 검증 Job 생성"
    result=$(api POST "/api/notification-jobs" "{
        \"idempotencyKey\": \"${key}\",
        \"channel\": \"EMAIL\",
        \"templateCode\": \"${template_code}\",
        \"locale\": \"ko\",
        \"type\": \"ENROLLMENT_COMPLETE\",
        \"metadata\": {\"eventId\": \"evt-stuck-${ts}\", \"lectureId\": \"lec-stuck\"},
        \"scheduledAt\": \"$(future_time 3)\",
        \"recipients\": [
            {\"recipientId\": 9101, \"contact\": \"stuck@test.com\", \"variables\": {\"name\": \"Stuck\", \"orderId\": \"STUCK-001\"}}
        ]
    }")
    http_code=$(http_code_from_response "$result")
    body=$(body_from_response "$result")
    if [[ "$http_code" != "201" ]]; then
        log_fail "Stuck Job 생성 실패: HTTP ${http_code}"
        return 1
    fi
    job_id=$(echo "$body" | jq -r '.data.jobId' 2>/dev/null || echo "")
    if [[ -z "$job_id" || "$job_id" == "null" ]]; then
        log_fail "Stuck Job ID 추출 실패"
        return 1
    fi
    log_ok "Stuck Job 생성 완료 (jobId=${job_id})"

    log "Notification ID 조회 대기"
    for _ in $(seq 1 15); do
        body=$(curl -sf "${BASE_URL}/api/users/9101/notifications?size=20" 2>/dev/null || true)
        notification_id=$(echo "$body" | jq -r --arg job_id "$job_id" '.data.items[]? | select((.jobId | tostring) == $job_id) | .id' 2>/dev/null | head -1)
        if [[ -n "$notification_id" && "$notification_id" != "null" ]]; then
            break
        fi
        sleep 1
    done
    if [[ -z "$notification_id" || "$notification_id" == "null" ]]; then
        log_fail "Stuck Notification ID 조회 실패"
        return 1
    fi
    log_ok "Stuck Notification 조회 완료 (notificationId=${notification_id})"

    log "PROCESSING 상태와 발송 시작 로그 대기"
    for _ in $(seq 1 25); do
        body=$(curl -sf "${BASE_URL}/api/notification-jobs/${job_id}" 2>/dev/null || true)
        job_status=$(echo "$body" | jq -r '.data.status // empty' 2>/dev/null || echo "")

        if [[ "$job_status" == "PROCESSING" ]] && grep -Eq "Job ${job_id} .* targets: 1" "$LOG_FILE"; then
            ready_to_crash=true
            break
        fi
        sleep 1
    done

    if [[ "$ready_to_crash" != "true" ]]; then
        log_fail "Stuck 진입 상태를 관찰하지 못했습니다. job=${job_status:-UNKNOWN}"
        set_mock_email 0.3 TRANSIENT 50 0.0 30000 || true
        curl -sf -X DELETE "${BASE_URL}/api/templates/${template_id}" > /dev/null 2>&1 || true
        return 1
    fi
    sleep 2
    log_ok "Stuck 진입 상태 확인 (job=${job_status}, send-start logged)"

    crash_server
    prepare_job_for_stuck_recovery_restart "$job_id"
    if ! start_server "$STUCK_RECOVERY_BOOT_ARGS"; then
        log_fail "Stuck recovery 검증용 서버 재기동 실패"
        restore_job_for_stuck_recovery "$job_id" || true
        return 1
    fi
    restore_job_for_stuck_recovery "$job_id"

    log "Stuck recovery 완료 대기"
    for _ in $(seq 1 20); do
        body=$(curl -sf "${BASE_URL}/api/notification-jobs/${job_id}" 2>/dev/null || true)
        job_status=$(echo "$body" | jq -r '.data.status // empty' 2>/dev/null || echo "")
        body=$(curl -sf "${BASE_URL}/api/notifications/${notification_id}" 2>/dev/null || true)
        notif_status=$(echo "$body" | jq -r '.data.status // empty' 2>/dev/null || echo "")
        failure_reason=$(echo "$body" | jq -r '.data.lastFailureReason // empty' 2>/dev/null || echo "")

        if [[ "$job_status" == "FAILED" && "$notif_status" == "DEAD_LETTER" ]] \
            && echo "$failure_reason" | grep -Fq "Stuck SENDING recovery" \
            && grep -Fq "[Recovery:StuckProcessing] Job ${job_id} recovered" "$LOG_FILE"; then
            recovered=true
            break
        fi
        sleep 1
    done

    set_mock_email 0.3 TRANSIENT 50 0.0 30000 || true
    curl -sf -X DELETE "${BASE_URL}/api/templates/${template_id}" > /dev/null 2>&1 || true

    if [[ "$recovered" != "true" ]]; then
        if [[ "$job_status" == "FAILED" && "$notif_status" == "PENDING" ]]; then
            log_fail "Stuck recovery 검증 실패. job은 FAILED로 복구됐지만 notification은 PENDING으로 남았습니다. SENDING 상태가 crash 전에 영속화되지 않은 것으로 보입니다."
        else
            log_fail "Stuck recovery 검증 실패. job=${job_status:-UNKNOWN}, notification=${notif_status:-UNKNOWN}, reason=${failure_reason:-empty}"
        fi
        return 1
    fi

    log_ok "Stuck recovery 완료: job=${job_status}, notification=${notif_status}"
    log_ok "Stuck recovery 사유 기록: ${failure_reason}"
}

# ================================================================
# Step 1: Docker 확인
# ================================================================

log "Step 1: Docker PostgreSQL 확인"
cd "$PROJECT_DIR"
if docker compose ps --services --status running 2>/dev/null | grep -qx "postgres"; then
    log_ok "PostgreSQL 컨테이너 실행 중"
else
    log "PostgreSQL 컨테이너 시작 중..."
    docker compose up -d postgres
    if docker compose ps --services --status running 2>/dev/null | grep -qx "postgres"; then
        log_ok "PostgreSQL 시작 완료"
    else
        log_fail "PostgreSQL 시작 실패. Docker가 실행 중인지 확인하세요."
        exit 1
    fi
fi

ensure_postgres_ready

# ================================================================
# Step 2: 기존 서버 확인
# ================================================================

log "Step 2: 기존 서버 확인"
if curl -sf "${BASE_URL}/actuator/health" > /dev/null 2>&1; then
    log "기존 서버 발견. 종료합니다..."
    lsof -ti:8080 | xargs kill 2>/dev/null || true
    wait_for_port_release 15 || true
fi

# ================================================================
# Step 3: 로그 tail + 기본 서버 시작
# ================================================================

> "$LOG_FILE"
start_log_tail
start_server

# ================================================================
# Step 4: 기본 통합 테스트 실행
# ================================================================

echo ""
echo -e "${BOLD}${CYAN}============================================================${NC}"
echo -e "${BOLD}${CYAN}  통합 테스트 시작${NC}"
echo -e "${BOLD}${CYAN}============================================================${NC}"
echo ""

set +e
if [[ "$RUN_BASE_TESTS" == "y" ]]; then
    VERBOSE="${VERBOSE:-0}" bash "${SCRIPT_DIR}/test.sh"
    TEST_EXIT=$?
else
    log "RUN_BASE_TESTS=${RUN_BASE_TESTS}: 기본 통합 테스트를 건너뜁니다."
    TEST_EXIT=0
fi
set -e

STUCK_EXIT=0
STUCK_RAN=false
if [[ $TEST_EXIT -eq 0 ]]; then
    if [[ "$RUN_BASE_TESTS" == "y" ]]; then
        log "기본 통합 테스트 통과. Stuck 전용 서버로 재기동합니다."
    else
        log "기본 통합 테스트를 건너뛰고 Stuck 전용 서버로 재기동합니다."
    fi
    stop_server
    if start_server "$STUCK_PHASE_BOOT_ARGS"; then
        STUCK_RAN=true
        set +e
        run_stuck_processing_test
        STUCK_EXIT=$?
        set -e
    else
        STUCK_RAN=true
        STUCK_EXIT=1
    fi
else
    log_fail "기본 통합 테스트 실패로 Stuck 시나리오는 건너뜁니다."
fi

echo ""
echo -e "${YELLOW}────────────────────────────────────────────────────────${NC}"

if [[ "$RUN_BASE_TESTS" != "y" ]]; then
    log "기본 통합 테스트 건너뜀"
elif [[ $TEST_EXIT -eq 0 ]]; then
    log_ok "기본 통합 테스트 통과"
else
    log_fail "기본 통합 테스트 실패 (exit code=${TEST_EXIT})"
fi

if [[ "$STUCK_RAN" != "true" ]]; then
    log "Stuck recovery 테스트 건너뜀"
elif [[ $STUCK_EXIT -eq 0 ]]; then
    log_ok "Stuck recovery 테스트 통과"
else
    log_fail "Stuck recovery 테스트 실패 (exit code=${STUCK_EXIT})"
fi

echo ""
log "전체 서버 로그: ${LOG_FILE}"
log "서버 로그 확인: tail -f ${LOG_FILE}"
echo ""

# ================================================================
# Step 5: 서버 유지 여부
# ================================================================

echo -e "${BOLD}서버를 계속 유지할까요?${NC}"
echo "  - Enter 또는 'n': 서버 종료"
echo "  - 'y': 서버 유지 (직접 종료 필요)"
echo ""
if [[ -t 0 ]]; then
    read -r -t 30 -p "선택 (y/n): " KEEP_SERVER || true
else
    log "비대화형 실행: KEEP_SERVER=${KEEP_SERVER}"
fi

if [[ "$KEEP_SERVER" == "y" ]]; then
    log_ok "서버를 유지합니다. (PID=${APP_PID:-unknown})"
    if [[ -n "$APP_PID" ]]; then
        log "종료: kill ${APP_PID}"
    fi
    log "로그: tail -f ${LOG_FILE}"
else
    log "서버를 종료합니다..."
fi

FINAL_EXIT=0
if [[ $TEST_EXIT -ne 0 || $STUCK_EXIT -ne 0 ]]; then
    FINAL_EXIT=1
fi

exit "$FINAL_EXIT"
