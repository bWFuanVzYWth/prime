# Falcor paired-neighbor table

`paired-neighbors-3-16.bytes` contains the unchanged unsigned 16-bit pixels decoded from Falcor `Source/Modules/ReSTIRPathTracing/PairedReusePattern/neighborCount3-stdev16.0/neighbor{0,1,2}.png`, in top-down, little-endian order. The file has three 256×256 segments; the actual tables are 254×254, 232×232, and 196×196, tightly packed at each segment start with unused entries zeroed. GPU initialization expands these words to the original uint32 representation once. Pixel bits 0–7 encode dx+128; bits 8–15 encode dy+128.

Reproduce using `python scripts/restir-paired-pattern.py <Falcor checkout>`. The generated file has 393216 bytes and SHA-256 `bad5dd07ed0b8f472a6f2950cacc9a477c1f7c1318ab5d9ae4a2a708eb3bf358`.

`paired-neighbors.bank.bytes` adds the 50 original configurations: neighbor counts 1–5 and mean radii 5–50 in steps of 5. Each radius selects `stdev=sqrt(8/(9*pi))*radius`, formatted to one decimal as in the source folder name. Every table is verified for reciprocal offsets across periodic edges before packing. The default still loads the exact separate payload above.

Reproduce with `python scripts/restir-paired-pattern.py <Falcor checkout> --all --output crates/prime-vulkan/assets/restir/paired-neighbors.bank.bytes` and Zstd available on PATH. The bank is 11823793 bytes, SHA-256 `2c91edd5881d73de2a18a1d3107bc23694c820a763ced83797e090ca833987fa`. Its `PRPN0001` header stores 50 little-endian 48-byte entries (count/radius, five dimensions, reserved word, u64 offset/length), followed by independent Zstd frames of uint16 segments. Only the selected frame is decoded and expanded during an actual spatial configuration change; inactive spatial reuse does not upload a changed LUT. The embedded bank is an LFS asset, not a shader variant per setting.

Original PNG SHA-256:

| Table | SHA-256 |
| --- | --- |
| neighbor0 | daa416e7258eb18c0a5b819abf18091095d286df430cd5719d237335094dc027 |
| neighbor1 | b8b8d6a268ef9a2df73f4f92e856b2adb294dc0eef843a0b5f8c4046eab3d861 |
| neighbor2 | 20a7c5c17aa7464bd5c8653d57732c29a9c6d559b145a97df51d0edf68fee471 |

Copyright NVIDIA; see [LICENSE.txt](LICENSE.txt). The reservoir, RNG, pairwise and neighbor-transform source files retain the original redistribution notice.
