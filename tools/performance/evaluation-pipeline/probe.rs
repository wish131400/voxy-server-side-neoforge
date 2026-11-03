//! Isolated instrumentation and candidate switches; never ship this module.
use std::{sync::OnceLock, sync::atomic::{AtomicU64, Ordering}, time::Instant};
static MODE: OnceLock<String> = OnceLock::new();
pub fn enabled(name: &str) -> bool {
    MODE.get_or_init(|| std::env::var("VSS_EVAL_MODE").unwrap_or_default())
        .split(',').any(|mode| mode == name)
}
static TRACE: OnceLock<bool> = OnceLock::new();
pub struct Timer { index: usize, start: Option<Instant> }
static NANOS: [AtomicU64; 14] = [const { AtomicU64::new(0) }; 14];
static CALLS: [AtomicU64; 14] = [const { AtomicU64::new(0) }; 14];
pub fn timer(index: usize) -> Timer {
    Timer { index, start: TRACE.get_or_init(|| std::env::var_os("VSS_EVAL_TRACE").is_some()).then(Instant::now) }
}
impl Drop for Timer {
    fn drop(&mut self) { if let Some(start) = self.start {
        NANOS[self.index].fetch_add(start.elapsed().as_nanos() as u64, Ordering::Relaxed);
        CALLS[self.index].fetch_add(1, Ordering::Relaxed);
    } }
}
pub fn snapshot() -> serde_json::Value {
    let names = ["biomeJni", "biomePoint", "answerLookup", "rawTake", "sourceSample", "rawReturn", "answerInsert", "materialNoise", "biomeTake", "biomeReturn", "memo2dHit", "memo3dHit", "memo2dMiss", "memo3dMiss"];
    serde_json::Value::Object(names.iter().enumerate().map(|(i,name)|
        ((*name).into(), serde_json::json!({"calls":CALLS[i].load(Ordering::Relaxed),
            "nanos":NANOS[i].load(Ordering::Relaxed)}))).collect())
}

pub fn count(index:usize) {if *TRACE.get_or_init(||std::env::var_os("VSS_EVAL_TRACE").is_some()) {CALLS[index].fetch_add(1,Ordering::Relaxed);}}
