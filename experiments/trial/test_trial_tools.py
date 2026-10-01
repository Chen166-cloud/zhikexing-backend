"""Offline tests: local HTTP fixture only; no real backend, DB, Redis or LLM."""
import contextlib
import http.server
import io
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest

from analyze_db import analyze
from trial_load import main, percentile


class TrialToolsTest(unittest.TestCase):
    def test_nearest_rank_percentiles_and_empty(self):
        self.assertIsNone(percentile([], .95))
        self.assertEqual(95, percentile(list(range(1, 101)), .95))
        self.assertEqual(99, percentile(list(range(1, 101)), .99))

    def test_http_admission_duplicates_drain_and_token_redaction(self):
        lock = threading.Lock()
        claims = {}
        calls = []

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def send_json(self, status, body):
                raw = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def promote(self):
                for claim in claims.values():
                    if time.monotonic() - claim["born"] > .05:
                        success = int(claim["requestId"]) <= 4
                        claim.update(status="SUCCEEDED" if success else "REJECTED",
                                     reason=None if success else "SOLD_OUT",
                                     orderId="order-" + claim["requestId"] if success else None)

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                key = body["clientRequestId"]
                with lock:
                    calls.append(key)
                    if key not in claims:
                        claims[key] = {"requestId": str(len(claims) + 1), "status": "PENDING",
                                       "campaignId": "test", "born": time.monotonic(), "orderId": None}
                    self.send_json(202, claims[key])

            def do_GET(self):
                with lock:
                    self.promote()
                    if self.path.endswith("/reconciliation"):
                        orders = sum(r["status"] == "SUCCEEDED" for r in claims.values())
                        pending = sum(r["status"] == "PENDING" for r in claims.values())
                        self.send_json(200, {"capacity": 4, "remaining": 4 - orders,
                                            "confirmedOrders": orders, "pending": pending,
                                            "reserved": 0, "releasePending": 0, "databaseInvariantHolds": True})
                    else:
                        receipt = self.path.rsplit("/", 1)[-1]
                        self.send_json(200, next(c for c in claims.values() if c["requestId"] == receipt))

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as temp:
                path = Path(temp)
                actors = path / "actors.json"
                actors.write_text(json.dumps({"workspaceId": "test", "ownerToken": "secret-owner",
                    "actors": [{"actorId": str(i), "token": "secret-token-" + str(i)} for i in range(10)]}))
                output = path / "run"
                with contextlib.redirect_stdout(io.StringIO()):
                    code = main(["--base-url", f"http://127.0.0.1:{server.server_port}",
                                 "--actors", str(actors), "--campaign-id", "test", "--users", "10",
                                 "--rate", "200", "--concurrency", "8", "--poll-rate", "500",
                                 "--poll-interval", ".01", "--reconcile-interval", ".01",
                                 "--drain-timeout", "3", "--output", str(output)])
                summary = json.loads((output / "summary.json").read_text())
                self.assertEqual(0, code, summary)
                self.assertEqual(12, len(calls))
                self.assertEqual(10, len(set(calls)))
                self.assertEqual({"SUCCEEDED": 4, "REJECTED": 6}, summary["terminalStatusCounts"])
                self.assertEqual([], summary["checks"]["duplicateRetryReceiptIds"])
                self.assertIsNotNone(summary["drainedAtS"])
                for artifact in output.iterdir():
                    self.assertNotIn("secret-", artifact.read_text(encoding="utf-8"))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_database_analysis_distinguishes_commits_from_acceptance(self):
        def row(i, status, epoch):
            success = status == "SUCCEEDED"
            return {"request_id": str(i), "actor_id": str(i), "source": "DIRECT", "client_request_id": "key" + str(i),
                    "status": status, "reason": "", "attempts": "1", "release_pending": "0", "capacity": "2",
                    "remaining": "0", "request_epoch_s": str(epoch), "terminal_epoch_s": str(epoch + 2),
                    "order_id": str(i) if success else "", "order_epoch_s": str(epoch + 2) if success else "",
                    "order_latency_us": "2000000" if success else "", "terminal_latency_us": "2000000",
                    "order_actor_id": str(i) if success else "", "order_campaign_id": "test" if success else "",
                    "campaign_id": "test", "amount_cent": "0" if success else ""}
        rows = [row(1, "SUCCEEDED", 100), row(2, "SUCCEEDED", 101), row(3, "REJECTED", 102)]
        http = {"campaignId": "test", "users": 3, "confirmedOrders": 2}
        result = analyze(rows, http)
        self.assertTrue(result["passed"])
        self.assertEqual(2000, result["databaseOrderLatencyMs"]["p95"])
        self.assertAlmostEqual(2 / 3, result["databaseOrderTpsFromFirstClaim"])
        self.assertFalse(result["steadyWindow"]["coveredBySubmissionWindow"])
        rows[1]["order_actor_id"] = "1"
        bad = analyze(rows, http)
        self.assertFalse(bad["passed"])
        self.assertEqual(1, bad["violations"]["duplicateOrdersPerActor"])


if __name__ == "__main__":
    unittest.main()
