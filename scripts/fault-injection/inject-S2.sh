#!/usr/bin/env bash
# Inject fault scenario S2 and immediately trigger agent diagnosis
# Usage: ./inject-S2.sh [--diagnose-only]
set -euo pipefail
source "$(dirname $0)/../lib/common.sh"
inject_S2
echo "Fault injected. Run: curl -X POST http://localhost:8090/api/diagnose -H 'Content-Type: application/json' \"
echo "  -d '{\"scenarioId\":\"S2\",\"symptomDescription\":\"<describe symptom>\",\"lookbackMinutes\":10}'"
