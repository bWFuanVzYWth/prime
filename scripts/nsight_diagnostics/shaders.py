"""Stored shader objects, standard ELF addresses, and timestamp-scoped PC samples.

Private RT liveState/callstack formats are deliberately not interpreted.
"""

import bisect
from collections import Counter
import re
import struct
import subprocess
from pathlib import Path

import lz4.block

from . import FormatError
from .format import brief, require, sha256


def unpack_blob(blob, limit=512 * 1024**2):
    if blob.uncompressedSize > limit:
        raise FormatError("shader blob exceeds size limit")
    if blob.compression == 1:
        data = blob.data
    elif blob.compression == 2:
        try:
            data = lz4.block.decompress(blob.data, uncompressed_size=blob.uncompressedSize)
        except (ValueError, lz4.block.LZ4BlockError) as error:
            raise FormatError(f"invalid compressed shader blob: {error}") from error
    else:
        raise FormatError(f"unsupported shader compression {blob.compression}")
    if len(data) != blob.uncompressedSize:
        raise FormatError("shader blob uncompressed size mismatch")
    return data


def decode_blob(blob, mapping):
    data = unpack_blob(blob)
    if blob.obfuscation == 1:
        return data
    if blob.obfuscation != 2:
        raise FormatError(f"unsupported shader obfuscation {blob.obfuscation}")
    if mapping is None:
        raise FormatError("OBFUSCATION_SIMPLE needs an explicit known-plaintext reference")
    if len(data) > len(mapping):
        raise FormatError(f"shader blob {len(data)} B exceeds validated XOR mapping {len(mapping)} B")
    return bytes(value ^ mapping[i] for i, value in enumerate(data))


def known_mapping(trace, device, blob_index, plain):
    if device >= len(trace.devices) or device < 0:
        raise FormatError("reference device index out of bounds")
    blobs = trace.devices[device].ShaderProfilerReport.blobTable.blobs
    if blob_index < 0 or blob_index >= len(blobs):
        raise FormatError("reference blob index out of bounds")
    blob = blobs[blob_index]
    if blob.obfuscation != 2:
        raise FormatError("reference blob must have OBFUSCATION_SIMPLE")
    encoded = unpack_blob(blob)
    validate_spirv(plain)
    if len(encoded) != len(plain):
        raise FormatError("known-plaintext reference size mismatch; never truncate a mapping")
    return bytes(a ^ b for a, b in zip(encoded, plain))


def validate_spirv(data):
    if len(data) < 20 or len(data) % 4 or data[:4] != b"\x03\x02\x23\x07":
        raise FormatError("not a little-endian SPIR-V module")
    words = struct.unpack_from("<5I", data)
    if words[1] >> 16 != 1 or words[4] != 0:
        raise FormatError("unsupported SPIR-V header")
    cursor = 20
    while cursor < len(data):
        size = struct.unpack_from("<I", data, cursor)[0] >> 16
        if size == 0:
            raise FormatError("zero-word SPIR-V instruction")
        require(data, cursor, size * 4, "SPIR-V instruction")
        cursor += size * 4


def elf_functions(data):
    require(data, 0, 64, "ELF64 header")
    if data[:7] != b"\x7fELF\x02\x01\x01":
        raise FormatError("only ELF64 little-endian version 1 is supported")
    shoff = struct.unpack_from("<Q", data, 40)[0]
    entsize, count, names_index = struct.unpack_from("<3H", data, 58)
    if entsize != 64 or not count or names_index >= count:
        raise FormatError("unsupported ELF section table (including extended numbering)")
    require(data, shoff, entsize * count, "ELF sections")
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * entsize) for i in range(count)]
    for section in sections:
        if section[1] != 8:  # SHT_NOBITS has no file data.
            require(data, section[4], section[5], "ELF section data")

    def strings(section):
        return data[section[4]:section[4] + section[5]]

    def string(table, offset):
        if offset >= len(table):
            raise FormatError("ELF string offset out of bounds")
        end = table.find(b"\0", offset)
        if end < 0:
            raise FormatError("unterminated ELF string")
        return table[offset:end].decode("utf-8", errors="replace")

    section_names = [string(strings(sections[names_index]), sec[0]) for sec in sections]
    symbols = []
    for section in sections:
        if section[1] != 2:  # SHT_SYMTAB
            continue
        if section[6] >= count or section[9] != 24 or section[5] % 24:
            raise FormatError("invalid ELF symbol table")
        table = strings(sections[section[6]])
        for offset in range(section[4], section[4] + section[5], 24):
            name, info, _, index, value, size = struct.unpack_from("<IBBHQQ", data, offset)
            if info & 15 != 2 or index == 0:
                continue
            if index >= count:
                raise FormatError("ELF function references invalid section")
            if not section_names[index].startswith(".text."):
                continue
            code = sections[index]
            if not size or size > code[5]:
                raise FormatError("ELF function has invalid size")
            if code[3] and code[3] <= value and value + size <= code[3] + code[5]:
                relative, va, mode = value - code[3], value, "absoluteVA"
            elif value + size <= code[5]:
                relative, va, mode = value, code[3] + value if code[3] else None, "sectionRelative"
            elif code[3] == 0 and value >= code[5] and size == code[5]:
                # Observed capture: whole-function text section, sh_addr=0,
                # st_value=runtime GPU VA. Only this proven layout is normalized.
                relative, va, mode = 0, value, "captureAbsoluteVA/fullTextSection"
            else:
                raise FormatError("unsupported ELF function address layout")
            symbols.append({"symbol": string(table, name), "section": section_names[index],
                            "sectionIndex": index, "symbolValueOffset": offset + 8,
                            "sectionOffset": code[4], "sectionBytes": code[5],
                            "sectionSha256": sha256(strings(code)), "addressMode": mode,
                            "relativeOffset": relative, "gpuVA": va, "bytes": size,
                            "registersSectionInfo": code[7] >> 24})
    for symbol in symbols:
        if symbol["addressMode"] == "captureAbsoluteVA/fullTextSection" and sum(
                other["sectionIndex"] == symbol["sectionIndex"] for other in symbols) != 1:
            raise FormatError("ambiguous absolute-VA text section with multiple functions")
    private = [{"name": name, "bytes": sec[5]} for name, sec in zip(section_names, sections)
               if name.startswith(".rt.info.")]
    return symbols, private


def normalize_elf(data, symbols):
    normalized = bytearray(data)
    for symbol in symbols:
        struct.pack_into("<Q", normalized, symbol["symbolValueOffset"], symbol["relativeOffset"])
    for symbol in symbols:
        begin, end = symbol["sectionOffset"], symbol["sectionOffset"] + symbol["sectionBytes"]
        if data[begin:end] != normalized[begin:end]:
            raise FormatError("normalization changed ELF text bytes")
    return bytes(normalized)


def range_index(functions):
    ranges = sorted((row["gpuVA"], row["gpuVA"] + row["bytes"], index)
                    for index, row in enumerate(functions) if row["gpuVA"] is not None)
    for left, right in zip(ranges, ranges[1:]):
        if left[1] > right[0]:
            raise FormatError("overlapping GPU function ranges; attribution is ambiguous")
    return ranges, [row[0] for row in ranges]


def pc_samples(sampling, functions, windows):
    ranges, starts = range_index(functions)
    scopes = [window for window in [*windows["frames"], *windows["dispatches"]] if window["durationNs"] is not None]
    result = {window["id"]: {"totalSamples": 0, "unmappedSamples": 0, "functions": Counter()} for window in scopes}
    for sm in sampling.PcSamplingPerSM:
        pcs, timestamps = sm.pcSamplesStorage, sm.timestampsStorage
        if len(pcs) % 16 or len(timestamps) % 16:
            raise FormatError("PC sample/timestamp storage is not 16-byte records")
        previous, previous_time = 0, None
        for timestamp, end in struct.iter_unpack("<QQ", timestamps):
            if end < previous or end > len(pcs) // 16 or (previous_time is not None and timestamp < previous_time):
                raise FormatError("PC timestamps or cumulative sample boundaries are nonmonotonic/out of bounds")
            selected = [window["id"] for window in scopes if window["startNs"] <= timestamp < window["endNs"]]
            if selected:
                for pc, _ in struct.iter_unpack("<QQ", pcs[previous * 16:end * 16]):
                    index = bisect.bisect_right(starts, pc) - 1
                    owner = ranges[index][2] if index >= 0 and pc < ranges[index][1] else None
                    for key in selected:
                        row = result[key]
                        row["totalSamples"] += 1
                        if owner is None:
                            row["unmappedSamples"] += 1
                        else:
                            row["functions"][owner] += 1
            previous, previous_time = end, timestamp
        if previous != len(pcs) // 16:
            raise FormatError("PC sample storage has unindexed trailing samples")
    return [{"windowId": key, "totalSamples": row["totalSamples"], "unmappedSamples": row["unmappedSamples"],
             "activeFunctions": [{"functionIndex": index, "samples": count} for index, count in sorted(row["functions"].items())]}
            for key, row in result.items()]


def run_tool(executable, arguments, output):
    proc = subprocess.run([str(executable), *arguments], capture_output=True, timeout=120)
    Path(str(output) + ".stdout.txt").write_bytes(proc.stdout)
    Path(str(output) + ".stderr.txt").write_bytes(proc.stderr)
    return {"executable": str(executable), "executableSha256": sha256(executable.read_bytes()),
            "arguments": arguments, "exitCode": proc.returncode, "stdoutBytes": len(proc.stdout),
            "stdoutPath": str(output) + ".stdout.txt", "stderrPath": str(output) + ".stderr.txt",
            "stderrBytes": len(proc.stderr), "success": proc.returncode == 0 and bool(proc.stdout),
            "unavailableReason": "nonzero exit or empty stdout" if proc.returncode != 0 or not proc.stdout else None}


def source_identity(row, expected, reference):
    matches = expected.get(row["decodedSha256"], [])
    self_derived = bool(reference and row["decodedSha256"] == reference["sourceSha256"] and
                        row["encodedSha256"] == reference["encodedSha256"])
    return matches, ("derivedFromReference/identityNotIndependentlyVerified" if self_derived else
                     "independentFullShaMatch" if matches else "sourceIdentityUnavailable")


def shader_objects(trace, windows, out, mapping=None, expected_dir=None, cuda_bin=None, reference=None):
    expected = {}
    if expected_dir:
        for path in sorted(expected_dir.glob("*.spv")):
            data = path.read_bytes()
            validate_spirv(data)
            expected.setdefault(sha256(data), []).append({"path": str(path), "bytes": len(data)})
    objects, functions, failures, matches, tools = [], [], [], [], []
    if cuda_bin:
        for name in ("cuobjdump", "nvdisasm"):
            path = cuda_bin / (name + ".exe" if (cuda_bin / (name + ".exe")).exists() else name)
            if path.exists():
                tools.append(run_tool(path, ["--version"], out / (name + "-version")))
            else:
                tools.append({"executable": str(path), "success": False, "unavailableReason": "requested executable absent"})
    for di, device in enumerate(trace.devices):
        if not device.HasField("ShaderProfilerReport"):
            continue
        report = device.ShaderProfilerReport
        blobs = report.blobTable.blobs
        session = report.pcCountersInfo.pcSamplingSession

        def export(index, stem):
            if index >= len(blobs) or index < 0:
                raise FormatError("shader blob reference out of bounds")
            blob = blobs[index]
            raw = unpack_blob(blob)
            (out / (stem + ".encoded.bin")).write_bytes(raw)
            data = decode_blob(blob, mapping)
            return data, {"device": di, "blob": index, "compression": blob.compression,
                          "obfuscation": blob.obfuscation, "encodedSha256": sha256(raw),
                          "decodedSha256": sha256(data), "bytes": len(data)}

        for mi, module in enumerate(session.modules):
            for kind in ("graphicsAPIModule", "rtCoreUserCubin"):
                if not module.HasField(kind):
                    continue
                index = module.graphicsAPIModule.bytecode.idx if kind == "graphicsAPIModule" else module.rtCoreUserCubin.cubin.ref.idx
                stem = f"device-{di}-module-{mi}-blob-{index}"
                try:
                    data, row = export(index, stem)
                    row.update(module=mi, kind=kind, capturedModuleMetadata=brief(module))
                    if kind == "graphicsAPIModule":
                        validate_spirv(data)
                        suffix = ".spv"
                        row["exactSourceMatches"], row["identityEvidence"] = source_identity(row, expected, reference)
                        if row["identityEvidence"] == "independentFullShaMatch":
                            matches.append({"device": di, "module": mi, "blob": index,
                                            "obfuscation": row["obfuscation"],
                                            "sha256": row["decodedSha256"], "matches": row["exactSourceMatches"]})
                    else:
                        suffix = ".cubin"
                        symbols, private = elf_functions(data)
                        row["privateRtSections"] = private
                        for symbol in symbols:
                            functions.append({**symbol, "device": di, "module": mi, "blob": index, "kind": kind})
                        derived = normalize_elf(data, symbols)
                        target = out / (stem + ".relative-symbols.cubin")
                        target.write_bytes(derived)
                        row["derivedElf"] = {"path": target.name, "sha256": sha256(derived), "textUnchanged": True}
                        if cuda_bin:
                            nvdisasm = cuda_bin / ("nvdisasm.exe" if (cuda_bin / "nvdisasm.exe").exists() else "nvdisasm")
                            if nvdisasm.exists():
                                row["disassembly"] = run_tool(nvdisasm, ["-gi", str(target)], out / (stem + ".sass"))
                    (out / (stem + suffix)).write_bytes(data)
                    row["path"] = stem + suffix
                    if kind == "rtCoreUserCubin" and cuda_bin:
                        cuobjdump = cuda_bin / ("cuobjdump.exe" if (cuda_bin / "cuobjdump.exe").exists() else "cuobjdump")
                        if cuobjdump.exists():
                            row["resourceTool"] = run_tool(cuobjdump, ["--dump-resource-usage", str(out / row["path"])], out / (stem + ".resources"))
                            content = (out / (stem + ".resources.stdout.txt")).read_text(encoding="utf-8", errors="replace")
                            for function in functions:
                                if function.get("module") != mi or function["device"] != di:
                                    continue
                                pattern = r"Function " + re.escape(function["symbol"]) + r":\s*REG:(\d+) STACK:(\d+) SHARED:(\d+) LOCAL:(\d+)"
                                match = re.search(pattern, content)
                                function["cudaResourceMetadata"] = dict(zip(
                                    ("REG", "CUDA_STACK", "CUDA_SHARED", "CUDA_LOCAL"), map(int, match.groups()))) if match else None
                                function["registerMetadataCorroborated"] = (match is not None and
                                    int(match.group(1)) == function["registersSectionInfo"])
                    objects.append(row)
                except (FormatError, OSError, subprocess.SubprocessError) as error:
                    failures.append({"device": di, "module": mi, "blob": index, "kind": kind, "reason": str(error)})
        for ci, code in enumerate(session.codeBlocks):
            if not code.HasField("shaderInstance"):
                continue
            shader = code.shaderInstance
            if not shader.HasField("vaRange"):
                continue
            row = {"device": di, "codeBlock": ci, "kind": "standardShaderInstance", "gpuVA": shader.vaRange.address,
                   "bytes": shader.vaRange.size, "symbol": None, "capturedMetadata": brief(shader.commonProgramMeta),
                   "capturedShaderMetadata": brief(shader),
                   "shaderType": brief(shader).get("type"), "functionGroups": list(shader.functionGroupIndices)}
            functions.append(row)
            try:
                data, obj = export(shader.instructions.idx, f"device-{di}-code-{ci}-instructions")
                obj["path"] = f"device-{di}-code-{ci}-instructions.bin"
                (out / obj["path"]).write_bytes(data)
                obj.update(codeBlock=ci, kind="standardShaderInstructions")
                objects.append(obj)
            except FormatError as error:
                failures.append({"device": di, "codeBlock": ci, "reason": str(error)})
            if shader.HasField("debugInfo"):
                stem = f"device-{di}-code-{ci}-debug"
                try:
                    data, obj = export(shader.debugInfo.ref.idx, stem)
                    obj.update(codeBlock=ci, kind="standardShaderDebugElf", path=stem + ".cubin")
                    symbols, private = elf_functions(data)
                    obj["symbols"], obj["privateRtSections"] = symbols, private
                    (out / obj["path"]).write_bytes(data)
                    if cuda_bin:
                        executable = cuda_bin / ("nvdisasm.exe" if (cuda_bin / "nvdisasm.exe").exists() else "nvdisasm")
                        if executable.exists():
                            obj["disassembly"] = run_tool(executable, ["-c", "-gi", str(out / obj["path"])], out / (stem + ".sass"))
                    objects.append(obj)
                except (FormatError, OSError, subprocess.SubprocessError) as error:
                    failures.append({"device": di, "codeBlock": ci, "kind": "standardShaderDebugElf", "reason": str(error)})
    samples, reason = [], None
    if len(trace.gpus) == 1 and len(trace.devices) == 1:
        try:
            samples = pc_samples(trace.gpus[0].SamplingData, functions, windows)
        except FormatError as error:
            reason = str(error)
    else:
        reason = "PC/device address namespace association is not reviewed for multiple GPUs/devices"
    mapping_confirmed = any(match["obfuscation"] == 2 for match in matches)
    return {"status": "partial" if failures or reason or (mapping is not None and not mapping_confirmed) else "decoded",
            "xorMappingIndependentTargetValidation": mapping_confirmed if mapping is not None else None,
            "objects": objects, "functions": functions,
            "sourceMatches": matches, "unavailableObjects": failures, "pcWindows": samples,
            "pcAttributionUnavailableReason": reason, "toolVersions": tools,
            "limitations": ["PC samples are not invocation counts, dynamic instructions, or exclusive time percentages.",
            "Unmapped PCs are separate; they cannot all be assigned to traversal.",
            "REG metadata is not peak live state or complete pipeline register allocation.",
            "CUDA STACK/LOCAL/SHARED=0 does not prove zero RT continuation stack or spill traffic.",
            "Private .rt.info sections are listed only; continuation stack bytes and dynamic spill bytes are unknown.",
            "Source SHA equality proves stored module identity, not actual specialization, push constants, or rendering budget."]}
