"""Combine explicit completed experiments into a credential-free evidence index.

Never discovers calibration directories automatically. Percentiles are reported
as the median and range of per-run percentiles, not mislabeled pooled quantiles.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import statistics


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def repeated(values):
    values = [value for value in values if value is not None]
    if any(not math.isfinite(value) for value in values):
        raise ValueError("Non-finite metric cannot be included in evidence")
    return {"n": len(values), "median": statistics.median(values),
            "min": min(values), "max": max(values)} if values else None


def reduction(before, after):
    return 100 * (1 - after / before) if before else None


def cache_summary(directories):
    rows, fingerprints = {}, []
    for directory in directories:
        source = read(directory / "summary.json")
        if source["status"] != "completed" or source["command"] != "throughput":
            raise ValueError(f"Incomplete or non-throughput cache source: {directory}")
        if len(source["rates"]) != 1:
            raise ValueError("Cache evidence requires one offered rate per mode; do not aggregate P95 across workloads")
        stages = [stage for stage in source["stages"] if stage["phase"] == "measure"]
        if len(stages) != source["repetitions"] * len(source["rates"]):
            raise ValueError("Cache measurement count differs from protocol")
        if source["mode"] in rows:
            raise ValueError("Choose exactly one formal directory per cache mode")
        expected_rounds = set(range(1, source["repetitions"] + 1))
        if {stage["repetition"] for stage in stages} != expected_rounds:
            raise ValueError("Cache repetition identities differ from protocol")
        if any(stage["configured_rps"] != source["rates"][0] for stage in stages):
            raise ValueError("Cache stage offered rate differs from manifest")
        fingerprints.append([source.get(key) for key in
                             ("scriptSha256", "baseUrls", "paths", "tokenCount", "rates", "repetitions",
                              "stageSeconds", "warmupSeconds", "startupRates", "startupSecondsPerRate",
                              "timeoutSeconds", "dropLagThresholdMs", "maxInflight", "maxKeepalive", "python", "httpx")])
        requests = sum(stage["sent"] for stage in stages)
        sql = sum(stage["metrics"]["sql_calls"] for stage in stages)
        shared_lookups = sum(value for stage in stages for instance in stage["metrics"]["by_instance"]
                             for key, value in instance.items() if key.startswith("catalog_cache_redis_requests_total"))
        local_hits = sum(value for stage in stages for instance in stage["metrics"]["by_instance"]
                         for key, value in instance.items() if key.startswith("cache_gets_total") and 'cache="courseCatalog"' in key and 'result="hit"' in key)
        local_misses = sum(value for stage in stages for instance in stage["metrics"]["by_instance"]
                           for key, value in instance.items() if key.startswith("cache_gets_total") and 'cache="courseCatalog"' in key and 'result="miss"' in key)
        seconds = sum(stage["window_seconds"] for stage in stages)
        if requests <= 0 or seconds <= 0:
            raise ValueError("Cache evidence requires positive measured requests and duration")
        valid = not any(stage["dropped"] or stage["errors"] for stage in stages)
        valid = valid and all(stage["sent"] == stage["success"] == stage["offered"] for stage in stages)
        rows[source["mode"]] = {
            "source": str(directory / "summary.json"), "protocolValid": valid,
            "offeredRps": source["rates"], "repetitions": source["repetitions"],
            "stageSeconds": source["stageSeconds"], "warmupSeconds": source["warmupSeconds"],
            "requestPaths": source["paths"], "tokenCount": source["tokenCount"],
            "machine": source.get("machine"),
            "measurementSeconds": seconds, "measurementRequests": requests,
            "successes": sum(stage["success"] for stage in stages),
            "errors": sum(sum(stage["errors"].values()) for stage in stages),
            "dropped": sum(stage["dropped"] for stage in stages),
            "excludedStartupAndWarmupDropped": sum(stage["dropped"] for stage in source["stages"] if stage["phase"] != "measure"),
            "successRps": repeated([stage["success_rps"] for stage in stages]),
            "httpP95Ms": repeated([stage["successful_latency_ms"]["p95"] for stage in stages]),
            "scheduledToFinishP95Ms": repeated([stage["scheduled_to_finish_ms"]["p95"] for stage in stages]),
            "httpP99Ms": repeated([stage["successful_latency_ms"]["p99"] for stage in stages]),
            "schedulingLagP95Ms": repeated([stage["scheduling_lag_ms"]["p95"] for stage in stages]),
            "sqlCalls": sql, "sqlCallsPerRequest": sql / requests,
            "sqlCallsPerSecond": sql / seconds,
            "sharedCacheLookupAttempts": shared_lookups,
            "sharedCacheLookupAttemptsPerSecond": shared_lookups / seconds,
            "localCacheHits": local_hits, "localCacheMisses": local_misses,
            "catalogMetricScope": "catalog JDBC statements and shared cache first-read attempts only; excludes authentication Redis, background SQL and lock/recheck commands",
            "windows": [[stage["window_started_at"], stage["window_ended_at"]] for stage in stages]}
    result = {"modes": rows, "identicalProtocol": all(item == fingerprints[0] for item in fingerprints),
              "statistic": "median and min/max of per-run percentiles; SQL counts summed across measurement windows",
              "capacityClaim": "sustained tested workload only, not maximum server capacity"}
    if "db" in rows and "two-level" in rows:
        baseline, optimized = rows["db"], rows["two-level"]
        result["twoLevelVersusDb"] = {
            "usable": result["identicalProtocol"] and baseline["protocolValid"] and optimized["protocolValid"],
            "httpP95ReductionPercent": reduction(baseline["httpP95Ms"]["median"], optimized["httpP95Ms"]["median"]),
            "sqlPerRequestReductionPercent": reduction(baseline["sqlCallsPerRequest"], optimized["sqlCallsPerRequest"])}
    if "redis" in rows and "two-level" in rows:
        result["twoLevelVersusRedis"] = {
            "httpP95ReductionPercent": reduction(rows["redis"]["httpP95Ms"]["median"], rows["two-level"]["httpP95Ms"]["median"]),
            "catalogSharedLookupReductionPercent": reduction(rows["redis"]["sharedCacheLookupAttempts"], rows["two-level"]["sharedCacheLookupAttempts"])}
    return result


def cold_summary(directory):
    source = read(directory / "summary.json")
    stages = source["stages"]
    return {"source": str(directory / "summary.json"), "status": source["status"],
            "instances": len(source["baseUrls"]), "attemptedBursts": len(stages),
            "concurrencies": source["concurrencies"], "roundsPerConcurrency": source["rounds"],
            "protocolComplete": source["status"] == "completed" and len(stages) == len(source["concurrencies"]) * source["rounds"],
            "passedBursts": sum(stage["one_load_assertion_passed"] for stage in stages),
            "requests": sum(stage["sent"] for stage in stages),
            "loaderCalls": sum(stage["metrics"]["loader_calls"] for stage in stages),
            "sqlCalls": sum(stage["metrics"]["sql_calls"] for stage in stages),
            "meaning": "one shared cold key, each burst after local TTL expiration; not general hotspot capacity"}


def trial_summary(directories):
    result = []
    for directory in directories:
        manifest = read(directory / "manifest.json")
        groups = {}
        stage_names = set()
        for summary_path in sorted(directory.glob("*/database-summary.json")):
            database = read(summary_path)
            http = read(summary_path.parent / "summary.json")
            label = summary_path.parent.name
            stage_names.add(label)
            group = label.split("-repeat-")[0]
            groups.setdefault(group, []).append((http, database))
        if not groups:
            raise ValueError(f"No completed trial stage: {directory}")
        declared_rates = [int(value) for value in str(manifest["rates"]).split(",") if value.strip()]
        expected_groups = {f"rate-{rate}" for rate in declared_rates}
        if manifest.get("includeBurst", "burst" in groups):
            expected_groups.add("burst")
        expected_stages = {f"{label}-repeat-{repetition}" for label in expected_groups
                           for repetition in range(1, manifest["repetitions"] + 1)}
        missing_stages = sorted(expected_stages - stage_names)
        unexpected_stages = sorted(stage_names - expected_stages)
        groups = {label: {"repetitions": len(pairs), "users": sum(http["users"] for http, _ in pairs),
                         "usersPerRun": repeated([http["users"] for http, _ in pairs]),
                         "countScope": "users/httpAttempts/confirmedOrders are totals across repetitions",
                         "expectedRepetitions": manifest["repetitions"],
                         "repetitionsComplete": len(pairs) == manifest["repetitions"],
                         "httpAttempts": sum(http["httpAttempts"] for http, _ in pairs),
                         "confirmedOrders": sum(database["orders"] for _, database in pairs),
                         "allChecksPassed": all(database["passed"] and http["httpChecksPassed"] for http, database in pairs),
                         "acceptanceP95Ms": repeated([http["acceptedPrimaryLatencyMs"]["p95"] for http, _ in pairs]),
                         "orderPersistenceP95Ms": repeated([database["databaseOrderLatencyMs"]["p95"] for _, database in pairs]),
                         "steadyOrderTps": repeated([database["steadyWindow"]["ordersPerSecond"] for _, database in pairs]),
                         "tpsIncludingDrain": repeated([database["databaseOrderTpsFromFirstClaim"] for _, database in pairs]),
                         "backlogDrainSeconds": repeated([database["databaseBacklogDrainAfterLastClaimS"] for _, database in pairs]),
                         "violations": {key: sum(database["violations"][key] for _, database in pairs)
                                        for key in pairs[0][1]["violations"]}}
                  for label, pairs in groups.items()}
        result.append({"source": str(directory), "configuration": manifest, "groups": groups,
                       "protocolComplete": not missing_stages and not unexpected_stages,
                       "missingStages": missing_stages, "unexpectedStages": unexpected_stages})
    return {"runs": result, "latencyScope": "202 acceptance and MySQL order.created_at minus claim.created_at are separate; order timestamp is inside transaction, not commit acknowledgement"}


def recovery_summary(directory):
    result = {"source": str(directory), **read(directory / "summary.json")}
    groups = defaultdict(list)
    cases = defaultdict(list)
    for line in (directory / "cases.jsonl").read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        cases[row["fault"]].append(row)
        if row["fault"] != "duplicate_commands" and row["passed"] and row.get("startupMs") is not None and row.get("recoveryMs") is not None:
            groups[row["fault"]].append(row["startupMs"] + row["recoveryMs"])
    if set(cases) != set(result["groups"]):
        raise ValueError("Recovery raw case groups and summary groups disagree")
    for fault, rows in cases.items():
        group = result["groups"].get(fault)
        if not group or group["attempted"] != len(rows) or group["passed"] != sum(row["passed"] for row in rows):
            raise ValueError("Recovery raw cases and summary counts disagree")
    for fault, values in groups.items():
        result["groups"][fault]["successfulStartupPlusRecoveryP95Ms"] = sorted(values)[math.ceil(.95 * len(values)) - 1]
        result["groups"][fault]["successfulCombinedClockCases"] = len(values)
    result["attemptedCases"] = sum(group["attempted"] for group in result["groups"].values())
    result["passedCases"] = sum(group["passed"] for group in result["groups"].values())
    manifest_path = directory / "manifest.json"
    if manifest_path.exists():
        manifest = read(manifest_path)
        expected = {fault: manifest["roundsPerFault"] for fault in manifest["faults"]}
        result["protocolComplete"] = {fault: len(rows) for fault, rows in cases.items()} == expected
        result["expectedCases"] = sum(expected.values())
    else:
        result["protocolComplete"] = None
    result["combinedClock"] = "per successful restart case: post-Popen-to-health startupMs + health-ready-to-terminal recoveryMs, then nearest-rank P95; not sum of two P95 values; HTTP replay excluded"
    result["clockLimitations"] = "Combined clock excludes kill time, prelaunch work, Popen creation and approval decision while down; not full fault-to-recovery RTO. HTTP replay recoveryMs is approval-to-completion. Failed cases remain in pass rate but are excluded from successful latency percentiles."
    return result


def read_jsonl(path):
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except ValueError as exc:
            raise ValueError(f"Invalid JSON at {path}:{number}") from exc
        if not isinstance(row, dict):
            raise ValueError(f"Expected JSON object at {path}:{number}")
        rows.append(row)
    return rows


def unique_case_ids(values, label):
    if not isinstance(values, list) or any(not isinstance(value, str) or not value for value in values):
        raise ValueError(f"{label} must be a list of nonempty case IDs")
    if len(values) != len(set(values)):
        raise ValueError(f"Duplicate case IDs in {label}")
    return set(values)


def agent_evaluation_summary(directory):
    source = read(directory / "summary.json")
    manifest = read(directory / "manifest.json")
    provider = manifest.get("provider")
    if provider not in {"fixture", "bailian"} or source.get("provider") != provider:
        raise ValueError("Agent manifest and summary provider disagree or are unsupported")
    grader = manifest.get("graderVersion")
    if not isinstance(grader, str) or not grader or source.get("graderVersion") != grader:
        raise ValueError("Agent manifest and summary grader disagree")
    if "externalModelCalls" in manifest and manifest["externalModelCalls"] != (provider == "bailian"):
        raise ValueError("Agent manifest external-model flag contradicts provider")
    selected_ids = manifest.get("caseIds")
    selected = unique_case_ids(selected_ids, "Agent manifest")
    cases_path = directory / "cases.jsonl"
    rows = read_jsonl(cases_path) if cases_path.exists() else []
    executed_ids = [row.get("caseId") for row in rows]
    executed = unique_case_ids(executed_ids, "Agent raw cases")
    if not executed <= selected:
        raise ValueError("Agent raw case IDs are outside the selected manifest")
    for row in rows:
        if row.get("provider") != provider:
            raise ValueError("Agent raw case provider differs from manifest")
        if type(row.get("contractPassed")) is not bool:
            raise ValueError("Agent raw contractPassed must be boolean")
        # Early exceptions do not reach evaluate_contract; they remain failures
        # in the denominator even when no row-level grader was emitted.
        if "graderVersion" in row and row["graderVersion"] != grader:
            raise ValueError("Agent raw case grader differs from manifest")
        if row["contractPassed"] and "graderVersion" not in row:
            raise ValueError("A passing Agent raw case requires its grader version")
        expected_score = row["contractPassed"] if provider == "bailian" and manifest.get("databaseOracle") is True else None
        actual_score = row.get("taskQualityScore")
        if ((expected_score is None and actual_score is not None)
                or (expected_score is not None and (type(actual_score) is not bool or actual_score != expected_score))):
            raise ValueError("Agent raw quality score contradicts provider/database oracle or contract result")
    passed = sum(row["contractPassed"] for row in rows)
    scored = [row for row in rows if row.get("taskQualityScore") is not None]
    expected_counts = {"attempted": len(rows), "engineeringContractPassed": passed,
                       "scoredRealModelTasks": len(scored)}
    for key, expected in expected_counts.items():
        if type(source.get(key)) is not int or source[key] != expected:
            raise ValueError(f"Agent raw cases and summary {key} disagree")
    expected_rate = sum(row["taskQualityScore"] for row in scored) / len(scored) if scored else None
    actual_rate = source.get("ruleTaskPassRate")
    if ((expected_rate is None and actual_rate is not None)
            or (expected_rate is not None and (type(actual_rate) not in (int, float)
                or not math.isclose(actual_rate, expected_rate, rel_tol=0, abs_tol=1e-12)))):
        raise ValueError("Agent raw quality scores and summary pass rate disagree")
    remaining = [case_id for case_id in selected_ids if case_id not in executed]
    if "selectedCases" in source and (type(source["selectedCases"]) is not int or source["selectedCases"] != len(selected)):
        raise ValueError("Agent manifest and summary selectedCases disagree")
    if "notExecutedCaseIds" in source and unique_case_ids(source["notExecutedCaseIds"], "Agent summary notExecutedCaseIds") != set(remaining):
        raise ValueError("Agent manifest/raw cases and summary notExecutedCaseIds disagree")
    finalized = all(key in source for key in ("selectedCases", "notExecutedCaseIds", "budgetStoppedReason"))
    return {"source": str(directory), **source, "selectedCases": len(selected),
            "notExecutedCaseIds": remaining,
            "protocolComplete": finalized and not remaining and not source.get("budgetStoppedReason"),
            "rawValidation": {"verified": True, "summaryFinalized": finalized,
                "rawCaseFilePresent": cases_path.exists(), "datasetSha256": manifest.get("datasetSha256"),
                "executedCaseIds": executed_ids, "uniqueExecutedCases": len(executed),
                "failedCases": len(rows) - passed, "recordedErrorCases": sum(bool(row.get("error")) for row in rows),
                "failuresWithoutRowGrader": sum(not row["contractPassed"] and "graderVersion" not in row for row in rows)},
            "evidenceScope": "fixture engineering contracts with real services; no model-quality score" if provider == "fixture" else "real-model rule contracts; unrestricted answer semantics require separate review"}


def agent_coverage(dataset, evaluations):
    rows = read_jsonl(dataset)
    dataset_ids = [row.get("id") for row in rows]
    all_ids = unique_case_ids(dataset_ids, "Agent frozen dataset")
    if any(type(row.get("fixtureSmokeEligible")) is not bool for row in rows):
        raise ValueError("Agent frozen dataset requires explicit fixtureSmokeEligible flags")
    digest = hashlib.sha256(dataset.read_bytes()).hexdigest()
    eligible = {row["id"] for row in rows if row["fixtureSmokeEligible"]}
    by_provider = {"fixture": set(), "bailian": set()}
    for evaluation in evaluations:
        validated = evaluation["rawValidation"]
        if not validated["verified"] or validated["datasetSha256"] != digest:
            raise ValueError("Agent evaluation dataset hash differs from the frozen coverage dataset")
        executed = set(validated["executedCaseIds"])
        if not executed <= all_ids:
            raise ValueError("Agent evaluated case IDs are absent from frozen dataset")
        by_provider[evaluation["provider"]].update(executed)
    fixture, real = by_provider["fixture"], by_provider["bailian"]
    ordered = lambda ids: [case_id for case_id in dataset_ids if case_id in ids]
    return {"dataset": str(dataset), "datasetSha256": digest, "totalCases": len(all_ids),
            "fixtureEligible": len(eligible), "fixtureEligibleIDs": ordered(eligible),
            "fixtureExecutedIDs": ordered(fixture), "fixtureExcludedIDs": ordered(all_ids - eligible),
            "fixtureEligibleNotExecutedIDs": ordered(eligible - fixture),
            "fixtureExecutedOutsideEligibleIDs": ordered(fixture - eligible),
            "realExecutedIDs": ordered(real), "realNotExecutedIDs": ordered(all_ids - real),
            "coverageUnit": "unique dataset case IDs attempted, including failures; executed does not mean passed; repeated runs are deduplicated and fixture is never real-model coverage"}


def rag_summary(directory):
    source = read(directory / "summary.json")
    semantic = source.get("semanticEvidence") is True and source.get("provider") == "bailian"
    return {"source": str(directory), **source, "semanticMetricsUsable": semantic,
            "evidenceScope": "real embedding retrieval on a frozen synthetic dataset; inspect latencyScope" if semantic else "engineering pipeline only; fixture or unverified embeddings cannot support semantic Recall/MRR or model-quality claims"}


def resource_summary(paths, cache):
    samples = []
    diagnostics = {"invalidJsonLines": 0, "samplerFailureSamples": 0, "invalidTimestampSamples": 0}
    for path in paths:
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            try:
                sample = json.loads(line)
            except ValueError:
                diagnostics["invalidJsonLines"] += 1
                continue
            if sample.get("samplerReturnCode", sample.get("exitCode", 0)) != 0 or sample.get("errorType"):
                diagnostics["samplerFailureSamples"] += 1
                continue
            try:
                started = datetime.fromisoformat(sample.get("sampleStartedAt", sample.get("atUtc", "")))
                finished = datetime.fromisoformat(sample.get("sampleFinishedAt", sample.get("atUtc", "")))
                if started.tzinfo is None or finished.tzinfo is None or finished < started:
                    raise ValueError("Invalid UTC observation interval")
            except (ValueError, TypeError):
                diagnostics["invalidTimestampSamples"] += 1
                continue
            samples.append((started, finished, sample))
    result = {}
    for mode, row in cache.get("modes", {}).items():
        windows = [(datetime.fromisoformat(start), datetime.fromisoformat(end)) for start, end in row["windows"]]
        values = {}
        for sample_started, sample_finished, sample in samples:
            if not any(start <= sample_started and sample_finished < end for start, end in windows):
                continue
            for container in sample.get("containers", []):
                try:
                    cpu = float(container["CPUPerc"].rstrip("%"))
                except (ValueError, KeyError, TypeError, AttributeError):
                    continue
                if not math.isfinite(cpu) or cpu < 0 or not container.get("Name"):
                    continue
                values.setdefault(container["Name"], []).append(cpu)
        result[mode] = {name: {"samples": len(cpu), "medianCpuPercent": statistics.median(cpu),
                               "maxCpuPercent": max(cpu), "medianLogicalCores": statistics.median(cpu) / 100,
                               "maxObservedLogicalCores": max(cpu) / 100} for name, cpu in values.items()}
    return {"cacheMeasurementWindows": result, "cpuDefinition": "Docker CPU percent; 100% is one logical core, 400% is four cores",
            "samplingScope": "sample median and observed maximum, not continuous peak or percent of CPU quota; bounded sampler intervals must fit a measurement window, host atUtc is completion-time approximation",
            "diagnostics": diagnostics,
            "source": [str(path) for path in paths]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-dirs", nargs="+", type=Path, default=[])
    parser.add_argument("--cold-dir", type=Path)
    parser.add_argument("--trial-dirs", nargs="+", type=Path, default=[])
    parser.add_argument("--recovery-dir", type=Path)
    parser.add_argument("--eval-dirs", nargs="+", type=Path, default=[])
    parser.add_argument("--agent-dataset", type=Path)
    parser.add_argument("--rag-dir", type=Path)
    parser.add_argument("--resources", nargs="+", type=Path, default=[])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = {"createdAtUtc": datetime.now(timezone.utc).isoformat(), "schemaVersion": 1}
    if args.cache_dirs:
        result["cache"] = cache_summary(args.cache_dirs)
    if args.cold_dir:
        result["cacheCold"] = cold_summary(args.cold_dir)
    if args.trial_dirs:
        result["trial"] = trial_summary(args.trial_dirs)
    if args.recovery_dir:
        result["recovery"] = recovery_summary(args.recovery_dir)
    if args.eval_dirs:
        result["agentEvaluation"] = [agent_evaluation_summary(path) for path in args.eval_dirs]
    if args.agent_dataset:
        result["agentCoverage"] = agent_coverage(args.agent_dataset, result.get("agentEvaluation", []))
    if args.rag_dir:
        result["rag"] = rag_summary(args.rag_dir)
    if args.resources:
        result["resources"] = resource_summary(args.resources, result.get("cache", {}))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(args.output), "sections": list(result)}))


if __name__ == "__main__":
    main()
