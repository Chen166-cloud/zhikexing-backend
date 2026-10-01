"""Export experiment figures from the sanitized evidence index."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def bars(axis, names, records, title, ylabel, color, show_range=True):
    if not records or any(record is None or record.get("n", 1) < 1 for record in records):
        raise ValueError("Cannot plot a missing measurement as a completed experiment")
    medians = [record["median"] for record in records]
    errors = [[record["median"] - record["min"] for record in records],
              [record["max"] - record["median"] for record in records]]
    axis.bar(names, medians, yerr=errors if show_range else None, color=color, width=.6, capsize=5)
    axis.set_title(title, fontsize=12, loc="left", pad=14)
    axis.set_ylabel(ylabel)
    axis.grid(axis="y", alpha=.18)
    axis.set_axisbelow(True)
    for index, value in enumerate(medians):
        label_height = records[index]["max"] if show_range else value
        axis.annotate(f"{value:.2f}", (index, label_height), xytext=(0, 8), textcoords="offset points", ha="center", fontsize=10)
    axis.margins(y=.25)
    axis.spines[["top", "right"]].set_visible(False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    data = json.loads(args.summary.read_text(encoding="utf-8-sig"))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10, "figure.facecolor": "#f8fafc", "axes.facecolor": "white"})
    colors = ["#94a3b8", "#38bdf8", "#0f766e"]
    if "cache" in data:
        cache = data["cache"]
        modes = [mode for mode in ("db", "redis", "two-level") if mode in cache["modes"]]
        names = [{"db": "MySQL", "redis": "Redis", "two-level": "Caffeine + Redis"}[mode] for mode in modes]
        rows = [cache["modes"][mode] for mode in modes]
        if not cache.get("identicalProtocol") or not rows or any(not row["protocolValid"] for row in rows):
            raise ValueError("Cache comparison requires valid runs with matching protocols")
        fig, axes = plt.subplots(1, 2, figsize=(12, 5), layout="constrained")
        bars(axes[0], names, [row["httpP95Ms"] for row in rows], "Authenticated catalog HTTP P95", "milliseconds", colors[:len(rows)])
        sql = [{"median": row["sqlCallsPerSecond"], "min": row["sqlCallsPerSecond"], "max": row["sqlCallsPerSecond"]} for row in rows]
        bars(axes[1], names, sql, "Actual catalog SQL calls", "calls / second", colors[:len(rows)], show_range=False)
        fig.suptitle("Local cache comparison | " + str(rows[0]["offeredRps"][0]) + " offered requests/s", fontsize=16, fontweight="bold")
        fig.supxlabel(f"{rows[0]['repetitions']} repeats x {rows[0]['stageSeconds']:g} s; {rows[0]['tokenCount']} actor tokens; "
                      f"{len(rows[0]['requestPaths'])} endpoint paths. P95 bars: median of run P95s, whiskers min-max.\n"
                      "SQL: total calls / measurement seconds. Resource limits are in the source manifest; tested load is not maximum capacity.", fontsize=9)
        fig.savefig(args.output_dir / "cache-evidence.png", dpi=160)
        plt.close(fig)
    if "trial" in data:
        runs = data["trial"]["runs"]
        candidates = [run for run in runs if "rate-80" in run["groups"]]
        if candidates:
            if any(not run.get("protocolComplete") for run in candidates):
                raise ValueError("Trial comparison contains missing or unexpected stages; inspect evidence index")
            comparison_keys = ("jarSha256", "trialRecoveryBatchSize", "repetitions", "durationSeconds", "rates",
                               "python", "platform", "trialLoadSha256")
            protocols = [[run["configuration"].get(key) for key in comparison_keys] for run in candidates]
            if any(protocol != protocols[0] for protocol in protocols):
                raise ValueError("Trial comparison changes more than recovery delay")
            names = [str(run["configuration"]["trialRecoveryDelayMs"]) + " ms fixed delay" for run in candidates]
            rows = [run["groups"]["rate-80"] for run in candidates]
            if any(not row["allChecksPassed"] or not row["repetitionsComplete"] for row in rows):
                raise ValueError("Trial chart requires all expected repetitions and correctness checks")
            fig, axes = plt.subplots(1, 2, figsize=(12, 5), layout="constrained")
            records = [{key: value / 1000 if key != "n" else value for key, value in row["orderPersistenceP95Ms"].items()} for row in rows]
            bars(axes[0], names, records, "Claim to order timestamp P95", "seconds", ["#94a3b8", "#0f766e"][:len(rows)])
            bars(axes[1], names, [row["steadyOrderTps"] for row in rows], "Orders in seconds 5-25", "orders / second", ["#94a3b8", "#0f766e"][:len(rows)])
            fig.suptitle("Trial pipeline | 80 new users/s + 20% HTTP replays", fontsize=16, fontweight="bold")
            configuration = candidates[0]["configuration"]
            fig.supxlabel(f"{configuration['durationSeconds']}-second submissions; {rows[0]['repetitions']} repeats; "
                          f"batch {configuration['trialRecoveryBatchSize']}. Bars: median of per-run metrics, whiskers min-max.\n"
                          "Order.created_at is inside the transaction; HTTP 202 is admission, not completed order. The right chart is observed window throughput.", fontsize=9)
            fig.savefig(args.output_dir / "trial-evidence.png", dpi=160)
            plt.close(fig)
    if "recovery" in data:
        if data["recovery"].get("protocolComplete") is False:
            raise ValueError("Recovery run is incomplete; do not plot it as formal evidence")
        groups = data["recovery"]["groups"]
        names = {"persisted_queue_kill": "Persisted queue", "approval_wait_restart": "Approval wait",
                 "committed_response_lost": "Response lost\nafter commit", "duplicate_commands": "HTTP command\nreplay"}
        fig, axis = plt.subplots(figsize=(10, 5), layout="constrained")
        keys = list(groups)
        values = [groups[key]["passRate"] * 100 for key in keys]
        axis.bar([names.get(key, key) for key in keys], values, color="#0f766e", width=.6)
        axis.set_ylim(0, 118)
        axis.set_ylabel("cases passed (%)")
        axis.set_title("Agent durability | real PostgreSQL + Java business writes", fontsize=15, loc="left", pad=16)
        axis.grid(axis="y", alpha=.18)
        axis.set_axisbelow(True)
        axis.spines[["top", "right"]].set_visible(False)
        for index, key in enumerate(keys):
            group = groups[key]
            axis.text(index, values[index] + 3, f"{group['passed']}/{group['attempted']}", ha="center")
        fig.supxlabel("Deterministic fixture model; single runtime worker. Checks include DB duplication and unapproved side effects.\nFinite fault samples; HTTP replay does not claim forced RocketMQ redelivery or production availability.", fontsize=9)
        fig.savefig(args.output_dir / "agent-recovery-evidence.png", dpi=160)
        plt.close(fig)
    if "rag" in data and data["rag"].get("semanticEvidence"):
        rag = data["rag"]
        groups = rag["metrics"]["holdout"]
        keys = [key for key in ("dense", "lexical", "rrf") if key in groups]
        fig, axis = plt.subplots(figsize=(9, 5), layout="constrained")
        values = [groups[key]["macroRecallAt5"] * 100 for key in keys]
        axis.bar(keys, values, color=colors[:len(keys)], width=.6)
        axis.set_ylim(0, 118)
        axis.set_ylabel("macro Recall@5 (%)")
        axis.set_title("Frozen real embeddings | held-out RAG questions", fontsize=15, loc="left", pad=16)
        axis.grid(axis="y", alpha=.18)
        axis.set_axisbelow(True)
        axis.spines[["top", "right"]].set_visible(False)
        for index, value in enumerate(values):
            axis.text(index, value + 3, f"{value:.1f}%", ha="center")
        fig.supxlabel(f"{rag['corpusChunks']} synthetic chunks; {groups[keys[0]]['queries']} held-out questions; {rag['model']}.\nExact pgvector / term-overlap / RRF; initial synthetic benchmark, not production answer accuracy.", fontsize=9)
        fig.savefig(args.output_dir / "rag-evidence.png", dpi=160)
        plt.close(fig)
    print(json.dumps({"figures": [str(path) for path in args.output_dir.glob("*-evidence.png")]}))


if __name__ == "__main__":
    main()
