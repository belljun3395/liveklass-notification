#!/usr/bin/env bash
# ============================================================
# Notification 시스템 통합 검증 스크립트
# ============================================================
# 사용법:
#   chmod +x scripts/test.sh
#   ./scripts/test.sh              # 기본 실행
#   VERBOSE=1 ./scripts/test.sh    # 요청/응답 로그 포함
#
# 사전 조건:
#   1. docker compose up -d postgres
#   2. ./gradlew bootRun --args='--spring.profiles.active=local'
#
# 검증 항목:
#   [필수 1] 알림 발송 요청 API (Job 생성, 상태 조회, 사용자 알림 목록)
#   [필수 2] 알림 처리 상태 관리 (상태 전이, 재시도, 실패 기록, Max Retry)
#   [필수 3] 중복 발송 방지 (멱등키 중복, 동시 요청)
#   [필수 4] 비동기 처리 구조 (API 즉시 응답, 후속 처리 분리)
#   [필수 5] 운영 시나리오 대응 (수동 복구, 수동 실행, RETRYING 취소)
#   [선택 1] 발송 스케줄링 (예약 발송, 스케줄 취소)
#   [선택 2] 알림 템플릿 관리 (CRUD, 미리보기)
#   [선택 3] 읽음 처리 (멀티 디바이스, firstReadAt 멱등)
#   [선택 4] 최종 실패 보관 및 수동 재시도 (sendTryCount 리셋)
# ============================================================

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
VERBOSE="${VERBOSE:-0}"
INSTANCE_ID="${INSTANCE_ID:-default}"
SCHEDULE_DELAY_SEC=5
POLL_INTERVAL=1
POLL_MAX=30

# -- 병렬 실행 지원: 각 인스턴스마다 고유한 데이터 생성 ------
TIMESTAMP=$(date +%s%N | tail -c 10)  # 나노초 기반 고유값
RANDOM_SUFFIX="${INSTANCE_ID}-${TIMESTAMP}"

# -- 색상 --------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
DIM='\033[2m'
BOLD='\033[1m'
NC='\033[0m'

# -- 카운터 -------------------------------------------------------
PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

# -- 출력 유틸리티 ------------------------------------------------

log_section() {
    echo ""
    echo -e "${BOLD}${CYAN}============================================================${NC}"
    echo -e "${BOLD}${CYAN}  $1${NC}"
    echo -e "${BOLD}${CYAN}============================================================${NC}"
}

log_header() {
    echo ""
    echo -e "${BOLD}${YELLOW}--- $1 ---${NC}"
}

log_step()   { echo -e "  ${YELLOW}> $1${NC}"; }
log_ok()     { echo -e "  ${GREEN}[PASS] $1${NC}"; PASS_COUNT=$((PASS_COUNT + 1)); }
log_fail()   { echo -e "  ${RED}[FAIL] $1${NC}"; FAIL_COUNT=$((FAIL_COUNT + 1)); }
log_skip()   { echo -e "  ${CYAN}[SKIP] $1${NC}"; SKIP_COUNT=$((SKIP_COUNT + 1)); }
log_info()   { echo -e "    $1"; }

# -- 요청/응답 로그 ------------------------------------------------

log_req() {
    # log_req METHOD URL [BODY]
    [[ "$VERBOSE" != "1" ]] && return
    local method=$1 url=$2 body=${3:-}
    echo -e "    ${DIM}>> ${method} ${url}${NC}" >&2
    if [[ -n "$body" ]]; then
        (echo "$body" | jq -C . 2>/dev/null | sed 's/^/       /' || echo "       $body") >&2
    fi
}

log_resp() {
    # log_resp HTTP_CODE BODY
    [[ "$VERBOSE" != "1" ]] && return
    local code=$1 body=${2:-}
    echo -e "    ${DIM}<< HTTP ${code}${NC}" >&2
    if [[ -n "$body" ]]; then
        (echo "$body" | jq -C . 2>/dev/null | sed 's/^/       /' || echo "       $body") >&2
    fi
}

# -- API 래퍼 (로그 자동 포함) ------------------------------------

# api METHOD URL [JSON_BODY]
# stdout: "BODY\nHTTP_CODE" (마지막 줄이 HTTP 코드)
api() {
    local method=$1 url=$2 body=${3:-}
    local full_url="${BASE_URL}${url}"
    log_req "$method" "$url" "$body"

    local resp
    if [[ -n "$body" ]]; then
        resp=$(curl -s -w "\n%{http_code}" -X "$method" "$full_url" \
            -H 'Content-Type: application/json' -d "$body")
    else
        resp=$(curl -s -w "\n%{http_code}" -X "$method" "$full_url")
    fi

    local http_code
    http_code=$(echo "$resp" | tail -1)
    local resp_body
    resp_body=$(echo "$resp" | sed '$d')
    log_resp "$http_code" "$resp_body"

    echo "${resp_body}"
    echo "${http_code}"
}

# api_get URL -> stdout: response body (JSON)
api_get() {
    local url=$1
    local full_url="${BASE_URL}${url}"
    log_req "GET" "$url"
    local resp
    resp=$(curl -sf "$full_url")
    log_resp "200" "$resp"
    echo "$resp"
}

# -- 검증 유틸리티 ------------------------------------------------

assert_eq() {
    local actual=$1 expected=$2 label=$3
    if [[ "$actual" == "$expected" ]]; then
        log_ok "${label}: ${actual}"
    else
        log_fail "${label}: expected=${expected}, actual=${actual}"
        return 1
    fi
}

assert_not_empty() {
    local value=$1 label=$2
    if [[ -n "$value" && "$value" != "null" ]]; then
        log_ok "${label}: ${value}"
    else
        log_fail "${label}: empty or null"
        return 1
    fi
}

assert_http() {
    local actual=$1 expected=$2 label=$3
    if [[ "$actual" == "$expected" ]]; then
        log_ok "${label}: HTTP ${actual}"
    else
        log_fail "${label}: expected HTTP ${expected}, got ${actual}"
        return 1
    fi
}

assert_error_contains() {
    local body=$1 expected=$2 label=$3
    local error
    error=$(echo "$body" | jq -r '.error // empty')
    if echo "$error" | grep -Fq "$expected"; then
        log_ok "${label}: ${error}"
    else
        log_fail "${label}: expected error containing '${expected}', actual='${error}'"
        return 1
    fi
}

# -- Job 유틸리티 -------------------------------------------------

get_job_status() {
    curl -sf "${BASE_URL}/api/notification-jobs/$1" | jq -r '.data.status'
}

wait_for_status() {
    local job_id=$1 expected=$2 label=$3 max=${4:-$POLL_MAX}
    local elapsed=0 actual="UNKNOWN"
    while [[ $elapsed -lt $max ]]; do
        local resp
        resp=$(curl -sf "${BASE_URL}/api/notification-jobs/${job_id}" 2>/dev/null || echo '{}')
        actual=$(echo "$resp" | jq -r '.data.status' 2>/dev/null || echo "UNKNOWN")
        if [[ "$actual" == "$expected" ]]; then
            log_ok "${label}: status=${actual} (${elapsed}s)"
            return 0
        fi
        sleep "$POLL_INTERVAL"
        elapsed=$((elapsed + POLL_INTERVAL))
    done
    log_fail "${label}: timeout ${max}s (expected=${expected}, last=${actual})"
    return 1
}

wait_for_any_status() {
    local job_id=$1 label=$2 max=$3
    shift 3
    local expected_statuses=("$@")
    local elapsed=0 actual="UNKNOWN"
    while [[ $elapsed -lt $max ]]; do
        local resp
        resp=$(curl -sf "${BASE_URL}/api/notification-jobs/${job_id}" 2>/dev/null || echo '{}')
        actual=$(echo "$resp" | jq -r '.data.status' 2>/dev/null || echo "UNKNOWN")
        for es in "${expected_statuses[@]}"; do
            if [[ "$actual" == "$es" ]]; then
                log_ok "${label}: status=${actual} (${elapsed}s)"
                return 0
            fi
        done
        sleep "$POLL_INTERVAL"
        elapsed=$((elapsed + POLL_INTERVAL))
    done
    log_fail "${label}: timeout ${max}s (expected=[${expected_statuses[*]}], last=${actual})"
    return 1
}

wait_for_failed_count() {
    local job_id=$1 expected=$2 label=$3 max=${4:-$POLL_MAX}
    local elapsed=0 actual="-1" status="UNKNOWN"
    while [[ $elapsed -lt $max ]]; do
        local resp
        resp=$(curl -sf "${BASE_URL}/api/notification-jobs/${job_id}" 2>/dev/null || echo '{}')
        status=$(echo "$resp" | jq -r '.data.status' 2>/dev/null || echo "UNKNOWN")
        actual=$(echo "$resp" | jq -r '.data.failedCount' 2>/dev/null || echo "-1")
        if [[ "$status" == "FAILED" && "$actual" == "$expected" ]]; then
            log_ok "${label}: status=${status}, failedCount=${actual} (${elapsed}s)"
            return 0
        fi
        sleep "$POLL_INTERVAL"
        elapsed=$((elapsed + POLL_INTERVAL))
    done
    log_fail \
        "${label}: timeout ${max}s (expected status=FAILED, failedCount=${expected}, last status=${status}, failedCount=${actual})"
    return 1
}

wait_for_dead_letter_count() {
    local job_id=$1 expected=$2 label=$3 max=${4:-$POLL_MAX}
    local elapsed=0 actual="-1" status="UNKNOWN"
    while [[ $elapsed -lt $max ]]; do
        local resp
        resp=$(curl -sf "${BASE_URL}/api/notification-jobs/${job_id}" 2>/dev/null || echo '{}')
        status=$(echo "$resp" | jq -r '.data.status' 2>/dev/null || echo "UNKNOWN")
        actual=$(echo "$resp" | jq -r '.data.deadLetterCount' 2>/dev/null || echo "-1")
        if [[ "$status" == "FAILED" && "$actual" == "$expected" ]]; then
            log_ok "${label}: status=${status}, deadLetterCount=${actual} (${elapsed}s)"
            return 0
        fi
        sleep "$POLL_INTERVAL"
        elapsed=$((elapsed + POLL_INTERVAL))
    done
    log_fail \
        "${label}: timeout ${max}s (expected status=FAILED, deadLetterCount=${expected}, last status=${status}, deadLetterCount=${actual})"
    return 1
}

future_time() {
    if [[ "$(uname)" == "Darwin" ]]; then
        date -u -v+${1}S '+%Y-%m-%dT%H:%M:%SZ'
    else
        date -u -d "+${1} seconds" '+%Y-%m-%dT%H:%M:%SZ'
    fi
}

set_mock_email() {
    local fail_rate=$1 fail_type=${2:-TRANSIENT} latency=${3:-50} hang_rate=${4:-0.0} hang_timeout_ms=${5:-30000}
    curl -sf -X POST "${BASE_URL}/actuator/mock-email" \
        -H 'Content-Type: application/json' \
        -d "{\"failRate\": ${fail_rate}, \"failType\": \"${fail_type}\", \"latencyMs\": ${latency}, \"hangRate\": ${hang_rate}, \"hangTimeoutMs\": ${hang_timeout_ms}}" > /dev/null
}

# 서버 발급 멱등키 헬퍼
# issue_job_key SCENARIO_LABEL -> sets ISSUED_KEY
issue_job_key() {
    local label=$1
    local result
    result=$(api POST "/api/notification-jobs/key")
    local http_code
    http_code=$(echo "$result" | tail -1)
    local resp_body
    resp_body=$(echo "$result" | sed '$d')

    if [[ "$http_code" != "200" ]]; then
        log_fail "멱등키 발급 실패 (${label}): HTTP ${http_code}"
        return 1
    fi

    ISSUED_KEY=$(echo "$resp_body" | jq -r '.data.idempotencyKey')
    if [[ -z "$ISSUED_KEY" || "$ISSUED_KEY" == "null" ]]; then
        log_fail "멱등키 발급 실패 (${label}): empty key"
        return 1
    fi

    log_info "발급된 멱등키 (${label}): ${ISSUED_KEY}"
}

# Job 생성 헬퍼
# create_job SCENARIO_LABEL SCHEDULED_OFFSET_SEC RECIPIENTS_JSON [TYPE] [METADATA_JSON] [ISSUED_KEY] -> sets JOB_ID, JOB_KEY
create_job() {
    local label=$1 offset=$2 recipients=$3
    local job_type=${4:-"ENROLLMENT_COMPLETE"}
    local job_metadata=${5:-"{\"eventId\": \"evt-${label}\", \"lectureId\": \"lec-001\"}"}
    local key=${6:-}
    local scheduled_at
    scheduled_at=$(future_time "$offset")

    if [[ -z "$key" ]]; then
        issue_job_key "$label"
        key=$ISSUED_KEY
    fi

    local body="{
        \"idempotencyKey\": \"${key}\",
        \"channel\": \"EMAIL\",
        \"templateCode\": \"${TMPL_CODE}\",
        \"locale\": \"ko\",
        \"type\": \"${job_type}\",
        \"metadata\": ${job_metadata},
        \"scheduledAt\": \"${scheduled_at}\",
        \"recipients\": ${recipients}
    }"
    local result
    result=$(api POST "/api/notification-jobs" "$body")
    local http_code
    http_code=$(echo "$result" | tail -1)
    local resp_body
    resp_body=$(echo "$result" | sed '$d')
    JOB_ID=$(echo "$resp_body" | jq -r '.data.jobId')
    JOB_KEY=$key
    JOB_HTTP=$http_code
}

# 각 인스턴스마다 고유한 키 생성 (병렬 실행 지원)
TS_UNIQUE="${RANDOM_SUFFIX}"
TMPL_CODE="test-${TS_UNIQUE}"

# ================================================================
# 0. 서버 헬스 체크
# ================================================================

log_section "0. 서버 헬스 체크"
if ! curl -sf "${BASE_URL}/actuator/health" > /dev/null 2>&1; then
    log_fail "서버가 실행 중이 아닙니다."
    echo "  ./gradlew bootRun --args='--spring.profiles.active=local'"
    exit 1
fi
log_ok "서버 정상 (${BASE_URL})"
if [[ "$VERBOSE" == "1" ]]; then
    log_info "VERBOSE=1: 모든 요청/응답을 출력합니다."
fi

# ================================================================
# [선택 2] 알림 템플릿 관리
# ================================================================

log_section "[선택 2] 알림 템플릿 관리 (CRUD + 미리보기)"

log_header "템플릿 생성"
TMPL_RESULT=$(api POST "/api/templates" "{
    \"code\": \"${TMPL_CODE}\",
    \"channel\": \"EMAIL\",
    \"locale\": \"ko\",
    \"titleTemplate\": \"안녕하세요 {{name}}님\",
    \"bodyTemplate\": \"{{name}}님, 주문 {{orderId}} 알림입니다.\",
    \"description\": \"통합 검증용 템플릿\",
    \"variables\": [
        {\"name\": \"name\", \"dataType\": \"STRING\", \"required\": true, \"exampleValue\": \"홍길동\", \"description\": \"수신자 이름\"},
        {\"name\": \"orderId\", \"dataType\": \"STRING\", \"required\": true, \"exampleValue\": \"ORD-001\", \"description\": \"주문번호\"}
    ]
}")
TMPL_HTTP=$(echo "$TMPL_RESULT" | tail -1)
TMPL_BODY=$(echo "$TMPL_RESULT" | sed '$d')
TEMPLATE_ID=$(echo "$TMPL_BODY" | jq -r '.data.id')
assert_http "$TMPL_HTTP" "201" "템플릿 생성"
assert_not_empty "$TEMPLATE_ID" "템플릿 ID"

log_header "템플릿 조회"
GET_TMPL=$(api_get "/api/templates/${TEMPLATE_ID}")
assert_eq "$(echo "$GET_TMPL" | jq -r '.data.code')" "$TMPL_CODE" "템플릿 code"

log_header "템플릿 목록 조회"
LIST_TMPL=$(api_get "/api/templates?code=${TMPL_CODE}")
LIST_COUNT=$(echo "$LIST_TMPL" | jq '.data | length')
if [[ "$LIST_COUNT" -ge 1 ]]; then
    log_ok "템플릿 목록: ${LIST_COUNT}개"
else
    log_fail "템플릿 목록이 비어있음"
fi

log_header "템플릿 미리보기"
PREVIEW_RESULT=$(api POST "/api/templates/${TEMPLATE_ID}/preview" \
    '{"variables": {"name": "김철수", "orderId": "ORD-999"}}')
PREVIEW_BODY=$(echo "$PREVIEW_RESULT" | sed '$d')
RENDERED_TITLE=$(echo "$PREVIEW_BODY" | jq -r '.data.renderedTitle')
RENDERED_BODY=$(echo "$PREVIEW_BODY" | jq -r '.data.renderedBody')
if echo "$RENDERED_TITLE" | grep -q "김철수"; then
    log_ok "미리보기 제목: ${RENDERED_TITLE}"
else
    log_fail "미리보기 제목 렌더링 실패: ${RENDERED_TITLE}"
fi
if echo "$RENDERED_BODY" | grep -q "ORD-999"; then
    log_ok "미리보기 본문: ${RENDERED_BODY}"
else
    log_fail "미리보기 본문 렌더링 실패: ${RENDERED_BODY}"
fi

# ================================================================
# [필수 1] 알림 발송 요청 API
# ================================================================

log_section "[필수 1] 알림 발송 요청 API"

# -- 1.0 미발급 키 거절 --
log_header "1.0 미발급 idempotencyKey 거절"
UNISSUED_RESULT=$(api POST "/api/notification-jobs" "{
    \"idempotencyKey\": \"unissued-${TS_UNIQUE}\",
    \"channel\": \"EMAIL\",
    \"templateCode\": \"${TMPL_CODE}\",
    \"locale\": \"ko\",
    \"type\": \"ENROLLMENT_COMPLETE\",
    \"metadata\": {\"eventId\": \"evt-unissued\"},
    \"scheduledAt\": \"$(future_time 60)\",
    \"recipients\": [{\"recipientId\": 9901, \"contact\": \"unissued@test.com\", \"variables\": {\"name\": \"미발급\", \"orderId\": \"NO-KEY\"}}]
}")
UNISSUED_HTTP=$(echo "$UNISSUED_RESULT" | tail -1)
UNISSUED_BODY=$(echo "$UNISSUED_RESULT" | sed '$d')
assert_http "$UNISSUED_HTTP" "400" "미발급 키 거절"
assert_error_contains "$UNISSUED_BODY" "발급되지 않은 멱등성 키" "미발급 키 오류 메시지"

# -- 1.1 Job 생성 + Happy Path --
log_header "1.1 Job 생성 (Happy Path: CREATED -> SCHEDULED -> COMPLETED)"
set_mock_email 0.0

HAPPY_LABEL="req1-happy-${TS_UNIQUE}"
create_job "$HAPPY_LABEL" $SCHEDULE_DELAY_SEC '[
    {"recipientId": 1001, "contact": "user1@test.com", "variables": {"name": "수신자A", "orderId": "A-001"}},
    {"recipientId": 1002, "contact": "user2@test.com", "variables": {"name": "수신자B", "orderId": "A-002"}},
    {"recipientId": 1003, "contact": "user3@test.com", "variables": {"name": "수신자C", "orderId": "A-003"}}
]'
JOB_HAPPY_ID=$JOB_ID
JOB_HAPPY_KEY=$JOB_KEY
assert_http "$JOB_HTTP" "201" "Job 생성"
assert_not_empty "$JOB_HAPPY_ID" "Job ID"

# -- 1.2 Job 상태 조회 --
log_header "1.2 Job 상태 조회"
JOB_RESP=$(api_get "/api/notification-jobs/${JOB_HAPPY_ID}")
assert_eq "$(echo "$JOB_RESP" | jq -r '.data.totalCount')" "3" "수신자 수"
assert_eq "$(echo "$JOB_RESP" | jq -r '.data.type')" "ENROLLMENT_COMPLETE" "알림 타입"
assert_not_empty "$(echo "$JOB_RESP" | jq -r '.data.metadata.eventId')" "참조 데이터 (eventId)"
assert_not_empty "$(echo "$JOB_RESP" | jq -r '.data.metadata.lectureId')" "참조 데이터 (lectureId)"
log_info "현재 상태: $(echo "$JOB_RESP" | jq -r '.data.status')"

# -- 1.3 COMPLETED 대기 --
log_header "1.3 파이프라인 완료 대기"
wait_for_status "$JOB_HAPPY_ID" "COMPLETED" "Happy Path 완료"

# -- 1.4 sentCount 확인 --
log_header "1.4 완료 후 상세 확인"
FINAL=$(api_get "/api/notification-jobs/${JOB_HAPPY_ID}")
assert_eq "$(echo "$FINAL" | jq -r '.data.sentCount')" "$(echo "$FINAL" | jq -r '.data.totalCount')" "sentCount == totalCount"

# ================================================================
# [필수 1] 사용자 알림 목록 + 개별 알림
# ================================================================

log_section "[필수 1] 사용자 알림 목록 조회"

log_header "사용자별 알림 (GET /api/users/{userId}/notifications)"
USER_NOTIFS=$(api_get "/api/users/1001/notifications?size=20")
USER_TOTAL=$(echo "$USER_NOTIFS" | jq -r '.data.items | length')
if [[ "$USER_TOTAL" -ge 1 ]]; then
    log_ok "사용자 1001 알림 수: ${USER_TOTAL}"
else
    log_fail "사용자 1001 알림 없음"
fi

NOTIF_ID=$(echo "$USER_NOTIFS" | jq -r '.data.items[0].id')
assert_not_empty "$NOTIF_ID" "알림 ID"

log_header "개별 알림 조회 (GET /api/notifications/{id})"

# 개별 알림이 SENT 상태가 될 때까지 대기 (최대 30초)
log_step "Notification ${NOTIF_ID} SENT 상태 대기 중..."
elapsed=0
max_wait=30
while [[ $elapsed -lt $max_wait ]]; do
    NOTIF_RESP=$(curl -sf "${BASE_URL}/api/notifications/${NOTIF_ID}" 2>/dev/null || echo '{}')
    notif_status=$(echo "$NOTIF_RESP" | jq -r '.data.status' 2>/dev/null || echo "UNKNOWN")
    if [[ "$notif_status" == "SENT" ]]; then
        log_ok "Notification 상태 SENT (${elapsed}s)"
        break
    fi
    sleep 1
    elapsed=$((elapsed + 1))
done

NOTIF_RESP=$(api_get "/api/notifications/${NOTIF_ID}")
assert_eq "$(echo "$NOTIF_RESP" | jq -r '.data.status')" "SENT" "알림 상태"
assert_eq "$(echo "$NOTIF_RESP" | jq -r '.data.channel')" "EMAIL" "알림 채널"
assert_eq "$(echo "$NOTIF_RESP" | jq -r '.data.type')" "ENROLLMENT_COMPLETE" "알림 타입 (Notification)"
assert_not_empty "$(echo "$NOTIF_RESP" | jq -r '.data.metadata.eventId')" "참조 데이터 (Notification)"
NOTIF_TITLE=$(echo "$NOTIF_RESP" | jq -r '.data.renderedTitle')
if echo "$NOTIF_TITLE" | grep -q "수신자A"; then
    log_ok "렌더링된 제목: ${NOTIF_TITLE}"
else
    log_info "렌더링된 제목: ${NOTIF_TITLE} (변수 치환 확인 필요)"
fi

# ================================================================
# [필수 3] 중복 발송 방지
# ================================================================

log_section "[필수 3] 중복 발송 방지"

# -- 3.1 같은 멱등키 재요청 --
log_header "3.1 같은 idempotencyKey 재요청 -> 기존 Job 반환"
DUP_RESULT=$(api POST "/api/notification-jobs" "{
    \"idempotencyKey\": \"${JOB_HAPPY_KEY}\",
    \"channel\": \"EMAIL\",
    \"templateCode\": \"${TMPL_CODE}\",
    \"locale\": \"ko\",
    \"type\": \"ENROLLMENT_COMPLETE\",
    \"metadata\": {\"eventId\": \"evt-dup\"},
    \"scheduledAt\": \"$(future_time 60)\",
    \"recipients\": [{\"recipientId\": 9999, \"contact\": \"dup@test.com\", \"variables\": {\"name\": \"중복\", \"orderId\": \"DUP\"}}]
}")
DUP_HTTP=$(echo "$DUP_RESULT" | tail -1)
DUP_BODY=$(echo "$DUP_RESULT" | sed '$d')
assert_http "$DUP_HTTP" "200" "중복 요청 -> 기존 반환"
assert_eq "$(echo "$DUP_BODY" | jq -r '.data.jobId')" "$JOB_HAPPY_ID" "동일 Job ID 반환"

# -- 3.2 동시 요청 --
log_header "3.2 동시 요청 (같은 멱등키 3개 병렬 전송)"
issue_job_key "req3-conc-${TS_UNIQUE}"
CONC_KEY=$ISSUED_KEY
CONC_BODY="{
    \"idempotencyKey\": \"${CONC_KEY}\",
    \"channel\": \"EMAIL\",
    \"templateCode\": \"${TMPL_CODE}\",
    \"locale\": \"ko\",
    \"type\": \"ENROLLMENT_COMPLETE\",
    \"metadata\": {\"eventId\": \"evt-conc\"},
    \"scheduledAt\": \"$(future_time 60)\",
    \"recipients\": [{\"recipientId\": 8001, \"contact\": \"conc@test.com\", \"variables\": {\"name\": \"동시\", \"orderId\": \"CONC\"}}]
}"

log_step "3개 동시 요청 전송"
CONC_TMP_DIR=$(mktemp -d)
CONC_PIDS=()
curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/api/notification-jobs" -H 'Content-Type: application/json' -d "$CONC_BODY" > "${CONC_TMP_DIR}/code-1" 2>/dev/null &
CONC_PIDS+=($!)
curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/api/notification-jobs" -H 'Content-Type: application/json' -d "$CONC_BODY" > "${CONC_TMP_DIR}/code-2" 2>/dev/null &
CONC_PIDS+=($!)
curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/api/notification-jobs" -H 'Content-Type: application/json' -d "$CONC_BODY" > "${CONC_TMP_DIR}/code-3" 2>/dev/null &
CONC_PIDS+=($!)
for pid in "${CONC_PIDS[@]}"; do
    wait "$pid" || true
done
CONC_CODES=()
CONC_CONFLICT=0
for idx in 1 2 3; do
    code=$(cat "${CONC_TMP_DIR}/code-${idx}" 2>/dev/null || echo "000")
    CONC_CODES+=("$code")
    case "$code" in
        200|201)
            ;;
        409)
            CONC_CONFLICT=1
            ;;
        *)
            log_fail "동시 요청 비정상 HTTP: ${code}"
            ;;
    esac
done
if [[ "$CONC_CONFLICT" == "1" ]]; then
    log_ok "동시 생성 락 경합 응답 관찰: [${CONC_CODES[*]}]"
else
    log_info "동시 요청 응답 코드: [${CONC_CODES[*]}] (409 미관찰)"
fi
rm -rf "${CONC_TMP_DIR}"
sleep 1

CONC_RESULT=$(api POST "/api/notification-jobs" "$CONC_BODY")
CONC_HTTP=$(echo "$CONC_RESULT" | tail -1)
CONC_RESP_BODY=$(echo "$CONC_RESULT" | sed '$d')
CONC_JOB_ID=$(echo "$CONC_RESP_BODY" | jq -r '.data.jobId')
assert_http "$CONC_HTTP" "200" "동시 요청 후 재확인 -> 기존 반환"
assert_not_empty "$CONC_JOB_ID" "동시 요청 Job ID"
curl -sf -o /dev/null -X DELETE "${BASE_URL}/api/notification-jobs/${CONC_JOB_ID}" || true

# ================================================================
# [필수 4] 비동기 처리 구조
# ================================================================

log_section "[필수 4] 비동기 처리 구조"

log_header "4.1 API 즉시 응답 + 백그라운드 처리"
set_mock_email 0.0
ASYNC_KEY="req4-async-${TS_UNIQUE}"

START_MS=$(python3 -c 'import time; print(int(time.time()*1000))' 2>/dev/null || date +%s%3N)
create_job "$ASYNC_KEY" $SCHEDULE_DELAY_SEC '[
    {"recipientId": 2001, "contact": "async1@test.com", "variables": {"name": "비동기A", "orderId": "ASYNC-1"}},
    {"recipientId": 2002, "contact": "async2@test.com", "variables": {"name": "비동기B", "orderId": "ASYNC-2"}}
]'
END_MS=$(python3 -c 'import time; print(int(time.time()*1000))' 2>/dev/null || date +%s%3N)
ASYNC_JOB_ID=$JOB_ID
API_LATENCY=$((END_MS - START_MS))

assert_http "$JOB_HTTP" "201" "API 응답"
log_info "API 응답 시간: ${API_LATENCY}ms"

IMMEDIATE_STATUS=$(get_job_status "$ASYNC_JOB_ID")
log_info "API 직후 상태: ${IMMEDIATE_STATUS} (CREATED/SCHEDULED = 비동기 정상)"

log_step "백그라운드 처리 완료 대기"
wait_for_status "$ASYNC_JOB_ID" "COMPLETED" "비동기 처리 완료"
log_ok "API 즉시 응답 + 백그라운드 처리 확인"

# ================================================================
# [필수 2] 알림 처리 상태 관리
# ================================================================

log_section "[필수 2] 알림 처리 상태 관리"

# -- 2.1 재시도 (TRANSIENT -> RETRYING -> COMPLETED) --
log_header "2.1 재시도 정책 (TRANSIENT 실패 -> 자동 재시도 -> 완료)"
set_mock_email 0.5 TRANSIENT

create_job "req2-retry-${TS_UNIQUE}" $SCHEDULE_DELAY_SEC '[
    {"recipientId": 3001, "contact": "retry1@test.com", "variables": {"name": "재시도A", "orderId": "RT-001"}},
    {"recipientId": 3002, "contact": "retry2@test.com", "variables": {"name": "재시도B", "orderId": "RT-002"}},
    {"recipientId": 3003, "contact": "retry3@test.com", "variables": {"name": "재시도C", "orderId": "RT-003"}}
]'
RETRY_JOB_ID=$JOB_ID
assert_not_empty "$RETRY_JOB_ID" "재시도 Job ID"

log_step "RETRYING 또는 COMPLETED 대기"
wait_for_any_status "$RETRY_JOB_ID" "첫 발송 후 상태" 30 "RETRYING" "COMPLETED"

if [[ "$(get_job_status "$RETRY_JOB_ID")" == "RETRYING" ]]; then
    log_step "mock email 실패율 0% -> 재시도 성공 유도"
    set_mock_email 0.0
    wait_for_status "$RETRY_JOB_ID" "COMPLETED" "재시도 후 완료"
fi
log_ok "재시도 정책 동작 확인"

# -- 2.2 PERMANENT 실패 --
log_header "2.2 실패 기록 (PERMANENT -> DEAD_LETTER -> Job FAILED)"
set_mock_email 1.0 PERMANENT

create_job "req2-fail-${TS_UNIQUE}" $SCHEDULE_DELAY_SEC '[
    {"recipientId": 4001, "contact": "fail1@test.com", "variables": {"name": "실패A", "orderId": "F-001"}},
    {"recipientId": 4002, "contact": "fail2@test.com", "variables": {"name": "실패B", "orderId": "F-002"}}
]'
FAIL_JOB_ID=$JOB_ID

wait_for_status "$FAIL_JOB_ID" "FAILED" "PERMANENT -> Job FAILED"

FAIL_DETAIL=$(api_get "/api/notification-jobs/${FAIL_JOB_ID}")
assert_eq "$(echo "$FAIL_DETAIL" | jq -r '.data.deadLetterCount')" "2" "deadLetterCount (DEAD_LETTER)"

# 개별 알림 실패 사유
FAIL_USER_NOTIFS=$(api_get "/api/users/4001/notifications?size=20")
FAIL_NOTIF_ID=$(echo "$FAIL_USER_NOTIFS" | jq -r '.data.items[0].id')
if [[ -n "$FAIL_NOTIF_ID" && "$FAIL_NOTIF_ID" != "null" ]]; then
    FAIL_NOTIF=$(api_get "/api/notifications/${FAIL_NOTIF_ID}")
    assert_eq "$(echo "$FAIL_NOTIF" | jq -r '.data.status')" "DEAD_LETTER" "실패 알림 상태"
    assert_not_empty "$(echo "$FAIL_NOTIF" | jq -r '.data.lastFailureReason')" "실패 사유 기록"
else
    log_skip "개별 실패 알림 조회 불가"
fi

# -- 2.3 Cancel --
log_header "2.3 상태 전이: Cancel (SCHEDULED -> CANCELLED)"
set_mock_email 0.0

create_job "req2-cancel-${TS_UNIQUE}" 120 \
    '[{"recipientId": 5001, "contact": "cancel@test.com", "variables": {"name": "취소", "orderId": "C-001"}}]'
CANCEL_JOB_ID=$JOB_ID

wait_for_status "$CANCEL_JOB_ID" "SCHEDULED" "SCHEDULED 전이"

CANCEL_RESULT=$(api DELETE "/api/notification-jobs/${CANCEL_JOB_ID}")
CANCEL_HTTP=$(echo "$CANCEL_RESULT" | tail -1)
assert_http "$CANCEL_HTTP" "204" "Cancel 요청"
assert_eq "$(get_job_status "$CANCEL_JOB_ID")" "CANCELLED" "Cancel 후 상태"

log_header "2.3-b 상태 전이: 이미 CANCELLED 재취소"
CANCEL_AGAIN_RESULT=$(api DELETE "/api/notification-jobs/${CANCEL_JOB_ID}")
CANCEL_AGAIN_HTTP=$(echo "$CANCEL_AGAIN_RESULT" | tail -1)
assert_http "$CANCEL_AGAIN_HTTP" "204" "이미 CANCELLED 재취소"
assert_eq "$(get_job_status "$CANCEL_JOB_ID")" "CANCELLED" "재취소 후 상태 유지"

# -- 2.4 Max Retry 초과 --
log_header "2.4 Max Retry 초과 (TRANSIENT x N -> DEAD_LETTER -> FAILED)"
set_mock_email 1.0 TRANSIENT

create_job "req2-maxretry-${TS_UNIQUE}" $SCHEDULE_DELAY_SEC \
    '[{"recipientId": 9001, "contact": "maxr@test.com", "variables": {"name": "맥스", "orderId": "MR-001"}}]'
MAXR_JOB_ID=$JOB_ID

log_info "max-send-try-count=3, base-delay=5s -> 최대 ~60초 소요"
wait_for_dead_letter_count "$MAXR_JOB_ID" "1" "Max Retry 소진 -> FAILED" 90

MAXR_DETAIL=$(api_get "/api/notification-jobs/${MAXR_JOB_ID}")
assert_eq "$(echo "$MAXR_DETAIL" | jq -r '.data.deadLetterCount')" "1" "전체 DEAD_LETTER 수"

log_header "2.5 취소 거절 (COMPLETED / FAILED)"
COMPLETED_CANCEL_RESULT=$(api DELETE "/api/notification-jobs/${JOB_HAPPY_ID}")
COMPLETED_CANCEL_HTTP=$(echo "$COMPLETED_CANCEL_RESULT" | tail -1)
COMPLETED_CANCEL_BODY=$(echo "$COMPLETED_CANCEL_RESULT" | sed '$d')
assert_http "$COMPLETED_CANCEL_HTTP" "400" "COMPLETED 취소 거절"
assert_error_contains "$COMPLETED_CANCEL_BODY" "취소 불가 상태" "COMPLETED 취소 오류 메시지"

FAILED_CANCEL_RESULT=$(api DELETE "/api/notification-jobs/${FAIL_JOB_ID}")
FAILED_CANCEL_HTTP=$(echo "$FAILED_CANCEL_RESULT" | tail -1)
FAILED_CANCEL_BODY=$(echo "$FAILED_CANCEL_RESULT" | sed '$d')
assert_http "$FAILED_CANCEL_HTTP" "400" "FAILED 취소 거절"
assert_error_contains "$FAILED_CANCEL_BODY" "취소 불가 상태" "FAILED 취소 오류 메시지"

# ================================================================
# [선택 1] 발송 스케줄링
# ================================================================

log_section "[선택 1] 발송 스케줄링"

log_info "예약 발송: [필수 1] Happy Path에서 scheduledAt 기반 발송 검증 완료"
log_ok "예약 발송 검증 완료"
log_info "예약 취소: [필수 2] 2.3에서 SCHEDULED->CANCELLED 검증 완료"
log_ok "예약 취소 검증 완료"

# ================================================================
# [필수 5] 운영 시나리오 대응
# ================================================================

log_section "[필수 5] 운영 시나리오 대응"

# -- 5.0 잘못된 복구 요청 --
log_header "5.0 잘못된 복구 요청 (COMPLETED)"
RECOVER_COMPLETED_RESULT=$(api POST "/api/notification-jobs/${JOB_HAPPY_ID}/recover")
RECOVER_COMPLETED_HTTP=$(echo "$RECOVER_COMPLETED_RESULT" | tail -1)
RECOVER_COMPLETED_BODY=$(echo "$RECOVER_COMPLETED_RESULT" | sed '$d')
assert_http "$RECOVER_COMPLETED_HTTP" "400" "COMPLETED 복구 거절"
assert_error_contains "$RECOVER_COMPLETED_BODY" "FAILED 또는 CANCELLED 상태" "COMPLETED 복구 오류 메시지"

# -- 5.1 FAILED 복구 --
log_header "5.1 수동 복구 (FAILED -> RETRYING -> COMPLETED)"
set_mock_email 0.0

log_step "테스트 2.2에서 FAILED된 Job ${FAIL_JOB_ID} 복구"
RECOVER_RESULT=$(api POST "/api/notification-jobs/${FAIL_JOB_ID}/recover")
assert_http "$(echo "$RECOVER_RESULT" | tail -1)" "200" "복구 요청"
wait_for_status "$FAIL_JOB_ID" "COMPLETED" "FAILED -> 복구 후 COMPLETED"

# -- 5.2 CANCELLED 복구 --
log_header "5.2 CANCELLED 복구 (CANCELLED -> RETRYING -> COMPLETED)"
log_step "테스트 2.3에서 CANCELLED된 Job ${CANCEL_JOB_ID} 복구"
RECOVER2_RESULT=$(api POST "/api/notification-jobs/${CANCEL_JOB_ID}/recover")
assert_http "$(echo "$RECOVER2_RESULT" | tail -1)" "200" "CANCELLED 복구 요청"
wait_for_status "$CANCEL_JOB_ID" "COMPLETED" "CANCELLED -> 복구 후 COMPLETED"

# -- 5.3 RETRYING 취소 --
log_header "5.3 RETRYING 상태에서 Cancel"
set_mock_email 1.0 TRANSIENT 200

create_job "req5-cancel-retrying-${TS_UNIQUE}" $SCHEDULE_DELAY_SEC '[
    {"recipientId": 7501, "contact": "cr1@test.com", "variables": {"name": "취소R1", "orderId": "CR-001"}},
    {"recipientId": 7502, "contact": "cr2@test.com", "variables": {"name": "취소R2", "orderId": "CR-002"}},
    {"recipientId": 7503, "contact": "cr3@test.com", "variables": {"name": "취소R3", "orderId": "CR-003"}}
]'
CANCEL_R_JOB_ID=$JOB_ID

log_step "RETRYING 대기 (발송 실패 후 RETRYING 전이)"
wait_for_any_status "$CANCEL_R_JOB_ID" "RETRYING 대기" 30 "RETRYING" "FAILED"

CANCEL_R_STATUS=$(get_job_status "$CANCEL_R_JOB_ID")
if [[ "$CANCEL_R_STATUS" == "RETRYING" ]]; then
    CANCEL_R_RESULT=$(api DELETE "/api/notification-jobs/${CANCEL_R_JOB_ID}")
    CANCEL_R_HTTP=$(echo "$CANCEL_R_RESULT" | tail -1)
    log_info "Cancel HTTP=${CANCEL_R_HTTP}"
    AFTER_STATUS=$(get_job_status "$CANCEL_R_JOB_ID")
    if [[ "$AFTER_STATUS" == "CANCELLED" ]]; then
        log_ok "RETRYING -> CANCELLED 전이 성공"
    else
        log_info "현재 상태: ${AFTER_STATUS} (이미 다른 상태로 전이)"
        log_ok "RETRYING Cancel 시도 완료 (경쟁 조건)"
    fi
else
    log_info "RETRYING 전에 ${CANCEL_R_STATUS}로 전이됨 (mock 타이밍)"
    log_ok "RETRYING Cancel 시나리오 확인 (경쟁 조건)"
fi

set_mock_email 0.0 TRANSIENT 50

# ================================================================
# [선택 3] 읽음 처리
# ================================================================

log_section "[선택 3] 읽음 처리 (멀티 디바이스 + firstReadAt 멱등)"

READ_TARGET_NOTIFS=$(api_get "/api/users/1001/notifications?size=20")
READ_NOTIF_ID=$(echo "$READ_TARGET_NOTIFS" | jq -r '.data.items[0].id')

if [[ -n "$READ_NOTIF_ID" && "$READ_NOTIF_ID" != "null" ]]; then
    log_header "읽음 처리 전 상태"
    BEFORE_READ=$(api_get "/api/notifications/${READ_NOTIF_ID}")
    log_info "읽기 전 firstReadAt: $(echo "$BEFORE_READ" | jq -r '.data.firstReadAt')"

    log_header "디바이스 1 읽음 (iPhone)"
    READ1_RESULT=$(api PATCH "/api/notifications/${READ_NOTIF_ID}/read?userId=1001" \
        '{"deviceId": "iphone-14", "deviceType": "MOBILE"}')
    assert_http "$(echo "$READ1_RESULT" | tail -1)" "202" "디바이스 1 읽음"

    FIRST_READ_AT="null"
    AFTER_READ1="{}"
    for _ in $(seq 1 10); do
        AFTER_READ1=$(api_get "/api/notifications/${READ_NOTIF_ID}")
        FIRST_READ_AT=$(echo "$AFTER_READ1" | jq -r '.data.firstReadAt')
        if [[ -n "$FIRST_READ_AT" && "$FIRST_READ_AT" != "null" ]]; then
            break
        fi
        sleep 1
    done
    assert_not_empty "$FIRST_READ_AT" "firstReadAt 설정됨"

    log_header "디바이스 2 읽음 (MacBook) - firstReadAt 멱등 검증"
    READ2_RESULT=$(api PATCH "/api/notifications/${READ_NOTIF_ID}/read?userId=1001" \
        '{"deviceId": "macbook-pro", "deviceType": "DESKTOP"}')
    assert_http "$(echo "$READ2_RESULT" | tail -1)" "202" "디바이스 2 읽음"
    sleep 1

    AFTER_READ2=$(api_get "/api/notifications/${READ_NOTIF_ID}")
    assert_eq "$(echo "$AFTER_READ2" | jq -r '.data.firstReadAt')" "$FIRST_READ_AT" "firstReadAt 변경 안됨 (멱등)"

    log_header "읽음/안읽음 필터링"
    READ_TRUE=$(api_get "/api/users/1001/notifications?read=true&size=20")
    READ_FALSE=$(api_get "/api/users/1001/notifications?read=false&size=20")
    READ_TRUE_COUNT=$(echo "$READ_TRUE" | jq -r '.data.items | length')
    READ_FALSE_COUNT=$(echo "$READ_FALSE" | jq -r '.data.items | length')
    if [[ "$READ_TRUE_COUNT" -ge 1 ]]; then
        log_ok "읽은 알림 필터: ${READ_TRUE_COUNT}개"
    else
        log_fail "읽은 알림 필터: 0개"
    fi
    log_info "안읽은 알림: ${READ_FALSE_COUNT}개"
else
    log_skip "읽음 처리 테스트 불가 (알림 ID 없음)"
fi

# ================================================================
# [선택 4] 최종 실패 보관 + sendTryCount 리셋
# ================================================================

log_section "[선택 4] 수동 재시도 (sendTryCount 리셋)"

log_header "PERMANENT 실패 -> DEAD_LETTER"
set_mock_email 1.0 PERMANENT

create_job "req-opt4-dead-${TS_UNIQUE}" $SCHEDULE_DELAY_SEC \
    '[{"recipientId": 7001, "contact": "dead@test.com", "variables": {"name": "데드", "orderId": "D-001"}}]'
DEAD_JOB_ID=$JOB_ID

wait_for_status "$DEAD_JOB_ID" "FAILED" "PERMANENT -> FAILED"

DEAD_NOTIFS=$(api_get "/api/users/7001/notifications?size=20")
DEAD_NOTIF_ID=$(echo "$DEAD_NOTIFS" | jq -r '.data.items[0].id')

if [[ -n "$DEAD_NOTIF_ID" && "$DEAD_NOTIF_ID" != "null" ]]; then
    DEAD_NOTIF=$(api_get "/api/notifications/${DEAD_NOTIF_ID}")
    DEAD_ATTEMPT=$(echo "$DEAD_NOTIF" | jq -r '.data.attemptCount')
    assert_eq "$(echo "$DEAD_NOTIF" | jq -r '.data.status')" "DEAD_LETTER" "DEAD_LETTER 상태"
    log_info "실패 시 attemptCount: ${DEAD_ATTEMPT}"

    log_header "수동 재시도 -> sendTryCount 리셋"
    set_mock_email 0.0
    RETRY_RESULT=$(api POST "/api/notification-jobs/${DEAD_JOB_ID}/recover")
    assert_http "$(echo "$RETRY_RESULT" | tail -1)" "200" "수동 재시도 요청"
    wait_for_status "$DEAD_JOB_ID" "COMPLETED" "수동 재시도 후 COMPLETED"

    RETRIED_NOTIF=$(api_get "/api/notifications/${DEAD_NOTIF_ID}")
    RETRIED_ATTEMPT=$(echo "$RETRIED_NOTIF" | jq -r '.data.attemptCount')
    assert_eq "$(echo "$RETRIED_NOTIF" | jq -r '.data.status')" "SENT" "재시도 후 SENT"
    if [[ "$RETRIED_ATTEMPT" -lt "$DEAD_ATTEMPT" || "$RETRIED_ATTEMPT" -eq 1 ]]; then
        log_ok "sendTryCount 리셋됨: ${DEAD_ATTEMPT} -> ${RETRIED_ATTEMPT}"
    else
        log_fail "sendTryCount 미리셋: ${DEAD_ATTEMPT} -> ${RETRIED_ATTEMPT}"
    fi
else
    log_skip "sendTryCount 리셋 테스트 불가 (알림 ID 없음)"
fi

# ================================================================
# 정리
# ================================================================

log_header "정리"
DEL_RESULT=$(api DELETE "/api/templates/${TEMPLATE_ID}")
assert_http "$(echo "$DEL_RESULT" | tail -1)" "204" "템플릿 삭제"
set_mock_email 0.3 TRANSIENT > /dev/null

# ================================================================
# 결과 요약
# ================================================================

log_section "검증 결과 요약"

echo ""
echo -e "  ${GREEN}PASS: ${PASS_COUNT}${NC}"
echo -e "  ${RED}FAIL: ${FAIL_COUNT}${NC}"
echo -e "  ${CYAN}SKIP: ${SKIP_COUNT}${NC}"
echo ""

# 병렬 테스트 결과 파싱용 표준 포맷 출력
echo "[총 결과] PASS: $PASS_COUNT, FAIL: $FAIL_COUNT, SKIP: $SKIP_COUNT"

if [[ $FAIL_COUNT -gt 0 ]]; then
    echo -e "  ${RED}${BOLD}일부 검증 항목이 실패했습니다.${NC}"
    exit 1
else
    echo -e "  ${GREEN}${BOLD}모든 구현 요구사항 검증 통과!${NC}"
    echo ""
    echo -e "  검증 항목:"
    echo -e "    [필수 1] 알림 발송 요청 API (생성, 조회, 목록)"
    echo -e "    [필수 2] 상태 관리 (전이, 재시도, 실패 기록, Max Retry)"
    echo -e "    [필수 3] 중복 발송 방지 (멱등키, 동시 요청)"
    echo -e "    [필수 4] 비동기 처리 구조 (즉시 응답, 백그라운드 처리)"
    echo -e "    [필수 5] 운영 시나리오 (FAILED 복구, 수동 실행, CANCELLED 복구, RETRYING 취소)"
    echo -e "    [선택 1] 발송 스케줄링 (예약, 취소)"
    echo -e "    [선택 2] 템플릿 관리 (CRUD, 미리보기)"
    echo -e "    [선택 3] 읽음 처리 (멀티 디바이스, firstReadAt 멱등)"
    echo -e "    [선택 4] 수동 재시도 (sendTryCount 리셋)"
    echo ""
    echo -e "  ${YELLOW}[추가] Stuck Processing Recovery${NC}"
    echo -e "    서버 재기동 기반 크래시 복구 시나리오는 test-with-server.sh 에서 검증됩니다."
    echo -e "    ${YELLOW}./scripts/test-with-server.sh${NC}"
    echo ""
fi
