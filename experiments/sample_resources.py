"""Read-only host sampler for benchmark containers; no Docker socket in load client."""
from __future__ import annotations

import argparse
import json
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--duration", type=int, default=3600)
    parser.add_argument("--interval", type=int, default=5)
    parser.add_argument("--distro", default="Ubuntu-22.04")
    parser.add_argument("--services", default="backend,mysql,redis,load-generator",
                        help="Comma-separated services in zhikexing-benchmark only")
    args = parser.parse_args()
    services = [service.strip() for service in args.services.split(",") if service.strip()]
    allowed = {"backend", "backend-second", "mysql", "redis", "load-generator", "broker", "nameserver", "postgres", "agent-experiment"}
    if not services or not set(services) <= allowed:
        parser.error("Select existing benchmark services only")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    end = time.monotonic() + args.duration
    with args.output.open("w", encoding="utf-8") as output:
        while time.monotonic() < end:
            command = ["wsl", "--distribution", args.distro, "--exec", "docker", "stats", "--no-stream",
                       "--format", "{{json .}}"]
            command += ["zhikexing-benchmark-" + service + "-1" for service in services]
            result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace", timeout=15)
            samples = []
            for line in result.stdout.splitlines():
                try:
                    samples.append(json.loads(line))
                except ValueError:
                    pass
            output.write(json.dumps({"atUtc": datetime.now(timezone.utc).isoformat(), "containers": samples,
                                     "samplerReturnCode": result.returncode}) + "\n")
            output.flush()
            time.sleep(args.interval)


if __name__ == "__main__":
    main()
