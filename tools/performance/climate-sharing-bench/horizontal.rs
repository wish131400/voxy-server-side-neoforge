//! Isolated world-owned, bounded Raw horizontal input cache. No tree/biome caching.
use super::{Graph,Id,Mode,Node};
use std::sync::Mutex;
const SIZE:usize=16384;
#[derive(Clone,Copy,Default)]
struct Row {key:u64,mask:u8,values:[f64;6]}
struct Data {rows:Vec<Row>,hits:u64,misses:u64,copies:u64}
pub(super) struct Shared {lanes:Vec<u8>,data:Mutex<Data>}
fn slot(k:u64)->usize {let mut h=k.wrapping_mul(0x9e3779b97f4a7c15);h^=h>>32;h=h.wrapping_mul(0xbf58476d1ce4e5b9);((h^(h>>32)) as usize)&(SIZE-1)}
fn key(x:i32,z:i32)->u64 {(x as u32 as u64)<<32|z as u32 as u64}
impl Shared {
    pub(super) fn new(g:&Graph)->Result<Self,String> {
        let mut lanes=vec![255;g.nodes.len()];
        for (i,name) in ["temperature","vegetation","continents","erosion","depth","ridges"].iter().enumerate() {
            fn cut(g:&Graph,id:Id,out:&mut Vec<Id>)->Result<(),String> {
                if g.column_plan.slots[id]!=super::column_plan::NONE {out.push(id);return Ok(())}
                match &g.nodes[id] {
                    Node::Gradient(..)|Node::Constant(_)=>Ok(()),
                    Node::Marker(_,a)|Node::Unary(_,a)|Node::Clamp(a,..)=>cut(g,*a,out),
                    Node::Binary(_,a,b)=>{cut(g,*a,out)?;cut(g,*b,out)},
                    _=>Err("Unsupported vertical climate graph".into())
                }
            }
            let mut ids=Vec::new();cut(g,g.root(name)?,&mut ids)?;
            if ids.len()!=1{return Err(format!("Unexpected horizontal cuts {name}: {}",ids.len()))}
            lanes[ids[0]]=i as u8;
        }
        Ok(Self{lanes,data:Mutex::new(Data{rows:vec![Row::default();SIZE],hits:0,misses:0,copies:0})})
    }
    pub(super) fn get(&self,id:Id,p:[i32;3],mode:Mode)->Option<f64> {
        if mode!=Mode::Raw{return None}let lane=self.lanes[id];if lane==255{return None}
        let k=key(p[0],p[2]);let mut d=self.data.lock().unwrap();let r=d.rows[slot(k)];
        if r.key==k && r.mask&(1<<lane)!=0 {d.hits+=1;Some(r.values[lane as usize])}else{d.misses+=1;None}
    }
    pub(super) fn put(&self,id:Id,p:[i32;3],mode:Mode,value:f64) {
        if mode!=Mode::Raw{return}let lane=self.lanes[id];if lane==255{return}
        let k=key(p[0],p[2]);let mut d=self.data.lock().unwrap();let r=&mut d.rows[slot(k)];
        if r.key!=k{*r=Row{key:k,..Row::default()}}r.values[lane as usize]=value;r.mask|=1<<lane;
    }
    pub(super) fn lookup(&self,points:&[(i32,i32)])->Vec<u8> {
        let mut d=self.data.lock().unwrap();let mut out=Vec::with_capacity(points.len()*56);
        for &(x,z) in points {let k=key(x,z);let r=d.rows[slot(k)];let mask=if r.key==k{r.mask}else{0};out.extend((mask as u64).to_le_bytes());for v in r.values{out.extend(v.to_le_bytes())}d.copies+=mask.count_ones() as u64;}
        out
    }
    pub(super) fn stats(&self,reset:bool)->serde_json::Value {
        let mut d=self.data.lock().unwrap();let v=serde_json::json!({"hits":d.hits,"misses":d.misses,"copiedLanes":d.copies,"rows":SIZE,"payloadBytes":SIZE*std::mem::size_of::<Row>()+self.lanes.len()});
        if reset{d.hits=0;d.misses=0;d.copies=0}v
    }
}
impl Graph {
    pub(crate) fn horizontal_lookup(&self,points:&[(i32,i32)])->Vec<u8> {self.experiment_horizontal.as_ref().map(|s|s.lookup(points)).unwrap_or_else(||vec![0;points.len()*56])}
    pub(crate) fn horizontal_stats(&self,reset:bool)->serde_json::Value {self.experiment_horizontal.as_ref().map(|s|s.stats(reset)).unwrap_or(serde_json::json!({"disabled":true}))}
}
