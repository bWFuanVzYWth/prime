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
            key = (path.parent.parent.name, row["case"], row["mode"], int(row.get("workset_sections", "8")))
            group = groups.setdefault(key, ([], set()))
            group[0].append(row)
            group[1].add(str(path.parent.parent.parent.relative_to(root)) or ".")
    results = []
    for (version, case, mode, workset), (selected, rounds) in sorted(groups.items()):
        thread_counts = {int(row.get("mc_threads", "1")) for row in selected}
        if len(thread_counts) != 1:
            raise ValueError("Cannot combine different reference thread counts")
        result = dict(version=version, case=case, mode=mode, workset_sections=workset, samples=len(selected), rounds=len(rounds), mc_threads=thread_counts.pop())
        for field in ("mc_ms", "frame_ms", "plan_ms", "pack_ms", "accept_ms", "route_ms", "source_bytes", "java_allocated_bytes", "tint_queries", "tint_callback_ms"):
            if field not in selected[0]:
                continue
            values = [float(row[field]) for row in selected]
            result[field] = dict(p50=statistics.median(values), p95=percentile(values, .95), maximum=max(values))
        if all("compiled_sections" in r and "native_triangles" in r for r in selected):
            seconds = sum(float(r["route_ms"]) for r in selected) / 1000
            result["sections_per_second"] = sum(int(r["compiled_sections"]) for r in selected) / seconds
            result["triangles_per_second"] = sum(int(r["native_triangles"]) for r in selected) / seconds
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
    lines = ["# Section CPU benchmark", "", "Actual MC SectionCompiler vs Java events + FFM plan + source packing + FFM accept/compile/publish. Neutral lighting, controlled baked resources. CPU-only: no FPS or GPU inference. Native uses the configured private worker pool; MC reference uses the same configured worker count (one section per task); its pool is test-only. Historical CSV without mc_threads means a serial reference; without workset_sections means eight edited sections. Cold and warmup samples remain in CSV, outside these statistics. p95 uses nearest rank; no outliers removed. Throughput is total compiled sections / total measured route time, not an average of sample rates.", "", "| MC | Case | Mode | Workset | Rounds | Samples | Route p95 ms | Route max ms | Sections/s | Original p50 ms | Route p50 ms | Pack p50 ms | Accept p50 ms |", "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for r in results:
        throughput = f"{r['sections_per_second']:.1f}" if "sections_per_second" in r else "n/a"
        lines.append(f"| {r['version']} | {r['case']} | {r['mode']} | {r['workset_sections']} | {r['rounds']} | {r['samples']} | {r['route_ms']['p95']:.4f} | {r['route_ms']['maximum']:.4f} | {throughput} | {r['mc_ms']['p50']:.4f} | {r['route_ms']['p50']:.4f} | {r['pack_ms']['p50']:.4f} | {r['accept_ms']['p50']:.4f} |")
    (root / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
