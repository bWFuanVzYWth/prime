//! 像素角落判定的测试参考实现。
//!
//! 用于独立验证最大同色区间端点判定，不编译进生产路径。

/// 四个角落的匹配状态，按 NW、NE、SW、SE 排列。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Corners(u8);

impl Corners {
    pub const fn from_flags([nw, ne, sw, se]: [bool; 4]) -> Self {
        Self(bool_bit(nw) | (bool_bit(ne) << 1) | (bool_bit(sw) << 2) | (bool_bit(se) << 3))
    }
}

const fn bool_bit(value: bool) -> u8 {
    if value { 1 } else { 0 }
}

/// 将四个角落的匹配状态编码为 4-bit key，按位依次为 NW、NE、SW、SE。
pub const fn corner_key(corners: Corners) -> u8 {
    corners.0
}

/// 水平方向的角落支持判定。
///
/// 四个角落中恰好三个匹配时返回 `Some((supports_right, supports_left))`，
/// 否则返回 `None`。
#[inline]
pub const fn horizontal_support_from_corners(corners: Corners) -> Option<(bool, bool)> {
    match corner_key(corners) {
        0b0111 | 0b1101 => Some((false, true)),
        0b1011 | 0b1110 => Some((true, false)),
        _ => None,
    }
}

/// 垂直方向的角落支持判定。
///
/// 四个角落中恰好三个匹配时返回 `Some((supports_down, supports_up))`，
/// 否则返回 `None`。
#[inline]
pub const fn vertical_support_from_corners(corners: Corners) -> Option<(bool, bool)> {
    match corner_key(corners) {
        0b0111 | 0b1011 => Some((false, true)),
        0b1101 | 0b1110 => Some((true, false)),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    mod correctness {
        use super::*;

        #[test]
        fn corner_key_encoding() {
            assert_eq!(
                corner_key(Corners::from_flags([true, false, false, false])),
                0b0001
            );
            assert_eq!(
                corner_key(Corners::from_flags([false, true, false, false])),
                0b0010
            );
            assert_eq!(
                corner_key(Corners::from_flags([false, false, true, false])),
                0b0100
            );
            assert_eq!(
                corner_key(Corners::from_flags([false, false, false, true])),
                0b1000
            );
            assert_eq!(
                corner_key(Corners::from_flags([true, true, true, true])),
                0b1111
            );
        }

        #[test]
        fn support_exhaustive_16_combinations() {
            for bits in 0..16u8 {
                let (nw, ne, sw, se) = (
                    (bits & 1) != 0,
                    (bits & 2) != 0,
                    (bits & 4) != 0,
                    (bits & 8) != 0,
                );
                let count = u8::from(nw) + u8::from(ne) + u8::from(sw) + u8::from(se);
                let corners = Corners::from_flags([nw, ne, sw, se]);
                let h = horizontal_support_from_corners(corners);
                let v = vertical_support_from_corners(corners);
                if count == 3 {
                    assert!(h.is_some(), "horizontal 3-true 0b{bits:04b}");
                    assert!(v.is_some(), "vertical 3-true 0b{bits:04b}");
                } else {
                    assert!(h.is_none(), "horizontal non-3 0b{bits:04b}");
                    assert!(v.is_none(), "vertical non-3 0b{bits:04b}");
                }
            }
        }

        #[test]
        fn horizontal_vs_vertical_differ_on_missing_sw_ne() {
            // missing SW (0b1011): h→(true,false), v→(false,true)
            let horizontal_sw_missing =
                horizontal_support_from_corners(Corners::from_flags([true, true, false, true]));
            let vertical_sw_missing =
                vertical_support_from_corners(Corners::from_flags([true, true, false, true]));
            assert!(horizontal_sw_missing.is_some());
            assert!(vertical_sw_missing.is_some());
            assert_ne!(horizontal_sw_missing, vertical_sw_missing);

            // missing NE (0b1101): h→(false,true), v→(true,false)
            let horizontal_ne_missing =
                horizontal_support_from_corners(Corners::from_flags([true, false, true, true]));
            let vertical_ne_missing =
                vertical_support_from_corners(Corners::from_flags([true, false, true, true]));
            assert!(horizontal_ne_missing.is_some());
            assert!(vertical_ne_missing.is_some());
            assert_ne!(horizontal_ne_missing, vertical_ne_missing);
        }
    }
}
