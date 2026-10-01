"""Sequential isolated trial experiments plus read-only DB timing/reconciliation."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import platform
import subprocess
import sys
import time
import zipfile
from pathlib import Path

from trial_load import load_actors

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def compose(distro, arguments, settings):
    path = subprocess.check_output(["wsl", "--distribution", distro, "--exec", "wslpath", "-u", str(ROOT / "experiments/compose.yml")],
                                   encoding="utf-8", errors="replace").strip()
    command = ["wsl", "--distribution", distro, "--exec", "env"]
    command.extend(f"{name}={value}" for name, value in settings.items())
    command += ["docker", "compose", "-f", path] + arguments
    result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError("Isolated Compose action failed: " + result.stderr[-1000:])
    return result.stdout.strip()


def validate_inputs(args, seed):
    """Fail before recreating any service when the experiment cannot run."""
    rates = [int(value.strip()) for value in args.rates.split(",") if value.strip()]
    if any(rate <= 0 for rate in rates) or (not rates and not args.burst):
        raise ValueError("Supply positive rates, or --burst for a burst-only run")
    if len(set(rates)) != len(rates):
        raise ValueError("Rates must be distinct to avoid overwriting stage directories")
    if args.duration <= 0 or args.repetitions <= 0 or args.delay_ms <= 0 or not 1 <= args.batch_size <= 10000:
        raise ValueError("Duration, repetitions and delay must be positive; batch size must be 1..10000")
    required = max([rate * args.duration for rate in rates] + ([1000] if args.burst else [0]))
    if required > len(seed["actors"]):
        raise ValueError(f"Need {required} actors for the largest stage; supplied {len(seed['actors'])}")
    if any(rate * args.duration > 10000 for rate in rates):
        raise ValueError("A sustained stage needs more than the maximum 10,000 seats; reduce rate or duration")
    if not seed.get("courseId") or not seed.get("schoolId") or not seed.get("workspaceId"):
        raise ValueError("Seed manifest needs courseId, schoolId and workspaceId")
    if not seed.get("ownerToken") and not os.getenv("TRIAL_OWNER_TOKEN"):
        raise ValueError("Seed manifest or TRIAL_OWNER_TOKEN must provide the owner session")
    return rates


def inspect_trial_jar(jar_path):
    """Verify packaged property bindings, rather than trusting source YAML."""
    with zipfile.ZipFile(jar_path) as jar:
        config = jar.read("BOOT-INF/classes/application.yaml").decode("utf-8")
        service = jar.read("BOOT-INF/classes/com/chy/zhikexing/trial/TrialService.class")
        messaging = jar.read("BOOT-INF/classes/com/chy/zhikexing/trial/TrialMessaging.class")
    checks = {
        "yamlBatchEnvironmentBinding": "${TRIAL_RECOVERY_BATCH_SIZE:30}" in config,
        "yamlDelayEnvironmentBinding": "${TRIAL_RECOVERY_DELAY_MS:1000}" in config,
        "serviceBatchProperty": b"app.trial.recovery-batch-size:30" in service,
        "scheduledDelayProperty": b"app.trial.recovery-delay-ms:1000" in messaging,
    }
    if not all(checks.values()):
        raise ValueError("Packaged JAR lacks trial configuration bindings: " + json.dumps(checks))
    return checks


def export_database(directory):
    import pymysql

    info = json.loads((directory / "summary.json").read_text(encoding="utf-8"))
    db = pymysql.connect(host="127.0.0.1", port=28306, user="root",
                         password=os.getenv("BENCHMARK_MYSQL_PASSWORD", "benchmark-only"),
                         database="zhikexing_benchmark", autocommit=True, charset="utf8mb4")
    with db.cursor() as cursor:
        cursor.execute("SET @campaign_id=%s", (info["campaignId"],))
        cursor.execute((HERE / "export_claims.sql").read_text(encoding="utf-8"))
        columns = [column[0] for column in cursor.description]
        with (directory / "claims.tsv").open("w", encoding="utf-8", newline="") as output:
            writer = csv.writer(output, delimiter="\t")
            writer.writerow(columns)
            for row in cursor.fetchall():
                writer.writerow(["" if value is None else str(value) for value in row])
    db.close()
    subprocess.run([sys.executable, str(HERE / "analyze_db.py"), "--claims-tsv", str(directory / "claims.tsv"),
                    "--http-summary", str(directory / "summary.json"), "--output", str(directory / "database-summary.json")], check=True)


def run_one(args, seed, label, rate, duration=None, users=None, capacity=10000):
    output = args.output / label
    command = [sys.executable, str(HERE / "trial_load.py"), "--actors", str(args.actors),
               "--course-id", str(seed["courseId"]), "--school-id", str(seed["schoolId"]),
               "--create-capacity", str(capacity), "--rate", str(rate), "--retry-fraction", "0.2",
               "--label", label, "--output", str(output)]
    command += ["--duration", str(duration)] if duration is not None else ["--users", str(users)]
    print(json.dumps({"starting": label, "logicalUsersPerSecond": rate, "durationSeconds": duration,
                      "capacity": capacity}), flush=True)
    result = subprocess.run(command)
    if (output / "summary.json").exists():
        export_database(output)
    if result.returncode:
        raise RuntimeError("HTTP correctness checks failed; original artifacts preserved: " + label)
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--actors", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--distro", default="Ubuntu-22.04")
    parser.add_argument("--delay-ms", type=int, default=1000)
    parser.add_argument("--batch-size", type=int, default=30)
    parser.add_argument("--rates", default="5,20,40,80")
    parser.add_argument("--duration", type=int, default=30)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--burst", action="store_true")
    args = parser.parse_args()
    # --help and offline input checks do not need optional infrastructure clients.
    # A real run verifies both dependencies before touching services or submitting load.
    import httpx
    import pymysql  # noqa: F401

    if args.output.exists():
        raise SystemExit("Choose a fresh output directory")
    seed = load_actors(args.actors)
    rates = validate_inputs(args, seed)
    jar_path = ROOT / "target/zhikexing-backend-0.0.1-SNAPSHOT.jar"
    jar_bindings = inspect_trial_jar(jar_path)
    settings = {"BENCHMARK_CACHE_MODE": "two-level", "BENCHMARK_TRIAL_BATCH_SIZE": str(args.batch_size),
                "BENCHMARK_TRIAL_DELAY_MS": str(args.delay_ms)}
    running = compose(args.distro, ["ps", "--status", "running", "--services"], settings).splitlines()
    if "backend-second" in running:
        raise RuntimeError("Stop the cache experiment's backend-second before the single-instance trial comparison")
    args.output.mkdir(parents=True)
    compose(args.distro, ["up", "-d", "--force-recreate", "--wait", "backend"], settings)
    actual_values = compose(args.distro, ["exec", "-T", "backend", "printenv",
                            "TRIAL_RECOVERY_BATCH_SIZE", "TRIAL_RECOVERY_DELAY_MS"], settings).splitlines()
    if actual_values != [str(args.batch_size), str(args.delay_ms)]:
        raise RuntimeError("Running container trial settings differ from requested values")
    # Health is readiness evidence only. Record container env and packaged bindings
    # separately; actuator/health cannot prove effective scheduler configuration.
    with httpx.Client(base_url="http://127.0.0.1:28381", timeout=15, trust_env=False) as client:
        health = client.get("/actuator/health")
        health.raise_for_status()
    metadata = {"python": platform.python_version(), "platform": platform.platform(),
                "trialRecoveryDelayMs": args.delay_ms, "trialRecoveryBatchSize": args.batch_size,
                "repetitions": args.repetitions, "durationSeconds": args.duration,
                "includeBurst": args.burst,
                "rates": args.rates, "configChangesFromDefault": args.delay_ms != 1000 or args.batch_size != 30,
                "containerTrialEnvironment": {"batchSize": actual_values[0], "delayMs": actual_values[1]},
                "packagedPropertyBindings": jar_bindings,
                "configurationEvidence": "running container environment plus packaged JAR bindings; health is readiness only",
                "singleBackendInstanceChecked": True,
                "burstSemantics": "1,000 new users at 100/s plus 20% replays, 100 seats; not instantaneous concurrency",
                "jarSha256": hashlib.sha256(jar_path.read_bytes()).hexdigest()}
    metadata["trialLoadSha256"] = hashlib.sha256((HERE / "trial_load.py").read_bytes()).hexdigest()
    (args.output / "manifest.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    if args.burst:
        for repetition in range(1, args.repetitions+1):
            run_one(args, seed, f"burst-repeat-{repetition}", 100, users=1000, capacity=100)
            time.sleep(2)
    for rate in rates:
        for repetition in range(1, args.repetitions+1):
            run_one(args, seed, f"rate-{rate}-repeat-{repetition}", rate, duration=args.duration)
            time.sleep(2)
    print(json.dumps({"completed": str(args.output), "stageCount": len(list(args.output.glob("*/database-summary.json")))}), flush=True)


if __name__ == "__main__":
    main()
