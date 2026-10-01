# RoboCute author BSDF hotfix

These files were provided directly to the Prime project owner by the RoboCute
author on 2026-07-24 as an authoritative temporary fix for dielectric highlight
energy compensation. They were authored against:

- Repository: https://github.com/RoboCute/RoboCute
- Base commit: `5985e989254b4685e3885d876b33f4874d233dcd`
- License: Apache License 2.0

Prime now pins RoboCute commit
`0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8`. That revision fixes thick-glass
transport but does not contain this replacement LUT or its branch-separated
multiple-scattering compensation, so the overlay remains independently locked.

The replacement transmission-GGX table has extent `44 x 32 x 159`, format
`HALF4`, and decoded size 1,790,976 bytes.

## SHA-256

- `coat.hpp`: `e5d7b25389111ef94bd9c5f5f0465938b41f6142dfb9dcc3d8441ed03e048df7`
- `microfacet.hpp`: `9d74a1f3e85f9a09fb8adfb604ecee0abe910ad1ae6a8afc53647ccc6c028c20`
- `resource_layout.hpp`: `6eac5cdde203394981529ff93b2d43ecee449deb0be48249eb497d89a1e0357d`
- `specular.hpp`: `867b4d51ef3db75d4808741c4e37c7977887fe3c29261f6a7ead024e8abc2f88`
- `transmission.hpp`: `7d81b6508cefac64715bf5bf9538db7e823068f6e889ea4712b4e0f3ecb8cefa`
- `trans_ggx.bytes`: `605c9160fb9348a1d033321c40cf9930226ce74c03f2624033f5b73aacfa67df`

Prime's GLSL files remain mechanical ports. Prime-specific bindings, resource
loading, tests, and material adapters stay outside this reference overlay.
