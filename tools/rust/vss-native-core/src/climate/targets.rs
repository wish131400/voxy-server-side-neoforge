//! Reuse quantized climate coordinates, never the order-sensitive RTree leaf.
//! Work is local to a graph workspace and bounded to 256 direct-mapped slots.
use crate::density::{Graph, Id, Mode, Scratch};

#[derive(Clone)]
struct Entry {
    ids: [Id; 6],
    p: [i32; 3],
    beard: u64,
    target: [i64; 7],
}
#[derive(Default)]
pub(crate) struct Targets {slots: Vec<Option<Entry>>}
impl Targets {
    pub(crate) fn clear(&mut self) {self.slots.fill(None);}
    pub(crate) fn retained_bytes(&self) -> usize {self.slots.capacity()*std::mem::size_of::<Option<Entry>>()}
}
pub(super) fn sample(g: &Graph, ids: &[Id; 6], p: [i32; 3], scratch: &mut Scratch) -> [i64; 7] {
    let h=(p[0] as u32).wrapping_mul(0x9e3779b9)
        ^ (p[1] as u32).wrapping_mul(0x85ebca6b).rotate_left(11)
        ^ (p[2] as u32).wrapping_mul(0xc2b2ae35).rotate_left(21);
    let slot=((h^(h>>16))&255) as usize;
    let beard=scratch.beard.to_bits();
    if let Some(Some(entry))=scratch.climate_targets.slots.get(slot) {
        if entry.ids==*ids && entry.p==p && entry.beard==beard {return entry.target;}
    }
    let mut target=[0;7];
    for i in 0..6 {target[i]=super::quantize(g.compute(ids[i],p,Mode::Raw,scratch) as f32);}
    if scratch.climate_targets.slots.is_empty() {scratch.climate_targets.slots.resize_with(256,||None);}
    scratch.climate_targets.slots[slot]=Some(Entry {ids:*ids,p,beard,target});
    target
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn repeated_colliding_and_alternate_targets_preserve_quantization_and_tree_ties() {
        let root=std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
        let doc=serde_json::from_str(&std::fs::read_to_string(root.join("overworld.json")).unwrap()).unwrap();
        let t=crate::terrain::Terrain::from_document(42,&doc).unwrap();
        let g=&t.graph;
        let ids=crate::climate::climate_ids(g).unwrap();
        let mut scratch=g.raw_scratch().unwrap();
        let mut oracle=g.raw_scratch().unwrap();
        let tree=crate::climate::ParameterTree::from_json(&serde_json::json!([
            {"biome":"test:first","parameters":{"temperature":0,"humidity":0,"continentalness":0,"erosion":0,"depth":0,"weirdness":0,"offset":0}},
            {"biome":"test:second","parameters":{"temperature":0,"humidity":0,"continentalness":0,"erosion":0,"depth":0,"weirdness":0,"offset":0}}
        ])).unwrap();
        for pass in 0..3 {
            let mut alternate=ids;alternate.swap(0,1);
            for i in 0..1024 {
                let p=[(i%17-8)*4,((i+pass)%7-3)*4,(i%23-11)*4];
                for roots in [&ids,&alternate] {
                    let actual=sample(g,roots,p,&mut scratch);
                    let mut expected=[0;7];
                    for j in 0..6 {expected[j]=crate::climate::quantize(g.compute(roots[j],p,Mode::Raw,&mut oracle) as f32);}
                    assert_eq!(actual,expected);
                    // Cached targets must still honour the caller's prior leaf
                    // when two leaves are exactly tied.
                    for preferred in [Some(0),Some(1),None] {
                        let mut a=preferred;let mut b=preferred;
                        assert_eq!(tree.search(actual,&mut a),tree.search(expected,&mut b));
                        assert_eq!(a,b);
                    }
                }
            }
            assert!(scratch.climate_targets.retained_bytes()<=40*1024);
            scratch.reset_chunk(pass*16,-pass*16);
            assert!(scratch.climate_targets.slots.iter().all(Option::is_none));
        }
    }
}
