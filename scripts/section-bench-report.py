"""Summarize measured samples without dropping outliers; standard library only."""
import csv
import json
import math
import statistics
import sys
from pathlib import Path


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def summarize(root):
    groups = {}
    for path in sorted(root.glob("**/mc-*/section-oracle/bench_*.csv")):
        with path.open(encoding="utf-8") as stream:
            rows = list(csv.DictReader(stream))
        for row in rows:
            if row["warmup"] != "false":
                continue
            key = (path.parent.parent.name, row["case"], row["mode"])
            group = groups.setdefault(key, ([], set()))
            group[0].append(row)
            group[1].add(str(path.parent.parent.parent.relative_to(root)) or ".")
    results = []
    for (version, case, mode), (selected, rounds) in sorted(groups.items()):
        thread_counts = {int(row.get("mc_threads", "1")) for row in selected}
        if len(thread_counts) != 1:
            raise ValueError("Cannot combine different reference thread counts")
        result = dict(version=version, case=case, mode=mode, samples=len(selected), rounds=len(rounds), mc_threads=thread_counts.pop())
        for field in ("mc_ms", "frame_ms", "plan_ms", "pack_ms", "accept_ms", "route_ms", "source_bytes", "java_allocated_bytes"):
            values = [float(row[field]) for row in selected]
            result[field] = dict(p50=statistics.median(values), p95=percentile(values, .95), maximum=max(values))
        # Both transition directions are visible, even with an odd sample count.
        if mode == "edit":
            result["transitions"] = {}
            for parity, name in [(0, "to_edited"), (1, "to_base")]:
                values = [float(r["route_ms"]) for r in selected if int(r["sample"]) % 2 == parity]
                result["transitions"][name] = dict(samples=len(values), p50=statistics.median(values), p95=percentile(values, .95), maximum=max(values))
        result["gc_count"] = sum(int(r["gc_count"]) for r in selected)
        result["gc_ms"] = sum(int(r["gc_ms"]) for r in selected)
        results.append(result)
    if not results:
        raise ValueError("No benchmark CSV files")
    return results


def main():
    root = Path(sys.argv[1])
    results = summarize(root)
    (root / "summary.json").write_text(json.dumps(results, indent=2), encoding="utf-8", newline="\n")
    lines = ["# Section CPU benchmark", "", "Actual MC SectionCompiler vs Java events + FFM plan + source packing + FFM accept/compile/publish. Neutral lighting, controlled baked resources, 8 edited sections. CPU-only: no FPS or GPU inference. Native uses the configured private worker pool; MC reference uses the same configured worker count (one section per task); its pool is test-only. Historical CSV without mc_threads means a serial reference. Cold and warmup samples remain in CSV, outside these statistics. p95 uses nearest rank; no outliers removed.", "", "| MC | Case | Mode | Rounds | Samples | Original p50 ms | Route p50 ms | Route p95 ms | Route max ms | Pack p50 ms | Accept p50 ms |", "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for r in results:
        lines.append(f"| {r['version']} | {r['case']} | {r['mode']} | {r['rounds']} | {r['samples']} | {r['mc_ms']['p50']:.4f} | {r['route_ms']['p50']:.4f} | {r['route_ms']['p95']:.4f} | {r['route_ms']['maximum']:.4f} | {r['pack_ms']['p50']:.4f} | {r['accept_ms']['p50']:.4f} |")
    (root / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
