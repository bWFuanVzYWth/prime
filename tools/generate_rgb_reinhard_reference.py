"""Execute the pinned drt WGSL to capture RGB outputs (requires numpy and wgpu).

Usage: python tools/generate_rgb_reinhard_reference.py C:/WorkSpace/drt
Only the resource bindings and entry wrapper change; all DRT functions are original.
"""

import colorsys
import hashlib
from pathlib import Path
import subprocess
import sys

import numpy as np
from wgpu.utils.compute import compute_with_buffers


REVISION = "a15e90947965f93c71e943fe27caf2ea56e08c10"
source_root = Path(sys.argv[1])
source_path = source_root / "shaders/rgb_reinhard.wgsl"
source_bytes = source_path.read_bytes()
committed = subprocess.check_output(
    ["git", "-C", str(source_root), "show", f"{REVISION}:shaders/rgb_reinhard.wgsl"]
)
if source_bytes.replace(b"\r\n", b"\n") != committed.replace(b"\r\n", b"\n"):
    raise ValueError("Reference shader differs from the pinned revision")
source = source_bytes.decode()
shader = source[:source.index("@group(0)")] + """
@group(0) @binding(0) var<storage, read> inputs: array<vec4f>;
@group(0) @binding(1) var<storage, read_write> outputs: array<vec4f>;
@group(0) @binding(2) var<storage, read> parameters: DrtParameters;
""" + source[source.index("const NEUTRAL_WEIGHTS"):source.index("@compute")]
shader += """
@compute @workgroup_size(1)
fn main(@builtin(global_invocation_id) id: vec3u) {
    let source = inputs[id.x].xyz;
    let originalLinear = max(source, vec3f(0.0));
    let working = toExpandedGamutCoordinates(originalLinear);
    let mappedLinear = fromExpandedGamutCoordinates(reinhard(working));
    let mapped = protectHsvHue(originalLinear, encodeSrgb(mappedLinear));
    outputs[id.x] = vec4f(prepareOutput(source, mapped), 1.0);
}
"""

colors = [(0, 0, 0), (1, 0, 0), (0, 1, 0), (0, 0, 1), (1, 1, 0),
          (1, 0, 1), (0, 1, 1), (-1, 0.1, 4), (0.18, 0.18, 0.18),
          (3.4374495, 3.376667, 3.439753)]
colors += [(v, v, v) for v in (1e-30, 1e-12, 1e-8, 1e-5, 0.0031307, 0.0031308,
           0.0031309, 0.09, 0.1799, 0.1801, 0.5, 1, 4, 16, 64, 1e4, 1e10, 1e30)]
colors += [colorsys.hsv_to_rgb(hue / 16, saturation, value)
           for hue in range(16) for saturation, value in ((1, 0.02), (1, 1), (0.8, 16), (0.1, 64))]
rng = np.random.default_rng(0x519A01D)
colors += [tuple(2.0 ** rng.uniform(-16, 16, 3)) for _ in range(24)]
inputs = np.array([(*color, 1.0) for color in colors], dtype=np.float32)

# src/gpu.rs ReinhardParameters::curve_for_headroom, with highlight reach overridden to 8.0.
f = np.float32
join = f(0.18)
lines = ["# drt " + REVISION + " shaders/rgb_reinhard.wgsl",
         "# WGSL sha256 (LF): " + hashlib.sha256(committed.replace(b"\r\n", b"\n")).hexdigest(),
         "# highlight reach 8.0 EV; other RGB Reinhard parameters use source defaults",
         "# headroom r g b mapped_r mapped_g mapped_b; linear Rec.709 in, encoded sRGB out"]
for headroom in (1, 4, 64):
    reach = f(0.18) * f(2) ** (f(8) + np.log2(f(headroom)))
    tangent_distance = reach - join
    output_distance = f(headroom) - join
    extent = output_distance * tangent_distance / (tangent_distance - output_distance)
    parameters = np.zeros(27, dtype=np.float32)
    parameters[15] = join
    parameters[18:23] = [f(0.04), f(1), f(headroom), f(0.5), join + extent]
    result = compute_with_buffers({0: inputs, 2: parameters}, {1: inputs.nbytes}, shader, n=len(inputs))
    mapped = np.frombuffer(result[1], dtype=np.float32).reshape(-1, 4)
    if not np.all(np.isfinite(mapped)):
        raise ValueError("Non-finite reference output")
    for color, output in zip(inputs, mapped):
        lines.append(" ".join(f"{v:.9g}" for v in (headroom, *color[:3], *output[:3])))
output_path = Path(__file__).resolve().parents[1] / "src/test/resources/prime/rgb_reinhard_reference.txt"
output_path.parent.mkdir(parents=True, exist_ok=True)
output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(f"Captured {len(inputs) * 3} WGSL RGB samples: {output_path}")
