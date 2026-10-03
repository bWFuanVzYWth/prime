use std::{
    alloc::{GlobalAlloc, Layout, System},
    cell::Cell,
    sync::atomic::{AtomicUsize, Ordering},
};

thread_local! { static TRACK: Cell<bool> = const { Cell::new(false) }; }
static ALLOCATIONS: AtomicUsize = AtomicUsize::new(0);
struct ObservedAllocator;
unsafe impl GlobalAlloc for ObservedAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        if TRACK.try_with(Cell::get).unwrap_or(false) {
            ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
        }
        unsafe { System.alloc(layout) }
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe {
            System.dealloc(ptr, layout);
        }
    }
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        if TRACK.try_with(Cell::get).unwrap_or(false) {
            ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
        }
        unsafe { System.realloc(ptr, layout, new_size) }
    }
}
#[global_allocator]
static ALLOCATOR: ObservedAllocator = ObservedAllocator;

#[test]
fn disabled_scope_and_context_capture_do_not_allocate() {
    TRACK.with(|v| v.set(true));
    for _ in 0..1024 {
        assert!(prime_diagnostics::capture_context().is_none());
        let mut span = prime_diagnostics::scope("disabled");
        span.count("items", 19);
        span.value("summary", "borrowed string");
        span.value_with("lazy", || "must not allocate".to_owned());
        span.fail();
        std::hint::black_box(span);
    }
    TRACK.with(|v| v.set(false));
    assert_eq!(ALLOCATIONS.load(Ordering::Relaxed), 0);
}
