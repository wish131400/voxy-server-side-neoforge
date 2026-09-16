//! Bounded biome reuse. Tints themselves remain position/resource dependent.
use super::*;
use crate::density::Scratch;
use std::collections::VecDeque;

const SHARD_CAPACITY: usize = 4096;
const WORK_BYTES: usize = 16 * 1024 * 1024;

#[derive(Default)]
pub(super) struct BiomeCache {
    values: HashMap<Pos, Biome>,
    order: VecDeque<Pos>,
}
impl BiomeCache {
    fn insert(&mut self, q: Pos, biome: Biome) {
        if self.values.contains_key(&q) { return; }
        if self.values.len() >= SHARD_CAPACITY {
            if let Some(old) = self.order.pop_front() { self.values.remove(&old); }
        }
        self.values.insert(q, biome);
        self.order.push_back(q);
    }
}

#[derive(Default)]
pub(super) struct WorkPool {
    idle: Vec<Scratch>,
    bytes: usize,
}
impl World {
    pub(super) fn color_biome(&self, q: Pos) -> Result<Biome> {
        let hash = (q[0] as u32).wrapping_mul(0x9e3779b9)
            ^ (q[1] as u32).wrapping_mul(0x85ebca6b).rotate_left(11)
            ^ (q[2] as u32).wrapping_mul(0xc2b2ae35).rotate_left(21);
        let shard = &self.color_biomes[((hash ^ (hash >> 16)) & 15) as usize];
        if let Some(b) = shard.lock().map_err(|_| "biome color cache lock")?.values.get(&q).cloned() {
            return Ok(b);
        }
        // Fixed sources need no graph workspace at all.
        let biome = if let BiomeSource::Fixed(name) = &self.source {
            self.biomes.get(name).cloned().ok_or_else(|| format!("missing biome {name}"))?
        } else {
            let cached = {
                let mut pool = self.color_work.lock().map_err(|_| "color work lock")?;
                let work = pool.idle.pop();
                if let Some(s) = &work { pool.bytes -= s.retained_bytes(); }
                work
            };
            let mut scratch = match cached {
                Some(s) => s,
                None => self.terrain.graph.raw_scratch()?,
            };
            // Raw memo entries carry coordinates and mode; retain them across
            // requests. Do not retain ParameterTree's last-leaf tie preference:
            // the old single-point query started that preference afresh too.
            let name = self.source.sample(&self.terrain.graph, q, &mut scratch, &mut None);
            let result = self.biomes.get(name).cloned().ok_or_else(|| format!("missing biome {name}"));
            let bytes = scratch.retained_bytes();
            let mut pool = self.color_work.lock().map_err(|_| "color work lock")?;
            if pool.idle.len() < 8 && bytes <= WORK_BYTES.saturating_sub(pool.bytes) {
                pool.bytes += bytes;
                pool.idle.push(scratch);
            }
            result?
        };
        shard.lock().map_err(|_| "biome color cache lock")?.insert(q, biome.clone());
        Ok(biome)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn document() -> Value {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
        let read = |name| serde_json::from_str::<Value>(&std::fs::read_to_string(root.join(name)).unwrap()).unwrap();
        let mut doc = read("overworld.json");
        for (key, file) in [("block_definitions", "blocks.json"), ("biomes", "biomes.json"),
            ("grass_colormap", "grass.json"), ("foliage_colormap", "foliage.json")] {
            doc[key] = read(file);
        }
        doc
    }

    fn fresh(world: &World, p: Pos) -> [u32; 3] {
        let t = &world.terrain;
        let mut q = zoom_quart(world.zoom_seed, p);
        q[1] = q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
        let mut work = t.graph.scratch(p[0] & !15, p[2] & !15, t.cell_width, t.cell_height).unwrap();
        let name = world.source.sample(&t.graph, q, &mut work, &mut None);
        let biome = &world.biomes[name];
        [world.colors.grass(biome, p[0] as f64, p[2] as f64), world.colors.foliage(biome), biome.water]
    }

    #[test]
    fn pooled_raw_colors_match_fresh_noisechunk_workspaces_across_threads() {
        let world = Arc::new(World::new(731, 912345, document()).unwrap());
        let points: Vec<Pos> = (0..512).map(|i| [i * 137 - 40000, (i % 11) * 53 - 140, 32000 - i * 193]).collect();
        let start = std::time::Instant::now();
        let expected: Vec<_> = points.iter().map(|&p| fresh(&world, p)).collect();
        let old_time = start.elapsed();
        let start = std::time::Instant::now();
        for (&p, color) in points.iter().zip(&expected) { assert_eq!(&world.colors_at(p).unwrap(), color, "{p:?}"); }
        println!("512 color queries fresh_ms={:.3} pooled_cold_ms={:.3}", old_time.as_secs_f64()*1000., start.elapsed().as_secs_f64()*1000.);
        std::thread::scope(|scope| {
            for worker in 0..8 {
                let world = &world; let points = &points; let expected = &expected;
                scope.spawn(move || {
                    for i in 0..points.len() {
                        let at = (i * 31 + worker) % points.len();
                        assert_eq!(world.colors_at(points[at]).unwrap(), expected[at]);
                        // Different height forces cold keys while sharing workspace.
                        let p = [points[at][0], points[at][1] + worker as i32 * 4, points[at][2]];
                        assert_eq!(world.colors_at(p).unwrap(), fresh(world, p));
                    }
                });
            }
        });
        let pool = world.color_work.lock().unwrap();
        assert!(pool.idle.len() <= 8 && pool.bytes <= WORK_BYTES);
        assert_eq!(pool.bytes, pool.idle.iter().map(Scratch::retained_bytes).sum::<usize>());
    }

    #[test]
    fn tint_cache_evicts_one_entry_and_fixed_sources_allocate_no_workspace() {
        let mut doc = document();
        doc["biome_source"] = json!({"type":"minecraft:fixed","biome":"minecraft:plains"});
        let world = World::new(1, 0, doc).unwrap();
        let mut cache = BiomeCache::default();
        let biome = world.biomes["minecraft:plains"].clone();
        for x in 0..=SHARD_CAPACITY as i32 { cache.insert([x, 0, 0], biome.clone()); }
        assert_eq!(cache.values.len(), SHARD_CAPACITY);
        assert_eq!(cache.order.len(), SHARD_CAPACITY);
        assert!(!cache.values.contains_key(&[0, 0, 0]));
        assert!(cache.values.contains_key(&[1, 0, 0]));
        cache.insert([1, 0, 0], biome);
        assert_eq!(cache.order.len(), SHARD_CAPACITY);
        assert_eq!(world.colors_at([-1, 64, -1]).unwrap(), fresh(&world, [-1, 64, -1]));
        assert_eq!(world.color_work.lock().unwrap().bytes, 0);
        world.replace_colormaps(vec![0x123456;65536], vec![0x654321;65536]).unwrap();
        assert_eq!(world.colors_at([-1, 64, -1]).unwrap(), fresh(&world, [-1, 64, -1]));
        assert_eq!(world.colors_at([-1, 64, -1]).unwrap()[0], 0x123456);
    }

    #[test]
    #[ignore = "requires VSS_TINT_DOCUMENT pointing to a captured modpack worldgen document"]
    fn captured_modpack_colors_match_fresh_queries() {
        let path = std::env::var("VSS_TINT_DOCUMENT").unwrap();
        let doc: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        let reference = World::new(2375620064288639166, 17, doc.clone()).unwrap();
        let world = World::new(2375620064288639166, 17, doc).unwrap();
        let points: Vec<Pos> = (0..2048).map(|i| [i * 31 - 32000, 32 + (i % 41) * 4, 16000 - i * 19]).collect();
        let start = std::time::Instant::now();
        let expected: Vec<_> = points.iter().map(|&p| fresh(&reference, p)).collect();
        let baseline = start.elapsed();
        let start = std::time::Instant::now();
        for (&p, color) in points.iter().zip(expected) { assert_eq!(world.colors_at(p).unwrap(), color, "{p:?}"); }
        println!("captured 2048 tints fresh_ms={:.3} pooled_cold_ms={:.3} terrablender={}",
            baseline.as_secs_f64()*1000., start.elapsed().as_secs_f64()*1000., world.uses_terrablender_routing());
    }

    #[test]
    fn terrablender_raw_markers_and_position_dependent_grass_keep_their_semantics() {
        let mut doc = document();
        for channel in ["temperature", "vegetation", "continents", "erosion", "depth", "ridges"] {
            let input = doc["settings"]["noise_router"][channel].clone();
            doc["settings"]["noise_router"][channel] = json!({"type":"minecraft:cache_2d","argument":{
                "type":"minecraft:flat_cache","argument":{"type":"minecraft:cache_once","argument":{
                    "type":"minecraft:interpolated","argument":input}}}});
        }
        let group = |biome: &str| json!({"biome":biome,"points":[[-4.,4.,-4.,4.,-4.,4.,-4.,4.,-4.,4.,-4.,4.,0.]]});
        doc["vss_terrablender"] = json!({"region_size":2,"regions":[
            {"name":"minecraft:overworld","weight":10,"base":true,"weighted":true},
            {"name":"test:swamp","weight":100,"weighted":true,"groups":[group("minecraft:swamp")]},
            {"name":"test:badlands","weight":100,"weighted":true,"groups":[group("minecraft:badlands")]}
        ]});
        doc["possible_biomes"] = json!(["minecraft:plains","minecraft:swamp","minecraft:badlands"]);
        let reference = World::new(42, 19, doc.clone()).unwrap();
        let world = World::new(42, 19, doc).unwrap();
        assert!(world.uses_terrablender_routing());
        let mut colors = std::collections::HashSet::new();
        for i in 0..1024 {
            let p = [i * 11 - 8000, 30 + (i % 19) * 4, 4000 - i * 17];
            let expected = fresh(&reference,p);
            assert_eq!(world.colors_at(p).unwrap(), expected, "{p:?}");
            colors.insert(expected);
            let adjacent = [p[0] + 1, p[1], p[2] + 1];
            assert_eq!(world.colors_at(adjacent).unwrap(), fresh(&reference,adjacent));
        }
        assert!(colors.len() > 1, "fixture must exercise multiple region/position tints");
    }
}
