"""Export immutable four-wave medium inputs from the pinned Sky Tracer source.

Only the temporary copy is modified. The runtime still solves scattering on the GPU;
this payload contains physical coefficients, quadrature and coordinate calibration.
"""
import argparse
import base64
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys

sys.dont_write_bytecode = True

COMMIT = "b66b16342afe38e788a5ece5371d3b3a67c5909a"
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--gpu-reference", action="store_true", help="Regenerate WGSL regression fixtures on a GPU")
    args = parser.parse_args()
    source = args.source.resolve()
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
    if revision != COMMIT:
        raise ValueError(f"Expected Sky Tracer {COMMIT}, got {revision}")
    if subprocess.check_output(["git", "status", "--porcelain", "--", "crates/sky-realtime"], cwd=source):
        raise ValueError("Sky Tracer realtime source has local changes")
    work = ROOT / "build/atmosphere-export"
    crate = work / "crates/sky-realtime"
    shutil.copytree(source / "crates/sky-realtime", crate, dirs_exist_ok=True)
    manifest = (source / "Cargo.toml").read_text().split("[workspace.package]", 1)[1]
    (work / "Cargo.toml").write_text('[workspace]\nmembers=["crates/sky-realtime"]\nresolver="3"\n[workspace.package]' + manifest)
    shutil.copy2(source / "Cargo.lock", work / "Cargo.lock")
    exporter = '''
pub fn export_prime(path: &std::path::Path) -> Result<()> {
    let m = Medium::new(&Model::earth()?, &Wavelengths::optimized_four(), &Config::balanced(), 0.18)?;
    std::fs::write(path.join("medium.bin"), bytemuck::cast_slice(&m.data))?;
    std::fs::write(path.join("params.bin"), bytemuck::bytes_of(&m.params))?;
    // Export gas and aerosol extinction independently; subtraction of the total would lose
    // small aerosol contributions. Scattering already has separate species in the payload.
    let gas = Model::earth_with_aerosol_scale(0.0)?;
    let mut scene = physics::data::load_scene_data(&physics::data::default_data_dir(), 0.0, 0.0)
        .map_err(|e| e.to_string())?;
    for point in &mut scene.atmospheric_profile {
        point.air_cm3 = 0.0;
        point.ozone_cm3 = 0.0;
    }
    let aerosol = Model::from_scene(&scene)?;
    let w = Wavelengths::optimized_four();
    let mut extinction: Vec<[f32; 4]> = Vec::new();
    for (h, _) in &gas.bands[w.indices[0]].profile {
        extinction.push(w.indices.map(|i| gas.bands[i].coefficients(*h).extinction));
        extinction.push(w.indices.map(|i| aerosol.bands[i].coefficients(*h).extinction));
    }
    std::fs::write(path.join("extinction.bin"), bytemuck::cast_slice(&extinction))?;
    let mut reference = Vec::new();
    for scale in [0.0, 0.5, 16.0] {
        let medium = Medium::new(&Model::earth_with_aerosol_scale(scale)?, &w, &Config::balanced(), 0.18)?;
        reference.extend_from_slice(bytemuck::cast_slice(&medium.data));
    }
    std::fs::write(path.join("medium-scale-reference.bin"), reference)?;
    Ok(())
}
'''
    lib = crate / "src/lib.rs"
    lib.write_text(lib.read_text() + exporter)
    examples = crate / "examples"
    examples.mkdir(exist_ok=True)
    (examples / "prime.rs").write_text('fn main() { sky_realtime::export_prime(std::path::Path::new("." )).unwrap(); }\n')
    subprocess.run(["cargo", "run", "--release", "-p", "sky-realtime", "--example", "prime"], cwd=work, check=True)
    payload = (work / "medium.bin").read_bytes()
    params = (work / "params.bin").read_bytes()
    resources = ROOT / "src/client/resources/prime/atmosphere"
    resources.mkdir(exist_ok=True)
    encoded = base64.encodebytes(gzip.compress(payload, mtime=0)).decode()
    (resources / "medium.bin.gz.b64").write_text(encoded)
    extinction = (work / "extinction.bin").read_bytes()
    (resources / "extinction.bin.gz.b64").write_bytes(base64.encodebytes(gzip.compress(extinction, mtime=0)))
    fixtures = ROOT / "src/test/resources/prime/atmosphere"
    fixtures.mkdir(parents=True, exist_ok=True)
    (fixtures / "medium-scale-reference.bin.gz.b64").write_bytes(base64.encodebytes(
        gzip.compress((work / "medium-scale-reference.bin").read_bytes(), mtime=0)))
    fields = [("size_steps", "I", 4), ("view", "f", 4), ("sun", "f", 4),
              ("dims", "I", 4), ("offsets0", "I", 4), ("offsets1", "I", 4),
              ("medium", "f", 4), ("solar", "f", 4), ("rgb", "f", 16),
              ("solve", "I", 4), ("extra", "I", 4), ("mapping", "I", 4),
              ("sky", "I", 4), ("optical_segments", "f", 24),
              ("angular_mapping", "f", 4), ("importance", "f", 4), ("batch", "I", 4)]
    values = {}
    offset = 0
    for name, kind, count in fields:
        values[name] = list(struct.unpack_from("<" + kind * count, params, offset))
        offset += count * 4
    assert offset == len(params)
    metadata = {"sourceCommit": COMMIT, "bytes": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(), "params": values,
                "extinction": {"bytes": len(extinction), "sha256": hashlib.sha256(extinction).hexdigest(),
                               "layout": "per profile height: gas float4, aerosol float4"}}
    (resources / "medium.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(f"Exported {len(payload)} bytes; SHA-256 {metadata['sha256']}")
    if args.gpu_reference:
        from atmosphere_reference import pack_reference
        lib.write_text(lib.read_text() + (ROOT / "tools/atmosphere_reference.rs").read_text())
        manifest = crate / "Cargo.toml"
        manifest.write_text(manifest.read_text().replace("[dev-dependencies]", "[dev-dependencies]\npollster.workspace = true"))
        (examples / "gpu.rs").write_text('''fn main() {
            for steps in [100, 0, 50, 1600] {
                let path = if steps == 100 { std::path::PathBuf::from(".") }
                    else { std::path::PathBuf::from(format!("aerosol-{steps}")) };
                std::fs::create_dir_all(&path).unwrap();
                pollster::block_on(sky_realtime::export_prime_gpu(&path, steps as f32 / 100.0)).unwrap();
            }
        }\n''')
        subprocess.run(["cargo", "run", "--release", "-p", "sky-realtime", "--example", "gpu"], cwd=work, check=True)
        pack_reference(work, fixtures, values)
        for steps in [0, 50, 1600]:
            pack_reference(work / f"aerosol-{steps}", fixtures / f"aerosol-{steps}", values)


if __name__ == "__main__":
    main()
