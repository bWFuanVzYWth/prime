"""Behavior checks for tail latency and throughput aggregation, without game/native dependencies."""
import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("section_report", Path(__file__).with_name("section-bench-report.py"))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class ReportTest(unittest.TestCase):
    def write(self, root, round_name, times, workset=8, threads=8, legacy=False):
        target = root / round_name / "mc-26.2" / "section-oracle" / "bench_dense.csv"
        target.parent.mkdir(parents=True, exist_ok=True)
        rows = []
        for sample, duration in enumerate([9999, *times]):
            row = dict(case="bench_dense", mode="edit", sample=sample, warmup=str(sample == 0).lower(),
                       mc_threads=threads, route_ms=duration, gc_count=0, gc_ms=0)
            if not legacy:
                row.update(workset_sections=workset, compiled_sections=workset, native_triangles=workset * 12)
            rows.append(row)
        with target.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=rows[0])
            writer.writeheader()
            writer.writerows(rows)

    def test_p95_keeps_outlier_and_throughput_uses_total_time(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, "round-1", [1, 2, 3, 4, 100])
            result, = report.summarize(root)
            self.assertEqual(result["samples"], 5)
            self.assertEqual(result["route_ms"], dict(p50=3, p95=100, maximum=100))
            self.assertAlmostEqual(result["sections_per_second"], 40 / .110)
            self.assertAlmostEqual(result["triangles_per_second"], 480 / .110)
            self.assertEqual(result["transitions"]["to_base"]["p95"], 100)

    def test_different_worksets_stay_separate_and_rounds_combine(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, "round-1", [1, 2, 3])
            self.write(root, "round-2", [4, 5, 6])
            self.write(root, "large", [10, 20, 30], workset=128)
            results = {r["workset_sections"]: r for r in report.summarize(root)}
            self.assertEqual(set(results), {8, 128})
            self.assertEqual((results[8]["samples"], results[8]["rounds"]), (6, 2))
            self.assertEqual(results[128]["route_ms"]["p95"], 30)

    def test_historical_counts_are_unknown_and_thread_counts_cannot_mix(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, "old", [1, 2, 3], legacy=True)
            self.write(root, "new", [4, 5, 6])
            result, = report.summarize(root)
            self.assertNotIn("sections_per_second", result)
            self.write(root, "serial", [1, 2, 3], threads=1)
            with self.assertRaises(ValueError):
                report.summarize(root)


if __name__ == "__main__":
    unittest.main()
