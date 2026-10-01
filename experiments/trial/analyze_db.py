"""Reconcile a read-only mysql TSV export with HTTP experiment outputs."""
from __future__ import annotations

import argparse
import collections
import csv
import json
import math
from pathlib import Path

from trial_load import distribution


def analyze(rows, http, steady_start=5, steady_end=25):
    if not rows:
        raise ValueError("No claim rows exported; check campaign ID and database")
    capacity_values = {int(r["capacity"]) for r in rows}
    remaining_values = {int(r["remaining"]) for r in rows}
    if len(capacity_values) != 1 or len(remaining_values) != 1:
        raise ValueError("export must contain one consistent campaign snapshot")
    campaign_values = {r["campaign_id"] for r in rows}
    if campaign_values != {str(http["campaignId"])}:
        raise ValueError("DB export campaign differs from HTTP summary")
    capacity, remaining = next(iter(capacity_values)), next(iter(remaining_values))
    orders = [r for r in rows if r["order_id"]]
    successful = [r for r in rows if r["status"] == "SUCCEEDED"]
    terminal = [r for r in rows if r["status"] in {"SUCCEEDED", "REJECTED"}]
    start = min(float(r["request_epoch_s"]) for r in rows)
    last_submission = max(float(r["request_epoch_s"]) for r in rows)
    last_order = max((float(r["order_epoch_s"]) for r in orders), default=None)
    last_terminal = max((float(r["terminal_epoch_s"]) for r in terminal), default=None)
    request_ids = [r["request_id"] for r in rows]
    actor_ids = [r["actor_id"] for r in rows]
    order_actors = [r["order_actor_id"] for r in orders]
    order_ids = [r["order_id"] for r in orders]
    keys = [(r["actor_id"], r["source"], r["client_request_id"]) for r in rows]
    terminal_ms = [float(r["terminal_latency_us"]) / 1000 for r in terminal]
    order_ms = [float(r["order_latency_us"]) / 1000 for r in orders]
    counts = collections.Counter(math.floor(float(r["order_epoch_s"]) - start) for r in orders)
    buckets = [{"fromFirstClaimS": i, "orders": counts[i]}
               for i in range(max(counts, default=-1) + 1)]
    observed_duration = last_submission - start
    # Only label a steady interval when the actual submission window covers it.
    steady_valid = 0 <= steady_start < steady_end <= observed_duration
    steady_orders = sum(steady_start <= float(r["order_epoch_s"]) - start < steady_end for r in orders)
    violations = {
        "duplicateClaimRows": len(request_ids) - len(set(request_ids)),
        "duplicateClaimsPerActor": len(actor_ids) - len(set(actor_ids)),
        "duplicateIdempotencyKeys": len(keys) - len(set(keys)),
        "duplicateOrdersPerActor": len(order_actors) - len(set(order_actors)),
        "duplicateOrderIds": len(order_ids) - len(set(order_ids)),
        "succeededWithoutOrder": sum(not r["order_id"] for r in successful),
        "orderWithoutSucceededMatchingClaim": sum(r["status"] != "SUCCEEDED" or
            r["actor_id"] != r["order_actor_id"] or r["campaign_id"] != r["order_campaign_id"] for r in orders),
        "nonzeroAmount": sum(int(r["amount_cent"]) != 0 for r in orders),
        "inFlight": sum(r["status"] not in {"SUCCEEDED", "REJECTED"} for r in rows),
        "compensationPending": sum(int(r["release_pending"]) for r in rows),
        "negativeDurations": sum(v < 0 for v in terminal_ms + order_ms),
    }
    checks = {"stockConserved": capacity == remaining + len(orders),
              "noOversell": 0 <= remaining <= capacity and len(orders) <= capacity,
              "exactExpectedUsers": len(set(request_ids)) == int(http["users"]),
              "httpOrderCountMatches": len(orders) == int(http["confirmedOrders"]),
              "expectedSuccessCount": len(orders) == min(capacity, int(http["users"]))}
    result = {
        "campaignId": str(http["campaignId"]), "requests": len(rows), "orders": len(orders),
        "capacity": capacity, "remaining": remaining,
        "terminalStatusCounts": dict(collections.Counter(r["status"] for r in rows)),
        "databaseOrderLatencyMs": distribution(order_ms),
        "databaseTerminalLatencyMs": distribution(terminal_ms),
        "databaseSubmissionSpanS": observed_duration,
        "databaseTimeToLastOrderS": last_order - start if last_order is not None else None,
        "databaseOrderTpsFromFirstClaim": len(orders) / (last_order - start) if last_order is not None and last_order > start else None,
        "databaseBacklogDrainAfterLastClaimS": max(0, last_terminal - last_submission) if last_terminal is not None else None,
        "steadyWindow": {"fromFirstClaimS": [steady_start, steady_end], "coveredBySubmissionWindow": steady_valid,
                         "orders": steady_orders if steady_valid else None,
                         "ordersPerSecond": steady_orders / (steady_end - steady_start) if steady_valid else None},
        "orderCountsBySecond": buckets, "checks": checks, "violations": violations,
        "passed": all(checks.values()) and not any(violations.values()),
        "notes": ["DB timing excludes HTTP/network cost. created_at is assigned inside the transaction, not at commit acknowledgement.",
                  "Rejected terminal updated_at can move during compensation; successful order uses immutable order.created_at.",
                  "TPS from first claim includes backlog drain; the optional steady interval reports a separately bounded window.",
                  "A DB export alone cannot prove retries returned the same receipt; inspect HTTP summary and requests.jsonl."]}
    return result


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--claims-tsv", required=True)
    p.add_argument("--http-summary", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--steady-start", type=float, default=5)
    p.add_argument("--steady-end", type=float, default=25)
    args = p.parse_args(argv)
    if not 0 <= args.steady_start < args.steady_end:
        p.error("steady window must have 0 <= start < end")
    with Path(args.claims_tsv).open(encoding="utf-8-sig", newline="") as source:
        rows = list(csv.DictReader(source, delimiter="\t"))
    http = json.loads(Path(args.http_summary).read_text(encoding="utf-8-sig"))
    result = analyze(rows, http, args.steady_start, args.steady_end)
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(Path(args.output).resolve()), "passed": result["passed"],
                      "orders": result["orders"]}, ensure_ascii=False))
    return 0 if result["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
