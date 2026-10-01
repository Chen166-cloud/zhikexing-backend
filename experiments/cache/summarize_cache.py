"""Combine measured cache stages, raw request percentiles and UTC resource samples."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime
import json
from pathlib import Path
import statistics

from run_cache import distribution


def timestamp(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def summarize(directory: Path, resources: list[dict]) -> dict:
    summary = json.loads((directory / "summary.json").read_text(encoding="utf-8-sig"))
    stages = [stage for stage in summary["stages"] if stage["phase"] == "measure"]
    selected = {stage["stageId"] for stage in stages}
    latencies, success_latencies, scheduler_lag = [], [], []
    raw_counts = Counter()
    with (directory / "requests.jsonl").open(encoding="utf-8") as stream:
        for line in stream:
            row = json.loads(line)
            if row["stageId"] not in selected:
                continue
            raw_counts["offered"] += 1
            raw_counts["sent"] += bool(row["sent"])
            raw_counts["success"] += bool(row["success"])
            raw_counts["dropped"] += bool(row["dropped"])
            scheduler_lag.append(row["scheduling_lag_ms"])
            if row["sent"]:
                latencies.append(row["latency_ms"])
            if row["success"]:
                success_latencies.append(row["latency_ms"])
    window_seconds = sum(stage["window_seconds"] for stage in stages)
    sql_calls = sum(stage["metrics"]["sql_calls"] for stage in stages)
    loader_calls = sum(stage["metrics"]["loader_calls"] for stage in stages)
    hit, miss = 0.0, 0.0
    for stage in stages:
        for snapshot in stage["metrics"]["by_instance"]:
            for key, value in snapshot.items():
                if key.startswith("cache_gets_total{"):
                    if 'result="hit"' in key:
                        hit += value
                    elif 'result="miss"' in key:
                        miss += value
    resource_rows = defaultdict(list)
    for sample in resources:
        instant = timestamp(sample["atUtc"])
        if any(timestamp(stage["window_started_at"]) <= instant <= timestamp(stage["window_ended_at"]) for stage in stages):
            for container in sample.get("containers", []):
                resource_rows[container["Name"]].append(float(container["CPUPerc"].strip("%")))
    return {"directory": str(directory), "mode": summary["mode"], "status": summary["status"],
            "machine": summary.get("machine"), "scriptSha256": summary.get("scriptSha256"),
            "authorizationChecks": summary.get("authorizationChecks"), "responseHashes": summary.get("responseHashes"),
            "phases": len(stages), "window_seconds": window_seconds, "raw_counts": dict(raw_counts),
            "offered_rps": raw_counts["offered"] / window_seconds,
            "success_rps": sum(stage["success_in_window"] for stage in stages) / window_seconds,
            "latency_ms_pooled": distribution(latencies), "successful_latency_ms_pooled": distribution(success_latencies),
            "scheduler_lag_ms_pooled": distribution(scheduler_lag),
            "round_p95_ms": [stage["successful_latency_ms"]["p95"] for stage in stages],
            "round_p95_median_ms": statistics.median(stage["successful_latency_ms"]["p95"] for stage in stages),
            "loader_calls": loader_calls, "sql_calls": sql_calls, "sql_qps": sql_calls / window_seconds,
            "l1_hit_rate": hit / (hit + miss) if hit + miss else None,
            "resources": {name: {"samples": len(values), "docker_cpu_percent": distribution(values)}
                          for name, values in resource_rows.items()},
            "errors": dict(sum((Counter(stage["errors"]) for stage in stages), Counter()))}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directories", nargs="+", type=Path)
    parser.add_argument("--resource-log", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    resources = []
    if args.resource_log:
        for line in args.resource_log.read_text(encoding="utf-8-sig").splitlines():
            resources.append(json.loads(line))
    results = [summarize(directory, resources) for directory in args.directories]
    baseline = next((row for row in results if row["mode"] == "db"), None)
    if baseline:
        for row in results:
            row["sql_reduction_vs_db"] = 1 - row["sql_qps"] / baseline["sql_qps"]
            row["pooled_p95_reduction_vs_db"] = 1 - row["successful_latency_ms_pooled"]["p95"] / baseline["successful_latency_ms_pooled"]["p95"]
    output = {"schemaVersion": 1, "scope": "measure phases only; warmup and calibrations excluded",
              "response_hashes_match": all(row["responseHashes"] == results[0]["responseHashes"] for row in results),
              "results": results}
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps([{key: row[key] for key in ["mode", "success_rps", "successful_latency_ms_pooled", "raw_counts", "sql_qps", "l1_hit_rate"]}
                      for row in results], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
