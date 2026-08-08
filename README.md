# GTAA-LM Research Artifact — SBCARS 2026

Research artifact accompanying the paper **“GTAA-LM: Arquitetura de Agentes Baseados em LLMs para Diagnóstico e Apoio à Orquestração de Microsserviços em Sistemas Distribuídos”**, accepted at the **20th Brazilian Symposium on Software Components, Architectures, and Reuse (SBCARS 2026), CBSoft 2026**.

This repository contains the source code, containerized execution environment, fault-injection scenarios, observability stack, evaluation scripts, and experimental outputs used to instantiate and evaluate GTAA-LM.

## Artifact availability

- Source repository: https://github.com/juliocslima/llm-microservices-poc
- Persistent archive: https://doi.org/10.5281/zenodo.21853147
- License: MIT (`LICENSE`)
- Claimed CBSoft Artifact Festival badges: **Available** and **Functional**

The Zenodo record is intended to provide the immutable snapshot submitted for artifact evaluation. The GitHub repository remains the development location.

## What is included

```text
llm-microservices-poc/
├── api-gateway/              # Spring Cloud Gateway — port 8080
├── order-service/            # Order management — port 8081
├── payment-service/          # Payment processing — port 8082
├── inventory-service/        # Inventory — port 8083
├── notification-service/     # RabbitMQ consumer — port 8084
├── agent-orchestrator/       # LangChain4j + Ollama agent — port 8090
├── infra/
│   ├── postgres/
│   ├── prometheus/
│   ├── loki/
│   ├── tempo/
│   └── grafana/
├── scripts/
│   ├── run-evaluation.sh       # one S1–S6 evaluation round
│   ├── run-paper-experiment.sh # 30 complete rounds
│   ├── aggregate-results.py    # aggregate reproduction results
│   └── fault-injection/
├── results/                    # experimental outputs distributed with the artifact
├── experiment-config.yml       # experiment provenance and reproduction configuration
├── docker-compose.yml          # complete containerized environment, including Ollama
├── setup.sh
└── LICENSE
```

## Architecture represented by the artifact

The implementation instantiates the six architectural concerns described in the paper:

| Concern | Artifact implementation |
|---|---|
| Microservices | API Gateway + Order, Payment, Inventory and Notification services |
| Observability | Prometheus, Loki, Tempo and Grafana |
| Controlled tools/connectors | Health, logs, metrics, topology, queues and incident-history tools |
| Agentic layer | ReAct-style diagnostic flow implemented with LangChain4j and Ollama |
| Governance and traceability | `GovernanceService` and `AuditService` |
| Human-agent interface | REST diagnostic and decision endpoints |

## Requirements

Recommended evaluation host:

- Linux x86_64 (Ubuntu recommended)
- Docker Engine 24+ and Docker Compose v2
- 16 GB RAM or more
- at least 10 GB free disk space
- `curl` and Python 3 on the host
- Internet access on the first execution to download container images and `llama3:8b`

Java 21 and Maven 3.9 are only required for development outside Docker.

> The artifact reproduction environment does **not** require access to the original laboratory Ollama endpoint. The historical endpoint used during the reported experiment is preserved only as provenance in `experiment-config.yml`. For evaluation, Ollama runs inside `docker-compose.yml`.

## Quick start

From a clean clone:

```bash
git clone https://github.com/juliocslima/llm-microservices-poc.git
cd llm-microservices-poc
chmod +x setup.sh scripts/*.sh
./setup.sh
```

`setup.sh` builds the application images, starts the Dockerized Ollama service, downloads `llama3:8b`, starts the complete stack, and waits for the agent endpoint to become available.

The first execution can take several minutes because the LLM model must be downloaded.

### Verify the environment

```bash
docker compose ps
curl -sf http://localhost:8090/api/diagnose/health
curl -sf http://localhost:11434/api/tags | python3 -m json.tool
```

Useful interfaces:

| Component | Address | Credentials |
|---|---|---|
| Agent API | http://localhost:8090 | — |
| Grafana | http://localhost:3000 | admin / admin123 |
| Prometheus | http://localhost:9090 | — |
| RabbitMQ Management | http://localhost:15672 | poc / poc123 |
| Ollama | http://localhost:11434 | — |

## Reproducing one evaluation round

A round executes all six fault scenarios:

```bash
./scripts/run-evaluation.sh ALL
```

A single scenario can also be executed:

```bash
./scripts/run-evaluation.sh S1
```

Each round creates an output directory containing the raw agent responses, audit information when available, `evaluation-results.json`, and `summary.json`.

## Reproducing the complete paper experiment

The experiment reported in the artifact configuration consists of **30 complete rounds × 6 scenarios = 180 scenario executions**.

```bash
./scripts/run-paper-experiment.sh
```

For a shorter evaluator smoke test, override the number of rounds:

```bash
RUNS=2 ./scripts/run-paper-experiment.sh
```

After the final round, `scripts/aggregate-results.py` produces:

```text
aggregate-summary.json
aggregate-results.csv
```

The aggregation reports service-detection rate, complete-diagnosis accuracy, traceability, tool use, evidence count, response time, human-approval rate, and per-scenario metrics.

To aggregate an existing reproduction directory manually:

```bash
python3 scripts/aggregate-results.py results/reproduction_<timestamp>
```

## Evaluated failure scenarios

| ID | Failure | Expected affected component |
|---|---|---|
| S1 | payment service returns HTTP 503 | payment-service |
| S2 | artificial inventory latency of 3000 ms | inventory-service |
| S3 | notification consumer stopped / queue accumulation | notification-service |
| S4 | invalid `INVENTORY_SERVICE_URL` | order-service |
| S5 | simulated inter-service authentication failure / HTTP 401 | payment-service |
| S6 | PostgreSQL unavailable | postgres / dependent services |

The expected causes and matching rules are documented in `experiment-config.yml` and encoded by the evaluation runner.

## Important note about S6

PostgreSQL is both a monitored dependency and the persistence backend used by the audit component. When PostgreSQL itself is intentionally stopped in S6, persistence of the audit trail may be unavailable even though the diagnostic workflow continues. This known architectural limitation is intentionally preserved and documented rather than hidden from the evaluator.

## Scenario S4 portability

S4 starts an alternative `order-service` container with an invalid inventory URL. To avoid depending on the directory name chosen by the evaluator, the Compose project, network, and order-service image are explicitly named in `docker-compose.yml`.

## Experimental provenance

`experiment-config.yml` distinguishes two environments:

1. **Reported experiment environment** — records the historical environment used for the executions reported in the paper.
2. **Artifact reproduction environment** — the self-contained Docker setup provided to evaluators.

This distinction prevents the original laboratory endpoint from becoming a hidden reproduction dependency.

## Manual diagnostic call

```bash
curl -X POST http://localhost:8090/api/diagnose \
  -H 'Content-Type: application/json' \
  -d '{
    "scenarioId":"MANUAL",
    "symptomDescription":"Orders are failing at checkout.",
    "suspectedService":"payment-service",
    "lookbackMinutes":10
  }' | python3 -m json.tool
```

## Stopping the environment

```bash
docker compose down
```

The named volumes are preserved. To remove all generated container state, including the downloaded Ollama model:

```bash
docker compose down -v
```

## Reproducibility notes

- The exact release submitted to the Artifact Festival should be identified by a Git tag and archived on Zenodo.
- The exact Ollama version and `llama3:8b` model digest should be recorded after validation of the frozen release.
- Avoid changing the archived Zenodo snapshot after the final evaluator smoke test; publish a new version instead if corrections are required.

## License

The source code in this artifact is distributed under the MIT License. See `LICENSE`.
