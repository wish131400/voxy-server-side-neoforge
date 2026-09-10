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
    cancelled: AtomicBool,
    color_biomes: Mutex<HashMap<Pos, Biome>>,
    column_locks: [Mutex<()>; 64],
    placed_cache: Mutex<HashMap<String, Arc<Placed>>>,
    color_generation: std::sync::atomic::AtomicU64,
    work: [std::sync::atomic::AtomicU64; 2],
}
impl World {
    pub fn new(seed: i64, zoom_seed: i64, document: Value) -> Result<Self> {
        let mut terrain = Terrain::from_document(seed, &document)?;
        let mut palette = Palette::from_json(&document["block_definitions"])?;
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
        let source = BiomeSource::from_document(&document, &terrain.graph)?;
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
            color_biomes: Mutex::new(HashMap::new()),
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
        })
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
        {
            let cache = self
                .color_biomes
                .lock()
                .map_err(|_| "biome color cache lock")?;
            if let Some(b) = cache.get(&q) {
                return Ok([
                    self.colors.grass(b, p[0] as f64, p[2] as f64),
                    self.colors.foliage(b),
                    b.water,
                ]);
            }
        }
        let mut scratch = t
            .graph
            .scratch(p[0] & !15, p[2] & !15, t.cell_width, t.cell_height)?;
        let mut last = None;
        let name = self.source.sample(&t.graph, q, &mut scratch, &mut last);
        let b = self
            .biomes
            .get(name)
            .ok_or_else(|| format!("missing biome {name}"))?;
        {
            let mut cache = self
                .color_biomes
                .lock()
                .map_err(|_| "biome color cache lock")?;
            if cache.len() >= 65536 {
                cache.clear();
            }
            cache.insert(q, b.clone());
        }
        Ok([
            self.colors.grass(b, p[0] as f64, p[2] as f64),
            self.colors.foliage(b),
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
        let mut job = self.terrain.job(x & !15, z & !15, false)?;
        job.beard = self.adjustments.get(&(x >> 4, z >> 4));
        self.prepare_exterior_columns(&mut job, &[(x, z)]);
        self.generate_surface_point(x, z, &mut job)
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
            let mut job = self.terrain.job(cx * 16, cz * 16, false)?;
            job.beard = self.adjustments.get(&(cx, cz));
            let missing: Vec<_> = indices.iter().map(|&i| points[i]).collect();
            self.prepare_exterior_columns(&mut job, &missing);
            for i in indices {
                let (x, z) = points[i];
                result[i] = match self.cached_surface_point(x, z)? {
                    Some(v) => v,
                    None => self.generate_surface_point(x, z, &mut job)?,
                };
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
    fn generate_surface_point(&self, x: i32, z: i32, job: &mut Job<'_>) -> Result<SurfaceColumn> {
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
        let mut v = Volume::new(
            [x - 1, t.min_y, z - 1],
            [3, t.height as usize, 3],
            self.palette.clone(),
        )?;
        let positions: BTreeSet<_> = [
            (x, z),
            (x, (z - 1).max(z0)),
            (x, (z + 1).min(z0 + 15)),
            ((x - 1).max(x0), z),
            ((x + 1).min(x0 + 15), z),
        ]
        .into_iter()
        .collect();
        let mut scratch = t.graph.scratch(x0, z0, t.cell_width, t.cell_height)?;
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
        let padding = if exterior && !t.graph.requires_complete_column_order() {
            self.surface.exterior_padding(&t.graph, x, z)
        } else {
            None
        };
        let mut truncated = if exterior {
            job.surface_bottom(x, z)
        } else {
            None
        };
        for &(xx, zz) in &positions {
            if (xx, zz) != (x, z) {
                let top = job.surface_top(xx, zz);
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
                    let mut stop = t.min_y;
                    let mut found = false;
                    let mut first_non_air = true;
                    let mut truncate_allowed = true;
                    for y in (t.min_y..t.min_y + t.height).rev() {
                        let block = job.block([x, y, z]);
                        v.set([x, y, z], self.base_ids[block as usize]);
                        if first_non_air && block != Substance::Air {
                            first_non_air = false;
                            let (name, _) = biome([x, if t.graph.legacy { 0 } else { y + 1 }, z])?;
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
                    }
                    continue;
                }
            }
            let col = job.column(xx, zz)?;
            for (i, b) in col.blocks.into_iter().enumerate() {
                v.set([xx, t.min_y + i as i32, zz], self.base_ids[b as usize]);
            }
        }
        // SurfaceSystem traverses X then Z. Only earlier neighbours have had
        // badlands/iceberg geometry applied when steepness is evaluated.
        for &(xx, zz) in &positions {
            if (xx, zz) < (x, z) {
                let top = v.height(xx, zz, false);
                let (name, _) = biome([xx, if t.graph.legacy { 0 } else { top }, zz])?;
                if !self.surface.sparse_safe_for_biome(&v.palette, Some(name)) {
                    return Ok(
                        self.surface_columns(x >> 4, z >> 4)?[((x & 15) * 16 + (z & 15)) as usize]
                    );
                }
                self.surface
                    .geometry_column(job, &mut v, xx, zz, &self.colors, &mut biome)?;
                let top = v.height(xx, zz, false);
                if top > t.min_y {
                    let (name, _) = biome([xx, top - 1, zz])?;
                    if !self.surface.sparse_safe_for_biome(&v.palette, Some(name)) {
                        return Ok(self.surface_columns(x >> 4, z >> 4)?
                            [((x & 15) * 16 + (z & 15)) as usize]);
                    }
                }
            }
        }
        self.surface
            .apply_column(job, &mut v, x0, z0, x, z, &self.colors, &mut biome)?;
        let result = self.column_record(&mut v, x, z, &mut scratch, &mut last)?;
        if let Some((bottom, padding)) = truncated {
            if result.values[0] - 7 < bottom + padding {
                // A surface rule can remove solid blocks or turn them into
                // water. Never publish an output whose material crosses the
                // proven retained range; rebuild its complete column instead.
                let mut full = t.job(x0, z0, false)?;
                full.beard = job.beard;
                full.prepare_surface_columns(&[(x, z)]);
                return self.generate_surface_point_depth(x, z, &mut full, false);
            }
        }
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
        let mut volume = Volume::new(origin, [80, t.height as usize, 80], self.palette.clone())?;
        let ice = volume.palette.named("minecraft:ice")?;
        for chunk_x in cx - 2..=cx + 2 {
            for chunk_z in cz - 2..=cz + 2 {
                let columns = self.surface_columns(chunk_x, chunk_z)?;
                for x in 0..16 {
                    for z in 0..16 {
                        let c = columns[(x * 16 + z) as usize].values;
                        let end = c[0].max(c[1]);
                        for y in t.min_y..end {
                            let id = if y >= c[0] {
                                if c[3] & 2 != 0 && y == c[1] - 1 {
                                    ice
                                } else if c[2] == 2 {
                                    self.base_ids[Substance::Lava as usize]
                                } else {
                                    self.base_ids[Substance::Water as usize]
                                }
                            } else if c[3] & (1 << 29) != 0 {
                                volume.palette.air
                            } else if y == c[0] - 1 {
                                c[4] as u32
                            } else if c[0] - 1 - y < 4 {
                                c[5] as u32
                            } else {
                                c[6] as u32
                            };
                            volume.set([chunk_x * 16 + x, y, chunk_z * 16 + z], id);
                        }
                    }
                }
            }
        }
        volume.write_bounds = Some((
            [cx * 16 - 16, t.min_y, cz * 16 - 16],
            [cx * 16 + 32, t.min_y + t.height, cz * 16 + 32],
        ));
        volume.write_budget = 65536;
        Ok(volume)
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
        let before = volume.blocks.clone();
        let published = volume.published.clone();
        let entropy = volume.decoration_entropy.clone();
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
                    volume.blocks = before;
                    volume.published = published;
                    volume.decoration_entropy = entropy;
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
