//! Isolated diagnostic. Thread-local recording must not be used as a timing baseline.
use std::{cell::RefCell,collections::HashMap,io::Write};
use serde_json::{json,Value};
#[derive(Clone)]
struct Row { target:[i64;7], bits:[u64;6], stages:u32,calls:u64,misses:u64 }
#[derive(Default)]
struct Trace {stage:u32,rows:HashMap<[i32;3],Row>,calls:[u64;5],misses:[u64;5],changes:u64,
    xz:std::collections::HashSet<[i32;2]>,columns:HashMap<[i32;2],u32>,
    records:[u64;5],record_ns:[u64;5],cold_ns:[u64;5],color_ns:[u64;5]}
thread_local! {static TRACE:RefCell<Trace>=RefCell::new(Trace::default());}
pub(crate) fn stage(stage:u32,reset:bool) {TRACE.with(|t|{let mut t=t.borrow_mut();if reset{*t=Trace::default();}t.stage=stage;});}
pub(crate) fn target(p:[i32;3],target:[i64;7],bits:Option<[u64;6]>) {
    TRACE.with(|t| {let mut t=t.borrow_mut();let stage=t.stage as usize;if stage==0 || stage>3{return;}
        t.calls[stage]+=1;if bits.is_some(){t.misses[stage]+=1;}
        t.xz.insert([p[0],p[2]]);
        if let Some(old)=t.rows.get(&p) {if old.target!=target{t.changes+=1;}}
        let row=t.rows.entry(p).or_insert_with(||Row {target,bits:bits.unwrap_or([0;6]),stages:0,calls:0,misses:0});
        row.stages|=1<<stage;row.calls+=1;
        if let Some(bits)=bits{row.bits=bits;row.misses+=1;}
    });
}
pub(crate) fn lookup(p:[i32;3])->i32 {TRACE.with(|t|{let t=t.borrow();i32::from(t.rows.contains_key(&p))|i32::from(t.xz.contains(&[p[0],p[2]]))*2})}
pub(crate) fn column(p:[i32;2],kind:u32) {TRACE.with(|t|{let mut t=t.borrow_mut();if t.stage==2{*t.columns.entry(p).or_default()|=kind;}});}
pub(crate) fn record(total:u64,cold:u64,color:u64) {
    TRACE.with(|t|{let mut t=t.borrow_mut();let s=t.stage as usize;if s<5{t.records[s]+=1;t.record_ns[s]+=total;t.cold_ns[s]+=cold;t.color_ns[s]+=color;}});
}
pub(crate) fn dump(path:&str)->crate::density::Result<Value> {
    TRACE.with(|t|{let t=t.borrow();let mut f=std::io::BufWriter::new(std::fs::File::create(path).map_err(|e|e.to_string())?);
        for (p,row) in &t.rows {
            for v in p {f.write_all(&v.to_be_bytes()).map_err(|e|e.to_string())?;}
            f.write_all(&row.stages.to_be_bytes()).map_err(|e|e.to_string())?;
            for v in [row.calls,row.misses]{f.write_all(&v.to_be_bytes()).map_err(|e|e.to_string())?;}
            for v in &row.bits{f.write_all(&v.to_be_bytes()).map_err(|e|e.to_string())?;}
            for v in &row.target[..6]{f.write_all(&v.to_be_bytes()).map_err(|e|e.to_string())?;}
        }
        f.flush().map_err(|e|e.to_string())?;
        let mut f=std::io::BufWriter::new(std::fs::File::create(format!("{path}.columns")).map_err(|e|e.to_string())?);
        for (p,kind) in &t.columns {for v in p{f.write_all(&v.to_be_bytes()).map_err(|e|e.to_string())?;}f.write_all(&kind.to_be_bytes()).map_err(|e|e.to_string())?;}
        f.flush().map_err(|e|e.to_string())?;
        Ok(json!({"uniqueCoordinates":t.rows.len(),"targetCalls":t.calls,"targetMisses":t.misses,
            "inconsistentTargets":t.changes,"records":t.records,"recordNs":t.record_ns,"coldNs":t.cold_ns,"colorNs":t.color_ns}))
    })
}
