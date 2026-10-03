//! Private synchronous fork/join. No global pool, escaping borrow or GPU/Java handle.
use rayon::prelude::*;

pub struct CpuWorkers {
    pool: Option<rayon::ThreadPool>,
    threads: usize,
}

impl CpuWorkers {
    pub fn new(threads: usize) -> Result<Self, String> {
        if threads == 0 {
            return Err("CPU worker count must be positive".into());
        }
        let pool = if threads == 1 {
            None
        } else {
            Some(
                rayon::ThreadPoolBuilder::new()
                    .num_threads(threads)
                    .thread_name(|i| format!("prime-cpu-{i}"))
                    .build()
                    .map_err(|e| format!("Create private CPU pool: {e}"))?,
            )
        };
        Ok(Self { pool, threads })
    }
    pub fn configured() -> Result<Self, String> {
        let threads = match std::env::var("PRIME_CPU_THREADS") {
            Ok(value) => value.parse().map_err(|_| "Invalid PRIME_CPU_THREADS")?,
            Err(std::env::VarError::NotPresent) => std::thread::available_parallelism()
                .map_or(1, usize::from)
                .min(8),
            Err(_) => return Err("Invalid PRIME_CPU_THREADS".into()),
        };
        Self::new(threads)
    }
    pub fn threads(&self) -> usize {
        self.threads
    }

    /// Partition a batch across caller-owned scratch states. States survive the call;
    /// neither inputs nor scratch can escape the synchronous join on success or error.
    pub fn batches_mut<T: Send, S: Send>(
        &self,
        states: &mut [S],
        output: &mut [T],
        operation: impl Fn(&mut S, &mut [T]) -> Result<(), String> + Send + Sync,
    ) -> Result<(), String> {
        assert_eq!(states.len(), self.threads);
        let context = prime_diagnostics::capture_context();
        let run = |index: usize, state: &mut S, out: &mut [T], start: usize| {
            let Some(context) = &context else {
                return operation(state, out);
            };
            let _context = context.enter();
            let mut span = prime_diagnostics::scope("cpu.batch");
            span.count("batch", index as u64);
            span.count("start", start as u64);
            span.count("items", out.len() as u64);
            let result = operation(state, out);
            if result.is_err() {
                span.fail();
            }
            result
        };
        if let Some(pool) = &self.pool
            && output.len() > 1
        {
            let chunk = output.len().div_ceil(self.threads);
            pool.install(|| {
                output
                    .par_chunks_mut(chunk)
                    .zip(states.par_iter_mut())
                    .enumerate()
                    .try_for_each(|(index, (out, state))| run(index, state, out, index * chunk))
            })
        } else {
            run(0, &mut states[0], output, 0)
        }
    }

    /// Every worker owns a disjoint output range. Partial output stays unpublished on
    /// error; Rayon joins all launched work before returning or propagating a panic.
    pub fn chunks_mut<T: Send, F: Fn(usize, &mut [T]) -> Result<(), String> + Send + Sync>(
        &self,
        output: &mut [T],
        chunk: usize,
        operation: F,
    ) -> Result<(), String> {
        assert!(chunk > 0);
        let context = prime_diagnostics::capture_context();
        let run = |start: usize, out: &mut [T]| {
            let Some(context) = &context else {
                return operation(start, out);
            };
            let _context = context.enter();
            let mut span = prime_diagnostics::scope("cpu.chunk");
            span.count("start", start as u64);
            span.count("items", out.len() as u64);
            let result = operation(start, out);
            if result.is_err() {
                span.fail();
            }
            result
        };
        if let Some(pool) = &self.pool
            && output.len() >= chunk.saturating_mul(2)
        {
            pool.install(|| {
                output
                    .par_chunks_mut(chunk)
                    .enumerate()
                    .try_for_each(|(i, out)| run(i * chunk, out))
            })
        } else {
            run(0, output)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    #[test]
    fn disjoint_outputs_match_and_failure_joins_before_return() {
        let one = CpuWorkers::new(1).unwrap();
        let many = CpuWorkers::new(4).unwrap();
        let mut expected = vec![0u64; 100_000];
        let mut actual = expected.clone();
        let operation = |start: usize, out: &mut [u64]| {
            for (i, value) in out.iter_mut().enumerate() {
                *value = (start + i) as u64 * 37;
            }
            Ok(())
        };
        one.chunks_mut(&mut expected, 4096, operation).unwrap();
        many.chunks_mut(&mut actual, 4096, operation).unwrap();
        assert_eq!(actual, expected);
        let live = AtomicUsize::new(0);
        assert!(
            many.chunks_mut(&mut actual, 4096, |start, out| {
                live.fetch_add(1, Ordering::SeqCst);
                out.fill(7);
                live.fetch_sub(1, Ordering::SeqCst);
                if start == 0 {
                    Err("injected failure".into())
                } else {
                    Ok(())
                }
            })
            .is_err()
        );
        assert_eq!(live.load(Ordering::SeqCst), 0);
        many.chunks_mut(&mut actual, 4096, operation).unwrap();
        assert_eq!(actual, expected);
    }

    #[test]
    fn scratch_batches_reuse_state_and_join_failures() {
        let workers = CpuWorkers::new(4).unwrap();
        let mut states = vec![Vec::new(); 4];
        let mut output = vec![0_usize; 17];
        for run in 1..=2 {
            workers
                .batches_mut(&mut states, &mut output, |state, values| {
                    state.push(values.len());
                    values.fill(state.len());
                    Ok(())
                })
                .unwrap();
            assert!(output.iter().all(|v| *v == run));
        }
        let live = AtomicUsize::new(0);
        assert!(
            workers
                .batches_mut(&mut states, &mut output, |_, values| {
                    live.fetch_add(1, Ordering::SeqCst);
                    values.fill(3);
                    live.fetch_sub(1, Ordering::SeqCst);
                    Err("injected batch failure".into())
                })
                .is_err()
        );
        assert_eq!(live.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn traces_propagate_dispatch_context_to_tasks_and_restore_workers_after_failure() {
        let workers = CpuWorkers::new(4).unwrap();
        let recorder = prime_diagnostics::Recorder::new();
        let mut output = vec![0_usize; 16];
        let parent;
        {
            let _context = recorder.enter(71);
            let dispatch = prime_diagnostics::scope("test.dispatch");
            parent = dispatch.id().unwrap();
            workers
                .chunks_mut(&mut output, 2, |start, values| {
                    let context = prime_diagnostics::capture_context().unwrap();
                    assert_eq!(context.frame_id(), 71);
                    assert!(context.parent_id().is_some());
                    values.fill(start);
                    Ok(())
                })
                .unwrap();
            let mut states = [0_usize; 4];
            assert!(
                workers
                    .batches_mut(&mut states, &mut output, |_, _| {
                        assert_eq!(prime_diagnostics::capture_context().unwrap().frame_id(), 71);
                        Err("injected".into())
                    })
                    .is_err()
            );
        }
        workers
            .chunks_mut(&mut output, 2, |_, _| {
                assert!(prime_diagnostics::capture_context().is_none());
                Ok(())
            })
            .unwrap();
        let json: serde_json::Value =
            serde_json::from_str(&recorder.drain_json().unwrap()).unwrap();
        let names = json["dict"]["n"].as_array().unwrap();
        let mut chunks = 0;
        let mut failed_batches = 0;
        for event in json["cpu"].as_array().unwrap() {
            let name = names[event["n"].as_u64().unwrap() as usize]
                .as_str()
                .unwrap();
            if matches!(name, "cpu.chunk" | "cpu.batch") {
                assert_eq!(event["f"], 71);
                assert_eq!(event["p"].as_u64(), Some(parent));
                if name == "cpu.chunk" {
                    chunks += 1;
                    assert_eq!(event["ok"], 1);
                } else {
                    failed_batches += 1;
                    assert_eq!(event["ok"], 0);
                }
            }
        }
        assert_eq!(chunks, 8);
        assert!(failed_batches > 0);
    }
}
