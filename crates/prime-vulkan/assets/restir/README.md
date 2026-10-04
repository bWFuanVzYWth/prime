# Falcor paired-neighbor table

`paired-neighbors-3-16.bytes` contains the unchanged unsigned 16-bit pixels decoded from Falcor `Source/Modules/ReSTIRPathTracing/PairedReusePattern/neighborCount3-stdev16.0/neighbor{0,1,2}.png`, in top-down, little-endian order. The file has three 256×256 segments; the actual tables are 254×254, 232×232, and 196×196, tightly packed at each segment start with unused entries zeroed. GPU initialization expands these words to the original uint32 representation once. Pixel bits 0–7 encode dx+128; bits 8–15 encode dy+128.

Reproduce using `python scripts/restir-paired-pattern.py <Falcor checkout>`. The generated file has 393216 bytes and SHA-256 `bad5dd07ed0b8f472a6f2950cacc9a477c1f7c1318ab5d9ae4a2a708eb3bf358`.

Original PNG SHA-256:

| Table | SHA-256 |
| --- | --- |
| neighbor0 | daa416e7258eb18c0a5b819abf18091095d286df430cd5719d237335094dc027 |
| neighbor1 | b8b8d6a268ef9a2df73f4f92e856b2adb294dc0eef843a0b5f8c4046eab3d861 |
| neighbor2 | 20a7c5c17aa7464bd5c8653d57732c29a9c6d559b145a97df51d0edf68fee471 |

Copyright NVIDIA; see [LICENSE.txt](LICENSE.txt). The reservoir, RNG, pairwise and neighbor-transform source files retain the original redistribution notice.
