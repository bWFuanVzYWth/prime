//! Scene preparation CPU diagnostics; independent from native renderer timings.
use prime_scene::incremental::TranslationWork;
use std::time::Instant;

#[derive(Default)]
struct Batch {
    frames: u64,
    completed: u64,
    sum_ns: [u64; 2],
    max_ns: [u64; 2],
    snapshots: u64,
    dynamics: u64,
    meshes: u64,
    textures: u64,
}

pub(crate) struct PrepareProfile {
    batch: Option<Batch>,
    last: Option<([u64; 2], TranslationWork)>,
}
impl Default for PrepareProfile {
    fn default() -> Self {
        Self {
            batch: std::env::var_os("PRIME_PROFILE_CPU")
                .is_some_and(|value| value == "1")
                .then(Batch::default),
            last: None,
        }
    }
}
impl PrepareProfile {
    #[inline]
    pub fn start(&self) -> Instant {
        Instant::now()
    }

    pub fn observe(&mut self, start: Instant, update_ns: u64, work: TranslationWork) {
        let values = [elapsed(start), update_ns];
        self.last = Some((values, work));
        let Some(batch) = &mut self.batch else { return };
        batch.frames += 1;
        batch.completed += 1;
        batch.snapshots += u64::from(work.snapshot_changed);
        batch.dynamics += u64::from(work.dynamic_changed);
        batch.meshes += work.meshes_published as u64;
        batch.textures += work.textures_published as u64;
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
    pub fn last_report(&self) -> String {
        let Some((ns, work)) = self.last else {
            return "available=false".into();
        };
        format!(
            "prepare_total={:.3} incremental_translate={:.3} meshes_validated={} meshes_published={} textures_published={} snapshot_changed={} dynamic_changed={}",
            ns[0] as f64 / 1e6,
            ns[1] as f64 / 1e6,
            work.meshes_validated,
            work.meshes_published,
            work.textures_published,
            work.snapshot_changed,
            work.dynamic_changed
        )
    }
}

impl Batch {
    fn report(&self) -> String {
        let values: [[f64; 3]; 2] = std::array::from_fn(|i| {
            let sum = self.sum_ns[i] as f64 / 1e6;
            [sum, sum / self.frames as f64, self.max_ns[i] as f64 / 1e6]
        });
        format!(
            "[Prime CPU engine] completed={} frames={} timing_ms=sum/mean/max excludes=renderer,FFM,source_submit,log prepare_total={:.3}/{:.3}/{:.3} incremental_translate={:.3}/{:.3}/{:.3} snapshot_updates={} dynamic_updates={} meshes_published={} textures_published={}",
            self.completed,
            self.frames,
            values[0][0],
            values[0][1],
            values[0][2],
            values[1][0],
            values[1][1],
            values[1][2],
            self.snapshots,
            self.dynamics,
            self.meshes,
            self.textures
        )
    }
}

#[inline]
pub(crate) fn elapsed(start: Instant) -> u64 {
    start.elapsed().as_nanos().min(u128::from(u64::MAX)) as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn coarse_clock_and_last_snapshot_do_not_require_verbose_aggregation() {
        let mut quiet = PrepareProfile {
            batch: None,
            last: None,
        };
        quiet.observe(
            quiet.start() - std::time::Duration::from_millis(1),
            51_000_000,
            TranslationWork {
                meshes_published: 123,
                ..Default::default()
            },
        );
        assert!(quiet.last.unwrap().0[0] >= 1_000_000);
        assert_eq!(quiet.last.unwrap().0[1], 51_000_000);
        assert!(quiet.last_report().contains("meshes_published=123"));
        let mut profile = PrepareProfile {
            batch: Some(Batch::default()),
            last: None,
        };
        for _ in 0..119 {
            profile.observe(
                profile.start(),
                1000,
                TranslationWork {
                    snapshot_changed: true,
                    ..Default::default()
                },
            );
        }
        let batch = profile.batch.as_ref().unwrap();
        assert_eq!(batch.snapshots, 119);
        assert_eq!(batch.sum_ns[1], 119000);
        assert_eq!(batch.max_ns[1], 1000);
        assert!(
            batch
                .report()
                .contains("snapshot_updates=119 dynamic_updates=0")
        );
        profile.observe(
            profile.start(),
            1000,
            TranslationWork {
                dynamic_changed: true,
                ..Default::default()
            },
        );
        let batch = profile.batch.as_ref().unwrap();
        assert_eq!(batch.completed, 120);
        assert_eq!(batch.frames, 0);
        assert_eq!(batch.sum_ns, [0; 2]);
        assert_eq!((batch.snapshots, batch.dynamics), (0, 0));
    }
}
