use serde_json::{json, Value};
use vss_native_core::blocks::Palette;
fn document() -> Value {
    let definitions: Value = serde_json::from_str(include_str!("fixtures/worldgen/blocks.json")).unwrap();
    json!({"block_definitions": definitions, "groups": [
        {"name":"minecraft:oak_log", "properties":["axis"], "values":[["z","x","y"]], "codes":[2,0,1]},
        {"name":"minecraft:air", "properties":[], "values":[], "codes":[0]}
    ]})
}
#[test] fn explicit_sources_survive_native_reordering_and_palette_clone() {
    let p = Palette::from_compact(&document()).unwrap();
    let m = p.compact_mapping();
    // Native inserts air before all caller states: source 3 must map to native 0.
    assert_eq!(m["state_sources"], json!([3,0,1,2]));
    assert_eq!(p.states[1].properties["axis"], "y");
    let mut clone = p.clone(); drop(p);
    let extra = clone.named("minecraft:stone").unwrap();
    let m = clone.compact_mapping();
    assert_eq!(m["state_sources"][extra as usize], -1);
    assert_eq!(m["extra_states"], json!([[extra,{"Name":"minecraft:stone"}]]));
}
#[test] fn rejects_corrupt_and_ambiguous_compact_states() {
    for codes in [json!([3]), json!([-1]), json!([1,1])] {
        let mut d = document(); d["groups"][0]["codes"] = codes;
        assert!(Palette::from_compact(&d).is_err());
    }
    let mut d = document(); d["groups"][0]["values"][0] = json!(["x","x","y"]);
    assert!(Palette::from_compact(&d).is_err());
    let mut d = document(); d["groups"][0]["properties"] = json!([]);
    assert!(Palette::from_compact(&d).is_err());
}
