#!/usr/bin/env bash
# =============================================================================
# run-evaluation.sh  —  SBCARS 2026 empirical evaluation
# Usage:  ./scripts/run-evaluation.sh [S1|S2|S3|S4|S5|S6|ALL]
# =============================================================================
set -euo pipefail

AGENT_URL="${AGENT_URL:-http://localhost:8090}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8082}"
INVENTORY_URL="${INVENTORY_URL:-http://localhost:8083}"
ORDER_IMAGE="${ORDER_IMAGE:-llm-microservices-poc-order-service:artifact-2026}"
POC_NETWORK="${POC_NETWORK:-llm-microservices-poc_poc-net}"
RESULTS_DIR="${RESULTS_DIR:-results/$(date +%Y%m%d_%H%M%S)}"
mkdir -p "$RESULTS_DIR"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log()  { echo -e "${CYAN}[$(date +%H:%M:%S)]${NC} $*"; }
ok()   { echo -e "${GREEN}✓${NC} $*"; }
warn() { echo -e "${YELLOW}⚠${NC} $*"; }
err()  { echo -e "${RED}✗${NC} $*"; }

wait_agent() {
    log "Waiting for agent-orchestrator to be ready..."
    for i in $(seq 1 60); do
        if curl -sf "$AGENT_URL/api/diagnose/health" > /dev/null 2>&1; then
            ok "Agent is ready"; return 0
        fi
        sleep 2
    done
    err "Agent not ready after 120s"; exit 1
}

reset_faults() {
    log "Resetting all fault injections..."
    curl -sf -X POST "$PAYMENT_URL/api/payments/fault" -H 'Content-Type: application/json' \
        -d '{"enabled":false,"type":"NONE","delayMs":0}' > /dev/null 2>&1 || true
    curl -sf -X POST "$INVENTORY_URL/api/inventory/fault" -H 'Content-Type: application/json' \
        -d '{"enabled":false,"delayMs":0}' > /dev/null 2>&1 || true

    if docker ps -a --format '{{.Names}}' | grep -qx "poc-order-service-bad"; then
        docker rm -f poc-order-service-bad > /dev/null 2>&1 || true
    fi
    docker start poc-order-service > /dev/null 2>&1 || true
    docker start poc-notification-service > /dev/null 2>&1 || true
    docker start poc-postgres > /dev/null 2>&1 || true
    sleep 5
    ok "Faults reset"
}

collect_baseline() {
    log "Collecting baseline metrics (warm-up traffic)..."
    for i in $(seq 1 10); do
        curl -sf -X POST "http://localhost:8081/api/orders" -H 'Content-Type: application/json' \
            -d '{"customerId":"baseline-customer","items":[{"productId":"PROD-001","quantity":1,"unitPrice":100.00}]}' \
            > /dev/null 2>&1 || true
        sleep 2
    done
    ok "Baseline traffic generated"
}

inject_s1_payment_unavailable() {
    curl -sf -X POST "$PAYMENT_URL/api/payments/fault" -H 'Content-Type: application/json' \
        -d '{"enabled":true,"type":"UNAVAILABLE","delayMs":0}' > /dev/null
    ok "S1: payment-service returning 503"
}

inject_s2_inventory_latency() {
    curl -sf -X POST "$INVENTORY_URL/api/inventory/fault" -H 'Content-Type: application/json' \
        -d '{"enabled":true,"delayMs":3000}' > /dev/null
    ok "S2: inventory-service adding 3000ms artificial delay"
}

inject_s3_queue_accumulation() {
    docker stop poc-notification-service > /dev/null 2>&1
    ok "S3: notification-service stopped (queue accumulation)"
}

inject_s4_config_error() {
    docker stop poc-order-service > /dev/null 2>&1 || true
    docker run -d --name poc-order-service-bad \
        --network "$POC_NETWORK" \
        -e SPRING_PROFILES_ACTIVE=docker \
        -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/poc \
        -e SPRING_DATASOURCE_USERNAME=poc \
        -e SPRING_DATASOURCE_PASSWORD=poc123 \
        -e SPRING_RABBITMQ_HOST=rabbitmq \
        -e SPRING_RABBITMQ_USERNAME=poc \
        -e SPRING_RABBITMQ_PASSWORD=poc123 \
        -e SPRING_DATA_REDIS_HOST=redis \
        -e PAYMENT_SERVICE_URL=http://payment-service:8082 \
        -e INVENTORY_SERVICE_URL=http://INVALID-HOST-DOES-NOT-EXIST:9999 \
        -p 8081:8081 \
        "$ORDER_IMAGE" > /dev/null
    ok "S4: order-service started with invalid INVENTORY_SERVICE_URL"
}

inject_s5_auth_failure() {
    curl -sf -X POST "$PAYMENT_URL/api/payments/fault" -H 'Content-Type: application/json' \
        -d '{"enabled":true,"type":"AUTH_FAILURE","delayMs":0}' > /dev/null
    ok "S5: payment-service returning 401 Unauthorized"
}

inject_s6_database_unavailable() {
    docker stop poc-postgres > /dev/null 2>&1
    ok "S6: PostgreSQL stopped"
    sleep 5
}

run_scenario() {
    local scenario_id="$1" symptom="$2" suspected_service="$3" inject_fn="$4" expected_service="$5" expected_cause_pattern="$6"
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "SCENARIO $scenario_id: $symptom"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    reset_faults; sleep 2
    $inject_fn
    sleep 5

    log "Generating traffic to produce observable evidence..."
    for i in $(seq 1 5); do
        curl -sf -X POST "http://localhost:8081/api/orders" -H 'Content-Type: application/json' \
            -d "{\"customerId\":\"test-${scenario_id}\",\"items\":[{\"productId\":\"PROD-00${i}\",\"quantity\":1,\"unitPrice\":50.00}]}" \
            > /dev/null 2>&1 || true
        sleep 1
    done
    sleep 3

    local start_ts end_ts wall_time response
    start_ts=$(date +%s%3N)
    response=$(curl -sf -X POST "$AGENT_URL/api/diagnose" -H 'Content-Type: application/json' \
        -d "{\"scenarioId\":\"${scenario_id}\",\"symptomDescription\":\"${symptom}\",\"suspectedService\":\"${suspected_service}\",\"lookbackMinutes\":10}") || {
        err "Agent call failed for $scenario_id"
        echo "{\"scenarioId\":\"$scenario_id\",\"error\":\"agent call failed\"}" >> "$RESULTS_DIR/errors.jsonl"
        reset_faults
        return
    }
    end_ts=$(date +%s%3N); wall_time=$((end_ts-start_ts))

    echo "$response" | python3 -m json.tool > "$RESULTS_DIR/${scenario_id}-response.json" 2>/dev/null || echo "$response" > "$RESULTS_DIR/${scenario_id}-response.txt"
    curl -sf "$AGENT_URL/api/diagnose/audit/$scenario_id" | python3 -m json.tool > "$RESULTS_DIR/${scenario_id}-audit.json" 2>/dev/null || true

    python3 - "$scenario_id" "$symptom" "$expected_service" "$expected_cause_pattern" "$wall_time" "$RESULTS_DIR/evaluation-results.json" <<'PYEOF' <<<"$response"
import json, re, sys
scenario_id, symptom, expected_service, cause_pattern, wall_time, out = sys.argv[1:]
d = json.load(sys.stdin)
detected = str(d.get('affectedService','unknown'))
probable = str(d.get('probableCause',''))
metrics = d.get('metrics',{}) or {}
gov = d.get('governance',{}) or {}
service_pattern = expected_service.replace('-', '')
service_correct = bool(re.search(service_pattern, detected.replace('-',''), re.I))
cause_correct = bool(re.search(cause_pattern, probable, re.I))
record = {
  'scenarioId': scenario_id,
  'symptom': symptom,
  'expectedService': expected_service,
  'detectedService': detected,
  'serviceCorrect': service_correct,
  'causeCorrect': cause_correct,
  'diagnosisCorrect': service_correct and cause_correct,
  'confidenceLevel': d.get('confidenceLevel','UNKNOWN'),
  'toolCallCount': int(metrics.get('toolCallCount',0) or 0),
  'evidenceCount': int(metrics.get('evidenceCount',0) or 0),
  'traceabilityScore': float(metrics.get('traceabilityScore',0.0) or 0.0),
  'durationMs': int(metrics.get('totalDurationMs', wall_time) or wall_time),
  'requiresHumanApproval': bool(gov.get('requiresHumanApproval',False)),
  'riskLevel': gov.get('riskLevel','LOW')
}
with open(out,'a',encoding='utf-8') as f: f.write(json.dumps(record,ensure_ascii=False)+'\n')
print(json.dumps(record,ensure_ascii=False,indent=2))
PYEOF
    reset_faults; sleep 5
}

print_summary() {
    python3 - "$RESULTS_DIR/evaluation-results.json" "$RESULTS_DIR/summary.json" <<'PYEOF'
import json, pathlib, sys
src, dst = map(pathlib.Path, sys.argv[1:])
results=[json.loads(x) for x in src.read_text().splitlines() if x.strip()]
if not results:
    print('No results to summarize.'); raise SystemExit(0)
n=len(results)
summary={
 'totalScenarios': n,
 'correctDiagnoses': sum(r['diagnosisCorrect'] for r in results),
 'correctServiceDetection': sum(r['serviceCorrect'] for r in results),
 'avgToolCallsPerSession': round(sum(r['toolCallCount'] for r in results)/n,2),
 'avgTraceabilityScore': round(sum(r['traceabilityScore'] for r in results)/n,3),
 'avgResponseTimeMs': round(sum(r['durationMs'] for r in results)/n),
 'scenariosRequiringHumanApproval': sum(r['requiresHumanApproval'] for r in results),
 'results': results
}
dst.write_text(json.dumps(summary,ensure_ascii=False,indent=2))
print(json.dumps(summary,ensure_ascii=False,indent=2))
PYEOF
}

main() {
    local scenario="${1:-ALL}"
    wait_agent
    collect_baseline
    : > "$RESULTS_DIR/evaluation-results.json"

    [[ "$scenario" == ALL || "$scenario" == S1 ]] && run_scenario S1 "Orders are failing at checkout. HTTP 503 Service Unavailable errors from payment-service." payment-service inject_s1_payment_unavailable payment "unavailable|503|down|unreachable|service.unavail|payment.*fail|connection.*refused"
    [[ "$scenario" == ALL || "$scenario" == S2 ]] && run_scenario S2 "Checkout is very slow. inventory-service calls are taking over 3 seconds." inventory-service inject_s2_inventory_latency inventory "latency|slow|delay|timeout|performance|3.*second"
    [[ "$scenario" == ALL || "$scenario" == S3 ]] && run_scenario S3 "No order confirmation notifications are being sent; RabbitMQ has unprocessed messages and 0 consumers." notification-service inject_s3_queue_accumulation notification "queue|consumer|message|rabbit|accumul|backlog|unprocessed"
    [[ "$scenario" == ALL || "$scenario" == S4 ]] && run_scenario S4 "Order service fails on inventory calls; possible INVENTORY_SERVICE_URL misconfiguration." order-service inject_s4_config_error order "config|invalid|host|misconfigur|url|unknown.*host|dns|resolution"
    [[ "$scenario" == ALL || "$scenario" == S5 ]] && run_scenario S5 "Payment service is rejecting requests with HTTP 401 Unauthorized." payment-service inject_s5_auth_failure payment "auth|401|unauthori|token|credential|bearer|expired"
    [[ "$scenario" == ALL || "$scenario" == S6 ]] && run_scenario S6 "Multiple services show SQL/JDBC connection errors; database is unreachable." postgres inject_s6_database_unavailable "postgres|order|payment" "database|postgres|sql|connection|jdbc|datasource|db.*down"

    reset_faults
    print_summary
    log "Results saved to $RESULTS_DIR"
}

main "${1:-ALL}"
