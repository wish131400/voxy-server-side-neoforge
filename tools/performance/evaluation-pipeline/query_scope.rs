//! Task-local Raw scratch lease for an isolated experiment.
use super::*;
use crate::density::Scratch;
use std::cell::RefCell;
struct Scope { token: i64, world: Arc<World>, scratch: Option<Scratch> }
thread_local! { static SCOPES: RefCell<Vec<Scope>> = const { RefCell::new(Vec::new()) }; }
pub(crate) fn begin(world: Arc<World>, token: i64) -> Result<()> {
    world.check_active()?;
    SCOPES.with(|scopes| {
        let mut scopes = scopes.borrow_mut();
        if scopes.len() >= 8 { return Err("Raw query scope nesting budget".into()); }
        scopes.push(Scope { token, world, scratch: None }); Ok(())
    })
}
pub(crate) fn end(token: i64) -> Result<()> {
    let scope = SCOPES.with(|scopes| -> Result<Scope> {
        let mut scopes = scopes.borrow_mut();
        if scopes.last().map(|s| s.token) != Some(token) { return Err("Raw query scope order/thread".into()); }
        Ok(scopes.pop().unwrap())
    })?;
    if let Some(scratch) = scope.scratch { scope.world.return_raw_work(scratch)?; }
    Ok(())
}
pub(super) fn take(world: &World) -> Option<Scratch> {
    SCOPES.with(|scopes| scopes.borrow_mut().last_mut()
        .filter(|s| std::ptr::eq(s.world.as_ref(),world)).and_then(|s|s.scratch.take()))
}
pub(super) fn put(world: &World, scratch: Scratch) -> Option<Scratch> {
    SCOPES.with(|scopes| {
        let mut scopes=scopes.borrow_mut();
        if let Some(scope)=scopes.last_mut().filter(|s|std::ptr::eq(s.world.as_ref(),world) && s.scratch.is_none()) {
            scope.scratch=Some(scratch); None
        } else { Some(scratch) }
    })
}

#[cfg(test)]
mod tests {
 use super::*;
 #[test]
 fn scopes_keep_order_thread_world_identity_and_release_on_unwind() {
  let doc=super::super::exterior_tests::document();
  let world=Arc::new(World::new(731,0,doc.clone()).unwrap());let other=Arc::new(World::new(19,0,doc).unwrap());
  begin(world.clone(),1).unwrap();
  let scratch=world.terrain.graph.raw_scratch().unwrap();assert!(put(&world,scratch).is_none());
  assert!(take(&other).is_none());
  begin(world.clone(),2).unwrap();assert!(take(&world).is_none());assert!(end(1).is_err());end(2).unwrap();
  let scratch=take(&world).unwrap();assert!(put(&world,scratch).is_none());
  std::thread::scope(|s|{let world=&world;s.spawn(move||{assert!(take(world).is_none());assert!(end(1).is_err());});});
  end(1).unwrap();assert!(take(&world).is_none());assert!(end(1).is_err());
  struct Guard(i64);impl Drop for Guard {fn drop(&mut self){end(self.0).unwrap();}}
  let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||{begin(world.clone(),3).unwrap();let _guard=Guard(3);panic!("cancel");}));
  assert!(result.is_err());assert!(take(&world).is_none());
  assert_eq!(Arc::strong_count(&world),1);
 }
}
