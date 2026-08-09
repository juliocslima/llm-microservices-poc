# Experimental Results

This directory contains experimental outputs associated with the GTAA-LM evaluation.

## Reported experiment

The paper reports an experiment with **30 complete rounds × 6 failure scenarios = 180 scenario executions**. The artifact-level configuration for that protocol is recorded in `../experiment-config.yml`.

The reported aggregate results are:

- affected service correctly identified in **180/180** executions;
- complete diagnosis correctly identified in **112/180** executions;
- mean traceability score of **0.945**;
- human-approval flag raised in **180/180** executions.

These values are paper-level aggregate results. Because the LLM is stochastic and local inference/runtime versions may differ, a new reproduction is not expected to match every individual diagnosis verbatim.

## Artifact validation round

A complete S1–S6 validation round was executed on the resource-constrained CPU-only host documented in `../experiment-config.yml` and `../README.md`.

The validation round produced:

- **6/6** affected services correctly identified;
- **5/6** complete diagnoses classified as correct by the evaluation runner;
- **6.0** tool calls per diagnostic session;
- mean traceability score of **0.975**;
- human-approval flag raised in **6/6** scenarios.

The S5 execution correctly identified `payment-service`, but returned the generic cause `FAULT_INJECTION` instead of the scenario-specific authentication failure / HTTP 401 cause. This result is intentionally preserved as an experimental diagnostic miss rather than changing the evaluator to force a pass.

## File organization

Existing timestamped directories under `results/` correspond to individual experiment or validation executions. A newly generated evaluation round normally contains:

```text
<timestamp>/
├── S1-response.json
├── S1-audit.json
├── S2-response.json
├── S2-audit.json
├── ...
├── S6-response.json
├── S6-audit.json
├── evaluation-results.json
└── summary.json
```

The exact audit files available may vary for S6 because PostgreSQL is intentionally stopped in that scenario while also serving as the persistence backend for the audit component.

## Reproducing results

Run a single full S1–S6 round:

```bash
bash scripts/run-evaluation.sh ALL
```

Run one scenario:

```bash
bash scripts/run-evaluation.sh S1
```

Run the complete paper protocol:

```bash
bash scripts/run-paper-experiment.sh
```

Run a short smoke reproduction with two rounds:

```bash
RUNS=2 bash scripts/run-paper-experiment.sh
```

Aggregate an existing multi-round reproduction:

```bash
python3 scripts/aggregate-results.py results/reproduction_<timestamp>
```

## Interpretation

The artifact should be considered functionally reproduced when the environment starts, fault injection and reset complete, diagnostic sessions produce structured results, controlled tools execute, governance and traceability metrics are generated, and the evaluation/aggregation scripts complete.

A diagnostic miss in an individual stochastic LLM run is an experimental outcome, not by itself an artifact execution failure.
