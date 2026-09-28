//! Owner-only pages. A released range cannot be reused until its submission completes.
use crate::{
    plan::Slots,
    resources::{Buffer, Context},
};
use ash::vk;
use std::{collections::VecDeque, rc::Rc, sync::Arc};

const UNIT: u64 = 256;
struct Page {
    buffer: Rc<Buffer>,
    slots: Slots,
    live: u32,
}

pub(crate) struct Lease {
    pub buffer: Rc<Buffer>,
    pub offset: u64,
    pub size: u64,
    page: usize,
    first: u32,
    units: u32,
}
impl Lease {
    pub fn address(&self) -> u64 {
        self.buffer.address() + self.offset
    }
    pub fn write(&self, bytes: &[u8]) -> Result<(), String> {
        if bytes.len() as u64 > self.size {
            return Err("Upload exceeds arena lease".into());
        }
        self.buffer.write_at(self.offset, bytes)
    }
    /// Fill a fresh upload lease. The writer joins all CPU workers before returning and must
    /// initialize every byte on success. Failed writes are never submitted to the GPU.
    pub fn write_with(
        &mut self,
        write: impl FnOnce(&mut [std::mem::MaybeUninit<u8>]) -> Result<(), String>,
    ) -> Result<(), String> {
        // SAFETY: Arena allocations have disjoint ranges and are not recycled until completion.
        // A fresh lease has no GPU consumer; its non-cloneable mutable borrow encloses all writes.
        unsafe {
            self.buffer
                .write_with(self.offset, self.size as usize, write)
        }
    }
}

pub(crate) struct Arena {
    pages: Vec<Page>,
    retired: VecDeque<(u64, Lease)>,
    serial: u64,
    host: bool,
    next: usize,
}
impl Arena {
    pub fn new(context: &Context, host: bool) -> Self {
        Self {
            pages: Vec::new(),
            retired: VecDeque::new(),
            serial: context.retirement_serial(),
            host,
            next: 0,
        }
    }
    pub fn begin(&mut self, completed: u64, serial: u64) {
        self.serial = serial;
        while self
            .retired
            .front()
            .is_some_and(|(value, _)| *value <= completed)
        {
            let (_, lease) = self.retired.pop_front().unwrap();
            let page = &mut self.pages[lease.page];
            page.slots.release(lease.first, lease.units);
            page.live -= lease.units;
            self.next = lease.page;
        }
    }
    pub fn allocate(
        &mut self,
        context: &Arc<Context>,
        size: u64,
        alignment: u64,
    ) -> Result<Lease, String> {
        assert!(alignment.is_power_of_two());
        let units = u32::try_from(
            size.max(1)
                .checked_add(alignment - 1)
                .ok_or("Arena allocation overflow")?
                .div_ceil(UNIT),
        )
        .map_err(|_| "Arena page exceeds address capacity")?;
        let mut found = None;
        for i in (self.next..self.pages.len()).chain(0..self.next) {
            let page = &mut self.pages[i];
            if page.slots.limit_value() - page.live >= units
                && let Ok(first) = page.slots.allocate(units)
            {
                found = Some((i, first));
                break;
            }
        }
        let (index, first) = if let Some(found) = found {
            found
        } else {
            let capacity = units.max(if self.host {
                4 * 1024 * 1024 / UNIT as u32
            } else {
                32 * 1024 * 1024 / UNIT as u32
            });
            let usage = if self.host {
                vk::BufferUsageFlags::TRANSFER_SRC
            } else {
                vk::BufferUsageFlags::ACCELERATION_STRUCTURE_STORAGE_KHR
                    | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS
                    | vk::BufferUsageFlags::STORAGE_BUFFER
            };
            let buffer = Rc::new(Buffer::new(
                context,
                u64::from(capacity) * UNIT,
                usage,
                self.host,
            )?);
            let mut slots = Slots::with_limit(capacity);
            let first = slots.allocate(units)?;
            let index = self.pages.len();
            self.pages.push(Page {
                buffer,
                slots,
                live: 0,
            });
            (index, first)
        };
        self.next = index;
        let page = &mut self.pages[index];
        page.live += units;
        let base = page.buffer.address();
        let offset = (base + u64::from(first) * UNIT).div_ceil(alignment) * alignment - base;
        Ok(Lease {
            buffer: page.buffer.clone(),
            offset,
            size,
            page: index,
            first,
            units,
        })
    }
    pub fn retire(&mut self, lease: Lease) {
        self.retired.push_back((self.serial, lease));
    }
    #[cfg(test)]
    pub fn page_count(&self) -> usize {
        self.pages.len()
    }
    #[cfg(test)]
    pub fn reserved_bytes(&self) -> u64 {
        self.pages.iter().map(|page| page.buffer.size).sum()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "requires Vulkan host-visible memory; checks exclusive mapped writes and failure reuse"]
    fn gpu_mapped_writes_preserve_neighbor_leases_and_recover_unsubmitted_failures() {
        let context = Context::new().unwrap();
        let workers = prime_scene::workers::CpuWorkers::new(4).unwrap();
        let mut arena = Arena::new(&context, true);
        let mut a = arena.allocate(&context, 32_768, 16).unwrap();
        let b = arena.allocate(&context, 32_768, 16).unwrap();
        assert_eq!(a.buffer.buffer, b.buffer.buffer);
        let len = (b.offset + b.size) as usize;
        a.buffer.write(&vec![0xcd; len]).unwrap();
        let before = context.cpu_upload_bytes();
        assert!(
            a.write_with(|out| {
                out[0].write(0x12);
                Err("unsubmitted test failure".into())
            })
            .is_err()
        );
        assert_eq!(context.cpu_upload_bytes(), before);
        a.write_with(|out| {
            workers.chunks_mut(out, 1024, |first, bytes| {
                for (i, value) in bytes.iter_mut().enumerate() {
                    value.write(((first + i) % 251) as u8);
                }
                Ok(())
            })
        })
        .unwrap();
        let bytes = a.buffer.read(len).unwrap();
        let range = a.offset as usize..(a.offset + a.size) as usize;
        assert!(bytes[..range.start].iter().all(|&v| v == 0xcd));
        assert!(bytes[range.end..].iter().all(|&v| v == 0xcd));
        assert!(
            bytes[range]
                .iter()
                .enumerate()
                .all(|(i, &v)| v == (i % 251) as u8)
        );
        assert_eq!(context.cpu_upload_bytes(), before + a.size);
        // No GPU commands reference these leases; normal arena destruction owns their pages.
    }
    #[test]
    #[ignore = "requires a Vulkan device; verifies allocator completion rules and bounded churn"]
    fn gpu_ranges_reuse_only_after_their_completion_proof() {
        let context = Context::new().unwrap();
        for host in [false, true] {
            let before = context
                .live_allocations
                .load(std::sync::atomic::Ordering::Relaxed);
            let mut arena = Arena::new(&context, host);
            arena.begin(0, 1);
            let a = arena.allocate(&context, 4096, 1024).unwrap();
            assert_eq!(a.address() % 1024, 0);
            let original = (a.buffer.buffer, a.offset);
            arena.retire(a);
            arena.begin(0, 2);
            let b = arena.allocate(&context, 4096, 1024).unwrap();
            assert_ne!(
                (b.buffer.buffer, b.offset),
                original,
                "CPU return cannot reclaim an in-flight range"
            );
            arena.retire(b);
            arena.begin(1, 3);
            let c = arena.allocate(&context, 4096, 1024).unwrap();
            assert_eq!((c.buffer.buffer, c.offset), original);
            arena.retire(c);
            for serial in 4..1004 {
                arena.begin(serial - 2, serial);
                let values: Vec<_> = (0..100)
                    .map(|_| arena.allocate(&context, 1024, 256).unwrap())
                    .collect();
                for value in values {
                    arena.retire(value);
                }
            }
            assert_eq!(
                arena.page_count(),
                1,
                "100k distinct completed leases reuse one page"
            );
            assert_eq!(
                context
                    .live_allocations
                    .load(std::sync::atomic::Ordering::Relaxed),
                before + 1
            );
            println!(
                "arena host={host} pages={} reserved_bytes={} distinct_leases=100003",
                arena.page_count(),
                arena.reserved_bytes()
            );
            drop(arena);
            assert_eq!(
                context
                    .live_allocations
                    .load(std::sync::atomic::Ordering::Relaxed),
                before
            );
        }
    }
}
