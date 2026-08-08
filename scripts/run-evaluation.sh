#!/usr/bin/env bash
set -euo pipefail

AGENT_URL="${AGENT_URL:-http://localhost:8090}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8082}"
INVENTORY_URL="${INVENTORY_URL:-http://localhost:8083}"
ORDER_IMAGE="${ORDER_IMAGE:-llm-microservices-poc-order-service:artifact-2026}"
POC_NETWORK="${POC_NETWORK:-llm-microservices-poc_poc-net}"
RESULTS_DIR="${RESULTS_DIR:-results/$(date +%Y%m%d_%H%M%S)}"
mkdir -p "$RESULTS_DIR"

log(){ printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

wait_agent(){
  log "Waiting for agent-orchestrator..."
  for _ in $(seq 1 60); do
    curl -sf "$AGENT_URL/api/diagnose/health" >/dev/null 2>&1 && return 0
    sleep 2
  done
  echo "Agent not ready after 120s" >&2; exit 1
}

reset_faults(){
  curl -sf -X POST "$PAYMENT_URL/api/payments/fault" -H 'Content-Type: application/json' -d '{"enabled":false,"type":"NONE","delayMs":0}' >/dev/null 2>&1 || true
  curl -sf -X POST "$INVENTORY_URL/api/inventory/fault" -H 'Content-Type: application/json' -d '{"enabled":false,"delayMs":0}' >/dev/null 2>&1 || true
  docker rm -f poc-order-service-bad >/dev/null 2>&1 || true
  docker start poc-order-service >/dev/null 2>&1 || true
  docker start poc-notification-service >/dev/null 2>&1 || true
  docker start poc-postgres >/dev/null 2>&1 || true
  sleep 5
}

collect_baseline(){
  for _ in $(seq 1 10); do
    curl -sf -X POST http://localhost:8081/api/orders -H 'Content-Type: application/json' \
      -d '{"customerId":"baseline-customer","items":[{"productId":"PROD-001","quantity":1,"unitPrice":100.00}]}' >/dev/null 2>&1 || true
    sleep 2
  done
}

inject_s1(){ curl -sf -X POST "$PAYMENT_URL/api/payments/fault" -H 'Content-Type: application/json' -d '{"enabled":true,"type":"UNAVAILABLE","delayMs":0}' >/dev/null; }
inject_s2(){ curl -sf -X POST "$INVENTORY_URL/api/inventory/fault" -H 'Content-Type: application/json' -d '{"enabled":true,"delayMs":3000}' >/dev/null; }
inject_s3(){ docker stop poc-notification-service >/dev/null; }
inject_s4(){
  docker stop poc-order-service >/dev/null 2>&1 || true
  docker run -d --name poc-order-service-bad --network "$POC_NETWORK" \
    -e SPRING_PROFILES_ACTIVE=docker \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/poc \
    -e SPRING_DATASOURCE_USERNAME=poc -e SPRING_DATASOURCE_PASSWORD=poc123 \
    -e SPRING_RABBITMQ_HOST=rabbitmq -e SPRING_RABBITMQ_USERNAME=poc -e SPRING_RABBITMQ_PASSWORD=poc123 \
    -e SPRING_DATA_REDIS_HOST=redis -e PAYMENT_SERVICE_URL=http://payment-service:8082 \
    -e INVENTORY_SERVICE_URL=http://INVALID-HOST-DOES-NOT-EXIST:9999 -p 8081:8081 "$ORDER_IMAGE" >/dev/null
}
inject_s5(){ curl -sf -X POST "$PAYMENT_URL/api/payments/fault" -H 'Content-Type: application/json' -d '{"enabled":true,"type":"AUTH_FAILURE","delayMs":0}' >/dev/null; }
inject_s6(){ docker stop poc-postgres >/dev/null; sleep 5; }

run_scenario(){
  local id="$1" symptom="$2" suspected="$3" injector="$4" expected="$5" cause_regex="$6"
  reset_faults
  "$injector"
  sleep 5

  for i in $(seq 1 5); do
    curl -sf -X POST http://localhost:8081/api/orders -H 'Content-Type: application/json' \
      -d "{\"customerId\":\"test-$id\",\"items\":[{\"productId\":\"PROD-00$i\",\"quantity\":1,\"unitPrice\":50.00}]}" >/dev/null 2>&1 || true
    sleep 1
  done
  sleep 3

  local started ended response_file audit_file
  started=$(date +%s%3N)
  response_file="$RESULTS_DIR/${id}-response.json"
  audit_file="$RESULTS_DIR/${id}-audit.json"

  if ! curl -sf -X POST "$AGENT_URL/api/diagnose" -H 'Content-Type: application/json' \
      -d "{\"scenarioId\":\"$id\",\"symptomDescription\":\"$symptom\",\"suspectedService\":\"$suspected\",\"lookbackMinutes\":10}" \
      -o "$response_file"; then
    printf '{"scenarioId":"%s","error":"agent call failed"}\n' "$id" >> "$RESULTS_DIR/errors.jsonl"
    reset_faults; return 0
  fi
  ended=$(date +%s%3N)
  curl -sf "$AGENT_URL/api/diagnose/audit/$id" -o "$audit_file" || true

  python3 - "$response_file" "$RESULTS_DIR/evaluation-results.json" "$id" "$symptom" "$expected" "$cause_regex" "$((ended-started))" <<'PY'
import json,re,sys
response_path,out,id,symptom,expected,cause_regex,wall=sys.argv[1:]
with open(response_path,encoding='utf-8') as f: d=json.load(f)
detected=str(d.get('affectedService','unknown'))
probable=str(d.get('probableCause',''))
metrics=d.get('metrics') or {}; gov=d.get('governance') or {}
service_ok=bool(re.search(expected.replace('-',''),detected.replace('-',''),re.I))
cause_ok=bool(re.search(cause_regex,probable,re.I))
record={
 'scenarioId':id,'symptom':symptom,'expectedService':expected,'detectedService':detected,
 'serviceCorrect':service_ok,'causeCorrect':cause_ok,'diagnosisCorrect':service_ok and cause_ok,
 'confidenceLevel':d.get('confidenceLevel','UNKNOWN'),
 'toolCallCount':int(metrics.get('toolCallCount',0) or 0),
 'evidenceCount':int(metrics.get('evidenceCount',0) or 0),
 'traceabilityScore':float(metrics.get('traceabilityScore',0.0) or 0.0),
 'durationMs':int(metrics.get('totalDurationMs',wall) or wall),
 'requiresHumanApproval':bool(gov.get('requiresHumanApproval',False)),
 'riskLevel':gov.get('riskLevel','LOW')}
with open(out,'a',encoding='utf-8') as f:f.write(json.dumps(record,ensure_ascii=False)+'\n')
print(json.dumps(record,ensure_ascii=False,indent=2))
PY
  reset_faults
}

summary(){
python3 - "$RESULTS_DIR/evaluation-results.json" "$RESULTS_DIR/summary.json" <<'PY'
import json,pathlib,sys
src,dst=map(pathlib.Path,sys.argv[1:])
rows=[json.loads(x) for x in src.read_text().splitlines() if x.strip()]
if not rows: raise SystemExit('No successful scenario results.')
n=len(rows)
s={
 'totalScenarios':n,
 'correctDiagnoses':sum(r['diagnosisCorrect'] for r in rows),
 'correctServiceDetection':sum(r['serviceCorrect'] for r in rows),
 'avgToolCallsPerSession':round(sum(r['toolCallCount'] for r in rows)/n,2),
 'avgTraceabilityScore':round(sum(r['traceabilityScore'] for r in rows)/n,3),
 'avgResponseTimeMs':round(sum(r['durationMs'] for r in rows)/n),
 'scenariosRequiringHumanApproval':sum(r['requiresHumanApproval'] for r in rows),
 'results':rows}
dst.write_text(json.dumps(s,ensure_ascii=False,indent=2))
print(json.dumps(s,ensure_ascii=False,indent=2))
PY
}

main(){
  local s="${1:-ALL}"
  wait_agent; collect_baseline; : > "$RESULTS_DIR/evaluation-results.json"
  if [[ "$s" == ALL || "$s" == S1 ]]; then run_scenario S1 "Orders are failing at checkout with HTTP 503 from payment-service." payment-service inject_s1 payment "unavailable|503|down|unreachable|service.unavail|payment.*fail|connection.*refused|fault[ _-]?injection|fault.*inject"; fi
  if [[ "$s" == ALL || "$s" == S2 ]]; then run_scenario S2 "Checkout is slow; inventory-service calls take more than 3 seconds." inventory-service inject_s2 inventory "latency|slow|delay|timeout|performance|3.*second"; fi
  if [[ "$s" == ALL || "$s" == S3 ]]; then run_scenario S3 "Notifications are not sent; RabbitMQ has unprocessed messages and zero consumers." notification-service inject_s3 notification "queue|consumer|message|rabbit|accumul|backlog|unprocessed"; fi
  if [[ "$s" == ALL || "$s" == S4 ]]; then run_scenario S4 "Order service fails on inventory calls; INVENTORY_SERVICE_URL may be misconfigured." order-service inject_s4 order "config|invalid|host|misconfigur|url|unknown.*host|dns|resolution"; fi
  if [[ "$s" == ALL || "$s" == S5 ]]; then run_scenario S5 "Payment service returns HTTP 401 Unauthorized." payment-service inject_s5 payment "auth|401|unauthori|token|credential|bearer|expired"; fi
  if [[ "$s" == ALL || "$s" == S6 ]]; then run_scenario S6 "Services show SQL/JDBC connection failures and the database is unreachable." postgres inject_s6 "postgres|order|payment" "database|postgres|sql|connection|jdbc|datasource|db.*down"; fi
  reset_faults; summary; log "Results saved to $RESULTS_DIR"
}

main "${1:-ALL}"
