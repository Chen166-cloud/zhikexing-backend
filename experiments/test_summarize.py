"""Small synthetic artifact checks only; no real results, services or load."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from summarize import (agent_coverage, agent_evaluation_summary, cache_summary, rag_summary,
                       recovery_summary, repeated, resource_summary, trial_summary)


def write(path, data):
    path.write_text(json.dumps(data), encoding="utf-8")


def write_jsonl(path, rows):
    path.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")


def evaluation_fixture(path, provider="bailian", digest="frozen", fail=True):
    path.mkdir(exist_ok=True)
    rows = [{"caseId": "a", "provider": provider, "contractPassed": True,
             "graderVersion": "frozen-v1", "taskQualityScore": True if provider == "bailian" else None}]
    if fail:
        # A real exception before grading has no grader, latency or checks.
        rows.append({"caseId": "b", "provider": provider, "contractPassed": False,
                     "taskQualityScore": False if provider == "bailian" else None, "error": "TimeoutError"})
    selected = ["a", "b", "c"] if fail else ["a"]
    write_jsonl(path / "cases.jsonl", rows)
    write(path / "manifest.json", {"provider": provider, "graderVersion": "frozen-v1",
          "databaseOracle": True, "externalModelCalls": provider == "bailian",
          "datasetSha256": digest, "caseIds": selected})
    write(path / "summary.json", {"provider": provider, "graderVersion": "frozen-v1",
          "attempted": len(rows), "engineeringContractPassed": 1,
          "scoredRealModelTasks": len(rows) if provider == "bailian" else 0,
          "ruleTaskPassRate": (0.5 if fail else 1.0) if provider == "bailian" else None,
          "selectedCases": len(selected), "notExecutedCaseIds": ["c"] if fail else [],
          "budgetStoppedReason": "unknown_chat_usage" if fail else None})


class EvidenceStatisticsTest(unittest.TestCase):
    def test_median_of_run_p95_and_range(self):
        self.assertEqual({"n": 3, "median": 15, "min": 10, "max": 120}, repeated([10, 120, 15]))

    def test_cache_refuses_mixed_load_percentiles(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            write(path / "summary.json", {"status": "completed", "command": "throughput", "rates": [100, 400]})
            with self.assertRaisesRegex(ValueError, "one offered rate"):
                cache_summary([path])

    def test_incomplete_trial_repetitions_are_explicit(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            write(path / "manifest.json", {"rates": "80", "repetitions": 3, "includeBurst": True})
            stage = path / "rate-80-repeat-1"
            stage.mkdir()
            write(stage / "summary.json", {"users": 2400, "httpAttempts": 2880,
                "httpChecksPassed": True, "acceptedPrimaryLatencyMs": {"p95": 12}})
            write(stage / "database-summary.json", {"orders": 2400, "passed": True,
                "databaseOrderLatencyMs": {"p95": 500}, "steadyWindow": {"ordersPerSecond": 80},
                "databaseOrderTpsFromFirstClaim": 75, "databaseBacklogDrainAfterLastClaimS": 2,
                "violations": {"duplicateOrderIds": 0}})
            result = trial_summary([path])["runs"][0]
            self.assertFalse(result["protocolComplete"])
            self.assertEqual(5, len(result["missingStages"]))
            row = result["groups"]["rate-80"]
            self.assertFalse(row["repetitionsComplete"])
            self.assertEqual(12, row["acceptanceP95Ms"]["median"])
            self.assertEqual(80, row["steadyOrderTps"]["median"])

    def test_recovery_combines_case_clocks_before_percentile(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            cases = [dict(fault="persisted_queue_kill", passed=True, startupMs=100, recoveryMs=1),
                     dict(fault="persisted_queue_kill", passed=True, startupMs=1, recoveryMs=100),
                     dict(fault="duplicate_commands", passed=True, startupMs=None, recoveryMs=50)]
            (path / "cases.jsonl").write_text("\n".join(json.dumps(case) for case in cases))
            write(path / "summary.json", {"groups": {
                "persisted_queue_kill": {"attempted": 2, "passed": 2},
                "duplicate_commands": {"attempted": 1, "passed": 1}}})
            result = recovery_summary(path)
            self.assertEqual(101, result["groups"]["persisted_queue_kill"]["successfulStartupPlusRecoveryP95Ms"])
            self.assertNotIn("successfulStartupPlusRecoveryP95Ms", result["groups"]["duplicate_commands"])
            self.assertIn("not full", result["clockLimitations"])

    def test_docker_percent_is_core_equivalent_and_interval_is_bounded(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "resources.jsonl"
            container = lambda cpu: [{"Name": "backend", "CPUPerc": cpu}]
            rows = [
                {"atUtc": "2026-10-01T00:00:05+00:00", "containers": container("200%")},
                {"sampleStartedAt": "2026-10-01T00:00:06+00:00", "sampleFinishedAt": "2026-10-01T00:00:07+00:00", "containers": container("400%")},
                {"sampleStartedAt": "2026-09-30T23:59:59+00:00", "sampleFinishedAt": "2026-10-01T00:00:01+00:00", "containers": container("999%")},
                {"atUtc": "2026-10-01T00:00:08+00:00", "samplerReturnCode": 1, "containers": container("999%")},
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows))
            cache = {"modes": {"db": {"windows": [["2026-10-01T00:00:00+00:00", "2026-10-01T00:00:10+00:00"]]}}}
            result = resource_summary([path], cache)
            cpu = result["cacheMeasurementWindows"]["db"]["backend"]
            self.assertEqual(2, cpu["samples"])
            self.assertEqual(300, cpu["medianCpuPercent"])
            self.assertEqual(3, cpu["medianLogicalCores"])
            self.assertEqual(1, result["diagnostics"]["samplerFailureSamples"])


class AgentEvidenceTest(unittest.TestCase):
    def test_early_exception_stays_in_failure_denominator(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            evaluation_fixture(path)
            result = agent_evaluation_summary(path)
            self.assertEqual(2, result["attempted"])
            self.assertEqual(1, result["engineeringContractPassed"])
            self.assertEqual(0.5, result["ruleTaskPassRate"])
            self.assertEqual(["c"], result["notExecutedCaseIds"])
            self.assertEqual(1, result["rawValidation"]["failedCases"])
            self.assertEqual(1, result["rawValidation"]["recordedErrorCases"])
            self.assertEqual(1, result["rawValidation"]["failuresWithoutRowGrader"])
            self.assertFalse(result["protocolComplete"])

    def test_rejects_inflated_counts_and_wrong_unexecuted_ids(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            for key, incorrect in (("attempted", 1), ("engineeringContractPassed", 2),
                                   ("selectedCases", 2), ("scoredRealModelTasks", 1),
                                   ("ruleTaskPassRate", 1.0), ("notExecutedCaseIds", ["b"])):
                with self.subTest(field=key):
                    evaluation_fixture(path)
                    summary = json.loads((path / "summary.json").read_text())
                    summary[key] = incorrect
                    write(path / "summary.json", summary)
                    with self.assertRaises(ValueError):
                        agent_evaluation_summary(path)

    def test_rejects_duplicate_ids_and_provider_or_grader_contamination(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            for key, incorrect in (("caseId", "a"), ("provider", "fixture"),
                                   ("graderVersion", "tuned-v2")):
                with self.subTest(field=key):
                    evaluation_fixture(path)
                    rows = [json.loads(line) for line in (path / "cases.jsonl").read_text().splitlines()]
                    rows[1][key] = incorrect
                    write_jsonl(path / "cases.jsonl", rows)
                    with self.assertRaises(ValueError):
                        agent_evaluation_summary(path)
            evaluation_fixture(path, provider="fixture", fail=False)
            rows = [json.loads((path / "cases.jsonl").read_text())]
            rows[0]["taskQualityScore"] = True
            write_jsonl(path / "cases.jsonl", rows)
            with self.assertRaisesRegex(ValueError, "quality score"):
                agent_evaluation_summary(path)

    def test_intermediate_summary_is_not_a_completed_protocol(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            evaluation_fixture(path, provider="fixture", fail=False)
            summary = json.loads((path / "summary.json").read_text())
            for key in ("selectedCases", "notExecutedCaseIds", "budgetStoppedReason"):
                del summary[key]
            write(path / "summary.json", summary)
            result = agent_evaluation_summary(path)
            self.assertEqual(1, result["selectedCases"])
            self.assertEqual([], result["notExecutedCaseIds"])
            self.assertFalse(result["rawValidation"]["summaryFinalized"])
            self.assertFalse(result["protocolComplete"])

    def test_coverage_deduplicates_attempts_without_turning_fixture_into_real(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            dataset = path / "dataset.jsonl"
            write_jsonl(dataset, [{"id": "a", "fixtureSmokeEligible": True},
                                  {"id": "b", "fixtureSmokeEligible": True},
                                  {"id": "c", "fixtureSmokeEligible": False}])
            digest = hashlib.sha256(dataset.read_bytes()).hexdigest()
            fixture_path, real_path = path / "fixture", path / "real"
            evaluation_fixture(fixture_path, provider="fixture", digest=digest)
            evaluation_fixture(real_path, provider="bailian", digest=digest, fail=False)
            fixture = agent_evaluation_summary(fixture_path)
            real = agent_evaluation_summary(real_path)
            result = agent_coverage(dataset, [fixture, fixture, real])
            self.assertEqual(3, result["totalCases"])
            self.assertEqual(2, result["fixtureEligible"])
            self.assertEqual(["a", "b"], result["fixtureExecutedIDs"])
            self.assertEqual(["c"], result["fixtureExcludedIDs"])
            self.assertEqual(["a"], result["realExecutedIDs"])
            self.assertEqual(["b", "c"], result["realNotExecutedIDs"])
            self.assertEqual([], result["fixtureEligibleNotExecutedIDs"])
            fixture_only = agent_coverage(dataset, [fixture])
            self.assertEqual([], fixture_only["realExecutedIDs"])
            self.assertEqual(["a", "b", "c"], fixture_only["realNotExecutedIDs"])
            empty = agent_coverage(dataset, [])
            self.assertEqual(["a", "b"], empty["fixtureEligibleNotExecutedIDs"])
            real["rawValidation"]["datasetSha256"] = "changed-after-freeze"
            with self.assertRaisesRegex(ValueError, "dataset hash"):
                agent_coverage(dataset, [real])

    def test_fixture_rag_preserves_raw_scores_but_disallows_semantic_claims(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            metrics = {"holdout": {"dense": {"macroRecallAt5": 1.0}}}
            write(path / "summary.json", {"provider": "fixture", "semanticEvidence": False, "metrics": metrics})
            result = rag_summary(path)
            self.assertEqual(metrics, result["metrics"])
            self.assertFalse(result["semanticMetricsUsable"])
            self.assertIn("engineering pipeline only", result["evidenceScope"])


if __name__ == "__main__":
    unittest.main()
