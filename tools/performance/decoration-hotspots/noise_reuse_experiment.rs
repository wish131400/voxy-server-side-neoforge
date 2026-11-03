//! Isolated prototype: exact primitive reuse, bounded per worker, no world edits.
use std::cell::RefCell;
use std::sync::atomic::{AtomicU64,Ordering};
use serde_json::{json,Value};
static NEXT:AtomicU64=AtomicU64::new(1);
pub(super) fn identity()->u64 { NEXT.fetch_add(1,Ordering::Relaxed) }
#[derive(Clone,Copy,Default)]
struct Entry { owner:u64, key:[u64;4], value:f64 }
#[derive(Default)]
struct Cache { entries:Vec<Entry>, hits:u64, misses:u64 }
thread_local! { static CACHE:RefCell<Cache>=RefCell::new(Cache::default()); }
#[inline]
pub(super) fn sample(owner:u64,key:[u64;4],capacity:usize,compute:impl FnOnce()->f64)->f64 {
    CACHE.with(|cell| {
        let mut cache=cell.borrow_mut();
        if cache.entries.len()!=capacity {cache.entries=vec![Entry::default();capacity];}
        let mut h=key[0].wrapping_mul(0x9e3779b97f4a7c15)^key[1]^key[2].rotate_left(21)^key[3].rotate_left(43);
        h=(h^(h>>30)).wrapping_mul(0xbf58476d1ce4e5b9);
        h=(h^(h>>27)).wrapping_mul(0x94d049bb133111eb);
        let slot=((h^(h>>31)) as usize)&(capacity-1);
        let entry=cache.entries[slot];
        if entry.owner==owner && entry.key==key {cache.hits+=1;return entry.value;}
        cache.misses+=1;
        let value=compute();
        cache.entries[slot]=Entry {owner,key,value};
        value
    })
}
pub(crate) fn snapshot(reset:bool)->Value {CACHE.with(|c| {
    let mut c=c.borrow_mut();let v=json!({"hits":c.hits,"misses":c.misses,
        "slots":c.entries.len(),"bytes":c.entries.capacity()*std::mem::size_of::<Entry>()});
    if reset {c.hits=0;c.misses=0;}v
})}
