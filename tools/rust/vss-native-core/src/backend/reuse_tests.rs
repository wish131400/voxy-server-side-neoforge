use super::*;

#[test]
fn top_cache_eviction_keeps_new_answers_and_exact_records() {
    let doc = exterior_tests::document();
    let mut world = World::new(42,0,doc.clone()).unwrap();
    world.neighbour_tops = Mutex::new(crate::terrain::TopCache::new(8));
    let oracle = World::new(42,0,doc).unwrap();
    for (x,z) in [(0,0),(-512,512),(512,-512),(0,0),(-16,-16)] {
        assert_eq!(world.surface_point(x,z).unwrap().values,oracle.surface_point(x,z).unwrap().values);
    }
    let (x,z)=(4096,-4096);
    let mut job=world.terrain.job(x,z,false).unwrap();
    world.attach_tops(&mut job);
    let expected=job.surface_top(x,z);
    assert_eq!(world.neighbour_tops.lock().unwrap().get(&(x,z)),Some(&expected));
    let mut second=world.terrain.job(x,z,false).unwrap();
    world.attach_tops(&mut second);
    assert_eq!(second.surface_top(x,z),expected);
}
