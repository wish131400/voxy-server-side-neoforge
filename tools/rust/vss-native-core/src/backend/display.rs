//! Display-only surface records. Approximation never enters exact block,
//! chunk caches. Only explicitly visual vegetation may use this proxy ground.
//! Unhandled rules and liquid settings fall back.
use super::*;
const DISPLAY: i32 = (1 << 26) | (1 << 27) | (1 << 28);

#[cfg(test)]
mod admission_probe {
    use super::*;
    #[test]
    #[ignore = "requires VSS_DISPLAY_DOCUMENT; offline benchmark admission diagnosis"]
    fn benchmark_admission() {
        let path = std::env::var("VSS_DISPLAY_DOCUMENT").unwrap();
        let doc = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        let seed = std::env::var("VSS_DISPLAY_SEED").ok().map(|s|s.parse().unwrap()).unwrap_or(0);
        let world = World::new(seed, 0, doc).unwrap();
        let t = &world.terrain;
        let mut scratch = t.graph.raw_scratch().unwrap();
        for tile in 0..16 {
            let (x,z) = (100000 + tile * 2048, 100000);
            let mut q = zoom_quart(world.zoom_seed, [x + 8, t.sea_level + 1, z + 8]);
            q[1] = q[1].clamp(t.min_y >> 2, (t.min_y+t.height-1)>>2);
            let name = world.source.sample(&t.graph,q,&mut scratch,&mut None);
            eprintln!("tile={tile} x={x} z={z} hint={name} summary_safe={} admitted={}",
                world.surface.display_safe_for_biome(&world.palette,Some(name)), world.display_biomes.contains(name));
            if !world.display_biomes.contains(name) {
                let mut job=t.job(x,z,false).unwrap();
                let floor=job.density_surface(x,z).map_or(t.min_y,|y|y+1);
                let q=zoom_quart(world.zoom_seed,[x,floor,z]);
                let actual=world.source.sample(&t.graph,q,&mut scratch,&mut None);
                let row=world.surface_points(&[(x,z)]).unwrap()[0];
                eprintln!("  density_floor={floor} actual={actual} exact={:?}",&row.values[..3]);
            }
        }
    }
}

/// Bounded FIFO for independent display columns. Updating an existing column never
/// consumes another slot; overflow evicts one entry rather than the whole horizon.
pub(super) struct DisplayColumns {
    values: HashMap<(i32, i32), SurfaceColumn>,
    order: std::collections::VecDeque<(i32, i32)>,
    capacity: usize,
    pub(super) evictions: u64,
}
impl DisplayColumns {
    pub(super) fn new(capacity: usize) -> Self {
        Self { values: HashMap::new(), order: std::collections::VecDeque::new(), capacity, evictions: 0 }
    }
    pub(super) fn get(&self, key: &(i32, i32)) -> Option<&SurfaceColumn> { self.values.get(key) }
    pub(super) fn contains_key(&self, key: &(i32, i32)) -> bool { self.values.contains_key(key) }
    pub(super) fn insert(&mut self, key: (i32, i32), value: SurfaceColumn) {
        if let Some(old) = self.values.get_mut(&key) { *old = value; return; }
        if self.capacity == 0 { return; }
        while self.values.len() >= self.capacity {
            if let Some(old) = self.order.pop_front() { self.values.remove(&old); self.evictions += 1; }
        }
        self.order.push_back(key);
        self.values.insert(key, value);
    }
    pub(super) fn clear(&mut self) { self.values.clear(); self.order.clear(); }
}

impl World {
    fn display_hint_eligible(&self, name: &str) -> bool {
        self.display_biomes.contains(name)
            || matches!(name, "minecraft:frozen_ocean" | "minecraft:deep_frozen_ocean")
    }

    fn display_biome_at(&self, name: &str, x: i32, z: i32) -> bool {
        if self.display_biomes.contains(name) { return true; }
        // Frozen ocean is a region, not an iceberg at every column. The same
        // noise gate as Surface::frozen proves that geometry is absent here.
        // Hole is X/Z-only, so unreachable air/water rules stay unreachable at
        // every material depth. Other unknown predicates remain conservative.
        matches!(name, "minecraft:frozen_ocean" | "minecraft:deep_frozen_ocean")
            && self.surface.iceberg_noise(&self.terrain.graph, x, z) <= 1.8
            && self.surface.display_safe_at(&self.palette, name, &self.terrain.graph, x, z)
    }

    pub fn display_points(&self, points: &[(i32, i32)]) -> Result<Vec<SurfaceColumn>> {
        self.check_active()?;
        self.display_queries[0].fetch_add(points.len() as u64, Ordering::Relaxed);
        if points.len() > 64
            || points
                .iter()
                .any(|&(x, z)| x.abs_diff(0) > 29999990 || z.abs_diff(0) > 29999990)
        {
            return Err("display batch outside range".into());
        }
        if self.terrain.graph.requires_complete_column_order() {
            return self.surface_points(points);
        }
        // Warm display grids return immediately, without probing the same
        // perimeter a second time or constructing chunk query groups.
        {
            let cache = self
                .display_columns
                .lock()
                .map_err(|_| "display cache lock")?;
            if let Some(rows) = points
                .iter()
                .map(|p| cache.get(p).copied())
                .collect::<Option<Vec<_>>>()
            {
                self.display_queries[1].fetch_add(points.len() as u64, Ordering::Relaxed);
                return Ok(rows);
            }
        }
        if let Some(grid) = self.adaptive_display_grid(points)? {
            self.display_queries[2].fetch_add(1, Ordering::Relaxed);
            return Ok(grid);
        }
        let generation = self.color_generation.load(Ordering::Acquire);
        let mut result = vec![SurfaceColumn { values: [0; 10] }; points.len()];
        let mut groups = std::collections::BTreeMap::<_, Vec<_>>::new();
        {
            let cache = self
                .display_columns
                .lock()
                .map_err(|_| "display cache lock")?;
            for (i, &(x, z)) in points.iter().enumerate() {
                if let Some(&value) = cache.get(&(x, z)) {
                    self.display_queries[1].fetch_add(1, Ordering::Relaxed);
                    result[i] = value;
                } else {
                    groups.entry((x >> 4, z >> 4)).or_default().push(i);
                }
            }
        }
        // This workspace is used only by biome routing/record colors in Raw mode.
        // Density traversal keeps its separate Job workspace. Reuse one lazy
        // Raw workspace across chunk groups instead of allocating a full graph per group.
        let mut biome_scratch = None;
        for ((cx, cz), indices) in groups {
            self.check_active()?;
            let eligible = !self.terrain.graph.requires_complete_column_order()
                && !self.adjustments.contains_key(&(cx, cz));
            if biome_scratch.is_none() { biome_scratch = Some(self.terrain.graph.raw_scratch()?); }
            let scratch = biome_scratch.as_mut().unwrap();
            // This hint only selects the exact route; it can never authorize
            // an approximation. Avoid per-column speculative work in regions
            // whose surface biome does not support material summaries.
            let t = &self.terrain;
            let mut q = zoom_quart(self.zoom_seed, [cx * 16 + 8, t.sea_level + 1, cz * 16 + 8]);
            q[1] = q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
            let hint = self.source.sample(&t.graph, q, scratch, &mut None);
            if !eligible || !self.display_hint_eligible(hint) {
                self.display_queries[3].fetch_add(indices.len() as u64, Ordering::Relaxed);
                let missing: Vec<_> = indices.iter().map(|&i| points[i]).collect();
                for (i, row) in indices.into_iter().zip(self.surface_points(&missing)?) {
                    result[i] = row;
                }
                continue;
            }
            let mut job = if eligible {
                let mut pool = self.display_work.lock().map_err(|_| "display work lock")?;
                match pool
                    .iter()
                    .position(|(k, _)| *k == (cx, cz))
                    .and_then(|i| pool.remove(i))
                {
                    Some((_, work)) => Job::resume(&self.terrain, work),
                    None => self.terrain.job(cx * 16, cz * 16, false)?,
                }
            } else {
                self.terrain.job(cx * 16, cz * 16, false)?
            };
            job.display = eligible;
            // Whole-cell bounds pay off only when multiple requested columns
            // share a horizontal noise cell. Sparse grids keep column bounds.
            job.reuse_density_cells = indices.windows(2).any(|pair| {
                let (a,b) = (points[pair[0]],points[pair[1]]);
                a.0.div_euclid(t.cell_width) == b.0.div_euclid(t.cell_width)
                    && a.1.div_euclid(t.cell_width) == b.1.div_euclid(t.cell_width)
            });
            job.beard = self.adjustments.get(&(cx, cz));
            self.attach_tops(&mut job);

            for i in indices {
                self.check_active()?;
                let (x, z) = points[i];
                let row = match self.cached_surface_point(x, z)? {
                    Some(row) => row,
                    None => match if eligible {
                        self.display_record(x, z, &mut job, scratch)?
                    } else {
                        None
                    } {
                        Some(mut row) => {
                            row.values[3] |= DISPLAY;
                            row
                        }
                        None => {
                            self.display_queries[3].fetch_add(1, Ordering::Relaxed);
                            // Share the existing job instead of allocating a
                            // second context and redoing the same column walk.
                            let display = job.display;
                            job.display = false;
                            self.prepare_exterior_columns(&mut job, &[(x, z)]);
                            let row = self.generate_surface_point(x, z, &mut job, scratch)?;
                            job.display = display;
                            row
                        }
                    },
                };
                result[i] = row;
            }
            if eligible {
                let work = job.park();
                if work.retained_bytes() <= 2 * 1024 * 1024 {
                    let mut pool = self.display_work.lock().map_err(|_| "display work lock")?;
                    while pool.len() >= 8 {
                        pool.pop_front();
                    }
                    pool.push_back(((cx, cz), work));
                }
            }
        }
        let mut cache = self
            .display_columns
            .lock()
            .map_err(|_| "display cache lock")?;
        if generation == self.color_generation.load(Ordering::Acquire) {
            for (&p, &value) in points.iter().zip(&result) {
                cache.insert(p, value);
            }
        }
        Ok(result)
    }

    fn display_record(
        &self,
        x: i32,
        z: i32,
        job: &mut Job<'_>,
        scratch: &mut crate::density::Scratch,
    ) -> Result<Option<SurfaceColumn>> {
        let t = &self.terrain;
        if job.display {
            return self.display_density_record(x, z, job, scratch);
        }
        let Some((mut y, mut block)) = job.surface_top(x, z) else {
            return Ok(None);
        };
        let top = y + 1;
        let mut top_q = zoom_quart(self.zoom_seed, [x, top, z]);
        top_q[1] = top_q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
        let top_name = self.source.sample(&t.graph, top_q, scratch, &mut None);
        // A conservative route hint: choosing the exact fallback is safe even
        // if the water floor later selects a different biome. Avoid traversing
        // the liquid column twice for swamps, icebergs and geometry rules.
        if !self.display_biomes.contains(top_name) {
            return Ok(None);
        }
        let (mut fluid_y, mut fluid) = (top, 0);
        // Retain the actual aquifer result, including elevated lakes and dry
        // pools. Only scan to the first solid floor, never through the crust.
        while !block.solid() {
            if fluid == 0 && matches!(block, Substance::Water | Substance::Lava) {
                fluid = if block == Substance::Lava { 2 } else { 1 };
                fluid_y = y + 1;
            }
            y -= 1;
            if y < t.min_y {
                return Ok(None);
            }
            block = job.block([x, y, z]);
        }
        let floor = y + 1;
        if fluid == 0 {
            fluid_y = floor;
        }
        let mut q = zoom_quart(self.zoom_seed, [x, floor, z]);
        q[1] = q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
        let name = if q == top_q {
            top_name
        } else {
            self.source.sample(&t.graph, q, scratch, &mut None)
        };
        if !self.display_biomes.contains(name) {
            return Ok(None);
        }
        let biome = self.biomes.get(name).ok_or("missing display biome")?;
        // Summarize the three visible material depths instead of filling a
        // column and locating four independent neighbour tops. Steep rules
        // retain a local density slope test, with the same original graph.
        let steep = job.display_steep(x, y, z);
        let materials = self.surface.display_materials(
            t,
            x,
            z,
            floor,
            if fluid == 0 { i32::MIN } else { fluid_y },
            name,
            biome,
            &self.colors,
            steep,
        );
        if materials
            .iter()
            .any(|&id| self.palette.is_air(id) || self.palette.fluid(id))
        {
            return Ok(None);
        }
        Ok(Some(self.record_surface(
            x, z, floor, fluid_y, fluid, materials, scratch, &mut None,
        )?))
    }

    /// Visual envelope: preserve the graph's Cell interpolation and highest
    /// density boundary, but omit aquifer pressure, ore and vertical liquid
    /// traversal. For ordinary fluid routers, display the configured sea plane.
    /// This intentionally differs from final block generation (dry depressions
    /// and perched aquifers), and must never populate the exact point cache.
    /// Custom fluid routers validate the exposed fluid band and fall back on
    /// disagreement. This local check cannot detect detached lakes far above
    /// the band. Stateful graphs, structures and geometry-changing surface
    /// rules retain the existing path.
    fn display_density_record(
        &self, x: i32, z: i32, job: &mut Job<'_>,
        scratch: &mut crate::density::Scratch,
    ) -> Result<Option<SurfaceColumn>> {
        let t = &self.terrain;
        let floor = job.density_surface(x, z).map_or(t.min_y, |y| y + 1);
        let (fluid_y, fluid) = t.preview_fluid(floor);
        if floor != t.min_y && !self.display_liquids_supported()
            && !job.display_fluid_band_matches(x, z, floor, fluid_y, fluid) {
            return Ok(None);
        }
        let mut q = zoom_quart(self.zoom_seed, [x, floor, z]);
        q[1] = q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
        let name = self.source.sample(&t.graph, q, scratch, &mut None);
        if !self.display_biome_at(name, x, z) { return Ok(None); }
        // Check the visible liquid biome too: an underwater plains floor must
        // not bypass the iceberg/special-surface fallback above it.
        if fluid != 0 {
            let mut water_q = zoom_quart(self.zoom_seed, [x, fluid_y - 1, z]);
            water_q[1] = water_q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
            if water_q != q && !self.display_biome_at(
                self.source.sample(&t.graph, water_q, scratch, &mut None), x, z) {
                return Ok(None);
            }
        }
        let biome = self.biomes.get(name).ok_or("missing display biome")?;
        let materials = if floor == t.min_y {
            [self.palette.air; 3]
        } else {
            self.surface.display_materials(t, x, z, floor,
                if fluid == 0 { i32::MIN } else { fluid_y }, name, biome,
                &self.colors, job.display_steep(x, floor - 1, z))
        };
        if floor != t.min_y && materials.iter().any(|&id| self.palette.is_air(id) || self.palette.fluid(id)) {
            return Ok(None);
        }
        let row = self.record_surface(x, z, floor, fluid_y, fluid, materials, scratch, &mut None)?;
        self.display_queries[4].fetch_add(1, Ordering::Relaxed);
        Ok(Some(row))
    }

    pub fn display_query_stats(&self) -> [u64; 6] {
        let evictions = self.display_columns.lock().map(|c| c.evictions).unwrap_or(0);
        [self.display_queries[0].load(Ordering::Relaxed), self.display_queries[1].load(Ordering::Relaxed),
         self.display_queries[2].load(Ordering::Relaxed), self.display_queries[3].load(Ordering::Relaxed), evictions,
         self.display_queries[4].load(Ordering::Relaxed)]
    }

    fn adaptive_display_grid(&self, points: &[(i32, i32)]) -> Result<Option<Vec<SurfaceColumn>>> {
        if points.len() != 64
            || points
                .iter()
                .any(|&(x, z)| self.adjustments.contains_key(&(x >> 4, z >> 4)))
        {
            return Ok(None);
        }
        let (x, z) = points[0];
        let step = points[1].0 - x;
        if !(1..=2).contains(&step)
            || !points
                .iter()
                .enumerate()
                .all(|(i, &p)| p == (x + (i % 8) as i32 * step, z + (i / 8) as i32 * step))
        {
            return Ok(None);
        }
        // Borders are exact display queries so adjacent batches always share
        // the same seam. Four centre probes reject curvature/material changes.
        let indices: Vec<_> = (0..64)
            .filter(|i| {
                let x = i % 8;
                let z = i / 8;
                x == 0 || x == 7 || z == 0 || z == 7 || ((x == 3 || x == 4) && (z == 3 || z == 4))
            })
            .collect();
        let probes: Vec<_> = indices.iter().map(|&i| points[i]).collect();
        let values = self.display_points(&probes)?;
        let first = values[0].values;
        if first[3] & (1 << 29) != 0
            || first[3] & (1 << 26) == 0
            || values
                .iter()
                .any(|r| r.values[2..] != first[2..] || first[2] != 0 && r.values[1] != first[1])
        {
            return Ok(None);
        }
        let mut result = vec![SurfaceColumn { values: first }; 64];
        for (&i, &v) in indices.iter().zip(&values) {
            result[i] = v;
        }
        let heights = [
            result[0].values[0],
            result[7].values[0],
            result[56].values[0],
            result[63].values[0],
        ];
        let height = |i: usize| {
            let x = (i % 8) as f64 / 7.;
            let z = (i / 8) as f64 / 7.;
            heights[0] as f64 * (1. - x) * (1. - z)
                + heights[1] as f64 * x * (1. - z)
                + heights[2] as f64 * (1. - x) * z
                + heights[3] as f64 * x * z
        };
        if indices
            .iter()
            .any(|&i| (result[i].values[0] as f64 - height(i)).abs() > 0.5)
        {
            return Ok(None);
        }
        for i in 0..64 {
            if !indices.contains(&i) {
                let y = height(i).round() as i32;
                result[i].values[0] = y;
                result[i].values[1] = if first[2] == 0 { y } else { first[1] };
            }
        }
        // Interpolated interiors are not placed in the per-point cache: other
        // grid alignments must obtain their own shared border observations.
        Ok(Some(result))
    }

    fn display_liquids_supported(&self) -> bool {
        *self
            .display_liquids
            .get_or_init(|| self.check_display_liquids())
    }
    fn check_display_liquids(&self) -> bool {
        let s = &self.document["settings"];
        if s["aquifers_enabled"].as_bool() == Some(false) {
            return true;
        }
        // Only the vanilla fluid router qualifies for the dry-land shortcut.
        // Resolve references, so an overriding datapack cannot hide custom
        // floodedness behind a vanilla identifier.
        fn resolve<'a>(doc: &'a Value, mut v: &'a Value) -> Option<&'a Value> {
            for _ in 0..32 {
                match v.as_str() {
                    Some(name) => v = doc["density_functions"].get(name)?,
                    None => return Some(v),
                }
            }
            None
        }
        for (key, noise, scale, octave) in [
            ("barrier", "minecraft:aquifer_barrier", 0.5, -3),
            (
                "fluid_level_floodedness",
                "minecraft:aquifer_fluid_level_floodedness",
                0.67,
                -7,
            ),
            (
                "fluid_level_spread",
                "minecraft:aquifer_fluid_level_spread",
                0.7142857142857143,
                -5,
            ),
            ("lava", "minecraft:aquifer_lava", 1., -1),
        ] {
            let Some(v) = resolve(&self.document, &s["noise_router"][key]) else {
                return false;
            };
            if v["type"] != "minecraft:noise"
                || v["noise"] != noise
                || v["xz_scale"].as_f64() != Some(1.)
                || v["y_scale"].as_f64() != Some(scale)
            {
                return false;
            }
            let definition = &self.document["noises"][noise];
            if definition["firstOctave"].as_i64() != Some(octave)
                || definition["amplitudes"] != json!([1.])
            {
                return false;
            }
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn display_cache_overflow_preserves_other_columns_and_updates_do_not_duplicate_fifo() {
        let mut cache = DisplayColumns::new(65536);
        let value = SurfaceColumn { values: [7; 10] };
        for x in 0..65536 { cache.insert((x, -x), value); }
        for _ in 0..100 { cache.insert((10, -10), SurfaceColumn { values: [9; 10] }); }
        assert_eq!(cache.order.len(), 65536);
        cache.insert((65536, -65536), value);
        assert!(cache.get(&(0, 0)).is_none());
        assert_eq!(cache.values.len(), 65536);
        assert_eq!(cache.evictions, 1);
        assert_eq!(cache.get(&(10, -10)).unwrap().values, [9; 10]);
        assert!((1..65536).all(|x| cache.contains_key(&(x, -x))));
        cache.clear();
        assert!(cache.values.is_empty() && cache.order.is_empty());
        cache.insert((-1, 1), value);
        assert_eq!(cache.get(&(-1, 1)).unwrap().values, [7; 10]);
    }

    #[test]
    fn raw_biome_workspace_matches_full_workspace_for_display_and_fallback_records() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
        let read = |f: &str| serde_json::from_str::<Value>(&std::fs::read_to_string(root.join(f)).unwrap()).unwrap();
        let mut doc = read("overworld.json");
        for (k, f) in [("block_definitions","blocks.json"),("biomes","biomes.json"),
                       ("grass_colormap","grass.json"),("foliage_colormap","foliage.json")] { doc[k] = read(f); }
        for seed in [0, 731] {
            let w = World::new(seed, seed, doc.clone()).unwrap();
            let t = &w.terrain;
            let mut raw = t.graph.raw_scratch().unwrap();
            for (x,z) in [(-17,31),(0,0),(1537,-2049),(100_000,-100_000)] {
                let mut full = t.graph.scratch(x & !15,z & !15,t.cell_width,t.cell_height).unwrap();
                let mut a = t.job(x & !15,z & !15,false).unwrap();
                let mut b = t.job(x & !15,z & !15,false).unwrap();
                a.display = w.display_liquids_supported(); b.display = a.display;
                let expected = w.display_record(x,z,&mut a,&mut full).unwrap();
                let actual = w.display_record(x,z,&mut b,&mut raw).unwrap();
                assert_eq!(actual.map(|v|v.values),expected.map(|v|v.values),"seed={seed},point={x},{z}");
                // The exact exterior fallback uses this same workspace only for biomes.
                a.display=false; b.display=false;
                let expected=w.exterior_record(x,z,&mut a,&mut full).unwrap();
                let actual=w.exterior_record(x,z,&mut b,&mut raw).unwrap();
                assert_eq!(actual.map(|v|v.values),expected.map(|v|v.values));
            }
        }
    }

    #[test]
    fn vanilla_fluid_router_enables_display_sampling() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
        let read = |f: &str| {
            serde_json::from_str::<Value>(&std::fs::read_to_string(root.join(f)).unwrap()).unwrap()
        };
        let mut d = read("overworld.json");
        for (k, f) in [
            ("block_definitions", "blocks.json"),
            ("biomes", "biomes.json"),
            ("grass_colormap", "grass.json"),
            ("foliage_colormap", "foliage.json"),
        ] {
            d[k] = read(f);
        }
        let w = World::new(0, 0, d).unwrap();
        assert!(w.display_liquids_supported());
        assert!(w
            .surface
            .display_safe_for_biome(&w.palette, Some("minecraft:plains")));
        let mut job = w.terrain.job(100000, 100000, false).unwrap();
        assert!(!w.terrain.graph.requires_complete_column_order());
        let h = job.preliminary(100000, 100000);
        let mut scratch = w
            .terrain
            .graph
            .scratch(100000, 100000, w.terrain.cell_width, w.terrain.cell_height)
            .unwrap();
        job.display = true;
        let row = w
            .exterior_record(100000, 100000, &mut job, &mut scratch)
            .unwrap();
        assert!(
            row.is_some(),
            "ordinary surface rules must support display records, preliminary={h}"
        );
    }
}
