//! Immutable host placement declarations. Per-position evaluation never calls the host.
#[cfg(test)]
use crate::wire::Reader;

#[derive(Clone, Copy, Default, PartialEq)]
pub(crate) struct Placement {
    pub offset: u32,
    pub horizontal: f32,
    pub vertical: f32,
    pub seed: u32,
}

impl Placement {
    pub fn from_typed(
        value: prime_abi::PrimeMcPlacement,
        has_offset: bool,
    ) -> Result<Self, String> {
        prime_abi::minecraft::finite(&[value.horizontal, value.vertical])?;
        if value.offset > 3
            || value.seed > 6
            || has_offset != (value.offset != 0)
            || value.horizontal < 0.
            || value.vertical < 0.
            || (matches!(value.offset, 0 | 3) && (value.horizontal != 0. || value.vertical != 0.))
            || (value.offset == 1 && value.vertical != 0.)
        {
            return Err("invalid source placement declaration".into());
        }
        Ok(Self {
            offset: value.offset,
            horizontal: value.horizontal,
            vertical: value.vertical,
            seed: value.seed,
        })
    }
    pub const NONE: Self = Self {
        offset: 0,
        horizontal: 0.0,
        vertical: 0.0,
        seed: 0,
    };

    #[cfg(test)]
    pub fn read(input: &mut Reader<'_>, has_offset: bool) -> Result<Self, String> {
        let value = Self {
            offset: input.u32()?,
            horizontal: input.f32()?,
            vertical: input.f32()?,
            seed: input.u32()?,
        };
        if value.offset > 3
            || value.seed > 6
            || has_offset != (value.offset != 0)
            || value.horizontal < 0.0
            || value.vertical < 0.0
            || (matches!(value.offset, 0 | 3) && (value.horizontal != 0.0 || value.vertical != 0.0))
            || (value.offset == 1 && value.vertical != 0.0)
        {
            return Err("invalid source placement declaration".into());
        }
        Ok(value)
    }

    pub fn seed(self, mut position: [i32; 3]) -> i64 {
        // Bed foot states use the head position; doors and double plants use their lower half.
        let (axis, shift) = match self.seed {
            1 => (1, -1),
            2 => (2, -1),
            3 => (2, 1),
            4 => (0, -1),
            5 => (0, 1),
            _ => (0, 0),
        };
        position[axis] = position[axis].wrapping_add(shift);
        crate::model::position_seed(position[0], position[1], position[2])
    }

    pub fn offset(self, position: [i32; 3]) -> [f64; 3] {
        if self.offset == 0 {
            return [0.0; 3];
        }
        let seed = crate::model::position_seed(position[0], 0, position[2]);
        if self.offset == 3 {
            // Explicit unsupported-source fallback: retain the previous prototype's XZ rule.
            return [
                ((seed & 15) as f64 / 15.0 - 0.5) * 0.5,
                0.0,
                (((seed >> 8) & 15) as f64 / 15.0 - 0.5) * 0.5,
            ];
        }
        // The actual host performs f32 division before converting to double, including XYZ.y.
        let nibble = |shift: u32| f64::from(((seed >> shift) & 15i64) as f32 / 15.0);
        let horizontal = f64::from(self.horizontal);
        [
            ((nibble(0) - 0.5) * 0.5).clamp(-horizontal, horizontal),
            if self.offset == 2 {
                (nibble(4) - 1.0) * f64::from(self.vertical)
            } else {
                0.0
            },
            ((nibble(8) - 0.5) * 0.5).clamp(-horizontal, horizontal),
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::u32_to;

    fn record(words: [u32; 4], has_offset: bool) -> Result<Placement, String> {
        let mut bytes = Vec::new();
        for word in words {
            u32_to(&mut bytes, word);
        }
        let pages = [bytes.as_slice()];
        let mut input = Reader::new(&pages)?;
        Placement::read(&mut input, has_offset)
    }

    #[test]
    fn placement_declarations_reject_inconsistent_flags_and_nonfinite_or_unused_values() {
        assert!(record([0, 0, 0, 0], false).is_ok());
        assert!(record([1, 0.125f32.to_bits(), 0, 5], true).is_ok());
        assert!(record([2, 0.25f32.to_bits(), 0.1f32.to_bits(), 1], true).is_ok());
        assert!(record([3, 0, 0, 6], true).is_ok());
        for words in [
            [4, 0, 0, 0],
            [0, 0, 0, 7],
            [0, 1, 0, 0],
            [1, 0.25f32.to_bits(), 1, 0],
            [1, f32::NAN.to_bits(), 0, 0],
            [2, 0.25f32.to_bits(), f32::INFINITY.to_bits(), 0],
            [2, (-0.25f32).to_bits(), 0, 0],
            [3, 1, 0, 0],
        ] {
            assert!(record(words, words[0] != 0).is_err(), "{words:?}");
        }
        assert!(record([0, 0, 0, 0], true).is_err());
        assert!(record([1, 0.25f32.to_bits(), 0, 0], false).is_err());
        let pages = [[0u8; 12].as_slice()];
        let mut input = Reader::new(&pages).unwrap();
        assert!(Placement::read(&mut input, false).is_err());
    }

    #[test]
    #[ignore = "generate both actual MC placement-oracle.bin files with cpuSmoke first"]
    fn actual_mc_offset_and_seed_declarations_match_host_at_wrapping_coordinates() {
        for (game, name) in [(262, "26.2"), (263, "26.3")] {
            let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(format!(
                "../../adapters/mc-{name}/build/routing-fixtures/placement-oracle.bin"
            ));
            let bytes =
                std::fs::read(path).expect("generate the matching actual MC placement oracle");
            let pages = [bytes.as_slice()];
            let mut input = Reader::new(&pages).unwrap();
            assert_eq!(input.u32().unwrap(), 0x504c4d43);
            assert_eq!(input.u32().unwrap(), 1);
            assert_eq!(input.u32().unwrap(), game);
            let count = input.u32().unwrap();
            let mut seen_offset = [false; 3];
            let mut seen_seed = [false; 6];
            for case in 0..count {
                let offset = input.u32().unwrap();
                let placement = Placement {
                    offset,
                    horizontal: input.f32().unwrap(),
                    vertical: input.f32().unwrap(),
                    seed: input.u32().unwrap(),
                };
                assert!(placement.offset < 3 && placement.seed < 6);
                let position = [
                    input.i32().unwrap(),
                    input.i32().unwrap(),
                    input.i32().unwrap(),
                ];
                let seed = input.u64().unwrap() as i64;
                let expected = [
                    input.f64().unwrap(),
                    input.f64().unwrap(),
                    input.f64().unwrap(),
                ];
                assert_eq!(
                    placement.seed(position),
                    seed,
                    "MC {game} case {case} pos {position:?}"
                );
                assert_eq!(
                    placement.offset(position).map(f64::to_bits),
                    expected.map(f64::to_bits),
                    "MC {game} case {case} pos {position:?}"
                );
                seen_offset[placement.offset as usize] = true;
                seen_seed[placement.seed as usize] = true;
            }
            assert_eq!(seen_offset, [true; 3]);
            assert_eq!(seen_seed, [true; 6]);
            input.finish().unwrap();
            println!("MC {game}: {count} actual host placement rows bitwise equal");
        }
    }
}
