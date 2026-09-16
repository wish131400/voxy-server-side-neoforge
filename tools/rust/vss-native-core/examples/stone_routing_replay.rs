//! Replays a captured worldgen document at the reported stone/shore coordinates.
use serde_json::Value;
use vss_native_core::{backend::World, climate::BiomeSource, density::Graph};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let path = args.next().expect("stone_routing_replay <document> <seed>");
    let seed: i64 = args.next().expect("seed").parse()?;
    let doc: Value = serde_json::from_str(&std::fs::read_to_string(path)?)?;
    let graph = Graph::from_document(seed, &doc)?;
    let source = BiomeSource::from_document(&doc, &graph, seed)?;
    let world = World::new(seed, 0, doc)?;
    for [x,y,z] in [[832,100,-624],[880,70,-560],[800,94,-592],[864,87,-592]] {
        let mut scratch = graph.scratch(x & !15, z & !15, 4, 8)?;
        let biome = source.sample(&graph, [x>>2,y>>2,z>>2], &mut scratch, &mut None);
        let row = world.surface_points(&[(x,z)])?.remove(0);
        println!("{x},{y},{z}: biome={biome} surfaceY={} top={}",row.values[0],
            world.palette.state(row.values[4] as _).name);
    }
    Ok(())
}
