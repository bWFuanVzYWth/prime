//! Optional queue timestamps. Slots are bound to the capture context at recording,
//! not the frame which happens to observe GPU completion.
use ash::vk;
use prime_diagnostics::{Context, GpuEvent};
use std::cell::Cell;

pub(crate) const QUERY_COUNT: u32 = 32;

#[derive(Clone, Copy)]
#[repr(u32)]
pub(crate) enum Stage {
    Preparation,
    Render,
    Total,
    Primary,
    Transport,
    Post,
    Offline,
    Reconstruction,
    Stars,
    Exposure,
    Display,
}

impl Stage {
    pub const ALL: [Self; 11] = [
        Self::Preparation,
        Self::Render,
        Self::Total,
        Self::Primary,
        Self::Transport,
        Self::Post,
        Self::Offline,
        Self::Reconstruction,
        Self::Stars,
        Self::Exposure,
        Self::Display,
    ];

    pub const fn name(self) -> &'static str {
        match self {
            Self::Preparation => "gpu.prepare",
            Self::Render => "gpu.render",
            Self::Total => "gpu.total",
            Self::Primary => "gpu.k1",
            Self::Transport => "gpu.k2",
            Self::Post => "gpu.post",
            Self::Offline => "gpu.offline",
            Self::Reconstruction => "gpu.rr",
            Self::Stars => "gpu.stars",
            Self::Exposure => "gpu.exposure",
            Self::Display => "gpu.display",
        }
    }

    pub const fn indices(self) -> [u32; 2] {
        // Preserve the first three coarse boundaries for delayed summary consumers.
        match self {
            Self::Preparation => [0, 1],
            Self::Render => [1, 2],
            Self::Total => [0, 2],
            _ => {
                let first = 3 + (self as u32 - 3) * 2;
                [first, first + 1]
            }
        }
    }
}

#[derive(Default)]
pub(crate) struct Frame {
    pub serial: u64,
    pub accepted: bool,
    pub context: Option<Context>,
    written: Cell<u32>,
}

impl Frame {
    pub fn begin(&mut self, serial: u64) {
        *self = Self {
            serial,
            accepted: false,
            context: prime_diagnostics::capture_context(),
            written: Cell::new(0),
        };
    }

    pub fn mark(&self, pool: vk::QueryPool, index: u32) -> Option<u32> {
        if pool == vk::QueryPool::null() || self.serial == 0 {
            return None;
        }
        self.written.set(self.written.get() | (1 << index));
        Some(index)
    }

    pub fn has(&self, indices: [u32; 2]) -> bool {
        indices
            .into_iter()
            .all(|i| self.written.get() & (1 << i) != 0)
    }

    pub fn begun(&self, stage: Stage) -> bool {
        self.written(stage.indices()[0])
    }

    pub fn written(&self, index: u32) -> bool {
        self.written.get() & (1 << index) != 0
    }

    pub fn emit(
        &self,
        stage: Stage,
        ticks: Option<[u64; 2]>,
        queue: u64,
        bits: u32,
        period: f64,
        status: &'static str,
    ) {
        self.emit_raw(
            stage,
            ticks.map(|v| v[0]),
            ticks.map(|v| v[1]),
            queue,
            bits,
            period,
            status,
            ticks.is_some(),
        );
    }

    #[allow(clippy::too_many_arguments)]
    pub fn emit_raw(
        &self,
        stage: Stage,
        start_tick: Option<u64>,
        end_tick: Option<u64>,
        queue: u64,
        bits: u32,
        period: f64,
        status: &'static str,
        observed: bool,
    ) {
        if let Some(context) = &self.context {
            context.record_gpu(GpuEvent {
                frame_id: context.frame_id(),
                parent_id: context.parent_id(),
                name: stage.name(),
                start_tick,
                end_tick,
                timestamp_valid_bits: bits,
                timestamp_period_ns: period,
                queue_id: queue,
                submission_id: self.serial,
                status,
                completion_observed_ns: observed.then(|| context.clock_ns()),
            });
        }
    }
}

pub(crate) fn elapsed_ns(first: u64, last: u64, bits: u32, period: f32) -> u64 {
    let mask = u64::MAX.checked_shr(64 - bits).unwrap_or(0);
    ((last.wrapping_sub(first) & mask) as f64 * f64::from(period)) as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use ash::vk::Handle;

    #[test]
    fn disabled_query_plan_has_no_marks_and_stage_pairs_fit_fixed_capacity() {
        let mut frame = Frame::default();
        frame.begin(7);
        for stage in Stage::ALL {
            for index in stage.indices() {
                assert!(index < QUERY_COUNT);
                assert_eq!(frame.mark(vk::QueryPool::null(), index), None);
            }
            assert!(!frame.has(stage.indices()));
        }
        let pool = vk::QueryPool::from_raw(1);
        assert_eq!(frame.mark(pool, 3), Some(3));
        assert!(!frame.has(Stage::Primary.indices()));
        frame.mark(pool, 4);
        assert!(frame.has(Stage::Primary.indices()));
        frame.begin(8);
        assert!(!frame.has(Stage::Primary.indices()));
        assert_eq!(frame.serial, 8);
        assert!(!frame.accepted);
    }

    #[test]
    fn durations_keep_timestamp_wrap_and_fractional_period() {
        assert_eq!(elapsed_ns(250, 5, 8, 2.5), 27);
        assert_eq!(elapsed_ns(u64::MAX - 2, 4, 64, 1.0), 7);
        assert_eq!(elapsed_ns(0, u64::MAX, 0, 1.0), 0);
    }

    #[test]
    fn delayed_gpu_events_keep_original_frame_and_closed_sessions_do_not_cross() {
        let first = prime_diagnostics::Recorder::new();
        let second = prime_diagnostics::Recorder::new();
        let mut frame = Frame::default();
        {
            let _context = first.enter(101);
            frame.begin(17);
            for i in Stage::Primary.indices() {
                frame.mark(vk::QueryPool::from_raw(1), i);
            }
            frame.accepted = true;
        }
        {
            let _later = second.enter(202);
            // Completion observes frame 202, but these are frame 101's original ticks.
            frame.emit(
                Stage::Primary,
                Some([u64::MAX - 1, 4]),
                31,
                64,
                2.5,
                "complete",
            );
            frame.emit(Stage::Primary, None, 31, 64, 2.5, "pending");
            frame.context = None; // capture close detaches unresolved GPU contexts
            frame.emit(Stage::Primary, Some([4, 9]), 31, 64, 2.5, "complete");
            frame.begin(18);
            frame.emit(Stage::Primary, None, 31, 64, 2.5, "unsubmitted");
        }
        let old: serde_json::Value = serde_json::from_str(&first.drain_json().unwrap()).unwrap();
        let new: serde_json::Value = serde_json::from_str(&second.drain_json().unwrap()).unwrap();
        let old_rows = old["gpu"].as_array().unwrap();
        assert_eq!(old_rows.len(), 2);
        assert!(
            old_rows
                .iter()
                .all(|event| event["f"] == 101 && event["x"] == 17)
        );
        assert_eq!(old_rows[0]["b"].as_u64(), Some(u64::MAX - 1));
        assert_eq!(old_rows[0]["e"], 4);
        assert!(old_rows[0]["obs"].is_u64());
        assert!(old_rows[1]["b"].is_null() && old_rows[1]["e"].is_null());
        assert!(old_rows[1]["obs"].is_null());
        assert_eq!(old_rows[1]["st"], "pending");
        let new_rows = new["gpu"].as_array().unwrap();
        assert_eq!(new_rows.len(), 1);
        assert_eq!(new_rows[0]["f"], 202);
        assert_eq!(new_rows[0]["x"], 18);
        assert_eq!(new_rows[0]["st"], "unsubmitted");
    }
}
