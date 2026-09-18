//! Vanilla NoiseBasedChunkGenerator base columns, Aquifer and OreVeinifier.
//! No structure starts, carvers or decoration are implied by a base column.
use crate::{
    density::{clamped_lerp, integer, string, Graph, Id, Mode, Result, Scratch},
    random::Positional,
};
use serde_json::Value;
// Job-local coordinates/IDs are lookup keys, never an evaluation order.
use rustc_hash::{FxHashMap as HashMap, FxHashSet};

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
#[repr(u8)]
pub enum Substance {
    Air,
    Default,
    Water,
    Lava,
    CopperOre,
    RawCopper,
    Granite,
    IronOre,
    RawIron,
    Tuff,
}
impl Substance {
    pub fn block_name(self, default: &str) -> &str {
        match self {
            Self::Air => "minecraft:air",
            Self::Default => default,
            Self::Water => "minecraft:water",
            Self::Lava => "minecraft:lava",
            Self::CopperOre => "minecraft:copper_ore",
            Self::RawCopper => "minecraft:raw_copper_block",
            Self::Granite => "minecraft:granite",
            Self::IronOre => "minecraft:deepslate_iron_ore",
            Self::RawIron => "minecraft:raw_iron_block",
            Self::Tuff => "minecraft:tuff",
        }
    }
    pub fn fluid(self) -> bool {
        matches!(self, Self::Water | Self::Lava)
    }
    pub fn solid(self) -> bool {
        self != Self::Air && !self.fluid()
    }
}
#[derive(Clone, Copy)]
struct Fluid {
    level: i32,
    kind: Substance,
}
impl Fluid {
    fn at(self, y: i32) -> Substance {
        if y < self.level {
            self.kind
        } else {
            Substance::Air
        }
    }
}
pub struct Terrain {
    pub graph: Graph,
    pub min_y: i32,
    pub height: i32,
    pub sea_level: i32,
    pub cell_width: i32,
    pub cell_height: i32,
    pub default_block: String,
    fluid: Substance,
    aquifers: bool,
    ores: bool,
    final_density: Id,
    initial: Id,
    barrier: Id,
    floodedness: Id,
    spread: Id,
    lava: Id,
    erosion: Id,
    depth: Id,
    vein_toggle: Id,
    vein_ridged: Id,
    vein_gap: Id,
    aquifer_random: Positional,
    ore_random: Positional,
}
#[derive(Clone)]
pub struct Column {
    pub min_y: i32,
    pub blocks: Vec<Substance>,
    pub surface_height: i32,
    pub ocean_floor: i32,
    pub fluid_height: Option<i32>,
}
pub struct Job<'a> {
    pub terrain: &'a Terrain,
    pub scratch: Scratch,
    pub beard: Option<&'a crate::beard::Beard>,
    pub(crate) display: bool,
    pub(crate) reuse_density_cells: bool,
    density_cells: HashMap<[i32; 3], bool>,
    density_ceiling: HashMap<(i32, i32), i32>,
    density_height: Option<((i32, i32), Option<i32>)>,
    display_tops: HashMap<(i32, i32), Option<(i32, Substance)>>,
    levels: HashMap<(i32, i32), i32>,
    locations: HashMap<[i32; 3], [i32; 3]>,
    fluids: HashMap<[i32; 3], Fluid>,
    proven_air_cells: FxHashSet<[i32; 3]>,
    surface_columns: HashMap<(i32, i32), Column>,
    surface_bottoms: HashMap<(i32, i32), (i32, i32)>,
    surface_tops: HashMap<(i32, i32), Option<(i32, Substance)>>,
    /// Results of `surface_top`, keyed the same way. Kept separate from
    /// `surface_tops` on purpose: that map uses `None` as a "not found yet"
    /// sentinel while `prepare_surface_columns_inner` is filling it, so writing
    /// real tops into it would make that pass stop early and change its output.
    neighbour_tops: HashMap<(i32, i32), Option<(i32, Substance)>>,
    /// Optional cache shared across columns, owned by `World`.
    ///
    /// `neighbour_tops` alone cannot serve a sparse query: `Job` is built per
    /// column, and the four neighbour positions inside one column are all
    /// distinct, so the map never sees a repeat. It only pays off when one
    /// `Job` handles many columns. A `World`-owned map survives across calls,
    /// which is what makes neighbouring grid samples reuse each other's scans.
    ///
    /// A top is a pure function of `(x, z)` given the world's fixed
    /// `adjustments`, so sharing it across columns cannot change any result.
    pub shared_tops: Option<&'a std::sync::Mutex<std::collections::HashMap<(i32, i32), Option<(i32, Substance)>>>>,
}
/// Owned query context, parked only within its immutable World and chunk.
/// No borrowed terrain or structure pointers cross a call boundary.
pub(crate) struct SurfaceWork {
    scratch: Scratch,
    density_cells: HashMap<[i32; 3], bool>,
    density_ceiling: HashMap<(i32, i32), i32>,
    density_height: Option<((i32, i32), Option<i32>)>,
    display_tops: HashMap<(i32, i32), Option<(i32, Substance)>>,
    levels: HashMap<(i32, i32), i32>,
    locations: HashMap<[i32; 3], [i32; 3]>,
    fluids: HashMap<[i32; 3], Fluid>,
    proven_air_cells: FxHashSet<[i32; 3]>,
    tops: HashMap<(i32, i32), Option<(i32, Substance)>>,
}
impl SurfaceWork {
    pub(crate) fn retained_bytes(&self) -> usize {
        self.scratch.retained_bytes()
            + self.density_cells.capacity()*64
            + self.density_ceiling.capacity()*64
            + (self.levels.capacity()+self.locations.capacity()+self.fluids.capacity()+self.tops.capacity()+self.proven_air_cells.capacity()+self.display_tops.capacity())*64
    }
}
const WAY_BELOW_MIN_Y: i32 = -32512;
impl Terrain {
    /// Preview-only exterior search. Raw density bypasses interpolation arrays,
    /// aquifers, ores and structure blending; exact columns never call this.
    pub fn preview_height(&self, x: i32, z: i32, scratch: &mut Scratch) -> i32 {
        let top = self.min_y + self.height - 1;
        let mut air = top + 1;
        let mut y = top;
        loop {
            if self.graph.compute(self.final_density, [x, y, z], Mode::Raw, scratch) > 0. {
                let mut solid = y;
                while air - solid > 4 {
                    let middle = solid + (air - solid) / 2;
                    if self.graph.compute(self.final_density, [x, middle, z], Mode::Raw, scratch) > 0. {
                        solid = middle;
                    } else {
                        air = middle;
                    }
                }
                return solid + 1;
            }
            if y == self.min_y { return self.min_y; }
            air = y;
            y = (y - 16).max(self.min_y);
        }
    }

    pub fn preview_fluid(&self, floor: i32) -> (i32, i32) {
        // Empty End columns must stay empty. This deliberately omits aquifers.
        if floor == self.min_y || floor >= self.sea_level { return (floor, 0); }
        match self.fluid {
            Substance::Water => (self.sea_level, 1),
            Substance::Lava => (self.sea_level, 2),
            _ => (floor, 0),
        }
    }

    pub fn from_document(seed: i64, doc: &Value) -> Result<Self> {
        let graph = Graph::from_document(seed, doc)?;
        let s = &doc["settings"];
        let n = &s["noise"];
        let min_y = integer(n, "min_y")?;
        let height = integer(n, "height")?;
        let cell_width = integer(n, "size_horizontal")?
            .checked_mul(4)
            .ok_or("cell width overflow")?;
        let cell_height = integer(n, "size_vertical")?
            .checked_mul(4)
            .ok_or("cell height overflow")?;
        if !(4..=64).contains(&cell_width)
            || !(4..=64).contains(&cell_height)
            || height <= 0
            || height > 4096
            || min_y < -4096
            || min_y + height > 4096
            || height % cell_height != 0
            || min_y % cell_height != 0
        {
            return Err("unsupported noise dimensions".into());
        }
        let fluid = match string(&s["default_fluid"], "Name")? {
            "minecraft:water" => Substance::Water,
            "minecraft:lava" => Substance::Lava,
            "minecraft:air" => Substance::Air,
            n => return Err(format!("unsupported default fluid {n}")),
        };
        Ok(Self {
            min_y,
            height,
            cell_width,
            cell_height,
            sea_level: integer(s, "sea_level")?,
            default_block: string(&s["default_block"], "Name")?.into(),
            fluid,
            aquifers: s["aquifers_enabled"]
                .as_bool()
                .ok_or("missing aquifer flag")?,
            ores: s["ore_veins_enabled"].as_bool().ok_or("missing ore flag")?,
            final_density: graph.root("final_density")?,
            initial: graph.root("initial_density_without_jaggedness")?,
            barrier: graph.root("barrier")?,
            floodedness: graph.root("fluid_level_floodedness")?,
            spread: graph.root("fluid_level_spread")?,
            lava: graph.root("lava")?,
            erosion: graph.root("erosion")?,
            depth: graph.root("depth")?,
            vein_toggle: graph.root("vein_toggle")?,
            vein_ridged: graph.root("vein_ridged")?,
            vein_gap: graph.root("vein_gap")?,
            aquifer_random: graph.random.from_hash("minecraft:aquifer").positional(),
            ore_random: graph.random.from_hash("minecraft:ore").positional(),
            graph,
        })
    }
    pub fn job(&self, x: i32, z: i32, base_column: bool) -> Result<Job<'_>> {
        if !(-30_000_000..=30_000_000).contains(&x) || !(-30_000_000..=30_000_000).contains(&z) {
            return Err("outside supported world coordinates".into());
        }
        let width = if base_column { self.cell_width } else { 16 };
        let x = x.div_euclid(width) * width;
        let z = z.div_euclid(width) * width;
        let mut scratch = self
            .graph
            .scratch(x & !3, z & !3, self.cell_width, self.cell_height)?;
        scratch.span = width & !3;
        // Exposed stateful caches retain the original initialization order.
        // Caches sheltered by Flat are filled lazily at its fixed height.
        self.graph.initialize_column_caches(&mut scratch);
        Ok(Job {
            terrain: self,
            scratch,
            beard: None,
            display: false,
            reuse_density_cells: false,
            density_cells: HashMap::default(),
            density_ceiling: HashMap::default(),
            density_height: None,
            display_tops: HashMap::default(),
            levels: HashMap::default(),
            locations: HashMap::default(),
            fluids: HashMap::default(),
            proven_air_cells: FxHashSet::default(),
            surface_columns: HashMap::default(),
            surface_bottoms: HashMap::default(),
            surface_tops: HashMap::default(),
            neighbour_tops: HashMap::default(),
            shared_tops: None,
        })
    }
    pub fn base_column(&self, x: i32, z: i32) -> Result<Column> {
        self.job(x, z, true)?.column(x, z)
    }
    fn global(&self, y: i32) -> Fluid {
        if y < (-54).min(self.sea_level) {
            Fluid {
                level: -54,
                kind: Substance::Lava,
            }
        } else {
            Fluid {
                level: self.sea_level,
                kind: self.fluid,
            }
        }
    }
}
impl Job<'_> {
    /// Batch-local sparse display reuse. Exact and order-sensitive routes keep
    /// their normal jobs. Never carry a sampled height/proof into another chunk.
    pub(crate) fn reset_display_chunk(&mut self, x: i32, z: i32) -> Result<()> {
        if !(-30_000_000..=30_000_000).contains(&x) || !(-30_000_000..=30_000_000).contains(&z) {
            return Err("outside supported world coordinates".into());
        }
        self.scratch.reset_chunk(x & !15, z & !15);
        self.terrain.graph.initialize_column_caches(&mut self.scratch);
        self.beard = None;
        self.display = false;
        self.reuse_density_cells = false;
        self.density_cells.clear();
        self.density_ceiling.clear();
        self.density_height = None;
        self.display_tops.clear();
        self.levels.clear();
        self.locations.clear();
        self.fluids.clear();
        self.proven_air_cells.clear();
        self.surface_columns.clear();
        self.surface_bottoms.clear();
        self.surface_tops.clear();
        self.neighbour_tops.clear();
        self.shared_tops = None;
        Ok(())
    }

    pub(crate) fn park(self) -> SurfaceWork {
        SurfaceWork { scratch: self.scratch, density_cells: self.density_cells, density_ceiling: self.density_ceiling,
            density_height: self.density_height,
            display_tops: self.display_tops, levels: self.levels,
            locations: self.locations, fluids: self.fluids, tops: self.neighbour_tops,
            proven_air_cells: self.proven_air_cells }
    }

    /// Sparse surface requests share NoiseChunk cells too. Visit each needed
    /// cell once, retaining complete centre columns and only the first block
    /// of steepness neighbours. This is also the full-depth fallback.
    pub fn prepare_surface_columns(&mut self, centres: &[(i32, i32)]) {
        self.prepare_surface_columns_inner(centres, |_, _, _| None, false);
    }

    pub fn surface_bottom(&self, x: i32, z: i32) -> Option<(i32, i32)> {
        self.surface_bottoms.get(&(x, z)).copied()
    }

    pub fn prepare_surface_columns_with_depth(
        &mut self,
        centres: &[(i32, i32)],
        padding: impl FnMut(i32, i32, i32) -> Option<i32>,
    ) {
        self.prepare_surface_columns_inner(centres, padding, true);
    }

    fn prepare_surface_columns_inner(
        &mut self,
        centres: &[(i32, i32)],
        mut padding: impl FnMut(i32, i32, i32) -> Option<i32>,
        exterior: bool,
    ) {
        let t = self.terrain;
        if !t.graph.requires_complete_column_order() || centres.is_empty() {
            return;
        }
        self.surface_columns.clear();
        self.surface_bottoms.clear();
        self.surface_tops.clear();
        let mut needed = std::collections::BTreeMap::new();
        for &(x, z) in centres {
            let x0 = x & !15;
            let z0 = z & !15;
            for p in [
                (x, z),
                (x, (z - 1).max(z0)),
                (x, (z + 1).min(z0 + 15)),
                ((x - 1).max(x0), z),
                ((x + 1).min(x0 + 15), z),
            ] {
                needed.entry(p).or_insert(false);
            }
            needed.insert((x, z), true);
        }
        if exterior {
            t.graph.plan_surface_slices(
                &mut self.scratch,
                &needed.keys().copied().collect::<Vec<_>>(),
            );
        } else {
            self.scratch.clear_surface_slices();
        }
        let mut cells = std::collections::BTreeMap::<_, Vec<_>>::new();
        for ((x, z), full) in needed {
            cells
                .entry((x.div_euclid(t.cell_width), z.div_euclid(t.cell_width)))
                .or_default()
                .push((x, z, full));
            self.surface_tops.insert((x, z), None);
            if full {
                self.surface_columns.insert(
                    (x, z),
                    Column {
                        min_y: t.min_y,
                        blocks: vec![Substance::Air; t.height as usize],
                        surface_height: t.min_y,
                        ocean_floor: t.min_y,
                        fluid_height: None,
                    },
                );
            }
        }
        for columns in cells.values() {
            for cy in (t.min_y..t.min_y + t.height)
                .step_by(t.cell_height as usize)
                .rev()
            {
                if columns.iter().all(|&(x, z, full)| {
                    (!full && self.surface_tops[&(x, z)].is_some())
                        || self
                            .surface_bottoms
                            .get(&(x, z))
                            .is_some_and(|&(bottom, _)| cy + t.cell_height <= bottom)
                }) {
                    break;
                }
                for y in (cy..cy + t.cell_height).rev() {
                    for &(x, z, full) in columns {
                        if !full && self.surface_tops[&(x, z)].is_some() {
                            continue;
                        }
                        if self
                            .surface_bottoms
                            .get(&(x, z))
                            .is_some_and(|&(bottom, _)| y < bottom)
                        {
                            continue;
                        }
                        let block = self.block([x, y, z]);
                        if block != Substance::Air && self.surface_tops[&(x, z)].is_none() {
                            self.surface_tops.insert((x, z), Some((y, block)));
                        }
                        if let Some(column) = self.surface_columns.get_mut(&(x, z)) {
                            if block.solid() && column.ocean_floor == t.min_y {
                                let top = self.surface_tops[&(x, z)].map_or(y + 1, |(y, _)| y + 1);
                                if let Some(extra) = padding(x, top, z) {
                                    let bottom = (y - 7 - extra).max(t.min_y);
                                    if bottom > t.min_y {
                                        self.surface_bottoms.insert((x, z), (bottom, extra));
                                    }
                                }
                            }
                            column.blocks[(y - t.min_y) as usize] = block;
                            if block != Substance::Air {
                                column.surface_height = column.surface_height.max(y + 1);
                            }
                            if block.solid() {
                                column.ocean_floor = column.ocean_floor.max(y + 1);
                            }
                            if block.fluid() && column.fluid_height.is_none() {
                                column.fluid_height = Some(y + 1);
                            }
                        }
                    }
                }
            }
        }
    }

    /// Visit a chunk in NoiseChunk's cell order. A column-at-a-time reader
    /// revisits each 4x4 horizontal cell sixteen times on stateful graphs.
    pub fn fill_chunk(
        &mut self,
        x0: i32,
        z0: i32,
        mut write: impl FnMut(i32, i32, i32, Substance),
    ) {
        let t = self.terrain;
        if t.graph.requires_complete_column_order() {
            let first_x = x0.div_euclid(t.cell_width) * t.cell_width;
            let first_z = z0.div_euclid(t.cell_width) * t.cell_width;
            for cx in (first_x..x0 + 16).step_by(t.cell_width as usize) {
                for cz in (first_z..z0 + 16).step_by(t.cell_width as usize) {
                    for cy in (t.min_y..t.min_y + t.height)
                        .step_by(t.cell_height as usize)
                        .rev()
                    {
                        for y in (cy..cy + t.cell_height).rev() {
                            for x in cx.max(x0)..(cx + t.cell_width).min(x0 + 16) {
                                for z in cz.max(z0)..(cz + t.cell_width).min(z0 + 16) {
                                    write(x, y, z, self.block([x, y, z]));
                                }
                            }
                        }
                    }
                }
            }
        } else {
            for x in x0..x0 + 16 {
                for z in z0..z0 + 16 {
                    for y in (t.min_y..t.min_y + t.height).rev() {
                        write(x, y, z, self.block([x, y, z]));
                    }
                }
            }
        }
    }

    fn compute(&mut self, id: Id, p: [i32; 3], mode: Mode) -> f64 {
        self.terrain.graph.compute(id, p, mode, &mut self.scratch)
    }
    /// Benchmark/diagnostic hook: the initial-density node the preliminary
    /// height march evaluates.  Read-only, no behaviour change.
    pub fn initial_density_id(&self) -> Id {
        self.terrain.initial
    }

    /// Diagnostic only, never called by generation. A coarse top-down march
    /// using the exact `Job::block` predicate that `surface_top` uses, stepping
    /// 16 blocks and then scanning the stride it landed in.
    ///
    /// The probe predicate and the target predicate have to be the same one.
    /// `Terrain::preview_height` fails as a pre-scan not because it is coarse
    /// but because it asks `final_density > 0`, while `surface_top` asks
    /// whether `substance` reports air - and the aquifer and global fluid layer
    /// make those two disagree by tens of blocks over deep water.
    ///
    /// Bisection is deliberately not used: `block != Air` is not monotone in y,
    /// because an isolated thin layer (snow, a water surface, a structure
    /// remnant) can sit alone between air on both sides. Scanning the landed
    /// stride costs up to 15 extra block calls and makes the answer exact.
    ///
    /// Returns floor semantics, matching `Job::surface_top`.
    pub fn probe_height_by_block(&mut self, x: i32, z: i32) -> i32 {
        let t = self.terrain;
        let top = t.min_y + t.height - 1;
        let mut y = top;
        loop {
            if self.block([x, y, z]) != Substance::Air {
                // Everything above `y` was sampled and was air, so the answer
                // is the highest non-air in (y, y + 15].
                let mut probe = (y + 15).min(top);
                while probe > y {
                    if self.block([x, probe, z]) != Substance::Air {
                        return probe + 1;
                    }
                    probe -= 1;
                }
                return y + 1;
            }
            if y == t.min_y {
                return t.min_y;
            }
            y = (y - 16).max(t.min_y);
        }
    }

    pub fn preliminary(&mut self, x: i32, z: i32) -> i32 {
        crate::prof::hit(&crate::prof::SURFACE.preliminary_calls);
        let key = (x & !3, z & !3);
        if let Some(&h) = self.levels.get(&key) {
            return h;
        }
        let mut h = i32::MAX;
        let t = self.terrain;
        if !t.graph.has_stateful_queries() || t.graph.has_initial_height_plan() {
            h=t.graph.first_above(t.initial,key.0,key.1,t.min_y,t.min_y+t.height,t.cell_height,0.390625,&mut self.scratch).unwrap_or(i32::MAX);
            if self.levels.len()<16384 {self.levels.insert(key,h);}
            return h;
        }
        let mut y = t.min_y + t.height;
        while y >= t.min_y {
            if self.compute(t.initial, [key.0, y, key.1], Mode::Single) > 0.390625 {
                h = y;
                break;
            }
            y -= t.cell_height;
        }
        if self.levels.len() < 16384 {
            self.levels.insert(key, h);
        }
        h
    }
    pub fn column(&mut self, x: i32, z: i32) -> Result<Column> {
        if let Some(column) = self.surface_columns.get(&(x, z)) {
            return Ok(column.clone());
        }
        let t = self.terrain;
        let mut blocks = vec![Substance::Air; t.height as usize];
        let mut surface = t.min_y;
        let mut floor = t.min_y;
        let mut fluid = None;
        for y in (t.min_y..t.min_y + t.height).rev() {
            let block = self.block([x, y, z]);
            blocks[(y - t.min_y) as usize] = block;
            if block != Substance::Air {
                surface = surface.max(y + 1);
            }
            if block.solid() {
                floor = floor.max(y + 1);
            }
            if block.fluid() && fluid.is_none() {
                fluid = Some(y + 1);
            }
        }
        Ok(Column {
            min_y: t.min_y,
            blocks,
            surface_height: surface,
            ocean_floor: floor,
            fluid_height: fluid,
        })
    }
    /// WORLD_SURFACE_WG only reads the first non-air block. Neighbour
    /// steepness must not pay for ores, aquifers and caves below that block.
    pub fn surface_top(&mut self, x: i32, z: i32) -> Option<(i32, Substance)> {
        if self.display {
            if let Some(top) = self.display_tops.get(&(x,z)) { return *top; }
            let top = self.display_top(x,z);
            self.display_tops.insert((x,z),top);
            return top;
        }
        if let Some(top) = self.neighbour_tops.get(&(x, z)) {
            crate::prof::hit(&crate::prof::SURFACE.top_reuses);
            return *top;
        }
        if let Some(shared) = self.shared_tops {
            if let Ok(cache) = shared.lock() {
                if let Some(&top) = cache.get(&(x, z)) {
                    crate::prof::hit(&crate::prof::SURFACE.top_reuses);
                    self.neighbour_tops.insert((x, z), top);
                    return top;
                }
            }
        }
        crate::prof::hit(&crate::prof::SURFACE.top_scans);
        let t = self.terrain;
        let mut found = None;
        let mut y = t.min_y + t.height - 1;
        while y >= t.min_y {
            if let Some(bottom) = self.proven_air_bottom([x, y, z]) {
                y = bottom - 1;
                continue;
            }
            let block = self.block([x, y, z]);
            if block != Substance::Air {
                found = Some((y, block));
                break;
            }
            y -= 1;
        }
        if let Some(shared) = self.shared_tops {
            if let Ok(mut cache) = shared.lock() {
                // Matches the exact-point cache budget: 65536 entries of
                // `(i32, i32) -> Option<(i32, Substance)>` is a couple of MiB.
                // Eviction is not needed for correctness - a top is a pure
                // function of position - so a full cache simply stops growing.
                if cache.len() < 1 << 16 {
                    cache.insert((x, z), found);
                }
            }
        }
        self.neighbour_tops.insert((x, z), found);
        found
    }
    fn display_top(&mut self, x:i32,z:i32) -> Option<(i32,Substance)> {
        let t=self.terrain;
        // This is a route hint only, never a bound used to discard geometry.
        // Likely coasts skip the speculative density walk and use exact fluid
        // semantics immediately; otherwise they would pay for both traversals.
        let likely_land=self.preliminary(x,z)>t.sea_level+24;
        if let Some(y)=if likely_land {self.density_surface(x,z)} else {None} {
            // Keep shorelines and low/underwater terrain on the complete fluid
            // query. Check the surface band for wet aquifer boundaries too.
            if y >= t.sea_level+t.cell_height {
                let mut dry=true;
                for yy in y+1..=(y+t.cell_height).min(t.min_y+t.height-1) {
                    if self.block([x,yy,z])!=Substance::Air {dry=false;break;}
                }
                if dry {return Some((y,self.block([x,y,z])));}
            }
        }
        self.display=false;
        let result=self.surface_top(x,z);
        self.display=true;
        result
    }
    /// Validate the proposed visible liquid band with the world's actual
    /// aquifer. Avoid traversing the entire sky or the whole water column.
    /// Detached fluids outside the checked band remain a visual approximation.
    pub(crate) fn display_fluid_band_matches(&mut self, x:i32, z:i32,
        floor:i32, fluid_y:i32, fluid:i32) -> bool {
        let t = self.terrain;
        if fluid != 0 {
            let expected = if fluid == 2 { Substance::Lava } else { Substance::Water };
            if self.block([x, floor, z]) != expected
                || self.block([x, fluid_y - 1, z]) != expected { return false; }
        }
        let top = if fluid == 0 { floor } else { fluid_y };
        (top..(top + t.cell_height).min(t.min_y + t.height))
            .all(|y| self.block([x, y, z]) == Substance::Air)
    }

    pub(crate) fn display_steep(&mut self,x:i32,y:i32,z:i32)->bool {
        let t=self.terrain;
        let high=(y+2).min(t.min_y+t.height-1);
        let low=(y-2).max(t.min_y);
        let solid=|job:&mut Self,p|{job.scratch.advance_block();job.compute(t.final_density,p,Mode::Cell)>0.};
        (solid(self,[x,high,(z+1).min((z&!15)+15)]) && !solid(self,[x,low,(z-1).max(z&!15)]))
            || (solid(self,[(x-1).max(x&!15),high,z]) && !solid(self,[(x+1).min((x&!15)+15),low,z]))
    }
    /// Highest positive final-density integer. Unknown intervals are searched
    /// in descending order; this does not assume a monotone vertical column.
    pub fn density_surface(&mut self, x: i32, z: i32) -> Option<i32> {
        let t = self.terrain;
        if t.graph.requires_complete_column_order() || self.beard.is_some() || self.scratch.beard != 0. { return None; }
        if let Some((point, height)) = self.density_height {
            if point == (x,z) { return height; }
        }
        let height = self.search_density_surface(x,z);
        // Both a top and a fully empty column are proven by the descending
        // search. Reuse only at exactly the same coordinate, never next door.
        self.density_height = Some(((x,z), height));
        height
    }

    fn search_density_surface(&mut self, x: i32, z: i32) -> Option<i32> {
        let t = self.terrain;
        // Reuse a proof for the entire horizontal cell, never a sampled height.
        // Every cell above this ceiling was proven non-positive at every X/Z;
        // lower unknown cells still use the original descending interval search.
        let horizontal = (x.div_euclid(t.cell_width), z.div_euclid(t.cell_width));
        let ceiling = self.reuse_density_cells.then(|| self.density_ceiling.get(&horizontal).copied()).flatten();
        let mut proved_ceiling = ceiling.is_some();
        for bottom in (t.min_y..ceiling.unwrap_or(t.min_y+t.height)).step_by(t.cell_height as usize).rev() {
            if self.reuse_density_cells {
                let base = [x.div_euclid(t.cell_width)*t.cell_width, bottom,
                    z.div_euclid(t.cell_width)*t.cell_width];
                let empty = if let Some(&empty) = self.density_cells.get(&base) { empty } else {
                    let empty = t.graph.surface_cell_sign(base, bottom+t.cell_height-1, &mut self.scratch)
                        == crate::density::SurfaceSign::NonPositive;
                    // Bound retained work even when a caller visits many cells.
                    if self.density_cells.len() < 16384 { self.density_cells.insert(base, empty); }
                    empty
                };
                if empty { continue; }
                if !proved_ceiling {
                    if self.density_ceiling.len() < 256 {
                        self.density_ceiling.insert(horizontal, bottom+t.cell_height);
                    }
                    proved_ceiling = true;
                }
            }
            if let Some(y) = self.density_interval(x, z, bottom, bottom+t.cell_height-1) { return Some(y); }
        }
        if self.reuse_density_cells && !proved_ceiling && self.density_ceiling.len() < 256 {
            self.density_ceiling.insert(horizontal, t.min_y);
        }
        None
    }
    fn density_interval(&mut self, x: i32, z: i32, bottom: i32, top: i32) -> Option<i32> {
        let t = self.terrain;
        if bottom == top {
            self.scratch.advance_block();
            return (self.compute(t.final_density,[x,top,z],Mode::Cell)>0.).then_some(top);
        }
        match t.graph.surface_sign([x,bottom,z],top,&mut self.scratch) {
            crate::density::SurfaceSign::NonPositive=>return None,
            crate::density::SurfaceSign::Positive=>return Some(top),
            crate::density::SurfaceSign::Unknown=>{}
        }
        if top-bottom<=2 {
            for y in (bottom..=top).rev() {
                self.scratch.advance_block();
                if self.compute(t.final_density,[x,y,z],Mode::Cell)>0. { return Some(y); }
            }
            return None;
        }
        let middle = bottom+(top-bottom)/2;
        self.density_interval(x,z,middle+1,top).or_else(||self.density_interval(x,z,bottom,middle))
    }
    /// Prove the rest of this interpolation cell is entirely air. No height
    /// monotonicity assumption: unbounded density nodes or unequal aquifer
    /// levels keep the original per-block query. Only exterior scans call it.
    pub fn proven_air_bottom(&mut self, p: [i32; 3]) -> Option<i32> {
        let t = self.terrain;
        if t.graph.requires_complete_column_order() || self.beard.is_some() || self.scratch.beard != 0. {
            return None;
        }
        let bottom = p[1].div_euclid(t.cell_height) * t.cell_height;
        // Try once per cell. Unproven cells use the normal block loop.
        if p[1] != bottom + t.cell_height - 1 || bottom < t.min_y { return None; }
        let base = [p[0].div_euclid(t.cell_width) * t.cell_width,
            bottom, p[2].div_euclid(t.cell_width) * t.cell_width];
        if self.proven_air_cells.contains(&base) { return Some(bottom); }
        if self.prove_air_cell(base) {
            if self.proven_air_cells.len() < 16384 { self.proven_air_cells.insert(base); }
            return Some(bottom);
        }
        if t.graph.column_range(t.final_density, [p[0], bottom, p[2]], p[1], &mut self.scratch).1 >= 0. {
            return None;
        }
        if !t.aquifers {
            return (t.global(bottom).at(bottom) == Substance::Air
                && t.global(p[1]).at(p[1]) == Substance::Air).then_some(bottom);
        }
        // Every possible nearest-three candidate must have the SAME level.
        // Then pressure() is exactly zero, and none may contain fluid anywhere
        // in the interval. This preserves aquifer-created solids and dry pools.
        let gx = (p[0] - 5).div_euclid(16);
        let gz = (p[2] - 5).div_euclid(16);
        let mut level = None;
        if t.global(bottom).at(bottom) == Substance::Lava { return None; }
        for dx in 0..=1 {
            for gy in (bottom + 1).div_euclid(12)-1..=(p[1]+1).div_euclid(12)+1 {
                for dz in 0..=1 {
                    let q = self.aquifer_location([gx+dx, gy, gz+dz]);
                    let f = self.status(q);
                    if f.at(bottom) != Substance::Air || level.is_some_and(|l| l != f.level) {
                        return None;
                    }
                    level = Some(f.level);
                }
            }
        }
        Some(bottom)
    }

    fn prove_air_cell(&mut self, base: [i32; 3]) -> bool {
        let t = self.terrain;
        let top = base[1] + t.cell_height - 1;
        if !(t.graph.cell_range(t.final_density, base, top, &mut self.scratch).1 < 0.) {
            return false;
        }
        if !t.aquifers {
            return t.global(base[1]).at(base[1]) == Substance::Air
                && t.global(top).at(top) == Substance::Air;
        }
        if t.global(base[1]).at(base[1]) == Substance::Lava { return false; }
        // Union of every nearest-three candidate over the entire cell.
        // Equal levels make all pressure terms zero; dry at the lowest Y
        // means dry everywhere above it. Different levels fall back unchanged.
        let mut level = None;
        for gx in (base[0]-5).div_euclid(16)..=(base[0]+t.cell_width-1-5).div_euclid(16)+1 {
            for gy in (base[1]+1).div_euclid(12)-1..=(top+1).div_euclid(12)+1 {
                for gz in (base[2]-5).div_euclid(16)..=(base[2]+t.cell_width-1-5).div_euclid(16)+1 {
                    let q = self.aquifer_location([gx,gy,gz]);
                    let f = self.status(q);
                    if f.at(base[1]) != Substance::Air || level.is_some_and(|l| l != f.level) {
                        return false;
                    }
                    level = Some(f.level);
                }
            }
        }
        true
    }

    fn aquifer_location(&mut self, g: [i32; 3]) -> [i32; 3] {
        if let Some(&q) = self.locations.get(&g) { return q; }
        let mut r = self.terrain.aquifer_random.at(g[0], g[1], g[2]);
        let q = [g[0]*16 + r.next_bounded(10), g[1]*12 + r.next_bounded(9), g[2]*16 + r.next_bounded(10)];
        if self.locations.len() < 16384 { self.locations.insert(g, q); }
        q
    }

    pub fn block(&mut self, p: [i32; 3]) -> Substance {
        crate::prof::hit(&crate::prof::SURFACE.block_calls);
        let t = self.terrain;
        {
            let _prof = crate::prof::Scope::new(&crate::prof::SURFACE.block_prepare);
            t.graph
                .prepare_cell(t.final_density, p, t.min_y, t.height, &mut self.scratch);
            self.scratch.advance_block();
        }
        let d = {
            let _prof = crate::prof::Scope::new(&crate::prof::SURFACE.block_density);
            // NoiseChunk wraps finalDensity+beardifier in CacheAllInCell.
            t.graph
                .final_cell_value(p, &self.scratch)
                .unwrap_or_else(|| self.compute(t.final_density, p, Mode::Cell))
                + self.beard.map_or(self.scratch.beard, |b| b.compute(p))
        };
        let state = {
            let _prof = crate::prof::Scope::new(&crate::prof::SURFACE.block_substance);
            self.substance(p, d)
        };
        if state != Substance::Default || !t.ores {
            return state;
        }
        {
            let _prof = crate::prof::Scope::new(&crate::prof::SURFACE.block_ore);
            self.ore(p).unwrap_or(Substance::Default)
        }
    }
    fn substance(&mut self, p: [i32; 3], density: f64) -> Substance {
        let t = self.terrain;
        let [x, y, z] = p;
        if density > 0. {
            return Substance::Default;
        }
        let global = t.global(y).at(y);
        if !t.aquifers || global == Substance::Lava {
            return global;
        }
        let grid = [
            (x - 5).div_euclid(16),
            (y + 1).div_euclid(12),
            (z - 5).div_euclid(16),
        ];
        let mut distances = [i32::MAX; 3];
        let mut positions = [[0; 3]; 3];
        for dx in 0..=1 {
            for dy in -1..=1 {
                for dz in 0..=1 {
                    let g = [grid[0] + dx, grid[1] + dy, grid[2] + dz];
                    let q = self.aquifer_location(g);
                    let dx2 = q[0] - x;
                    let dy2 = q[1] - y;
                    let dz2 = q[2] - z;
                    // Squared distances stay far inside i32: each term is at
                    // most a few hundred. Plain multiplies keep this inlined;
                    // `i32::pow` is a generic routine the optimiser may not
                    // fold, and this runs 18 times per block evaluation.
                    let d = dx2 * dx2 + dy2 * dy2 + dz2 * dz2;
                    for i in 0..3 {
                        if distances[i] >= d {
                            for j in (i + 1..3).rev() {
                                distances[j] = distances[j - 1];
                                positions[j] = positions[j - 1];
                            }
                            distances[i] = d;
                            positions[i] = q;
                            break;
                        }
                    }
                }
            }
        }
        let a = self.status(positions[0]);
        let state = a.at(y);
        // Measurement build only; see the `skip-aquifer` feature. Returning here
        // keeps the 18-offset nearest-aquifer scan but drops every `status` and
        // `fluid` call, so the difference from the unskipped run sizes the scan.
        #[cfg(feature = "skip-aquifer")]
        return state;
        let s01 = similarity(distances[0], distances[1]);
        if s01 <= 0. {
            return state;
        }
        if state == Substance::Water && t.global(y - 1).at(y - 1) == Substance::Lava {
            return state;
        }
        let mut barrier = None;
        let b = self.status(positions[1]);
        if density + s01 * self.pressure(p, a, b, &mut barrier) > 0. {
            return Substance::Default;
        }
        let c = self.status(positions[2]);
        let s02 = similarity(distances[0], distances[2]);
        if s02 > 0. && density + s01 * s02 * self.pressure(p, a, c, &mut barrier) > 0. {
            return Substance::Default;
        }
        let s12 = similarity(distances[1], distances[2]);
        if s12 > 0. && density + s01 * s12 * self.pressure(p, b, c, &mut barrier) > 0. {
            return Substance::Default;
        }
        state
    }
    fn pressure(&mut self, p: [i32; 3], a: Fluid, b: Fluid, barrier: &mut Option<f64>) -> f64 {
        let y = p[1];
        let sa = a.at(y);
        let sb = b.at(y);
        if (sa == Substance::Lava && sb == Substance::Water)
            || (sa == Substance::Water && sb == Substance::Lava)
        {
            return 2.;
        }
        let delta = (a.level - b.level).abs();
        if delta == 0 {
            return 0.;
        }
        let middle = 0.5 * (a.level + b.level) as f64;
        let offset = y as f64 + 0.5 - middle;
        let edge = delta as f64 / 2. - offset.abs();
        let pressure = if offset > 0. {
            if edge > 0. {
                edge / 1.5
            } else {
                edge / 2.5
            }
        } else {
            let v = 3. + edge;
            if v > 0. {
                v / 3.
            } else {
                v / 10.
            }
        };
        let noise = if (-2.0..=2.0).contains(&pressure) {
            *barrier.get_or_insert_with(|| self.compute(self.terrain.barrier, p, Mode::Block))
        } else {
            0.
        };
        2. * (noise + pressure)
    }
    fn status(&mut self, p: [i32; 3]) -> Fluid {
        // Deliberately not counted: this runs ~1200 times per column inside
        // `substance`, and an atomic on that path blocks inlining and roughly
        // doubles the column cost.
        if let Some(&v) = self.fluids.get(&p) {
            return v;
        }
        let result = self.fluid(p);
        if self.fluids.len() < 16384 {
            self.fluids.insert(p, result);
        }
        result
    }
    fn fluid(&mut self, p: [i32; 3]) -> Fluid {
        crate::prof::hit(&crate::prof::SURFACE.fluid_calls);
        let t = self.terrain;
        let key = (p[0] & !3, p[2] & !3);
        if (!t.graph.has_stateful_queries() || t.graph.has_initial_height_plan()) && !self.levels.contains_key(&key) {
            // The centre aquifer test returns global when h < y - 20. Prove
            // only that inequality; finding h itself would search the ground
            // even for candidates hundreds of blocks above it. No monotonic
            // assumption: bound the entire remaining initial-density range.
            let bottom = (p[1] - 20 + t.cell_height - 1).div_euclid(t.cell_height) * t.cell_height;
            let top = t.min_y + t.height;
            if bottom > top || (bottom >= t.min_y && t.graph.single_range(
                t.initial, [key.0, bottom, key.1], top, &mut self.scratch).1 <= 0.390625) {
                return t.global(p[1]);
            }
        }
        self.fluid_full(p)
    }
    fn fluid_full(&mut self, p: [i32; 3]) -> Fluid {
        const OFFSETS: [[i32; 2]; 13] = [
            [0, 0],
            [-2, -1],
            [-1, -1],
            [0, -1],
            [1, -1],
            [-3, 0],
            [-2, 0],
            [-1, 0],
            [1, 0],
            [-2, 1],
            [-1, 1],
            [0, 1],
            [1, 1],
        ];
        let t = self.terrain;
        let [x, y, z] = p;
        let global = t.global(y);
        let mut min = i32::MAX;
        let mut surface_water = false;
        for [dx, dz] in OFFSETS {
            let xx = x + dx * 16;
            let zz = z + dz * 16;
            let h = self.preliminary(xx, zz);
            let water_y = h.wrapping_add(8);
            let center = dx == 0 && dz == 0;
            if center && y - 12 > water_y {
                return global;
            }
            let above = y + 12 > water_y;
            if above || center {
                let status = t.global(water_y);
                if status.at(water_y) != Substance::Air {
                    if center {
                        surface_water = true
                    }
                    if above {
                        return status;
                    }
                }
            }
            min = min.min(h);
        }
        let deep_dark = self.compute(t.erosion, p, Mode::Single) < -0.225f32 as f64
            && self.compute(t.depth, p, Mode::Single) > 0.9f32 as f64;
        let (lower, upper) = if deep_dark {
            (-1., -1.)
        } else {
            let delta = min.wrapping_add(8).wrapping_sub(y);
            let amount = if surface_water {
                clamped_lerp(delta as f64 / 64., 1., 0.)
            } else {
                0.
            };
            let flood = self.compute(t.floodedness, p, Mode::Single).clamp(-1., 1.);
            (
                flood - (-0.8 + (1. - amount) * (0.4 - -0.8)),
                flood - (-0.3 + (1. - amount) * (0.8 - -0.3)),
            )
        };
        let level = if upper > 0. {
            global.level
        } else if lower > 0. {
            let gx = x.div_euclid(16);
            let gy = y.div_euclid(40);
            let gz = z.div_euclid(16);
            let spread = self.compute(t.spread, [gx, gy, gz], Mode::Single) * 10.;
            min.min(gy * 40 + 20 + (spread / 3.).floor() as i32 * 3)
        } else {
            WAY_BELOW_MIN_Y
        };
        let mut kind = global.kind;
        if level <= -10
            && level != WAY_BELOW_MIN_Y
            && kind != Substance::Lava
            && self
                .compute(
                    t.lava,
                    [x.div_euclid(64), y.div_euclid(40), z.div_euclid(64)],
                    Mode::Single,
                )
                .abs()
                > 0.3
        {
            kind = Substance::Lava
        }
        Fluid { level, kind }
    }
    fn ore(&mut self, p: [i32; 3]) -> Option<Substance> {
        let t = self.terrain;
        let v = self.compute(t.vein_toggle, p, Mode::Block);
        let copper = v > 0.;
        let (min, max) = if copper { (0, 50) } else { (-60, -8) };
        let edge = (max - p[1]).min(p[1] - min);
        if edge < 0 {
            return None;
        }
        let strength = v.abs();
        if strength + clamped_lerp(edge as f64 / 20., -0.2, 0.) < 0.4f32 as f64 {
            return None;
        }
        let mut r = t.ore_random.at(p[0], p[1], p[2]);
        if r.next_float() > 0.7 || self.compute(t.vein_ridged, p, Mode::Block) >= 0. {
            return None;
        }
        let richness = clamped_lerp(
            (strength - 0.4f32 as f64) / (0.6f32 as f64 - 0.4f32 as f64),
            0.1f32 as f64,
            0.3f32 as f64,
        );
        Some(
            if (r.next_float() as f64) < richness
                && self.compute(t.vein_gap, p, Mode::Block) > -0.3f32 as f64
            {
                if r.next_float() < 0.02 {
                    if copper {
                        Substance::RawCopper
                    } else {
                        Substance::RawIron
                    }
                } else if copper {
                    Substance::CopperOre
                } else {
                    Substance::IronOre
                }
            } else if copper {
                Substance::Granite
            } else {
                Substance::Tuff
            },
        )
    }
}
impl<'a> Job<'a> {
    pub(crate) fn resume(terrain: &'a Terrain, work: SurfaceWork) -> Self {
        Self { terrain, scratch: work.scratch, beard: None, display: false,
            reuse_density_cells: false, density_cells: work.density_cells, density_ceiling: work.density_ceiling,
            density_height: work.density_height,
            display_tops: work.display_tops, levels: work.levels,
            locations: work.locations, fluids: work.fluids, neighbour_tops: work.tops,
            proven_air_cells: work.proven_air_cells,
            surface_columns: HashMap::default(), surface_bottoms: HashMap::default(),
            surface_tops: HashMap::default(), shared_tops: None }
    }
}
fn similarity(a: i32, b: i32) -> f64 {
    1. - (b - a).abs() as f64 / 25.
}

#[cfg(test)]
mod fluid_bound_tests {
    use super::*;
    use serde_json::json;

    fn fixture(name: &str) -> Value {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures/worldgen");
        serde_json::from_str(&std::fs::read_to_string(root.join(name)).unwrap()).unwrap()
    }

    #[test]
    fn proven_ceiling_keeps_higher_thin_layers_in_neighbouring_columns() {
        let mut d = fixture("overworld.json");
        d["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:max",
            "argument1":{"type":"minecraft:y_clamped_gradient","from_y":31,"to_y":33,"from_value":1.,"to_value":-1.},
            "argument2":{"type":"minecraft:range_choice",
                "input":{"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":-64.,"to_value":320.},
                "min_inclusive":105,"max_exclusive":106,"when_out_of_range":-1.,
                "when_in_range":{"type":"minecraft:range_choice","input":{"type":"lithostitched:axis","axis":"x"},
                    "min_inclusive":-2,"max_exclusive":-1,"when_in_range":1.,"when_out_of_range":-1.}}});
        let t = Terrain::from_document(0, &d).unwrap();
        let mut fast = t.job(-16, -16, false).unwrap();
        fast.reuse_density_cells = true;
        // The first column misses the upper sheet. Its sampled height must
        // never become a ceiling for the next column in the same noise cell.
        assert_eq!(fast.density_surface(-4, -4), Some(31));
        assert!(fast.density_ceiling[&(-1, -1)] > 105);
        assert!(fast.density_ceiling[&(-1, -1)] < t.min_y + t.height);
        let mut fast = Job::resume(&t, fast.park());
        fast.reuse_density_cells = true;
        for (x, z) in [(-2,-4), (-1,-4), (-4,-4), (-2,-1), (0,0)] {
            let mut full = t.job(x, z, false).unwrap();
            let expected = (t.min_y..t.min_y+t.height).rev().find(|&y| {
                full.scratch.advance_block();
                full.compute(t.final_density, [x,y,z], Mode::Cell) > 0.
            });
            assert_eq!(fast.density_surface(x,z), expected, "{x},{z}");
        }
    }

    #[test]
    fn sparse_rebase_resets_proofs_and_fallback_state_without_reallocating_graph_vectors() {
        let d = fixture("overworld.json");
        let t = Terrain::from_document(917, &d).unwrap();
        let mut reused = t.job(0, 0, false).unwrap();
        let allocation = reused.scratch.retained_bytes();
        for (x,z) in [(0,0),(-17,-65),(4096,8192),(-4096,0),(16,-16),(0,0)] {
            reused.reset_display_chunk(x,z).unwrap();
            assert!(reused.density_cells.is_empty() && reused.density_ceiling.is_empty());
            assert!(reused.surface_columns.is_empty() && reused.neighbour_tops.is_empty());
            assert!(reused.scratch.retained_bytes() >= allocation);
            let mut fresh = t.job(x,z,false).unwrap();
            assert_eq!(reused.density_surface(x,z), fresh.density_surface(x,z));
            // Fill exact fallback aquifer/ore/cache state before the next rebase.
            assert_eq!(reused.column(x,z).unwrap().blocks, fresh.column(x,z).unwrap().blocks);
            reused.reuse_density_cells = true;
            reused.density_surface(x,z);
        }
    }

    #[test]
    fn empty_density_ceiling_cache_is_bounded_and_survives_workspace_reuse() {
        let mut d = fixture("overworld.json");
        d["settings"]["noise_router"]["final_density"] = json!(-1.);
        let t = Terrain::from_document(0, &d).unwrap();
        let mut job = t.job(0,0,false).unwrap();
        job.reuse_density_cells = true;
        for x in -300..300 { assert_eq!(job.density_surface(x*4,0), None); }
        assert_eq!(job.density_ceiling.len(),256);
        assert!(job.density_ceiling.values().all(|&y| y == t.min_y));
        let work = job.park();
        assert!(work.retained_bytes() >= work.density_ceiling.capacity()*64);
        let mut job = Job::resume(&t, work);
        job.reuse_density_cells = true;
        assert_eq!(job.density_surface(-1200,0),None);
        assert_eq!(job.density_surface(4096,0),None);
    }

    fn compare(t: &Terrain, reverse: bool) {
        let mut fast = t.job(-48, 32, false).unwrap();
        let mut full = t.job(-48, 32, false).unwrap();
        for i in 0..16 {
            let x = -97 + i * 17;
            let z = 101 - i * 31;
            let ys: Vec<_> = (t.min_y - 16..=t.min_y + t.height + 24).step_by(5).collect();
            for k in 0..ys.len() {
                let y = ys[if reverse { ys.len() - 1 - k } else { k }];
                let p = [x, y, z];
                let a = fast.fluid(p);
                let b = full.fluid_full(p);
                assert_eq!((a.level, a.kind), (b.level, b.kind), "fluid at {p:?}");
            }
        }
    }

    #[test]
    fn high_aquifer_candidates_match_full_height_search() {
        for file in ["overworld.json", "amplified.json", "nether.json", "end.json"] {
            for seed in [0, -917] {
                compare(&Terrain::from_document(seed, &fixture(file)).unwrap(), seed == 0);
            }
        }
    }

    #[test]
    fn thin_initial_layers_empty_columns_and_stateful_nodes_keep_fluid_levels() {
        for variant in 0..5 {
            let mut d = fixture("overworld.json");
            let axis = json!({"type":"minecraft:y_clamped_gradient", "from_y":-64,
                "to_y":320,"from_value":-64.,"to_value":320.});
            let initial = match variant {
                0 => json!(-1.), // preliminary returns its MAX sentinel.
                1 => json!(1.),  // highest lattice point is solid.
                2 => json!({"type":"minecraft:range_choice","input":axis,
                    "min_inclusive":240,"max_exclusive":241,
                    "when_in_range":1.,"when_out_of_range":-1.}),
                3 => json!({"type":"minecraft:range_choice","input":axis,
                    "min_inclusive":-24,"max_exclusive":-23,
                    "when_in_range":1.,"when_out_of_range":-1.}),
                _ => json!({"type":"minecraft:cache_2d","argument":axis}),
            };
            d["settings"]["noise_router"]["initial_density_without_jaggedness"] = initial;
            let t = Terrain::from_document(917, &d).unwrap();
            compare(&t, false);
            compare(&t, true);
        }
    }
}
