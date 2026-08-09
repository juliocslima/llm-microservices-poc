# GTAA-LM Artifact Evaluation Guide

This document provides a compact evaluator-oriented path for verifying the GTAA-LM research artifact submitted to the CBSoft 2026 Artifact Festival.

## 1. Scope

The artifact contains the implementation and experimental environment used to instantiate and evaluate the GTAA-LM architecture described in the associated SBCARS 2026 paper.

Requested badges:

- **Available**
- **Functional**

## 2. Expected evaluator outcome

A successful functional verification does not require reproducing the exact stochastic wording or exact diagnosis rate reported in the paper.

The functional gate is satisfied when:

1. the Docker environment starts successfully;
2. the Ollama model is available;
3. the agent endpoint becomes healthy;
4. the fault-injection scenario executes;
5. the agent invokes the controlled diagnostic tools;
6. a structured diagnostic result is produced;
7. governance and traceability metrics are generated;
8. the runner saves the result files; and
9. reset returns the environment to an operational state.

## 3. Setup

From a clean clone:

```bash
git clone https://github.com/juliocslima/llm-microservices-poc.git
cd llm-microservices-poc
bash setup.sh
```

The first execution downloads the required Docker images and `llama3:8b`, so Internet access is required initially.

## 4. Verify services

```bash
docker compose ps
curl -sf http://localhost:8090/api/diagnose/health
curl -sf http://localhost:11434/api/tags | python3 -m json.tool
```

The agent health endpoint must return successfully and the Ollama model list must include `llama3:8b`.

## 5. Recommended smoke test

Run one scenario:

```bash
bash scripts/run-evaluation.sh S1
```

S1 injects payment-service unavailability. The evaluator should observe a newly created timestamped directory under `results/` containing at least:

```text
S1-response.json
S1-audit.json
evaluation-results.json
summary.json
```

The exact LLM text can vary. The response should remain structured and include diagnostic, governance, and metric fields.

## 6. Complete S1-S6 round

For a broader functional test:

```bash
bash scripts/run-evaluation.sh ALL
```

This executes all six documented fault scenarios and automatically resets faults between scenarios.

The validation performed before artifact freezing successfully executed all six scenarios, identified the affected service in 6/6 cases, and completed the controlled six-tool workflow in every session. One S5 run returned a generic fault-injection cause rather than the scenario-specific HTTP 401 authentication cause; this is preserved as a diagnostic miss rather than hidden by the evaluation rule.

## 7. Full paper protocol

The complete experiment consists of 30 rounds × 6 scenarios = 180 scenario executions:

```bash
bash scripts/run-paper-experiment.sh
```

A shorter multi-round smoke reproduction can be run with:

```bash
RUNS=2 bash scripts/run-paper-experiment.sh
```

The multi-round runner generates aggregate JSON and CSV outputs using `scripts/aggregate-results.py`.

## 8. Hardware and execution time

LLM inference runs locally through Ollama and is hardware-dependent. The artifact was validated on a CPU-only notebook with an Intel Core i7 5th generation, 16 GB RAM, and SATA SSD while the full microservices and observability stack ran on the same machine.

On resource-constrained CPU-only hosts, individual scenarios may take several minutes. This does not indicate a failure of the artifact. Faster CPU, additional RAM, or GPU acceleration may substantially reduce inference time without changing the workflow.

## 9. Known limitation: S6

S6 intentionally stops PostgreSQL. PostgreSQL is also used as the audit persistence backend, so some persisted audit information may be unavailable during that scenario. The diagnosis workflow itself continues and this architectural limitation is intentionally documented.

## 10. Results and provenance

- `README.md` — complete artifact documentation
- `experiment-config.yml` — experiment, model, scenario, environment, and validation metadata
- `results/README.md` — interpretation of reported and reproduced results
- `CITATION.cff` — citation metadata
- `LICENSE` — MIT License

## 11. Cleaning the environment

Stop containers while preserving volumes:

```bash
docker compose down
```

Remove all generated container state and model data:

```bash
docker compose down -v
```

## 12. Reproducibility interpretation

The artifact uses an LLM-based diagnostic agent. Therefore, exact textual output equality is not the reproduction criterion. Evaluators should verify executable workflow, controlled tool use, structured outputs, traceability, governance, fault injection/reset, and result generation.
