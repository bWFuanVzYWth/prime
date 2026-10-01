"""Bounded WRPV v10 and embedded protobuf descriptor readers."""

import hashlib
import json
import re
import struct
from pathlib import Path

import lz4.block
from google.protobuf import descriptor_pb2, descriptor_pool, message_factory
from google.protobuf.message import DecodeError

from . import FormatError

# Format profile observed in WRPV v10. Do not ship proprietary descriptors: read
# the caller's installed plugin/cache and require the reviewed wire contract.
PROFILE = {
    "Gfx/Messages/Common.proto": "2e777bb0f314e51dd7dc9fd34d01ba76674c1bee60171a7b376293d582625d50",
    "Gfx/Messages/Objects.proto": "1af17ecf3d8498babaa89104ed38f8c6b68f325e6abb64c3621827e0f630890b",
    "Gfx/Messages/EventParameters.proto": "25a3af4480554bad0df9f75bed50d81d37b10a30a3b9ea1794b4ad33965678b6",
    "ShaderProfiler/Messages/Report.proto": "1b5b15f5a0d2bd8180577f3e87988563aa6d9e2d46532bbf93872b98884a32d8",
    "WarpViz.proto": "d248c69b587b6c67e8c22c5c485b70ef70e55d0a92498b744ac8ef9b4fc1b0a8",
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
                          encoding="utf-8", newline="\n")


def require(data, offset, size, label):
    if offset < 0 or size < 0 or offset + size > len(data):
        raise FormatError(f"truncated/out-of-bounds {label} at {offset}, size {size}")


def unpack_chunks(data, max_chunk_bytes=512 * 1024 * 1024, max_total_bytes=2 * 1024**3):
    require(data, 0, 8, "WRPV header")
    magic, version = struct.unpack_from("<4sI", data)
    if (magic, version) != (b"WRPV", 10):
        raise FormatError(f"unsupported container magic/version: {magic!r}/{version}; supported WRPV v10")
    chunks, cursor, total = [], 8, 0
    identifiers = set()
    while cursor < len(data):
        require(data, cursor, 24, "chunk header")
        offset = cursor
        marker, count, usize = struct.unpack_from("<3Q", data, cursor)
        # Bits 16..31 have observed flags 0 (counter image) / 1 (metadata);
        # their meaning is private. Reject other values rather than masking them.
        if marker & 0xffffffff not in (0x1234, 0x11234) or not 1 <= count <= 16:
            raise FormatError(f"unsupported chunk marker/block count at {cursor}: {marker:#x}/{count}")
        identifier = marker >> 32
        if identifier in identifiers:
            raise FormatError(f"duplicate chunk id {identifier}")
        identifiers.add(identifier)
        total += usize
        if usize > max_chunk_bytes or total > max_total_bytes:
            raise FormatError("uncompressed data exceeds configured size limit")
        cursor += 24
        pieces, blocks, block_total = [], [], 0
        for _ in range(count):
            require(data, cursor, 24, "LZ4 block header")
            block_marker, csize, busize = struct.unpack_from("<3Q", data, cursor)
            if block_marker != 0x14321 or busize > usize:
                raise FormatError(f"invalid LZ4 block marker/size at {cursor}")
            require(data, cursor + 24, csize, "LZ4 payload")
            block_total += busize
            if block_total > usize:
                raise FormatError("block sizes exceed chunk size")
            encoded = data[cursor + 24:cursor + 24 + csize]
            try:
                decoded = lz4.block.decompress(encoded, uncompressed_size=busize)
            except (ValueError, lz4.block.LZ4BlockError) as error:
                raise FormatError(f"invalid LZ4 at {cursor}: {error}") from error
            if len(decoded) != busize:
                raise FormatError("LZ4 output length does not match block header")
            pieces.append(decoded)
            blocks.append({"offset": cursor, "compressedBytes": csize, "uncompressedBytes": busize})
            cursor += 24 + csize
        decoded = b"".join(pieces)
        if len(decoded) != usize:
            raise FormatError("chunk output length does not match header")
        chunks.append(({"id": identifier, "offset": offset, "marker": hex(marker), "privateFlags": (marker >> 16) & 65535,
                        "uncompressedBytes": usize, "sha256": sha256(decoded), "blocks": blocks}, decoded))
    if not chunks:
        raise FormatError("empty WRPV container")
    return chunks


def varint(data, offset):
    value = 0
    for shift in range(0, 70, 7):
        require(data, offset, 1, "protobuf varint")
        byte = data[offset]
        offset += 1
        if shift == 63 and byte > 1:
            raise FormatError("overflowing protobuf varint")
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
    raise FormatError("unterminated protobuf varint")


def descriptor_at(data, start):
    offset = start
    while offset < len(data):
        key, next_offset = varint(data, offset)
        if key >> 3 not in {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14}:
            break
        wire = key & 7
        if wire == 2:
            size, next_offset = varint(data, next_offset)
            next_offset += size
        elif wire == 0:
            _, next_offset = varint(data, next_offset)
        elif wire in (1, 5):
            next_offset += 8 if wire == 1 else 4
        else:
            break
        if next_offset > len(data) or next_offset - start > 100000:
            break
        offset = next_offset
    encoded = data[start:offset]
    descriptor = descriptor_pb2.FileDescriptorProto.FromString(encoded)
    if not descriptor.name.endswith(".proto") or not descriptor.message_type:
        raise FormatError("not a complete descriptor")
    return descriptor, encoded


def plugin_descriptors(path):
    data = path.read_bytes()
    found = {}
    for match in re.finditer(rb"\x0a[\x01-\x7f][A-Za-z0-9_./-]+\.proto", data):
        try:
            descriptor, encoded = descriptor_at(data, match.start())
        except (ValueError, IndexError, DecodeError):
            continue
        if descriptor.name not in PROFILE:
            continue
        if descriptor.name in found and found[descriptor.name][0] != encoded:
            raise FormatError(f"ambiguous embedded descriptor {descriptor.name}")
        found[descriptor.name] = (encoded, match.start())
    return found, {"path": str(path), "bytes": len(data), "sha256": sha256(data)}


def schema_class(host, schema_dir, out):
    if schema_dir is not None:
        found = {name: ((schema_dir / (name.replace("/", "__") + ".pb")).read_bytes(), None)
                 for name in PROFILE}
        source = {"kind": "explicitDescriptorCache", "path": str(schema_dir)}
    elif host is not None:
        found, source = plugin_descriptors(host / "Plugins/WarpVizPlugin/WarpVizPlugin.dll")
        source["kind"] = "embeddedPluginDescriptors"
    else:
        raise FormatError("no descriptor source: supply --nsight-host or --schema-dir; chunks remain available")
    rows = []
    pool = descriptor_pool.DescriptorPool()
    for name, expected in PROFILE.items():
        if name not in found:
            raise FormatError(f"required descriptor missing: {name}")
        encoded, offset = found[name]
        digest = sha256(encoded)
        if digest != expected:
            raise FormatError(f"unreviewed descriptor wire profile: {name} SHA256={digest}")
        pool.AddSerializedFile(encoded)
        target = name.replace("/", "__") + ".pb"
        (out / target).write_bytes(encoded)
        rows.append({"name": name, "sha256": digest, "bytes": len(encoded), "sourceOffset": offset,
                     "path": target})
    cls = message_factory.GetMessageClass(pool.FindMessageTypeByName("NV.WarpViz.PbTraceData"))
    return cls, {"profile": "wrpv10-reviewed-wire-v1", "source": source, "descriptors": rows}


def brief(msg, limit=100):
    result = {}
    for field, value in msg.ListFields():
        def one(item):
            if field.type == field.TYPE_BYTES:
                return {"bytes": len(item), "sha256": sha256(item)}
            if field.message_type:
                return brief(item, limit)
            if field.enum_type:
                enum = field.enum_type.values_by_number.get(item)
                return enum.name if enum else {"unknownEnumNumber": item}
            return item
        if field.is_repeated:
            result[field.name] = ([one(item) for item in value] if len(value) <= limit else
                                  {"count": len(value), "first": [one(item) for item in value[:5]]})
        else:
            result[field.name] = one(value)
    return result
