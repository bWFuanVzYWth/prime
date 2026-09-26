//! 测试与 bench 共用输入；八类单位面来自 Prime 686f5fdc，另加混合 LOD。
//! 数量来自同源 334d078，仅作回归指纹；覆盖检查独立回填像素。

use std::num::NonZeroU16;

use crate::{QuadLeaf64, Rectangle};

#[derive(Clone, Copy, Debug)]
pub enum Case {
    Solid,
    Stripes,
    Checkerboard,
    Sparse,
    Dense,
    Holes,
    MaxChords,
    ManyLabels,
    MixedLod,
}

pub const CASES: [Case; 9] = [
    Case::Solid,
    Case::Stripes,
    Case::Checkerboard,
    Case::Sparse,
    Case::Dense,
    Case::Holes,
    Case::MaxChords,
    Case::ManyLabels,
    Case::MixedLod,
];

impl Case {
    pub const fn name(self) -> &'static str {
        match self {
            Self::Solid => "solid",
            Self::Stripes => "stripes",
            Self::Checkerboard => "checkerboard",
            Self::Sparse => "sparse",
            Self::Dense => "dense",
            Self::Holes => "holes",
            Self::MaxChords => "max_chords",
            Self::ManyLabels => "many_labels",
            Self::MixedLod => "mixed_lod",
        }
    }

    pub const fn count(self) -> usize {
        match self {
            Self::Solid => 1,
            Self::Stripes => 16,
            Self::Checkerboard => 4096,
            Self::Sparse => 213,
            Self::Dense => 448,
            Self::Holes => 521,
            Self::MaxChords => 1025,
            Self::ManyLabels => 768,
            Self::MixedLod => 6,
        }
    }

    pub fn leaves(self) -> Result<Vec<QuadLeaf64>, String> {
        if matches!(self, Self::MixedLod) {
            return [(0, 5), (32, 4), (48, 3), (56, 2), (60, 1), (62, 0)]
                .into_iter()
                .zip(1..=6)
                .map(|((u, lod), value)| {
                    Ok(QuadLeaf64 {
                        u,
                        v: 0,
                        lod,
                        value: NonZeroU16::new(value).ok_or("zero label")?,
                    })
                })
                .collect();
        }
        let mut cells = [0_u16; 4096];
        for (index, cell) in (0_u16..4096).zip(&mut cells) {
            let x = index % 64;
            let y = index / 64;
            let hash = (u32::from(index) + 1).wrapping_mul(0x9e37_79b9);
            let hash = (hash ^ (hash >> 16)).wrapping_mul(0x21f0_aaad);
            let hash = hash ^ (hash >> 15);
            *cell = match self {
                Self::Solid | Self::Holes => 1,
                Self::Stripes => 1 + x / 4,
                Self::Checkerboard => 1 + ((x + y) & 1),
                Self::Sparse => u16::from(hash.is_multiple_of(16)),
                Self::Dense => u16::from(!hash.is_multiple_of(8)),
                Self::MaxChords => u16::from(x & 1 != 0 || y & 1 != 0),
                Self::ManyLabels => {
                    if (x.is_multiple_of(4) && y.is_multiple_of(4)) || (x & 3 == 3 && y & 3 == 3) {
                        0
                    } else {
                        0x8000 + y / 4 * 16 + x / 4
                    }
                }
                Self::MixedLod => 0,
            };
        }
        if matches!(self, Self::Holes) {
            let mut holes = 0;
            let mut sample = 0_u32;
            while holes < 512 {
                let x = sample.reverse_bits() >> 26;
                let (mut numerator, mut denominator, mut value) = (0, 1, sample);
                while denominator < 729 {
                    numerator = numerator * 3 + value % 3;
                    denominator *= 3;
                    value /= 3;
                }
                let index = usize::try_from(numerator * 64 / denominator * 64 + x)
                    .map_err(|error| error.to_string())?;
                let cell = cells.get_mut(index).ok_or("hole outside grid")?;
                if *cell != 0 {
                    *cell = 0;
                    holes += 1;
                }
                sample += 1;
            }
        }
        let mut leaves = Vec::with_capacity(4096);
        for (row, v) in cells.as_chunks::<64>().0.iter().zip(0_u8..64) {
            for (&value, u) in row.iter().zip(0_u8..64) {
                if let Some(value) = NonZeroU16::new(value) {
                    leaves.push(QuadLeaf64 {
                        u,
                        v,
                        lod: 0,
                        value,
                    });
                }
            }
        }
        Ok(leaves)
    }
}

pub fn verify(leaves: &[QuadLeaf64], rectangles: &[Rectangle]) -> Result<(), String> {
    let mut expected = [0_u16; 4096];
    for leaf in leaves {
        let edge = 1_u8 << leaf.lod;
        paint(
            &mut expected,
            leaf.u..leaf.u + edge,
            leaf.v..leaf.v + edge,
            leaf.value.get(),
        )?;
    }
    let mut actual = [0_u16; 4096];
    for rect in rectangles {
        paint(&mut actual, rect.x.into(), rect.y.into(), rect.value)?;
    }
    if actual != expected {
        return Err("coverage or labels changed".into());
    }
    Ok(())
}

fn paint(
    cells: &mut [u16; 4096],
    x: std::ops::Range<u8>,
    y: std::ops::Range<u8>,
    value: u16,
) -> Result<(), String> {
    if x.is_empty() || y.is_empty() || x.end > 64 || y.end > 64 || value == 0 {
        return Err("invalid rectangle".into());
    }
    for v in y {
        for u in x.clone() {
            let cell = cells
                .get_mut(usize::from(v) * 64 + usize::from(u))
                .ok_or("pixel outside grid")?;
            if *cell != 0 {
                return Err("overlapping rectangles".into());
            }
            *cell = value;
        }
    }
    Ok(())
}
