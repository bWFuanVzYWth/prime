//! Runtime-gated coarse timers share the same start/duration with raw tracing.
use std::time::{Duration, Instant};

#[derive(Clone, Copy)]
pub(super) struct StageTimer(Option<Instant>);

impl StageTimer {
    pub(super) fn new(diagnostics: bool) -> Self {
        Self((diagnostics || prime_diagnostics::is_recording()).then(clock))
    }
    pub(super) fn elapsed(self) -> Duration {
        self.0
            .map_or(Duration::ZERO, |start| clock().duration_since(start))
    }
    pub(super) fn scope(self, name: &'static str) -> prime_diagnostics::SpanGuard {
        self.0
            .map_or_else(prime_diagnostics::SpanGuard::disabled, |start| {
                prime_diagnostics::scope_at(name, start)
            })
    }
    pub(super) fn enabled(self) -> bool {
        self.0.is_some()
    }
}

fn clock() -> Instant {
    #[cfg(test)]
    READS.with(|v| v.set(v.get() + 1));
    Instant::now()
}

#[cfg(test)]
thread_local! { static READS: std::cell::Cell<u64> = const { std::cell::Cell::new(0) }; }
#[cfg(test)]
pub(super) fn clock_reads() -> u64 {
    READS.with(|v| v.get())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn off_timers_read_no_clocks_and_recording_reuses_timer_duration() {
        let before = READS.with(|v| v.get());
        let timer = StageTimer::new(false);
        assert!(!timer.enabled());
        assert_eq!(timer.elapsed(), Duration::ZERO);
        assert!(!timer.scope("off").enabled());
        assert_eq!(READS.with(|v| v.get()), before);
        let recorder = prime_diagnostics::Recorder::new();
        {
            let _context = recorder.enter(5);
            let timer = StageTimer::new(false);
            let span = timer.scope("on");
            assert!(span.enabled());
            span.finish_duration(timer.elapsed());
        }
        assert_eq!(READS.with(|v| v.get()), before + 2);
    }
}
