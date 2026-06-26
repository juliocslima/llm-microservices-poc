#!/usr/bin/env bash
# =============================================================================
# setup.sh — One-command POC setup
#
# Usage:
#   ./setup.sh [--model llama3|mistral|llama3.1]
#
# Prerequisites: Docker, Docker Compose v2
# =============================================================================
set -euo pipefail

MODEL="${1:-llama3}"
COMPOSE="docker compose"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log()  { echo -e "${CYAN}[setup]${NC} $*"; }
ok()   { echo -e "${GREEN}✓${NC} $*"; }
warn() { echo -e "${YELLOW}⚠${NC} $*"; }

echo "╔══════════════════════════════════════════════════════════╗"
echo "║  LLM Microservices POC — Setup                          ║"
echo "║  SBCARS 2026                                             ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# 1. Build all Java services
log "Building Java services (Maven multi-module)..."
$COMPOSE build --parallel
ok "All images built"

# 2. Start infrastructure first
log "Starting infrastructure (postgres, rabbitmq, redis)..."
$COMPOSE up -d postgres rabbitmq redis
log "Waiting for infrastructure to be healthy..."
sleep 10
ok "Infrastructure started"

# 3. Start observability stack
log "Starting observability stack (prometheus, loki, tempo, grafana)..."
$COMPOSE up -d prometheus loki tempo promtail grafana
sleep 5
ok "Observability stack started"

# 4. Check connectivity to external Ollama server
log "Checking connectivity to Ollama server at localhost:11434..."
if curl -sf http://localhost:11434/api/tags > /dev/null 2>&1; then
    ok "Ollama server reachable at localhost:11434"
    MODELS=$(curl -sf http://localhost:11434/api/tags | python3 -c "import sys,json; tags=json.load(sys.stdin); print(', '.join(m['name'] for m in tags.get('models',[])))" 2>/dev/null || echo "unknown")
    log "Available models: $MODELS"
    if echo "$MODELS" | grep -q "llama3:8b"; then
        ok "Model llama3:8b is available"
    else
        warn "Model llama3:8b not found on server. Run on the Ollama host: ollama pull llama3:8b"
    fi
else
    warn "Cannot reach Ollama at localhost:11434 — check network connectivity before starting the agent"
fi

# 5. Start microservices
log "Starting microservices..."
$COMPOSE up -d api-gateway order-service payment-service inventory-service notification-service
log "Waiting for microservices to start..."
sleep 15

# 6. Start agent orchestrator
log "Starting agent orchestrator..."
$COMPOSE up -d agent-orchestrator
sleep 10

# 7. Health checks
log "Running health checks..."
SERVICES=(
    "api-gateway:8080"
    "order-service:8081"
    "payment-service:8082"
    "inventory-service:8083"
    "notification-service:8084"
    "agent-orchestrator:8090"
)

ALL_OK=true
for svc_port in "${SERVICES[@]}"; do
    svc="${svc_port%%:*}"
    port="${svc_port##*:}"
    if curl -sf "http://localhost:$port/actuator/health" > /dev/null 2>&1; then
        ok "$svc is UP on port $port"
    else
        warn "$svc not yet ready on port $port"
        ALL_OK=false
    fi
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Access URLs:"
echo "  Grafana:           http://localhost:3000  (admin / admin123)"
echo "  Prometheus:        http://localhost:9090"
echo "  RabbitMQ Mgmt:     http://localhost:15672 (poc / poc123)"
echo "  Agent API:         http://localhost:8090/api/diagnose"
echo "  Order Service:     http://localhost:8081/api/orders"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "  Quick test:"
echo "  curl -X POST http://localhost:8090/api/diagnose \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"scenarioId\":\"TEST\",\"symptomDescription\":\"System check\",\"lookbackMinutes\":5}'"
echo ""
echo "  Run evaluation:"
echo "  chmod +x scripts/run-evaluation.sh && ./scripts/run-evaluation.sh"
echo ""
if [ "$ALL_OK" = "false" ]; then
    warn "Some services are not yet ready. Check 'docker compose logs' for details."
fi
