"""Behavior checks for the restricted C ABI generator; uses real C/Rust compilers, no GPU."""

from pathlib import Path
import importlib.util
import os
import shutil
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("generate_abi", Path(__file__).with_name("generate-abi.py"))
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)

HEADER = """#include <stdint.h>
#define PRIME_FIXTURE_VERSION 1
#ifdef __cplusplus
extern "C" {
#endif
typedef struct PrimeHeader {
    uint32_t struct_size;
    uint32_t abi_version;
} PrimeHeader;
typedef struct PrimePod {
    uint8_t marker;
    uint32_t amount;
    double values[2];
    const PrimeHeader *source;
} PrimePod;
uint32_t prime_value(void);
int32_t prime_mc_take(uint64_t handle, const PrimePod *pod);
#ifdef __cplusplus
}
#endif
"""

RUST_IMPLEMENTATIONS = """#![allow(dead_code)]
#[path = "abi.rs"]
mod prime_abi;
mod ffi {
    use crate::prime_abi;
    unsafe extern "C" fn prime_value() -> u32 { 7 }
    include!("ffi_exports.rs");
}
mod minecraft_ffi {
    use crate::prime_abi;
    unsafe extern "C" fn prime_mc_take(_: u64, _: *const prime_abi::PrimePod) -> i32 { 0 }
    include!("minecraft_ffi_exports.rs");
}
"""


class AbiGeneratorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.clang = shutil.which(os.environ.get("PRIME_ABI_C_COMPILER", "clang"))
        cls.rustc = shutil.which("rustc")
        if not cls.clang or not cls.rustc:
            raise RuntimeError("ABI generator behavior tests require clang and rustc; missing tools are failures")

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="prime-abi-tests-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.header = self.root / "prime.h"

    def generate(self, source=HEADER):
        self.header.write_text(source, encoding="utf-8", newline="\n")
        return GENERATOR.generate(self.header, self.root)

    def compile_exports(self, implementations=RUST_IMPLEMENTATIONS):
        outputs, _, _ = self.generate()
        for path, source in outputs.items():
            if path.name == "generated.rs":
                (self.root / "abi.rs").write_text(source, encoding="utf-8", newline="\n")
            elif path.name.endswith("_exports.rs"):
                (self.root / path.name).write_text(source, encoding="utf-8", newline="\n")
        source = self.root / "lib.rs"
        source.write_text(implementations, encoding="utf-8", newline="\n")
        return subprocess.run([self.rustc, "--edition", "2024", "--crate-type", "lib",
                               str(source), "-o", str(self.root / "fixture.rlib")],
                              text=True, capture_output=True)

    def test_supported_pod_padding_arrays_pointers_and_both_export_modules_compile(self):
        result = self.compile_exports()
        self.assertEqual(result.returncode, 0, result.stderr)
        _, probe, expected = self.generate()
        self.assertEqual((expected["PrimePod.size"], expected["PrimePod.align"]), (32, 8))
        self.assertEqual((expected["PrimePod.amount"], expected["PrimePod.values"],
                          expected["PrimePod.source"]), (4, 8, 24))
        self.assertEqual(GENERATOR.run_probe(probe, expected, self.header, self.clang), len(expected))

    def test_independent_c_probe_rejects_a_wrong_layout_fact(self):
        _, probe, expected = self.generate()
        expected["PrimePod.values"] += 1
        with self.assertRaisesRegex(ValueError, "C compiler ABI layout mismatch"):
            GENERATOR.run_probe(probe, expected, self.header, self.clang)

    def test_unsupported_returns_and_declaration_syntax_cannot_disappear(self):
        for declaration in ["float prime_bad(void);", "uint64_t *prime_bad(void);",
                            "int32_t prime_bad(uint64_t value) __attribute__((unused));",
                            "int32_t prime_bad(uint64_t value)"]:
            with self.subTest(declaration=declaration), self.assertRaisesRegex(ValueError, "ABI declaration"):
                self.generate(HEADER + declaration)

    def test_unknown_argument_types_and_unsupported_arguments_fail_explicitly(self):
        for arguments in ["Unknown *value", "uint16_t value", "PrimePod value",
                          "uint8_t values[4]", "void (*callback)(void)", "", "..."]:
            with self.subTest(arguments=arguments), self.assertRaisesRegex(ValueError, "ABI (argument|declaration|function)"):
                self.generate(HEADER + f"int32_t prime_bad({arguments});")

    def test_duplicate_public_declarations_fail(self):
        with self.assertRaisesRegex(ValueError, "Duplicate ABI declaration: prime_value"):
            self.generate(HEADER + "uint32_t prime_value(void);")

    def test_unknown_pod_fields_and_empty_arrays_fail(self):
        for field in ["Unknown *value;", "uint8_t bytes[0];"]:
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "C (field|arrays)"):
                self.generate(HEADER + f"typedef struct PrimeBad {{ {field} }} PrimeBad;")

    def test_missing_generic_or_mc_export_fails_actual_rust_compilation(self):
        for implementation, name in [
            ('    unsafe extern "C" fn prime_value() -> u32 { 7 }\n', "prime_value"),
            ('    unsafe extern "C" fn prime_mc_take(_: u64, _: *const prime_abi::PrimePod) -> i32 { 0 }\n', "prime_mc_take"),
        ]:
            with self.subTest(export=name):
                result = self.compile_exports(RUST_IMPLEMENTATIONS.replace(implementation, ""))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("E0425", result.stderr)
                self.assertIn(name, result.stderr)

    def test_wrong_export_signature_fails_actual_rust_compilation(self):
        result = self.compile_exports(RUST_IMPLEMENTATIONS.replace("prime_value() -> u32", "prime_value() -> u64"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("E0308", result.stderr)


if __name__ == "__main__":
    unittest.main()
