"""HTTP cache experiments. Open-loop arrivals; no credentials or response bodies in artifacts."""
from __future__ import annotations

import argparse
import asyncio
from collections import Counter
from datetime import datetime, timedelta, timezone
import hashlib
import json
import math
import os
import platform
from pathlib import Path
import re
import shlex
import socket
import statistics
import time
from typing import Any

import httpx


SCHEMA_VERSION = 1
METRIC_PREFIXES = ("catalog_", "cache_", "process_cpu_usage", "jvm_memory_used_bytes",
                   "jvm_gc_pause_seconds", "hikaricp_connections")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def percentile(values: list[float], percentile_value: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentile_value / 100
    lo, hi = math.floor(position), math.ceil(position)
    return round(ordered[lo] + (ordered[hi] - ordered[lo]) * (position - lo), 3)


def distribution(values: list[float]) -> dict[str, float | None]:
    return {"p50": percentile(values, 50), "p95": percentile(values, 95),
            "p99": percentile(values, 99),
            "max": round(max(values), 3) if values else None,
            "mean": round(statistics.fmean(values), 3) if values else None}


def load_tokens(path: Path) -> list[str]:
    raw = path.read_text(encoding="utf-8-sig").strip()
    tokens = json.loads(raw) if raw.startswith("[") else raw.splitlines()
    if not isinstance(tokens, list) or not tokens or any(not isinstance(t, str) or not t.strip() for t in tokens):
        raise ValueError("tokens-file must contain a JSON array of nonempty token strings or one token per line")
    return [t.strip() for t in tokens]


def digest_response(response: httpx.Response) -> str:
    # Canonical JSON ignores harmless object property ordering. Never persist business content.
    body = json.dumps(response.json(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(body.encode("utf-8")).hexdigest()


def parse_metrics(text: str) -> dict[str, float]:
    result: dict[str, float] = {}
    for line in text.splitlines():
        if not line or line.startswith("#") or not line.startswith(METRIC_PREFIXES):
            continue
        match = re.match(r'^(\w+(?:\{.*\})?)\s+([-+\d.eE]+)(?:\s|$)', line)
        if match:
            result[match.group(1)] = float(match.group(2))
    return result


def metric_total(snapshots: list[dict[str, float]], metric: str) -> float:
    return sum(v for sample in snapshots for key, v in sample.items()
               if key == metric or key.startswith(metric + "{"))


def metric_deltas(before: list[dict[str, float]], after: list[dict[str, float]]) -> dict[str, Any]:
    by_instance = []
    for first, last in zip(before, after):
        by_instance.append({key: value - first.get(key, 0) for key, value in last.items()
                            if key.startswith(("catalog_", "cache_")) and not key.startswith("catalog_cache_configuration")})
    return {"by_instance": by_instance,
            "loader_calls": metric_total(after, "catalog_cache_loads_total") - metric_total(before, "catalog_cache_loads_total"),
            "sql_calls": metric_total(after, "catalog_sql_queries_total") - metric_total(before, "catalog_sql_queries_total")}


async def scrape(client: httpx.AsyncClient, urls: list[str], output: Path, label: str) -> list[dict[str, float]]:
    result = []
    for index, url in enumerate(urls):
        response = await client.get(url)
        response.raise_for_status()
        metrics = parse_metrics(response.text)
        (output / f"{label}.instance-{index}.prom").write_text(response.text, encoding="utf-8")
        result.append(metrics)
    return result


def validate_configuration(snapshots: list[dict[str, float]], mode: str, prefix: str | None = None) -> None:
    for snapshot in snapshots:
        rows = [k for k, v in snapshot.items() if k.startswith("catalog_cache_configuration{") and v == 1]
        if len(rows) != 1 or f'mode="{mode}"' not in rows[0]:
            raise ValueError("Backend cache mode does not match --mode (or configuration metric is missing)")
        if prefix is not None and f'key_prefix="{prefix}"' not in rows[0]:
            raise ValueError("Backend cache key prefix does not match the exact cleanup namespace")


async def preflight(client: httpx.AsyncClient, bases: list[str], paths: list[str], tokens: list[str],
                    expected_status: int) -> dict[str, str]:
    expected = {}
    # Check every target and every route. These are excluded from reported workload.
    for target_index, base in enumerate(bases):
        for path in paths:
            response = await client.get(base + path, headers={"Authorization": tokens[target_index % len(tokens)]})
            if response.status_code != expected_status:
                raise ValueError(f"Preflight failed: target {target_index}, status {response.status_code}; no load started")
            if expected_status == 200:
                digest = digest_response(response)
                if path in expected and digest != expected[path]:
                    raise ValueError("Target instances returned different catalog content")
                expected[path] = digest
    return expected


async def verify_authorization(client: httpx.AsyncClient, bases: list[str], paths: list[str]) -> list[dict[str, Any]]:
    checks = []
    for target_index, base in enumerate(bases):
        for path in paths:
            anonymous = await client.get(base + path)
            invalid = await client.get(base + path, headers={"Authorization": "invalid-benchmark-session"})
            check = {"target": target_index, "path": path, "anonymousStatus": anonymous.status_code,
                     "invalidSessionStatus": invalid.status_code}
            checks.append(check)
            if anonymous.status_code != 401 or invalid.status_code != 401:
                raise ValueError("Catalog authorization preflight did not reject an absent or invalid session")
    return checks


async def request_one(client: httpx.AsyncClient, args: argparse.Namespace, tokens: list[str], expected: dict[str, str],
                      stage: str, index: int, scheduled: float, start: float, duration: float) -> dict[str, Any]:
    target_index = (index // len(args.paths)) % len(args.base_urls)
    path = args.paths[index % len(args.paths)]
    began = time.perf_counter()
    record: dict[str, Any] = {"schemaVersion": SCHEMA_VERSION, "stageId": stage, "index": index,
                             "target": target_index, "path": path, "scheduled_offset_ms": round((scheduled - start) * 1000, 3),
                             "scheduling_lag_ms": round(max(0.0, began - scheduled) * 1000, 3),
                             "sent": True, "dropped": False, "status": None, "error": None, "success": False}
    try:
        response = await client.get(args.base_urls[target_index] + path,
                                    headers={"Authorization": tokens[index % len(tokens)]})
        record["status"] = response.status_code
        record["bytes"] = len(response.content)
        if response.status_code != args.expected_status:
            record["error"] = "unexpected_http_status"
        elif args.expected_status == 200 and digest_response(response) != expected[path]:
            record["error"] = "response_content_changed"
        else:
            record["success"] = True
    except Exception as failure:
        record["error"] = type(failure).__name__
    finished = time.perf_counter()
    record["latency_ms"] = round((finished - began) * 1000, 3)
    record["end_to_end_ms"] = round((finished - scheduled) * 1000, 3)
    record["finished_offset_ms"] = round((finished - start) * 1000, 3)
    record["finished_in_window"] = finished <= start + duration
    return record


def summarize(records: list[dict[str, Any]], duration: float, elapsed: float, late_ms: float) -> dict[str, Any]:
    sent = [r for r in records if r["sent"]]
    success = [r for r in sent if r["success"]]
    completed_in_window = [r for r in sent if r["finished_in_window"]]
    success_in_window = [r for r in success if r["finished_in_window"]]
    return {"offered": len(records), "sent": len(sent), "completed": len(sent), "success": len(success),
            "completed_in_window": len(completed_in_window), "success_in_window": len(success_in_window),
            "dropped": len(records) - len(sent), "late": sum(r["scheduling_lag_ms"] > late_ms for r in records),
            "errors": dict(Counter(r["error"] for r in records if r["error"])),
            "http_statuses": dict(Counter(str(r["status"]) for r in sent)),
            "offered_rps": round(len(records) / duration, 3),
            "achieved_rps": round(len(sent) / duration, 3),
            "completed_rps": round(len(completed_in_window) / duration, 3),
            "success_rps": round(len(success_in_window) / duration, 3),
            "success_rps_including_drain": round(len(success) / elapsed, 3),
            "latency_ms": distribution([r["latency_ms"] for r in sent]),
            "successful_latency_ms": distribution([r["latency_ms"] for r in success]),
            "scheduled_to_finish_ms": distribution([r["end_to_end_ms"] for r in sent]),
            "scheduling_lag_ms": distribution([r["scheduling_lag_ms"] for r in records]),
            "window_seconds": duration, "elapsed_including_drain_seconds": round(elapsed, 3)}


async def open_loop(client: httpx.AsyncClient, args: argparse.Namespace, tokens: list[str], expected: dict[str, str],
                    output_stream: Any, stage: str, rate: float, duration: float) -> dict[str, Any]:
    start = time.perf_counter() + 0.05
    planned_start_utc = datetime.now(timezone.utc) + timedelta(seconds=0.05)
    slots = math.ceil(rate * duration)
    tasks: set[asyncio.Task] = set()
    records: list[dict[str, Any]] = []

    def save(record: dict[str, Any]) -> None:
        records.append(record)
        output_stream.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")

    async def send(index: int, scheduled: float) -> None:
        save(await request_one(client, args, tokens, expected, stage, index, scheduled, start, duration))

    cpu_start = time.process_time()
    max_active = 0
    try:
        for index in range(slots):
            scheduled = start + index / rate
            delay = scheduled - time.perf_counter()
            if delay > 0:
                await asyncio.sleep(delay)
            lag_ms = max(0.0, time.perf_counter() - scheduled) * 1000
            reason = "scheduler_lag" if lag_ms > args.max_lag_ms else "max_inflight" if len(tasks) >= args.max_inflight else None
            if reason:
                save({"schemaVersion": SCHEMA_VERSION, "stageId": stage, "index": index,
                      "scheduled_offset_ms": round((scheduled - start) * 1000, 3), "scheduling_lag_ms": round(lag_ms, 3),
                      "sent": False, "dropped": True, "success": False, "error": reason, "status": None})
                continue
            task = asyncio.create_task(send(index, scheduled))
            tasks.add(task)
            task.add_done_callback(tasks.discard)
            max_active = max(max_active, len(tasks))
        await asyncio.sleep(max(0.0, start + duration - time.perf_counter()))
        if tasks:
            await asyncio.gather(*list(tasks))
    except BaseException:
        pending = list(tasks)
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)
        raise
    elapsed = max(duration, time.perf_counter() - start)
    result = summarize(records, duration, elapsed, args.late_ms)
    result.update({"stageId": stage, "configured_rps": rate, "peak_inflight": max_active,
                   "window_started_at": planned_start_utc.isoformat(),
                   "window_ended_at": (planned_start_utc + timedelta(seconds=duration)).isoformat(),
                   "finished_at": utc_now(),
                   "load_generator_cpu_seconds": round(time.process_time() - cpu_start, 3)})
    output_stream.flush()
    return result


def delete_exact_redis_key(args: argparse.Namespace) -> int:
    """Minimal RESP client: optional AUTH, SELECT, one exact DEL. No SCAN/KEYS/FLUSH."""
    key = args.redis_key_prefix + "v1:" + args.cache_key
    password = os.environ.get(args.redis_password_env) if args.redis_password_env else None
    with socket.create_connection((args.redis_host, args.redis_port), timeout=5) as connection:
        stream = connection.makefile("rb")

        def command(*parts: str) -> Any:
            encoded = [part.encode("utf-8") for part in parts]
            connection.sendall(("*" + str(len(encoded)) + "\r\n").encode("ascii") +
                               b"".join(("$" + str(len(part)) + "\r\n").encode("ascii") + part + b"\r\n" for part in encoded))
            line = stream.readline()
            if line.startswith(b"+"):
                return line[1:-2].decode("utf-8")
            if line.startswith(b":"):
                return int(line[1:-2])
            # Do not echo server messages that could include credentials.
            raise RuntimeError("Redis command failed")

        if args.redis_password_env and not password:
            raise ValueError("Redis password environment variable is unset")
        if password:
            command("AUTH", args.redis_username, password) if args.redis_username else command("AUTH", password)
        command("SELECT", str(args.redis_database))
        return command("DEL", key)


async def cold_burst(client: httpx.AsyncClient, args: argparse.Namespace, tokens: list[str], expected: dict[str, str],
                     output_stream: Any, stage: str, concurrency: int) -> dict[str, Any]:
    gate = asyncio.Event()
    start = 0.0

    async def send(index: int) -> dict[str, Any]:
        await gate.wait()
        return await request_one(client, args, tokens, expected, stage, index, start, start, args.timeout)

    tasks = [asyncio.create_task(send(index)) for index in range(concurrency)]
    await asyncio.sleep(0)
    start = time.perf_counter()
    started_at = utc_now()
    gate.set()
    records = await asyncio.gather(*tasks)
    elapsed = max(0.000001, time.perf_counter() - start)
    for record in records:
        output_stream.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
    output_stream.flush()
    result = summarize(records, elapsed, elapsed, args.late_ms)
    result.update({"stageId": stage, "concurrency": concurrency, "window_started_at": started_at,
                   "window_ended_at": utc_now(), "finished_at": utc_now()})
    return result


async def sample_resources(args: argparse.Namespace, metadata: dict[str, Any], stop: asyncio.Event) -> None:
    if not args.docker_command:
        return
    command = shlex.split(args.docker_command)
    with (args.output_dir / "resources.jsonl").open("w", encoding="utf-8") as output:
        while not stop.is_set():
            sample: dict[str, Any] = {"sampleStartedAt": utc_now(), "stageId": metadata.get("activeStage")}
            try:
                process = await asyncio.create_subprocess_exec(*command, "stats", "--no-stream", "--format", "{{json .}}",
                                                               *args.docker_containers,
                                                               stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                try:
                    stdout, _ = await asyncio.wait_for(process.communicate(), timeout=20)
                except asyncio.TimeoutError:
                    process.kill()
                    await process.communicate()
                    raise
                sample["exitCode"] = process.returncode
                sample["containers"] = [json.loads(line) for line in stdout.decode("utf-8").splitlines() if line.strip()]
            except Exception as failure:
                sample["errorType"] = type(failure).__name__
            sample["sampleFinishedAt"] = utc_now()
            output.write(json.dumps(sample, ensure_ascii=False, separators=(",", ":")) + "\n")
            output.flush()
            try:
                await asyncio.wait_for(stop.wait(), timeout=args.resource_interval)
            except asyncio.TimeoutError:
                pass


async def run(args: argparse.Namespace) -> int:
    tokens = load_tokens(args.tokens_file)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    if (args.output_dir / "requests.jsonl").exists():
        raise ValueError("Output directory already has requests.jsonl; choose a new directory")
    metadata = {"schemaVersion": SCHEMA_VERSION, "experiment": "cache", "command": args.command,
                "startedAt": utc_now(), "mode": args.mode, "baseUrls": args.base_urls,
                "paths": args.paths, "tokenCount": len(tokens), "maxInflight": args.max_inflight,
                "timeoutSeconds": args.timeout, "lateThresholdMs": args.late_ms,
                "maxKeepalive": args.max_keepalive, "startupRates": args.startup_rates,
                "startupSecondsPerRate": args.startup_seconds,
                "dropLagThresholdMs": args.max_lag_ms,
                "python": platform.python_version(), "platform": platform.platform(), "httpx": httpx.__version__,
                "scriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                "dockerContainers": args.docker_containers, "resourceIntervalSeconds": args.resource_interval,
                "externalResourceLog": args.resource_log,
                "rpsDefinition": "offered/achieved count scheduled/sent in the arrival window; success_rps counts successful completions within it",
                "stages": [], "status": "running"}
    if args.machine_metadata:
        metadata["machine"] = json.loads(args.machine_metadata.read_text(encoding="utf-8-sig"))
    if args.command == "throughput":
        metadata.update({"rates": args.rates, "repetitions": args.repetitions,
                         "warmupSeconds": args.warmup_seconds, "stageSeconds": args.stage_seconds})
    else:
        metadata.update({"concurrencies": args.concurrencies, "rounds": args.rounds,
                         "redisKeyPrefix": args.redis_key_prefix, "cacheKey": args.cache_key,
                         "localTtlWaitSeconds": args.local_ttl_wait_seconds})

    def persist() -> None:
        (args.output_dir / "summary.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")

    persist()
    limits = httpx.Limits(max_connections=args.max_inflight, max_keepalive_connections=args.max_keepalive)
    async with httpx.AsyncClient(timeout=args.timeout, limits=limits, trust_env=False) as client:
        resource_stop = asyncio.Event()
        resource_task = asyncio.create_task(sample_resources(args, metadata, resource_stop))
        try:
            metadata["authorizationChecks"] = await verify_authorization(client, args.base_urls, args.paths)
            expected = await preflight(client, args.base_urls, args.paths, tokens, args.expected_status)
            metadata["responseHashes"] = expected
            initial = await scrape(client, args.metrics_urls, args.output_dir, "initial")
            if initial:
                validate_configuration(initial, args.mode, args.redis_key_prefix if args.command == "cold" else None)
            with (args.output_dir / "requests.jsonl").open("w", encoding="utf-8", buffering=1024 * 1024) as output_stream:
                if args.command == "throughput":
                    for startup_rate in args.startup_rates:
                        metadata["activeStage"] = f"startup.rps-{startup_rate:g}"
                        persist()
                        startup = await open_loop(client, args, tokens, expected, output_stream,
                                                  metadata["activeStage"], startup_rate, args.startup_seconds)
                        startup.update({"phase": "startup", "repetition": 0})
                        metadata["stages"].append(startup)
                        persist()
                    for repetition in range(1, args.repetitions + 1):
                        for rate in args.rates:
                            prefix = f"repeat-{repetition}.rps-{rate:g}"
                            if args.warmup_seconds:
                                metadata["activeStage"] = prefix + ".warmup"
                                persist()
                                warmup = await open_loop(client, args, tokens, expected, output_stream,
                                                         prefix + ".warmup", rate, args.warmup_seconds)
                                warmup.update({"phase": "warmup", "repetition": repetition})
                                metadata["stages"].append(warmup)
                                persist()
                            before = await scrape(client, args.metrics_urls, args.output_dir, prefix + ".before")
                            metadata["activeStage"] = prefix + ".measure"
                            persist()
                            stage = await open_loop(client, args, tokens, expected, output_stream,
                                                    prefix + ".measure", rate, args.stage_seconds)
                            after = await scrape(client, args.metrics_urls, args.output_dir, prefix + ".after")
                            stage.update({"phase": "measure", "repetition": repetition,
                                          "metrics": metric_deltas(before, after) if before else None})
                            metadata["stages"].append(stage)
                            persist()
                            print(json.dumps({"stageId": stage["stageId"], "successRps": stage["success_rps"],
                                              "p95Ms": stage["successful_latency_ms"]["p95"],
                                              "dropped": stage["dropped"], "errors": stage["errors"]}), flush=True)
                else:
                    for concurrency in args.concurrencies:
                        for round_number in range(1, args.rounds + 1):
                            stage_id = f"cold-{concurrency}.round-{round_number}"
                            metadata["activeStage"] = stage_id
                            persist()
                            # Wait out each instance's L1 before deleting only this experiment's shared value.
                            await asyncio.sleep(args.local_ttl_wait_seconds)
                            deleted = await asyncio.to_thread(delete_exact_redis_key, args)
                            before = await scrape(client, args.metrics_urls, args.output_dir, stage_id + ".before")
                            stage = await cold_burst(client, args, tokens, expected, output_stream, stage_id, concurrency)
                            after = await scrape(client, args.metrics_urls, args.output_dir, stage_id + ".after")
                            delta = metric_deltas(before, after)
                            passed = stage["success"] == concurrency and delta["loader_calls"] == 1
                            stage.update({"phase": "cold", "round": round_number, "metrics": delta,
                                          "deleted_shared_keys": deleted, "one_load_assertion_passed": passed})
                            metadata["stages"].append(stage)
                            persist()
                            print(json.dumps({"stageId": stage_id, "success": stage["success"],
                                              "loads": delta["loader_calls"], "passed": passed}), flush=True)
            metadata["status"] = "completed"
            if args.command == "cold" and not all(s["one_load_assertion_passed"] for s in metadata["stages"]):
                metadata["status"] = "assertion_failed"
        except BaseException as failure:
            metadata["status"] = "interrupted" if isinstance(failure, (KeyboardInterrupt, asyncio.CancelledError)) else "failed"
            metadata["errorType"] = type(failure).__name__
            raise
        finally:
            resource_stop.set()
            await resource_task
            metadata.pop("activeStage", None)
            metadata["finishedAt"] = utc_now()
            persist()
    return 0 if metadata["status"] == "completed" else 2


def csv(value: str) -> list[str]:
    return [part.strip() for part in value.split(",") if part.strip()]


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["throughput", "cold"])
    parser.add_argument("--base-urls", required=True, type=csv)
    parser.add_argument("--metrics-urls", type=csv, default=[])
    parser.add_argument("--tokens-file", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--mode", choices=["db", "redis", "two-level"], required=True)
    parser.add_argument("--paths", type=csv, default=["/api/v1/courses"])
    parser.add_argument("--expected-status", type=int, default=200)
    parser.add_argument("--max-inflight", type=int, default=512)
    parser.add_argument("--max-keepalive", type=int, default=32)
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--late-ms", type=float, default=5)
    parser.add_argument("--max-lag-ms", type=float, default=250)
    parser.add_argument("--rates", type=lambda s: [float(n) for n in csv(s)], default=[25, 50, 100])
    parser.add_argument("--warmup-seconds", type=float, default=60)
    parser.add_argument("--stage-seconds", type=float, default=180)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--startup-rates", type=lambda s: [float(n) for n in csv(s)], default=[])
    parser.add_argument("--startup-seconds", type=float, default=10)
    parser.add_argument("--concurrencies", type=lambda s: [int(n) for n in csv(s)], default=[100, 300])
    parser.add_argument("--rounds", type=int, default=20)
    parser.add_argument("--local-ttl-wait-seconds", type=float, default=10.5)
    parser.add_argument("--redis-host", default="127.0.0.1")
    parser.add_argument("--redis-port", type=int, default=6379)
    parser.add_argument("--redis-database", type=int, default=0)
    parser.add_argument("--redis-username")
    parser.add_argument("--redis-password-env")
    parser.add_argument("--redis-key-prefix")
    parser.add_argument("--cache-key", help="Exact logical key, e.g. course:100 or courses:home")
    parser.add_argument("--machine-metadata", type=Path)
    parser.add_argument("--docker-command", help='Optional command prefix, e.g. "docker" or "wsl -d Ubuntu-22.04 -- docker"')
    parser.add_argument("--docker-containers", type=csv, default=[])
    parser.add_argument("--resource-interval", type=float, default=5)
    parser.add_argument("--resource-log", help="Optional reference to an independently recorded UTC container resource log")
    args = parser.parse_args()
    args.base_urls = [url.rstrip("/") for url in args.base_urls]
    if not args.base_urls or not args.paths or any(not path.startswith("/") for path in args.paths):
        parser.error("At least one target and one absolute request path are required")
    for url in args.base_urls + args.metrics_urls:
        parsed = httpx.URL(url)
        if parsed.scheme not in ("http", "https") or parsed.username or parsed.password:
            parser.error("URLs must use http(s) and must not embed credentials")
    if args.max_inflight < 1 or args.max_keepalive < 1 or args.max_keepalive > args.max_inflight or args.timeout <= 0 or args.late_ms < 0 or args.max_lag_ms <= 0:
        parser.error("Invalid concurrency, timeout or lag threshold")
    if args.docker_command and (not args.docker_containers or args.resource_interval <= 0):
        parser.error("Resource sampling requires explicit Docker container names and a positive interval")
    if args.command == "throughput":
        if not args.rates or min(args.rates) <= 0 or args.warmup_seconds < 0 or args.stage_seconds <= 0 or args.repetitions < 1:
            parser.error("Rates, stage length and repetitions must be positive")
        if args.startup_seconds <= 0 or any(rate <= 0 for rate in args.startup_rates):
            parser.error("Startup rates and stage duration must be positive")
    else:
        if len(args.base_urls) < 2 or len(args.metrics_urls) != len(args.base_urls) or len(args.paths) != 1:
            parser.error("cold requires two or more instances, one metrics URL each and exactly one path")
        if args.mode == "db" or not args.concurrencies or min(args.concurrencies) < 1 or args.rounds < 1:
            parser.error("cold requires a shared-cache mode and positive rounds/concurrency")
        if max(args.concurrencies) > args.max_inflight:
            parser.error("max-inflight must be at least the largest cold burst")
        if not args.redis_key_prefix or not re.fullmatch(r"(?:benchmark|experiment):cache:[A-Za-z0-9:_-]*", args.redis_key_prefix):
            parser.error("Cleanup prefix must be an explicit benchmark:cache: or experiment:cache: namespace")
        if not args.redis_key_prefix.endswith(":"):
            args.redis_key_prefix += ":"
        if not args.cache_key or not re.fullmatch(r"[A-Za-z0-9:_-]+", args.cache_key):
            parser.error("cache-key must be one explicit logical key without wildcards")
        if args.local_ttl_wait_seconds <= 0:
            parser.error("local-ttl-wait-seconds must exceed the backend configured L1 TTL")
    return args


if __name__ == "__main__":
    raise SystemExit(asyncio.run(run(arguments())))
