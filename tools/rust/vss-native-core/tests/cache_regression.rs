//! Cached native computations must preserve output across cell boundaries,
//! interpolation modes and independently edited volume palettes.
use serde_json::{json, Value};
use std::{fs, path::Path};
use vss_native_core::{
    blocks::Palette,
    density::{Graph, Mode},
};

fn fixture(name: &str) -> Value {
    serde_json::from_str(
        &fs::read_to_string(
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("tests/fixtures/worldgen")
                .join(name),
        )
        .unwrap(),
    )
    .unwrap()
}

#[test]
fn reused_interpolators_match_fresh_sampling_across_boundaries_and_modes() {
    let doc = fixture("overworld.json");
    let mut count = 0;
    for seed in [0, -918273645] {
        let graph = Graph::from_document(seed, &doc).unwrap();
        for (width, height) in [(4, 8), (8, 4)] {
            let mut reused = graph.scratch(-16, -16, width, height).unwrap();
            // Y runs through the same cell, crosses its edge and revisits it.
            // Distinct interpolators and Mode::Raw queries interleave with it.
            for (x, z) in [(-16, -16), (-1, -1), (0, 0), (7, 8), (-16, -16)] {
                for y in [-9, -8, -7, -6, -1, 0, 1, 2, 7, 8, 9, 8, -8] {
                    let p = [x, y, z];
                    for name in ["final_density", "vein_toggle", "vein_ridged"] {
                        let id = graph.root(name).unwrap();
                        for mode in [Mode::Cell, Mode::Block, Mode::Raw, Mode::Cell] {
                            let actual = graph.compute(id, p, mode, &mut reused);
                            let mut fresh = graph.scratch(-16, -16, width, height).unwrap();
                            let expected = graph.compute(id, p, mode, &mut fresh);
                            assert_eq!(
                                actual.to_bits(),
                                expected.to_bits(),
                                "seed={seed} cell=({width},{height}) {name} {p:?} {mode:?}"
                            );
                            count += 1;
                        }
                    }
                }
            }
        }
    }
    println!("reused/fresh interpolation comparisons={count}");
}

#[test]
fn registered_flags_follow_states_and_keep_cloned_palettes_isolated() {
    let mut original = Palette::from_json(&fixture("blocks.json")).unwrap();
    let air = original.air;
    let dry = original.named("minecraft:oak_leaves").unwrap();
    let water = original.named("minecraft:water").unwrap();
    let original_len = original.states.len();
    let mut branch = original.clone();
    let wet = branch.with(dry, "waterlogged", "true").unwrap();
    assert!(branch.fluid(wet));
    assert!(!branch.fluid(dry));
    assert!(branch.fluid(water));
    assert!(!branch.is_air(wet));
    assert!(branch.tag(wet, "minecraft:leaves"));
    assert!(branch.is_air(air));
    assert_eq!(branch.with(wet, "waterlogged", "false").unwrap(), dry);
    assert_eq!(original.states.len(), original_len);
    assert!(!original.fluid(dry));
    // Force different local IDs to ensure metadata follows each palette.
    original.named("minecraft:dirt").unwrap();
    let original_wet = original
        .intern(&json!({
            "Name":"minecraft:oak_leaves", "Properties":{"waterlogged":"true"}
        }))
        .unwrap();
    assert_ne!(wet, original_wet);
    assert!(original.fluid(original_wet));
    assert!(branch.fluid(wet));
    assert!(original.with(dry, "waterlogged", "invalid").is_err());
    assert_eq!(
        branch.definition(wet).motion_blocking,
        original.definition(original_wet).motion_blocking
    );
}
