//! Bounded world-owned Raw climate inputs. All other evaluation modes stay separate.
use super::{Graph, Id, Node};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::{Mutex, atomic::{AtomicBool, AtomicUsize, Ordering}};
const SIZE: usize = 16384;
const SHARDS: usize = 64;
const ROWS: usize = SIZE / SHARDS;
const BUDGET: usize = 64 * 1024 * 1024;
static LIVE_BYTES: AtomicUsize = AtomicUsize::new(0);
const BYTES: usize = SIZE * std::mem::size_of::<Row>();
const ROOTS: [&str; 6] = ["temperature", "vegetation", "continents", "erosion", "depth", "ridges"];
#[derive(Clone, Copy, Default)]
struct Row { key: u64, mask: u8, values: [f64; 6] }
#[derive(Default)]
struct Shard { rows: Option<Box<[Row]>>, hits: u64, misses: u64, copies: u64 }
pub(super) struct Shared {
    lanes: Vec<u8>, aliases: Value, shards: Vec<Mutex<Shard>>, closed: AtomicBool,
}
fn position(x: i32, z: i32) -> (u64, usize) {
    let key = (x as u32 as u64) << 32 | z as u32 as u64;
    let mut h = key.wrapping_mul(0x9e3779b97f4a7c15); h ^= h >> 32;
    h = h.wrapping_mul(0xbf58476d1ce4e5b9);
    (key, (h ^ (h >> 32)) as usize & (SIZE - 1))
}
impl Shared {
    pub(super) fn new(g: &Graph, intern: &HashMap<String, Id>, doc: &Value) -> Option<Self> {
        if doc["vss_horizontal_climate"].as_bool() == Some(false) || g.stateful_columns { return None; }
        fn cut(g: &Graph, id: Id, out: &mut Vec<Id>) {
            if g.column_plan.slots[id] != super::column_plan::NONE { out.push(id); return; }
            match &g.nodes[id] {
                Node::Marker(_, a) | Node::Unary(_, a) | Node::Clamp(a, ..) => cut(g, *a, out),
                Node::Binary(_, a, b) => { cut(g, *a, out); cut(g, *b, out); }
                _ => {}
            }
        }
        let mut lanes = vec![0; g.nodes.len()];
        let mut aliases = vec![Vec::<Value>::new(); 6];
        // The complete encoded subtree is the identity, not the climate root's name.
        fn expand(v: &Value, doc: &Value, depth: usize, left: &mut usize) -> Option<Value> {
            if depth > 128 || *left == 0 { return None; } *left -= 1;
            if let Some(name) = v.as_str() {
                if let Some(value) = doc["density_functions"].get(name) { return expand(value, doc, depth + 1, left); }
            }
            Some(match v {
                Value::Object(map) => Value::Object(map.iter().map(|(k,v)| Some((k.clone(), expand(v,doc,depth+1,left)?))).collect::<Option<_>>()?),
                Value::Array(list) => Value::Array(list.iter().map(|v| expand(v,doc,depth+1,left)).collect::<Option<_>>()?),
                _ => v.clone(),
            })
        }
        for (lane, name) in ROOTS.iter().enumerate() {
            let Some(&root) = g.roots.get(*name) else { continue; };
            let mut ids = Vec::new(); cut(g, root, &mut ids); ids.sort_unstable(); ids.dedup();
            if ids.len() != 1 { continue; }
            let id = ids[0]; lanes[id] |= 1 << lane;
            for (expression, &node) in intern {
                if node == id {
                    if let Ok(value) = serde_json::from_str(expression) {
                        if let Some(value) = expand(&value, doc, 0, &mut 65536) {
                            if !aliases[lane].contains(&value) { aliases[lane].push(value); }
                        }
                    }
                }
            }
        }
        if lanes.iter().all(|mask| *mask == 0) { return None; }
        LIVE_BYTES.fetch_update(Ordering::AcqRel, Ordering::Relaxed, |used| (used <= BUDGET - BYTES).then_some(used + BYTES)).ok()?;
        Some(Self { lanes, aliases: serde_json::json!(aliases),
            shards: (0..SHARDS).map(|_| Mutex::new(Shard::default())).collect(), closed: AtomicBool::new(false) })
    }
    pub(super) fn extend(&mut self, len: usize, replaced: bool) {
        if replaced { self.close(); self.lanes.fill(0); }
        self.lanes.resize(len, 0);
    }
    pub(super) fn close(&self) {
        if self.closed.swap(true, Ordering::AcqRel) { return; }
        for shard in &self.shards { if let Ok(mut shard) = shard.lock() { shard.rows = None; } }
        LIVE_BYTES.fetch_sub(BYTES, Ordering::AcqRel);
    }
    pub(super) fn get(&self, id: Id, p: [i32;3]) -> Option<f64> {
        let mask = self.lanes.get(id).copied().unwrap_or(0);
        if mask == 0 || self.closed.load(Ordering::Relaxed) { return None; }
        let (key, slot) = position(p[0], p[2]); let mut shard = self.shards[slot % SHARDS].lock().ok()?;
        let row = shard.rows.as_ref().map(|rows| rows[slot / SHARDS]);
        if let Some(row) = row.filter(|row| row.key == key && row.mask & mask != 0) {
            shard.hits += 1; Some(row.values[(row.mask & mask).trailing_zeros() as usize])
        } else { shard.misses += 1; None }
    }
    pub(super) fn put(&self, id: Id, p: [i32;3], value: f64) {
        let mask = self.lanes.get(id).copied().unwrap_or(0);
        if mask == 0 || self.closed.load(Ordering::Relaxed) { return; }
        let (key, slot) = position(p[0], p[2]); let Ok(mut shard) = self.shards[slot % SHARDS].lock() else { return; };
        if self.closed.load(Ordering::Relaxed) { return; }
        let row = &mut shard.rows.get_or_insert_with(|| vec![Row::default(); ROWS].into_boxed_slice())[slot / SHARDS];
        if row.key != key { *row = Row { key, ..Row::default() }; }
        for lane in 0..6 { if mask & (1 << lane) != 0 { row.values[lane] = value; } }
        row.mask |= mask;
    }
    fn lookup(&self, points: &[(i32,i32)]) -> Vec<u8> {
        let mut output = Vec::with_capacity(points.len() * 56);
        for &(x,z) in points {
            let (key, slot) = position(x,z); let mut shard = self.shards[slot % SHARDS].lock().unwrap();
            let row = if self.closed.load(Ordering::Relaxed) { Row::default() } else {
                shard.rows.as_ref().map(|rows| rows[slot / SHARDS]).filter(|r| r.key == key).unwrap_or_default()
            };
            output.extend((row.mask as u64).to_le_bytes());
            for value in row.values { output.extend(value.to_le_bytes()); }
            shard.copies += row.mask.count_ones() as u64;
        }
        output
    }
    fn stats(&self) -> Value {
        let (mut hits, mut misses, mut copies, mut allocated) = (0,0,0,0);
        for shard in &self.shards { let shard = shard.lock().unwrap(); hits += shard.hits; misses += shard.misses; copies += shard.copies;
            if shard.rows.is_some() { allocated += ROWS * std::mem::size_of::<Row>(); } }
        serde_json::json!({"hits":hits,"misses":misses,"copiedLanes":copies,"payloadBytes":allocated,"rows":SIZE,"closed":self.closed.load(Ordering::Relaxed)})
    }
}
impl Drop for Shared { fn drop(&mut self) { self.close(); } }
impl Graph {
    pub(crate) fn horizontal_lookup(&self, points: &[(i32,i32)]) -> Vec<u8> {
        self.horizontal_climate.as_ref().map(|s| s.lookup(points)).unwrap_or_else(|| vec![0;points.len()*56])
    }
    pub(crate) fn horizontal_descriptor(&self) -> Value {
        serde_json::json!({"seed":self.seed,"precision":"fp64","lanes":
            self.horizontal_climate.as_ref().map(|s|s.aliases.clone()).unwrap_or_else(||serde_json::json!([[],[],[],[],[],[]]))})
    }
    pub(crate) fn horizontal_stats(&self) -> Value {
        self.horizontal_climate.as_ref().map(Shared::stats).unwrap_or_else(||serde_json::json!({"disabled":true}))
    }
}
