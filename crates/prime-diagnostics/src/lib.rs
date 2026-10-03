//! Opt-in raw timelines. CPU scopes append to thread-local buffers and flush at context exit.
//! A disabled scope checks TLS only: no clock, allocation, registration or synchronization.
#![forbid(unsafe_code)]

use serde::Serialize;
use std::{
    cell::RefCell,
    collections::HashMap,
    marker::PhantomData,
    rc::Rc,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::Instant,
};

static NEXT_RECORDER: AtomicU64 = AtomicU64::new(1);
thread_local! {
    static LOCAL: RefCell<Local> = const { RefCell::new(Local {
        context: None, recorder_id: 0, shard: None, events: Vec::new(), next_id: 0, end_id: 0,
    }) };
}

struct Local {
    context: Option<Context>,
    recorder_id: u64,
    shard: Option<Arc<Shard>>,
    events: Vec<CpuEvent>,
    next_id: u64,
    end_id: u64,
}

impl Local {
    fn flush(&mut self) {
        if !self.events.is_empty() {
            self.shard
                .as_ref()
                .expect("recorded events have a shard")
                .events
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .append(&mut self.events);
        }
    }

    fn event_id(&mut self, recorder: &Recorder) -> u64 {
        // Reserve IDs in blocks to avoid a shared atomic on every worker span.
        if self.next_id == self.end_id {
            self.next_id = recorder.next_event.fetch_add(1024, Ordering::Relaxed);
            self.end_id = self.next_id + 1024;
        }
        let id = self.next_id;
        self.next_id += 1;
        id
    }
}

struct Shard {
    thread: ThreadMetadata,
    events: Mutex<Vec<CpuEvent>>,
}

/// Session-owned recorder. Draining does not stop the session or discard unfinished scopes.
pub struct Recorder {
    id: u64,
    origin: Instant,
    next_event: AtomicU64,
    next_thread: AtomicU64,
    shards: Mutex<Vec<Arc<Shard>>>,
    gpu: Mutex<Vec<GpuEvent>>,
    dictionaries: Mutex<SessionDictionaries>,
}

impl Recorder {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            id: NEXT_RECORDER.fetch_add(1, Ordering::Relaxed),
            origin: now(),
            next_event: AtomicU64::new(1),
            next_thread: AtomicU64::new(1),
            shards: Mutex::new(Vec::new()),
            gpu: Mutex::new(Vec::new()),
            dictionaries: Mutex::new(SessionDictionaries::default()),
        })
    }

    pub fn enter(self: &Arc<Self>, frame_id: u64) -> ContextGuard {
        Context {
            recorder: Arc::clone(self),
            frame_id,
            parent_id: None,
        }
        .enter()
    }

    /// Monotonic nanoseconds relative to this native session's origin.
    /// It is not a Java, GPU or wall clock; callers must preserve the clock domain.
    pub fn clock_ns(&self) -> u64 {
        self.time_ns(now())
    }

    fn time_ns(&self, instant: Instant) -> u64 {
        instant
            .saturating_duration_since(self.origin)
            .as_nanos()
            .min(u128::from(u64::MAX)) as u64
    }

    fn register_thread(&self) -> Arc<Shard> {
        let thread = std::thread::current();
        let native_id = format!("{:?}", thread.id());
        let mut shards = self.shards.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(shard) = shards.iter().find(|s| s.thread.native_id == native_id) {
            return Arc::clone(shard);
        }
        let shard = Arc::new(Shard {
            thread: ThreadMetadata {
                id: self.next_thread.fetch_add(1, Ordering::Relaxed),
                native_id,
                name: thread.name().map(str::to_owned),
            },
            events: Mutex::new(Vec::new()),
        });
        shards.push(Arc::clone(&shard));
        shard
    }

    pub fn record_gpu(&self, event: GpuEvent) {
        self.gpu
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .push(event);
    }

    /// Destructively drain completed events. Call after synchronous worker joins and the
    /// corresponding outer context exit. Scopes still in flight appear in a later drain.
    /// Name/key IDs stay stable across drains; dict nb/kb locate each incremental suffix.
    /// Consume chunks in session order to reconstruct the dictionaries and thread metadata.
    pub fn drain_json(&self) -> Result<String, serde_json::Error> {
        // Permit draining completed scopes on the current thread before its context exits.
        LOCAL.with(|cell| {
            let mut local = cell.borrow_mut();
            if local.recorder_id == self.id {
                local.flush();
            }
        });
        // Serialize drains and codebook updates; producers never acquire this mutex.
        let mut dictionaries = self.dictionaries.lock().unwrap_or_else(|p| p.into_inner());
        let shards = self
            .shards
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .clone();
        let mut cpu = Vec::new();
        for shard in &shards {
            cpu.append(&mut shard.events.lock().unwrap_or_else(|p| p.into_inner()));
        }
        let gpu = std::mem::take(&mut *self.gpu.lock().unwrap_or_else(|p| p.into_inner()));
        let nb = dictionaries.names.values.len();
        let kb = dictionaries.keys.values.len();
        let tb = dictionaries.threads;
        let cpu_rows: Vec<_> = cpu
            .iter()
            .map(|event| CompactCpu {
                i: event.id,
                p: event.parent_id,
                f: event.frame_id,
                t: event.thread_id,
                n: dictionaries.names.id(event.name),
                s: event.start_ns,
                d: event.duration_ns,
                ok: match event.status {
                    "complete" => 1,
                    "panicked" => 2,
                    _ => 0,
                },
                a: event
                    .summary
                    .iter()
                    .map(|field| (dictionaries.keys.id(field.key), &field.value))
                    .collect(),
            })
            .collect();
        let gpu_rows: Vec<_> = gpu
            .iter()
            .map(|event| CompactGpu {
                f: event.frame_id,
                p: event.parent_id,
                n: dictionaries.names.id(event.name),
                b: event.start_tick,
                e: event.end_tick,
                v: event.timestamp_valid_bits,
                h: event.timestamp_period_ns,
                q: event.queue_id,
                x: event.submission_id,
                st: event.status,
                obs: event.completion_observed_ns,
            })
            .collect();
        // Preserve ownership if serialization fails: no completed record is silently lost.
        let result = serde_json::to_string(&Chunk {
            v: 1,
            r: self.id,
            clk: ["rust_instant_session_ns", "vulkan_queue_ticks"],
            dict: Dictionaries {
                nb,
                n: &dictionaries.names.values[nb..],
                kb,
                k: &dictionaries.keys.values[kb..],
                t: shards[tb..].iter().map(|s| &s.thread).collect(),
            },
            cpu: cpu_rows,
            gpu: gpu_rows,
        });
        if result.is_err() {
            dictionaries.names.rollback(nb);
            dictionaries.keys.rollback(kb);
            // Retain CPU records in their original shard, without changing IDs/timestamps.
            for event in cpu {
                if let Some(shard) = shards.iter().find(|s| s.thread.id == event.thread_id) {
                    shard
                        .events
                        .lock()
                        .unwrap_or_else(|p| p.into_inner())
                        .push(event);
                }
            }
            self.gpu
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .extend(gpu);
        } else {
            dictionaries.threads = shards.len();
        }
        result
    }
}

/// Clone only at synchronous dispatch boundaries; the owning recorder outlives all spans.
#[derive(Clone)]
pub struct Context {
    recorder: Arc<Recorder>,
    frame_id: u64,
    parent_id: Option<u64>,
}

impl Context {
    pub fn enter(&self) -> ContextGuard {
        let previous = LOCAL.with(|cell| {
            let mut local = cell.borrow_mut();
            if local.recorder_id != self.recorder.id {
                local.flush();
                local.recorder_id = self.recorder.id;
                local.shard = Some(self.recorder.register_thread());
                local.next_id = 0;
                local.end_id = 0;
            }
            local.context.replace(self.clone())
        });
        ContextGuard {
            previous,
            _thread_bound: PhantomData,
        }
    }

    pub fn frame_id(&self) -> u64 {
        self.frame_id
    }
    pub fn parent_id(&self) -> Option<u64> {
        self.parent_id
    }
    pub fn clock_ns(&self) -> u64 {
        self.recorder.clock_ns()
    }
    pub fn record_gpu(&self, mut event: GpuEvent) {
        event.frame_id = self.frame_id;
        event.parent_id = self.parent_id;
        self.recorder.record_gpu(event);
    }
}

pub fn capture_context() -> Option<Context> {
    LOCAL.with(|cell| cell.borrow().context.clone())
}
pub fn current_context() -> Option<Context> {
    capture_context()
}
pub fn is_recording() -> bool {
    LOCAL.with(|cell| cell.borrow().context.is_some())
}

pub struct ContextGuard {
    previous: Option<Context>,
    _thread_bound: PhantomData<Rc<()>>,
}

impl Drop for ContextGuard {
    fn drop(&mut self) {
        LOCAL.with(|cell| {
            let mut local = cell.borrow_mut();
            local.flush();
            local.context = self.previous.take();
            // A nested independent recorder must restore the original buffer identity.
            if let Some(context) = local.context.clone()
                && local.recorder_id != context.recorder.id
            {
                local.recorder_id = context.recorder.id;
                local.shard = Some(context.recorder.register_thread());
                local.next_id = 0;
                local.end_id = 0;
            }
        });
    }
}

pub fn scope(name: &'static str) -> SpanGuard {
    create_scope(name, None)
}

/// Reuse an existing stage timer: enabling a trace need not add a second start clock read.
pub fn scope_at(name: &'static str, start: Instant) -> SpanGuard {
    create_scope(name, Some(start))
}

fn create_scope(name: &'static str, start: Option<Instant>) -> SpanGuard {
    let active = LOCAL.with(|cell| {
        let mut local = cell.borrow_mut();
        let context = local.context.clone()?;
        let id = local.event_id(&context.recorder);
        let thread_id = local.shard.as_ref().unwrap().thread.id;
        let start_ns = context.recorder.time_ns(start.unwrap_or_else(now));
        let parent_id = context.parent_id;
        let frame_id = context.frame_id;
        local.context.as_mut().unwrap().parent_id = Some(id);
        Some(ActiveSpan {
            context,
            event: CpuEvent {
                id,
                parent_id,
                frame_id,
                thread_id,
                name,
                start_ns,
                duration_ns: 0,
                status: "complete",
                summary: Vec::new(),
            },
        })
    });
    SpanGuard {
        active,
        _thread_bound: PhantomData,
    }
}

struct ActiveSpan {
    context: Context,
    event: CpuEvent,
}

pub struct SpanGuard {
    active: Option<ActiveSpan>,
    _thread_bound: PhantomData<Rc<()>>,
}

impl SpanGuard {
    pub fn disabled() -> Self {
        Self {
            active: None,
            _thread_bound: PhantomData,
        }
    }
    pub fn rename(&mut self, name: &'static str) {
        if let Some(span) = &mut self.active {
            span.event.name = name;
        }
    }
    pub fn id(&self) -> Option<u64> {
        self.active.as_ref().map(|s| s.event.id)
    }
    pub fn enabled(&self) -> bool {
        self.active.is_some()
    }
    pub fn count(&mut self, key: &'static str, value: u64) {
        if let Some(span) = &mut self.active {
            span.event.summary.push(Field {
                key,
                value: Value::Unsigned(value),
            });
        }
    }
    pub fn value(&mut self, key: &'static str, value: &str) {
        if let Some(span) = &mut self.active {
            span.event.summary.push(Field {
                key,
                value: Value::Text(value.to_owned()),
            });
        }
    }
    /// Formatting or other summary work runs only for an enabled span.
    pub fn value_with(&mut self, key: &'static str, value: impl FnOnce() -> String) {
        if let Some(span) = &mut self.active {
            span.event.summary.push(Field {
                key,
                value: Value::Text(value()),
            });
        }
    }
    pub fn fail(&mut self) {
        if let Some(span) = &mut self.active {
            span.event.status = "failed";
        }
    }
    pub fn succeed(&mut self) {
        if let Some(span) = &mut self.active {
            span.event.status = "complete";
        }
    }
    /// Reuse a caller's existing elapsed duration to avoid a second end clock read.
    pub fn finish_duration(mut self, duration: std::time::Duration) {
        self.finish(Some(duration.as_nanos().min(u128::from(u64::MAX)) as u64));
    }
    fn finish(&mut self, duration_ns: Option<u64>) {
        let Some(mut span) = self.active.take() else {
            return;
        };
        span.event.duration_ns = duration_ns.unwrap_or_else(|| {
            span.context
                .recorder
                .clock_ns()
                .saturating_sub(span.event.start_ns)
        });
        if std::thread::panicking() {
            span.event.status = "panicked";
        }
        LOCAL.with(|cell| {
            let mut local = cell.borrow_mut();
            if let Some(context) = &mut local.context {
                context.parent_id = span.context.parent_id;
            }
            local.events.push(span.event);
        });
    }
}

impl Drop for SpanGuard {
    fn drop(&mut self) {
        self.finish(None);
    }
}

#[derive(Serialize)]
pub struct CpuEvent {
    pub id: u64,
    pub parent_id: Option<u64>,
    pub frame_id: u64,
    pub thread_id: u64,
    pub name: &'static str,
    pub start_ns: u64,
    pub duration_ns: u64,
    pub status: &'static str,
    pub summary: Vec<Field>,
}

#[derive(Serialize)]
pub struct Field {
    pub key: &'static str,
    pub value: Value,
}
#[derive(Serialize)]
#[serde(untagged)]
pub enum Value {
    Unsigned(u64),
    Text(String),
}

#[derive(Serialize)]
pub struct ThreadMetadata {
    #[serde(rename = "i")]
    pub id: u64,
    #[serde(rename = "r")]
    pub native_id: String,
    #[serde(rename = "n", skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

/// Raw Vulkan timestamps retain their queue domain, period, valid bits and submission.
/// Pending/unsubmitted observations use None ticks; they do not claim a zero duration.
#[derive(Serialize)]
pub struct GpuEvent {
    pub frame_id: u64,
    pub parent_id: Option<u64>,
    pub name: &'static str,
    pub start_tick: Option<u64>,
    pub end_tick: Option<u64>,
    pub timestamp_valid_bits: u32,
    pub timestamp_period_ns: f64,
    pub queue_id: u64,
    pub submission_id: u64,
    pub status: &'static str,
    pub completion_observed_ns: Option<u64>,
}

#[derive(Serialize)]
struct Chunk<'a> {
    v: u32,
    r: u64,
    clk: [&'static str; 2],
    dict: Dictionaries<'a>,
    cpu: Vec<CompactCpu<'a>>,
    gpu: Vec<CompactGpu>,
}
#[derive(Serialize)]
struct Dictionaries<'a> {
    nb: usize,
    n: &'a [&'static str],
    kb: usize,
    k: &'a [&'static str],
    t: Vec<&'a ThreadMetadata>,
}
#[derive(Serialize)]
struct CompactCpu<'a> {
    i: u64,
    p: Option<u64>,
    f: u64,
    t: u64,
    n: u32,
    s: u64,
    d: u64,
    ok: u8,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    a: Vec<(u32, &'a Value)>,
}
#[derive(Serialize)]
struct CompactGpu {
    f: u64,
    p: Option<u64>,
    n: u32,
    b: Option<u64>,
    e: Option<u64>,
    v: u32,
    h: f64,
    q: u64,
    x: u64,
    st: &'static str,
    obs: Option<u64>,
}
#[derive(Default)]
struct Codebook {
    values: Vec<&'static str>,
    indices: HashMap<&'static str, u32>,
}
impl Codebook {
    fn rollback(&mut self, len: usize) {
        for value in self.values.drain(len..) {
            self.indices.remove(value);
        }
    }
    fn id(&mut self, value: &'static str) -> u32 {
        *self.indices.entry(value).or_insert_with(|| {
            let id = self.values.len() as u32;
            self.values.push(value);
            id
        })
    }
}
#[derive(Default)]
struct SessionDictionaries {
    names: Codebook,
    keys: Codebook,
    threads: usize,
}

fn now() -> Instant {
    #[cfg(test)]
    CLOCK_READS.with(|count| count.set(count.get() + 1));
    Instant::now()
}

#[cfg(test)]
thread_local! { static CLOCK_READS: std::cell::Cell<u64> = const { std::cell::Cell::new(0) }; }

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value as Json;
    use std::sync::Barrier;

    fn rows(json: &Json) -> &[Json] {
        json["cpu"].as_array().unwrap()
    }
    fn name<'a>(json: &'a Json, event: &Json) -> &'a str {
        let index = event["n"].as_u64().unwrap() - json["dict"]["nb"].as_u64().unwrap();
        json["dict"]["n"][index as usize].as_str().unwrap()
    }

    #[test]
    fn disabled_has_no_clocks_or_registration_and_lazy_summary_is_not_called() {
        assert!(capture_context().is_none());
        let recorder = Recorder::new();
        let before = CLOCK_READS.with(|v| v.get());
        for _ in 0..1000 {
            let mut span = scope("disabled");
            assert!(!span.enabled());
            span.count("ignored", 17);
            span.value_with("ignored", || panic!("disabled summary evaluated"));
            span.fail();
        }
        assert_eq!(CLOCK_READS.with(|v| v.get()), before);
        assert!(recorder.shards.lock().unwrap().is_empty());
    }

    #[test]
    fn compact_json_preserves_integers_escaping_parent_failure_and_drain_tail() {
        let recorder = Recorder::new();
        let parent;
        {
            let _context = recorder.enter(u64::MAX - 1);
            let root = scope("native.frame");
            parent = root.id().unwrap();
            {
                let mut child = scope("escaped\"stage\n");
                child.count("large", u64::MAX);
                child.value("detail", "quote\" newline\n 中文");
                child.fail();
                child.finish_duration(std::time::Duration::from_nanos(9_007_199_254_740_993));
            }
        }
        let text = recorder.drain_json().unwrap();
        let json: Json = serde_json::from_str(&text).unwrap();
        assert_eq!(rows(&json).len(), 2);
        let child = rows(&json)
            .iter()
            .find(|r| name(&json, r).starts_with("escaped"))
            .unwrap();
        assert_eq!(child["p"].as_u64(), Some(parent));
        assert_eq!(child["f"].as_u64(), Some(u64::MAX - 1));
        assert_eq!(child["d"].as_u64(), Some(9_007_199_254_740_993));
        assert_eq!(child["ok"], 0);
        assert_eq!(child["a"][0][1].as_u64(), Some(u64::MAX));
        assert_eq!(child["a"][1][1], "quote\" newline\n 中文");
        assert!(!text.contains("\n"));
        let empty: Json = serde_json::from_str(&recorder.drain_json().unwrap()).unwrap();
        assert!(rows(&empty).is_empty());
        // A second context on the same OS thread has the same timeline thread identity.
        {
            let _context = recorder.enter(8);
            let _scope = scope("tail");
        }
        let tail: Json = serde_json::from_str(&recorder.drain_json().unwrap()).unwrap();
        assert_eq!(rows(&tail).len(), 1);
        assert!(tail["dict"]["t"].as_array().unwrap().is_empty());
        assert_eq!(tail["dict"]["nb"], 2);
        assert_eq!(rows(&tail)[0]["t"], child["t"]);
        {
            let _context = recorder.enter(9);
            let mut repeated = scope("escaped\"stage\n");
            repeated.count("large", 6);
        }
        let repeated: Json = serde_json::from_str(&recorder.drain_json().unwrap()).unwrap();
        assert!(repeated["dict"]["n"].as_array().unwrap().is_empty());
        assert!(repeated["dict"]["k"].as_array().unwrap().is_empty());
        assert_eq!(rows(&repeated)[0]["n"], child["n"]);
        assert_eq!(rows(&repeated)[0]["a"][0][0], child["a"][0][0]);
    }

    #[test]
    fn nested_sessions_restore_parent_and_thread_identity() {
        let a = Recorder::new();
        let b = Recorder::new();
        let parent;
        {
            let _context = a.enter(11);
            let root = scope("a.root");
            parent = root.id().unwrap();
            {
                let _other = b.enter(22);
                let _scope = scope("b.root");
            }
            assert_eq!(capture_context().unwrap().frame_id(), 11);
            assert_eq!(capture_context().unwrap().parent_id(), Some(parent));
            let _child = scope("a.child");
        }
        assert!(capture_context().is_none());
        let aj: Json = serde_json::from_str(&a.drain_json().unwrap()).unwrap();
        let bj: Json = serde_json::from_str(&b.drain_json().unwrap()).unwrap();
        assert_eq!(rows(&aj).len(), 2);
        assert_eq!(rows(&bj).len(), 1);
        assert_eq!(aj["dict"]["t"].as_array().unwrap().len(), 1);
        let child = rows(&aj)
            .iter()
            .find(|r| name(&aj, r) == "a.child")
            .unwrap();
        assert_eq!(child["p"].as_u64(), Some(parent));
    }

    #[test]
    fn multithread_spans_overlap_share_parent_and_flush_on_worker_exit() {
        let recorder = Recorder::new();
        let barrier = Arc::new(Barrier::new(3));
        let parent;
        {
            let _context = recorder.enter(91);
            let root = scope("dispatch");
            parent = root.id().unwrap();
            let context = capture_context().unwrap();
            std::thread::scope(|threads| {
                for i in 0..2 {
                    let context = context.clone();
                    let barrier = Arc::clone(&barrier);
                    threads.spawn(move || {
                        assert!(capture_context().is_none());
                        {
                            let _guard = context.enter();
                            let mut span = scope("worker");
                            span.count("index", i);
                            barrier.wait();
                            barrier.wait();
                        }
                        assert!(capture_context().is_none());
                    });
                }
                barrier.wait();
                barrier.wait();
            });
        }
        let json: Json = serde_json::from_str(&recorder.drain_json().unwrap()).unwrap();
        let workers: Vec<_> = rows(&json)
            .iter()
            .filter(|r| name(&json, r) == "worker")
            .collect();
        assert_eq!(workers.len(), 2);
        assert_ne!(workers[0]["t"], workers[1]["t"]);
        for worker in &workers {
            assert_eq!(worker["f"], 91);
            assert_eq!(worker["p"].as_u64(), Some(parent));
        }
        let start = |w: &Json| w["s"].as_u64().unwrap();
        let end = |w: &Json| start(w) + w["d"].as_u64().unwrap();
        assert!(start(workers[0]) <= end(workers[1]));
        assert!(start(workers[1]) <= end(workers[0]));
        assert_eq!(json["dict"]["t"].as_array().unwrap().len(), 3);
    }

    #[test]
    fn panic_and_pending_gpu_are_recorded_without_fabricated_duration() {
        let recorder = Recorder::new();
        {
            let _context = recorder.enter(7);
            let result = std::panic::catch_unwind(|| {
                let _span = scope("panic");
                panic!("injected");
            });
            assert!(result.is_err());
            current_context().unwrap().record_gpu(GpuEvent {
                frame_id: 0,
                parent_id: None,
                name: "vk.pt",
                start_tick: None,
                end_tick: None,
                timestamp_valid_bits: 36,
                timestamp_period_ns: 0.5,
                queue_id: 2,
                submission_id: 19,
                status: "pending",
                completion_observed_ns: None,
            });
        }
        let json: Json = serde_json::from_str(&recorder.drain_json().unwrap()).unwrap();
        assert_eq!(rows(&json)[0]["ok"], 2);
        assert_eq!(json["gpu"][0]["f"], 7);
        assert!(json["gpu"][0]["b"].is_null());
        assert!(json["gpu"][0]["e"].is_null());
        assert_eq!(json["gpu"][0]["st"], "pending");
    }
}
