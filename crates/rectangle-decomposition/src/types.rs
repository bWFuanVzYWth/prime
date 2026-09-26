//! 矩形分解的公共类型。

/// 左闭右开的 `u8` 范围，模仿 `std::ops::Range` 命名。
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct RangeU8 {
    /// 起始坐标（含）。
    pub start: u8,
    /// 结束坐标（不含）。
    pub end: u8,
}

impl RangeU8 {
    /// 创建一个左闭右开的 `u8` 范围。
    #[must_use]
    pub const fn new(start: u8, end: u8) -> Self {
        Self { start, end }
    }
}

impl From<RangeU8> for std::ops::Range<u8> {
    fn from(r: RangeU8) -> Self {
        r.start..r.end
    }
}

/// 分解结果矩形。
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct Rectangle {
    /// 矩形覆盖的像素值。
    pub value: u16,
    /// x 范围，左闭右开。
    pub x: RangeU8,
    /// y 范围，左闭右开。
    pub y: RangeU8,
}

/// 方向枚举。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Orientation {
    Horizontal,
    Vertical,
}

/// 有效 chord（矩形边片段），用于 chord 提取和匹配阶段。
///
/// 水平 chord 覆盖 `x1..x2`，垂直 chord 覆盖 `y1..y2`。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct EffectiveChord {
    pub(crate) orientation: Orientation,
    pub(crate) x1: u8,
    pub(crate) y1: u8,
    pub(crate) x2: u8,
    pub(crate) y2: u8,
}

pub trait ChordAccess {
    fn chord(&self) -> EffectiveChord;
}

impl ChordAccess for EffectiveChord {
    fn chord(&self) -> EffectiveChord {
        *self
    }
}

/// 单行上的水平连续段，`x_start..x_end`。
#[derive(Clone, Copy, Default)]
pub struct Run {
    pub(crate) value: u16,
    pub(crate) x_start: u8,
    pub(crate) x_end: u8,
}

/// 向下延伸中的活跃矩形，`x_start..x_end`。
#[derive(Clone, Copy, Default)]
pub struct ActiveRect {
    pub(crate) value: u16,
    pub(crate) x_start: u8,
    pub(crate) x_end: u8,
    pub(crate) y_start: u8,
}

impl ActiveRect {
    pub(crate) const fn new(value: u16, x_start: u8, x_end: u8, y_start: u8) -> Self {
        Self {
            value,
            x_start,
            x_end,
            y_start,
        }
    }

    pub(crate) const fn match_key(self) -> u32 {
        (self.value as u32) | ((self.x_start as u32) << 16) | ((self.x_end as u32) << 24)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    mod correctness {
        use super::*;

        #[test]
        fn match_key_encoding() {
            // 字段编码：bits[0..16)=value, bits[16..24)=x_start, bits[24..32)=x_end
            let rect = ActiveRect {
                value: 0x1234,
                x_start: 0x56,
                x_end: 0x78,
                y_start: 10,
            };
            assert_eq!(rect.match_key(), 0x7856_1234u32);

            // 不同 value 或 x 范围产生不同 key
            let value_one = ActiveRect {
                value: 1,
                x_start: 10,
                x_end: 20,
                y_start: 0,
            };
            let value_two = ActiveRect {
                value: 2,
                x_start: 10,
                x_end: 20,
                y_start: 0,
            };
            assert_ne!(value_one.match_key(), value_two.match_key());

            let wider = ActiveRect {
                value: 1,
                x_start: 10,
                x_end: 30,
                y_start: 0,
            };
            assert_ne!(value_one.match_key(), wider.match_key());

            // y_start 不参与 key
            let later_y = ActiveRect {
                value: 1,
                x_start: 10,
                x_end: 20,
                y_start: 99,
            };
            assert_eq!(value_one.match_key(), later_y.match_key());
        }
    }
}
