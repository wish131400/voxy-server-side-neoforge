//! Can a coarse pre-scan shorten the exact march without changing it?
//!
//! Three candidate pre-scans are compared against the exact `Job::surface_top`:
//!
//! * `preview_height` - `Mode::Raw`, no beardifier, bisection bound 4
//! * `probe_height_by_block` - the `Job::block` predicate, 16-block stride
//!   followed by a full scan of the landed stride
//! * `linear` - the same top-down single-block scan `surface_top` does,
//!   repeated independently
//!
//! The first fails because it asks `final_density > 0` while the target asks
//! whether `substance` reports air; the aquifer and global fluid layer make
//! those disagree by tens of blocks over deep water.
//!
//! `linear` exists to separate two failure modes that look identical from the
//! outside: a coarse stride that skipped a layer, versus `Job::block` returning
//! different answers for the same position depending on what was evaluated
//! before it. If `linear` matches `surface_top` but `by_block` does not, the
//! stride scan is at fault. If `linear` itself disagrees, `block` is not a pure
//! function of position.
use serde_json::Value;
use std::{fs, path::Path};
use vss_native_core::backend::World;

/// 4x4 blocks sampled; each contributes all sixteen of its columns.
const BLOCKS: usize = 256;
/// Sample coordinates are uniform in `-RANGE..RANGE`.
const RANGE: i32 = 2_000_000;

fn main() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |s: &str| -> Value {
        serde_json::from_str(&fs::read_to_string(root.join(s)).unwrap()).unwrap()
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
    let world = World::new(0, 0, doc).unwrap();

    let mut rng = Lcg(0x2545_f491_4f6c_dd1d);
    let mut raw_margins: Vec<i32> = Vec::new();
    let mut block_margins: Vec<i32> = Vec::new();
    let mut linear_margins: Vec<i32> = Vec::new();
    let mut stride_vs_linear: Vec<i32> = Vec::new();
    let mut worst_block: Option<([i32; 6], i32)> = None;
    let mut worst_linear: Option<([i32; 6], i32)> = None;

    for _ in 0..BLOCKS {
        let bx = rng.axis(RANGE) & !3;
        let bz = rng.axis(RANGE) & !3;
        let points: Vec<(i32, i32)> = (0..4)
            .flat_map(|dx| (0..4).map(move |dz| (bx + dx, bz + dz)))
            .collect();
        for row in world.probe_raw_vs_exact(&points).unwrap() {
            let exact = row[3];
            let by_block = row[4];
            let linear = row[5];
            raw_margins.push(row[2] - exact);
            block_margins.push(by_block - exact);
            linear_margins.push(linear - exact);
            stride_vs_linear.push(by_block - linear);
            if worst_block.map_or(true, |(_, m)| by_block - exact < m) {
                worst_block = Some((row, by_block - exact));
            }
            if worst_linear.map_or(true, |(_, m)| linear - exact < m) {
                worst_linear = Some((row, linear - exact));
            }
        }
    }

    println!(
        "world seed 0, columns = {} sampled in +-{RANGE}",
        raw_margins.len()
    );
    report("preview_height (Raw,no beard)", &mut raw_margins, None);
    report("probe_height_by_block", &mut block_margins, worst_block);
    report("linear vs surface_top", &mut linear_margins, worst_linear);
    report("by_block vs linear", &mut stride_vs_linear, None);

    println!();
    println!("Reading:");
    println!("  linear vs surface_top  != 0  -> Job::block is not pure in position");
    println!("  by_block vs linear     != 0  -> the coarse stride scan is wrong");
    println!("  both zero                    -> only the density-vs-air predicate differs");
}

fn report(label: &str, margins: &mut [i32], worst: Option<([i32; 6], i32)>) {
    margins.sort_unstable();
    let negative = margins.iter().filter(|&&m| m < 0).count();
    let nonzero = margins.iter().filter(|&&m| m != 0).count();
    println!();
    println!("=== {label} ===");
    println!(
        "{:<22} {:>7} {:>7} {:>7} {:>7} {:>7}",
        "margin", "min", "p1", "p50", "p99", "max"
    );
    println!(
        "{:<22} {:>7} {:>7} {:>7} {:>7} {:>7}",
        "per column",
        margins[0],
        percentile(margins, 0.01),
        percentile(margins, 0.50),
        percentile(margins, 0.99),
        margins[margins.len() - 1]
    );
    println!(
        "nonzero: {nonzero} of {}   negative: {negative}",
        margins.len()
    );
    if let Some((r, m)) = worst {
        println!(
            "worst: x={} z={} raw={} by_block={} linear={} exact={} margin={m}",
            r[0], r[1], r[2], r[4], r[5], r[3]
        );
    }
}

fn percentile(sorted: &[i32], p: f64) -> i32 {
    let i = ((sorted.len() - 1) as f64 * p).round() as usize;
    sorted[i.min(sorted.len() - 1)]
}

/// Deterministic stand-in for a random source; the probe only needs spread.
struct Lcg(u64);

impl Lcg {
    /// Uniform in `-bound..bound`.
    fn axis(&mut self, bound: i32) -> i32 {
        self.0 = self
            .0
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        ((self.0 >> 33) as i64).rem_euclid(bound as i64 * 2) as i32 - bound
    }
}
