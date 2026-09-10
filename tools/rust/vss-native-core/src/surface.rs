//! SurfaceRules codecs and SurfaceSystem's column pass, including terracotta
//! bands, eroded badlands and frozen ocean extensions (Minecraft 1.21.1).
use crate::{
    biome::{Biome, ClimateColors},
    blocks::{Palette, Pos, StateId, Volume},
    density::{integer, lerp, minecraft_type, number, string, Graph, Result},
    random::{Positional, Random},
    terrain::{Job, Terrain},
};
use serde_json::Value;
enum Rule {
    Block(StateId),
    Sequence(Vec<Rule>),
    Condition(Condition, Box<Rule>),
    Bands,
}
enum Condition {
    Biome(Vec<String>),
    Noise(usize, f64, f64),
    Gradient(Positional, i32, i32),
    Y(i32, i32, bool),
    Water(i32, i32, bool),
    Cold,
    Steep,
    Hole,
    Above,
    Not(Box<Condition>),
    Stone(i32, bool, i32, bool),
}
struct Context<'a> {
    p: Pos,
    biome: &'a str,
    cold: bool,
    steep: bool,
    depth: i32,
    secondary: f64,
    above: i32,
    below: i32,
    water: i32,
    min_surface: i32,
}
pub struct Surface {
    rule: Rule,
    bands: [StateId; 192],
    band_offset: usize,
    depth: usize,
    secondary: usize,
    badlands: [usize; 3],
    iceberg: [usize; 3],
    default: StateId,
    water: StateId,
    packed_ice: StateId,
    snow: StateId,
}
fn flag(v: &Value, key: &str) -> Result<bool> {
    v[key]
        .as_bool()
        .ok_or_else(|| format!("missing boolean {key}"))
}
fn anchor(v: &Value, min: i32, height: i32) -> Result<i32> {
    if v.get("absolute").is_some() {
        integer(v, "absolute")
    } else if v.get("above_bottom").is_some() {
        Ok(min + integer(v, "above_bottom")?)
    } else if v.get("below_top").is_some() {
        Ok(min + height - 1 - integer(v, "below_top")?)
    } else {
        Err("unknown vertical anchor".into())
    }
}
impl Surface {
    pub fn new(terrain: &mut Terrain, doc: &Value, palette: &mut Palette) -> Result<Self> {
        let rule = parse_rule(
            &doc["settings"]["surface_rule"],
            &mut terrain.graph,
            doc,
            palette,
            terrain.min_y,
            terrain.height,
            0,
        )?;
        let mut register = |name: &str| {
            terrain
                .graph
                .registered_noise(&format!("minecraft:{name}"), doc)
        };
        let band_offset = register("clay_bands_offset")?;
        let depth = register("surface")?;
        let secondary = register("surface_secondary")?;
        let badlands = [
            register("badlands_surface")?,
            register("badlands_pillar")?,
            register("badlands_pillar_roof")?,
        ];
        let iceberg = [
            register("iceberg_surface")?,
            register("iceberg_pillar")?,
            register("iceberg_pillar_roof")?,
        ];
        let mut r = terrain.graph.random.from_hash("minecraft:clay_bands");
        let mut bands = [palette.named("minecraft:terracotta")?; 192];
        let orange = palette.named("minecraft:orange_terracotta")?;
        let mut i = 0;
        while i < 192 {
            i += r.next_bounded(5) as usize + 1;
            if i < 192 {
                bands[i] = orange
            }
            i += 1;
        }
        for (size, color) in [(1, "yellow"), (2, "brown"), (1, "red")] {
            let state = palette.named(&format!("minecraft:{color}_terracotta"))?;
            make_bands(&mut r, &mut bands, size, state);
        }
        let white = palette.named("minecraft:white_terracotta")?;
        let gray = palette.named("minecraft:light_gray_terracotta")?;
        let count = r.next_bounded(7) + 9;
        let mut i = 0;
        for _ in 0..count {
            if i >= 192 {
                break;
            }
            bands[i] = white;
            if i > 1 && r.next_bool() {
                bands[i - 1] = gray
            }
            if i + 1 < 192 && r.next_bool() {
                bands[i + 1] = gray
            }
            i += r.next_bounded(16) as usize + 4;
        }
        Ok(Self {
            rule,
            bands,
            band_offset,
            depth,
            secondary,
            badlands,
            iceberg,
            default: palette.intern(&doc["settings"]["default_block"])?,
            water: palette.named("minecraft:water")?,
            packed_ice: palette.named("minecraft:packed_ice")?,
            snow: palette.named("minecraft:snow_block")?,
        })
    }
    pub fn depth(&self, graph: &Graph, x: i32, z: i32) -> i32 {
        (graph.noise(self.depth, x as f64, 0., z as f64) * 2.75
            + 3.
            + graph.random.at(x, 0, z).next_double() * 0.25) as i32
    }
    pub fn band(&self, graph: &Graph, p: Pos) -> StateId {
        let offset =
            (graph.noise(self.band_offset, p[0] as f64, 0., p[2] as f64) * 4. + 0.5).floor() as i32;
        self.bands[(p[1] + offset + 192).rem_euclid(192) as usize]
    }
    pub fn min_surface(&self, job: &mut Job<'_>, x: i32, z: i32, depth: i32) -> i32 {
        let xx = x & !15;
        let zz = z & !15;
        let a = job.preliminary(xx, zz) as f64;
        let b = job.preliminary(xx + 16, zz) as f64;
        let c = job.preliminary(xx, zz + 16) as f64;
        let d = job.preliminary(xx + 16, zz + 16) as f64;
        (lerp(
            (z & 15) as f64 / 16.,
            lerp((x & 15) as f64 / 16., a, b),
            lerp((x & 15) as f64 / 16., c, d),
        )
        .floor() as i32)
            .wrapping_add(depth)
            .wrapping_sub(8)
    }
    pub fn apply_chunk<'b>(
        &self,
        job: &mut Job<'_>,
        world: &mut Volume,
        x0: i32,
        z0: i32,
        colors: &ClimateColors,
        mut biome: impl FnMut(Pos) -> Result<(&'b str, &'b Biome)>,
    ) -> Result<()> {
        for x in x0..x0 + 16 {
            for z in z0..z0 + 16 {
                self.apply_column(job, world, x0, z0, x, z, colors, &mut biome)?;
            }
        }
        Ok(())
    }
    pub fn sparse_safe(&self, palette: &Palette) -> bool {
        self.sparse_safe_for_biome(palette, None)
    }
    /// Steepness reads WORLD_SURFACE_WG: solid-to-water preserves its height.
    /// Only reachable air writes can change a predecessor column's top.
    pub fn sparse_safe_for_biome(&self, palette: &Palette, biome: Option<&str>) -> bool {
        fn known(condition: &Condition, biome: Option<&str>) -> Option<bool> {
            match condition {
                Condition::Biome(names) => biome.map(|b| names.iter().any(|n| n == b)),
                Condition::Not(c) => known(c, biome).map(|b| !b),
                _ => None,
            }
        }
        fn safe(rule: &Rule, p: &Palette, biome: Option<&str>) -> bool {
            match rule {
                Rule::Block(id) => !p.is_air(*id),
                Rule::Bands => true,
                Rule::Sequence(r) => r.iter().all(|r| safe(r, p, biome)),
                Rule::Condition(c, r) => known(c, biome) == Some(false) || safe(r, p, biome),
            }
        }
        safe(&self.rule, palette, biome)
    }
    pub fn geometry_column<'b>(
        &self,
        job: &mut Job<'_>,
        world: &mut Volume,
        x: i32,
        z: i32,
        colors: &ClimateColors,
        mut biome: impl FnMut(Pos) -> Result<(&'b str, &'b Biome)>,
    ) -> Result<()> {
        let top = world.height(x, z, false);
        let t = job.terrain;
        let (name, b) = biome([x, if t.graph.legacy { 0 } else { top }, z])?;
        if name == "minecraft:eroded_badlands" {
            self.eroded(&t.graph, world, x, z, top);
        }
        if name == "minecraft:frozen_ocean" || name == "minecraft:deep_frozen_ocean" {
            let depth = self.depth(&t.graph, x, z);
            let min = self.min_surface(job, x, z, depth);
            self.frozen(
                &t.graph,
                world,
                x,
                z,
                top,
                min,
                t.sea_level,
                colors.temperature(b, [x, 63, z]) > 0.1,
            );
        }
        Ok(())
    }
    /// Maximum downward stone-depth predicate used anywhere in the rule tree.
    /// With this many extra blocks, a truncated solid run produces exactly the
    /// same predicate results for every retained output material.
    pub fn exterior_padding(&self, graph: &Graph, x: i32, z: i32) -> Option<i32> {
        fn condition(c: &Condition, depth: i32, secondary: f64) -> Option<i32> {
            match c {
                Condition::Stone(offset, add, range, true) => {
                    let extra = if *range == 0 {
                        0
                    } else {
                        ((secondary + 1.) / 2. * *range as f64) as i32
                    };
                    1i32.checked_add(*offset)?
                        .checked_add(if *add { depth } else { 0 })?
                        .checked_add(extra)
                }
                Condition::Not(c) => condition(c, depth, secondary),
                _ => Some(0),
            }
        }
        fn rule(r: &Rule, depth: i32, secondary: f64) -> Option<i32> {
            match r {
                Rule::Sequence(rs) => rs
                    .iter()
                    .try_fold(0, |n, r| Some(n.max(rule(r, depth, secondary)?))),
                Rule::Condition(c, r) => {
                    Some(condition(c, depth, secondary)?.max(rule(r, depth, secondary)?))
                }
                _ => Some(0),
            }
        }
        let padding = rule(
            &self.rule,
            self.depth(graph, x, z),
            graph.noise(self.secondary, x as f64, 0., z as f64),
        )?
        .max(0)
        .checked_add(1)?;
        (padding <= 64).then_some(padding)
    }

    pub fn apply_column<'b>(
        &self,
        job: &mut Job<'_>,
        world: &mut Volume,
        x0: i32,
        z0: i32,
        x: i32,
        z: i32,
        colors: &ClimateColors,
        mut biome: impl FnMut(Pos) -> Result<(&'b str, &'b Biome)>,
    ) -> Result<()> {
        let t = job.terrain;
        let graph = &t.graph;
        let original_top = world.height(x, z, false);
        let (name, b) = biome([x, if t.graph.legacy { 0 } else { original_top }, z])?;
        if name == "minecraft:eroded_badlands" {
            self.eroded(graph, world, x, z, original_top);
        }
        let top = world.height(x, z, false);
        let depth = self.depth(graph, x, z);
        let secondary = graph.noise(self.secondary, x as f64, 0., z as f64);
        let min_surface = self.min_surface(job, x, z, depth);
        let north = world.height(x, (z - 1).max(z0), false);
        let south = world.height(x, (z + 1).min(z0 + 15), false);
        let steep = if south >= north + 4 {
            true
        } else {
            let west = world.height((x - 1).max(x0), z, false);
            let east = world.height((x + 1).min(x0 + 15), z, false);
            west >= east + 4
        };
        let mut above = 0;
        let mut water = i32::MIN;
        let mut floor = i32::MAX;
        for y in (world.origin[1]..=top).rev() {
            let p = [x, y, z];
            let state = world.get(p);
            if world.palette.is_air(state) {
                above = 0;
                water = i32::MIN;
            } else if world.palette.fluid(state) {
                if water == i32::MIN {
                    water = y + 1;
                }
            } else {
                if floor >= y {
                    floor = -32512;
                    for yy in (world.origin[1] - 1..y).rev() {
                        let s = world.get([x, yy, z]);
                        if !world.palette.stone(s) {
                            floor = yy + 1;
                            break;
                        }
                    }
                }
                above += 1;
                if state == self.default {
                    let (biome_name, biome_data) = biome(p)?;
                    let context = Context {
                        p,
                        biome: biome_name,
                        cold: colors.temperature(biome_data, p) < 0.15,
                        steep,
                        depth,
                        secondary,
                        above,
                        below: y - floor + 1,
                        water,
                        min_surface,
                    };
                    if let Some(id) = self.rule(&self.rule, graph, &context) {
                        world.set(p, id);
                    }
                }
            }
        }
        if name == "minecraft:frozen_ocean" || name == "minecraft:deep_frozen_ocean" {
            self.frozen(
                graph,
                world,
                x,
                z,
                original_top,
                min_surface,
                t.sea_level,
                colors.temperature(b, [x, 63, z]) > 0.1,
            );
        }
        if world.incomplete {
            Err("surface pass missing horizontal neighbour data".into())
        } else {
            Ok(())
        }
    }
    /// Run the world's real material rules against a cheap exterior context.
    /// Neighbour steepness, pillars and iceberg geometry await exact refinement.
    pub fn preview_materials(&self, t: &Terrain, x: i32, z: i32, floor: i32,
        water: i32, name: &str, biome: &Biome, colors: &ClimateColors) -> [StateId; 3] {
        let depth = self.depth(&t.graph, x, z);
        let secondary = t.graph.noise(self.secondary, x as f64, 0., z as f64);
        [1, 2, 7].map(|offset| {
            let p = [x, floor - offset, z];
            let c = Context { p, biome: name, cold: colors.temperature(biome, p) < 0.15,
                steep: false, depth, secondary, above: offset,
                below: (floor - offset - t.min_y + 1).max(1), water,
                min_surface: floor - depth - 8 };
            self.rule(&self.rule, &t.graph, &c).unwrap_or(self.default)
        })
    }

    fn rule(&self, rule: &Rule, graph: &Graph, c: &Context<'_>) -> Option<StateId> {
        match rule {
            Rule::Block(id) => Some(*id),
            Rule::Bands => Some(self.band(graph, c.p)),
            Rule::Sequence(rules) => rules.iter().find_map(|r| self.rule(r, graph, c)),
            Rule::Condition(test, then) => {
                if test.matches(graph, c) {
                    self.rule(then, graph, c)
                } else {
                    None
                }
            }
        }
    }
    fn eroded(&self, g: &Graph, w: &mut Volume, x: i32, z: i32, top: i32) {
        let n = g.noise(self.badlands[0], x as f64, 0., z as f64).abs() * 8.25;
        let pillar = g.noise(self.badlands[1], x as f64 * 0.2, 0., z as f64 * 0.2) * 15.;
        let v = n.min(pillar);
        if v <= 0. {
            return;
        }
        let roof = (g.noise(self.badlands[2], x as f64 * 0.75, 0., z as f64 * 0.75) * 1.5).abs();
        let end = (64. + (v * v * 2.5).min((roof * 50.).ceil() + 24.)).floor() as i32;
        if top > end {
            return;
        }
        for y in (w.origin[1]..=end).rev() {
            let b = w.get([x, y, z]);
            if w.palette.state(b).name == w.palette.state(self.default).name {
                break;
            }
            if w.palette.is(b, "minecraft:water") {
                return;
            }
        }
        for y in (w.origin[1]..=end).rev() {
            let p = [x, y, z];
            let b = w.get(p);
            if !w.palette.is_air(b) {
                break;
            }
            w.set(p, self.default);
        }
    }
    fn frozen(
        &self,
        g: &Graph,
        w: &mut Volume,
        x: i32,
        z: i32,
        top: i32,
        min: i32,
        sea: i32,
        melt: bool,
    ) {
        let v = (g.noise(self.iceberg[0], x as f64, 0., z as f64) * 8.25)
            .abs()
            .min(g.noise(self.iceberg[1], x as f64 * 1.28, 0., z as f64 * 1.28) * 15.);
        if v <= 1.8 {
            return;
        }
        let roof = (g.noise(self.iceberg[2], x as f64 * 1.17, 0., z as f64 * 1.17) * 1.5).abs();
        let mut height = (v * v * 1.2).min((roof * 40.).ceil() + 14.);
        if melt {
            height -= 2.;
        }
        let bottom = if height > 2. {
            let b = sea as f64 - height - 7.;
            height += sea as f64;
            b
        } else {
            height = 0.;
            0.
        };
        let mut r = g.random.at(x, 0, z);
        let snow_count = 2 + r.next_bounded(4);
        let snow_start = sea + 18 + r.next_bounded(10);
        let mut snow = 0;
        for y in (min..=top.max(height as i32 + 1)).rev() {
            let p = [x, y, z];
            let b = w.get(p);
            if (w.palette.is_air(b) && y < (height as i32) && r.next_double() > 0.01)
                || (b == self.water
                    && y > bottom as i32
                    && y < sea
                    && bottom != 0.
                    && r.next_double() > 0.15)
            {
                if snow <= snow_count && y > snow_start {
                    w.set(p, self.snow);
                    snow += 1;
                } else {
                    w.set(p, self.packed_ice);
                }
            }
        }
    }
}
impl Condition {
    fn matches(&self, g: &Graph, c: &Context<'_>) -> bool {
        match self {
            Self::Biome(names) => names.iter().any(|name| name == c.biome),
            Self::Noise(id, a, b) => {
                let v = g.noise(*id, c.p[0] as f64, 0., c.p[2] as f64);
                v >= *a && v <= *b
            }
            Self::Gradient(r, a, b) => {
                if c.p[1] <= *a {
                    true
                } else if c.p[1] >= *b {
                    false
                } else {
                    (r.at(c.p[0], c.p[1], c.p[2]).next_float() as f64)
                        < 1. + (c.p[1] - a) as f64 / (b - a) as f64 * -1.
                }
            }
            Self::Y(anchor, mul, stone) => {
                c.p[1] + if *stone { c.above } else { 0 } >= anchor + c.depth * mul
            }
            Self::Water(offset, mul, stone) => {
                c.water == i32::MIN
                    || c.p[1] + if *stone { c.above } else { 0 } >= c.water + offset + c.depth * mul
            }
            Self::Cold => c.cold,
            Self::Steep => c.steep,
            Self::Hole => c.depth <= 0,
            Self::Above => c.p[1] >= c.min_surface,
            Self::Not(test) => !test.matches(g, c),
            Self::Stone(offset, add, secondary, ceiling) => {
                let depth = if *ceiling { c.below } else { c.above };
                let extra = if *secondary == 0 {
                    0
                } else {
                    ((c.secondary - -1.) / 2. * *secondary as f64) as i32
                };
                depth <= 1 + offset + if *add { c.depth } else { 0 } + extra
            }
        }
    }
}
fn parse_rule(
    v: &Value,
    g: &mut Graph,
    doc: &Value,
    p: &mut Palette,
    min: i32,
    height: i32,
    depth: usize,
) -> Result<Rule> {
    if depth > 128 {
        return Err("surface rule depth exceeded".into());
    }
    Ok(match minecraft_type(v)? {
        "block" => Rule::Block(p.intern(&v["result_state"])?),
        "bandlands" => Rule::Bands,
        "sequence" => {
            let values = v["sequence"].as_array().ok_or("missing surface sequence")?;
            if values.is_empty() || values.len() > 4096 {
                return Err("surface sequence budget".into());
            }
            Rule::Sequence(
                values
                    .iter()
                    .map(|v| parse_rule(v, g, doc, p, min, height, depth + 1))
                    .collect::<Result<_>>()?,
            )
        }
        "condition" => Rule::Condition(
            parse_condition(&v["if_true"], g, doc, min, height, 0)?,
            Box::new(parse_rule(
                &v["then_run"],
                g,
                doc,
                p,
                min,
                height,
                depth + 1,
            )?),
        ),
        s => return Err(format!("unsupported surface rule {s}")),
    })
}
fn parse_condition(
    v: &Value,
    g: &mut Graph,
    doc: &Value,
    min: i32,
    height: i32,
    depth: usize,
) -> Result<Condition> {
    if depth > 128 {
        return Err("surface condition depth exceeded".into());
    }
    Ok(match minecraft_type(v)? {
        "biome" => Condition::Biome(
            serde_json::from_value(v["biome_is"].clone())
                .map_err(|e| format!("biome condition: {e}"))?,
        ),
        "noise_threshold" => Condition::Noise(
            g.registered_noise(string(v, "noise")?, doc)?,
            number(v, "min_threshold")?,
            number(v, "max_threshold")?,
        ),
        "vertical_gradient" => Condition::Gradient(
            g.random.from_hash(string(v, "random_name")?).positional(),
            anchor(&v["true_at_and_below"], min, height)?,
            anchor(&v["false_at_and_above"], min, height)?,
        ),
        "y_above" => Condition::Y(
            anchor(&v["anchor"], min, height)?,
            integer(v, "surface_depth_multiplier")?,
            flag(v, "add_stone_depth")?,
        ),
        "water" => Condition::Water(
            integer(v, "offset")?,
            integer(v, "surface_depth_multiplier")?,
            flag(v, "add_stone_depth")?,
        ),
        "temperature" => Condition::Cold,
        "steep" => Condition::Steep,
        "hole" => Condition::Hole,
        "above_preliminary_surface" => Condition::Above,
        "not" => Condition::Not(Box::new(parse_condition(
            &v["invert"],
            g,
            doc,
            min,
            height,
            depth + 1,
        )?)),
        "stone_depth" => {
            let surface = string(v, "surface_type")?;
            if surface != "floor" && surface != "ceiling" {
                return Err("invalid cave surface".into());
            }
            Condition::Stone(
                integer(v, "offset")?,
                flag(v, "add_surface_depth")?,
                integer(v, "secondary_depth_range")?,
                surface == "ceiling",
            )
        }
        s => return Err(format!("unsupported surface condition {s}")),
    })
}
fn make_bands(r: &mut Random, bands: &mut [StateId; 192], base: i32, color: StateId) {
    let count = r.next_bounded(10) + 6;
    for _ in 0..count {
        let size = base + r.next_bounded(3);
        let start = r.next_bounded(192) as usize;
        for i in start..(start + size as usize).min(192) {
            bands[i] = color;
        }
    }
}
