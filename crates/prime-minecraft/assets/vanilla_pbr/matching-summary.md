# Frozen vanilla material matching

Counts below enumerate literal Java PNG textures. Generated palette sprites are inventoried separately and deferred because their winning source and palette origins are unavailable. Eligibility follows the pinned JAR's actual blocks/items atlas declarations.

| Java | Literal PNGs | Eligible atlas PNGs | Integrated | Eligible unmatched | Artist excluded | Other deferred | Generated palette sprites deferred |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 26.2 | 3855 | 2074 | 1833 | 239 | 2 | 1781 | 640 |
| 26.3 | 3964 | 2140 | 1841 | 297 | 2 | 1824 | 64 |

1843 frozen entries (1831 shared); 1845 RGBA8 PNGs, 704472 encoded bytes and 2903040 base-level decoded bytes. These are asset costs, not runtime performance measurements.

Bedrock sets: 126 conflict-loser, 1696 integrated, 574 mapping-low-confidence, 1 missing-source, 2 policy-excluded, 641 runtime-deferred.

## What the long exclusion list contains

Counts are texture/sprite IDs, not distinct block or item types. Bed colors/faces, hydration stages and two Java versions expand a small number of families into many rows. Eligible unmatched IDs have no accepted RGB/frame correspondence; other literal PNGs are outside the activated blocks/items source boundary. Generated palette sprites are counted separately, and unused Bedrock sets are a second source inventory rather than additional Java gaps.

### Eligible atlas textures without a match

| Texture family | 26.2 | 26.3 |
| --- | ---: | ---: |
| Bed faces | 114 | 116 |
| Signs and hanging signs | 24 | 26 |
| Dried ghast hydration faces | 24 | 24 |
| Debug/test/light icons | 24 | 23 |
| Block breaking overlays | 10 | 20 |
| Other block textures | 13 | 17 |
| Sulfur spike blocks/items | 11 | 11 |
| Map icons/overlays | 1 | 18 |
| Cushion items | 0 | 14 |
| Other item textures | 7 | 7 |
| Conduit | 6 | 6 |
| Chains | 5 | 5 |
| Poplar blocks/items | 0 | 10 |

### Other literal textures deferred by source boundary

| Texture family | 26.2 | 26.3 |
| --- | ---: | ---: |
| entity | 716 | 734 |
| gui | 555 | 563 |
| particle | 288 | 300 |
| painting | 52 | 52 |
| trims | 57 | 40 |
| mob_effect | 40 | 40 |
| map | 37 | 42 |
| environment | 14 | 14 |
| misc | 13 | 13 |
| palettes | 0 | 17 |
| font | 5 | 5 |
| colormap | 3 | 3 |
| effect | 1 | 1 |

Entity/particle textures need trustworthy runtime source identity, winning-pack origin and UV/material ownership before activation. GUI/font/map/environment/colormap data also include inputs with no ordinary surface-PBR role; inventory presence alone does not justify attaching a material. Palette-derived armor/item sprites additionally need the winning origins of both source image and palette.

[unintegrated.md](unintegrated.md) lists every excluded ID and reason. Every Java decision, top accepted alternatives, unused Bedrock set and failure reason is in [matching-report.json](matching-report.json). Exact RGB ignores alpha; fuzzy RGB uses Java visible texels and spatial structure. Host color, alpha and tint remain authoritative. Pixel similarity supports an artistic transfer, not model/UV or rendering equivalence.

Source-frame correspondence and normalized layer rescaling are frozen per entry. Repeated RGB frames can have different materials; deterministic same-index ties and the selected first used frame are recorded. RGB24 MER with S=0 is an importer compatibility approximation. Bedrock metal/subsurface winner selection is applied before encoding. The two retained lava DirectX normals preserve green sign for Prime's +V basis. Vanilla water presets are artist-excluded, retaining native optical defaults, geometric normals and host Color/alpha/animation; external authored water materials keep their own behavior.

Common routed item geometry is covered. Raw, foil/outline, unknown source or cross-sprite UV fallback, standalone dynamic textures and generated palette sprites retain their existing material behavior. Build/runtime consumes only frozen PNGs and registry.rs.
