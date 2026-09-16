//! Composes the native phases. A base/surface region does not claim carver or
//! structure completion. Feature calls require an explicit ordered schedule.
use crate::{
    biome::{zoom_quart, Biome, ClimateColors},
    blocks::{Palette, Pos, StateId, Volume},
    climate::BiomeSource,
    decoration::Schedule,
    density::Result,
    random::Random,
    surface::Surface,
    terrain::{Job, Substance, Terrain},
    vegetation::{Feature, Placed, PlacementContext},
};
use serde_json::{json, Value};
use std::collections::{BTreeSet, HashMap};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};

#[cfg(test)]
mod exterior_tests {
    use super::*;
    #[test]
    fn preview_does_not_contaminate_exact_columns() {
        let doc = document();
        let world = World::new(42, 0, doc.clone()).unwrap();
        let oracle = World::new(42, 0, doc).unwrap();
        let points = [(-16, -16), (0, 0), (15, 15), (512, -512)];
        let preview = world.preview_points(&points).unwrap();
        for record in &preview {
            assert_ne!(record.values[3] & (1 << 27), 0);
            assert!((-64..=320).contains(&record.values[0]));
        }
        for (&(x, z), record) in points.iter().zip(world.surface_points(&points).unwrap()) {
            assert_eq!(record.values[3] & (1 << 27), 0);
            assert_eq!(record.values, oracle.surface_point(x, z).unwrap().values);
        }
        // Exact-cache warmth must not change approximation quality or order.
        assert_eq!(preview.iter().map(|r| r.values).collect::<Vec<_>>(),
            world.preview_points(&points).unwrap().iter().map(|r| r.values).collect::<Vec<_>>());
        assert!(world.preview_points(&[(i32::MIN, 0)]).is_err());
        assert!(world.preview_points(&[(0, 0); 65]).is_err());
    }

    #[test]
    fn preview_preserves_world_material_rules_and_empty_columns() {
        let mut doc = document();
        doc["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:y_clamped_gradient",
            "from_y":0,"to_y":128,"from_value":1.,"to_value":-1.});
        doc["settings"]["surface_rule"] = json!({"type":"minecraft:block",
            "result_state":{"Name":"minecraft:orange_terracotta"}});
        let world = World::new(42, 0, doc.clone()).unwrap();
        let row = world.preview_points(&[(0, 0)]).unwrap()[0];
        assert!((60..=64).contains(&row.values[0]));
        assert_eq!(world.palette.state(row.values[4] as StateId).name, "minecraft:orange_terracotta");
        doc["settings"]["noise_router"]["final_density"] = json!(-1.);
        let empty = World::new(42, 0, doc).unwrap().preview_points(&[(0, 0)]).unwrap()[0];
        assert_ne!(empty.values[3] & (1 << 29), 0);
        assert_eq!(empty.values[2], 0);
    }

    fn document() -> Value {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
        let read = |name: &str| {
            serde_json::from_str::<Value>(&std::fs::read_to_string(root.join(name)).unwrap())
                .unwrap()
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
        doc["biome_source"] = json!({"type":"minecraft:fixed","biome":"minecraft:plains"});
        doc
    }
    fn compare(world: &World, x: i32, z: i32) -> (u128, u128) {
        let mut full = world.terrain.job(x & !15, z & !15, false).unwrap();
        let mut shell = world.terrain.job(x & !15, z & !15, false).unwrap();
        let start = std::time::Instant::now();
        let expected = world
            .generate_surface_point_depth(x, z, &mut full, false)
            .unwrap();
        let full_time = start.elapsed().as_nanos();
        let start = std::time::Instant::now();
        let actual = world
            .generate_surface_point_depth(x, z, &mut shell, true)
            .unwrap();
        let shell_time = start.elapsed().as_nanos();
        assert_eq!(
            expected.values, actual.values,
            "exterior mismatch at {x},{z}"
        );
        (full_time, shell_time)
    }
    #[test]
    fn exterior_preserves_ceiling_depth_and_surface_removal_rules() {
        let mut doc = document();
        doc["settings"]["aquifers_enabled"] = json!(false);
        doc["settings"]["ore_veins_enabled"] = json!(false);
        doc["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:y_clamped_gradient",
            "from_y":-64,"to_y":320,"from_value":1.0,"to_value":-1.0});
        for rule in [
            json!({"type":"minecraft:sequence","sequence":[
                {"type":"minecraft:condition","if_true":{"type":"minecraft:stone_depth","offset":32,
                    "add_surface_depth":true,"secondary_depth_range":30,"surface_type":"ceiling"},
                    "then_run":{"type":"minecraft:block","result_state":{"Name":"minecraft:packed_ice"}}},
                {"type":"minecraft:block","result_state":{"Name":"minecraft:sand"}}]}),
            json!({"type":"minecraft:condition","if_true":{"type":"minecraft:y_above","anchor":{"absolute":110},
                    "surface_depth_multiplier":0,"add_stone_depth":false},
                    "then_run":{"type":"minecraft:block","result_state":{"Name":"minecraft:water"}}}),
            json!({"type":"minecraft:block","result_state":{"Name":"minecraft:air"}}),
        ] {
            doc["settings"]["surface_rule"] = rule;
            let world = World::new(731, 0, doc.clone()).unwrap();
            for (x, z) in [(-17, -17), (7, 8), (16, -16)] {
                compare(&world, x, z);
                let actual = world.surface_point(x, z).unwrap();
                assert_eq!(
                    actual.values,
                    world.surface_columns(x >> 4, z >> 4).unwrap()
                        [((x & 15) * 16 + (z & 15)) as usize]
                        .values
                );
            }
        }
    }
    #[test]
    fn exterior_sample_cost_and_parity() {
        let world = World::new(731, 0, document()).unwrap();
        let mut full = Vec::new();
        let mut shell = Vec::new();
        for pass in 0..6 {
            let mut times = (0, 0);
            for (x, z) in [
                (0, 0),
                (-17, 35),
                (64, -128),
                (1024, 2048),
                (777, -919),
                (9999, -1999),
            ] {
                let (a, b) = compare(&world, x, z);
                times.0 += a;
                times.1 += b;
            }
            if pass > 0 {
                full.push(times.0);
                shell.push(times.1);
            }
        }
        full.sort();
        shell.sort();
        println!(
            "six vanilla sparse points, median of five: full_us={}, exterior_us={}",
            full[2] / 1000,
            shell[2] / 1000
        );
    }
    #[test]
    fn order_sensitive_density_keeps_complete_traversal() {
        let mut doc = document();
        doc["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:cache_2d","argument":{
            "type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":1.0,"to_value":-1.0}});
        let world = World::new(731, 0, doc).unwrap();
        assert!(world.terrain.graph.requires_complete_column_order());
        for (x, z) in [(-17, 5), (8, 9), (16, 16)] {
            compare(&world, x, z);
        }
    }
}
#[derive(Clone, Copy, Debug)]
pub struct SurfaceColumn {
    pub values: [i32; 10],
}
mod exterior;
mod display;
mod tint;
struct ColumnCache {
    values: HashMap<(i32, i32), Arc<Vec<SurfaceColumn>>>,
    order: std::collections::VecDeque<(i32, i32)>,
    points: HashMap<(i32, i32), SurfaceColumn>,
    point_order: std::collections::VecDeque<(i32, i32)>,
}
pub struct World {
    pub terrain: Terrain,
    pub palette: Palette,
    pub document: Value,
    source: BiomeSource,
    surface: Surface,
    colors: ClimateColors,
    biomes: HashMap<String, Biome>,
    zoom_seed: i64,
    pub base_ids: [StateId; 10],
    pub schedule: Option<Schedule>,
    adjustments: HashMap<(i32, i32), crate::beard::Beard>,
    columns: Mutex<ColumnCache>,
    /// Chunks served by assembling already-cached exact points instead of
    /// running `surface_region`. Diagnostic only.
    reused_chunk_builds: std::sync::atomic::AtomicU64,
    /// Cross-column `surface_top` cache. A `Job` is built per column and the
    /// four neighbour positions within a column are all distinct, so the map on
    /// `Job` never sees a repeat; this one survives across calls so adjacent
    /// grid samples reuse each other's column scans. A top is a pure function
    /// of `(x, z)` under the world's fixed adjustments.
    neighbour_tops: Mutex<HashMap<(i32, i32), Option<(i32, Substance)>>>,
    surface_work: Mutex<std::collections::VecDeque<((i32, i32), crate::terrain::SurfaceWork)>>,
    display_columns: Mutex<display::DisplayColumns>,
    display_queries: [std::sync::atomic::AtomicU64; 5],
    // Decoration page calls, requested columns, warm columns, elapsed nanos.
    decoration_queries: [std::sync::atomic::AtomicU64; 4],
    display_liquids: std::sync::OnceLock<bool>,
    display_biomes: std::collections::HashSet<String>,
    display_work: Mutex<std::collections::VecDeque<((i32,i32),crate::terrain::SurfaceWork)>>,
    cancelled: AtomicBool,
    color_biomes: [Mutex<tint::BiomeCache>; 16],
    color_work: Mutex<tint::WorkPool>,
    column_locks: [Mutex<()>; 64],
    placed_cache: Mutex<HashMap<String, Arc<Placed>>>,
    color_generation: std::sync::atomic::AtomicU64,
    work: [std::sync::atomic::AtomicU64; 2],
}
impl World {
    /// Whether biome selection replays TerraBlender positional regions. The
    /// JNI describe() reports this so the Java side only trusts native
    /// surface materials when the routing was actually compiled in.
    pub fn uses_terrablender_routing(&self) -> bool {
        self.source.uses_terrablender_routing()
    }

    pub fn new(seed: i64, zoom_seed: i64, document: Value) -> Result<Self> {
        Self::new_with_palette(seed, zoom_seed, document, None)
    }
    pub fn new_with_palette(seed: i64, zoom_seed: i64, document: Value, shared: Option<Palette>) -> Result<Self> {
        let mut terrain = Terrain::from_document(seed, &document)?;
        let mut palette = match shared { Some(p) => p, None => Palette::from_json(&document["block_definitions"])? };
        if let Some(states) = document.get("input_states") {
            for state in states.as_array().ok_or("invalid input state table")? {
                palette.intern(state)?;
            }
        }
        let surface = Surface::new(&mut terrain, &document, &mut palette)?;
        let colors = ClimateColors::new(
            serde_json::from_value(document["grass_colormap"].clone())
                .map_err(|e| format!("grass colormap: {e}"))?,
            serde_json::from_value(document["foliage_colormap"].clone())
                .map_err(|e| format!("foliage colormap: {e}"))?,
        )?;
        let mut biomes = HashMap::new();
        for (name, definition) in document["biomes"].as_object().ok_or("missing biomes")? {
            biomes.insert(name.clone(), Biome::from_json(definition)?);
        }
        let source = BiomeSource::from_document(&document, &terrain.graph, seed)?;
        let schedule = document
            .get("possible_biomes")
            .map(|v| -> Result<_> {
                let possible: Vec<String> =
                    serde_json::from_value(v.clone()).map_err(|_| "invalid possible biomes")?;
                Schedule::build(&document, &possible)
            })
            .transpose()?;
        let mut adjustments = HashMap::new();
        if let Some(values) = document.get("structure_terrain") {
            let values = values
                .as_array()
                .ok_or("invalid structure terrain contexts")?;
            if values.len() > 1024 {
                return Err("structure terrain context budget".into());
            }
            for v in values {
                let cx = crate::density::integer(v, "chunk_x")?;
                let cz = crate::density::integer(v, "chunk_z")?;
                if adjustments
                    .insert((cx, cz), crate::beard::Beard::parse(v)?)
                    .is_some()
                {
                    return Err("duplicate structure terrain context".into());
                }
            }
        }
        let kinds = [
            Substance::Air,
            Substance::Default,
            Substance::Water,
            Substance::Lava,
            Substance::CopperOre,
            Substance::RawCopper,
            Substance::Granite,
            Substance::IronOre,
            Substance::RawIron,
            Substance::Tuff,
        ];
        let mut base_ids = [0; 10];
        for (i, kind) in kinds.into_iter().enumerate() {
            base_ids[i] = if kind == Substance::Default {
                palette.intern(&document["settings"]["default_block"])?
            } else {
                palette.named(kind.block_name(&terrain.default_block))?
            };
        }
        let display_biomes=biomes.keys().filter(|name|
            !matches!(name.as_str(),"minecraft:eroded_badlands"|"minecraft:frozen_ocean"|"minecraft:deep_frozen_ocean")
                && surface.display_safe_for_biome(&palette,Some(name))).cloned().collect();
        Ok(Self {
            terrain,
            palette,
            document,
            source,
            surface,
            colors,
            biomes,
            zoom_seed,
            base_ids,
            schedule,
            adjustments,
            cancelled: AtomicBool::new(false),
            color_biomes: std::array::from_fn(|_| Mutex::new(tint::BiomeCache::default())),
            color_work: Mutex::new(tint::WorkPool::default()),
            column_locks: std::array::from_fn(|_| Mutex::new(())),
            placed_cache: Mutex::new(HashMap::new()),
            color_generation: std::sync::atomic::AtomicU64::new(0),
            work: std::array::from_fn(|_| std::sync::atomic::AtomicU64::new(0)),
            columns: Mutex::new(ColumnCache {
                values: HashMap::new(),
                order: std::collections::VecDeque::new(),
                points: HashMap::new(),
                point_order: std::collections::VecDeque::new(),
            }),
            reused_chunk_builds: std::sync::atomic::AtomicU64::new(0),
            neighbour_tops: Mutex::new(HashMap::new()),
            surface_work: Mutex::new(std::collections::VecDeque::new()),
            display_columns: Mutex::new(display::DisplayColumns::new(65536)),
            display_queries: std::array::from_fn(|_| std::sync::atomic::AtomicU64::new(0)),
            decoration_queries: std::array::from_fn(|_| std::sync::atomic::AtomicU64::new(0)),
            display_liquids: std::sync::OnceLock::new(),
            display_biomes,
            display_work: Mutex::new(std::collections::VecDeque::new()),
        })
    }
    /// Attaches the world-owned cross-column `surface_top` cache to a job.
    ///
    /// Kept behind a helper so the `no-shared-tops` measurement feature can
    /// detach it in one place. Leaving `shared_tops` as `None` is exactly the
    /// pre-existing behaviour: only the per-job map is consulted, which a
    /// single-column job never gets a hit from.
    fn attach_tops<'a>(&'a self, job: &mut Job<'a>) {
        #[cfg(not(feature = "no-shared-tops"))]
        {
            job.shared_tops = Some(&self.neighbour_tops);
        }
        #[cfg(feature = "no-shared-tops")]
        {
            let _ = job;
        }
    }
    pub fn surface_region(&self, chunk_x: i32, chunk_z: i32, side: i32) -> Result<Volume> {
        self.check_active()?;
        if !(1..=5).contains(&side)
            || !(-1_874_998..=1_874_994).contains(&chunk_x)
            || !(-1_874_998..=1_874_994).contains(&chunk_z)
        {
            return Err("region coordinates/size out of range".into());
        }
        let t = &self.terrain;
        let origin = [chunk_x * 16, t.min_y, chunk_z * 16];
        let mut world = Volume::new(
            origin,
            [
                (side * 16) as usize,
                t.height as usize,
                (side * 16) as usize,
            ],
            self.palette.clone(),
        )?;
        // All base columns precede the surface pass. This preserves the height
        // context and avoids a feature querying partially generated neighbours.
        for cx in 0..side {
            for cz in 0..side {
                let x0 = origin[0] + cx * 16;
                let z0 = origin[2] + cz * 16;
                let mut job = t.job(x0, z0, false)?;
                job.beard = self.adjustments.get(&(chunk_x + cx, chunk_z + cz));
                self.check_active()?;
                job.fill_chunk(x0, z0, |x, y, z, block| {
                    world.set([x, y, z], self.base_ids[block as usize]);
                });
            }
        }
        let mut biome_scratch =
            t.graph
                .scratch(origin[0], origin[2], t.cell_width, t.cell_height)?;
        let mut last = None;
        let mut biome_cache = HashMap::new();
        for cx in 0..side {
            for cz in 0..side {
                let x0 = origin[0] + cx * 16;
                let z0 = origin[2] + cz * 16;
                let mut job = t.job(x0, z0, false)?;
                self.check_active()?;
                self.surface
                    .apply_chunk(&mut job, &mut world, x0, z0, &self.colors, |p| {
                        let mut q = zoom_quart(self.zoom_seed, p);
                        q[1] = q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
                        let name = *biome_cache.entry(q).or_insert_with(|| {
                            self.source
                                .sample(&t.graph, q, &mut biome_scratch, &mut last)
                        });
                        let data = self.biomes.get(name).ok_or_else(|| {
                            format!("biome source references absent biome {name}")
                        })?;
                        Ok((name, data))
                    })?;
            }
        }
        Ok(world)
    }
    pub fn colors_at(&self, p: Pos) -> Result<[u32; 3]> {
        let t = &self.terrain;
        let mut q = zoom_quart(self.zoom_seed, p);
        q[1] = q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
        let b = self.color_biome(q)?;
        Ok([
            self.colors.grass(&b, p[0] as f64, p[2] as f64),
            self.colors.foliage(&b),
            b.water,
        ])
    }
    pub fn feature(
        &self,
        world: &mut Volume,
        value: &Value,
        seed: i64,
        p: Pos,
    ) -> Result<(bool, i64)> {
        let feature = Feature::compile(value, &self.document, &mut world.palette)?;
        let name = value.as_str().unwrap_or("inline");
        let mut allow = |_: &str, _| true;
        let mut context = PlacementContext::new(name, &mut allow);
        let mut random = Random::new(seed, 3);
        let result = feature.place_transaction(world, &mut random, p, &mut context)?;
        Ok((result, random.next_long()))
    }
    pub fn support(&self, value: &Value, placed: bool) -> Result<()> {
        let mut palette = self.palette.clone();
        if placed {
            Placed::compile(value, &self.document, &mut palette)?.deterministic()
        } else {
            Feature::compile(value, &self.document, &mut palette)?.deterministic()
        }
    }
    pub fn placed(
        &self,
        world: &mut Volume,
        name: &str,
        chunk_x: i32,
        chunk_z: i32,
        index: i32,
        step: i32,
    ) -> Result<(bool, i64)> {
        if !(-1874998..=1874997).contains(&chunk_x)
            || !(-1874998..=1874997).contains(&chunk_z)
            || !(0..=65535).contains(&index)
            || !(0..32).contains(&step)
        {
            return Err("invalid decoration coordinates/index/step".into());
        }
        let schedule = self
            .schedule
            .as_ref()
            .ok_or("missing possible_biomes schedule")?;
        if schedule
            .steps
            .get(step as usize)
            .and_then(|v| v.get(index as usize))
            .map(String::as_str)
            != Some(name)
        {
            return Err("feature identity does not match native global index".into());
        }
        self.check_active()?;
        let cached = self
            .placed_cache
            .lock()
            .map_err(|_| "placed feature cache lock")?
            .get(name)
            .cloned();
        let feature = match cached {
            Some(feature) => feature,
            None => {
                let compiled = Arc::new(Placed::compile(
                    &json!(name),
                    &self.document,
                    &mut world.palette,
                )?);
                // Game snapshots supply all runtime states. Smaller offline
                // palettes may allocate local IDs, which cannot be shared.
                if world.palette.states.len() == self.palette.states.len() {
                    self.placed_cache
                        .lock()
                        .map_err(|_| "placed feature cache lock")?
                        .insert(name.into(), compiled.clone());
                }
                compiled
            }
        };
        let t = &self.terrain;
        let mut scratch =
            t.graph
                .scratch(chunk_x * 16, chunk_z * 16, t.cell_width, t.cell_height)?;
        let mut last = None;
        let mut allow = |feature: &str, p| {
            let mut q = zoom_quart(self.zoom_seed, p);
            q[1] = q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
            let biome = self.source.sample(&t.graph, q, &mut scratch, &mut last);
            schedule.allowed(biome, feature)
        };
        let mut context = PlacementContext::new(name, &mut allow);
        let mut random = Random::new(0, 3);
        let seed = random.decoration_seed(t.graph.seed, chunk_x * 16, chunk_z * 16);
        random.feature_seed(seed, index, step);
        let result = feature.place_transaction(
            world,
            &mut random,
            [chunk_x * 16, t.min_y, chunk_z * 16],
            &mut context,
        )?;
        Ok((result, random.next_long()))
    }
    pub fn state_table(palette: &Palette) -> Value {
        json!(palette.states.iter().map(|s| s.json()).collect::<Vec<_>>())
    }
    pub fn surface_columns(&self, cx: i32, cz: i32) -> Result<Arc<Vec<SurfaceColumn>>> {
        self.check_active()?;
        let generation = self.color_generation.load(Ordering::Acquire);
        let key = (cx, cz);
        if let Some(value) = self
            .columns
            .lock()
            .map_err(|_| "surface cache lock")?
            .values
            .get(&key)
            .cloned()
        {
            return Ok(value);
        }
        let stripe =
            ((cx as u32).wrapping_mul(73856093) ^ (cz as u32).wrapping_mul(19349663)) as usize & 63;
        let _generation = self.column_locks[stripe]
            .lock()
            .map_err(|_| "surface generation lock")?;
        if let Some(value) = self
            .columns
            .lock()
            .map_err(|_| "surface cache lock")?
            .values
            .get(&key)
            .cloned()
        {
            return Ok(value);
        }
        // Refinement samples exact points before decoration asks for the whole
        // chunk. When the density graph has no ordering dependency, those points
        // are the same values `surface_region` would produce, so a chunk whose
        // 256 columns are all already cached can be assembled instead of
        // re-running the region. Graphs that need a complete column order
        // (Epic Terrain and similar) keep the full path.
        if !self.terrain.graph.requires_complete_column_order() {
            if let Some(assembled) = self.assemble_columns_from_points(cx, cz)? {
                self.reused_chunk_builds.fetch_add(1, Ordering::Relaxed);
                let mut cache = self.columns.lock().map_err(|_| "surface cache lock")?;
                if self.color_generation.load(Ordering::Acquire) != generation {
                    return Ok(assembled);
                }
                if let Some(old) = cache.values.get(&key) {
                    return Ok(old.clone());
                }
                while cache.values.len() >= 1024 {
                    if let Some(old) = cache.order.pop_front() {
                        cache.values.remove(&old);
                    }
                }
                cache.order.push_back(key);
                cache.values.insert(key, assembled.clone());
                return Ok(assembled);
            }
        }
        let mut v = self.surface_region(cx, cz, 1)?;
        self.work[0].fetch_add(1, Ordering::Relaxed);
        let mut result = Vec::with_capacity(256);
        let t = &self.terrain;
        let mut scratch = t
            .graph
            .scratch(cx * 16, cz * 16, t.cell_width, t.cell_height)?;
        let mut last = None;
        for x in cx * 16..cx * 16 + 16 {
            for z in cz * 16..cz * 16 + 16 {
                result.push(self.column_record(&mut v, x, z, &mut scratch, &mut last)?);
            }
        }
        let result = Arc::new(result);
        let mut cache = self.columns.lock().map_err(|_| "surface cache lock")?;
        if self.color_generation.load(Ordering::Acquire) != generation {
            return Ok(result);
        }
        if let Some(old) = cache.values.get(&key) {
            return Ok(old.clone());
        }
        while cache.values.len() >= 1024 {
            if let Some(old) = cache.order.pop_front() {
                cache.values.remove(&old);
            }
        }
        cache.order.push_back(key);
        cache.values.insert(key, result.clone());
        Ok(result)
    }
    /// Assembles a chunk from exact points already present in the column cache.
    ///
    /// Returns `None` unless all 256 columns are cached: a partially refined
    /// chunk must still take the full region path rather than mix sources.
    /// Callers are responsible for checking that the graph does not require a
    /// complete column order.
    fn assemble_columns_from_points(
        &self,
        cx: i32,
        cz: i32,
    ) -> Result<Option<Arc<Vec<SurfaceColumn>>>> {
        let cache = self.columns.lock().map_err(|_| "surface cache lock")?;
        let mut result = Vec::with_capacity(256);
        for x in cx * 16..cx * 16 + 16 {
            for z in cz * 16..cz * 16 + 16 {
                match cache.points.get(&(x, z)) {
                    Some(column) => result.push(*column),
                    None => return Ok(None),
                }
            }
        }
        Ok(Some(Arc::new(result)))
    }
    /// Chunks served by assembling already-cached exact points instead of
    /// running `surface_region`. Diagnostic only.
    pub fn reused_chunk_builds(&self) -> u64 {
        self.reused_chunk_builds.load(Ordering::Relaxed)
    }
    fn column_record(
        &self,
        v: &mut Volume,
        x: i32,
        z: i32,
        scratch: &mut crate::density::Scratch,
        last: &mut Option<usize>,
    ) -> Result<SurfaceColumn> {
        let t = &self.terrain;
        let mut floor = t.min_y;
        let mut fluid_y = t.min_y;
        let mut fluid = 0;
        for y in (t.min_y..t.min_y + t.height).rev() {
            let b = v.get([x, y, z]);
            if v.palette.is_air(b) {
                continue;
            }
            if v.palette.fluid(b) {
                if fluid == 0 {
                    fluid = if v.palette.is(b, "minecraft:lava") {
                        2
                    } else {
                        1
                    };
                    fluid_y = y + 1;
                }
            } else {
                floor = y + 1;
                break;
            }
        }
        if fluid_y <= floor {
            fluid = 0;
            fluid_y = floor;
        }
        let top = v.get([x, floor - 1, z]);
        let under = v.get([x, floor - 2, z]);
        let deep = v.get([x, floor - 7, z]);
        self.record_surface(x,z,floor,fluid_y,fluid,[top,under,deep],scratch,last)
    }
    fn record_surface(&self,x:i32,z:i32,floor:i32,fluid_y:i32,fluid:i32,
        materials:[StateId;3],scratch:&mut crate::density::Scratch,last:&mut Option<usize>) -> Result<SurfaceColumn> {
        let t=&self.terrain;
        let [top,under,deep]=materials;
        let p = [x, if fluid != 0 { fluid_y - 1 } else { floor }, z];
        let mut q = zoom_quart(self.zoom_seed, p);
        q[1] = q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
        let name = self.source.sample(&t.graph, q, scratch, last);
        let b = self.biomes.get(name).ok_or("missing surface biome")?;
        let cold = self.colors.temperature(b, p) < 0.15;
        let rain = self.document["biomes"][name]["has_precipitation"]
            .as_bool()
            .unwrap_or(false);
        let flags = (1 << 28)
            | if floor == t.min_y { 1 << 29 } else { 0 }
            | if cold && rain { 1 } else { 0 }
            | if cold && fluid == 1 { 2 } else { 0 };
        Ok(SurfaceColumn {
            values: [
                floor,
                fluid_y,
                fluid,
                flags,
                top as i32,
                under as i32,
                deep as i32,
                self.colors.grass(b, x as f64, z as f64) as i32,
                self.colors.foliage(b) as i32,
                b.water as i32,
            ],
        })
    }
    pub fn surface_point(&self, x: i32, z: i32) -> Result<SurfaceColumn> {
        if let Some(v) = self.cached_surface_point(x, z)? {
            return Ok(v);
        }
        crate::prof::hit(&crate::prof::SURFACE.columns);
        let cx = x >> 4;
        let cz = z >> 4;
        // Reuse the same chunk-level job pool `surface_points` uses, so points
        // queried one at a time in one chunk share density corners, preliminary
        // heights, aquifer locations and neighbour tops instead of rebuilding
        // them for every column. A sparse walk still misses the pool, which is
        // why the pool is bounded rather than world sized.
        let reusable = !self.terrain.graph.requires_complete_column_order();
        let parked = if reusable {
            let mut pool = self.surface_work.lock().map_err(|_| "surface work lock")?;
            pool.iter()
                .position(|(key, _)| *key == (cx, cz))
                .and_then(|i| pool.remove(i))
                .map(|(_, work)| work)
        } else {
            None
        };
        let mut job = {
            let _prof = crate::prof::Scope::new(&crate::prof::SURFACE.column_setup);
            let mut job = match parked {
                Some(work) => Job::resume(&self.terrain, work),
                None => self.terrain.job(cx * 16, cz * 16, false)?,
            };
            job.beard = self.adjustments.get(&(cx, cz));
            self.attach_tops(&mut job);
            self.prepare_exterior_columns(&mut job, &[(x, z)]);
            job
        };
        let t = &self.terrain;
        let mut scratch = t
            .graph
            .scratch(x & !15, z & !15, t.cell_width, t.cell_height)?;
        let result = self.generate_surface_point(x, z, &mut job, &mut scratch);
        if reusable {
            // Park even after a failed generation: the caches are still warm,
            // and `generate_surface_point` already released the job.
            let mut pool = self.surface_work.lock().map_err(|_| "surface work lock")?;
            let work = job.park();
            let bytes = work.retained_bytes();
            if bytes <= 2 * 1024 * 1024 {
                let mut used: usize = pool.iter().map(|(_, w)| w.retained_bytes()).sum();
                while pool.len() >= 8 || used + bytes > 16 * 1024 * 1024 {
                    let Some((_, old)) = pool.pop_front() else { break };
                    used = used.saturating_sub(old.retained_bytes());
                }
                pool.push_back(((cx, cz), work));
            }
        }
        result
    }
    /// Diagnostic only, never called by generation. Pairs the preliminary
    /// height with the exact `floor` that `surface_point` produces, so an
    /// offline probe can decide whether `preliminary` is a safe upper bound for
    /// skipping the air part of a column.
    ///
    /// `Job::preliminary` is evaluated on a 4x4 lattice (`x & !3`, `z & !3`),
    /// so a whole block shares one value and it is only usable as an upper
    /// bound if it clears every column in that block. It also ignores the
    /// beardifier, which the exact floor does not.
    ///
    /// Returns `[x, z, preliminary, floor]` per requested point, in order.
    #[doc(hidden)]
    pub fn probe_preliminary(&self, points: &[(i32, i32)]) -> Result<Vec<[i32; 4]>> {
        let mut out = Vec::with_capacity(points.len());
        for &(x, z) in points {
            let mut job = self.terrain.job(x & !15, z & !15, false)?;
            job.beard = self.adjustments.get(&(x >> 4, z >> 4));
            self.attach_tops(&mut job);
            let preliminary = job.preliminary(x, z);
            let floor = self.surface_point(x, z)?.values[0];
            out.push([x, z, preliminary, floor]);
        }
        Ok(out)
    }
    /// Diagnostic only, never called by generation. Compares the cheap raw
    /// pre-scan against the exact path's first non-air block.
    ///
    /// `Terrain::preview_height` walks down in 16-block steps evaluating
    /// `final_density` with `Mode::Raw`, then bisects to within 4 blocks. That
    /// is the same function the exact path evaluates, so unlike
    /// `Job::preliminary` it tracks the real surface - but it skips
    /// interpolation, the beardifier and the aquifer, so it can disagree in
    /// either direction.
    ///
    /// A pre-scan can only shorten the exact march if it never lands *below*
    /// the exact answer, and is only worth doing if the gap stays short. Both
    /// values use floor semantics: one past the highest non-air block.
    ///
    /// Returns `[x, z, raw_floor, exact_floor, block_floor, linear_floor]` per
    /// point, in order.
    ///
    /// * `raw_floor` - `Terrain::preview_height` (`Mode::Raw`, no beardifier)
    /// * `block_floor` - `Job::probe_height_by_block` (coarse stride + stride scan)
    /// * `linear_floor` - the same top-down single-block scan `surface_top`
    ///   performs, repeated here independently
    /// * `exact_floor` - `Job::surface_top`
    ///
    /// `block_floor` and `linear_floor` should agree if the stride scan is
    /// correct; `linear_floor` and `exact_floor` should agree if `Job::block`
    /// is a pure function of position. Comparing all four separates "the coarse
    /// stride missed something" from "block disagrees with itself".
    #[doc(hidden)]
    pub fn probe_raw_vs_exact(&self, points: &[(i32, i32)]) -> Result<Vec<[i32; 6]>> {
        let t = &self.terrain;
        let mut out = Vec::with_capacity(points.len());
        for &(x, z) in points {
            let mut job = self.terrain.job(x & !15, z & !15, false)?;
            job.beard = self.adjustments.get(&(x >> 4, z >> 4));
            self.attach_tops(&mut job);
            let mut scratch = t
                .graph
                .scratch(x & !15, z & !15, t.cell_width, t.cell_height)?;
            let raw = t.preview_height(x, z, &mut scratch);
            let by_block = job.probe_height_by_block(x, z);
            let mut linear = t.min_y;
            for py in (t.min_y..t.min_y + t.height).rev() {
                if job.block([x, py, z]) != Substance::Air {
                    linear = py + 1;
                    break;
                }
            }
            let exact = job.surface_top(x, z).map_or(t.min_y, |(y, _)| y + 1);
            out.push([x, z, raw, exact, by_block, linear]);
        }
        Ok(out)
    }
    /// Reuse density corners, aquifer locations and preliminary heights for
    /// points in the same chunk, while preserving the caller's output order.
    pub fn surface_points(&self, points: &[(i32, i32)]) -> Result<Vec<SurfaceColumn>> {
        self.check_active()?;
        if points.len() > 64 {
            return Err("surface batch count".into());
        }
        let mut result = vec![SurfaceColumn { values: [0; 10] }; points.len()];
        let mut groups = std::collections::BTreeMap::<_, Vec<_>>::new();
        for (i, &(x, z)) in points.iter().enumerate() {
            if let Some(v) = self.cached_surface_point(x, z)? {
                result[i] = v;
            } else {
                groups.entry((x >> 4, z >> 4)).or_default().push(i);
            }
        }
        for ((cx, cz), indices) in groups {
            let reusable = !self.terrain.graph.requires_complete_column_order();
            let cached = if reusable {
                let mut pool = self.surface_work.lock().map_err(|_| "surface work lock")?;
                pool.iter().position(|(key, _)| *key == (cx, cz)).and_then(|i| pool.remove(i)).map(|(_, work)| work)
            } else { None };
            let mut job = match cached {
                Some(work) => Job::resume(&self.terrain, work),
                None => self.terrain.job(cx * 16, cz * 16, false)?,
            };
            job.beard = self.adjustments.get(&(cx, cz));
            self.attach_tops(&mut job);
            let missing: Vec<_> = indices.iter().map(|&i| points[i]).collect();
            self.prepare_exterior_columns(&mut job, &missing);
            // One scratch per chunk: its origin is the chunk origin, which is
            // what every column in this group samples from.
            let t = &self.terrain;
            let mut scratch = t
                .graph
                .scratch(cx * 16, cz * 16, t.cell_width, t.cell_height)?;
            for i in indices {
                let (x, z) = points[i];
                result[i] = match self.cached_surface_point(x, z)? {
                    Some(v) => v,
                    None => {
                        crate::prof::hit(&crate::prof::SURFACE.columns);
                        self.generate_surface_point(x, z, &mut job, &mut scratch)?
                    }
                };
            }
            if reusable {
                let mut pool = self.surface_work.lock().map_err(|_| "surface work lock")?;
                // A small working set across JNI batches, never a world-sized
                // cache of vertical volumes. Active jobs hold no pool lock.
                let work=job.park();
                let bytes=work.retained_bytes();
                if bytes<=2*1024*1024 {
                    let mut used:usize=pool.iter().map(|(_,w)|w.retained_bytes()).sum();
                    while pool.len()>=8 || used+bytes>16*1024*1024 {
                        let Some((_,old))=pool.pop_front() else {break;};
                        used=used.saturating_sub(old.retained_bytes());
                    }
                    pool.push_back(((cx, cz), work));
                }
            }
        }
        Ok(result)
    }
    /// Approximate exterior records have their own API and never enter the
    /// exact point/chunk caches used by terrain and vegetation generation.
    pub fn preview_points(&self, points: &[(i32, i32)]) -> Result<Vec<SurfaceColumn>> {
        self.check_active()?;
        if points.len() > 64 || points.iter().any(|&(x, z)| x.abs_diff(0) > 29999990 || z.abs_diff(0) > 29999990) {
            return Err("preview batch outside range".into());
        }
        let t = &self.terrain;
        let mut result = Vec::with_capacity(points.len());
        for &(x, z) in points {
            self.check_active()?;
            // Per-point scratch bounds caches and prevents order-sensitive
            // biome samplers from leaking state across unrelated chunks.
            let mut scratch = t.graph.scratch(x & !15, z & !15, t.cell_width, t.cell_height)?;
            let floor = t.preview_height(x, z, &mut scratch);
            let (fluid_y, fluid) = t.preview_fluid(floor);
            let p = [x, if fluid != 0 { fluid_y - 1 } else { floor }, z];
            let mut q = zoom_quart(self.zoom_seed, p);
            q[1] = q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
            let name = self.source.sample(&t.graph, q, &mut scratch, &mut None);
            let b = self.biomes.get(name).ok_or("missing preview biome")?;
            let cold = self.colors.temperature(b, p) < 0.15;
            let rain = self.document["biomes"][name]["has_precipitation"].as_bool().unwrap_or(false);
            let materials = if floor == t.min_y { [self.base_ids[0]; 3] } else {
                self.surface.preview_materials(t, x, z, floor,
                    if fluid != 0 { fluid_y } else { i32::MIN }, name, b, &self.colors)
            };
            let flags = (1 << 27) | (1 << 28)
                | if floor == t.min_y { 1 << 29 } else { 0 }
                | if cold && rain { 1 } else { 0 }
                | if cold && fluid == 1 { 2 } else { 0 };
            result.push(SurfaceColumn { values: [floor, fluid_y, fluid, flags,
                materials[0] as i32, materials[1] as i32, materials[2] as i32,
                self.colors.grass(b, x as f64, z as f64) as i32,
                self.colors.foliage(b) as i32, b.water as i32] });
        }
        Ok(result)
    }

    fn prepare_exterior_columns(&self, job: &mut Job<'_>, points: &[(i32, i32)]) {
        let t = &self.terrain;
        if !t.graph.supports_surface_slices() {
            job.prepare_surface_columns(points);
            return;
        }
        job.prepare_surface_columns_with_depth(points, |x, y, z| {
            let mut scratch = t
                .graph
                .scratch(x & !15, z & !15, t.cell_width, t.cell_height)
                .ok()?;
            let mut q = zoom_quart(self.zoom_seed, [x, if t.graph.legacy { 0 } else { y }, z]);
            q[1] = q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
            let name = self.source.sample(&t.graph, q, &mut scratch, &mut None);
            if matches!(
                name,
                "minecraft:eroded_badlands"
                    | "minecraft:frozen_ocean"
                    | "minecraft:deep_frozen_ocean"
            ) {
                return None;
            }
            self.surface.exterior_padding(&t.graph, x, z)
        });
    }
    fn cached_surface_point(&self, x: i32, z: i32) -> Result<Option<SurfaceColumn>> {
        self.check_active()?;
        if x.abs_diff(0) > 29999990 || z.abs_diff(0) > 29999990 {
            return Err("surface point outside range".into());
        }
        {
            let cache = self.columns.lock().map_err(|_| "surface cache lock")?;
            if let Some(v) = cache.values.get(&(x >> 4, z >> 4)) {
                return Ok(Some(v[((x & 15) * 16 + (z & 15)) as usize]));
            }
            if let Some(&v) = cache.points.get(&(x, z)) {
                return Ok(Some(v));
            }
        }
        Ok(None)
    }
    fn generate_surface_point(
        &self,
        x: i32,
        z: i32,
        job: &mut Job<'_>,
        scratch: &mut crate::density::Scratch,
    ) -> Result<SurfaceColumn> {
        let generation=self.color_generation.load(Ordering::Acquire);
        if let Some(result)=self.exterior_record(x,z,job,scratch)? {
            return self.store_surface_result(x,z,result,generation);
        }
        self.generate_surface_point_depth(x, z, job, true)
    }
    fn generate_surface_point_depth(
        &self,
        x: i32,
        z: i32,
        job: &mut Job<'_>,
        exterior: bool,
    ) -> Result<SurfaceColumn> {
        let generation = self.color_generation.load(Ordering::Acquire);
        let t = &self.terrain;
        let x0 = x & !15;
        let z0 = z & !15;
        let mut v = {
            let _prof = crate::prof::Scope::new(&crate::prof::SURFACE.volume_new);
            Volume::new(
                [x - 1, t.min_y, z - 1],
                [3, t.height as usize, 3],
                self.palette.clone(),
            )?
        };
        let positions: BTreeSet<_> = [
            (x, z),
            (x, (z - 1).max(z0)),
            (x, (z + 1).min(z0 + 15)),
            ((x - 1).max(x0), z),
            ((x + 1).min(x0 + 15), z),
        ]
        .into_iter()
        .collect();
        let mut scratch = {
            let _prof = crate::prof::Scope::new(&crate::prof::SURFACE.scratch);
            t.graph.scratch(x0, z0, t.cell_width, t.cell_height)?
        };
        let mut last = None;
        let mut cache = HashMap::new();
        let mut biome = |p| {
            let mut q = zoom_quart(self.zoom_seed, p);
            q[1] = q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
            let name = *cache
                .entry(q)
                .or_insert_with(|| self.source.sample(&t.graph, q, &mut scratch, &mut last));
            Ok((
                name,
                self.biomes
                    .get(name)
                    .ok_or("missing sparse surface biome")?,
            ))
        };
        let padding = {
            let _prof = crate::prof::Scope::new(&crate::prof::SURFACE.exterior_padding);
            if exterior && !t.graph.requires_complete_column_order() {
                self.surface.exterior_padding(&t.graph, x, z)
            } else {
                None
            }
        };
        let mut truncated = if exterior {
            job.surface_bottom(x, z)
        } else {
            None
        };
        {
            let _prof = crate::prof::Scope::new(&crate::prof::SURFACE.neighbour_fill);
            for &(xx, zz) in &positions {
                if (xx, zz) != (x, z) {
                    let top: Option<(i32, Substance)> = {
                        let _prof =
                            crate::prof::Scope::new(&crate::prof::SURFACE.neighbour_top);
                        // Measurement build only; see the `skip-neighbour-top`
                        // feature. Reporting "no surface found" removes the four
                        // neighbour column scans so a differential run can size
                        // them against `centre_march`.
                        #[cfg(feature = "skip-neighbour-top")]
                        {
                            None
                        }
                        #[cfg(not(feature = "skip-neighbour-top"))]
                        {
                            job.surface_top(xx, zz)
                        }
                    };
                    let y = top.map_or(t.min_y, |(y, _)| y + 1);
                    // Later neighbours have not had any surface geometry applied.
                    // Earlier badlands/icebergs need their actual column contents;
                    // all other neighbours are observed solely through height().
                    let needs_geometry = if (xx, zz) < (x, z) {
                        let (name, _) = biome([xx, if t.graph.legacy { 0 } else { y }, zz])?;
                        matches!(
                            name,
                            "minecraft:eroded_badlands"
                                | "minecraft:frozen_ocean"
                                | "minecraft:deep_frozen_ocean"
                        )
                    } else {
                        false
                    };
                    if !needs_geometry {
                        if let Some((y, b)) = top {
                            v.set([xx, y, zz], self.base_ids[b as usize]);
                        }
                        continue;
                    }
                }
                if (xx, zz) == (x, z) {
                    if let Some(padding) = padding {
                        let _prof_march =
                            crate::prof::Scope::new(&crate::prof::SURFACE.centre_march);
                        let mut stop = t.min_y;
                        let mut found = false;
                        let mut first_non_air = true;
                        let mut truncate_allowed = true;
                        let mut y = t.min_y + t.height - 1;
                        while y >= t.min_y {
                            if !found {
                                if let Some(bottom) = job.proven_air_bottom([x, y, z]) {
                                    y = bottom - 1;
                                    continue;
                                }
                            }
                            let block = {
                                let _prof =
                                    crate::prof::Scope::new(&crate::prof::SURFACE.centre_block);
                                job.block([x, y, z])
                            };
                            {
                                let _prof =
                                    crate::prof::Scope::new(&crate::prof::SURFACE.centre_set);
                                v.set([x, y, z], self.base_ids[block as usize]);
                            }
                            crate::prof::hit(&crate::prof::SURFACE.centre_steps);
                            if first_non_air && block != Substance::Air {
                                first_non_air = false;
                                let (name, _) =
                                    biome([x, if t.graph.legacy { 0 } else { y + 1 }, z])?;
                                truncate_allowed = !matches!(
                                    name,
                                    "minecraft:eroded_badlands"
                                        | "minecraft:frozen_ocean"
                                        | "minecraft:deep_frozen_ocean"
                                );
                            }
                            if !found && block.solid() {
                                found = true;
                                if truncate_allowed {
                                    stop = (y - 7 - padding).max(t.min_y);
                                }
                            }
                            if found && y == stop {
                                if stop > t.min_y {
                                    truncated = Some((stop, padding));
                                }
                                break;
                            }
                            y -= 1;
                        }
                        continue;
                    }
                }
                let col = {
                    let _prof =
                        crate::prof::Scope::new(&crate::prof::SURFACE.neighbour_column);
                    job.column(xx, zz)?
                };
                for (i, b) in col.blocks.into_iter().enumerate() {
                    v.set([xx, t.min_y + i as i32, zz], self.base_ids[b as usize]);
                }
            }
        }
        // SurfaceSystem traverses X then Z. Only earlier neighbours have had
        // badlands/iceberg geometry applied when steepness is evaluated.
        {
            let _prof = crate::prof::Scope::new(&crate::prof::SURFACE.neighbour_geometry);
            for &(xx, zz) in &positions {
                if (xx, zz) < (x, z) {
                    let top = v.height(xx, zz, false);
                    let (name, _) = biome([xx, if t.graph.legacy { 0 } else { top }, zz])?;
                    if !self.surface.sparse_safe_at(&v.palette, name, &t.graph, xx, zz) {
                        return Ok(
                            self.surface_columns(x >> 4, z >> 4)?[((x & 15) * 16 + (z & 15)) as usize]
                        );
                    }
                    self.surface
                        .geometry_column(job, &mut v, xx, zz, &self.colors, &mut biome)?;
                    let top = v.height(xx, zz, false);
                    if top > t.min_y {
                        let (name, _) = biome([xx, top - 1, zz])?;
                        if !self.surface.sparse_safe_at(&v.palette, name, &t.graph, xx, zz) {
                            return Ok(self.surface_columns(x >> 4, z >> 4)?
                                [((x & 15) * 16 + (z & 15)) as usize]);
                        }
                    }
                }
            }
        }
        {
            let _prof = crate::prof::Scope::new(&crate::prof::SURFACE.apply_rules);
            self.surface
                .apply_column(job, &mut v, x0, z0, x, z, &self.colors, &mut biome)?;
        }
        let result = {
            let _prof = crate::prof::Scope::new(&crate::prof::SURFACE.record);
            self.column_record(&mut v, x, z, &mut scratch, &mut last)?
        };
        if let Some((bottom, padding)) = truncated {
            if result.values[0] - 7 < bottom + padding {
                crate::prof::hit(&crate::prof::SURFACE.truncated_rebuilds);
                // A surface rule can remove solid blocks or turn them into
                // water. Never publish an output whose material crosses the
                // proven retained range; rebuild its complete column instead.
                let mut full = t.job(x0, z0, false)?;
                full.beard = job.beard;
                full.prepare_surface_columns(&[(x, z)]);
                return self.generate_surface_point_depth(x, z, &mut full, false);
            }
        }
        self.store_surface_result(x,z,result,generation)
    }
    fn store_surface_result(&self,x:i32,z:i32,result:SurfaceColumn,generation:u64) -> Result<SurfaceColumn> {
        {
            let _prof = crate::prof::Scope::new(&crate::prof::SURFACE.cache_store);
            self.work[1].fetch_add(1, Ordering::Relaxed);
            let mut cache = self.columns.lock().map_err(|_| "surface cache lock")?;
            if self.color_generation.load(Ordering::Acquire) != generation {
                return Ok(result);
            }
            if !cache.points.contains_key(&(x, z)) {
                while cache.points.len() >= 65536 {
                    if let Some(p) = cache.point_order.pop_front() {
                        cache.points.remove(&p);
                    }
                }
                cache.point_order.push_back((x, z));
                cache.points.insert((x, z), result);
            }
        }
        Ok(result)
    }
    /// The existing surface-only prediction context, assembled in native memory.
    /// Actual structure edits are overlaid before decoration; this is not a
    /// claim that carved/underground chunks have been generated.
    pub fn surface_proxy(&self, cx: i32, cz: i32) -> Result<Volume> {
        if !(-1874996..=1874995).contains(&cx) || !(-1874996..=1874995).contains(&cz) {
            return Err("proxy origin outside range".into());
        }
        let t = &self.terrain;
        let origin = [(cx - 2) * 16, t.min_y, (cz - 2) * 16];
        // Collect the column summaries first: the proxy computes every cell from
        // them on demand instead of materialising an 80 x height x 80 array
        // (about 9.4 MiB at height 384). Flatten the entire region in
        // x * 80 + z order, matching `ProxyBase::value`.
        let mut summaries = vec![[0; 10]; 80 * 80];
        for chunk_x in cx - 2..=cx + 2 {
            for chunk_z in cz - 2..=cz + 2 {
                let columns = self.surface_columns(chunk_x, chunk_z)?;
                for x in 0..16 {
                    for z in 0..16 {
                        let local_x = (chunk_x - (cx - 2)) * 16 + x;
                        let local_z = (chunk_z - (cz - 2)) * 16 + z;
                        summaries[(local_x * 80 + local_z) as usize] =
                            columns[(x * 16 + z) as usize].values;
                    }
                }
            }
        }
        let mut volume = Volume::proxy(
            origin,
            80,
            80,
            t.height as usize,
            self.palette.clone(),
            summaries,
        )?;
        volume.write_bounds = Some((
            [cx * 16 - 16, t.min_y, cz * 16 - 16],
            [cx * 16 + 32, t.min_y + t.height, cz * 16 + 32],
        ));
        volume.write_budget = 65536;
        Ok(volume)
    }
    /// Preserve exact column semantics without preparing unobserved neighbours.
    pub fn lazy_surface_proxy(self: &Arc<Self>, cx: i32, cz: i32) -> Result<Volume> {
        if !(-1874996..=1874995).contains(&cx) || !(-1874996..=1874995).contains(&cz) {
            return Err("proxy origin outside range".into());
        }
        let owner = Arc::clone(self);
        let t = &self.terrain;
        let mut volume = Volume::lazy_proxy(
            [(cx - 2) * 16, t.min_y, (cz - 2) * 16], 80, 80, t.height as usize,
            self.palette.clone(), Box::new(move |x, z| {
                let columns = owner.surface_columns(cx - 2 + x as i32, cz - 2 + z as i32)?;
                Ok(Arc::new(columns.iter().map(|column| column.values).collect()))
            }),
        )?;
        volume.write_bounds = Some((
            [cx * 16 - 16, t.min_y, cz * 16 - 16],
            [cx * 16 + 32, t.min_y + t.height, cz * 16 + 32],
        ));
        volume.write_budget = 65536;
        Ok(volume)
    }
    /// Small-page context for prediction only. Display records stay in their
    /// own cache; exact callers and unknown features retain exact queries.
    pub fn decoration_proxy(self: &Arc<Self>, cx: i32, cz: i32, display: bool) -> Result<Volume> {
        self.check_active()?;
        if !(-1874996..=1874995).contains(&cx) || !(-1874996..=1874995).contains(&cz) {
            return Err("proxy origin outside range".into());
        }
        let owner = Arc::clone(self);
        let t = &self.terrain;
        let ox = (cx - 2) * 16;
        let oz = (cz - 2) * 16;
        let mut volume = Volume::paged_proxy([ox, t.min_y, oz], 80, 80, t.height as usize,
            self.palette.clone(), 4, Box::new(move |px, pz| {
                owner.check_active()?;
                let points: Vec<_> = (0..16).map(|i|
                    (ox + px as i32 * 4 + i / 4, oz + pz as i32 * 4 + i % 4)).collect();
                let columns = owner.decoration_points(&points,display)?;
                Ok(Arc::new(columns.iter().map(|column| column.values).collect()))
            }))?;
        volume.write_bounds = Some(([cx*16-16,t.min_y,cz*16-16],
            [cx*16+32,t.min_y+t.height,cz*16+32]));
        volume.write_budget = 65536;
        Ok(volume)
    }
    pub fn decoration_points(&self, points: &[(i32,i32)], display: bool) -> Result<Vec<SurfaceColumn>> {
        self.check_active()?;
        let start = std::time::Instant::now();
        let mut hits = 0;
        {
            let exact = self.columns.lock().map_err(|_| "surface cache lock")?;
            let visual = self.display_columns.lock().map_err(|_| "display cache lock")?;
            for &(x,z) in points {
                if exact.values.contains_key(&(x>>4,z>>4)) || exact.points.contains_key(&(x,z))
                    || display && visual.contains_key(&(x,z)) { hits += 1; }
            }
        }
        let result = if display { self.display_points(points) } else { self.surface_points(points) };
        for (counter,value) in self.decoration_queries.iter().zip([1,points.len() as u64,hits,start.elapsed().as_nanos() as u64]) {
            counter.fetch_add(value,Ordering::Relaxed);
        }
        result
    }
    pub fn decoration_query_stats(&self) -> [u64;4] {
        std::array::from_fn(|i| self.decoration_queries[i].load(Ordering::Relaxed))
    }
    /// Selects features from the complete 3x3 biome neighbourhood, as vanilla
    /// ChunkGenerator.applyBiomeDecoration does. Block context is a separate
    /// explicit input, allowing structures/carvers from a compatible provider.
    pub fn decoration_indices(
        &self,
        chunk_x: i32,
        chunk_z: i32,
        step: usize,
    ) -> Result<Vec<usize>> {
        if !(-1874998..=1874997).contains(&chunk_x)
            || !(-1874998..=1874997).contains(&chunk_z)
            || step >= 32
        {
            return Err("invalid decoration region".into());
        }
        let schedule = self
            .schedule
            .as_ref()
            .ok_or("missing decoration schedule")?;
        let t = &self.terrain;
        let mut scratch =
            t.graph
                .scratch(chunk_x * 16, chunk_z * 16, t.cell_width, t.cell_height)?;
        let mut last = None;
        let mut biomes = BTreeSet::new();
        for x in (chunk_x - 1) * 4..(chunk_x + 2) * 4 {
            for z in (chunk_z - 1) * 4..(chunk_z + 2) * 4 {
                for y in t.min_y >> 2..(t.min_y + t.height) >> 2 {
                    let name = self
                        .source
                        .sample(&t.graph, [x, y, z], &mut scratch, &mut last);
                    biomes.insert(name.to_owned());
                }
            }
        }
        Ok(schedule.indices(step, &biomes))
    }
    /// A supported step commits atomically. Unsupported features report their
    /// names; no partially decorated chunk is returned as complete.
    pub fn decorate_step(
        &self,
        volume: &mut Volume,
        chunk_x: i32,
        chunk_z: i32,
        step: usize,
    ) -> Result<Value> {
        let indices = self.decoration_indices(chunk_x, chunk_z, step)?;
        let schedule = self
            .schedule
            .as_ref()
            .ok_or("missing decoration schedule")?;
        let mut blocked = vec![];
        for &i in &indices {
            let name = &schedule.steps[step][i];
            if let Err(reason) = self.support(&json!(name), true) {
                blocked.push(json!({"feature":name,"reason":reason}));
            }
        }
        if !blocked.is_empty() {
            return Err(format!(
                "decoration step needs compatibility provider: {}",
                json!(blocked)
            ));
        }
        let checkpoint = volume.decoration_checkpoint()?;
        let mut placed = 0;
        for &i in &indices {
            match self.placed(
                volume,
                &schedule.steps[step][i],
                chunk_x,
                chunk_z,
                i as i32,
                step as i32,
            ) {
                Ok((yes, _)) => placed += usize::from(yes),
                Err(e) => {
                    volume.restore_decoration(checkpoint);
                    return Err(e);
                }
            }
        }
        Ok(json!({"features":indices.len(),"placed":placed,"step":step,"complete_worldgen":false}))
    }
    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
    }
    pub fn work_counts(&self) -> [u64; 2] {
        std::array::from_fn(|i| self.work[i].load(Ordering::Relaxed))
    }
    pub fn replace_colormaps(&self, grass: Vec<u32>, foliage: Vec<u32>) -> Result<()> {
        self.check_active()?;
        self.colors.replace_colormaps(grass, foliage)?;
        let mut cache = self.columns.lock().map_err(|_| "surface cache lock")?;
        self.color_generation.fetch_add(1, Ordering::AcqRel);
        cache.values.clear();
        cache.order.clear();
        cache.points.clear();
        cache.point_order.clear();
        self.display_columns.lock().map_err(|_| "display cache lock")?.clear();
        Ok(())
    }
    pub fn check_active(&self) -> Result<()> {
        if self.cancelled.load(Ordering::Acquire) {
            Err("native world cancelled".into())
        } else {
            Ok(())
        }
    }
}
