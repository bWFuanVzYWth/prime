//! Lazily allocated material pages. Individual ranges use u32 indices; the scene
//! is not constrained to one descriptor-sized buffer or one global index range.
//! Freed ranges are reused, while pages retain their high-water capacity until
//! the arena is dropped. Buffer destruction follows the Context completion rule.
use crate::plan::Slots;
use crate::resources::{Buffer, Context};
use ash::vk;
use std::sync::Arc;

const INITIAL_PAGE_BYTES: u64 = 4 * 1024 * 1024;
const MAX_PAGE_BYTES: u64 = 64 * 1024 * 1024;
#[cfg(test)]
const RECORD_BYTES: u64 = 128;
#[cfg(test)]
const PAGE_RECORDS: u32 = (64 * 1024 * 1024) / RECORD_BYTES as u32;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Allocation {
    pub page: usize,
    pub first: u32,
    pub count: u32,
}

struct PageSlots {
    slots: Slots,
    free: u32,
}

#[derive(Default)]
struct Layout {
    pages: Vec<PageSlots>,
    next: usize,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct NewPage {
    capacity: u32,
    bytes: u64,
}

impl NewPage {
    #[cfg(test)]
    fn plan(count: u32) -> Result<Self, String> {
        Self::with_stride(count, RECORD_BYTES, MAX_PAGE_BYTES)
    }

    fn with_stride(count: u32, record_bytes: u64, page_bytes: u64) -> Result<Self, String> {
        if count == 0 {
            return Err("Cannot allocate an empty material range".into());
        }
        let capacity = count.max((page_bytes / record_bytes) as u32);
        let bytes = u64::from(capacity)
            .checked_mul(record_bytes)
            .ok_or("Material page byte size overflow")?;
        Ok(Self { capacity, bytes })
    }
}

impl Layout {
    fn allocate_existing(&mut self, count: u32) -> Option<Allocation> {
        // Streaming fills the current page instead of revisiting every full
        // page for every cluster. A release makes that page the next candidate.
        for page in (self.next..self.pages.len()).chain(0..self.next) {
            let state = &mut self.pages[page];
            // Full pages are common and must not allocate an error string while
            // looking for reusable space. Fragmented pages may still fail below.
            if state.free < count {
                continue;
            }
            if let Ok(first) = state.slots.allocate(count) {
                state.free -= count;
                self.next = page;
                return Some(Allocation { page, first, count });
            }
        }
        None
    }

    fn commit_page(&mut self, plan: NewPage, count: u32) -> Allocation {
        let mut slots = Slots::with_limit(plan.capacity);
        let first = slots
            .allocate(count)
            .expect("a new material page contains its planned request");
        let allocation = Allocation {
            page: self.pages.len(),
            first,
            count,
        };
        self.pages.push(PageSlots {
            slots,
            free: plan.capacity - count,
        });
        self.next = allocation.page;
        allocation
    }

    fn free(&mut self, allocation: Allocation) {
        let state = &mut self.pages[allocation.page];
        state.slots.release(allocation.first, allocation.count);
        state.free += allocation.count;
        self.next = allocation.page;
    }
}

pub(crate) struct MaterialArena {
    layout: Layout,
    buffers: Vec<Buffer>,
    record_bytes: u64,
}

impl MaterialArena {
    #[cfg(test)]
    pub fn reserved_bytes(&self) -> u64 {
        self.buffers.iter().map(|b| b.size).sum()
    }
    pub fn with_stride(record_bytes: u64) -> Self {
        assert!(record_bytes >= 16 && record_bytes.is_multiple_of(16));
        Self {
            layout: Layout::default(),
            buffers: Vec::new(),
            record_bytes,
        }
    }

    /// Allocation/free operations belong to the recording owner. Before writing
    /// a reused range, the caller orders earlier shader/AS readers on its queue.
    pub fn allocate(&mut self, context: &Arc<Context>, count: u32) -> Result<Allocation, String> {
        if count == 0 {
            return Err("Cannot allocate an empty material range".into());
        }
        if let Some(allocation) = self.layout.allocate_existing(count) {
            return Ok(allocation);
        }
        // Small format groups must not each reserve 64 MiB. Grow subsequent pages
        // without moving existing device addresses or adding a shader indirection.
        let page_bytes = self.buffers.last().map_or(INITIAL_PAGE_BYTES, |b| {
            b.size.saturating_mul(2).min(MAX_PAGE_BYTES)
        });
        let plan = NewPage::with_stride(count, self.record_bytes, page_bytes)?;
        self.layout
            .pages
            .try_reserve(1)
            .map_err(|_| "Material page layout allocation failed")?;
        self.buffers
            .try_reserve(1)
            .map_err(|_| "Material page owner allocation failed")?;
        // Buffer::new counts every live Context allocation, including existing
        // pages and resources waiting for GPU completion, against the device
        // allocation limit. There is no separate global triangle-count cap.
        let buffer = Buffer::new(
            context,
            plan.bytes,
            vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS
                | vk::BufferUsageFlags::TRANSFER_SRC
                | vk::BufferUsageFlags::TRANSFER_DST
                | vk::BufferUsageFlags::ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_KHR,
            false,
        )
        .map_err(|error| {
            format!(
                "Material page allocation failed: pages={} records={} bytes={}: {error}",
                self.buffers.len(),
                plan.capacity,
                plan.bytes,
            )
        })?;
        // Publish CPU ownership only after the device allocation succeeds. Both
        // vectors have reserved capacity, so a failed creation consumes no slot.
        let allocation = self.layout.commit_page(plan, count);
        self.buffers.push(buffer);
        Ok(allocation)
    }

    /// The range must be owned by this arena and released exactly once. This
    /// changes future placement only; it does not destroy or retire a GPU page.
    pub fn free(&mut self, allocation: Allocation) {
        self.layout.free(allocation);
    }

    pub fn buffer(&self, allocation: Allocation) -> &Buffer {
        &self.buffers[allocation.page]
    }

    pub fn address(&self, allocation: Allocation) -> u64 {
        self.buffer(allocation).address() + u64::from(allocation.first) * self.record_bytes
    }

    pub fn page_count(&self) -> usize {
        self.buffers.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn small_format_pages_grow_without_padding_each_format_to_sixty_four_mib() {
        for stride in [176, 240, 272, 432] {
            let mut page_bytes = INITIAL_PAGE_BYTES;
            for expected in [4, 8, 16, 32, 64, 64] {
                let page = NewPage::with_stride(1, stride, page_bytes).unwrap();
                assert!(page.bytes <= expected * 1024 * 1024);
                assert!(page.bytes > expected * 1024 * 1024 - stride * 32);
                page_bytes = page.bytes.saturating_mul(2).min(MAX_PAGE_BYTES);
            }
            let large = NewPage::with_stride(1_000_000, stride, INITIAL_PAGE_BYTES).unwrap();
            assert_eq!(large.capacity, 1_000_000);
        }
    }

    fn allocate(layout: &mut Layout, count: u32) -> Allocation {
        let plan = NewPage::plan(count).unwrap();
        layout
            .allocate_existing(count)
            .unwrap_or_else(|| layout.commit_page(plan, count))
    }

    #[test]
    fn pages_are_lazy_and_reuse_freed_ranges_without_moving_live_ranges() {
        let mut layout = Layout::default();
        assert!(layout.pages.is_empty());
        let a = allocate(&mut layout, PAGE_RECORDS / 2);
        let b = allocate(&mut layout, PAGE_RECORDS / 2);
        let c = allocate(&mut layout, 1);
        assert_eq!((a.page, a.first), (0, 0));
        assert_eq!((b.page, b.first), (0, PAGE_RECORDS / 2));
        assert_eq!((c.page, c.first), (1, 0));
        layout.free(a);
        let d = allocate(&mut layout, PAGE_RECORDS / 2);
        assert_eq!(a, d);
        assert_eq!(layout.pages.len(), 2);
        layout.free(b);
        layout.free(d);
        assert_eq!(allocate(&mut layout, PAGE_RECORDS).page, 0);
        assert_eq!(layout.pages[1].free, PAGE_RECORDS - c.count);
    }

    #[test]
    fn fragmentation_uses_another_page_then_coalesces_for_reuse() {
        let mut layout = Layout::default();
        let quarter = PAGE_RECORDS / 4;
        let blocks: Vec<_> = (0..4).map(|_| allocate(&mut layout, quarter)).collect();
        layout.free(blocks[0]);
        layout.free(blocks[2]);
        let larger = allocate(&mut layout, quarter + 1);
        assert_eq!(larger.page, 1);
        assert_eq!(layout.pages[0].free, quarter * 2);
        layout.free(blocks[1]);
        let joined = allocate(&mut layout, quarter * 3);
        assert_eq!((joined.page, joined.first), (0, 0));
        assert_eq!(layout.pages.len(), 2);
    }

    #[test]
    fn page_math_and_total_capacity_exceed_signed_32_bit_without_allocating_bytes() {
        let mut layout = Layout::default();
        for _ in 0..4097 {
            allocate(&mut layout, PAGE_RECORDS);
        }
        let total: u64 = layout
            .pages
            .iter()
            .map(|page| u64::from(page.slots.limit()))
            .sum();
        assert!(total > 1_u64 << 31);
        assert_eq!(layout.pages.len(), 4097);

        let largest = NewPage::plan(u32::MAX).unwrap();
        assert_eq!(largest.capacity, u32::MAX);
        assert_eq!(largest.bytes, u64::from(u32::MAX) * 128);
        let oversized = allocate(&mut layout, u32::MAX);
        assert_eq!((oversized.first, oversized.count), (0, u32::MAX));
        layout.free(oversized);
        assert_eq!(allocate(&mut layout, u32::MAX), oversized);
    }

    #[test]
    fn uncommitted_or_invalid_page_plan_does_not_consume_ranges() {
        let mut layout = Layout::default();
        let occupied = allocate(&mut layout, PAGE_RECORDS);
        assert!(layout.allocate_existing(1).is_none());
        let pending = NewPage::plan(1).unwrap();
        // A resource-creation failure abandons this pure plan before commit.
        assert_eq!(pending.bytes, 64 * 1024 * 1024);
        assert!(NewPage::plan(0).is_err());
        assert_eq!(layout.pages.len(), 1);
        assert_eq!(layout.pages[0].free, 0);
        layout.free(occupied);
        assert_eq!(allocate(&mut layout, PAGE_RECORDS), occupied);
    }
}
