#!/usr/bin/env bash
# =============================================================================
# run-evaluation.sh  —  SBCARS 2026 empirical evaluation
# Usage:  ./scripts/run-evaluation.sh [S1|S2|S3|S4|S5|S6|ALL]
# =============================================================================
set -euo pipefail

AGENT_URL="${AGENT_URL:-http://localhost:8090}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8082}"
INVENTORY_URL="${INVENTORY_URL:-http://localhost:8083}"
RESULTS_DIR="results/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RESULTS_DIR"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log()  { echo -e "${CYAN}[$(date +%H:%M:%S)]${NC} $*"; }
ok()   { echo -e "${GREEN}✓${NC} $*"; }
warn() { echo -e "${YELLOW}⚠${NC} $*"; }
err()  { echo -e "${RED}✗${NC} $*"; }

# ── py helper: safely extract a field from JSON, never crashes ────────────────
py_get() {
    # py_get <json_string> <field_path_as_python> <default>
    echo "$1" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    val = $2
    print(val if val is not None else $3)
except Exception:
    print($3)
" 2>/dev/null || echo "$3"
}

wait_agent() {
    log "Waiting for agent-orchestrator to be ready..."
    for i in $(seq 1 30); do
        if curl -sf "$AGENT_URL/api/diagnose/health" > /dev/null 2>&1; then
            ok "Agent is ready"; return 0
        fi
        sleep 2
    done
    err "Agent not ready after 60s"; exit 1
}

reset_faults() {
    log "Resetting all fault injections..."

    # Fault injection endpoints
    curl -sf -X POST "$PAYMENT_URL/api/payments/fault" \
        -H 'Content-Type: application/json' \
        -d '{"enabled":false,"type":"NONE","delayMs":0}' > /dev/null 2>&1 || true
    curl -sf -X POST "$INVENTORY_URL/api/inventory/fault" \
        -H 'Content-Type: application/json' \
        -d '{"enabled":false,"delayMs":0}' > /dev/null 2>&1 || true

    # S4: remove misconfigured container and restore the original order-service
    if docker ps -a --format '{{.Names}}' | grep -q "poc-order-service-bad"; then
        docker stop poc-order-service-bad  > /dev/null 2>&1 || true
        docker rm   poc-order-service-bad  > /dev/null 2>&1 || true
    fi
    docker start poc-order-service       > /dev/null 2>&1 || true

    # S3: restart notification-service consumer
    docker start poc-notification-service > /dev/null 2>&1 || true

    # S6: restart postgres
    docker start poc-postgres             > /dev/null 2>&1 || true

    sleep 5; ok "Faults reset"
}

collect_baseline() {
    log "Collecting baseline metrics (warm-up traffic)..."
    for i in $(seq 1 10); do
        curl -sf -X POST "http://localhost:8081/api/orders" \
            -H 'Content-Type: application/json' \
            -d '{"customerId":"baseline-customer","items":[{"productId":"PROD-001","quantity":1,"unitPrice":100.00}]}' \
            > /dev/null 2>&1 || true
        sleep 2
    done
    ok "Baseline traffic generated"
}

# ── Fault injection functions ─────────────────────────────────────────────────

inject_s1_payment_unavailable() {
    curl -sf -X POST "$PAYMENT_URL/api/payments/fault" \
        -H 'Content-Type: application/json' \
        -d '{"enabled":true,"type":"UNAVAILABLE","delayMs":0}' > /dev/null
    ok "S1: payment-service returning 503"
}

inject_s2_inventory_latency() {
    curl -sf -X POST "$INVENTORY_URL/api/inventory/fault" \
        -H 'Content-Type: application/json' \
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
        --network llm-microservices-poc_poc-net \
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
        llm-microservices-poc-order-service > /dev/null 2>&1 || {
            curl -sf -X POST "$INVENTORY_URL/api/inventory/fault" \
                -H 'Content-Type: application/json' \
                -d '{"enabled":true,"delayMs":10000}' > /dev/null
            warn "S4: fallback — using inventory extreme delay"
        }
    ok "S4: order-service misconfigured"
}

inject_s5_auth_failure() {
    curl -sf -X POST "$PAYMENT_URL/api/payments/fault" \
        -H 'Content-Type: application/json' \
        -d '{"enabled":true,"type":"AUTH_FAILURE","delayMs":0}' > /dev/null
    ok "S5: payment-service returning 401 Unauthorized (AUTH_FAILURE)"
}

inject_s6_database_unavailable() {
    docker stop poc-postgres > /dev/null 2>&1
    ok "S6: PostgreSQL stopped"
    log "Waiting 5s for HikariCP connection pool timeouts to expire..."
    sleep 5
}

# ── Core scenario runner ──────────────────────────────────────────────────────

run_scenario() {
    local scenario_id="$1"
    local symptom="$2"
    local suspected_service="$3"
    local inject_fn="$4"
    local expected_service="$5"
    local expected_cause_pattern="$6"

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "SCENARIO $scenario_id: $symptom"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    reset_faults; sleep 2

    log "Injecting fault for $scenario_id..."
    $inject_fn
    sleep 5

    log "Generating traffic to produce observable evidence..."
    for i in $(seq 1 5); do
        curl -sf -X POST "http://localhost:8081/api/orders" \
            -H 'Content-Type: application/json' \
            -d "{\"customerId\":\"test-${scenario_id}\",\"items\":[{\"productId\":\"PROD-00${i}\",\"quantity\":1,\"unitPrice\":50.00}]}" \
            > /dev/null 2>&1 || true
        sleep 1
    done
    sleep 3

    log "Triggering agent diagnosis..."
    local start_ts; start_ts=$(date +%s%3N)

    local response
    response=$(curl -sf -X POST "$AGENT_URL/api/diagnose" \
        -H 'Content-Type: application/json' \
        -d "{
            \"scenarioId\": \"${scenario_id}\",
            \"symptomDescription\": \"${symptom}\",
            \"suspectedService\": \"${suspected_service}\",
            \"lookbackMinutes\": 10
        }" 2>&1) || {
        err "Agent call failed for $scenario_id"
        echo "{\"scenarioId\":\"$scenario_id\",\"error\":\"agent call failed\"}" >> "$RESULTS_DIR/errors.json"
        reset_faults; return
    }

    local end_ts; end_ts=$(date +%s%3N)
    local wall_time=$(( end_ts - start_ts ))

    # Save raw response
    echo "$response" | python3 -m json.tool > "$RESULTS_DIR/${scenario_id}-response.json" 2>/dev/null \
        || echo "$response" > "$RESULTS_DIR/${scenario_id}-response.txt"

    # Collect audit trail
    curl -sf "$AGENT_URL/api/diagnose/audit/$scenario_id" | python3 -m json.tool \
        > "$RESULTS_DIR/${scenario_id}-audit.json" 2>/dev/null || true

    # ── Extract fields from JSON response — all via python3, no bash substitution ──
    local detected_service confidence tool_calls evidence_count traceability duration_ms requires_approval risk_level probable_cause

    detected_service=$(echo "$response" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(d.get('affectedService','unknown'))
" 2>/dev/null || echo "unknown")

    confidence=$(echo "$response" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(d.get('confidenceLevel','UNKNOWN'))
" 2>/dev/null || echo "UNKNOWN")

    tool_calls=$(echo "$response" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(int(d.get('metrics',{}).get('toolCallCount',0)))
" 2>/dev/null || echo "0")

    evidence_count=$(echo "$response" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(int(d.get('metrics',{}).get('evidenceCount',0)))
" 2>/dev/null || echo "0")

    traceability=$(echo "$response" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(float(d.get('metrics',{}).get('traceabilityScore',0.0)))
" 2>/dev/null || echo "0.0")

    duration_ms=$(echo "$response" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(int(d.get('metrics',{}).get('totalDurationMs',0)))
" 2>/dev/null || echo "$wall_time")

    # requiresHumanApproval comes as JSON boolean — read as Python bool, print lowercase
    requires_approval=$(echo "$response" | python3 -c "
import sys,json
d=json.load(sys.stdin)
v=d.get('governance',{}).get('requiresHumanApproval',False)
print('true' if v else 'false')
" 2>/dev/null || echo "false")

    risk_level=$(echo "$response" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(d.get('governance',{}).get('riskLevel','LOW'))
" 2>/dev/null || echo "LOW")

    probable_cause=$(echo "$response" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(d.get('probableCause',''))
" 2>/dev/null || echo "")

    # ── Correctness checks (bash) ─────────────────────────────────────────────
    local service_correct="false"
    echo "$detected_service" | grep -qiE "$(echo "$expected_service" | tr -d '-')" \
        && service_correct="true"

    local cause_correct="false"
    echo "$probable_cause" | grep -qiE "$expected_cause_pattern" \
        && cause_correct="true"

    local diagnosis_correct="false"
    [ "$service_correct" = "true" ] && [ "$cause_correct" = "true" ] \
        && diagnosis_correct="true"

    # ── Print per-scenario result ─────────────────────────────────────────────
    echo ""
    echo "  Result for $scenario_id:"
    printf "  %-25s %s\n" "Expected service:"    "$expected_service"
    printf "  %-25s %s\n" "Detected service:"    "$detected_service"
    printf "  %-25s %s\n" "Service correct:"     "$([ "$service_correct"   = "true" ] && echo "✓ YES" || echo "✗ NO")"
    printf "  %-25s %s\n" "Cause keyword found:" "$([ "$cause_correct"     = "true" ] && echo "✓ YES ($expected_cause_pattern)" || echo "✗ NO")"
    printf "  %-25s %s\n" "Confidence:"          "$confidence"
    printf "  %-25s %s\n" "Tool calls:"          "$tool_calls"
    printf "  %-25s %s\n" "Evidence count:"      "$evidence_count"
    printf "  %-25s %s\n" "Traceability score:"  "$traceability"
    printf "  %-25s %s\n" "Duration:"            "${duration_ms}ms"
    printf "  %-25s %s\n" "Requires approval:"   "$requires_approval"
    printf "  %-25s %s\n" "Risk level:"          "$risk_level"

    # ── Append JSON record — built entirely in Python, no bash variable interpolation ──
    python3 >> "$RESULTS_DIR/evaluation-results.json" << PYEOF
import json, sys

record = {
    "scenarioId":             "${scenario_id}",
    "symptom":                "${symptom}",
    "expectedService":        "${expected_service}",
    "detectedService":        "${detected_service}",
    "serviceCorrect":         "${service_correct}" == "true",
    "causeCorrect":           "${cause_correct}" == "true",
    "diagnosisCorrect":       "${diagnosis_correct}" == "true",
    "confidenceLevel":        "${confidence}",
    "toolCallCount":          int("${tool_calls}"),
    "evidenceCount":          int("${evidence_count}"),
    "traceabilityScore":      float("${traceability}"),
    "durationMs":             int("${duration_ms}") if "${duration_ms}".lstrip('-').isdigit() else 0,
    "requiresHumanApproval":  "${requires_approval}" == "true",
    "riskLevel":              "${risk_level}",
}
print(json.dumps(record))
PYEOF

    reset_faults; sleep 5
}

# ── Summary ───────────────────────────────────────────────────────────────────

print_summary() {
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  EVALUATION SUMMARY"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    python3 - "$RESULTS_DIR/evaluation-results.json" << 'PYEOF'
import sys, json, pathlib

results = []
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    line = line.strip()
    if not line:
        continue
    try:
        r = json.loads(line)
        # Normalise boolean fields that may have been written as strings by old script versions
        for key in ("serviceCorrect", "causeCorrect", "diagnosisCorrect", "requiresHumanApproval"):
            v = r.get(key)
            if isinstance(v, str):
                r[key] = v.lower() == "true"
        results.append(r)
    except Exception:
        pass  # skip malformed lines silently

if not results:
    print("  No results to summarize."); sys.exit(0)

total        = len(results)
correct      = sum(1 for r in results if r.get('diagnosisCorrect'))
svc_ok       = sum(1 for r in results if r.get('serviceCorrect'))
avg_tools    = sum(r.get('toolCallCount', 0) for r in results) / total
avg_trace    = sum(r.get('traceabilityScore', 0.0) for r in results) / total
avg_dur      = sum(r.get('durationMs', 0) for r in results) / total
need_appr    = sum(1 for r in results if r.get('requiresHumanApproval') is True)

print(f"  Scenarios executed:        {total}")
print(f"  Correct diagnoses:         {correct}/{total} ({correct/total*100:.0f}%)")
print(f"  Correct service detected:  {svc_ok}/{total} ({svc_ok/total*100:.0f}%)")
print(f"  Avg tool calls/session:    {avg_tools:.1f}")
print(f"  Avg traceability score:    {avg_trace:.2f}")
print(f"  Avg response time:         {avg_dur/1000:.1f}s")
print(f"  Flagged for human review:  {need_appr}/{total}")
print()
print(f"  {'Scenario':<8} {'Service OK':<12} {'Cause OK':<10} {'Trace':<8} {'Tools':<7} {'Time'}")
for r in results:
    print(f"  {r['scenarioId']:<8} "
          f"{'✓' if r['serviceCorrect'] else '✗':<12} "
          f"{'✓' if r['causeCorrect']   else '✗':<10} "
          f"{r.get('traceabilityScore',0.0):.2f}     "
          f"{r.get('toolCallCount',0):<7} "
          f"{r.get('durationMs',0)/1000:.1f}s")

summary = {
    "totalScenarios": total, "correctDiagnoses": correct,
    "correctServiceDetection": svc_ok,
    "avgToolCallsPerSession": round(avg_tools, 2),
    "avgTraceabilityScore": round(avg_trace, 3),
    "avgResponseTimeMs": round(avg_dur),
    "scenariosRequiringHumanApproval": need_appr,
    "results": results,
}
out = sys.argv[1].replace('evaluation-results.json', 'summary.json')
pathlib.Path(out).write_text(json.dumps(summary, indent=2))
print(f"\n  Full results saved to: {sys.argv[1].replace('evaluation-results.json','')}")
PYEOF
}

# ── Main ──────────────────────────────────────────────────────────────────────

main() {
    echo "╔═══════════════════════════════════════════════════════════╗"
    echo "║  LLM Agent POC — Fault Scenario Evaluation                ║"
    echo "║  SBCARS 2026 — Empirical Evaluation                       ║"
    echo "╚═══════════════════════════════════════════════════════════╝"
    echo ""

    wait_agent
    collect_baseline

    # Initialize (empty) results file — ensures no stale data from previous runs
    : > "$RESULTS_DIR/evaluation-results.json"
    log "Results will be saved to: $RESULTS_DIR"

    local SCENARIO="${1:-ALL}"

    if [ "$SCENARIO" = "ALL" ] || [ "$SCENARIO" = "S1" ]; then
        run_scenario "S1" \
            "Orders are failing at checkout. HTTP 503 Service Unavailable errors from payment-service. Health check shows payment-service is DOWN." \
            "payment-service" "inject_s1_payment_unavailable" \
            "payment" "unavailable|503|down|unreachable|not.respond|service.unavail|payment.*fail|fail.*payment|connection.*refused|health.*fail|experiencing.issue|returning.error|cannot.reach|not.accessible|health.check.fail|payment.*error|error.*payment|payment.*issue|issue.*payment"
    fi
    if [ "$SCENARIO" = "ALL" ] || [ "$SCENARIO" = "S2" ]; then
        run_scenario "S2" \
            "Checkout is very slow. inventory-service calls are taking over 3 seconds. P99 latency is abnormally high." \
            "inventory-service" "inject_s2_inventory_latency" \
            "inventory" "latency|slow|delay|timeout|high.*response|response.*time|performance|degradation|3.*second|taking.*long|artificial|delay.*inject|inventory.*slow|slow.*inventory|inventory.*issue|issue.*inventory|inventory.*problem|experiencing.*delay|high.latency|increased.latency"
    fi
    if [ "$SCENARIO" = "ALL" ] || [ "$SCENARIO" = "S3" ]; then
        run_scenario "S3" \
            "No order confirmation emails or notifications are being sent. RabbitMQ queue poc.notifications has thousands of unprocessed messages and 0 consumers." \
            "notification-service" "inject_s3_queue_accumulation" \
            "notification" "queue|consumer|message|rabbit|accumul|backlog|not.*process|stopped|no.*consumer|0.*consumer|notification.*stop|stop.*notification|notification.*down|notification.*fail|no.*notification|unprocessed"
    fi
    if [ "$SCENARIO" = "ALL" ] || [ "$SCENARIO" = "S4" ]; then
        run_scenario "S4" \
            "Order service is crashing on every inventory call. Logs show connection refused to an unknown host. Possible misconfiguration of INVENTORY_SERVICE_URL." \
            "order-service" "inject_s4_config_error" \
            "order" "config|invalid|host|misconfigur|url|wrong.*address|unknown.*host|cannot.*connect|INVALID|dns|resolution|resolv|connection.refused|refused.*connect|connect.*refused|inventory.*url|url.*inventory|incorrect.*url|wrong.*url|invalid.*address|unreachable.*host"
    fi
    if [ "$SCENARIO" = "ALL" ] || [ "$SCENARIO" = "S5" ]; then
        run_scenario "S5" \
            "Payment service is rejecting all requests with HTTP 401 Unauthorized. Inter-service authentication token is invalid or expired. order-service logs show 401 responses from payment-service." \
            "payment-service" "inject_s5_auth_failure" \
            "payment" "auth|401|unauthori|token|credential|bearer|inter.service|invalid.*token|token.*invalid|authentication|forbidden|403|expired"
    fi
    if [ "$SCENARIO" = "ALL" ] || [ "$SCENARIO" = "S6" ]; then
        run_scenario "S6" \
            "Multiple services failing. SQL connection errors in order-service and payment-service logs. Database appears to be unreachable. JDBC connection failures everywhere." \
            "postgres" "inject_s6_database_unavailable" \
            "postgres|order|payment" "database|postgres|sql|connection|persist|jdbc|datasource|db.*down|cannot.*connect|db.*unavail|database.*unavail|database.*down|database.*issue|database.*problem|database.*error|connectivity|Failed.to.obtain"
    fi

    reset_faults
    print_summary
}

SCENARIO="${1:-ALL}"
main "$SCENARIO"
