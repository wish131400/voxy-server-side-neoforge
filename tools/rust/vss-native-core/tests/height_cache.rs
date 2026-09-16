//! P1-04: the per-column height cache must never change a result.
//!
//! `Volume::heightmap` used to walk from the top of the world down on every
//! call. It is now memoised per column. Every test here compares the cached
//! answer against the uncached scan after a sequence of edits, deletions and
//! rollbacks - the `kind` predicates (air / motion blocking / fluid / leaves)
//! differ, so all four are exercised.
//!
//! State ids are resolved through the volume's own palette; a second palette
//! built from the same JSON would assign different ids and silently write the
//! wrong blocks.
use vss_native_core::blocks::{Palette, Volume};

fn volume() -> Volume {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/worldgen/blocks.json");
    let json: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
    let palette = Palette::from_json(&json).unwrap();
    Volume::new([0, -64, 0], [16, 384, 16], palette).unwrap()
}

/// A deterministic edit script that fills, deletes and restores columns.
fn edit_script(volume: &mut Volume) {
    let stone = volume.palette.named("minecraft:stone").unwrap();
    let water = volume.palette.named("minecraft:water").unwrap();
    let leaves = volume.palette.named("minecraft:oak_leaves").unwrap();
    let air = volume.palette.air;

    for (i, (x, z)) in [(0, 0), (5, 9), (15, 15), (2, 13)].into_iter().enumerate() {
        // Build a column, then remove its top again: deletions must invalidate.
        let base = 40 + i as i32 * 3;
        for y in -64..base {
            volume.set([x, y, z], stone);
        }
        // Water above the floor, then leaves, then remove them one by one.
        for y in base..base + 4 {
            volume.set([x, y, z], water);
        }
        volume.set([x, base + 4, z], leaves);
        volume.set([x, base + 4, z], air);
        // Trimming the top must lower the reported height.
        volume.set([x, base + 3, z], air);
    }
}

fn check_all_kinds(volume: &mut Volume, points: &[(i32, i32)]) {
    for &(x, z) in points {
        for kind in 0..4u8 {
            assert_eq!(
                volume.heightmap(x, z, kind),
                volume.heightmap_uncached(x, z, kind),
                "({x},{z}) kind={kind}: cached height disagrees with the scan"
            );
        }
    }
}

#[test]
fn cached_heights_match_the_uncached_scan_after_edits_and_deletions() {
    let mut volume = volume();
    let points = [(0, 0), (5, 9), (15, 15), (2, 13), (7, 7)];

    // Prime the cache on an untouched world first, so later edits must
    // invalidate rather than merely miss.
    check_all_kinds(&mut volume, &[(0, 0), (5, 9), (15, 15), (2, 13)]);

    edit_script(&mut volume);
    check_all_kinds(&mut volume, &points);

    // Repeat queries must be served consistently after the edits settle.
    check_all_kinds(&mut volume, &points);
}

#[test]
fn rollback_restores_the_heights_the_scan_reports() {
    let mut volume = volume();
    let stone = volume.palette.named("minecraft:stone").unwrap();
    volume.set([2, 60, 3], stone);
    let before: Vec<_> = (0..4u8).map(|k| volume.heightmap(2, 3, k)).collect();

    volume.begin().unwrap();
    volume.set([2, 70, 3], stone);
    // A failed transaction must restore the blocks and therefore the heights.
    volume.finish(false).unwrap();
    let after: Vec<_> = (0..4u8).map(|k| volume.heightmap(2, 3, k)).collect();
    assert_eq!(before, after, "rollback must restore cached heights");

    check_all_kinds(&mut volume, &[(2, 3)]);
}

#[test]
fn a_committed_transaction_publishes_the_new_heights() {
    let mut volume = volume();
    let stone = volume.palette.named("minecraft:stone").unwrap();
    volume.set([4, 60, 5], stone);
    let before = volume.heightmap(4, 5, 1);

    volume.begin().unwrap();
    volume.set([4, 90, 5], stone);
    volume.finish(true).unwrap();

    let after = volume.heightmap(4, 5, 1);
    assert_ne!(before, after, "a committed edit must be visible to the cache");
    check_all_kinds(&mut volume, &[(4, 5)]);
}

#[test]
fn sparse_upper_bound_matches_full_scan_through_edits_and_rollbacks() {
    let mut palette = volume().palette;
    let stone = palette.named("minecraft:stone").unwrap();
    let water = palette.named("minecraft:water").unwrap();
    let leaves = palette.named("minecraft:oak_leaves").unwrap();
    let air = palette.air;
    let columns = (0..16*16).map(|i| {
        let y = -60 + (i % 80) as i32;
        [y, y+4, (i%3) as i32, if i%2==0 {2} else {0}, stone as i32, stone as i32, stone as i32,0,0,0]
    }).collect();
    let mut v = Volume::proxy([-16,-64,-16],16,16,384,palette,columns).unwrap();
    let points: Vec<_> = (-16..0).flat_map(|x| (-16..0).map(move |z| (x,z))).collect();
    check_all_kinds(&mut v,&points);
    let mut random = vss_native_core::random::Random::new(71,0);
    for transaction in 0..30 {
        v.begin().unwrap();
        for _ in 0..24 {
            let x = random.next_bounded(16)-16;
            let z = random.next_bounded(16)-16;
            let y = random.next_bounded(384)-64;
            let state = [stone,water,leaves,air][random.next_bounded(4) as usize];
            v.set([x,y,z],state);
            check_all_kinds(&mut v,&[(x,z)]);
        }
        v.finish(transaction%3!=0).unwrap();
        check_all_kinds(&mut v,&points);
    }
    // Removing a high write must expose the base; a conservative bound may
    // stay high but must not report the deleted state as the surface.
    v.set([-16,319,-16],stone);
    check_all_kinds(&mut v,&[(-16,-16)]);
    v.set([-16,319,-16],air);
    check_all_kinds(&mut v,&[(-16,-16)]);
    v.materialize().unwrap();
    check_all_kinds(&mut v,&points);
}
