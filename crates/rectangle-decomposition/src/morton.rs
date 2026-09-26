//! 两个 8 位坐标的 Morton 编码。

pub fn encode(x: u8, y: u8) -> u64 {
    spread(x) | (spread(y) << 1)
}

fn spread(value: u8) -> u64 {
    let value = u64::from(value);
    let value = (value | (value << 4)) & 0x0f0f;
    let value = (value | (value << 2)) & 0x3333;
    (value | (value << 1)) & 0x5555
}

#[cfg(test)]
mod tests {
    use super::encode;

    #[test]
    fn encoding_matches_bit_interleaving() {
        for x in 0..=u8::MAX {
            for y in 0..=u8::MAX {
                let expected = (0..u8::BITS).fold(0u64, |code, bit| {
                    code | (u64::from((x >> bit) & 1) << (bit * 2))
                        | (u64::from((y >> bit) & 1) << (bit * 2 + 1))
                });
                assert_eq!(encode(x, y), expected);
            }
        }
    }
}
