//! Original reciprocal LUT selection. Decode only the chosen configuration, once per change.
use super::{Buffer, Context};
use ash::vk;
use std::sync::Arc;

pub(super) struct Pairing {
    pub buffer: Buffer,
    pub sizes: [u32; 5],
    count: u32,
    radius: u32,
}

impl Pairing {
    pub fn new(context: &Arc<Context>, count: u32, radius: u32) -> Result<Self, String> {
        let (sizes, source) = decode(count, radius)?;
        let expanded: Vec<u8> = source
            .as_chunks::<2>()
            .0
            .iter()
            .flat_map(|word| u32::from(u16::from_le_bytes(*word)).to_le_bytes())
            .collect();
        Ok(Self {
            buffer: Buffer::upload_device(
                context,
                &expanded,
                vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
            )?,
            sizes,
            count,
            radius,
        })
    }

    pub fn matches(&self, count: u32, radius: u32) -> bool {
        self.count == count && self.radius == radius
    }
}

fn decode(count: u32, radius: u32) -> Result<([u32; 5], Vec<u8>), String> {
    if count == 3 && radius == 30 {
        // RA-015: default profile retains the exact pre-existing payload and dimensions.
        return Ok((
            [254, 232, 196, 0, 0],
            prime_render_data::paired_neighbors().to_vec(),
        ));
    }
    let bank = prime_render_data::paired_neighbor_bank();
    if bank.get(..8) != Some(b"PRPN0001") || bank.len() < 16 + 50 * 48 {
        return Err("Invalid ReSTIR paired-neighbor bank".into());
    }
    let word = |offset: usize| u32::from_le_bytes(bank[offset..offset + 4].try_into().unwrap());
    for index in 0..50 {
        let entry = 16 + index * 48;
        if word(entry) != count || word(entry + 4) != radius {
            continue;
        }
        let sizes = std::array::from_fn(|i| word(entry + 8 + i * 4));
        let address = |offset| u64::from_le_bytes(bank[offset..offset + 8].try_into().unwrap());
        let offset =
            usize::try_from(address(entry + 32)).map_err(|_| "Paired LUT offset overflow")?;
        let length =
            usize::try_from(address(entry + 40)).map_err(|_| "Paired LUT size overflow")?;
        let packed = bank
            .get(
                offset
                    ..offset
                        .checked_add(length)
                        .ok_or("Paired LUT range overflow")?,
            )
            .ok_or("Paired LUT outside bank")?;
        let source = zstd::bulk::decompress(packed, count as usize * 256 * 256 * 2)
            .map_err(|failure| format!("Decode ReSTIR paired LUT: {failure}"))?;
        if source.len() != count as usize * 256 * 256 * 2 {
            return Err("Unexpected paired-neighbor payload size".into());
        }
        return Ok((sizes, source));
    }
    Err("Unsupported original ReSTIR paired-neighbor configuration".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn all_original_luts_preserve_mutual_inverse_and_default_bytes() {
        for count in 1..=5 {
            for radius in (5..=50).step_by(5) {
                let (sizes, source) = decode(count, radius).unwrap();
                for (index, &size) in sizes.iter().enumerate().take(count as usize) {
                    assert!((1..=256).contains(&size));
                    let value = |x: i32, y: i32| {
                        let x = x.rem_euclid(size as i32) as usize;
                        let y = y.rem_euclid(size as i32) as usize;
                        let start = 2 * (index * 256 * 256 + y * size as usize + x);
                        let packed =
                            u16::from_le_bytes(source[start..start + 2].try_into().unwrap());
                        ((packed & 255) as i32 - 128, (packed >> 8) as i32 - 128)
                    };
                    for y in 0..size as i32 {
                        for x in 0..size as i32 {
                            let (dx, dy) = value(x, y);
                            assert_eq!(value(x + dx, y + dy), (-dx, -dy));
                        }
                    }
                }
            }
        }
        assert_eq!(
            decode(3, 30).unwrap().1,
            prime_render_data::paired_neighbors()
        );
        assert!(decode(6, 30).is_err());
    }
}
