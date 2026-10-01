import argparse
import asyncio
import io
import json
import unittest

import httpx

import run_cache


class ReportingTest(unittest.TestCase):
    def test_metrics_keep_labelled_counters_and_delta_counts_sql_separately(self):
        before = run_cache.parse_metrics('catalog_cache_loads_total 1.0\ncatalog_sql_queries_total{query="courses"} 1.0\n')
        after = run_cache.parse_metrics('catalog_cache_loads_total 2.0\ncatalog_sql_queries_total{query="courses"} 2.0\ncatalog_sql_queries_total{query="page_count"} 1.0\n')
        result = run_cache.metric_deltas([before], [after])
        self.assertEqual(1, result["loader_calls"])
        self.assertEqual(2, result["sql_calls"])

    def test_namespace_and_mode_must_match_live_configuration(self):
        snapshot = run_cache.parse_metrics('catalog_cache_configuration{key_prefix="benchmark:cache:",mode="two-level"} 1\n')
        run_cache.validate_configuration([snapshot], "two-level", "benchmark:cache:")
        with self.assertRaises(ValueError):
            run_cache.validate_configuration([snapshot], "redis", "benchmark:cache:")
        with self.assertRaises(ValueError):
            run_cache.validate_configuration([snapshot], "two-level", "benchmark:cache:other:")


class ArrivalTest(unittest.IsolatedAsyncioTestCase):
    async def test_cancellation_drains_request_tasks_before_the_output_is_closed(self):
        started = asyncio.Event()
        cancelled = asyncio.Event()
        async def blocked(request):
            started.set()
            try:
                await asyncio.sleep(30)
                return httpx.Response(200, json={"id": "1"})
            finally:
                cancelled.set()
        args = argparse.Namespace(base_urls=["http://test"], paths=["/api/v1/courses/1"],
                                  max_inflight=1, max_lag_ms=1000, late_ms=5, expected_status=200)
        expected = {args.paths[0]: run_cache.digest_response(httpx.Response(200, json={"id": "1"}))}
        output = io.StringIO()
        async with httpx.AsyncClient(transport=httpx.MockTransport(blocked)) as client:
            pending = asyncio.create_task(run_cache.open_loop(client, args, ["fixture-token"], expected,
                                                             output, "unit", rate=10, duration=30))
            await asyncio.wait_for(started.wait(), timeout=1)
            pending.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await pending
            self.assertTrue(cancelled.is_set())
            output.close()

    async def test_open_arrivals_drop_at_capacity_instead_of_becoming_closed_loop(self):
        async def slow(request):
            await asyncio.sleep(0.035)
            return httpx.Response(200, json={"id": "1"})

        args = argparse.Namespace(base_urls=["http://test"], paths=["/api/v1/courses/1"],
                                  max_inflight=1, max_lag_ms=1000, late_ms=5, expected_status=200)
        sample = httpx.Response(200, json={"id": "1"})
        expected = {args.paths[0]: run_cache.digest_response(sample)}
        output = io.StringIO()
        async with httpx.AsyncClient(transport=httpx.MockTransport(slow)) as client:
            result = await run_cache.open_loop(client, args, ["fixture-token"], expected,
                                              output, "unit", rate=200, duration=0.1)
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(20, result["offered"])
        self.assertEqual(20, len(rows))
        self.assertGreater(result["dropped"], 0)
        self.assertEqual(result["sent"] + result["dropped"], 20)
        self.assertEqual(result["sent"], result["success"])
        self.assertLessEqual(result["success_rps"], result["achieved_rps"])
        self.assertNotIn("fixture-token", output.getvalue())

    async def test_wrong_status_and_changed_response_are_not_successes(self):
        responses = [httpx.Response(500, json={"id": "1"}), httpx.Response(200, json={"id": "2"})]
        async def handler(request):
            return responses.pop(0)

        args = argparse.Namespace(base_urls=["http://test"], paths=["/api/v1/courses/1"], expected_status=200)
        expected = {args.paths[0]: run_cache.digest_response(httpx.Response(200, json={"id": "1"}))}
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            first = await run_cache.request_one(client, args, ["fixture-token"], expected, "unit", 0, 0, 0, 10)
            second = await run_cache.request_one(client, args, ["fixture-token"], expected, "unit", 1, 0, 0, 10)
        self.assertFalse(first["success"])
        self.assertFalse(second["success"])
        self.assertEqual("unexpected_http_status", first["error"])
        self.assertEqual("response_content_changed", second["error"])


if __name__ == "__main__":
    unittest.main()
