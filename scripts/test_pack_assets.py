"""Contract checks for the offline lossless asset packer (no GPU/native build)."""

import copy
import json
import struct
import unittest

from pack_assets import read_safetensors, stable_manifest


def safetensors(header, payload=b"\0" * 8):
    encoded = json.dumps(header).encode("utf-8")
    return struct.pack("<Q", len(encoded)) + encoded + payload


class SafetensorsContractTest(unittest.TestCase):
    def setUp(self):
        self.header = {
            "__metadata__": {"schema": "fixture"},
            "half": {"dtype": "F16", "shape": [2], "data_offsets": [0, 4]},
            "scalar": {"dtype": "F32", "shape": [], "data_offsets": [4, 8]},
        }

    def test_original_bytes_scalar_empty_and_unordered_tensors(self):
        header = {"scalar": self.header["scalar"], "half": self.header["half"],
                  "empty": {"dtype": "F32", "shape": [0, 3], "data_offsets": [4, 4]}}
        payload = bytes(range(8))
        decoded_header, decoded_payload = read_safetensors(safetensors(header, payload))
        self.assertEqual(header, decoded_header)
        self.assertEqual(payload, decoded_payload)

    def test_duplicate_keys_at_every_header_depth(self):
        entries = (
            '{"a":{"dtype":"F32","shape":[],"data_offsets":[0,4]},'
            '"a":{"dtype":"F32","shape":[],"data_offsets":[4,8]}}',
            '{"a":{"dtype":"F32","dtype":"F16","shape":[4],"data_offsets":[0,8]}}',
            '{"__metadata__":{"schema":"a","schema":"b"}}',
        )
        for text in entries:
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, "Duplicate"):
                encoded = text.encode("utf-8")
                read_safetensors(struct.pack("<Q", len(encoded)) + encoded + b"\0" * 8)

    def test_invalid_dtype_shape_offsets_and_length(self):
        invalid = (
            ("dtype", "U32"), ("dtype", []),
            ("shape", [True, 2]), ("shape", [1.0, 2]), ("shape", [-1, 2]),
            ("shape", "2"), ("shape", [3]),
            ("data_offsets", [False, 4]), ("data_offsets", [0.0, 4]),
            ("data_offsets", [-1, 3]), ("data_offsets", [4, 0]),
            ("data_offsets", [0, 12]), ("data_offsets", [0]),
        )
        for field, value in invalid:
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                header = copy.deepcopy(self.header)
                header["half"][field] = value
                read_safetensors(safetensors(header))

    def test_payload_gaps_overlaps_and_trailing_bytes(self):
        invalid = (([4, 8], [8, 12], 12), ([0, 4], [8, 12], 12),
                   ([0, 4], [0, 4], 8), ([0, 4], [4, 8], 12))
        for half, scalar, length in invalid:
            with self.subTest(half=half, scalar=scalar, length=length), self.assertRaises(ValueError):
                header = copy.deepcopy(self.header)
                header["half"]["data_offsets"] = half
                header["scalar"]["data_offsets"] = scalar
                read_safetensors(safetensors(header, b"\0" * length))

    def test_header_metadata_and_truncation(self):
        for header in ([], {"__metadata__": []}, {"__metadata__": {"schema": 1}},
                       {"tensor": []}):
            with self.subTest(header=header), self.assertRaises(ValueError):
                read_safetensors(safetensors(header))
        for data in (b"", struct.pack("<Q", 3) + b"{}"):
            with self.subTest(data=data), self.assertRaises(ValueError):
                read_safetensors(data)


class ManifestContractTest(unittest.TestCase):
    def test_timing_only_in_report_and_unrelated_records_preserved(self):
        source = {"version": 1, "assets": {
            "atmosphere/example.ktx2": {"container": "KTX2", "levels": [
                {"uncompressedSha256": "abc", "encodeSeconds": 3.5, "zstd19Bytes": 10}]},
            "atmosphere/medium.safetensors.zst": {"container": "Zstd", "sha256": "def"},
        }}
        original = copy.deepcopy(source)
        lock = stable_manifest(source)
        self.assertEqual(source, original)
        self.assertEqual(lock["assets"]["atmosphere/example.ktx2"]["levels"],
                         [{"uncompressedSha256": "abc", "zstd19Bytes": 10}])
        self.assertEqual(lock["assets"]["atmosphere/medium.safetensors.zst"],
                         source["assets"]["atmosphere/medium.safetensors.zst"])


if __name__ == "__main__":
    unittest.main()
