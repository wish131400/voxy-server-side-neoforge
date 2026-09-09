use std::{fs,time::Instant};
use vss_native_core::{backend::World,perf_probe};
fn main(){
    let a:Vec<_>=std::env::args().collect();
    let doc:serde_json::Value=serde_json::from_str(&fs::read_to_string(&a[1]).unwrap()).unwrap();
    let seed=a[2].parse().unwrap(); let zoom=a[3].parse().unwrap();
    let cx:i32=a[4].parse().unwrap();let cz:i32=a[5].parse().unwrap();
    let w=World::new(seed,zoom,doc).unwrap();
    println!("{}",serde_json::json!({"height":w.terrain.height,"min_y":w.terrain.min_y,
        "cell_width":w.terrain.cell_width,"cell_height":w.terrain.cell_height,
        "order_sensitive":w.terrain.graph.requires_complete_column_order()}));
    for mode in 0..5 {
        perf_probe::reset(); let start=Instant::now();let mut hash=0xcbf29ce484222325u64;
        let name=match mode {
            0|1=>{let cols=w.surface_columns(cx,cz).unwrap();for c in cols.iter(){for v in c.values{hash=(hash^v as u32 as u64).wrapping_mul(0x100000001b3);}} if mode==0{"full_chunk_cold"}else{"full_chunk_warm"}},
            2=>{let points:Vec<_>=(0..64).map(|i|((cx+32)*16+(i%8)*64,(cz+32)*16+(i/8)*64)).collect();
                for c in w.surface_points(&points).unwrap(){for v in c.values{hash=(hash^v as u32 as u64).wrapping_mul(0x100000001b3);}}"sparse_64_cold"},
            _=>{let volume=w.surface_proxy(cx,cz).unwrap();for v in &volume.blocks{hash=(hash^*v as u64).wrapping_mul(0x100000001b3);}if mode==3{"proxy_5x5_cold_24_chunks"}else{"proxy_5x5_warm"}}
        };
        println!("{}",serde_json::json!({"case":name,"elapsed_ms":start.elapsed().as_secs_f64()*1000.,
            "checksum":format!("{hash:016x}"),"work_counts":w.work_counts(),"stages":perf_probe::report()}));
    }
}
