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
        if let Some(pool) = &self.pool
            && output.len() > 1
        {
            let chunk = output.len().div_ceil(self.threads);
            pool.install(|| {
                output
                    .par_chunks_mut(chunk)
                    .zip(states.par_iter_mut())
                    .try_for_each(|(out, state)| operation(state, out))
            })
        } else {
            operation(&mut states[0], output)
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
        if let Some(pool) = &self.pool
            && output.len() >= chunk.saturating_mul(2)
        {
            pool.install(|| {
                output
                    .par_chunks_mut(chunk)
                    .enumerate()
                    .try_for_each(|(i, out)| operation(i * chunk, out))
            })
        } else {
            operation(0, output)
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
}
