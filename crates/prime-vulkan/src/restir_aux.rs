//! Optional point-profile data. Ownership epochs prevent reading newly allocated history.
use super::{Buffer, Context};
use ash::vk;
use prime_scene::restir_settings::RestirSettings;
use std::sync::Arc;

#[derive(Default)]
pub(super) struct Auxiliary {
    pub sample_ids: Option<Buffer>,
    pub duplicate_counts: Option<Buffer>,
    pub ages: Option<Buffer>,
    pub shading: Option<Buffer>,
    pub rr_initial: Option<Buffer>,
    pub rr_smoothed: Option<Buffer>,
    pub rr_fireflies: Option<Buffer>,
    pub duplicate_generation: u64,
    pub age_generation: u64,
    pub rr_generation: u64,
    pixels: u32,
}

pub(super) fn duplicate_producer(settings: RestirSettings, realtime: bool) -> bool {
    // RA-017: cold/spatial-only realtime still produces the next accepted map.
    // Offline has no temporal consumer; debug1 independently displays this map.
    settings.debug_view == 1 || (realtime && settings.history_length != 0 && settings.duplicate_map)
}

impl Auxiliary {
    pub fn prepare(
        &mut self,
        context: &Arc<Context>,
        pixels: u32,
        settings: RestirSettings,
        rr_consumer: bool,
        duplicate_producer: bool,
    ) -> Result<(), String> {
        if self.pixels != pixels {
            *self = Self {
                duplicate_generation: self.duplicate_generation + 1,
                age_generation: self.age_generation + 1,
                rr_generation: self.rr_generation + 1,
                pixels,
                ..Default::default()
            };
        }
        let duplicates = duplicate_producer;
        if duplicates && self.sample_ids.is_none() {
            let ids = allocate(context, u64::from(pixels) * 4)?;
            let counts = allocate(context, u64::from(pixels) * 4)?;
            self.sample_ids = Some(ids);
            self.duplicate_counts = Some(counts);
            self.duplicate_generation += 1;
        } else if !duplicates && self.sample_ids.is_some() {
            self.sample_ids = None;
            self.duplicate_counts = None;
            self.duplicate_generation += 1;
        }
        let track_age = settings.debug_view == 2 || rr_consumer;
        if track_age && self.ages.is_none() {
            self.ages = Some(allocate(context, u64::from(pixels) * 8)?);
            self.age_generation += 1;
        } else if !track_age && self.ages.is_some() {
            self.ages = None;
            self.age_generation += 1;
        }
        let decoupled = settings.decoupled_shading
            && settings.spatial_reuse
            && settings.spatial_iterations != 0;
        if decoupled && self.shading.is_none() {
            self.shading = Some(allocate(context, u64::from(pixels) * 16)?);
        } else if !decoupled {
            self.shading = None;
        }
        // RA-016: the output mixture only consumes RGB/UCW, not another 80-byte path.
        // Keep statistics independent of reservoir ownership and allocate by actual consumer.
        if rr_consumer && self.rr_initial.is_none() {
            self.rr_initial = Some(allocate(context, u64::from(pixels) * 16)?);
        } else if !rr_consumer {
            self.rr_initial = None;
        }
        let smooth = rr_consumer && settings.rr_mode as u32 == 2;
        if smooth && self.rr_smoothed.is_none() {
            self.rr_smoothed = Some(allocate(context, u64::from(pixels) * 8)?);
            self.rr_generation += 1;
        } else if !smooth && self.rr_smoothed.is_some() {
            self.rr_smoothed = None;
            self.rr_generation += 1;
        }
        if rr_consumer && settings.rr_firefly && self.rr_fireflies.is_none() {
            self.rr_fireflies = Some(allocate(context, u64::from(pixels) * 4)?);
        } else if !rr_consumer || !settings.rr_firefly {
            self.rr_fireflies = None;
        }
        Ok(())
    }

    pub fn rr_addresses(&self, primary_bank: usize) -> [u64; 4] {
        let smooth = self.rr_smoothed.as_ref().map_or(0, Buffer::address);
        let stride = u64::from(self.pixels) * 4;
        [
            self.rr_initial.as_ref().map_or(0, Buffer::address),
            if smooth != 0 {
                smooth + primary_bank as u64 * stride
            } else {
                0
            },
            if smooth != 0 {
                smooth + (primary_bank ^ 1) as u64 * stride
            } else {
                0
            },
            self.rr_fireflies.as_ref().map_or(0, Buffer::address),
        ]
    }

    pub fn addresses(&self, primary_bank: usize) -> [u64; 5] {
        let age = self.ages.as_ref().map_or(0, Buffer::address);
        let stride = u64::from(self.pixels) * 4;
        [
            self.sample_ids.as_ref().map_or(0, Buffer::address),
            self.duplicate_counts.as_ref().map_or(0, Buffer::address),
            if age != 0 {
                age + primary_bank as u64 * stride
            } else {
                0
            },
            if age != 0 {
                age + (primary_bank ^ 1) as u64 * stride
            } else {
                0
            },
            self.shading.as_ref().map_or(0, Buffer::address),
        ]
    }
}

fn allocate(context: &Arc<Context>, bytes: u64) -> Result<Buffer, String> {
    Buffer::new(
        context,
        bytes,
        vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS
            | if cfg!(all(test, feature = "shader-tests")) {
                vk::BufferUsageFlags::TRANSFER_SRC
            } else {
                vk::BufferUsageFlags::empty()
            },
        false,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn duplicate_map_requires_an_actual_consumer_or_next_frame_producer() {
        let mut settings = RestirSettings::default();
        assert!(duplicate_producer(settings, true)); // includes cold and spatial-only
        assert!(!duplicate_producer(settings, false));
        settings.history_length = 0;
        assert!(!duplicate_producer(settings, true));
        settings.debug_view = 1;
        assert!(duplicate_producer(settings, true));
        assert!(duplicate_producer(settings, false));
        settings.debug_view = 2;
        assert!(!duplicate_producer(settings, false));
        settings.history_length = 20;
        settings.duplicate_map = false;
        assert!(!duplicate_producer(settings, true));
    }
}
