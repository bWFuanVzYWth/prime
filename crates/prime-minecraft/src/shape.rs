//! Immutable face profiles lowered once per resource epoch. No per-block shape allocation or join.
use crate::wire::Reader;

#[derive(Clone, Copy, Default, Debug, PartialEq, Eq, Hash)]
pub(crate) struct FaceId(pub u32);
#[derive(PartialEq)]
pub(crate) struct Face {
    u: Vec<f64>,
    v: Vec<f64>,
    cells: Vec<bool>,
    /// Height covered across the entire [0,1] width, starting at zero (fluid side tests).
    height: f64,
}
const EPS: f64 = 1e-7; // Shapes/IndexMerger coordinate tolerance in both supported hosts.
impl Face {
    pub fn from_typed(
        value: &prime_abi::PrimeMcFace,
        source: &prime_abi::minecraft::Resources<'_>,
    ) -> Result<(FaceId, Self), String> {
        use prime_abi::minecraft::range;
        let u = range(source.coordinates(), value.u)?;
        let v = range(source.coordinates(), value.v)?;
        if value.id < 2
            || value.reserved != 0
            || !(2..=4097).contains(&u.len())
            || !(2..=4097).contains(&v.len())
            || u.iter().chain(v).any(|p| !p.is_finite())
            || u.windows(2).chain(v.windows(2)).any(|p| p[0] >= p[1])
        {
            return Err("invalid face profile layout".into());
        }
        let count = (u.len() - 1) * (v.len() - 1);
        let words = range(source.words(), value.words)?;
        if words.len() > count.div_ceil(64) {
            return Err("invalid face profile words".into());
        }
        let mut cells = vec![false; count];
        for (w, &word) in words.iter().enumerate() {
            for bit in 0..64.min(count - w * 64) {
                cells[w * 64 + bit] = word & (1 << bit) != 0;
            }
        }
        let mut face = Self {
            u: u.to_vec(),
            v: v.to_vec(),
            cells,
            height: 0.,
        };
        face.height = face.covered_height();
        Ok((FaceId(value.id), face))
    }
    pub fn read(r: &mut Reader<'_>) -> Result<(FaceId, Self), String> {
        let id = FaceId(r.u32()?);
        let nu = r.u32()? as usize;
        let nv = r.u32()? as usize;
        let words = r.u32()? as usize;
        let count = nu
            .checked_sub(1)
            .and_then(|u| nv.checked_sub(1).and_then(|v| u.checked_mul(v)))
            .ok_or("invalid face grid")?;
        if id.0 < 2
            || !(2..=4097).contains(&nu)
            || !(2..=4097).contains(&nv)
            || words > count.div_ceil(64)
        {
            return Err("invalid face profile layout".into());
        }
        fn coords(r: &mut Reader<'_>, n: usize) -> Result<Vec<f64>, String> {
            let mut values = Vec::with_capacity(n);
            for _ in 0..n {
                values.push(r.f64()?);
            }
            if values.windows(2).any(|p| p[0] >= p[1]) {
                return Err("unordered face coordinates".into());
            }
            Ok(values)
        }
        let u = coords(r, nu)?;
        let v = coords(r, nv)?;
        let mut cells = vec![false; count];
        for w in 0..words {
            let word = r.u64()?;
            for bit in 0..64.min(count - w * 64) {
                cells[w * 64 + bit] = word & (1 << bit) != 0;
            }
        }
        let mut face = Self {
            u,
            v,
            cells,
            height: 0.,
        };
        face.height = face.covered_height();
        Ok((id, face))
    }
    fn filled(&self, u: usize, v: usize) -> bool {
        self.cells[u * (self.v.len() - 1) + v]
    }
    fn covers_rect(&self, u0: f64, v0: f64, u1: f64, v1: f64) -> bool {
        if self.u[0] > u0 + EPS
            || self.v[0] > v0 + EPS
            || *self.u.last().unwrap() < u1 - EPS
            || *self.v.last().unwrap() < v1 - EPS
        {
            return false;
        }
        for (u, x) in self.u.windows(2).enumerate() {
            if x[1] <= u0 + EPS || x[0] >= u1 - EPS {
                continue;
            }
            for (v, y) in self.v.windows(2).enumerate() {
                if y[1] > v0 + EPS && y[0] < v1 - EPS && !self.filled(u, v) {
                    return false;
                }
            }
        }
        true
    }
    pub fn covers(&self, source: &Self) -> bool {
        for (u, x) in source.u.windows(2).enumerate() {
            for (v, y) in source.v.windows(2).enumerate() {
                if source.filled(u, v) && !self.covers_rect(x[0], y[0], x[1], y[1]) {
                    return false;
                }
            }
        }
        true
    }
    fn covered_height(&self) -> f64 {
        if self.u[0] > EPS || *self.u.last().unwrap() < 1. - EPS || self.v[0] > EPS {
            return 0.;
        }
        let mut result = 1f64;
        for (u, x) in self.u.windows(2).enumerate() {
            if x[1] <= EPS || x[0] >= 1. - EPS {
                continue;
            }
            let mut height = 0f64;
            for (v, y) in self.v.windows(2).enumerate() {
                if y[1] <= EPS {
                    continue;
                }
                if !self.filled(u, v) {
                    break;
                }
                height = y[1];
            }
            result = result.min(height);
        }
        result
    }
    pub fn covers_height(&self, height: f32) -> bool {
        self.height + EPS >= f64::from(height)
    }
}
