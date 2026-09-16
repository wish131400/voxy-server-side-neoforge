use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize,Ordering};
use vss_native_core::blocks::{Palette,Volume};
struct Counting;
static DENSE:AtomicUsize=AtomicUsize::new(0);
static ALLOCS:AtomicUsize=AtomicUsize::new(0);
unsafe impl GlobalAlloc for Counting {
 unsafe fn alloc(&self,l:Layout)->*mut u8 {
  ALLOCS.fetch_add(1,Ordering::Relaxed);
  if l.size()==80*384*80*4 {DENSE.fetch_add(1,Ordering::Relaxed);}
  unsafe{System.alloc(l)}
 }
 unsafe fn alloc_zeroed(&self,l:Layout)->*mut u8 {
  ALLOCS.fetch_add(1,Ordering::Relaxed);
  if l.size()==80*384*80*4 {DENSE.fetch_add(1,Ordering::Relaxed);}
  unsafe{System.alloc_zeroed(l)}
 }
 unsafe fn realloc(&self,p:*mut u8,l:Layout,n:usize)->*mut u8 {
  ALLOCS.fetch_add(1,Ordering::Relaxed);
  unsafe{System.realloc(p,l,n)}
 }
 unsafe fn dealloc(&self,p:*mut u8,l:Layout){unsafe{System.dealloc(p,l)}}
}
#[global_allocator]static ALLOCATOR:Counting=Counting;
#[test]
fn sparse_proxy_avoids_dense_allocation_and_reuses_transaction_storage(){
 let path=std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen/blocks.json");
 let json=serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
 let mut palette=Palette::from_json(&json).unwrap();
 let dirt=palette.named("minecraft:dirt").unwrap();
 for s in ["minecraft:water","minecraft:lava","minecraft:ice"]{palette.named(s).unwrap();}
 let columns=vec![[64,64,0,0,dirt as i32,dirt as i32,dirt as i32,0,0,0];6400];
 DENSE.store(0,Ordering::Relaxed);
 let mut v=Volume::proxy([0,-64,0],80,80,384,palette,columns).unwrap();
 let allocations=DENSE.load(Ordering::Relaxed);
 assert_eq!(allocations, 0, "constructing a proxy must never allocate a dense volume");
 assert_eq!(v.blocks.capacity(), 0);
 println!("dense_allocations_during_proxy={allocations} retained_dense_bytes={}",v.blocks.capacity()*4);
 for pass in 0..3 {
  ALLOCS.store(0,Ordering::Relaxed);
  v.begin().unwrap();
  v.set([1,64,1],dirt);
  v.finish(false).unwrap();
  let n=ALLOCS.load(Ordering::Relaxed);
  if pass > 0 { assert_eq!(n, 0, "a repeated rollback must reuse allocated storage"); }
  println!("rollback_transaction={pass} allocations_or_reallocations={n}");
 }
}
