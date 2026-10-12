# Frozen vanilla material matching

Counts below enumerate literal Java PNG textures. Generated palette sprites are inventoried separately and deferred because their winning source and palette origins are unavailable. Eligibility follows the pinned JAR's actual blocks/items atlas declarations.

| Java | Literal PNGs | Eligible atlas PNGs | Integrated | Eligible unmatched | Other deferred | Generated palette sprites deferred |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 26.2 | 3855 | 2074 | 1835 | 239 | 1781 | 640 |
| 26.3 | 3964 | 2140 | 1843 | 297 | 1824 | 64 |

1845 frozen entries (1833 shared); 1849 RGBA8 PNGs, 729301 encoded bytes and 3230720 base-level decoded bytes. These are asset costs, not runtime performance measurements.

Bedrock sets: 128 conflict-loser, 1698 integrated, 574 mapping-low-confidence, 1 missing-source, 639 runtime-deferred.

[unintegrated.md](unintegrated.md) lists every excluded ID and reason. Every Java decision, top accepted alternatives, unused Bedrock set and failure reason is in [matching-report.json](matching-report.json). Exact RGB ignores alpha; fuzzy RGB uses Java visible texels and spatial structure. Host color, alpha and tint remain authoritative. Pixel similarity supports an artistic transfer, not model/UV or rendering equivalence.

Source-frame correspondence and normalized layer rescaling are frozen per entry. Repeated RGB frames can have different materials; deterministic same-index ties and the selected first used frame are recorded. RGB24 MER with S=0 is an importer compatibility approximation. Bedrock metal/subsurface winner selection is applied before encoding. Four DirectX normals preserve green sign for Prime's +V basis.

Common routed item geometry is covered. Raw, foil/outline, unknown source or cross-sprite UV fallback, standalone dynamic textures and generated palette sprites retain their existing material behavior. Build/runtime consumes only frozen PNGs and registry.rs.
