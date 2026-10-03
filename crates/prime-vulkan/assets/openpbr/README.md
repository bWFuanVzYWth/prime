# Production OpenPBR energy reference

`author-bsdf-hotfix-2026-07-24/trans_ggx.bytes` is the immutable 44x32x159
HALF4 source texture used by the exact OpenPBR supported-domain kernels. Its six
reference files and original author notice are retained without edits; the
C++ headers are provenance/oracle references and are not build inputs.
`robocute.lock.json` fixes the upstream revision, separately authorized overlay,
raw asset hashes, supported domain and approved endpoint differences.

Production embeds `trans_ggx.ktx2`, a lossless KTX2/Zstd-22 packaging of those
same HALF4 bytes. The original author reference stays immutable. Dimensions,
parameter axes, provenance and decoded SHA-256 are recorded in the KTX metadata
and `../packed-assets.json`; `scripts/pack_assets.py --check` validates them.

The resource owner uploads the table once into device-local image memory during
actual frame preparation. Realtime and Offline entries bind it at set0/binding9
and explicitly pass an energy provider into pure shader libraries. No source
material presets or automatic foliage recipe is included. RoboCute Apache-2.0
notice and license are at the repository `licenses/robocute-*` paths.
