# Frozen vanilla PBR preset — internal schema 1

The committed PNGs are the final editable source assets. Builds embed PNGs and
the frozen Java registry; they do not run matching or require Bedrock files or
Java client JARs. This preset transfers usable material layers from the pinned
Mojang samples onto actual Java textures. General Bedrock pack loading is separate.

[matching-summary.md](matching-summary.md) gives generated coverage and asset costs.
[unintegrated.md](unintegrated.md) lists every unintegrated Java ID and unused
Bedrock set with reasons. [matching-report.json](matching-report.json) retains both
complete literal PNG inventories, separate generated palette sprite IDs, accepted
candidates, metric values, conflicts and source decisions. These inventories do
not claim to enumerate all sprites a mod can generate at runtime.
[unmatched-review.md](unmatched-review.md) separates the eligible matching gaps by
actual source declarations, version differences, UV/crop evidence, tint/structure
differences and rejected metric gates. Its [machine report](unmatched-review.json)
retains each ID, concrete candidates, model UV regions and exact crop probes.

## Reachability and matching

Eligibility follows the pinned Java `blocks.json` and `items.json` atlas sources,
including conduit, bell and enchanting-book sprites in the block atlas. Common
routed item geometry uses the captured item's actual sprite identity and private
base sheet. Raw, foil/outline, unknown-source or cross-sprite UV fallback retains
its existing material behavior. Standalone dynamic textures and generated palette
sprites are deferred: their required source identity or winning pack origins are
not available. A texture's directory alone does not establish runtime eligibility.

`manifest.json` uses exact Java ResourceLocations. `base: host` retains actual host
RGB, alpha and tint; no duplicate original albedo is embedded. External authored
materials take precedence in the Minecraft source adapter. The preset therefore
transfers PBR over the Java base color, including metallic materials; it does not
claim identical Bedrock rendering or infer another pack's albedo.

Matching is offline and deterministic, with unrestricted Bedrock source categories:

1. Exact full-sheet RGB, ignoring alpha; then exact first-used-frame RGB; then
   exact nearest-resampled frame RGB at 16×16 with the same aspect ratio.
2. Fuzzy candidates compare 16×16 bilinear RGB on Java-visible texels (`A > 12`),
   ignoring source alpha. RGB RMSE, channel-mean-centered RMSE, luminance correlation
   and visible edge correlation jointly require spatial evidence.
3. The near gate requires RGB RMSE ≤36/255, MAE ≤28/255, centered RMSE ≤30/255,
   luminance correlation ≥0.55 and edge cosine ≥0.35. The recolor gate permits
   RMSE/MAE ≤96/255, but centered RMSE ≤14/255, correlation ≥0.85 and edge ≥0.70.
   Very low-variance matches require RMSE ≤16/255, centered RMSE ≤8/255 and logical
   name support ≥0.4; visible coverage below 10% also requires name support ≥0.4.
4. Accepted exact candidates prefer logical names, then matching category and a
   stable source ID. Accepted fuzzy candidates prefer logical names before spatial
   score. Score is `RMSE + 0.5*centered + (12/255)*(1-corr) + (8/255)*(1-edge)`.
   Numbered Java icons prefer equal source frame indices among identical RGB.

Names support ranking; spatial gates determine fuzzy acceptance. Similar pixels
are evidence for an artistic transfer, not proof of semantic or model/UV equality.
Identical RGB can have different PBR layers. The report retains top candidates,
their score margin and material conflicts; every source losing a target conflict
remains individually listed. A margin can be negative when logical naming wins
over a lower RGB distance.

Corrections live in
[scripts/vanilla-pbr-matches.json](../../../../scripts/vanilla-pbr-matches.json):
`forced_matches` maps a Java ID (or `262:<Java ID>` / `263:<Java ID>`) to
`{source, frame, reason}`; optional `projection: frame-index` requires evidenced
equal source/target frame layouts. `exclude_java` and `exclude_bedrock` map IDs to reasons.
`source_frame_extent` requires an explicit extent and source-layout reason. The
four compass/recovery/watch directional atlases have evidenced 16×16 frame rules.
The powered lightning rod has an explicit source override: its Java RGB is uniform
white and collides with white glass, so the powered-rod material wins by identity
while host color/tint remains unchanged. The report retains the RGB alternative.
Conduit wind sprites use two explicit same-name, same-index overrides: source and
Java visible RGB agree over all 22 frames, while invisible RGB caused a coarse
resampled-frame collision with an unrelated trim texture. Their source frames are
64×32, matching the host layout; resulting MERS is the same constant material.
There is no fuzzy matching or reference-file lookup at runtime.

## Internal planes and animation

All material PNGs are 8-bit RGBA, consumed as linear data bytes without ICC/gamma
conversion. MERS channels are R metalness, G emission, B perceptual roughness and
A subsurface. All four filter continuously. MERS alpha is data: retain RGB at A=0,
never premultiply or clear invisible RGB on export. PNG zero bytes remain zero;
the Minecraft translation layer lets a complete source with G=0 inherit native
block/fluid emission, for both embedded presets and external Lab materials. Any
nonzero frame retains authored emission and its local dark texels. When native and
texture emission coexist, power mixing defaults to 50% while retaining the texture
pattern; see the [emission contract](../../../../docs/materials.md#发光与介质).
The generic Scene interface retains its explicit authored-emission semantics.
The importer does not invent emission bytes.

Generated layers apply Bedrock's
[metal/subsurface winner rule](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/texturesetsreference/texturesetsconcepts/texturesetsintroduction?view=minecraft-bedrock-stable):
M>S retains M and clears S; otherwise S wins and M is cleared, including ties.
The samples' RGB24 files declared as MERS lack the fourth channel. Importing those
as MER with S=0 is an explicit compatibility approximation, not the documented
four-channel MERS contract. Original files, modes and hashes remain recorded.

Auxiliary channels are R/G tangent normal XY, B GGX distribution roughness and
A IOR encoded as `1 + 1.5*A/255`. XY uses biased signed bytes, with 128 as zero.
Absent auxiliary uses `[128,128,0,85]` (IOR 1.5). The authored-normal flag is separate:
without it, geometric normals remain authoritative. The two retained lava source
normals are [DirectX normals](https://www.nvidia.com/en-us/geforce/guides/minecraft-rtx-texturing-guide/).
Their XYZ vectors are normalized and green sign retained: Prime's bitangent follows
positive texture V/image-down, confirmed with the production normal-mapping shader.
There are no AO or height planes. CPU normal mips/animation preserve moments;
GPU spatial filtering follows the [material contract](../../../../docs/materials.md).

PNG dimensions are the complete Java sheet, not merely a frame. `frame_extent` and
the actual host animation sequence/durations are recorded. Exact whole sheets keep
frame-index correspondence and rescale the PBR layer by normalized UV if required.
Other matches freeze a source frame per Java sheet frame: the selected first-used
frame is fixed, later frames use nearest visible RGB with equal-index duplicate
ties, and a static source repeats. This projection is an explicit approximation;
the host controls animation timing and visibility.

Vanilla water still/flow are explicitly excluded in the import policy as an
artistic choice. They have no embedded MERS/Aux pages: the native smooth optical
surface uses geometric normals and water IOR, retaining actual host Color, alpha
and animation. External authored LabPBR water remains independent and takes
precedence. Regeneration retains this exclusion; `--replace-art` does not re-enable
it. This choice does not claim to reproduce Bedrock's original water appearance.

## Reproduce and edit

Install pinned offline-tool requirements, then run from the repository root:

```powershell
python -m pip install -r scripts/vanilla-pbr-requirements.txt
python scripts/import-vanilla-pbr.py C:\WorkSpace\_ref\bedrock-samples `
  --java-26-2 "$env:USERPROFILE/.gradle/caches/fabric-loom/26.2/minecraft-client.jar" `
  --java-26-3 "$env:USERPROFILE/.gradle/caches/fabric-loom/26.3/minecraft-client.jar"
python scripts/import-vanilla-pbr.py --check
python -m unittest scripts/test_import_vanilla_pbr.py
```

With the same source/JAR arguments, add `--review-unmatched` to regenerate the
diagnostic review only. It does not add matches or change registry/PNG data.
Logical aliases are investigation candidates, and exact RGB crop proof applies
only to the specified visible pixels/UV region. Accepting a new material proxy,
or building and validating a complete UV repack, is a separate explicit decision.

Use `--output artifacts/vanilla-pbr-generated` for a separate review baseline and
`--policy <file>` for correction policies. Generation verifies the pinned reference
HEAD and unchanged source inputs, records both JAR hashes and tool versions, and
round-trips every generated PNG to exact channel bytes. Existing artist-edited
PNGs are retained when their extent still matches. `--replace-art` explicitly
restores generated baselines. Byte-identical outputs are not rewritten.

Edit referenced `mers.png` or `auxiliary.png` in an image editor, keeping its full
sheet extent, 8-bit RGBA export and raw data channels. Version-specific entries
have `262`/`263` subdirectories; common entries use game version 0. Build requires
only these PNGs and metadata. `--check` validates frozen registry, asset layouts
and report completeness without either reference checkout or JAR; it reports art
edits without rejecting them. Baseline hashes describe provenance, not a request
to undo artwork. Adding/removing planes or changing layout requires updating
manifest and registry together; ordinary pixel edits do not change metadata.
Download Git LFS objects before compiling; a pointer file is not a PNG.

## Source and license

Source: Mojang [bedrock-samples](https://github.com/Mojang/bedrock-samples), commit
`921fafb05c93abeae56c2f2868cd8f942bdbcc0f`, version `1.26.30.5` (2026-06-16).
Per-entry source paths/hashes and per-version decisions are frozen in the manifest;
all source sets appear in the report. Original TGA files stay in the external
reference checkout and are not runtime assets.

[SOURCE-LICENSE.md](SOURCE-LICENSE.md) retains the upstream notice with repository
line endings. Mojang assets retain their copyright and Minecraft EULA terms;
container conversion does not make them Prime-authored GPL assets. Prime's importer
and registry code follow the project license. See repository third-party notices
for distribution scope.
