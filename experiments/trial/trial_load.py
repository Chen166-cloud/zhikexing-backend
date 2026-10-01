"""HTTP-only trial campaign experiment; Python 3.10+, standard library only.

Use only against the isolated benchmark deployment. No model calls are made.
HTTP 202 means admission, not an order. SQL export provides exact DB timing.
"""
from __future__ import annotations

import argparse
import collections
import concurrent.futures
import datetime as dt
import http.client
import json
import math
import os
from pathlib import Path
import random
import threading
import time
import urllib.parse
import uuid

TERMINAL = {"SUCCEEDED", "REJECTED"}


def percentile(values, quantile):
    """Nearest-rank percentile; no artificial interpolation of observations."""
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * quantile) - 1)], 3)


def distribution(values):
    return {"count": len(values), "p50": percentile(values, .5),
            "p95": percentile(values, .95), "p99": percentile(values, .99),
            "max": round(max(values), 3) if values else None}


class JsonLines:
    def __init__(self, path):
        self.file = Path(path).open("w", encoding="utf-8")
        self.lock = threading.Lock()

    def write(self, row):
        with self.lock:
            self.file.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
            self.file.flush()

    def close(self):
        self.file.close()


class HttpClient:
    """One keep-alive connection per worker. Never silently retry POSTs."""
    def __init__(self, base_url, timeout):
        self.url = urllib.parse.urlsplit(base_url.rstrip("/"))
        if self.url.scheme not in {"http", "https"} or not self.url.hostname:
            raise ValueError("base URL must be http(s)")
        self.timeout = timeout
        self.local = threading.local()
        self.connections = []
        self.lock = threading.Lock()

    def connection(self):
        if getattr(self.local, "connection", None) is None:
            kind = http.client.HTTPSConnection if self.url.scheme == "https" else http.client.HTTPConnection
            self.local.connection = kind(self.url.hostname, self.url.port, timeout=self.timeout)
            with self.lock:
                self.connections.append(self.local.connection)
        return self.local.connection

    def request(self, method, path, token, payload=None):
        started = time.perf_counter()
        try:
            body = json.dumps(payload).encode() if payload is not None else None
            token = token if token.startswith("Bearer ") else "Bearer " + token
            headers = {"Authorization": token, "Accept": "application/json"}
            if body is not None:
                headers["Content-Type"] = "application/json"
            conn = self.connection()
            conn.request(method, self.url.path + path, body, headers)
            response = conn.getresponse()
            raw = response.read()
            try:
                parsed = json.loads(raw) if raw else {}
            except (ValueError, UnicodeError):
                parsed = {"nonJsonBody": True}
            return {"httpStatus": response.status, "body": parsed,
                    "latencyMs": (time.perf_counter() - started) * 1000}
        except (OSError, http.client.HTTPException) as error:
            conn = getattr(self.local, "connection", None)
            if conn is not None:
                conn.close()
                self.local.connection = None
            return {"httpStatus": 0, "body": {}, "errorType": type(error).__name__,
                    "latencyMs": (time.perf_counter() - started) * 1000}

    def close(self):
        for conn in self.connections:
            conn.close()


def must_succeed(result, operation):
    if result["httpStatus"] // 100 != 2:
        raise RuntimeError(f"{operation}: HTTP {result['httpStatus']}; {result['body']}")
    return result["body"]


def load_actors(path):
    data = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if isinstance(data, list):
        data = {"actors": data}
    actors = data.get("actors", [])
    if not actors:
        raise ValueError("actor file must contain actors: [{actorId, token}, ...]")
    for actor in actors:
        actor["actorId"] = str(actor.get("actorId", actor.get("id", "")))
        if not actor["actorId"] or not actor.get("token"):
            raise ValueError("every actor needs actorId and token")
    if len({a["actorId"] for a in actors}) != len(actors):
        raise ValueError("actor IDs must be distinct; one claim per actor per campaign")
    if len({a["token"] for a in actors}) != len(actors):
        raise ValueError("every actor needs its own token")
    return data


class Experiment:
    def __init__(self, args):
        self.args = args
        manifest = load_actors(args.actors)
        self.workspace = args.workspace or manifest.get("workspaceId")
        self.owner = os.getenv(args.owner_token_env, "") or manifest.get("ownerToken")
        if not self.workspace or not self.owner:
            raise ValueError("workspaceId and ownerToken (or CLI/environment override) required")
        self.count = math.floor(args.rate * args.duration) if args.duration else args.users
        if self.count < 1 or self.count > len(manifest["actors"]):
            raise ValueError(f"need {self.count} distinct actors; supplied {len(manifest['actors'])}")
        self.actors = manifest["actors"][:self.count]
        self.retry_indices = set(random.Random(args.seed).sample(
            range(self.count), math.floor(self.count * args.retry_fraction)))
        self.client = HttpClient(args.base_url, args.http_timeout)
        self.prefix = f"/api/v1/workspaces/{urllib.parse.quote(str(self.workspace), safe='')}/trials"
        self.path = Path(args.output)
        self.path.mkdir(parents=True, exist_ok=True)
        if (self.path / "manifest.json").exists():
            raise ValueError("output directory already contains a run; choose a new directory")
        self.requests = JsonLines(self.path / "requests.jsonl")
        self.terminals = JsonLines(self.path / "terminals.jsonl")
        self.snapshots = JsonLines(self.path / "reconciliation.jsonl")
        self.lock = threading.RLock()
        self.stop = threading.Event()
        self.submissions_done = threading.Event()
        self.records = {}
        self.http_rows = []
        self.reconciliation_rows = []
        self.poll_errors = collections.Counter()
        self.poll_count = 0
        self.monitor_error = None
        self.run_id = args.label[:36] + "-" + uuid.uuid4().hex[:12]

    def elapsed(self):
        return time.perf_counter() - self.started

    def setup_campaign(self):
        args = self.args
        if args.create_capacity:
            if not args.course_id or not args.school_id:
                raise ValueError("--create-capacity requires --course-id and --school-id")
            now = dt.datetime.now(dt.timezone.utc)
            body = {"title": "benchmark-" + self.run_id, "courseId": str(args.course_id),
                    "schoolId": str(args.school_id), "capacity": args.create_capacity,
                    "startsAt": (now - dt.timedelta(seconds=5)).isoformat(),
                    "endsAt": (now + dt.timedelta(hours=2)).isoformat()}
            created = must_succeed(self.client.request("POST", self.prefix + "/campaigns", self.owner, body), "create")
            self.campaign = str(created["id"])
            must_succeed(self.client.request("POST", f"{self.prefix}/campaigns/{self.campaign}/publish", self.owner, {}), "publish")
        else:
            self.campaign = args.campaign_id
        self.claim_path = f"{self.prefix}/campaigns/{self.campaign}/claims"
        self.reconcile_path = f"{self.prefix}/campaigns/{self.campaign}/reconciliation"
        initial = must_succeed(self.client.request("GET", self.reconcile_path, self.owner), "initial reconciliation")
        # Fresh campaigns are necessary for exact attribution and safe accounting.
        if any(int(initial.get(key, 0)) for key in ("confirmedOrders", "pending", "reserved", "releasePending")):
            raise ValueError("campaign must have no existing orders or in-flight requests")
        if int(initial["remaining"]) != int(initial["capacity"]):
            raise ValueError("campaign inventory must be untouched")
        self.initial = initial
        configuration = {k: v for k, v in vars(args).items() if k not in {"actors"}}
        (self.path / "manifest.json").write_text(json.dumps({
            "runId": self.run_id, "startedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "campaignId": self.campaign, "workspaceId": str(self.workspace),
            "users": self.count, "retryUsers": len(self.retry_indices), "initial": initial,
            "configuration": configuration,
            "semantics": {"arrivalRate": "logical new users/s; retries are additional HTTP requests",
                          "burst": "rate=0 schedules all logical users immediately; workers bound in-flight HTTP",
                          "terminalLatency": "client-observed upper bound; use exported DB timestamps for exact timing",
                          "sqlValidationRequired": True}}, ensure_ascii=False, indent=2), encoding="utf-8")

    def accept_view(self, index, body, observed):
        record = self.records[index]
        receipt = body.get("requestId")
        if receipt is None:
            return
        receipt = str(receipt)
        record["receipts"].add(receipt)
        record["receipt"] = receipt
        if body.get("status") in TERMINAL and record.get("terminal") is None:
            terminal = {"kind": "terminal", "actorId": record["actor"]["actorId"],
                        "requestId": receipt, "status": body["status"], "reason": body.get("reason"),
                        "orderId": body.get("orderId"), "observedAtS": observed,
                        "observedLatencyMs": (observed - record["firstStarted"]) * 1000,
                        "lastNonterminalObservedAtS": record.get("lastNonterminal")}
            record["terminal"] = terminal
            self.terminals.write(terminal)
        elif body.get("status") not in TERMINAL:
            record["lastNonterminal"] = observed

    def submit_actor(self, index, scheduled):
        actor = self.actors[index]
        client_id = f"{self.run_id}-{index}"
        with self.lock:
            self.records[index] = {"actor": actor, "clientId": client_id, "receipts": set(),
                                   "firstStarted": self.elapsed(), "lastPoll": -1e9, "polling": False}
        for attempt in range(2 if index in self.retry_indices else 1):
            started = self.elapsed()
            result = self.client.request("POST", self.claim_path, actor["token"], {"clientRequestId": client_id})
            ended = self.elapsed()
            body = result["body"] if isinstance(result["body"], dict) else {}
            row = {"kind": "submit", "actorId": actor["actorId"], "clientRequestId": client_id,
                   "attempt": attempt, "scheduledAtS": scheduled, "startedAtS": started,
                   "finishedAtS": ended, "generatorLagMs": max(0, started - scheduled) * 1000 if attempt == 0 else None,
                   "httpStatus": result["httpStatus"], "latencyMs": result["latencyMs"],
                   "requestId": body.get("requestId"), "status": body.get("status"),
                   "reason": body.get("reason"), "errorType": result.get("errorType")}
            self.requests.write(row)
            with self.lock:
                self.http_rows.append(row)
                if result["httpStatus"] == 202:
                    self.accept_view(index, body, ended)
        return index

    def poll(self, index):
        with self.lock:
            record = self.records[index]
            token, receipt = record["actor"]["token"], record["receipt"]
        result = self.client.request("GET", self.prefix + "/claims/" + receipt, token)
        observed = self.elapsed()
        with self.lock:
            record["polling"] = False
            self.poll_count += 1
            if result["httpStatus"] == 200 and isinstance(result["body"], dict):
                self.accept_view(index, result["body"], observed)
            else:
                self.poll_errors[str(result["httpStatus"])] += 1

    def monitor(self):
        try:
            self._monitor()
        except Exception as error:
            self.monitor_error = f"{type(error).__name__}: {error}"
            self.stop.set()

    def _monitor(self):
        next_snapshot = 0
        next_poll = 0
        with concurrent.futures.ThreadPoolExecutor(max_workers=self.args.poll_concurrency) as pool:
            pending = set()
            while not self.stop.is_set():
                now = self.elapsed()
                if now >= next_snapshot:
                    result = self.client.request("GET", self.reconcile_path, self.owner)
                    row = {"kind": "reconciliation", "atS": self.elapsed(),
                           "httpStatus": result["httpStatus"], "latencyMs": result["latencyMs"],
                           "snapshot": result["body"]}
                    self.snapshots.write(row)
                    with self.lock:
                        self.reconciliation_rows.append(row)
                    next_snapshot = self.elapsed() + self.args.reconcile_interval
                for done in tuple(pending):
                    if done.done():
                        done.result()
                        pending.remove(done)
                now = self.elapsed()
                # A rate budget for polling prevents N users * fast loops flooding the database.
                if now >= next_poll and len(pending) < self.args.poll_concurrency:
                    with self.lock:
                        candidates = [(r["lastPoll"], i) for i, r in self.records.items()
                                      if r.get("receipt") and not r.get("terminal") and not r["polling"]
                                      and now - r["lastPoll"] >= self.args.poll_interval]
                        if candidates:
                            _, index = min(candidates)
                            self.records[index]["lastPoll"] = now
                            self.records[index]["polling"] = True
                            pending.add(pool.submit(self.poll, index))
                    next_poll = now + 1 / self.args.poll_rate
                self.stop.wait(.005)

    def complete(self):
        with self.lock:
            missing = [r for r in self.records.values() if r.get("receipt") and not r.get("terminal")]
            snapshots = [r for r in self.reconciliation_rows
                         if r["httpStatus"] == 200 and r["atS"] >= self.submission_end]
            if not snapshots:
                return False
            final = snapshots[-1]["snapshot"]
            return not missing and all(int(final.get(k, -1)) == 0 for k in ("pending", "reserved", "releasePending"))

    def summarize(self):
        rows = self.http_rows
        primary = [r for r in rows if r["attempt"] == 0]
        accepted = [r for r in primary if r["httpStatus"] == 202 and r["requestId"]]
        terminals = [r["terminal"] for r in self.records.values() if r.get("terminal")]
        succeeded = [r for r in terminals if r["status"] == "SUCCEEDED"]
        snapshots = [r for r in self.reconciliation_rows if r["httpStatus"] == 200 and isinstance(r["snapshot"], dict)]
        final = snapshots[-1]["snapshot"] if snapshots else {}
        empty = [r for r in snapshots if r["atS"] >= self.submission_end and
                 all(int(r["snapshot"].get(k, -1)) == 0 for k in ("pending", "reserved", "releasePending"))]
        drained_at = empty[0]["atS"] if empty else None
        # First sampled timestamp at final order count: bounded by reconciliation interval.
        final_orders = int(final.get("confirmedOrders", 0))
        order_count_reached = next((r["atS"] for r in snapshots
                                  if int(r["snapshot"].get("confirmedOrders", 0)) == final_orders), None) if final_orders else None
        known = [r for r in self.records.values() if r.get("receipt")]
        receipt_duplicates = [r["actor"]["actorId"] for r in known if len(r["receipts"]) > 1]
        order_ids = [str(r["orderId"]) for r in succeeded if r.get("orderId")]
        duplicate_order_references = len(order_ids) - len(set(order_ids))
        checks = {"received202ForEveryActor": len(known) == self.count,
                  "everyAttemptReturned202WithReceipt": all(r["httpStatus"] == 202 and r["requestId"] for r in rows),
                  "everyKnownReceiptReachedTerminal": len(terminals) == len(known),
                  "duplicateRetryReceiptIds": receipt_duplicates,
                  "duplicateOrderReferences": duplicate_order_references,
                  "succeededMissingOrderId": sum(not r.get("orderId") for r in succeeded),
                  "databaseInvariantHolds": final.get("databaseInvariantHolds"),
                  "noOversellObserved": 0 <= final_orders <= int(self.initial["capacity"]),
                  "drained": drained_at is not None,
                  "observedSuccessMatchesOrderCount": len(succeeded) == final_orders,
                  "expectedSuccessCount": final_orders == min(self.count, int(self.initial["capacity"])),
                  "databaseDuplicateValidation": "REQUIRED: export_claims.sql + analyze_db.py"}
        warnings = ["Client terminal latency includes polling delay; DB summary uses server persistence timestamps (not a commit timestamp).",
                    "HTTP 202 is admission only. Reconciliation order TPS is a sampled whole-run average, not steady-state TPS."]
        if self.args.rate and percentile([r["generatorLagMs"] for r in primary], .95) > 100:
            warnings.append("Generator P95 scheduling lag >100ms: offered rate not maintained; inspect generator capacity.")
        summary = {"schemaVersion": 1, "runId": self.run_id, "campaignId": self.campaign,
                   "users": self.count, "expectedRetryUsers": len(self.retry_indices),
                   "httpAttempts": len(rows), "primaryHttpStatusCounts": dict(collections.Counter(str(r["httpStatus"]) for r in primary)),
                   "allHttpStatusCounts": dict(collections.Counter(str(r["httpStatus"]) for r in rows)),
                   "primaryHttpLatencyMs": distribution([r["latencyMs"] for r in primary]),
                   "acceptedPrimaryLatencyMs": distribution([r["latencyMs"] for r in accepted]),
                   "allAttemptLatencyMs": distribution([r["latencyMs"] for r in rows]),
                   "generatorLagMs": distribution([r["generatorLagMs"] for r in primary]),
                   "submissionDurationS": self.submission_end,
                   "achievedPrimaryRequestRate": self.count / self.submission_end,
                   "achievedHttpAttemptRate": len(rows) / self.submission_end,
                   "observedTerminalLatencyMs": distribution([r["observedLatencyMs"] for r in terminals]),
                   "observedSuccessLatencyMs": distribution([r["observedLatencyMs"] for r in succeeded]),
                   "terminalStatusCounts": dict(collections.Counter(r["status"] for r in terminals)),
                   "rejectionReasons": dict(collections.Counter(str(r["reason"]) for r in terminals if r["status"] == "REJECTED")),
                   "confirmedOrders": final_orders,
                   "sampledOrderCountReachedAtS": order_count_reached,
                   "sampledOrderTpsFromStart": final_orders / order_count_reached if order_count_reached else None,
                   "backlogMax": max((int(r["snapshot"].get("pending", 0)) + int(r["snapshot"].get("reserved", 0)) for r in snapshots), default=None),
                   "drainedAtS": drained_at,
                   "backlogDrainAfterSubmissionS": max(0, drained_at - self.submission_end) if drained_at is not None else None,
                   "pollRequests": self.poll_count, "pollErrors": dict(self.poll_errors),
                   "reconciliationHttpErrors": sum(r["httpStatus"] != 200 for r in self.reconciliation_rows),
                   "monitorError": self.monitor_error, "finalReconciliation": final,
                   "checks": checks, "warnings": warnings}
        summary["httpChecksPassed"] = (checks["received202ForEveryActor"] and checks["everyAttemptReturned202WithReceipt"]
                and checks["everyKnownReceiptReachedTerminal"] and checks["expectedSuccessCount"]
                and not receipt_duplicates and duplicate_order_references == 0 and checks["succeededMissingOrderId"] == 0
                and checks["databaseInvariantHolds"] is True and checks["noOversellObserved"] and checks["drained"]
                and checks["observedSuccessMatchesOrderCount"] and not self.monitor_error)
        return summary

    def run(self):
        self.setup_campaign()
        self.started = time.perf_counter()
        monitor = threading.Thread(target=self.monitor, name="trial-observer", daemon=True)
        monitor.start()
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=self.args.concurrency) as pool:
                futures = []
                for index in range(self.count):
                    scheduled = index / self.args.rate if self.args.rate else 0
                    delay = scheduled - self.elapsed()
                    if delay > 0:
                        time.sleep(delay)
                    futures.append(pool.submit(self.submit_actor, index, scheduled))
                for future in concurrent.futures.as_completed(futures):
                    future.result()
            self.submission_end = self.elapsed()
            self.submissions_done.set()
            deadline = self.submission_end + self.args.drain_timeout
            while self.elapsed() < deadline and not self.complete() and not self.monitor_error:
                time.sleep(.1)
        finally:
            self.stop.set()
            monitor.join()
            self.client.close()
            for output in (self.requests, self.terminals, self.snapshots):
                output.close()
        summary = self.summarize()
        (self.path / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"output": str(self.path.resolve()), "campaignId": self.campaign,
                          "httpChecksPassed": summary["httpChecksPassed"],
                          "confirmedOrders": summary["confirmedOrders"],
                          "databaseValidationStillRequired": True}, ensure_ascii=False))
        return 0 if summary["httpChecksPassed"] else 2


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--base-url", default="http://127.0.0.1:28380")
    p.add_argument("--actors", required=True)
    p.add_argument("--workspace")
    p.add_argument("--owner-token-env", default="TRIAL_OWNER_TOKEN")
    target = p.add_mutually_exclusive_group(required=True)
    target.add_argument("--campaign-id")
    target.add_argument("--create-capacity", type=int)
    p.add_argument("--course-id")
    p.add_argument("--school-id")
    p.add_argument("--users", type=int, default=1000)
    p.add_argument("--rate", type=float, default=100, help="logical users/s; 0=immediate burst; retries are additional")
    p.add_argument("--duration", type=float, help="use floor(rate*duration) distinct users instead of --users")
    p.add_argument("--concurrency", type=int, default=128)
    p.add_argument("--retry-fraction", type=float, default=.2)
    p.add_argument("--seed", type=int, default=20261001)
    p.add_argument("--label", default="baseline")
    p.add_argument("--output", required=True)
    p.add_argument("--http-timeout", type=float, default=15)
    p.add_argument("--poll-rate", type=float, default=50)
    p.add_argument("--poll-concurrency", type=int, default=32)
    p.add_argument("--poll-interval", type=float, default=1)
    p.add_argument("--reconcile-interval", type=float, default=1)
    p.add_argument("--drain-timeout", type=float, default=240)
    return p


def main(argv=None):
    p = parser()
    args = p.parse_args(argv)
    if args.rate < 0 or (args.duration is not None and (args.rate <= 0 or args.duration <= 0)):
        p.error("rate must be >=0; duration requires positive rate and duration")
    if not 0 <= args.retry_fraction <= 1:
        p.error("retry fraction must be between 0 and 1")
    if any(getattr(args, key) <= 0 for key in ("users", "concurrency", "http_timeout", "poll_rate", "poll_concurrency", "poll_interval", "reconcile_interval", "drain_timeout")):
        p.error("user, concurrency, timeout and polling settings must be positive")
    if args.create_capacity is not None and not 1 <= args.create_capacity <= 10000:
        p.error("capacity must be 1..10000")
    return Experiment(args).run()


if __name__ == "__main__":
    raise SystemExit(main())
