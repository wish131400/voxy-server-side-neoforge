//! Exact interpolation inputs shared by independent NoiseChunk workspaces.
//! A lease has exclusive access on the hot path. Only lease/return takes a
//! lock; idle tables belong to the graph, never to a permanent worker thread.
use super::Id;
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc, Mutex,
};

const SLOTS: usize = 131072;
const WORLD_TABLES: usize = 4;
const TABLE_BYTES: usize = SLOTS * std::mem::size_of::<Entry>();
const GLOBAL_BYTES_LIMIT: usize = 64 * 1024 * 1024;
static GLOBAL_BYTES: AtomicUsize = AtomicUsize::new(0);

#[derive(Clone, Copy)]
struct Entry {
    node: Id,
    point: [i32; 3],
    value: f64,
}
struct Table {
    entries: Box<[Entry]>,
}
impl Table {
    fn new() -> Option<Self> {
        GLOBAL_BYTES
            .fetch_update(Ordering::AcqRel, Ordering::Relaxed, |used| {
                (used <= GLOBAL_BYTES_LIMIT - TABLE_BYTES).then_some(used + TABLE_BYTES)
            })
            .ok()?;
        let mut entries = Vec::new();
        if entries.try_reserve_exact(SLOTS).is_err() {
            GLOBAL_BYTES.fetch_sub(TABLE_BYTES, Ordering::AcqRel);
            return None;
        }
        entries.resize(
            SLOTS,
            Entry {
                node: Id::MAX,
                point: [0; 3],
                value: 0.,
            },
        );
        Some(Self {
            entries: entries.into_boxed_slice(),
        })
    }
}
impl Drop for Table {
    fn drop(&mut self) {
        GLOBAL_BYTES.fetch_sub(TABLE_BYTES, Ordering::AcqRel);
    }
}
#[derive(Default)]
struct State {
    idle: Vec<Table>,
    allocated: usize,
    closed: bool,
}
#[derive(Default)]
pub(super) struct Pool {
    state: Mutex<State>,
}
impl Pool {
    pub fn take(self: &Arc<Self>) -> Option<Lease> {
        let mut state = self.state.lock().ok()?;
        if state.closed {
            return None;
        }
        let table = if let Some(table) = state.idle.pop() {
            table
        } else {
            if state.allocated >= WORLD_TABLES {
                return None;
            }
            let table = Table::new()?;
            state.allocated += 1;
            table
        };
        Some(Lease {
            pool: self.clone(),
            table: Some(table),
        })
    }
    pub fn close(&self) {
        if let Ok(mut state) = self.state.lock() {
            state.closed = true;
            state.allocated -= state.idle.len();
            state.idle.clear();
        }
    }
}
pub(super) struct Lease {
    pool: Arc<Pool>,
    table: Option<Table>,
}
impl Lease {
    pub fn belongs_to(&self, pool: &Arc<Pool>) -> bool {
        Arc::ptr_eq(&self.pool, pool)
    }
    #[inline]
    fn index(node: Id, p: [i32; 3]) -> usize {
        let mut h = (node as u64 | (1u64 << 62)).wrapping_mul(0x9e3779b97f4a7c15)
            ^ p[0] as u64
            ^ (p[1] as u64).rotate_left(21)
            ^ (p[2] as u64).rotate_left(43);
        h = (h ^ (h >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
        h = (h ^ (h >> 27)).wrapping_mul(0x94d049bb133111eb);
        ((h ^ (h >> 31)) as usize) & (SLOTS - 1)
    }
    #[inline]
    pub fn get(&self, node: Id, point: [i32; 3]) -> Option<f64> {
        let entry = self.table.as_ref()?.entries[Self::index(node, point)];
        (entry.node == node && entry.point == point).then_some(entry.value)
    }
    #[inline]
    pub fn insert(&mut self, node: Id, point: [i32; 3], value: f64) {
        if let Some(table) = self.table.as_mut() {
            table.entries[Self::index(node, point)] = Entry { node, point, value };
        }
    }
}
impl Drop for Lease {
    fn drop(&mut self) {
        if let Some(table) = self.table.take() {
            if let Ok(mut state) = self.pool.state.lock() {
                if !state.closed {
                    state.idle.push(table);
                    return;
                }
                state.allocated -= 1;
            }
            // Closed worlds release in-flight tables as their queries finish.
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bounded_leases_reuse_exact_keys_and_release_on_close() {
        assert_eq!(TABLE_BYTES, 4 * 1024 * 1024);
        let pool = Arc::new(Pool::default());
        let mut leases: Vec<_> = (0..WORLD_TABLES).map(|_| pool.take().unwrap()).collect();
        assert!(pool.take().is_none());
        let p = [-30_000_000, -64, 30_000_000];
        leases[0].insert(7, p, -0.);
        assert_eq!(leases[0].get(7, p).unwrap().to_bits(), (-0f64).to_bits());
        assert!(leases[0].get(8, p).is_none());
        let collision = (0..1_000_000)
            .map(|x| [x, 7, 8])
            .find(|&q| Lease::index(7, q) == Lease::index(7, p))
            .unwrap();
        leases[0].insert(7, collision, f64::INFINITY);
        assert!(leases[0].get(7, p).is_none());
        assert_eq!(leases[0].get(7, collision), Some(f64::INFINITY));
        let first = leases.remove(0);
        drop(first);
        let first = pool.take().unwrap();
        assert_eq!(first.get(7, collision), Some(f64::INFINITY));
        drop(first);
        assert_eq!(pool.state.lock().unwrap().idle.len(), 1);
        pool.close();
        assert!(pool.take().is_none());
        assert_eq!(pool.state.lock().unwrap().idle.len(), 0);
        drop(leases);
        assert_eq!(pool.state.lock().unwrap().allocated, 0);
        assert!(Arc::new(Pool::default())
            .take()
            .unwrap()
            .get(7, collision)
            .is_none());
    }

    #[test]
    fn concurrent_queries_fall_back_without_sharing_mutable_tables() {
        let pool = Arc::new(Pool::default());
        std::thread::scope(|scope| {
            for worker in 0..8 {
                let pool = &pool;
                scope.spawn(move || {
                    for batch in 0..8 {
                        if let Some(mut lease) = pool.take() {
                            for i in 0..256 {
                                let p = [worker * 1024 + i, batch, -i];
                                let value = (worker * 10000 + i) as f64;
                                lease.insert(99, p, value);
                                assert_eq!(lease.get(99, p), Some(value));
                            }
                        }
                    }
                });
            }
        });
        let state = pool.state.lock().unwrap();
        assert!(state.allocated <= WORLD_TABLES);
        assert_eq!(state.allocated, state.idle.len());
    }
}
