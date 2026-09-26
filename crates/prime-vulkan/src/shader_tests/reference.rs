//! Independent u64/top-down Sobol oracle (from the author's z_sobol), scalar
//! direction recurrence instead of the production shader's packed permutation/butterfly.
pub fn hash(mut v: u32) -> u32 {
    v ^= v >> 16;
    v = v.wrapping_mul(0x7feb352d);
    v ^= v >> 15;
    v = v.wrapping_mul(0x846ca68b);
    v ^ (v >> 16)
}

// FastOwen from PBRT-v4, Apache-2.0; see THIRD_PARTY_NOTICES.md.
fn owen(value: u32, seed: u32) -> u32 {
    let mut v = value.reverse_bits();
    v ^= v.wrapping_mul(0x3d20adea);
    v = v.wrapping_add(seed);
    v = v.wrapping_mul((seed >> 16) | 1);
    v ^= v.wrapping_mul(0x05526c56);
    v ^= v.wrapping_mul(0x53a22864);
    v.reverse_bits()
}

pub fn sobol(&[x, y, sample, r, s, seed, domain, _]: &[u32; 8]) -> [u32; 2] {
    let mut morton = 0u64;
    for i in 0..r {
        morton |= u64::from((x >> i) & 1) << (2 * i);
        morton |= u64::from((y >> i) & 1) << (2 * i + 1);
    }
    morton = (morton << s) | u64::from(sample);
    let key = hash(domain ^ seed);
    let node_hash = |prefix: u64, shift: u32| {
        hash(
            prefix as u32
                ^ ((prefix >> 32) as u32).wrapping_mul(0x9e3779b9)
                ^ key
                ^ shift.wrapping_mul(0x632be59b),
        )
    };
    let mut index = 0;
    let odd = s & 1;
    let mut shift = 2 * r + s;
    while shift > odd {
        shift -= 2;
        let h = node_hash(morton >> (shift + 2), shift);
        let basis = [(1, 2), (1, 3), (2, 1), (2, 3), (3, 1), (3, 2)];
        let (a, b) = basis[(((h >> 8) * 6) >> 24) as usize];
        let digit = [0u32, a, b, a ^ b][((morton >> shift) & 3) as usize] ^ (h & 3);
        index |= u64::from(digit) << shift;
    }
    if odd != 0 {
        index |= (morton & 1) ^ u64::from(node_hash(morton >> 1, 0) & 1);
    }
    let mut out = [0, 0];
    let mut direction = 0x80000000u32;
    for bit in 0..52 {
        if index & (1u64 << bit) != 0 {
            if bit < 32 {
                out[0] ^= 0x80000000 >> bit;
            }
            out[1] ^= direction;
        }
        direction ^= direction >> 1;
    }
    [
        owen(out[0], hash(key ^ 0xa511e9b3)),
        owen(out[1], hash(key ^ 0x63d83595)),
    ]
}

pub fn decode(v: f64) -> f64 {
    if v <= 0.04045 {
        v / 12.92
    } else {
        ((v + 0.055) / 1.055).powf(2.4)
    }
}
pub fn encode(v: f64) -> f64 {
    if v <= 0.0031308 {
        v * 12.92
    } else {
        1.055 * v.powf(1.0 / 2.4) - 0.055
    }
}
pub fn dot(a: [f64; 3], b: [f64; 3]) -> f64 {
    a.into_iter().zip(b).map(|(a, b)| a * b).sum()
}
pub fn sub(a: [f64; 3], b: [f64; 3]) -> [f64; 3] {
    std::array::from_fn(|i| a[i] - b[i])
}
pub fn cross(a: [f64; 3], b: [f64; 3]) -> [f64; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}
pub fn normalized(v: [f64; 3]) -> [f64; 3] {
    let length = dot(v, v).sqrt();
    v.map(|v| v / length)
}
pub fn transform(m: [[f32; 4]; 3], p: [f64; 3]) -> [f64; 3] {
    m.map(|r| {
        f64::from(r[0]) * p[0] + f64::from(r[1]) * p[1] + f64::from(r[2]) * p[2] + f64::from(r[3])
    })
}
pub fn inverse(m: [[f32; 4]; 3]) -> [[f32; 4]; 3] {
    let mut a: [[f64; 6]; 3] = std::array::from_fn(|i| {
        std::array::from_fn(|j| {
            if j < 3 {
                f64::from(m[i][j])
            } else {
                f64::from(i == j - 3)
            }
        })
    });
    for col in 0..3 {
        let pivot = a[col][col];
        a[col] = a[col].map(|v| v / pivot);
        for row in 0..3 {
            if row != col {
                let factor = a[row][col];
                for j in 0..6 {
                    a[row][j] -= factor * a[col][j];
                }
            }
        }
    }
    std::array::from_fn(|i| {
        [
            a[i][3] as f32,
            a[i][4] as f32,
            a[i][5] as f32,
            (-dot(a[i][3..6].try_into().unwrap(), m.map(|r| f64::from(r[3])))) as f32,
        ]
    })
}
