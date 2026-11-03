//! World-owned immutable height answers. Keep accepting the current region
//! after the budget fills; per-job answers survive eviction independently.
use super::Substance;
use std::collections::{HashMap, VecDeque};

pub(crate) struct TopCache {
    values: HashMap<(i32, i32), Option<(i32, Substance)>>,
    order: VecDeque<(i32, i32)>,
    capacity: usize,
}
impl TopCache {
    pub(crate) fn new(capacity: usize) -> Self {
        assert!(capacity > 0);
        Self { values: HashMap::new(), order: VecDeque::new(), capacity }
    }
    pub(crate) fn get(&self, key: &(i32, i32)) -> Option<&Option<(i32, Substance)>> {
        self.values.get(key)
    }
    pub(crate) fn insert(&mut self, key: (i32, i32), value: Option<(i32, Substance)>) {
        if let Some(old) = self.values.get_mut(&key) {
            *old = value;
            return;
        }
        if self.values.len() == self.capacity {
            if let Some(old) = self.order.pop_front() { self.values.remove(&old); }
        }
        self.order.push_back(key);
        self.values.insert(key, value);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn full_cache_tracks_new_regions_and_updates_do_not_duplicate_queue_entries() {
        let mut cache = TopCache::new(3);
        for x in 0..3 { cache.insert((x, -x), Some((x, Substance::Default))); }
        for _ in 0..100 { cache.insert((1, -1), None); }
        cache.insert((3, -3), None);
        assert!(cache.get(&(0, 0)).is_none());
        assert_eq!(cache.get(&(3, -3)), Some(&None));
        assert_eq!(cache.order.len(), 3);
        for x in 4..1000 { cache.insert((x, -x), Some((x, Substance::Default))); }
        assert_eq!(cache.values.len(), 3);
        assert_eq!(cache.order.len(), 3);
        for x in 997..1000 { assert_eq!(cache.get(&(x, -x)), Some(&Some((x, Substance::Default)))); }
    }
}
