//! The accelerated query must prove air, not assume a monotone height field.
use serde_json::{json, Value};
use std::{fs, path::Path};
use vss_native_core::terrain::{Substance, Terrain};

fn fixture(name: &str) -> Value {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    serde_json::from_str(&fs::read_to_string(root.join(name)).unwrap()).unwrap()
}

fn check(t: &Terrain, x: i32, z: i32) -> usize {
    let mut fast = t.job(x, z, false).unwrap();
    let mut linear = t.job(x, z, false).unwrap();
    let mut skipped = 0;
    // Also compare below the surface: this exercises unequal aquifer levels
    // and cave pressures, even though the production exterior loop stops sooner.
    let mut y = t.min_y + t.height - 1;
    while y >= t.min_y {
        if let Some(bottom) = fast.proven_air_bottom([x, y, z]) {
            for yy in (bottom..=y).rev() {
                assert_eq!(linear.block([x, yy, z]), Substance::Air,
                    "false air proof at {x},{yy},{z} (interval {bottom}..{y})");
                skipped += 1;
            }
            y = bottom - 1;
        } else {
            assert_eq!(fast.block([x, y, z]), linear.block([x, y, z]),
                "probe changed subsequent block at {x},{y},{z}");
            y -= 1;
        }
    }
    let mut oracle = t.job(x, z, false).unwrap();
    let top = (t.min_y..t.min_y+t.height).rev().find_map(|y| {
        let b = oracle.block([x,y,z]); (b != Substance::Air).then_some((y,b))
    });
    assert_eq!(t.job(x,z,false).unwrap().surface_top(x,z), top);
    skipped
}

#[test]
fn intervals_preserve_blocks_aquifers_and_neighbour_heights() {
    let mut skipped = 0;
    for name in ["overworld.json", "amplified.json", "nether.json", "end.json"] {
        for seed in [0, -917] {
            let t = Terrain::from_document(seed, &fixture(name)).unwrap();
            for i in 0..32 {
                skipped += check(&t, 100000 + (i%8)*65, -100000 + (i/8)*63);
            }
        }
    }
    assert!(skipped > 1000, "must exercise actual skipped cells");
}

#[test]
fn thin_layers_and_noninterpolated_branches_are_not_skipped() {
    for interpolated in [false, true] {
        for slab in 104..112 {
            let mut d = fixture("overworld.json");
            let g = json!({"type":"minecraft:y_clamped_gradient", "from_y":-64,
                "to_y":320, "from_value":-64., "to_value":320.});
            let mut density = json!({"type":"minecraft:range_choice", "input":g,
                "min_inclusive":slab,"max_exclusive":slab+1,
                "when_in_range":1.,"when_out_of_range":-1.});
            if interpolated { density = json!({"type":"minecraft:interpolated","argument":density}); }
            d["settings"]["noise_router"]["final_density"] = density;
            d["settings"]["aquifers_enabled"] = json!(false);
            let t = Terrain::from_document(0, &d).unwrap();
            check(&t, -7, 13);
            if !interpolated {
                assert_eq!(t.job(-7,13,false).unwrap().surface_top(-7,13).unwrap().0, slab);
            }
        }
    }
}

#[test]
fn dry_and_flooded_aquifers_keep_the_same_blocks() {
    for flood in [-100., 100.] {
        let mut d = fixture("overworld.json");
        d["settings"]["noise_router"]["fluid_level_floodedness"] = json!(flood);
        d["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:interpolated",
            "argument":{"type":"minecraft:y_clamped_gradient", "from_y":0,
                "to_y":64, "from_value":1., "to_value":-1.}});
        let t = Terrain::from_document(0, &d).unwrap();
        for i in 0..16 { check(&t, i*17-100, i*31-200); }
    }
}

#[test]
fn structure_adjustments_and_stateful_graphs_use_original_traversal() {
    let mut d = fixture("overworld.json");
    let t = Terrain::from_document(0, &d).unwrap();
    let beard = vss_native_core::beard::Beard::parse(&json!({"pieces":[],"junctions":[]})).unwrap();
    let mut job = t.job(0,0,false).unwrap();
    job.beard = Some(&beard);
    assert_eq!(job.proven_air_bottom([0,319,0]), None);
    d["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:cache_2d",
        "argument":{"type":"minecraft:y_clamped_gradient","from_y":-64,
            "to_y":320,"from_value":1.,"to_value":-1.}});
    let t = Terrain::from_document(0,&d).unwrap();
    assert!(t.graph.requires_complete_column_order());
    assert_eq!(t.job(0,0,false).unwrap().proven_air_bottom([0,319,0]), None);
}

#[test]
fn shared_air_cells_preserve_every_column_in_their_spatial_extent() {
    for file in ["overworld.json", "amplified.json", "nether.json", "end.json"] {
        for seed in [0, -917] {
            let t = Terrain::from_document(seed, &fixture(file)).unwrap();
            let x0 = -3 * t.cell_width;
            let z0 = 2 * t.cell_width;
            let mut fast = t.job(x0, z0, false).unwrap();
            let mut oracle = t.job(x0, z0, false).unwrap();
            // Reverse the axes between seeds; reuse cannot depend on which
            // column happened to prove the cell empty first.
            for i in 0..t.cell_width * t.cell_width {
                let k = if seed == 0 { i } else { t.cell_width * t.cell_width - 1 - i };
                let x = x0 + k / t.cell_width;
                let z = z0 + k % t.cell_width;
                let mut y = t.min_y + t.height - 1;
                while y >= t.min_y {
                    if let Some(bottom) = fast.proven_air_bottom([x,y,z]) {
                        for yy in (bottom..=y).rev() {
                            assert_eq!(oracle.block([x,yy,z]), Substance::Air,
                                "{file} seed={seed} shared proof at {x},{yy},{z}");
                        }
                        y = bottom - 1;
                    } else {
                        assert_eq!(fast.block([x,y,z]),oracle.block([x,y,z]));
                        y -= 1;
                    }
                }
            }
        }
    }
}

#[test]
fn narrow_solid_sheets_inside_a_cached_horizontal_cell_are_preserved() {
    for horizontal in [1, 2, 4] {
        let mut d = fixture("overworld.json");
        d["settings"]["noise"]["size_horizontal"] = json!(horizontal);
        d["settings"]["aquifers_enabled"] = json!(false);
        // Only one X coordinate is solid. Endpoint-only or vertical-only
        // bounds reused as whole-cell bounds would erase this sheet.
        d["settings"]["noise_router"]["final_density"] = json!({
            "type":"minecraft:range_choice", "input":{"type":"lithostitched:axis","axis":"x"},
            "min_inclusive":-2,"max_exclusive":-1,
            "when_in_range":1.,"when_out_of_range":-1.
        });
        // Use the runtime-supported axis codec declared by the density parser.
        let t = Terrain::from_document(0, &d).unwrap();
        let mut fast = t.job(-t.cell_width,0,false).unwrap();
        for x in -t.cell_width..0 {
            let expected = t.job(x,0,false).unwrap().column(x,0).unwrap();
            let mut y = t.min_y+t.height-1;
            while y >= t.min_y {
                if let Some(bottom) = fast.proven_air_bottom([x,y,0]) {
                    for yy in bottom..=y {
                        assert_eq!(expected.blocks[(yy-t.min_y) as usize],Substance::Air);
                    }
                    y=bottom-1;
                } else {
                    assert_eq!(fast.block([x,y,0]),expected.blocks[(y-t.min_y) as usize]);
                    y-=1;
                }
            }
        }
    }
}
