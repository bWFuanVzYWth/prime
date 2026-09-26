//! Scene preparation CPU diagnostics; independent from native renderer timings.
use std::time::Instant;

#[derive(Default)]
struct Batch {
    frames: u64,
    completed: u64,
    sum_ns: [u64; 3],
    max_ns: [u64; 3],
    snapshots: u64,
    dynamics: u64,
}

pub(crate) struct PrepareProfile(Option<Batch>);
impl Default for PrepareProfile {
    fn default() -> Self {
        Self(
            std::env::var_os("PRIME_PROFILE_CPU")
                .is_some_and(|value| value == "1")
                .then(Batch::default),
        )
    }
}
impl PrepareProfile {
    #[inline]
    pub fn start(&self) -> Option<Instant> {
        self.0.as_ref().map(|_| Instant::now())
    }

    pub fn observe(
        &mut self,
        start: Option<Instant>,
        snapshot_ns: u64,
        dynamic_ns: u64,
        snapshot: bool,
        dynamic: bool,
    ) {
        let Some(batch) = &mut self.0 else { return };
        let values = [elapsed(start), snapshot_ns, dynamic_ns];
        batch.frames += 1;
        batch.completed += 1;
        batch.snapshots += u64::from(snapshot);
        batch.dynamics += u64::from(dynamic);
        for (index, ns) in values.into_iter().enumerate() {
            batch.sum_ns[index] = batch.sum_ns[index].saturating_add(ns);
            batch.max_ns[index] = batch.max_ns[index].max(ns);
        }
        if batch.frames == 120 {
            eprintln!("{}", batch.report());
            let completed = batch.completed;
            *batch = Batch {
                completed,
                ..Default::default()
            };
        }
    }
}

impl Batch {
    fn report(&self) -> String {
        let values: [[f64; 3]; 3] = std::array::from_fn(|i| {
            let sum = self.sum_ns[i] as f64 / 1e6;
            [sum, sum / self.frames as f64, self.max_ns[i] as f64 / 1e6]
        });
        format!(
            "[Prime CPU engine] completed={} frames={} timing_ms=sum/mean/max children=disjoint_within_prepare_total excludes=renderer,FFM,source_submit,log prepare_total={:.3}/{:.3}/{:.3} snapshot_translate={:.3}/{:.3}/{:.3} dynamic_refresh={:.3}/{:.3}/{:.3} snapshot_updates={} dynamic_updates={} snapshot_includes_dynamic_on_rebuild=1",
            self.completed,
            self.frames,
            values[0][0],
            values[0][1],
            values[0][2],
            values[1][0],
            values[1][1],
            values[1][2],
            values[2][0],
            values[2][1],
            values[2][2],
            self.snapshots,
            self.dynamics
        )
    }
}

#[inline]
pub(crate) fn elapsed(start: Option<Instant>) -> u64 {
    start.map_or(0, |start| {
        start.elapsed().as_nanos().min(u128::from(u64::MAX)) as u64
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disabled_has_no_clock_and_completed_batches_clear_only_batch_state() {
        let disabled = PrepareProfile(None);
        assert!(disabled.start().is_none());
        let mut profile = PrepareProfile(Some(Batch::default()));
        for _ in 0..119 {
            profile.observe(None, 1000, 0, true, false);
        }
        let batch = profile.0.as_ref().unwrap();
        assert_eq!(batch.snapshots, 119);
        assert_eq!(batch.sum_ns[1], 119000);
        assert_eq!(batch.max_ns[1], 1000);
        assert!(
            batch
                .report()
                .contains("snapshot_updates=119 dynamic_updates=0")
        );
        profile.observe(None, 0, 1000, false, true);
        let batch = profile.0.as_ref().unwrap();
        assert_eq!(batch.completed, 120);
        assert_eq!(batch.frames, 0);
        assert_eq!(batch.sum_ns, [0; 3]);
        assert_eq!((batch.snapshots, batch.dynamics), (0, 0));
    }
}
