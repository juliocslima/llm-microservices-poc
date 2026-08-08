#!/usr/bin/env python3
import csv
import json
import pathlib
import sys
from statistics import mean

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "results")
files = sorted(root.glob("run_*/evaluation-results.json"))
if not files:
    raise SystemExit(f"No run_*/evaluation-results.json files found under {root}")

rows = []
for f in files:
    run_id = f.parent.name
    for line in f.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        item = json.loads(line)
        item["run"] = run_id
        rows.append(item)

if not rows:
    raise SystemExit("No scenario results found.")

summary = {
    "runsFound": len(files),
    "scenarioExecutions": len(rows),
    "correctServiceDetection": sum(bool(r.get("serviceCorrect")) for r in rows),
    "correctDiagnoses": sum(bool(r.get("diagnosisCorrect")) for r in rows),
    "serviceDetectionRate": round(mean(bool(r.get("serviceCorrect")) for r in rows), 6),
    "diagnosisAccuracy": round(mean(bool(r.get("diagnosisCorrect")) for r in rows), 6),
    "avgTraceabilityScore": round(mean(float(r.get("traceabilityScore", 0.0)) for r in rows), 6),
    "avgToolCallsPerSession": round(mean(float(r.get("toolCallCount", 0)) for r in rows), 3),
    "avgEvidenceCount": round(mean(float(r.get("evidenceCount", 0)) for r in rows), 3),
    "avgResponseTimeMs": round(mean(float(r.get("durationMs", 0)) for r in rows), 3),
    "humanApprovalRate": round(mean(bool(r.get("requiresHumanApproval")) for r in rows), 6),
}

per_scenario = {}
for scenario in sorted({r["scenarioId"] for r in rows}):
    subset = [r for r in rows if r["scenarioId"] == scenario]
    per_scenario[scenario] = {
        "n": len(subset),
        "serviceDetectionRate": round(mean(bool(r.get("serviceCorrect")) for r in subset), 6),
        "diagnosisAccuracy": round(mean(bool(r.get("diagnosisCorrect")) for r in subset), 6),
        "avgTraceabilityScore": round(mean(float(r.get("traceabilityScore", 0.0)) for r in subset), 6),
        "avgResponseTimeMs": round(mean(float(r.get("durationMs", 0)) for r in subset), 3),
    }
summary["perScenario"] = per_scenario

(root / "aggregate-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

fields = [
    "run", "scenarioId", "expectedService", "detectedService", "serviceCorrect",
    "causeCorrect", "diagnosisCorrect", "confidenceLevel", "toolCallCount",
    "evidenceCount", "traceabilityScore", "durationMs", "requiresHumanApproval", "riskLevel"
]
with (root / "aggregate-results.csv").open("w", newline="", encoding="utf-8") as fh:
    writer = csv.DictWriter(fh, fieldnames=fields, extrasaction="ignore")
    writer.writeheader()
    writer.writerows(rows)

print(json.dumps(summary, ensure_ascii=False, indent=2))
print(f"Wrote {root / 'aggregate-summary.json'}")
print(f"Wrote {root / 'aggregate-results.csv'}")
