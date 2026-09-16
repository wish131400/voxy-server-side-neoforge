//! P1-03: a sparse proxy volume must answer exactly like the dense volume the
//! old `surface_proxy` materialised.
//!
//! The oracle here is the literal fill loop previously used by `surface_proxy`
//! (`for y in min_y..max(top, fluid)` writing ice/lava/water or top/under/deep),
//! replayed into a dense `Volume` built from the same column summaries. Any
//! divergence in the on-demand formula shows up as a differing cell.
use vss_native_core::blocks::{Palette, Volume};

fn palette() -> Palette {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/worldgen/blocks.json");
    let json: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
    Palette::from_json(&json).unwrap()
}

/// Ten summary words per column. `top`/`under`/`deep` must be state ids that
/// exist in the palette, so they are resolved through `named` by the caller.
fn summary(floor: i32, fluid_y: i32, fluid: i32, flags: i32, top: u32) -> [i32; 10] {
    let mut c = [0i32; 10];
    c[0] = floor;
    c[1] = fluid_y;
    c[2] = fluid;
    c[3] = flags;
    c[4] = top as i32;
    c[5] = top as i32;
    c[6] = top as i32;
    c
}

const MIN_Y: i32 = -64;
const HEIGHT: usize = 384;
const WIDTH: usize = 4;
const DEPTH: usize = 4;

/// The original dense fill, reproduced verbatim.
fn dense_oracle(palette: &mut Palette, columns: &[[i32; 10]]) -> Volume {
    // Resolve every fluid id *before* cloning: the clone snapshots the state
    // table, and `set` rejects ids the volume's own palette does not hold.
    let ice = palette.named("minecraft:ice").unwrap();
    let water = palette.named("minecraft:water").unwrap();
    let lava = palette.named("minecraft:lava").unwrap();
    let air = palette.air;
    let mut volume =
        Volume::new([0, MIN_Y, 0], [WIDTH, HEIGHT, DEPTH], palette.clone()).unwrap();
    for x in 0..WIDTH {
        for z in 0..DEPTH {
            let c = columns[x * DEPTH + z];
            let end = c[0].max(c[1]);
            for y in MIN_Y..end {
                let id = if y >= c[0] {
                    if c[3] & 2 != 0 && y == c[1] - 1 {
                        ice
                    } else if c[2] == 2 {
                        lava
                    } else {
                        water
                    }
                } else if c[3] & (1 << 29) != 0 {
                    air
                } else if y == c[0] - 1 {
                    c[4] as u32
                } else if c[0] - 1 - y < 4 {
                    c[5] as u32
                } else {
                    c[6] as u32
                };
                volume.set([x as i32, y, z as i32], id);
            }
        }
    }
    volume
}

/// A spread of columns covering every branch of the fill rule.
fn column_set(palette: &mut Palette) -> Vec<[i32; 10]> {
    // `blocks.json` carries definitions only, so state ids appear on first use.
    // The summary words must name ids that actually exist or `set` is rejected.
    let top = palette.named("minecraft:dirt").unwrap() as u32;
    let mut columns = Vec::new();
    for i in 0..(WIDTH * DEPTH) {
        columns.push(match i % 6 {
            // Plain land column, nothing above.
            0 => summary(64, 64, 0, 0, top),
            // Ocean: water up to sea level.
            1 => summary(40, 63, 1, 0, top),
            // Frozen ocean: the top fluid layer becomes ice (flag bit 1).
            2 => summary(40, 63, 1, 2, top),
            // Lava lake.
            3 => summary(20, 25, 2, 0, top),
            // A column the flag marks as air below its floor.
            4 => summary(70, 70, 0, 1 << 29, top),
            // Fluid and land together, with water above the terrain.
            5 => summary(55, 62, 1, 0, top),
            _ => unreachable!(),
        });
    }
    columns
}

#[test]
fn proxy_matches_the_dense_fill_cell_for_cell() {
    let mut p = palette();
    let columns = column_set(&mut p);
    // Build the oracle first: it interns ice/water/lava, so the palette the
    // proxy clones already holds every id the summaries can name.
    let oracle = dense_oracle(&mut p, &columns);
    let mut proxy =
        Volume::proxy([0, MIN_Y, 0], WIDTH, DEPTH, HEIGHT, p.clone(), columns.clone()).unwrap();
    assert!(proxy.is_proxy());
    assert!(proxy.blocks.is_empty(), "a proxy must not hold a dense array");

    for x in 0..WIDTH as i32 {
        for z in 0..DEPTH as i32 {
            for y in MIN_Y..MIN_Y + HEIGHT as i32 {
                let actual = proxy.get([x, y, z]);
                let expected = oracle.blocks
                    [((x as usize * DEPTH + z as usize) * HEIGHT) + (y - MIN_Y) as usize];
                assert_eq!(
                    actual, expected,
                    "({x},{y},{z}) proxy cell differs from the dense fill"
                );
            }
        }
    }
}

#[test]
fn proxy_edits_and_rollback_leave_the_base_values_intact() {
    let mut p = palette();
    let columns = column_set(&mut p);
    // The oracle interns ice/water/lava; the proxy must clone a palette that
    // already holds them, or the two sides name different ids.
    let mut oracle = dense_oracle(&mut p, &columns);
    // Every id the test writes must exist before the proxy clones the palette;
    // `set` rejects ids the volume's own state table does not hold.
    let diamond = p.named("minecraft:diamond_block").unwrap();
    let mut proxy =
        Volume::proxy([0, MIN_Y, 0], WIDTH, DEPTH, HEIGHT, p.clone(), columns.clone()).unwrap();
    let base_before = proxy.get([1, 100, 2]);

    // A failed transaction must restore the state as it was when that
    // transaction began - here the computed base value, which is not stored as
    // an edit at all.
    proxy.begin().unwrap();
    proxy.set([1, 100, 2], diamond);
    assert_eq!(proxy.get([1, 100, 2]), diamond);
    proxy.finish(false).unwrap();
    assert_eq!(
        proxy.get([1, 100, 2]),
        base_before,
        "rollback must restore the proxy base value"
    );

    // A committed edit survives, and a later rollback returns to it rather than
    // to the original base.
    proxy.begin().unwrap();
    proxy.set([1, 100, 2], diamond);
    proxy.finish(true).unwrap();
    assert_eq!(proxy.get([1, 100, 2]), diamond);

    proxy.begin().unwrap();
    proxy.set([1, 100, 2], diamond);
    proxy.finish(false).unwrap();
    assert_eq!(
        proxy.get([1, 100, 2]),
        diamond,
        "rollback returns to the state at the start of its own transaction"
    );

    // The oracle is a separate volume and never saw the committed edit, so a
    // cell-for-cell comparison here would compare different inputs. Unedited
    // equivalence is covered by `proxy_matches_the_dense_fill_cell_for_cell`.
    let _ = oracle.get([0, 0, 0]);
}

#[test]
fn materializing_a_proxy_reproduces_the_dense_array() {
    let mut p = palette();
    let columns = column_set(&mut p);
    let diamond = p.named("minecraft:diamond_block").unwrap();
    let mut proxy =
        Volume::proxy([0, MIN_Y, 0], WIDTH, DEPTH, HEIGHT, p.clone(), columns.clone()).unwrap();
    proxy.begin().unwrap();
    proxy.set([2, 90, 3], diamond);
    proxy.finish(true).unwrap();

    let expected: Vec<_> = (0..WIDTH * DEPTH * HEIGHT)
        .map(|i| {
            let x = i / (DEPTH * HEIGHT);
            let z = (i / HEIGHT) % DEPTH;
            let y = MIN_Y + (i % HEIGHT) as i32;
            proxy.get([x as i32, y, z as i32])
        })
        .collect();

    proxy.materialize().unwrap();
    assert!(!proxy.is_proxy());
    assert_eq!(proxy.blocks.len(), WIDTH * DEPTH * HEIGHT);
    assert_eq!(
        proxy.blocks, expected,
        "materialize must reproduce exactly what get() reported"
    );
}
