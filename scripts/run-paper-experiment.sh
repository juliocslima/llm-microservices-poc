#!/usr/bin/env bash
set -euo pipefail

RUNS="${RUNS:-30}"
ROOT_RESULTS="${ROOT_RESULTS:-results/reproduction_$(date +%Y%m%d_%H%M%S)}"
mkdir -p "$ROOT_RESULTS"

echo "GTAA-LM — paper experiment reproduction"
echo "Rounds: $RUNS | Scenarios/round: 6 | Total expected: $((RUNS*6))"
echo "Output: $ROOT_RESULTS"

for run in $(seq -w 1 "$RUNS"); do
  echo ""
  echo "=== Round $run/$RUNS ==="
  run_dir="$ROOT_RESULTS/run_$run"
  mkdir -p "$run_dir"
  RESULTS_DIR="$run_dir" ./scripts/run-evaluation.sh ALL
  sleep 5
done

python3 ./scripts/aggregate-results.py "$ROOT_RESULTS"
echo "Reproduction complete: $ROOT_RESULTS"
