//! Offline real-registry benchmark; no terrain policy or sampling fidelity changes.
use std::{fs, time::Instant};
use serde_json::{json, Value};
use vss_native_core::{backend::World, blocks::Palette};
fn main() {
    let args: Vec<String> = std::env::args().collect();
    let mode = &args[1];
    let start = Instant::now();
    if mode == "legacy" {
        let text = fs::read_to_string(&args[2]).unwrap();
        let bytes = text.len();
        let document: Value = serde_json::from_str(&text).unwrap(); drop(text);
        let parsed = start.elapsed();
        let world = World::new(42, 0, document).unwrap();
        let built = start.elapsed();
        let table = World::state_table(&world.palette).to_string();
        println!("{}", json!({"mode":mode,"inputBytes":bytes,"states":world.palette.states.len(),
            "parseMs":parsed.as_secs_f64()*1000.,"createMs":(built-parsed).as_secs_f64()*1000.,
            "describeMs":(start.elapsed()-built).as_secs_f64()*1000.,"describeBytes":table.len(),
            "totalMs":start.elapsed().as_secs_f64()*1000.}));
    } else {
        let text = fs::read_to_string(&args[2]).unwrap(); let bytes=text.len();
        let doc: Value = serde_json::from_str(&text).unwrap(); drop(text);
        let palette = Palette::from_compact(&doc).unwrap(); drop(doc);
        let prepared = start.elapsed();
        let dimension = fs::read_to_string(&args[3]).unwrap();
        let mut rows = vec![];
        for _ in 0..3 {
            let begin=Instant::now();
            let doc: Value=serde_json::from_str(&dimension).unwrap();
            let parsed=begin.elapsed();
            let world=World::new_with_palette(42,0,doc,Some(palette.clone())).unwrap();
            let built=begin.elapsed();
            let mapping=world.palette.compact_mapping().to_string();
            let elapsed=begin.elapsed();
            // Verify every returned source maps to precisely the same name/properties as the input palette.
            assert_eq!(world.palette.states.as_ref(),palette.states.as_ref());
            rows.push(json!({"states":world.palette.states.len(),"parseMs":parsed.as_secs_f64()*1000.,
                "createMs":(built-parsed).as_secs_f64()*1000.,"describeMs":(elapsed-built).as_secs_f64()*1000.,
                "describeBytes":mapping.len(),"totalMs":elapsed.as_secs_f64()*1000.}));
        }
        println!("{}",json!({"mode":mode,"paletteBytes":bytes,"paletteMs":prepared.as_secs_f64()*1000.,
            "dimensionBytes":dimension.len(),"dimensions":rows}));
    }
}
