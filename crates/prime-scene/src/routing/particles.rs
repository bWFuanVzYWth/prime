use super::{EMPTY_TRIANGLE, SourceRoutes};
use crate::{protocol::Reader, scene::Triangle, workers::CpuWorkers};

struct Billboard {
    center: [f32; 3],
    axes: [[f32; 2]; 3],
    scale: f32,
    uv: [f32; 4],
    color: [f32; 4],
}

impl SourceRoutes {
    pub(crate) fn billboards(
        &mut self,
        raw: &[u8],
        texture_id: u32,
        flags: u32,
        triangles: &mut Vec<Triangle>,
        bounds: &mut [[f32; 3]; 2],
    ) -> Result<(), String> {
        if raw.is_empty() {
            return Ok(());
        }
        if self.workers.is_none() {
            self.workers = Some(CpuWorkers::configured()?);
        }
        let start = triangles.len();
        triangles.resize(start + raw.len() / 52 * 2, EMPTY_TRIANGLE);
        self.workers.as_ref().unwrap().chunks_mut(
            &mut triangles[start..],
            2048,
            |first, out| {
                for (i, pair) in out.as_chunks_mut::<2>().0.iter_mut().enumerate() {
                    let index = first / 2 + i;
                    let source = Billboard::read(&raw[index * 52..][..52])?;
                    for (triangle, corners) in pair.iter_mut().zip([[0, 1, 2], [2, 3, 0]]) {
                        triangle.texture_id = texture_id;
                        triangle.flags = flags;
                        for (to, corner) in corners.into_iter().enumerate() {
                            let [x, y] =
                                [[1.0, -1.0], [1.0, 1.0], [-1.0, 1.0], [-1.0, -1.0]][corner];
                            triangle.positions[to] = std::array::from_fn(|axis| {
                                (source.axes[axis][0] * x + source.axes[axis][1] * y) * source.scale
                                    + source.center[axis]
                            });
                            if triangle.positions[to].iter().any(|v| !v.is_finite()) {
                                return Err("billboard position overflow".into());
                            }
                            triangle.colors[to] = source.color;
                            triangle.uvs[to] = [
                                source.uv[usize::from(x > 0.0)],
                                source.uv[2 + usize::from(y < 0.0)],
                            ];
                        }
                    }
                }
                Ok(())
            },
        )?;
        for triangle in &triangles[start..] {
            for position in triangle.positions {
                for axis in 0..3 {
                    bounds[0][axis] = bounds[0][axis].min(position[axis]);
                    bounds[1][axis] = bounds[1][axis].max(position[axis]);
                }
            }
        }
        Ok(())
    }
}

impl Billboard {
    fn read(raw: &[u8]) -> Result<Self, String> {
        let mut input = Reader {
            data: raw,
            offset: 0,
        };
        let center = input.vector()?;
        let [x, y, z] = input.vector()?;
        let w = input.f32()?;
        let scale = input.f32()?;
        let uv = [input.f32()?, input.f32()?, input.f32()?, input.f32()?];
        let rgba: [u8; 4] = input.take(4)?.try_into().unwrap();
        let (xx, yy, zz, ww) = (x * x, y * y, z * z, w * w);
        let k = 1.0 / (xx + yy + zz + ww);
        if !k.is_finite() || k <= 0.0 {
            return Err("invalid billboard rotation".into());
        }
        // A quaternion need not be normalized. This is the host's q*v*q^-1 semantics.
        let axes = [
            [(xx - yy - zz + ww) * k, 2.0 * (x * y - z * w) * k],
            [2.0 * (x * y + z * w) * k, (yy - xx - zz + ww) * k],
            [2.0 * (x * z - y * w) * k, 2.0 * (y * z + x * w) * k],
        ];
        Ok(Self {
            center,
            axes,
            scale,
            uv,
            color: rgba.map(|v| f32::from(v) / 255.0),
        })
    }
}
