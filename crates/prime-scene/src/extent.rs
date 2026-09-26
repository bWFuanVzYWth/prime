//! Output extent contract, independent of a particular graphics device.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RenderExtent {
    pub width: u32,
    pub height: u32,
}

impl RenderExtent {
    /// The Z-Sobol pixel domain uses at most 16 bits per coordinate. Device image,
    /// dispatch and storage limits are additional constraints, checked by the backend.
    pub fn new(width: u32, height: u32) -> Result<Self, String> {
        if width == 0 || height == 0 || width > 65536 || height > 65536 {
            return Err("Render dimensions must be within 1..65536".into());
        }
        Ok(Self { width, height })
    }

    pub fn pixels(self) -> u64 {
        u64::from(self.width) * u64::from(self.height)
    }

    pub fn log2_resolution(self) -> u32 {
        u32::BITS - (self.width.max(self.height) - 1).leading_zeros()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extent_keeps_wide_counts_and_uses_a_covering_sobol_domain() {
        for (width, height, r) in [
            (1, 1, 0),
            (1920, 1080, 11),
            (4097, 3, 13),
            (65536, 65536, 16),
        ] {
            let extent = RenderExtent::new(width, height).unwrap();
            assert_eq!(extent.log2_resolution(), r);
            assert_eq!(extent.pixels(), u64::from(width) * u64::from(height));
        }
        assert_eq!(
            RenderExtent::new(65536, 65536).unwrap().pixels(),
            1u64 << 32
        );
        for (width, height) in [(0, 1), (1, 0), (65537, 1), (1, u32::MAX)] {
            assert!(RenderExtent::new(width, height).is_err());
        }
    }
}
