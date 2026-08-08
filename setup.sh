#!/usr/bin/env bash
set -euo pipefail

COMPOSE="docker compose"
MODEL="${OLLAMA_MODEL:-llama3:8b}"

log(){ printf '[setup] %s\n' "$*"; }

command -v docker >/dev/null || { echo 'Docker is required.' >&2; exit 1; }
docker compose version >/dev/null || { echo 'Docker Compose v2 is required.' >&2; exit 1; }
command -v curl >/dev/null || { echo 'curl is required on the host.' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'python3 is required on the host.' >&2; exit 1; }

log "Building application images..."
$COMPOSE build --parallel

log "Starting Ollama and downloading ${MODEL}..."
$COMPOSE up -d ollama
$COMPOSE up ollama-pull

log "Starting the complete environment..."
$COMPOSE up -d

log "Waiting for agent-orchestrator..."
for _ in $(seq 1 90); do
  if curl -sf http://localhost:8090/api/diagnose/health >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

if ! curl -sf http://localhost:8090/api/diagnose/health >/dev/null 2>&1; then
  echo 'agent-orchestrator did not become ready. Run: docker compose logs agent-orchestrator' >&2
  exit 1
fi

log "Validating Ollama model..."
curl -sf http://localhost:11434/api/tags | python3 -c "import json,sys; d=json.load(sys.stdin); names=[m.get('name') for m in d.get('models',[])]; print('Models:', ', '.join(filter(None,names))); raise SystemExit(0 if '${MODEL}' in names else 1)"

log "Environment ready."
echo "Agent API:  http://localhost:8090"
echo "Grafana:    http://localhost:3000"
echo "Prometheus: http://localhost:9090"
echo "RabbitMQ:   http://localhost:15672"
echo
echo "Run one complete S1-S6 round with:"
echo "  ./scripts/run-evaluation.sh ALL"
echo
echo "Run the 30-round paper experiment with:"
echo "  ./scripts/run-paper-experiment.sh"
