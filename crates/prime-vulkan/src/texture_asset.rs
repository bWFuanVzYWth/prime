//! Narrow KTX2/Zstandard reader for Prime's immutable native GPU textures.
//! No transcoding, arrays, cubemaps, implicit mip generation or color conversion.
use ash::vk;
use std::mem::MaybeUninit;
use zstd::zstd_safe::{self, WriteBuf};

const IDENTIFIER: &[u8; 12] = b"\xabKTX 20\xbb\r\n\x1a\n";

#[derive(Clone, Copy)]
pub(super) struct TextureSpec {
    pub format: vk::Format,
    /// KTX dimensions: depth zero denotes 2D; positive depth denotes 3D.
    pub extent: [u32; 3],
    pub levels: u32,
    pub primaries: u8,
}

struct Level<'a> {
    compressed: &'a [u8],
    size: usize,
    extent: [u32; 3],
}

pub(super) struct TextureAsset<'a> {
    levels: Vec<Level<'a>>,
    metadata: Vec<(&'a str, &'a [u8])>,
}

fn u32_at(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let word = bytes
        .get(offset..offset + 4)
        .ok_or("Truncated KTX2 integer")?;
    Ok(u32::from_le_bytes(word.try_into().unwrap()))
}

fn u64_at(bytes: &[u8], offset: usize) -> Result<u64, String> {
    let word = bytes
        .get(offset..offset + 8)
        .ok_or("Truncated KTX2 integer")?;
    Ok(u64::from_le_bytes(word.try_into().unwrap()))
}

fn format_layout(format: vk::Format) -> Result<(u32, usize, u32), String> {
    match format {
        vk::Format::BC6H_UFLOAT_BLOCK => Ok((4, 16, 1)),
        vk::Format::R16G16B16A16_SFLOAT => Ok((1, 8, 2)),
        vk::Format::R32G32B32A32_SFLOAT => Ok((1, 16, 4)),
        _ => Err("Unsupported fixed KTX2 GPU format".into()),
    }
}

fn check_dfd(bytes: &[u8], spec: TextureSpec, texel_bytes: usize) -> Result<(), String> {
    let compressed = spec.format == vk::Format::BC6H_UFLOAT_BLOCK;
    let samples = if compressed { 1 } else { 4 };
    let size = 28 + samples * 16;
    if bytes.len() != size
        || u32_at(bytes, 0)? != size as u32
        || u32_at(bytes, 4)? != 0 // Khronos basic format, no vendor extension.
        || u32_at(bytes, 8)? != 2 | (((size - 4) as u32) << 16)
        || bytes[12] != if compressed { 133 } else { 1 }
        || bytes[13] != spec.primaries
        || bytes[14] != 1 // Linear transfer; no shader-side container conversion.
        || bytes[15] != 0 // Straight alpha.
        || bytes[16..20] != if compressed { [3, 3, 0, 0] } else { [0; 4] }
        || bytes[20] != texel_bytes as u8
        || bytes[21..28] != [0; 7]
    {
        return Err("KTX2 DFD does not match the fixed GPU format/color contract".into());
    }
    for sample in 0..samples {
        let offset = 28 + sample * 16;
        let bits = if compressed { 128 } else { texel_bytes * 2 };
        let channel = if compressed {
            0x80
        } else {
            0xc0 | [0, 1, 2, 15][sample]
        };
        let header =
            ((sample * bits) as u32) | (((bits - 1) as u32) << 16) | ((channel as u32) << 24);
        if u32_at(bytes, offset)? != header
            || u32_at(bytes, offset + 4)? != 0
            || u32_at(bytes, offset + 8)? != if compressed { 0 } else { (-1.0f32).to_bits() }
            || u32_at(bytes, offset + 12)? != 1.0f32.to_bits()
        {
            return Err("KTX2 DFD sample layout does not match the fixed GPU format".into());
        }
    }
    Ok(())
}

impl<'a> TextureAsset<'a> {
    pub fn parse(bytes: &'a [u8], spec: TextureSpec) -> Result<Self, String> {
        let (block, texel_bytes, type_size) = format_layout(spec.format)?;
        let maximum_levels = 32 - spec.extent.into_iter().max().unwrap().leading_zeros();
        if spec.extent[0] == 0
            || spec.extent[1] == 0
            || spec.levels == 0
            || spec.levels > maximum_levels
            || bytes.get(..12) != Some(IDENTIFIER)
            || u32_at(bytes, 12)? != spec.format.as_raw() as u32
            || u32_at(bytes, 16)? != type_size
            || [u32_at(bytes, 20)?, u32_at(bytes, 24)?, u32_at(bytes, 28)?] != spec.extent
            || u32_at(bytes, 32)? != 0
            || u32_at(bytes, 36)? != 1
            || u32_at(bytes, 40)? != spec.levels
            || u32_at(bytes, 44)? != 2
            || u64_at(bytes, 64)? != 0
            || u64_at(bytes, 72)? != 0
        {
            return Err("KTX2 header does not match the fixed texture contract".into());
        }
        let index_end = 80 + spec.levels as usize * 24;
        let dfd_offset = u32_at(bytes, 48)? as usize;
        let dfd_size = u32_at(bytes, 52)? as usize;
        let dfd_end = dfd_offset
            .checked_add(dfd_size)
            .ok_or("KTX2 DFD range overflow")?;
        if dfd_offset != index_end {
            return Err("Unsupported KTX2 descriptor placement".into());
        }
        check_dfd(
            bytes.get(dfd_offset..dfd_end).ok_or("Truncated KTX2 DFD")?,
            spec,
            texel_bytes,
        )?;
        let kvd_offset = u32_at(bytes, 56)? as usize;
        let kvd_size = u32_at(bytes, 60)? as usize;
        let mut metadata = Vec::new();
        let mut data_start = dfd_end;
        if kvd_size == 0 {
            if kvd_offset != 0 {
                return Err("Empty KTX2 metadata has a nonzero offset".into());
            }
        } else {
            if kvd_offset != dfd_end || !kvd_size.is_multiple_of(4) {
                return Err("Invalid KTX2 metadata placement".into());
            }
            data_start = kvd_offset
                .checked_add(kvd_size)
                .ok_or("KTX2 metadata range overflow")?;
            let kvd = bytes
                .get(kvd_offset..data_start)
                .ok_or("Truncated KTX2 metadata")?;
            let mut offset = 0;
            let mut previous = "";
            while offset < kvd.len() {
                let size = u32_at(kvd, offset)? as usize;
                offset += 4;
                let end = offset
                    .checked_add(size)
                    .ok_or("KTX2 metadata entry overflow")?;
                let entry = kvd
                    .get(offset..end)
                    .ok_or("Truncated KTX2 metadata entry")?;
                let key_end = entry
                    .iter()
                    .position(|&b| b == 0)
                    .ok_or("KTX2 key lacks NUL")?;
                let key = std::str::from_utf8(&entry[..key_end]).map_err(|e| e.to_string())?;
                if key.is_empty() || key.starts_with('\u{feff}') || key <= previous {
                    return Err("KTX2 keys are empty, repeated or not sorted".into());
                }
                previous = key;
                metadata.push((key, &entry[key_end + 1..]));
                let aligned = end.checked_add(3).ok_or("KTX2 padding range overflow")? & !3;
                if kvd
                    .get(end..aligned)
                    .is_none_or(|padding| padding.iter().any(|&b| b != 0))
                {
                    return Err("Invalid KTX2 metadata padding".into());
                }
                offset = aligned;
            }
        }
        let mut levels = Vec::with_capacity(spec.levels as usize);
        for level in 0..spec.levels as usize {
            let offset = 80 + level * 24;
            let start = usize::try_from(u64_at(bytes, offset)?).map_err(|e| e.to_string())?;
            let length = usize::try_from(u64_at(bytes, offset + 8)?).map_err(|e| e.to_string())?;
            let end = start
                .checked_add(length)
                .ok_or("KTX2 level range overflow")?;
            let extent = spec.extent.map(|size| (size >> level).max(1));
            let size = (extent[0].div_ceil(block) as usize)
                .checked_mul(extent[1].div_ceil(block) as usize)
                .and_then(|size| size.checked_mul(extent[2] as usize))
                .and_then(|size| size.checked_mul(texel_bytes))
                .ok_or("KTX2 mip byte count overflow")?;
            if length == 0 || u64_at(bytes, offset + 16)? != size as u64 {
                return Err("KTX2 mip byte count does not match the GPU extent".into());
            }
            levels.push(Level {
                compressed: bytes.get(start..end).ok_or("KTX2 mip exceeds the file")?,
                size,
                extent,
            });
        }
        // Scheme 2 uses alignment 1. Physical images run smallest mip to largest, with
        // no unindexed gaps/overlap/trailing data; index entries run largest to smallest.
        let mut cursor = data_start;
        for level in (0..spec.levels as usize).rev() {
            if u64_at(bytes, 80 + level * 24)? != cursor as u64 {
                return Err("KTX2 mip layout overlaps, has gaps or is out of order".into());
            }
            cursor = cursor
                .checked_add(levels[level].compressed.len())
                .ok_or("KTX2 size overflow")?;
        }
        if cursor != bytes.len() {
            return Err("KTX2 contains trailing data".into());
        }
        let result = Self { levels, metadata };
        let orientation = if spec.extent[2] == 0 { "rd" } else { "rdi" };
        if result.metadata_text("KTXorientation")? != orientation {
            return Err("Unsupported KTX2 texture orientation".into());
        }
        if let Some(value) = result.metadata_value("KTXswizzle") {
            if text_value(value)? != "rgba" {
                return Err("Unsupported KTX2 texture swizzle".into());
            }
        }
        Ok(result)
    }

    pub fn level_size(&self, level: usize) -> usize {
        self.levels[level].size
    }

    pub fn level_extent(&self, level: usize) -> [u32; 3] {
        self.levels[level].extent
    }

    pub fn decode_level_into(
        &self,
        level: usize,
        destination: &mut [MaybeUninit<u8>],
    ) -> Result<(), String> {
        let source = self
            .levels
            .get(level)
            .ok_or("KTX2 mip index exceeds the asset")?;
        if destination.len() != source.size {
            return Err("KTX2 decode destination does not match the mip extent".into());
        }
        decode_zstd_into(source.compressed, destination)
    }

    fn metadata_value(&self, key: &str) -> Option<&'a [u8]> {
        self.metadata
            .binary_search_by_key(&key, |&(name, _)| name)
            .ok()
            .map(|index| self.metadata[index].1)
    }

    pub fn metadata_text(&self, key: &str) -> Result<&'a str, String> {
        text_value(
            self.metadata_value(key)
                .ok_or_else(|| format!("Missing KTX2 metadata {key}"))?,
        )
    }
}

fn text_value(bytes: &[u8]) -> Result<&str, String> {
    let bytes = bytes.strip_suffix(&[0]).unwrap_or(bytes);
    if bytes.contains(&0) {
        return Err("KTX2 text metadata contains an embedded NUL".into());
    }
    std::str::from_utf8(bytes).map_err(|e| e.to_string())
}

struct UninitializedDestination<'a> {
    bytes: &'a mut [MaybeUninit<u8>],
    initialized: usize,
}

// SAFETY: Capacity covers exclusive writable bytes. No uninitialized byte is exposed by
// as_slice; only zstd's successful write_from callback advances the initialized prefix.
unsafe impl WriteBuf for UninitializedDestination<'_> {
    fn as_slice(&self) -> &[u8] {
        // SAFETY: filled_until is called only after zstd initialized this prefix.
        unsafe { std::slice::from_raw_parts(self.bytes.as_ptr().cast(), self.initialized) }
    }
    fn capacity(&self) -> usize {
        self.bytes.len()
    }
    fn as_mut_ptr(&mut self) -> *mut u8 {
        self.bytes.as_mut_ptr().cast()
    }
    unsafe fn filled_until(&mut self, n: usize) {
        self.initialized = n;
    }
}

/// Decode exactly one frame directly into exclusively owned uninitialized storage.
/// Success initializes every destination byte; failure must not publish that storage.
pub(super) fn decode_zstd_into(
    bytes: &[u8],
    destination: &mut [MaybeUninit<u8>],
) -> Result<(), String> {
    if bytes.get(..4) != Some(&[0x28, 0xb5, 0x2f, 0xfd])
        || zstd_safe::find_frame_compressed_size(bytes).map_err(zstd_error)? != bytes.len()
        || zstd_safe::get_dict_id_from_frame(bytes).is_some()
    {
        return Err("Asset must contain exactly one dictionary-free Zstd frame".into());
    }
    if zstd_safe::get_frame_content_size(bytes)
        .map_err(|e| e.to_string())?
        .is_some_and(|size| size != destination.len() as u64)
    {
        return Err("Zstd frame size does not match the expected asset".into());
    }
    let mut context = zstd_safe::DCtx::try_create().ok_or("Create asset Zstd decoder")?;
    let mut output = UninitializedDestination {
        bytes: destination,
        initialized: 0,
    };
    let size = context.decompress(&mut output, bytes).map_err(zstd_error)?;
    if size != output.capacity() {
        return Err("Decoded asset length does not match its extent".into());
    }
    Ok(())
}

fn zstd_error(code: usize) -> String {
    format!("Asset Zstd decode: {}", zstd_safe::get_error_name(code))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn compress(bytes: &[u8]) -> Vec<u8> {
        let mut encoder = zstd_safe::CCtx::create();
        encoder
            .set_parameter(zstd_safe::CParameter::ChecksumFlag(true))
            .unwrap();
        encoder
            .set_parameter(zstd_safe::CParameter::CompressionLevel(3))
            .unwrap();
        let mut output = Vec::with_capacity(zstd_safe::compress_bound(bytes.len()));
        encoder.compress2(&mut output, bytes).unwrap();
        output
    }

    fn fixture(spec: TextureSpec) -> (Vec<u8>, Vec<Vec<u8>>) {
        let compressed = spec.format == vk::Format::BC6H_UFLOAT_BLOCK;
        let (block, bpp, type_size) = format_layout(spec.format).unwrap();
        let mut dfd = vec![0u8; if compressed { 44 } else { 92 }];
        let dfd_size = dfd.len();
        dfd[..4].copy_from_slice(&(dfd_size as u32).to_le_bytes());
        dfd[8..12].copy_from_slice(&(2 | (((dfd_size - 4) as u32) << 16)).to_le_bytes());
        dfd[12..16].copy_from_slice(&[if compressed { 133 } else { 1 }, spec.primaries, 1, 0]);
        if compressed {
            dfd[16..20].copy_from_slice(&[3, 3, 0, 0]);
        }
        dfd[20] = bpp as u8;
        for sample in 0..if compressed { 1 } else { 4 } {
            let offset = 28 + sample * 16;
            let bits = if compressed { 128 } else { bpp * 2 };
            dfd[offset..offset + 2].copy_from_slice(&((sample * bits) as u16).to_le_bytes());
            dfd[offset + 2] = (bits - 1) as u8;
            dfd[offset + 3] = if compressed {
                0x80
            } else {
                0xc0 | [0, 1, 2, 15][sample]
            };
            dfd[offset + 8..offset + 12]
                .copy_from_slice(&if compressed { 0u32 } else { (-1f32).to_bits() }.to_le_bytes());
            dfd[offset + 12..offset + 16].copy_from_slice(&1f32.to_bits().to_le_bytes());
        }
        let mut kvd = Vec::new();
        for (key, value) in [
            (
                "KTXorientation",
                if spec.extent[2] == 0 { "rd" } else { "rdi" },
            ),
            ("schema", "fixture"),
        ] {
            kvd.extend_from_slice(&((key.len() + value.len() + 2) as u32).to_le_bytes());
            kvd.extend_from_slice(key.as_bytes());
            kvd.push(0);
            kvd.extend_from_slice(value.as_bytes());
            kvd.push(0);
            while !kvd.len().is_multiple_of(4) {
                kvd.push(0);
            }
        }
        let dfd_offset = 80 + spec.levels as usize * 24;
        let mut output = vec![0u8; dfd_offset];
        output[..12].copy_from_slice(IDENTIFIER);
        for (offset, word) in [
            (12, spec.format.as_raw() as u32),
            (16, type_size),
            (20, spec.extent[0]),
            (24, spec.extent[1]),
            (28, spec.extent[2]),
            (36, 1),
            (40, spec.levels),
            (44, 2),
            (48, dfd_offset as u32),
            (52, dfd.len() as u32),
            (56, (dfd_offset + dfd.len()) as u32),
            (60, kvd.len() as u32),
        ] {
            output[offset..offset + 4].copy_from_slice(&word.to_le_bytes());
        }
        output.extend_from_slice(&dfd);
        output.extend_from_slice(&kvd);
        let payloads: Vec<Vec<u8>> = (0..spec.levels)
            .map(|level| {
                let [w, h, d] = spec.extent.map(|size| (size >> level).max(1));
                let size =
                    w.div_ceil(block) as usize * h.div_ceil(block) as usize * d as usize * bpp;
                (0..size)
                    .map(|index| (index as u8).wrapping_add(level as u8))
                    .collect()
            })
            .collect();
        for level in (0..spec.levels as usize).rev() {
            let frame = compress(&payloads[level]);
            let index = 80 + level * 24;
            let position = output.len() as u64;
            output[index..index + 8].copy_from_slice(&position.to_le_bytes());
            output[index + 8..index + 16].copy_from_slice(&(frame.len() as u64).to_le_bytes());
            output[index + 16..index + 24]
                .copy_from_slice(&(payloads[level].len() as u64).to_le_bytes());
            output.extend_from_slice(&frame);
        }
        (output, payloads)
    }

    fn spec() -> TextureSpec {
        TextureSpec {
            format: vk::Format::BC6H_UFLOAT_BLOCK,
            extent: [4, 4, 0],
            levels: 3,
            primaries: 4,
        }
    }

    fn decoded(asset: &TextureAsset<'_>, level: usize) -> Result<Vec<u8>, String> {
        let size = asset.level_size(level);
        let mut output = Vec::with_capacity(size);
        asset.decode_level_into(level, &mut output.spare_capacity_mut()[..size])?;
        // SAFETY: Successful exact-length decode initialized this entire prefix.
        unsafe { output.set_len(size) };
        Ok(output)
    }

    #[test]
    fn native_formats_preserve_exact_2d_3d_mip_payloads_without_color_conversion() {
        for spec in [
            spec(),
            TextureSpec {
                format: vk::Format::R16G16B16A16_SFLOAT,
                extent: [2, 2, 2],
                levels: 2,
                primaries: 0,
            },
            TextureSpec {
                format: vk::Format::R32G32B32A32_SFLOAT,
                extent: [2, 3, 0],
                levels: 1,
                primaries: 0,
            },
        ] {
            let (bytes, payloads) = fixture(spec);
            let asset = TextureAsset::parse(&bytes, spec).unwrap();
            assert_eq!(asset.metadata_text("schema").unwrap(), "fixture");
            for (level, expected) in payloads.iter().enumerate() {
                assert_eq!(
                    asset.level_extent(level),
                    spec.extent.map(|size| (size >> level).max(1))
                );
                assert_eq!(decoded(&asset, level).unwrap(), *expected);
            }
        }
    }

    #[test]
    fn malformed_header_dfd_index_and_trailing_bytes_are_rejected() {
        let (bytes, _) = fixture(spec());
        for offset in [
            0, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 64, 72, 80, 88, 96, 104, 112,
            120,
        ] {
            let mut bad = bytes.clone();
            bad[offset] ^= 1;
            assert!(
                TextureAsset::parse(&bad, spec()).is_err(),
                "header/index offset={offset}"
            );
        }
        let dfd = u32_at(&bytes, 48).unwrap() as usize;
        for offset in 0..44 {
            let mut bad = bytes.clone();
            bad[dfd + offset] ^= 1;
            assert!(
                TextureAsset::parse(&bad, spec()).is_err(),
                "DFD byte={offset}"
            );
        }
        assert!(TextureAsset::parse(&bytes[..79], spec()).is_err());
        assert!(TextureAsset::parse(&bytes[..bytes.len() - 1], spec()).is_err());
        let mut bad = bytes.clone();
        bad.push(0);
        assert!(TextureAsset::parse(&bad, spec()).is_err());
        let mut expected = spec();
        expected.primaries = 0;
        assert!(TextureAsset::parse(&bytes, expected).is_err());
        expected.format = vk::Format::R8G8B8A8_UNORM;
        assert!(TextureAsset::parse(&bytes, expected).is_err());
    }

    #[test]
    fn invalid_metadata_orientation_padding_and_key_are_rejected() {
        let (bytes, _) = fixture(spec());
        let kvd = u32_at(&bytes, 56).unwrap() as usize;
        for (offset, value) in [
            (kvd, 255),
            (kvd + 4, 0xff),
            (kvd + 18, 1),
            (kvd + 19, b'l'),
            (kvd + 22, 1),
        ] {
            let mut bad = bytes.clone();
            bad[offset] = value;
            assert!(
                TextureAsset::parse(&bad, spec()).is_err(),
                "metadata offset={offset}"
            );
        }
        // The second key must sort after the first; no normalization/reordering is allowed.
        let mut bad = bytes.clone();
        bad[kvd + 28] = b'A';
        assert!(TextureAsset::parse(&bad, spec()).is_err());
        // Insert a repeated whole key/value record and adjust the otherwise valid indices.
        let first_record = 24;
        let mut bad = bytes[..kvd + first_record].to_vec();
        bad.extend_from_slice(&bytes[kvd..kvd + first_record]);
        bad.extend_from_slice(&bytes[kvd + first_record..]);
        let metadata_size = u32_at(&bytes, 60).unwrap() + first_record as u32;
        bad[60..64].copy_from_slice(&metadata_size.to_le_bytes());
        for level in 0..3 {
            let offset = 80 + level * 24;
            let position = u64_at(&bytes, offset).unwrap() + first_record as u64;
            bad[offset..offset + 8].copy_from_slice(&position.to_le_bytes());
        }
        assert!(TextureAsset::parse(&bad, spec()).is_err());
        assert!(text_value(b"a\0b\0").is_err());
        assert_eq!(text_value(b"text").unwrap(), "text");
        assert_eq!(text_value(b"text\0").unwrap(), "text");
    }

    #[test]
    fn zstd_corruption_length_tail_frame_and_destination_mismatch_are_rejected() {
        let frame = compress(&[1, 2, 3, 4]);
        let mut output = [MaybeUninit::uninit(); 4];
        decode_zstd_into(&frame, &mut output).unwrap();
        let mut bad = frame.clone();
        *bad.last_mut().unwrap() ^= 1;
        assert!(decode_zstd_into(&bad, &mut output).is_err());
        assert!(decode_zstd_into(&frame[..frame.len() - 1], &mut output).is_err());
        let mut bad = frame.clone();
        bad.extend_from_slice(&compress(&[]));
        assert!(decode_zstd_into(&bad, &mut output).is_err());
        bad = frame.clone();
        bad.push(0);
        assert!(decode_zstd_into(&bad, &mut output).is_err());
        assert!(decode_zstd_into(&frame, &mut [MaybeUninit::uninit(); 3]).is_err());
        assert!(decode_zstd_into(&frame, &mut [MaybeUninit::uninit(); 5]).is_err());
        let (bytes, _) = fixture(spec());
        let asset = TextureAsset::parse(&bytes, spec()).unwrap();
        assert!(asset.decode_level_into(0, &mut output).is_err());
        assert!(asset.decode_level_into(3, &mut output).is_err());
    }
}
