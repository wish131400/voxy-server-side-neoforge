//! Vanilla NoiseBasedChunkGenerator base columns, Aquifer and OreVeinifier.
//! No structure starts, carvers or decoration are implied by a base column.
use crate::{
    density::{clamped_lerp, integer, string, Graph, Id, Mode, Result, Scratch},
    random::Positional,
};
use serde_json::Value;
use std::collections::HashMap;

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
    levels: HashMap<(i32, i32), i32>,
    locations: HashMap<[i32; 3], [i32; 3]>,
    fluids: HashMap<[i32; 3], Fluid>,
    surface_columns: HashMap<(i32, i32), Column>,
    surface_tops: HashMap<(i32, i32), Option<(i32, Substance)>>,
}
const WAY_BELOW_MIN_Y: i32 = -32512;
impl Terrain {
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
        self.graph.initialize_column_caches(&mut scratch);
        Ok(Job {
            terrain: self,
            scratch,
            beard: None,
            levels: HashMap::new(),
            locations: HashMap::new(),
            fluids: HashMap::new(),
            surface_columns: HashMap::new(),
            surface_tops: HashMap::new(),
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
    /// Sparse surface requests share NoiseChunk cells too. Visit each needed
    /// cell once, retaining complete centre columns and only the first block
    /// of steepness neighbours. The cache lives for this chunk batch only.
    pub fn prepare_surface_columns(&mut self, centres: &[(i32, i32)]) {
        let t = self.terrain;
        if !t.graph.requires_complete_column_order() || centres.is_empty() {
            return;
        }
        self.surface_columns.clear();
        self.surface_tops.clear();
        let mut needed = std::collections::BTreeMap::new();
        for &(x, z) in centres {
            let x0 = x & !15;
            let z0 = z & !15;
            for p in [(x, z), (x, (z - 1).max(z0)), (x, (z + 1).min(z0 + 15)),
                ((x - 1).max(x0), z), ((x + 1).min(x0 + 15), z)] {
                needed.entry(p).or_insert(false);
            }
            needed.insert((x, z), true);
        }
        let mut cells = std::collections::BTreeMap::<_, Vec<_>>::new();
        for ((x, z), full) in needed {
            cells.entry((x.div_euclid(t.cell_width), z.div_euclid(t.cell_width)))
                .or_default().push((x, z, full));
            self.surface_tops.insert((x, z), None);
            if full {
                self.surface_columns.insert((x, z), Column { min_y: t.min_y,
                    blocks: vec![Substance::Air; t.height as usize],
                    surface_height: t.min_y, ocean_floor: t.min_y, fluid_height: None });
            }
        }
        for columns in cells.values() {
            for cy in (t.min_y..t.min_y + t.height).step_by(t.cell_height as usize).rev() {
                if columns.iter().all(|&(x, z, full)| !full && self.surface_tops[&(x, z)].is_some()) {
                    break;
                }
                for y in (cy..cy + t.cell_height).rev() {
                    for &(x, z, full) in columns {
                        if !full && self.surface_tops[&(x, z)].is_some() { continue; }
                        let block = self.block([x, y, z]);
                        if block != Substance::Air && self.surface_tops[&(x, z)].is_none() {
                            self.surface_tops.insert((x, z), Some((y, block)));
                        }
                        if let Some(column) = self.surface_columns.get_mut(&(x, z)) {
                            column.blocks[(y - t.min_y) as usize] = block;
                            if block != Substance::Air { column.surface_height = column.surface_height.max(y + 1); }
                            if block.solid() { column.ocean_floor = column.ocean_floor.max(y + 1); }
                            if block.fluid() && column.fluid_height.is_none() { column.fluid_height = Some(y + 1); }
                        }
                    }
                }
            }
        }
    }

    /// Visit a chunk in NoiseChunk's cell order. A column-at-a-time reader
    /// revisits each 4x4 horizontal cell sixteen times on stateful graphs.
    pub fn fill_chunk(&mut self, x0: i32, z0: i32, mut write: impl FnMut(i32, i32, i32, Substance)) {
        let t = self.terrain;
        if t.graph.requires_complete_column_order() {
            let first_x = x0.div_euclid(t.cell_width) * t.cell_width;
            let first_z = z0.div_euclid(t.cell_width) * t.cell_width;
            for cx in (first_x..x0 + 16).step_by(t.cell_width as usize) {
                for cz in (first_z..z0 + 16).step_by(t.cell_width as usize) {
                    for cy in (t.min_y..t.min_y + t.height).step_by(t.cell_height as usize).rev() {
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
    pub fn preliminary(&mut self, x: i32, z: i32) -> i32 {
        let key = (x & !3, z & !3);
        if let Some(&h) = self.levels.get(&key) {
            return h;
        }
        let mut h = i32::MAX;
        let t = self.terrain;
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
        if let Some(top) = self.surface_tops.get(&(x, z)) {
            return *top;
        }
        let t = self.terrain;
        for y in (t.min_y..t.min_y + t.height).rev() {
            let block = self.block([x, y, z]);
            if block != Substance::Air {
                return Some((y, block));
            }
        }
        None
    }
    pub fn block(&mut self, p: [i32; 3]) -> Substance {
        let t = self.terrain;
        t.graph
            .prepare_cell(t.final_density, p, t.min_y, t.height, &mut self.scratch);
        self.scratch.advance_block();
        // NoiseChunk wraps finalDensity+beardifier in CacheAllInCell.
        let d = t
            .graph
            .final_cell_value(p, &self.scratch)
            .unwrap_or_else(|| self.compute(t.final_density, p, Mode::Cell))
            + self.beard.map_or(self.scratch.beard, |b| b.compute(p));
        let state = self.substance(p, d);
        if state != Substance::Default || !t.ores {
            return state;
        }
        self.ore(p).unwrap_or(Substance::Default)
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
                    let q = if let Some(&q) = self.locations.get(&g) {
                        q
                    } else {
                        let mut r = t.aquifer_random.at(g[0], g[1], g[2]);
                        let q = [
                            g[0] * 16 + r.next_bounded(10),
                            g[1] * 12 + r.next_bounded(9),
                            g[2] * 16 + r.next_bounded(10),
                        ];
                        if self.locations.len() < 16384 {
                            self.locations.insert(g, q);
                        }
                        q
                    };
                    let d = (q[0] - x).pow(2) + (q[1] - y).pow(2) + (q[2] - z).pow(2);
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
fn similarity(a: i32, b: i32) -> f64 {
    1. - (b - a).abs() as f64 / 25.
}
