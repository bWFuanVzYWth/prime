"""CPU behavior/byte-layout fixtures for the offline trace reader.

No private captures/descriptors are checked in. Real capture regressions run via
the CLI with caller-supplied files and are documented separately in artifacts.
"""

import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest

try:
    import lz4.block
    from google.protobuf import descriptor_pb2, descriptor_pool, message_factory
except ModuleNotFoundError as error:
    raise unittest.SkipTest("Optional Nsight reader dependencies missing; install scripts/nsight-requirements.txt: " + str(error))

from nsight_diagnostics import FormatError
from nsight_diagnostics.analysis import aggregate, gpu_windows, metric_unit
from nsight_diagnostics.format import PROFILE, descriptor_at, schema_class, sha256, unpack_chunks, varint
from nsight_diagnostics.shaders import (decode_blob, elf_functions, normalize_elf, pc_samples,
                                        range_index, run_tool, source_identity, validate_spirv)


def container(chunks):
    result = bytearray(struct.pack("<4sI", b"WRPV", 10))
    for identifier, flags, blocks in chunks:
        encoded = [lz4.block.compress(block, store_size=False) for block in blocks]
        result += struct.pack("<3Q", (identifier << 32) | (flags << 16) | 0x1234, len(blocks), sum(map(len, blocks)))
        for plain, compressed in zip(blocks, encoded):
            result += struct.pack("<3Q", 0x14321, len(compressed), len(plain)) + compressed
    return bytes(result)


def elf_fixture(value=0x200000000, address=0, symbol_size=16):
    # Standard ELF64 header + text/shstrtab/strtab/symtab sections, one function.
    names = b"\0.text.kernel\0.shstrtab\0.strtab\0.symtab\0"
    strings = b"\0kernel\0"
    text = bytes(range(16))
    symbols = bytes(24) + struct.pack("<IBBHQQ", 1, 0x12, 0, 1, value, symbol_size)
    result = bytearray(64)
    result[:7] = b"\x7fELF\x02\x01\x01"
    pieces = [(text, 1, 0, address, 86 << 24, 0), (names, 3, 0, 0, 0, 0),
              (strings, 3, 0, 0, 0, 0), (symbols, 2, 3, 0, 0, 24)]
    sections = [bytes(64)]
    for name, (data, kind, link, va, info, size) in zip((1, 14, 24, 32), pieces):
        offset = len(result)
        result += data
        sections.append(struct.pack("<IIQQQQIIQQ", name, kind, 0, va, offset, len(data), link, info, 1, size))
    shoff = len(result)
    result += b"".join(sections)
    struct.pack_into("<Q", result, 40, shoff)
    struct.pack_into("<3H", result, 58, 64, 5, 2)
    return bytes(result)


class Message(SimpleNamespace):
    def HasField(self, name):
        return hasattr(self, name)


def fixture_call(name):
    descriptor = descriptor_pb2.FileDescriptorProto(name="fixture-call.proto", package="fixture")
    call = descriptor.message_type.add(name="Call")
    call.field.add(name="functionName", number=1, type=9, label=1)
    pool = descriptor_pool.DescriptorPool()
    pool.Add(descriptor)
    cls = message_factory.GetMessageClass(pool.FindMessageTypeByName("fixture.Call"))
    return cls(functionName=name)


class ContainerTest(unittest.TestCase):
    def test_actual_header_layout_both_flags_multiblock(self):
        # Chunk0 contains a genuine protobuf FileDescriptorProto wire message.
        desc = descriptor_pb2.FileDescriptorProto(name="WarpViz.proto", package="fixture")
        desc.message_type.add(name="Trace")
        wire = desc.SerializeToString()
        data = container([(0, 1, [wire]), (1, 0, [b"LOPDATA", b"\0" * 2048])])
        chunks = unpack_chunks(data)
        self.assertEqual([row["privateFlags"] for row, _ in chunks], [1, 0])
        self.assertEqual(chunks[0][1], wire)
        self.assertEqual(chunks[1][1], b"LOPDATA" + b"\0" * 2048)
        self.assertEqual(chunks[1][0]["sha256"], sha256(chunks[1][1]))

    def test_bad_version_flags_and_duplicate_ids(self):
        data = container([(0, 1, [b"hello"])])
        bad = bytearray(data)
        struct.pack_into("<I", bad, 4, 11)
        for value in (bytes(bad), container([(0, 2, [b"hello"])]),
                      container([(0, 1, [b"a"]), (0, 0, [b"b"])])):
            with self.assertRaises(FormatError):
                unpack_chunks(value)

    def test_truncation_and_corrupt_size_limits(self):
        data = container([(0, 1, [b"hello" * 100])])
        for stop in (0, 7, 15, 31, 40, len(data) - 1):
            with self.assertRaises(FormatError):
                unpack_chunks(data[:stop])
        with self.assertRaises(FormatError):
            unpack_chunks(data, max_chunk_bytes=100)
        bad = bytearray(data)
        struct.pack_into("<Q", bad, 48, 10000)
        with self.assertRaises(FormatError):
            unpack_chunks(bytes(bad))

    def test_descriptor_scanner_and_varint_bounds(self):
        descriptor = descriptor_pb2.FileDescriptorProto(name="WarpViz.proto", package="fixture")
        descriptor.message_type.add(name="Trace")
        wire = descriptor.SerializeToString()
        found, encoded = descriptor_at(wire + b"\0not-protobuf", 0)
        self.assertEqual(found.name, "WarpViz.proto")
        self.assertEqual(encoded, wire)
        for wire in (b"\x80", b"\xff" * 10):
            with self.assertRaises(FormatError):
                varint(wire, 0)

    def test_unreviewed_descriptor_cache_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in PROFILE:
                descriptor = descriptor_pb2.FileDescriptorProto(name=name)
                descriptor.message_type.add(name="FutureUnknownMessage")
                (root / (name.replace("/", "__") + ".pb")).write_bytes(descriptor.SerializeToString())
            with self.assertRaisesRegex(FormatError, "unreviewed descriptor"):
                schema_class(None, root, root)

    def test_cli_failure_writes_integrity_and_rejects_reuse(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, out = root / "bad.ngfx-gputrace", root / "analysis"
            source.write_bytes(b"not-a-trace")
            command = [sys.executable, str(Path(__file__).with_name("nsight-trace.py")), str(source), "--out", str(out)]
            proc = subprocess.run(command, capture_output=True)
            self.assertEqual(proc.returncode, 2)
            summary = json.loads((out / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "failed")
            self.assertTrue(summary["provenance"]["inputUnchanged"])
            before = (out / "summary.json").read_bytes()
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertEqual((out / "summary.json").read_bytes(), before)


class AggregateTest(unittest.TestCase):
    def test_weighted_mean_prorated_sum_and_missing(self):
        rows = [{"start": 0, "end": 10, "load.pct": 10, "bytes.sum": 100},
                {"start": 10, "end": 30, "load.pct": 40, "bytes.sum": 400}]
        result = aggregate(rows, 5, 20, ["load.pct", "bytes.sum", "missing.sum"])
        self.assertEqual(result["coverage"], 1)
        self.assertEqual(result["metrics"]["load.pct"]["durationWeightedMean"], 30)
        self.assertEqual(result["metrics"]["bytes.sum"]["overlapProratedSum"], 250)
        self.assertIsNone(result["metrics"]["missing.sum"]["durationWeightedMean"])
        self.assertIsNone(result["metrics"]["missing.sum"]["overlapProratedSum"])

    def test_gap_nan_and_overlap_fail(self):
        result = aggregate([{"start": 0, "end": 5, "x.pct": float("nan")},
                            {"start": 10, "end": 20, "x.pct": 0}], 0, 20, ["x.pct"])
        self.assertEqual(result["coverage"], .75)
        self.assertEqual(result["metrics"]["x.pct"]["validDurationNs"], 10)
        self.assertEqual(result["metrics"]["x.pct"]["durationWeightedMean"], 0)
        with self.assertRaises(FormatError):
            aggregate([{"start": 0, "end": 10}, {"start": 5, "end": 15}], 0, 20, [])

    def test_ratio_uses_paired_samples_not_equal_disjoint_duration(self):
        warp, thread = "smsp__inst_executed.sum", "smsp__thread_inst_executed.sum"
        rows = [{"start": 0, "end": 10, warp: 100, thread: None},
                {"start": 10, "end": 20, warp: None, thread: 3200}]
        result = aggregate(rows, 0, 20, [warp, thread])
        self.assertIsNone(result["threadInstOver32WarpInst"])
        self.assertEqual(result["threadWarpPairedCoverage"]["validDurationNs"], 0)
        rows.append({"start": 20, "end": 30, warp: 10, thread: 160})
        result = aggregate(rows, 0, 30, [warp, thread])
        self.assertEqual(result["threadInstOver32WarpInst"], .5)
        self.assertEqual(result["threadWarpPairedCoverage"]["validDurationNs"], 10)

    def test_units_do_not_guess_unknown(self):
        self.assertEqual(metric_unit("gpu__time_duration.sum"), "nanoseconds")
        self.assertEqual(metric_unit("rtcore__cycles_executed.sum"), "cycles")
        self.assertEqual(metric_unit("sm__foo.avg.pct_of_peak_sustained_elapsed"), "percent")
        self.assertIn("unknown", metric_unit("new.private.metric"))

    def test_timestamp_call_index_anomaly_does_not_repair_raw_capture(self):
        # Actual wire model: NextCallIndex refers to the next call, len is legal
        # as an end timestamp. Out-of-range indices must not become fake timing.
        stream = Message(Calls=[fixture_call("vkCmdDispatch")], Timestamps=[
            Message(NextCallIndex=240, Values=[Message(PtimerValue=1)]),
            Message(NextCallIndex=0, Values=[Message(PtimerValue=100)]),
            Message(NextCallIndex=1, Values=[Message(PtimerValue=200)])])
        trace = Message(devices=[Message(swapchains=[Message(Frames=[Message(Start=80, End=220)])],
                                        CommandQueues=[Message(CommandStreams=[stream])])])
        result = gpu_windows(trace)
        self.assertEqual(result["dispatches"][0]["durationNs"], 100)
        self.assertEqual(result["streams"][0]["outOfRangeTimestampCallIndices"], [240])


class ShaderTest(unittest.TestCase):
    def test_empty_disassembler_stdout_is_not_success(self):
        with tempfile.TemporaryDirectory() as directory:
            result = run_tool(Path(sys.executable), ["-c", "pass"], Path(directory) / "empty-tool")
            self.assertEqual(result["exitCode"], 0)
            self.assertFalse(result["success"])

    def test_xor_no_mapping_unknown_encoding_and_long_blob(self):
        blob = Message(data=b"abcdefgh", compression=1, obfuscation=2, uncompressedSize=8)
        for mapping in (None, b"tiny"):
            with self.assertRaises(FormatError):
                decode_blob(blob, mapping)
        self.assertEqual(decode_blob(blob, bytes(8)), b"abcdefgh")
        blob.obfuscation = 3
        with self.assertRaises(FormatError):
            decode_blob(blob, bytes(8))

    def test_self_reference_is_not_independent_identity(self):
        row = {"decodedSha256": "plain", "encodedSha256": "encoded"}
        expected = {"plain": [{"path": "frozen.spv"}]}
        reference = {"sourceSha256": "plain", "encodedSha256": "encoded"}
        self.assertIn("NotIndependentlyVerified", source_identity(row, expected, reference)[1])
        row["encodedSha256"] = "independent-other-blob"
        self.assertEqual(source_identity(row, expected, reference)[1], "independentFullShaMatch")

    def test_spirv_header_and_instruction_bounds(self):
        good = struct.pack("<6I", 0x07230203, 0x00010600, 0, 1, 0, 1 << 16)
        validate_spirv(good)
        for data in (good[:-1], good[:20] + bytes(4), good[:20] + struct.pack("<I", 100 << 16)):
            with self.assertRaises(FormatError):
                validate_spirv(data)

    def test_actual_rt_absolute_zero_section_address_normalization(self):
        data = elf_fixture()
        symbols, _ = elf_functions(data)
        symbol = symbols[0]
        self.assertEqual(symbol["gpuVA"], 0x200000000)
        self.assertEqual(symbol["relativeOffset"], 0)
        self.assertEqual(symbol["registersSectionInfo"], 86)
        derived = normalize_elf(data, symbols)
        self.assertEqual(struct.unpack_from("<Q", derived, symbol["symbolValueOffset"])[0], 0)
        begin = symbol["sectionOffset"]
        self.assertEqual(data[begin:begin + 16], derived[begin:begin + 16])
        self.assertNotEqual(sha256(data), sha256(derived))

    def test_standard_absolute_and_relative_address_modes(self):
        data = elf_fixture(value=0x1000, address=0x1000)
        self.assertEqual(elf_functions(data)[0][0]["relativeOffset"], 0)
        self.assertEqual(elf_functions(elf_fixture(value=0))[0][0]["gpuVA"], None)
        with self.assertRaises(FormatError):
            elf_functions(elf_fixture(symbol_size=32))
        with self.assertRaises(FormatError):
            elf_functions(data[:-1])

    def test_pc_window_end_exclusive_and_unmapped(self):
        functions = [{"gpuVA": 100, "bytes": 10}]
        sampling = Message(PcSamplingPerSM=[Message(
            pcSamplesStorage=b"".join(struct.pack("<QQ", pc, 0) for pc in (100, 109, 110, 105)),
            timestampsStorage=struct.pack("<QQQQ", 10, 3, 20, 4))])
        windows = {"frames": [{"id": "frame", "startNs": 10, "endNs": 20, "durationNs": 10}], "dispatches": []}
        row = pc_samples(sampling, functions, windows)[0]
        self.assertEqual(row["totalSamples"], 3)
        self.assertEqual(row["unmappedSamples"], 1)
        self.assertEqual(row["activeFunctions"], [{"functionIndex": 0, "samples": 2}])
        with self.assertRaises(FormatError):
            range_index([{"gpuVA": 100, "bytes": 20}, {"gpuVA": 110, "bytes": 20}])
        sampling.PcSamplingPerSM[0].timestampsStorage = struct.pack("<QQ", 10, 99)
        with self.assertRaises(FormatError):
            pc_samples(sampling, functions, windows)


if __name__ == "__main__":
    unittest.main()
