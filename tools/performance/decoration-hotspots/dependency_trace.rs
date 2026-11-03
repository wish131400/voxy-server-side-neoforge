//! Experiment-only counters. Never copied into production source sets.
use super::{Graph, Id, Mode, Node};
use rustc_hash::FxHashMap;
use serde_json::{json, Value};
use std::cell::RefCell;

#[derive(Default, Clone)]
struct NodeCounts { calls: [u64;5], dispatch: [u64;5], horizontal: u64, memo: u64 }
#[derive(Default, Clone)]
struct NoiseCounts { calls:u64, sampled:u64, unique:u64 }
#[derive(Default)]
struct Trace {
    nodes: Vec<NodeCounts>, noise: Vec<NoiseCounts>,
    inputs: FxHashMap<(usize,u64,u64,u64),u64>, dropped:u64,
    kinds: Vec<&'static str>, roots: Vec<(String,Id)>,
}
thread_local! { static TRACE: RefCell<Trace> = RefCell::new(Trace::default()); }
fn mode(m:Mode)->usize {match m {Mode::Raw=>0,Mode::Single=>1,Mode::Block=>2,Mode::Cell=>3,Mode::Slice=>4}}
fn kind(n:&Node)->&'static str {match n {
    Node::Constant(..)=>"constant",Node::Gradient(..)=>"gradient",Node::Unary(..)=>"unary",
    Node::Binary(..)=>"binary",Node::Clamp(..)=>"clamp",Node::Range(..)=>"range",
    Node::Marker(..)=>"marker",Node::Noise(..)=>"noise",Node::Shift(..)=>"shift",
    Node::Shifted(..)=>"shifted",Node::Weird(..)=>"weird",Node::Blended(..)=>"blended",
    Node::End(..)=>"end",Node::Spline(..)=>"spline",Node::Beard=>"beard",
    Node::Axis(..)=>"axis",Node::Mix(..)=>"mix",Node::Select(..)=>"select",
    Node::CoordinateShift(..)=>"coordinate_shift",Node::FastNoise(..)=>"fast_noise",
    Node::FtfNoise(..)=>"ftf_noise",Node::FtfUnit(..)=>"ftf_unit",Node::LinearSpline(..)=>"linear_spline",
}}
pub(super) fn call(g:&Graph,id:Id,m:Mode) {TRACE.with(|v| {
    let mut t=v.borrow_mut();
    if t.nodes.len()!=g.nodes.len() {
        t.nodes.resize(g.nodes.len(),NodeCounts::default());
        t.kinds=g.nodes.iter().map(kind).collect();
        t.roots=g.roots.iter().map(|(n,&id)|(n.clone(),id)).collect();
    }
    t.nodes[id].calls[mode(m)]+=1;
});}
pub(super) fn dispatch(id:Id,m:Mode) {TRACE.with(|v|v.borrow_mut().nodes[id].dispatch[mode(m)]+=1);}
pub(super) fn horizontal(id:Id) {TRACE.with(|v|v.borrow_mut().nodes[id].horizontal+=1);}
pub(super) fn memo(id:Id) {TRACE.with(|v|v.borrow_mut().nodes[id].memo+=1);}
pub(super) fn noise(id:usize,x:f64,y:f64,z:f64) {TRACE.with(|v| {
    let mut t=v.borrow_mut();
    if t.noise.len()<=id {t.noise.resize(id+1,NoiseCounts::default());}
    t.noise[id].calls+=1;
    let key=(id,x.to_bits(),y.to_bits(),z.to_bits());
    let mut h=key.1^key.2.rotate_left(21)^key.3.rotate_left(43)^(id as u64).wrapping_mul(0x9e3779b97f4a7c15);
    h=(h^(h>>30)).wrapping_mul(0xbf58476d1ce4e5b9);
    h=(h^(h>>27)).wrapping_mul(0x94d049bb133111eb);
    if (h^(h>>31))&63!=0 {return;}
    t.noise[id].sampled+=1;
    if let Some(n)=t.inputs.get_mut(&key) {*n+=1;}
    else if t.inputs.len()<1_000_000 {t.inputs.insert(key,1);t.noise[id].unique+=1;}
    else {t.dropped+=1;}
});}
pub(crate) fn snapshot(reset:bool)->Value {TRACE.with(|v| {
    let mut t=v.borrow_mut();
    let rows:Vec<_>=t.nodes.iter().enumerate().map(|(id,n)|json!({
        "id":id,"kind":t.kinds.get(id),"calls":n.calls,"dispatch":n.dispatch,
        "horizontalHits":n.horizontal,"memoHits":n.memo,
        "roots":t.roots.iter().filter(|(_,i)|*i==id).map(|(s,_)|s).collect::<Vec<_>>(),
    })).collect();
    let noises:Vec<_>=t.noise.iter().enumerate().map(|(id,n)|json!({
        "id":id,"calls":n.calls,"sampled":n.sampled,"unique":n.unique,
    })).collect();
    let result=json!({"nodes":rows,"noises":noises,"sampleRate":64,"dropped":t.dropped});
    if reset {*t=Trace::default();}
    result
})}
