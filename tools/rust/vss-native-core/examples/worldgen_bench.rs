//! Offline throughput probe; this does not measure Minecraft frame time.
use serde_json::Value;
use std::{fs, path::Path, time::Instant};
use vss_native_core::backend::World;
fn main() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |s: &str| -> Value {
        serde_json::from_str(&fs::read_to_string(root.join(s)).unwrap()).unwrap()
    };
    let start = Instant::now();
    let mut doc = read("overworld.json");
    for (key, file) in [
        ("block_definitions", "blocks.json"),
        ("biomes", "biomes.json"),
        ("grass_colormap", "grass.json"),
        ("foliage_colormap", "foliage.json"),
    ] {
        doc[key] = read(file);
    }
    if let Some(path) = std::env::args().nth(1) {
        doc = serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap();
    }
    let seed = std::env::args()
        .nth(2)
        .map(|s| s.parse::<i64>().unwrap())
        .unwrap_or(0);
    let world = World::new(seed, 0, doc).unwrap();
    println!(
        "world construction: {:.2} ms",
        start.elapsed().as_secs_f64() * 1000.
    );
    for (x, z) in [(0, 0), (13, -17), (1234, 5678)] {
        let start = Instant::now();
        let v = world.surface_region(x, z, 1).unwrap();
        println!(
            "base + surface ({x},{z}): {:.2} ms; volume={} bytes, states={}",
            start.elapsed().as_secs_f64() * 1000.,
            v.blocks.len() * 4,
            v.palette.states.len()
        );
        println!("base checksum ({x},{z}): {:016x}", checksum(&v.blocks));
    }
    let start = Instant::now();
    let mut points = vec![];
    for i in 0..64 {
        points.extend(
            world
                .surface_point(10000 + i % 8 * 64, 10000 + i / 8 * 64)
                .unwrap()
                .values
                .map(|v| v as u32),
        );
    }
    println!(
        "64 sparse cold points: {:.2} ms",
        start.elapsed().as_secs_f64() * 1000.
    );
    println!("point checksum: {:016x}", checksum(&points));
    let start = Instant::now();
    for _ in 0..100 {
        for i in 0..64 {
            std::hint::black_box(
                world
                    .surface_point(10000 + i % 8 * 64, 10000 + i / 8 * 64)
                    .unwrap(),
            );
        }
    }
    println!(
        "64 cached points, per batch averaged over 100: {:.4} ms",
        start.elapsed().as_secs_f64() * 10.
    );
    let start = Instant::now();
    let v = world.surface_proxy(0, 0).unwrap();
    println!(
        "5x5 surface proxy cold: {:.2} ms; native block storage={} bytes",
        start.elapsed().as_secs_f64() * 1000.,
        v.blocks.len() * 4
    );
    println!("proxy checksum: {:016x}", checksum(&v.blocks));
    drop(v);
    let start = Instant::now();
    std::hint::black_box(world.surface_proxy(0, 0).unwrap());
    println!(
        "5x5 surface proxy cached: {:.2} ms",
        start.elapsed().as_secs_f64() * 1000.
    );
}

fn checksum(values: &[u32]) -> u64 {
    values.iter().fold(0xcbf29ce484222325u64, |h, &v| {
        (h ^ u64::from(v)).wrapping_mul(0x100000001b3)
    })
}
