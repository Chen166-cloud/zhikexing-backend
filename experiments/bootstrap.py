"""Seed only the explicitly named isolated benchmark DB and issue fixture sessions.

Passwords and tokens are saved under ignored results; they are never printed.
The session creation executes the same Lua script as the application.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import socket
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path

import httpx
import pymysql

ROOT = Path(__file__).resolve().parents[1]


class Redis:
    def __init__(self, host: str, port: int):
        self.sock = socket.create_connection((host, port), timeout=10)
        self.file = self.sock.makefile("rb")

    def command(self, *args):
        values = [str(x).encode() for x in args]
        self.sock.sendall(b"*" + str(len(values)).encode() + b"\r\n" + b"".join(
            b"$" + str(len(x)).encode() + b"\r\n" + x + b"\r\n" for x in values))
        return self.read()

    def read(self):
        line = self.file.readline()
        kind, value = line[:1], line[1:-2]
        if kind == b"+":
            return value.decode()
        if kind == b"-":
            raise RuntimeError(value.decode())
        if kind == b":":
            return int(value)
        if kind == b"$":
            length = int(value)
            if length < 0:
                return None
            result = self.file.read(length)
            self.file.read(2)
            return result.decode()
        if kind == b"*":
            return [self.read() for _ in range(int(value))]
        raise RuntimeError("Invalid Redis response")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--actors", type=int, default=5000)
    parser.add_argument("--courses", type=int, default=10000)
    parser.add_argument("--database", default="zhikexing_benchmark")
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", type=int, default=28306)
    parser.add_argument("--redis-host", default="127.0.0.1")
    parser.add_argument("--redis-port", type=int, default=28379)
    parser.add_argument("--base-url", default="http://127.0.0.1:28380")
    args = parser.parse_args()
    if args.database != "zhikexing_benchmark":
        raise SystemExit("This seed may only access zhikexing_benchmark")
    args.output.mkdir(parents=True, exist_ok=True)
    db = pymysql.connect(host=args.mysql_host, port=args.mysql_port, user="root",
                         password=os.getenv("BENCHMARK_MYSQL_PASSWORD", "benchmark-only"),
                         database=args.database, charset="utf8mb4", autocommit=False)
    user_start, course_start, member_start = 300331822288990200, 220331822288990210, 400331822288990200
    workspace = "benchmark-2026-10-01"
    with db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")
        if cursor.fetchone()[0] < 7:
            raise RuntimeError("Run application Flyway migrations before seeding")
        cursor.executemany("INSERT IGNORE INTO user_info(id,user_name,password,nick_name) VALUES(%s,%s,%s,%s)",
            [(user_start+i, f"benchmark-{i:05}", "experiment-token-only-no-password-login", f"实验用户{i}")
             for i in range(args.actors)])
        cursor.execute("INSERT IGNORE INTO agent_workspace(id,name,owner_id) VALUES(%s,%s,%s)",
                       (workspace, "隔离性能实验空间", user_start))
        cursor.executemany("INSERT IGNORE INTO agent_workspace_member(id,workspace_id,user_id,role) VALUES(%s,%s,%s,%s)",
            [(member_start+i, workspace, user_start+i, "OWNER" if i == 0 else "MEMBER") for i in range(args.actors)])
        names = ["Java后端研发实战", "Python Agent开发", "Redis高并发系统", "MySQL性能优化", "LangGraph任务恢复"]
        cursor.executemany("INSERT IGNORE INTO course(id,name,edu,type,price,duration) VALUES(%s,%s,%s,%s,%s,%s)",
            [(course_start+i, names[i] if i < len(names) else f"合成课程{i:05}", i % 5,
              "后端" if i % 2 == 0 else "Agent", 3999+i, 30+i % 90) for i in range(args.courses)])
        cursor.executemany("INSERT IGNORE INTO school(id,name,city) VALUES(%s,%s,%s)",
            [(220331822288990212, "线上实验校区", "线上"), (220331822288990213, "上海实验校区", "上海")])
    db.commit()
    db.close()
    redis = Redis(args.redis_host, args.redis_port)
    lua = (ROOT / "src/main/resources/lua/auth-session-create.lua").read_text(encoding="utf-8")
    actors = []
    for i in range(args.actors):
        token = uuid.uuid4().hex
        actor_id = str(user_start+i)
        redis.command("EVAL", lua, 2, "login:v2:sessions:"+actor_id, "login:v2:token:"+token,
                      token, actor_id, f"benchmark-{i:05}", f"实验用户{i}", 86400000, 3, "login:v2:token:")
        actors.append({"actorId": actor_id, "token": token})
    data = {"workspaceId": workspace, "ownerToken": actors[0]["token"], "actors": actors,
            "courseId": str(course_start), "schoolId": "220331822288990212", "courseCount": args.courses}
    (args.output / "actors.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    (args.output / "tokens.json").write_text(json.dumps([x["token"] for x in actors]), encoding="utf-8")
    (args.output / "bootstrap.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    with httpx.Client(base_url=args.base_url, timeout=15, headers={"Authorization": actors[0]["token"]}) as client:
        response = client.get("/api/v1/courses")
        response.raise_for_status()
        payload = response.json()
        assert payload["total"] == args.courses and len(payload["items"]) == 12
        response = client.get(f"/api/v1/courses/{course_start}")
        response.raise_for_status()
        assert response.json()["id"] == str(course_start)
    metadata = {"createdAtUtc": datetime.now(timezone.utc).isoformat(), "actors": args.actors,
                "courses": args.courses, "python": platform.python_version(), "platform": platform.platform(),
                "backendCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "seedMethod": "isolated SQL fixtures plus production session-create Lua; no login loop",
                "credentialsExcluded": True,
                "bootstrapHash": hashlib.sha256(json.dumps({"actors":args.actors,"courses":args.courses}).encode()).hexdigest()}
    (args.output / "manifest.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(json.dumps({"seededActors": args.actors, "seededCourses": args.courses,
                      "httpSmoke": "passed", "output": str(args.output)}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
