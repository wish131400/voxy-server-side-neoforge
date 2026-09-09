use serde_json::Value;
use std::{fs, path::Path};
use vss_native_core::beard::Beard;
#[test]
fn vanilla_structure_terrain_adjustment() {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen/beard.jsonl");
    let mut count = 0;
    for line in fs::read_to_string(path).unwrap().lines() {
        let row: Value = serde_json::from_str(line).unwrap();
        let b = Beard::parse(&row["context"]).unwrap();
        for v in row["samples"].as_array().unwrap() {
            let p = serde_json::from_value(v["pos"].clone()).unwrap();
            let expected = u64::from_str_radix(v["bits"].as_str().unwrap(), 16).unwrap();
            let actual = b.compute(p);
            assert_eq!(
                actual.to_bits(),
                expected,
                "{p:?}: {actual} != {}",
                f64::from_bits(expected)
            );
            count += 1;
        }
    }
    println!("Vanilla structure terrain adjustment bit-exact values={count}");
}
