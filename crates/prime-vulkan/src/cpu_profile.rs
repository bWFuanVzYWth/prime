//! Opt-in host CPU recording diagnostics. Stage times are disjoint children of
//! record_total; none measures GPU execution or the caller's eventual submission.
use std::{fmt::Write, time::Instant};

pub(crate) fn enabled() -> bool {
    std::env::var_os("PRIME_PROFILE_CPU").is_some_and(|value| value == "1")
}

#[derive(Clone, Copy)]
#[repr(usize)]
pub(crate) enum Stage {
    Total,
    BeginRetire,
    Collect,
    SlotWait,
    FrameSetup,
    Static,
    Plan,
    Execute,
    Tlas,
    Output,
    Descriptors,
    Dispatch,
}

const STAGES: [&str; 12] = [
    "record_total",
    "begin_retire",
    "collect",
    "slot_wait",
    "frame_setup",
    "static_prepare",
    "objects_plan",
    "objects_execute",
    "tlas",
    "output_prepare",
    "descriptors",
    "dispatch_record",
];
const LOADS: [&str; 14] = [
    "static_triangles",
    "raw_triangles",
    "prototypes",
    "source_instances",
    "static_clusters",
    "static_material_pages",
    "object_material_pages",
    "object_blas",
    "tlas_instances",
    "rebuilt_static",
    "rebuilt_object",
    "static_updates",
    "tlas_rebuilds",
    "cpu_upload_bytes",
];
const BATCH: u64 = 120;

#[derive(Default)]
pub(crate) struct FrameCpu {
    enabled: bool,
    ns: [u64; STAGES.len()],
    pub static_updates: u64,
    pub tlas_rebuilds: u64,
}
impl FrameCpu {
    pub fn new(enabled: bool) -> Self {
        Self {
            enabled,
            ..Self::default()
        }
    }
    #[inline]
    pub fn start(&self) -> Option<Instant> {
        self.enabled.then(Instant::now)
    }
    #[inline]
    pub fn finish(&mut self, stage: Stage, start: Option<Instant>) {
        if let Some(start) = start {
            let ns = start.elapsed().as_nanos().min(u128::from(u64::MAX)) as u64;
            self.ns[stage as usize] = self.ns[stage as usize].saturating_add(ns);
        }
    }
}

#[derive(Default)]
pub(crate) struct CpuProfile {
    completed: u64,
    frames: u64,
    sum_ns: [u64; STAGES.len()],
    max_ns: [u64; STAGES.len()],
    last_load: [u64; LOADS.len()],
    max_load: [u64; LOADS.len()],
    sum_upload_bytes: u64,
}
impl CpuProfile {
    #[cfg(test)]
    pub fn pending_counters(&self) -> (u64, u64) {
        (self.completed, self.sum_upload_bytes)
    }

    /// Fixed-size aggregation: no scene traversal, allocation, or log until a
    /// complete batch. Failed recordings and unfinished teardown batches are omitted.
    pub fn observe(&mut self, frame: &FrameCpu, load: [u64; LOADS.len()]) {
        self.frames += 1;
        self.completed += 1;
        for (index, value) in frame.ns.iter().copied().enumerate() {
            self.sum_ns[index] = self.sum_ns[index].saturating_add(value);
            self.max_ns[index] = self.max_ns[index].max(value);
        }
        self.last_load = load;
        for (index, value) in load.into_iter().enumerate() {
            self.max_load[index] = self.max_load[index].max(value);
        }
        self.sum_upload_bytes = self.sum_upload_bytes.saturating_add(load[13]);
        if self.frames == BATCH {
            eprintln!("{}", self.report());
            self.frames = 0;
            self.sum_ns.fill(0);
            self.max_ns.fill(0);
            self.max_load.fill(0);
            self.sum_upload_bytes = 0;
        }
    }

    fn report(&self) -> String {
        let mut line = format!(
            "[Prime CPU renderer] completed={} frames={} timing_ms=sum/mean/max children=disjoint_within_record_total excludes=engine_prepare,FFM,host_submit,GPU,log",
            self.completed, self.frames
        );
        for (index, name) in STAGES.iter().enumerate() {
            let sum = self.sum_ns[index] as f64 / 1e6;
            let _ = write!(
                line,
                " {name}={sum:.3}/{:.3}/{:.3}",
                sum / self.frames as f64,
                self.max_ns[index] as f64 / 1e6
            );
        }
        let measured_children = self.sum_ns[1..].iter().sum::<u64>();
        let other = self.sum_ns[0].saturating_sub(measured_children) as f64 / 1e6;
        let _ = write!(line, " other_sum_ms={other:.3} load=last/max");
        for (index, name) in LOADS.iter().enumerate() {
            let _ = write!(
                line,
                " {name}={}/{}",
                self.last_load[index], self.max_load[index]
            );
        }
        let _ = write!(
            line,
            " cpu_upload_bytes_sum={} upload_definition=successful_mapped_buffer_writes_not_PCIe",
            self.sum_upload_bytes
        );
        line
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disabled_frame_has_no_timer_and_batch_aggregates_without_resetting_total() {
        let disabled = FrameCpu::default();
        assert!(disabled.start().is_none());
        let mut frame = FrameCpu::new(true);
        frame.ns[Stage::Total as usize] = 4_000_000;
        frame.ns[Stage::Plan as usize] = 1_000_000;
        let mut profile = CpuProfile::default();
        let mut load = [0; LOADS.len()];
        load[0] = 1u64 << 33;
        load[13] = 4096;
        for _ in 0..119 {
            profile.observe(&frame, load);
        }
        assert_eq!(profile.frames, 119);
        assert_eq!(profile.sum_ns[0], 476_000_000);
        assert_eq!(profile.max_load[0], 1u64 << 33);
        assert!(
            profile
                .report()
                .contains("record_total=476.000/4.000/4.000")
        );
        profile.observe(&frame, load);
        assert_eq!(profile.frames, 0);
        assert_eq!(profile.completed, 120);
        assert_eq!(profile.sum_ns, [0; STAGES.len()]);
        assert_eq!(profile.sum_upload_bytes, 0);
    }
}
