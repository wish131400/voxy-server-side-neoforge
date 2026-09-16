//! Offline probe: is `Job::preliminary` a safe upper bound for the exact floor?
//!
//! `preliminary` is evaluated on a 4x4 lattice, so one block shares a single
//! value and it is only usable as a bound if it clears every column in that
//! block. This probe samples whole blocks and reports the margin under both
//! readings. It is read-only: generation is unchanged.
//!
//! A negative margin anywhere means `preliminary` cannot be used to skip the
//! air part of a column without changing the output.
use serde_json::Value;
use std::{fs, path::Path};
use vss_native_core::backend::World;

/// 4x4 blocks sampled; each contributes all sixteen of its columns.
const BLOCKS: usize = 256;
/// Sample coordinates are uniform in `-RANGE..RANGE`.
const RANGE: i32 = 2_000_000;
/// `min_y + height - 1`, the y the centre march starts from.
const TOP: i32 = 319;

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
    let mut column_margins = Vec::with_capacity(BLOCKS * 16);
    let mut block_margins = Vec::with_capacity(BLOCKS);
    let mut now_steps = Vec::with_capacity(BLOCKS);
    let mut skipped_steps = Vec::with_capacity(BLOCKS);
    let mut unsafe_columns = 0usize;
    let mut unsafe_blocks = 0usize;
    let mut inconsistent_blocks = 0usize;
    let mut worst: Option<([i32; 4], i32)> = None;

    for _ in 0..BLOCKS {
        let bx = rng.axis(RANGE) & !3;
        let bz = rng.axis(RANGE) & !3;
        let mut points = Vec::with_capacity(16);
        for dx in 0..4 {
            for dz in 0..4 {
                points.push((bx + dx, bz + dz));
            }
        }
        let rows = world.probe_preliminary(&points).unwrap();
        // Self-check on the alignment claim: every column of a 4x4 block must
        // share one preliminary value, because it is read at `x & !3`.
        if rows.iter().any(|r| r[2] != rows[0][2]) {
            inconsistent_blocks += 1;
        }
        let mut block_worst = i32::MAX;
        let mut highest_floor = i32::MIN;
        for row in &rows {
            let margin = row[2] - row[3];
            column_margins.push(margin);
            if margin < 0 {
                unsafe_columns += 1;
            }
            block_worst = block_worst.min(margin);
            highest_floor = highest_floor.max(row[3]);
            if worst.map_or(true, |(_, m)| margin < m) {
                worst = Some((*row, margin));
            }
        }
        block_margins.push(block_worst);
        if block_worst < 0 {
            unsafe_blocks += 1;
        }
        // The march walks y downward from TOP and stops a few blocks under the
        // first solid one, so both readings differ by the same trailing
        // constant and their ratio survives it.
        now_steps.push(TOP - highest_floor);
        skipped_steps.push(TOP - rows[0][2]);
    }

    column_margins.sort_unstable();
    block_margins.sort_unstable();

    println!("world seed 0, blocks={BLOCKS} columns={} in +-{RANGE}", column_margins.len());
    println!();
    println!(
        "{:<30} {:>7} {:>7} {:>7} {:>7} {:>7}",
        "margin = preliminary - floor", "min", "p1", "p50", "p99", "max"
    );
    row("per column", &column_margins);
    row("per block (worst column)", &block_margins);
    println!();
    println!("columns with negative margin: {unsafe_columns}");
    println!("blocks  with negative margin: {unsafe_blocks}");
    println!("blocks where the 4x4 value was not shared: {inconsistent_blocks}");
    if let Some((r, margin)) = worst {
        println!(
            "worst: x={} z={} preliminary={} floor={} margin={margin}",
            r[0], r[1], r[2], r[3]
        );
    }
    let mean = |v: &[i32]| v.iter().map(|&x| f64::from(x)).sum::<f64>() / v.len() as f64;
    let now = mean(&now_steps);
    let skipped = mean(&skipped_steps);
    println!();
    println!("march steps from TOP to floor        : {now:.1}");
    println!("march steps from TOP to preliminary  : {skipped:.1}");
    if now > 0. {
        println!(
            "air segment a bound would remove     : {:.1}%",
            (now - skipped) / now * 100.
        );
    }
    println!();
    println!("note: `preliminary` ignores the beardifier and is quantised to the");
    println!("4x8x4 cell grid, so a non-negative margin here is evidence, not proof.");
}

fn row(label: &str, sorted: &[i32]) {
    println!(
        "{:<30} {:>7} {:>7} {:>7} {:>7} {:>7}",
        label,
        sorted[0],
        percentile(sorted, 0.01),
        percentile(sorted, 0.50),
        percentile(sorted, 0.99),
        sorted[sorted.len() - 1]
    );
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
