//! Owner-only pages. A released range cannot be reused until its submission completes.
use crate::{
    plan::Slots,
    resources::{Buffer, Context},
};
use ash::vk;
use std::{collections::VecDeque, rc::Rc, sync::Arc};

const UNIT: u64 = 256;
const COLLECTION_INTERVAL: u64 = 64;

#[path = "arena_policy.rs"]
mod policy;
use policy::{completed_leases, keep_idle_page};
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
    pages: Vec<Option<Page>>,
    retired: VecDeque<(u64, Lease)>,
    serial: u64,
    host: bool,
    next: usize,
    pending_collection: bool,
    last_collection: u64,
}
impl Arena {
    pub fn new(context: &Context, host: bool) -> Self {
        Self {
            pages: Vec::new(),
            retired: VecDeque::new(),
            serial: context.retirement_serial(),
            host,
            next: 0,
            pending_collection: false,
            last_collection: 0,
        }
    }
    pub fn begin(&mut self, completed: u64, serial: u64) {
        self.serial = serial;
        let mut newly_empty = false;
        let budget = self.idle_budget();
        let multiple_pages = self.pages.len() > 1;
        for lease in completed_leases(&mut self.retired, completed) {
            let page = self.pages[lease.page].as_mut().unwrap();
            page.slots.release(lease.first, lease.units);
            page.live -= lease.units;
            newly_empty |= page.live == 0 && (multiple_pages || page.buffer.size > budget);
            self.next = lease.page;
        }
        self.pending_collection |= newly_empty;
        if self.pending_collection
            && completed.saturating_sub(self.last_collection) >= COLLECTION_INTERVAL
        {
            self.reclaim_empty_pages();
            self.last_collection = completed;
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
            let Some(page) = &mut self.pages[i] else {
                continue;
            };
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
                    | vk::BufferUsageFlags::TRANSFER_DST
            };
            let usage = if !self.host && context.opacity_micromap.is_some() {
                usage
                    | vk::BufferUsageFlags::MICROMAP_STORAGE_EXT
                    | vk::BufferUsageFlags::MICROMAP_BUILD_INPUT_READ_ONLY_EXT
                    | vk::BufferUsageFlags::ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_KHR
            } else {
                usage
            };
            let buffer = Rc::new(Buffer::new(
                context,
                u64::from(capacity) * UNIT,
                usage,
                self.host,
            )?);
            let mut slots = Slots::with_limit(capacity);
            let first = slots.allocate(units)?;
            let page = Some(Page {
                buffer,
                slots,
                live: 0,
            });
            let index = if let Some(index) = self.pages.iter().position(Option::is_none) {
                self.pages[index] = page;
                index
            } else {
                let index = self.pages.len();
                self.pages.push(page);
                index
            };
            (index, first)
        };
        self.next = index;
        let page = self.pages[index].as_mut().unwrap();
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
    fn idle_budget(&self) -> u64 {
        if self.host {
            4 * 1024 * 1024
        } else {
            32 * 1024 * 1024
        }
    }
    /// Completion has already released every range. Keep at most one standard
    /// idle page, never a burst-sized page. External owners prevent collection.
    pub fn reclaim_empty_pages(&mut self) {
        if !self.pending_collection {
            return;
        }
        let budget = self.idle_budget();
        let keep = keep_idle_page(
            self.pages.iter().enumerate().filter_map(|(index, page)| {
                let page = page.as_ref()?;
                Some((
                    index,
                    page.buffer.size,
                    page.live == 0 && Rc::strong_count(&page.buffer) == 1,
                ))
            }),
            budget,
        );
        self.pending_collection = false;
        for (index, page) in self.pages.iter_mut().enumerate() {
            if page
                .as_ref()
                .is_some_and(|page| page.live == 0 && Rc::strong_count(&page.buffer) == 1)
            {
                if Some(index) != keep {
                    *page = None;
                }
            }
            self.pending_collection |= page
                .as_ref()
                .is_some_and(|page| page.live == 0 && Rc::strong_count(&page.buffer) != 1);
        }
        while self.pages.last().is_some_and(Option::is_none) {
            self.pages.pop();
        }
        if self.next >= self.pages.len() {
            self.next = 0;
        }
    }
    #[cfg(test)]
    pub fn page_count(&self) -> usize {
        self.pages.iter().flatten().count()
    }
    #[cfg(test)]
    pub fn retired_count(&self) -> usize {
        self.retired.len()
    }
    #[cfg(test)]
    pub fn reserved_bytes(&self) -> u64 {
        self.pages
            .iter()
            .flatten()
            .map(|page| page.buffer.size)
            .sum()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "requires Vulkan allocation; sparse page collection, last-use proof and cancellation"]
    fn gpu_empty_pages_preserve_in_flight_leases_and_reuse_sparse_identities() {
        let context = Context::new().unwrap();
        let mut arena = Arena::new(&context, false);
        arena.begin(0, 7);
        let normal = arena.allocate(&context, 31 * 1024 * 1024, 256).unwrap();
        let a = arena.allocate(&context, 40 * 1024 * 1024, 256).unwrap();
        let b = arena.allocate(&context, a.size, 256).unwrap();
        let c = arena.allocate(&context, normal.size, 256).unwrap();
        let stable = (c.page, c.buffer.buffer, c.offset, c.address());
        let held = b.buffer.clone();
        arena.retire(a);
        arena.begin(6, 8);
        arena.reclaim_empty_pages();
        assert_eq!(
            arena.page_count(),
            4,
            "in-flight ranges keep physical pages"
        );
        arena.retire(b);
        arena.begin(7, 9);
        arena.reclaim_empty_pages();
        assert_eq!(arena.page_count(), 3);
        assert!(arena.pages[1].is_none());
        assert_eq!((c.page, c.buffer.buffer, c.offset, c.address()), stable);
        arena.begin(8, 10);
        arena.reclaim_empty_pages();
        assert_eq!(arena.page_count(), 3, "external owners prevent collection");
        drop(held);
        arena.reclaim_empty_pages();
        assert_eq!(arena.page_count(), 2);
        assert!(arena.pages[2].is_none());
        assert_eq!((c.page, c.buffer.buffer, c.offset, c.address()), stable);
        let hole = arena.allocate(&context, 40 * 1024 * 1024, 256).unwrap();
        assert_eq!(
            hole.page, 1,
            "new pages reuse sparse holes without moving c"
        );
        arena.retire(hole);
        arena.retire(normal);
        arena.retire(c);
        arena.begin(9, 11);
        arena.reclaim_empty_pages();
        assert_eq!(arena.page_count(), 3);
        arena.begin(10, 12);
        arena.reclaim_empty_pages();
        assert_eq!(
            arena.page_count(),
            1,
            "one standard idle page survives; burst pages do not"
        );
        assert_eq!(arena.pages.len(), 1);
        assert_eq!(arena.reserved_bytes(), 32 * 1024 * 1024);
    }
    #[test]
    #[ignore = "requires Vulkan host-visible memory; checks exclusive mapped writes and failure reuse"]
    fn gpu_mapped_writes_preserve_neighbor_leases_and_recover_unsubmitted_failures() {
        let context = Context::new().unwrap();
        context.set_diagnostics(true);
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
