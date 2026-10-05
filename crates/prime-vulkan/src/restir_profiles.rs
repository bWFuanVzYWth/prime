//! Source charts are immutable while referenced by a reusable path.
use super::{Buffer, Context};
use ash::vk;
use prime_scene::restir_settings::RestirSettings;
use std::sync::Arc;

pub(super) fn thresholds(settings: RestirSettings) -> [u32; 4] {
    [
        (settings.distance_threshold / 100.0).to_bits(),
        settings.distance_sigma.to_bits(),
        settings.roughness_threshold.to_bits(),
        settings.roughness_sigma.to_bits(),
    ]
}

#[derive(Default)]
pub(super) struct Profiles {
    planes: Option<Buffer>,
    table: Option<Buffer>,
    values: Vec<[u32; 4]>,
    pub generation: u64,
    pub current: u32,
    pixels: u32,
}

impl Profiles {
    pub fn prepare(
        &mut self,
        context: &Arc<Context>,
        pixels: u32,
        accepted: Option<[u32; 4]>,
        current: [u32; 4],
    ) -> Result<(), String> {
        if self.pixels != pixels {
            *self = Self {
                generation: self.generation + 1,
                pixels,
                ..Default::default()
            };
        }
        if self.planes.is_some() && self.values[self.current as usize] == current {
            return Ok(());
        }
        let mut values = self.values.clone();
        let planes = if self.planes.is_none() {
            let Some(previous) = accepted.filter(|previous| *previous != current) else {
                return Ok(());
            };
            // RA-015: lazily attach source-chart IDs only after a live threshold change.
            // The accepted bank implicitly belongs to profile zero until its first rewrite.
            let planes = Buffer::new(
                context,
                u64::from(pixels) * 8,
                vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS
                    | if cfg!(all(test, feature = "shader-tests")) {
                        vk::BufferUsageFlags::TRANSFER_SRC
                    } else {
                        vk::BufferUsageFlags::empty()
                    },
                false,
            )?;
            values.push(previous);
            Some(planes)
        } else {
            None
        };
        if let Some(index) = self.values.iter().position(|value| *value == current) {
            self.current = index as u32;
            return Ok(());
        }
        let index =
            u32::try_from(values.len()).map_err(|_| "ReSTIR source profile index overflow")?;
        values.push(current);
        let bytes: Vec<u8> = values
            .iter()
            .flatten()
            .flat_map(|value| value.to_le_bytes())
            .collect();
        // UI updates publish a new immutable table; old reads retire with their submission.
        // Sixteen bytes per distinct chart are retained because old winners can still use it.
        let table =
            Buffer::upload_device(context, &bytes, vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS)?;
        // Publish the index and owners together. A failed allocation/upload must leave
        // the accepted source IDs readable and a subsequent prepare retry well-defined.
        if let Some(planes) = planes {
            self.planes = Some(planes);
            self.generation += 1;
        }
        self.values = values;
        self.current = index;
        self.table = Some(table);
        Ok(())
    }

    pub fn active(&self) -> bool {
        self.planes.is_some()
    }

    #[cfg(all(test, feature = "shader-tests"))]
    pub fn planes_for_test(&self) -> Option<&Buffer> {
        self.planes.as_ref()
    }
    #[cfg(all(test, feature = "shader-tests"))]
    pub fn values_for_test(&self) -> &[[u32; 4]] {
        &self.values
    }

    pub fn addresses(&self, initial_bank: usize) -> [u64; 3] {
        let Some(planes) = &self.planes else {
            return [0; 3];
        };
        let stride = u64::from(self.pixels) * 4;
        [
            planes.address() + initial_bank as u64 * stride,
            planes.address() + (initial_bank ^ 1) as u64 * stride,
            self.table.as_ref().unwrap().address(),
        ]
    }
}
