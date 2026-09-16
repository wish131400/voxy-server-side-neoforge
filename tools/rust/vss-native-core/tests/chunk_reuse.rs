//! P1-02: a chunk whose 256 exact columns were already produced by refinement
//! must be assembled from those points rather than regenerated through
//! `surface_region`.
//!
//! The contract is not "faster" but "identical": the assembled chunk has to
//! match, value for value, what the full region path produces for the same
//! seed and document. The oracle is therefore a second world that never saw the
//! point queries and takes the region path.
use serde_json::Value;
use std::{fs, path::Path};
use vss_native_core::backend::World;

fn document() -> Value {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |name: &str| -> Value {
        serde_json::from_str(&fs::read_to_string(root.join(name)).unwrap()).unwrap()
    };
    let mut doc = read("overworld.json");
    for (key, file) in [
        ("block_definitions", "blocks.json"),
        ("biomes", "biomes.json"),
        ("grass_colormap", "grass.json"),
        ("foliage_colormap", "foliage.json"),
    ] {
        doc[key] = read(file);
    }
    doc
}

#[test]
fn refined_points_are_assembled_into_an_identical_chunk() {
    let doc = document();
    for seed in [0, -917, 4242] {
        let (cx, cz) = (3, -13);
        let world = World::new(seed, 0, doc.clone()).unwrap();
        // The reuse path is only valid when column order carries no meaning.
        assert!(
            !world.terrain.graph.requires_complete_column_order(),
            "fixture must exercise the order-independent path"
        );
        assert_eq!(world.reused_chunk_builds(), 0);

        // Refinement samples exact points; batches are capped at 64 per call,
        // so a full chunk needs four of them, exactly as the Java grid does.
        let mut points = Vec::with_capacity(256);
        for x in cx * 16..cx * 16 + 16 {
            for z in cz * 16..cz * 16 + 16 {
                points.push((x, z));
            }
        }
        for batch in points.chunks(64) {
            assert_eq!(world.surface_points(batch).unwrap().len(), batch.len());
        }

        let assembled = world.surface_columns(cx, cz).unwrap();
        assert_eq!(assembled.len(), 256);
        assert_eq!(
            world.reused_chunk_builds(),
            1,
            "seed={seed}: a fully refined chunk must be assembled, not regenerated"
        );

        let oracle = World::new(seed, 0, doc.clone()).unwrap();
        assert_eq!(oracle.reused_chunk_builds(), 0);
        let expected = oracle.surface_columns(cx, cz).unwrap();
        for i in 0..256 {
            assert_eq!(
                assembled[i].values, expected[i].values,
                "seed={seed} column index {i} differs between the assembled and region paths"
            );
        }
    }
}

#[test]
fn a_partially_refined_chunk_still_takes_the_region_path() {
    let doc = document();
    let (cx, cz) = (3, -13);
    let world = World::new(0, 0, doc.clone()).unwrap();
    // Only 64 of the 256 columns are cached: mixing sources would be wrong, so
    // the chunk must be regenerated in full.
    let partial: Vec<_> = (0..4)
        .flat_map(|x| (0..16).map(move |z| (cx * 16 + x, cz * 16 + z)))
        .collect();
    assert_eq!(world.surface_points(&partial).unwrap().len(), 64);

    let columns = world.surface_columns(cx, cz).unwrap();
    assert_eq!(columns.len(), 256);
    assert_eq!(
        world.reused_chunk_builds(),
        0,
        "a partially refined chunk must not be assembled from points"
    );

    let oracle = World::new(0, 0, doc).unwrap();
    let expected = oracle.surface_columns(cx, cz).unwrap();
    for i in 0..256 {
        assert_eq!(columns[i].values, expected[i].values, "column index {i}");
    }
}

// Exercise the real 25-chunk producer, not just a hand-built ProxyBase table.
#[test]
fn surface_proxy_preserves_world_coordinates_across_chunk_boundaries() {
    let doc = document();
    for (seed, cx, cz) in [(0, 3, -13), (-917, -3, 2)] {
        let world = World::new(seed, 0, doc.clone()).unwrap();
        let mut proxy = world.surface_proxy(cx, cz).unwrap();
        assert!(proxy.is_proxy());
        assert!(proxy.blocks.is_empty());
        for chunk_x in cx - 2..=cx + 2 {
            for chunk_z in cz - 2..=cz + 2 {
                let columns = world.surface_columns(chunk_x, chunk_z).unwrap();
                for x in 0..16 {
                    for z in 0..16 {
                        let c = columns[(x * 16 + z) as usize].values;
                        let wx = chunk_x * 16 + x;
                        let wz = chunk_z * 16 + z;
                        // Top, under, deep, water/ice and air boundaries. Read
                        // each by absolute coordinate to catch chunk-major data.
                        for y in [c[0] - 7, c[0] - 2, c[0] - 1, c[0], c[1] - 1, c[0].max(c[1])] {
                            if y < proxy.origin[1] || y >= proxy.origin[1] + proxy.size[1] as i32 {
                                continue;
                            }
                            let expected = if y >= c[0].max(c[1]) {
                                proxy.palette.air
                            } else if y >= c[0] {
                                let name = if c[3] & 2 != 0 && y == c[1] - 1 {
                                    "minecraft:ice"
                                } else if c[2] == 2 {
                                    "minecraft:lava"
                                } else {
                                    "minecraft:water"
                                };
                                proxy.palette.named(name).unwrap()
                            } else if c[3] & (1 << 29) != 0 {
                                proxy.palette.air
                            } else if y == c[0] - 1 {
                                c[4] as u32
                            } else if c[0] - 1 - y < 4 {
                                c[5] as u32
                            } else {
                                c[6] as u32
                            };
                            assert_eq!(proxy.get([wx, y, wz]), expected,
                                "seed={seed} world=({wx},{y},{wz})");
                        }
                    }
                }
            }
        }
    }
}
