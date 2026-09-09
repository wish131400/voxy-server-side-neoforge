//! Vanilla vegetation features on native block storage. Placement modifiers
//! execute lazily, depth first, sharing the feature's WorldgenRandom stream.
//! Unsupported codecs fail compilation; they never silently become fake trees.
use crate::{
    blocks::{offset, Palette, Pos, StateId, Volume},
    density::{integer, minecraft_type, number, string, Result},
    noise::PerlinSimplexNoise,
    providers::{IntProvider, StateProvider},
    random::Random,
};
use serde_json::Value;
use std::collections::HashSet;
mod decorators;
use decorators::{sorted_positions, Decorator};
mod mushroom;
mod shape;
mod special;
use special::{Branches, Roots};

pub struct Placed {
    feature: Feature,
    modifiers: Vec<Modifier>,
}
pub enum Feature {
    Simple(StateProvider),
    Bamboo(f32),
    Patch(i32, i32, i32, Box<Placed>),
    Selector(Vec<(Placed, f32)>, Box<Placed>),
    Random(Vec<Placed>),
    Boolean(Box<Placed>, Box<Placed>),
    Tree(Tree),
    Mushroom(mushroom::Mushroom),
    None,
}
enum Modifier {
    Count(IntProvider),
    Square,
    Height(u8),
    WaterDepth(i32),
    Rarity(i32),
    Offset(IntProvider, IntProvider),
    Filter(Predicate),
    Biome,
    NoiseCount(i32, f64, f64),
    NoiseThreshold(f64, i32, i32),
}
enum Predicate {
    True,
    All(Vec<Self>),
    Any(Vec<Self>),
    Not(Box<Self>),
    Blocks(Pos, Vec<String>),
    Tag(Pos, String),
    Survive(Pos, StateId),
    Solid(Pos),
}
pub struct Tree {
    decorators: Vec<Decorator>,
    branches: Branches,
    roots: Option<Roots>,
    cherry_chances: [f32; 4],
    trunk_type: String,
    bend_length: IntProvider,
    min_height_for_leaves: i32,
    leaf_attempts: i32,
    height: [i32; 3],
    trunk: StateProvider,
    foliage: StateProvider,
    dirt: StateProvider,
    force_dirt: bool,
    ignore_vines: bool,
    minimum: Value,
    foliage_type: String,
    radius: IntProvider,
    offset: IntProvider,
    foliage_height: IntProvider,
}
pub struct PlacementContext<'a> {
    pub top_feature: &'a str,
    pub biome_allowed: &'a mut dyn FnMut(&str, Pos) -> bool,
    budget: usize,
    noise: PerlinSimplexNoise,
}
impl<'a> PlacementContext<'a> {
    pub fn new(top_feature: &'a str, biome_allowed: &'a mut dyn FnMut(&str, Pos) -> bool) -> Self {
        Self {
            top_feature,
            biome_allowed,
            budget: 262144,
            noise: PerlinSimplexNoise::new(&mut Random::new(2345, 2), &[0]).unwrap(),
        }
    }
    fn charge(&mut self) -> Result<()> {
        self.budget = self
            .budget
            .checked_sub(1)
            .ok_or("feature work budget exceeded")?;
        Ok(())
    }
}

impl Placed {
    pub fn deterministic(&self) -> Result<()> {
        self.feature.deterministic()
    }
    pub fn place_transaction(
        &self,
        w: &mut Volume,
        r: &mut Random,
        p: Pos,
        c: &mut PlacementContext<'_>,
    ) -> Result<bool> {
        let saved = r.clone();
        let saved_entropy = w.decoration_entropy.clone();
        w.begin()?;
        let result = self.place(w, r, p, c);
        let finish = w.finish(result.is_ok());
        match (result, finish) {
            (Ok(v), Ok(())) => Ok(v),
            (Err(e), _) | (_, Err(e)) => {
                *r = saved;
                w.decoration_entropy = saved_entropy;
                Err(e)
            }
        }
    }
    pub fn compile(value: &Value, registries: &Value, palette: &mut Palette) -> Result<Self> {
        Self::parse(value, registries, palette, 0)
    }
    fn parse(v: &Value, doc: &Value, p: &mut Palette, depth: usize) -> Result<Self> {
        if depth > 64 {
            return Err("placed feature recursion limit".into());
        }
        if let Some(name) = v.as_str() {
            let v = doc["placed_features"]
                .get(name)
                .ok_or_else(|| format!("missing placed feature {name}"))?;
            return Self::parse(v, doc, p, depth + 1);
        }
        let modifiers: Vec<_> = v["placement"]
            .as_array()
            .ok_or("missing feature placement")?
            .iter()
            .map(|v| Modifier::parse(v, p))
            .collect::<Result<_>>()?;
        if modifiers.len() > 64 {
            return Err("placement depth budget".into());
        }
        Ok(Self {
            feature: Feature::parse(&v["feature"], doc, p, depth + 1)?,
            modifiers,
        })
    }
    pub fn place(
        &self,
        w: &mut Volume,
        r: &mut Random,
        p: Pos,
        c: &mut PlacementContext<'_>,
    ) -> Result<bool> {
        self.step(0, w, r, p, c)
    }
    fn step(
        &self,
        index: usize,
        w: &mut Volume,
        r: &mut Random,
        mut p: Pos,
        c: &mut PlacementContext<'_>,
    ) -> Result<bool> {
        c.charge()?;
        if index == self.modifiers.len() {
            return self.feature.place(w, r, p, c);
        }
        let next = index + 1;
        let mut repeat = None;
        match &self.modifiers[index] {
            Modifier::Count(n) => repeat = Some(n.sample(r)),
            Modifier::Square => {
                p[0] += r.next_bounded(16);
                p[2] += r.next_bounded(16);
            }
            Modifier::Height(kind) => {
                p[1] = w.heightmap(p[0], p[2], *kind);
                if p[1] <= w.origin[1] {
                    return Ok(false);
                }
            }
            Modifier::WaterDepth(max) => {
                if w.height(p[0], p[2], false) - w.height(p[0], p[2], true) > *max {
                    return Ok(false);
                }
            }
            Modifier::Rarity(chance) => {
                if r.next_float() >= 1.0 / *chance as f32 {
                    return Ok(false);
                }
            }
            Modifier::Offset(xz, y) => {
                let dx = xz.sample(r);
                let dy = y.sample(r);
                let dz = xz.sample(r);
                p = offset(p, dx, dy, dz);
            }
            Modifier::Filter(test) => {
                if !test.test(w, p)? {
                    return Ok(false);
                }
            }
            Modifier::Biome => {
                if !(c.biome_allowed)(c.top_feature, p) {
                    return Ok(false);
                }
            }
            Modifier::NoiseCount(ratio, factor, off) => {
                repeat = Some(
                    ((c.noise
                        .sample(p[0] as f64 / factor, p[2] as f64 / factor, false)
                        + off)
                        * *ratio as f64)
                        .ceil() as i32,
                )
            }
            Modifier::NoiseThreshold(level, below, above) => {
                repeat = Some(
                    if c.noise
                        .sample(p[0] as f64 / 200., p[2] as f64 / 200., false)
                        < *level
                    {
                        *below
                    } else {
                        *above
                    },
                )
            }
        }
        if let Some(count) = repeat {
            if count > 262144 {
                return Err("placement count budget".into());
            }
            let mut result = false;
            for _ in 0..count {
                result |= self.step(next, w, r, p, c)?;
            }
            Ok(result)
        } else {
            self.step(next, w, r, p, c)
        }
    }
}
impl Feature {
    pub fn deterministic(&self) -> Result<()> {
        match self {
            Self::Tree(tree)
                if tree
                    .decorators
                    .iter()
                    .any(|d| matches!(d,Decorator::Beehive(p) if *p>0.)) =>
            {
                Err("beehive may require external shuffle entropy and block-entity context".into())
            }
            Self::Patch(_, _, _, p) => p.deterministic(),
            Self::Selector(list, p) => {
                for (v, _) in list {
                    v.deterministic()?;
                }
                p.deterministic()
            }
            Self::Random(list) => {
                for v in list {
                    v.deterministic()?;
                }
                Ok(())
            }
            Self::Boolean(a, b) => {
                a.deterministic()?;
                b.deterministic()
            }
            _ => Ok(()),
        }
    }
    pub fn compile(value: &Value, doc: &Value, palette: &mut Palette) -> Result<Self> {
        Self::parse(value, doc, palette, 0)
    }
    fn parse(v: &Value, doc: &Value, p: &mut Palette, depth: usize) -> Result<Self> {
        if depth > 64 {
            return Err("configured feature recursion limit".into());
        }
        if let Some(name) = v.as_str() {
            let v = doc["configured_features"]
                .get(name)
                .ok_or_else(|| format!("missing configured feature {name}"))?;
            return Self::parse(v, doc, p, depth + 1);
        }
        let config = &v["config"];
        Ok(match minecraft_type(v)? {
            "simple_block" => Self::Simple(StateProvider::parse(&config["to_place"], p)?),
            "bamboo" => Self::Bamboo(number(config, "probability")? as f32),
            "random_patch" | "flower" | "no_bonemeal_flower" => {
                let tries = config["tries"].as_i64().unwrap_or(128);
                let xz = config["xz_spread"].as_i64().unwrap_or(7);
                let y = config["y_spread"].as_i64().unwrap_or(3);
                if !(0..=262144).contains(&tries)
                    || !(0..=128).contains(&xz)
                    || !(0..=128).contains(&y)
                {
                    return Err("random patch budget".into());
                }
                Self::Patch(
                    tries as i32,
                    xz as i32,
                    y as i32,
                    Box::new(Placed::parse(&config["feature"], doc, p, depth + 1)?),
                )
            }
            "random_selector" => {
                let mut entries = vec![];
                for e in config["features"]
                    .as_array()
                    .ok_or("selector features missing")?
                {
                    entries.push((
                        Placed::parse(&e["feature"], doc, p, depth + 1)?,
                        number(e, "chance")? as f32,
                    ));
                }
                Self::Selector(
                    entries,
                    Box::new(Placed::parse(&config["default"], doc, p, depth + 1)?),
                )
            }
            "simple_random_selector" => {
                let values = config["features"]
                    .as_array()
                    .ok_or("random features missing")?;
                if values.is_empty() {
                    return Err("empty feature selector".into());
                }
                Self::Random(
                    values
                        .iter()
                        .map(|v| Placed::parse(v, doc, p, depth + 1))
                        .collect::<Result<_>>()?,
                )
            }
            "random_boolean_selector" => Self::Boolean(
                Box::new(Placed::parse(&config["feature_true"], doc, p, depth + 1)?),
                Box::new(Placed::parse(&config["feature_false"], doc, p, depth + 1)?),
            ),
            "tree" => Self::Tree(Tree::parse(config, p)?),
            "huge_brown_mushroom" | "huge_red_mushroom" => Self::Mushroom(
                mushroom::Mushroom::parse(config, minecraft_type(v)? == "huge_red_mushroom", p)?,
            ),
            "no_op" => Self::None,
            s => return Err(format!("unsupported configured feature minecraft:{s}")),
        })
    }
    pub fn place(
        &self,
        w: &mut Volume,
        r: &mut Random,
        p: Pos,
        c: &mut PlacementContext<'_>,
    ) -> Result<bool> {
        c.charge()?;
        match self {
            Self::None => Ok(true),
            Self::Simple(provider) => {
                let id = provider.state(r, p, &mut w.palette)?;
                if !survives(w, p, id)? {
                    return Ok(false);
                }
                if w.palette.definition(id).double_plant {
                    let above = offset(p, 0, 1, 0);
                    let state = w.get(above);
                    if !w.palette.is_air(state) {
                        return Ok(false);
                    }
                    let lower = w.palette.with(id, "half", "lower")?;
                    let upper = w.palette.with(id, "half", "upper")?;
                    w.set(p, lower);
                    w.set(above, upper);
                } else {
                    w.set(p, id)
                }
                Ok(true)
            }
            Self::Bamboo(probability) => bamboo(w, r, p, *probability),
            Self::Patch(tries, xz, y, placed) => {
                let mut result = false;
                for _ in 0..*tries {
                    let q = offset(
                        p,
                        r.next_bounded(xz + 1) - r.next_bounded(xz + 1),
                        r.next_bounded(y + 1) - r.next_bounded(y + 1),
                        r.next_bounded(xz + 1) - r.next_bounded(xz + 1),
                    );
                    result |= placed.place(w, r, q, c)?;
                }
                Ok(result)
            }
            Self::Selector(entries, default) => {
                for (feature, chance) in entries {
                    if r.next_float() < *chance {
                        return feature.place(w, r, p, c);
                    }
                }
                default.place(w, r, p, c)
            }
            Self::Random(features) => {
                features[r.next_bounded(features.len() as i32) as usize].place(w, r, p, c)
            }
            Self::Boolean(yes, no) => {
                if r.next_bool() {
                    yes.place(w, r, p, c)
                } else {
                    no.place(w, r, p, c)
                }
            }
            Self::Tree(tree) => tree.place(w, r, p),
            Self::Mushroom(m) => m.place(w, r, p),
        }
    }
    /// An incomplete halo or an unsupported survival hook rolls back writes
    /// and the random stream. A retry must see exactly the same initial state.
    pub fn place_transaction(
        &self,
        w: &mut Volume,
        r: &mut Random,
        p: Pos,
        c: &mut PlacementContext<'_>,
    ) -> Result<bool> {
        let saved = r.clone();
        let saved_entropy = w.decoration_entropy.clone();
        w.begin()?;
        let result = self.place(w, r, p, c);
        let finish = w.finish(result.is_ok());
        match (result, finish) {
            (Ok(v), Ok(())) => Ok(v),
            (Err(e), _) | (_, Err(e)) => {
                *r = saved;
                w.decoration_entropy = saved_entropy;
                Err(e)
            }
        }
    }
}
fn survives(w: &mut Volume, p: Pos, id: StateId) -> Result<bool> {
    let below = w.get(offset(p, 0, -1, 0));
    if w.palette.definition(id).double_plant
        && w.palette
            .state(id)
            .properties
            .get("half")
            .is_some_and(|v| v == "upper")
    {
        return Ok(w.palette.state(id).name == w.palette.state(below).name
            && w.palette
                .state(below)
                .properties
                .get("half")
                .is_some_and(|v| v == "lower"));
    }
    let name = w.palette.state(id).name.as_str();
    match name {
        "minecraft:azalea" | "minecraft:flowering_azalea" | "minecraft:mangrove_propagule" => {
            if name == "minecraft:mangrove_propagule"
                && w.palette
                    .state(id)
                    .properties
                    .get("hanging")
                    .is_some_and(|v| v == "true")
            {
                let above = w.get(offset(p, 0, 1, 0));
                return Ok(w.palette.is(above, "minecraft:mangrove_leaves"));
            }
            Ok(w.palette.tag(below, "minecraft:dirt")
                || w.palette.is(below, "minecraft:farmland")
                || w.palette.is(below, "minecraft:clay"))
        }
        "minecraft:bamboo" => {
            let at = w.get(p);
            Ok(!w.palette.fluid(at) && w.palette.tag(below, "minecraft:bamboo_plantable_on"))
        }
        "minecraft:short_grass"
        | "minecraft:oak_sapling"
        | "minecraft:spruce_sapling"
        | "minecraft:birch_sapling"
        | "minecraft:jungle_sapling"
        | "minecraft:acacia_sapling"
        | "minecraft:dark_oak_sapling"
        | "minecraft:cherry_sapling"
        | "minecraft:fern"
        | "minecraft:tall_grass"
        | "minecraft:large_fern"
        | "minecraft:dandelion"
        | "minecraft:poppy"
        | "minecraft:blue_orchid"
        | "minecraft:allium"
        | "minecraft:azure_bluet"
        | "minecraft:red_tulip"
        | "minecraft:orange_tulip"
        | "minecraft:white_tulip"
        | "minecraft:pink_tulip"
        | "minecraft:oxeye_daisy"
        | "minecraft:cornflower"
        | "minecraft:lily_of_the_valley"
        | "minecraft:sunflower"
        | "minecraft:lilac"
        | "minecraft:rose_bush"
        | "minecraft:peony"
        | "minecraft:pink_petals" => {
            Ok(w.palette.tag(below, "minecraft:dirt") || w.palette.is(below, "minecraft:farmland"))
        }
        "minecraft:wither_rose" => Ok(w.palette.tag(below, "minecraft:dirt")
            || [
                "minecraft:farmland",
                "minecraft:netherrack",
                "minecraft:soul_sand",
                "minecraft:soul_soil",
            ]
            .iter()
            .any(|b| w.palette.is(below, b))),
        _ => Err(format!("unsupported block survival hook {name}")),
    }
}
fn bamboo(w: &mut Volume, r: &mut Random, p: Pos, chance: f32) -> Result<bool> {
    let at = w.get(p);
    if !w.palette.is_air(at) {
        return Ok(false);
    }
    let base = w.palette.named("minecraft:bamboo")?;
    if survives(w, p, base)? {
        let height = r.next_bounded(12) + 5;
        if r.next_float() < chance {
            let radius = r.next_bounded(4) + 1;
            let podzol = w.palette.named("minecraft:podzol")?;
            for x in p[0] - radius..=p[0] + radius {
                for z in p[2] - radius..=p[2] + radius {
                    if (x - p[0]).pow(2) + (z - p[2]).pow(2) <= radius * radius {
                        let q = [x, w.height(x, z, false) - 1, z];
                        let b = w.get(q);
                        if w.palette.tag(b, "minecraft:dirt") {
                            w.set(q, podzol);
                        }
                    }
                }
            }
        }
        let trunk = w.palette.with(base, "age", "1")?;
        let trunk = w.palette.with(trunk, "leaves", "none")?;
        let trunk = w.palette.with(trunk, "stage", "0")?;
        let mut q = p;
        for _ in 0..height {
            let b = w.get(q);
            if !w.palette.is_air(b) {
                break;
            }
            w.set(q, trunk);
            q[1] += 1;
        }
        if q[1] - p[1] >= 3 {
            let large = w.palette.with(trunk, "leaves", "large")?;
            let final_large = w.palette.with(large, "stage", "1")?;
            let small = w.palette.with(trunk, "leaves", "small")?;
            w.set(q, final_large);
            w.set(offset(q, 0, -1, 0), large);
            w.set(offset(q, 0, -2, 0), small);
        }
    }
    Ok(true)
}
impl Tree {
    fn parse(v: &Value, p: &mut Palette) -> Result<Self> {
        let trunk = &v["trunk_placer"];
        if ![
            "straight_trunk_placer",
            "forking_trunk_placer",
            "bending_trunk_placer",
            "fancy_trunk_placer",
            "dark_oak_trunk_placer",
            "giant_trunk_placer",
            "mega_jungle_trunk_placer",
            "cherry_trunk_placer",
            "upwards_branching_trunk_placer",
        ]
        .contains(&minecraft_type(trunk)?)
        {
            return Err(format!(
                "unsupported trunk placer {}",
                string(trunk, "type")?
            ));
        }
        let roots = v
            .get("root_placer")
            .filter(|v| !v.is_null())
            .map(|v| Roots::parse(v, p))
            .transpose()?;
        let decorators = v["decorators"]
            .as_array()
            .ok_or("missing tree decorators")?
            .iter()
            .map(|v| Decorator::parse(v, p))
            .collect::<Result<Vec<_>>>()?;
        let f = &v["foliage_placer"];
        let kind = minecraft_type(f)?;
        if ![
            "blob_foliage_placer",
            "pine_foliage_placer",
            "spruce_foliage_placer",
            "acacia_foliage_placer",
            "bush_foliage_placer",
            "random_spread_foliage_placer",
            "fancy_foliage_placer",
            "dark_oak_foliage_placer",
            "mega_pine_foliage_placer",
            "jungle_foliage_placer",
            "cherry_foliage_placer",
        ]
        .contains(&kind)
        {
            return Err(format!("unsupported foliage placer {kind}"));
        }
        let minimum = v["minimum_size"].clone();
        if !["two_layers_feature_size", "three_layers_feature_size"]
            .contains(&minecraft_type(&minimum)?)
        {
            return Err("unsupported tree size".into());
        }
        let height = [
            integer(trunk, "base_height")?,
            integer(trunk, "height_rand_a")?,
            integer(trunk, "height_rand_b")?,
        ];
        if height.iter().any(|v| !(0..=64).contains(v)) {
            return Err("tree height budget".into());
        }
        Ok(Self {
            decorators,
            branches: Branches::parse(trunk)?,
            roots,
            cherry_chances: if kind == "cherry_foliage_placer" {
                [
                    number(f, "wide_bottom_layer_hole_chance")? as f32,
                    number(f, "corner_hole_chance")? as f32,
                    number(f, "hanging_leaves_chance")? as f32,
                    number(f, "hanging_leaves_extension_chance")? as f32,
                ]
            } else {
                [0.; 4]
            },
            trunk_type: minecraft_type(trunk)?.into(),
            bend_length: if minecraft_type(trunk)? == "bending_trunk_placer" {
                IntProvider::parse(&trunk["bend_length"])?
            } else {
                IntProvider::Constant(0)
            },
            min_height_for_leaves: trunk["min_height_for_leaves"].as_i64().unwrap_or(1) as i32,
            leaf_attempts: f["leaf_placement_attempts"]
                .as_i64()
                .unwrap_or(0)
                .clamp(0, 256) as i32,
            height,
            trunk: StateProvider::parse(&v["trunk_provider"], p)?,
            foliage: StateProvider::parse(&v["foliage_provider"], p)?,
            dirt: StateProvider::parse(&v["dirt_provider"], p)?,
            force_dirt: v["force_dirt"].as_bool().unwrap_or(false),
            ignore_vines: v["ignore_vines"].as_bool().unwrap_or(false),
            minimum,
            foliage_type: kind.into(),
            radius: IntProvider::parse(&f["radius"])?,
            offset: IntProvider::parse(&f["offset"])?,
            foliage_height: if kind == "acacia_foliage_placer" {
                IntProvider::Constant(0)
            } else if kind == "dark_oak_foliage_placer" {
                IntProvider::Constant(4)
            } else {
                IntProvider::parse(if kind == "spruce_foliage_placer" {
                    &f["trunk_height"]
                } else if kind == "random_spread_foliage_placer" {
                    &f["foliage_height"]
                } else if kind == "mega_pine_foliage_placer" {
                    &f["crown_height"]
                } else {
                    &f["height"]
                })?
            },
        })
    }
    fn size(&self, height: i32, y: i32) -> i32 {
        let v = &self.minimum;
        let n = |key: &str, d: i32| v[key].as_i64().unwrap_or(d as i64) as i32;
        if y < n("limit", 1) {
            n("lower_size", 0)
        } else if v["type"] == "minecraft:three_layers_feature_size" {
            if y >= height - n("upper_limit", 1) {
                n("upper_size", 1)
            } else {
                n("middle_size", 1)
            }
        } else {
            n("upper_size", 1)
        }
    }
    fn place(&self, w: &mut Volume, r: &mut Random, p: Pos) -> Result<bool> {
        let height = self.height[0]
            + r.next_bounded(self.height[1] + 1)
            + r.next_bounded(self.height[2] + 1);
        let mut fh = self.foliage_height.sample(r);
        if self.foliage_type == "spruce_foliage_placer" {
            fh = (height - fh).max(4)
        }
        let mut radius = self.radius.sample(r);
        if self.foliage_type == "pine_foliage_placer" {
            radius += r.next_bounded((height - fh + 1).max(1));
        }
        if !(0..=64).contains(&fh) || !(0..=32).contains(&radius) {
            return Err("foliage work budget".into());
        }
        let base = p;
        let p = if let Some(root) = &self.roots {
            let off = root.offset.sample(r);
            if !(-64..=64).contains(&off) {
                return Err("root offset budget".into());
            }
            offset(p, 0, off, 0)
        } else {
            p
        };
        if p[1].min(base[1]) < w.origin[1] + 1
            || p[1].max(base[1]) + height + 1 > w.origin[1] + w.size[1] as i32
        {
            return Ok(false);
        }
        let mut free = height;
        'check: for y in 0..=height + 1 {
            let size = self.size(height, y);
            if !(0..=32).contains(&size) {
                return Err("tree clearance budget".into());
            }
            for dx in -size..=size {
                for dz in -size..=size {
                    let b = w.get(offset(p, dx, y, dz));
                    if !(self.valid_trunk(&w.palette, b) || w.palette.tag(b, "minecraft:logs"))
                        || (!self.ignore_vines && w.palette.is(b, "minecraft:vine"))
                    {
                        free = y - 2;
                        break 'check;
                    }
                }
            }
        }
        if free < height
            && !self.minimum["min_clipped_height"]
                .as_i64()
                .is_some_and(|h| free >= h as i32)
        {
            return Ok(false);
        }
        let mut logs = vec![];
        let mut leaves = vec![];
        let mut roots = vec![];
        if let Some(root) = &self.roots {
            if !root.place(w, r, base, p, &mut roots)? {
                return Ok(false);
            }
        }
        let attachments = self.trunks(w, r, p, free, &mut logs)?;
        for (top, radius_offset, double) in attachments {
            let off = self.offset.sample(r);
            if !(-32..=32).contains(&off) {
                return Err("foliage offset budget".into());
            }
            match self.foliage_type.as_str() {
                "cherry_foliage_placer" => self.cherry_foliage(
                    w,
                    r,
                    offset(top, 0, off, 0),
                    radius + radius_offset - 1,
                    fh,
                    double,
                    &mut leaves,
                )?,
                "jungle_foliage_placer" => {
                    let h = if double { fh } else { 1 + r.next_bounded(2) };
                    for y in (off - h..=off).rev() {
                        self.row_wide(
                            w,
                            r,
                            top,
                            radius + radius_offset + 1 - y,
                            y,
                            false,
                            double,
                            &mut leaves,
                        )?;
                    }
                }
                "mega_pine_foliage_placer" => {
                    let mut prev = 0;
                    for y in top[1] - fh + off..=top[1] + off {
                        let k = top[1] - y;
                        let size =
                            radius + radius_offset + (k as f32 / fh as f32 * 3.5).floor() as i32;
                        let actual = size + i32::from(k > 0 && size == prev && y & 1 == 0);
                        self.row_wide(
                            w,
                            r,
                            [top[0], y, top[2]],
                            actual,
                            0,
                            false,
                            double,
                            &mut leaves,
                        )?;
                        prev = size;
                    }
                }
                "fancy_foliage_placer" => {
                    for y in (off - fh..=off).rev() {
                        let size = radius + i32::from(y != off && y != off - fh);
                        self.row_wide(w, r, top, size, y, false, double, &mut leaves)?;
                    }
                }
                "dark_oak_foliage_placer" => {
                    let top = offset(top, 0, off, 0);
                    self.row_wide(w, r, top, radius + 2, -1, false, double, &mut leaves)?;
                    self.row_wide(
                        w,
                        r,
                        top,
                        radius + if double { 3 } else { 1 },
                        0,
                        false,
                        double,
                        &mut leaves,
                    )?;
                    if double {
                        self.row_wide(w, r, top, radius + 2, 1, false, true, &mut leaves)?;
                        if r.next_bool() {
                            self.row_wide(w, r, top, radius, 2, false, true, &mut leaves)?;
                        }
                    }
                }
                "blob_foliage_placer" => {
                    for y in (off - fh..=off).rev() {
                        self.row(
                            w,
                            r,
                            top,
                            (radius + radius_offset - 1 - y / 2).max(0),
                            y,
                            true,
                            &mut leaves,
                        )?;
                    }
                }
                "bush_foliage_placer" => {
                    for y in (off - fh..=off).rev() {
                        self.row(
                            w,
                            r,
                            top,
                            radius + radius_offset - 1 - y,
                            y,
                            true,
                            &mut leaves,
                        )?;
                    }
                }
                "acacia_foliage_placer" => {
                    let top = offset(top, 0, off, 0);
                    self.row(
                        w,
                        r,
                        top,
                        radius + radius_offset,
                        -1 - fh,
                        false,
                        &mut leaves,
                    )?;
                    self.row(w, r, top, radius - 1, -fh, false, &mut leaves)?;
                    self.row(w, r, top, radius + radius_offset - 1, 0, false, &mut leaves)?;
                }
                "random_spread_foliage_placer" => {
                    if radius <= 0 || fh <= 0 {
                        return Err("random spread radius/height must be positive".into());
                    }
                    for _ in 0..self.leaf_attempts {
                        let q = offset(
                            top,
                            r.next_bounded(radius) - r.next_bounded(radius),
                            r.next_bounded(fh) - r.next_bounded(fh),
                            r.next_bounded(radius) - r.next_bounded(radius),
                        );
                        self.leaf(w, r, q, &mut leaves)?;
                    }
                }
                "pine_foliage_placer" => {
                    let mut size = 0;
                    for y in (off - fh..=off).rev() {
                        self.row(w, r, top, size, y, false, &mut leaves)?;
                        if size >= 1 && y == off - fh + 1 {
                            size -= 1
                        } else if size < radius + radius_offset {
                            size += 1
                        }
                    }
                }
                _ => {
                    let mut size = r.next_bounded(2);
                    let mut limit = 1;
                    let mut next = 0;
                    for y in (-fh..=off).rev() {
                        self.row(w, r, top, size, y, false, &mut leaves)?;
                        if size >= limit {
                            size = next;
                            next = 1;
                            limit = (limit + 1).min(radius + radius_offset)
                        } else {
                            size += 1
                        }
                    }
                }
            }
        }
        if logs.is_empty() && leaves.is_empty() {
            return Ok(false);
        }
        let mut decorated = vec![];
        if !self.decorators.is_empty() {
            let ordered_logs = sorted_positions(&logs);
            let ordered_leaves = sorted_positions(&leaves);
            let ordered_roots = sorted_positions(&roots);
            for d in &self.decorators {
                d.place(
                    w,
                    r,
                    &ordered_logs,
                    &ordered_leaves,
                    &ordered_roots,
                    &mut decorated,
                )?;
            }
        }
        decorated.extend(roots);
        update_leaves(w, &logs, &leaves, &decorated)?;
        Ok(true)
    }
    fn dirt_at(&self, w: &mut Volume, r: &mut Random, p: Pos, logs: &mut Vec<Pos>) -> Result<()> {
        let soil = w.get(p);
        if self.force_dirt
            || !w.palette.tag(soil, "minecraft:dirt")
            || w.palette.is(soil, "minecraft:grass_block")
            || w.palette.is(soil, "minecraft:mycelium")
        {
            let state = self.dirt.state(r, p, &mut w.palette)?;
            w.set(p, state);
            logs.push(p);
        }
        Ok(())
    }
    fn log_at(&self, w: &mut Volume, r: &mut Random, p: Pos, logs: &mut Vec<Pos>) -> Result<bool> {
        let b = w.get(p);
        if !self.valid_trunk(&w.palette, b) {
            return Ok(false);
        }
        let state = self.trunk.state(r, p, &mut w.palette)?;
        w.set(p, state);
        logs.push(p);
        Ok(true)
    }
    fn valid_trunk(&self, p: &Palette, b: StateId) -> bool {
        p.tree_replaceable(b)
            || matches!(&self.branches,Branches::Upwards{through,..} if through.contains(p,b))
    }
    fn trunks(
        &self,
        w: &mut Volume,
        r: &mut Random,
        p: Pos,
        height: i32,
        logs: &mut Vec<Pos>,
    ) -> Result<Vec<(Pos, i32, bool)>> {
        const DIRECTIONS: [[i32; 2]; 4] = [[0, -1], [1, 0], [0, 1], [-1, 0]];
        let mut result = vec![];
        match self.trunk_type.as_str() {
            "cherry_trunk_placer" | "upwards_branching_trunk_placer" => {
                return self.branch_trunks(w, r, p, height, logs)
            }
            "giant_trunk_placer" | "mega_jungle_trunk_placer" => {
                for [x, z] in [[0, 0], [1, 0], [0, 1], [1, 1]] {
                    self.dirt_at(w, r, offset(p, x, -1, z), logs)?;
                }
                for y in 0..height {
                    self.log_at(w, r, offset(p, 0, y, 0), logs)?;
                    if y < height - 1 {
                        for [x, z] in [[1, 0], [1, 1], [0, 1]] {
                            self.log_at(w, r, offset(p, x, y, z), logs)?;
                        }
                    }
                }
                result.push((offset(p, 0, height, 0), 0, true));
                if self.trunk_type == "mega_jungle_trunk_placer" {
                    let mut y = height - 2 - r.next_bounded(4);
                    while y > height / 2 {
                        let angle = r.next_float() * (std::f64::consts::PI * 2.) as f32;
                        let mut x = 0;
                        let mut z = 0;
                        for length in 0..5 {
                            x = (1.5f32 + mth_trig(angle, true) * length as f32) as i32;
                            z = (1.5f32 + mth_trig(angle, false) * length as f32) as i32;
                            self.log_at(w, r, offset(p, x, y - 3 + length / 2, z), logs)?;
                        }
                        result.push((offset(p, x, y, z), -2, false));
                        y -= 2 + r.next_bounded(4);
                    }
                }
            }
            "straight_trunk_placer" => {
                self.dirt_at(w, r, offset(p, 0, -1, 0), logs)?;
                for y in 0..height {
                    self.log_at(w, r, offset(p, 0, y, 0), logs)?;
                }
                result.push((offset(p, 0, height, 0), 0, false));
            }
            "forking_trunk_placer" => {
                self.dirt_at(w, r, offset(p, 0, -1, 0), logs)?;
                let direction = r.next_bounded(4) as usize;
                let [dx, dz] = DIRECTIONS[direction];
                let bend = height - r.next_bounded(4) - 1;
                let mut amount = 3 - r.next_bounded(3);
                let mut x = p[0];
                let mut z = p[2];
                let mut top = None;
                for y in 0..height {
                    if y >= bend && amount > 0 {
                        x += dx;
                        z += dz;
                        amount -= 1;
                    }
                    if self.log_at(w, r, [x, p[1] + y, z], logs)? {
                        top = Some(p[1] + y + 1);
                    }
                }
                if let Some(y) = top {
                    result.push(([x, y, z], 1, false));
                }
                x = p[0];
                z = p[2];
                let other = r.next_bounded(4) as usize;
                if other != direction {
                    let [dx, dz] = DIRECTIONS[other];
                    let start = bend - r.next_bounded(2) - 1;
                    let mut amount = 1 + r.next_bounded(3);
                    let mut top = None;
                    for y in start..height {
                        if amount <= 0 {
                            break;
                        }
                        amount -= 1;
                        if y >= 1 {
                            x += dx;
                            z += dz;
                            if self.log_at(w, r, [x, p[1] + y, z], logs)? {
                                top = Some(p[1] + y + 1);
                            }
                        }
                    }
                    if let Some(y) = top {
                        result.push(([x, y, z], 0, false));
                    }
                }
            }
            "bending_trunk_placer" => {
                let [dx, dz] = DIRECTIONS[r.next_bounded(4) as usize];
                let mut q = p;
                self.dirt_at(w, r, offset(p, 0, -1, 0), logs)?;
                for y in 0..height {
                    if y + 1 >= height - 1 + r.next_bounded(2) {
                        q[0] += dx;
                        q[2] += dz;
                    }
                    self.log_at(w, r, q, logs)?;
                    if y >= self.min_height_for_leaves {
                        result.push((q, 0, false));
                    }
                    q[1] += 1;
                }
                let length = self.bend_length.sample(r);
                if !(0..=32).contains(&length) {
                    return Err("bend length budget".into());
                }
                for _ in 0..=length {
                    self.log_at(w, r, q, logs)?;
                    result.push((q, 0, false));
                    q[0] += dx;
                    q[2] += dz;
                }
            }
            "fancy_trunk_placer" => {
                let total = height + 2;
                let trunk_height = (total as f64 * 0.618).floor() as i32;
                self.dirt_at(w, r, offset(p, 0, -1, 0), logs)?;
                let candidates = 1.min((1.382 + (total as f64 / 13.).powi(2)).floor() as i32);
                let max_base = p[1] + trunk_height;
                let mut branches = vec![(offset(p, 0, total - 5, 0), max_base)];
                for y in (0..=total - 5).rev() {
                    if (y as f32) < total as f32 * 0.3 {
                        continue;
                    }
                    let half = total as f32 / 2.;
                    let delta = half - y as f32;
                    let shape = if delta == 0. {
                        half
                    } else if delta.abs() >= half {
                        0.
                    } else {
                        (half * half - delta * delta).sqrt()
                    } * 0.5;
                    for _ in 0..candidates {
                        let length = shape as f64 * (r.next_float() as f64 + 0.328);
                        let angle = (r.next_float() * 2.) as f64 * std::f64::consts::PI;
                        let q = offset(
                            p,
                            (length * angle.sin() + 0.5).floor() as i32,
                            y - 1,
                            (length * angle.cos() + 0.5).floor() as i32,
                        );
                        if self.limb(w, r, q, offset(q, 0, 5, 0), false, logs)? {
                            let dx = p[0] - q[0];
                            let dz = p[2] - q[2];
                            let base_y = (q[1] as f64 - ((dx * dx + dz * dz) as f64).sqrt() * 0.381)
                                .min(max_base as f64)
                                as i32;
                            if self.limb(w, r, [p[0], base_y, p[2]], q, false, logs)? {
                                branches.push((q, base_y));
                            }
                        }
                    }
                }
                self.limb(w, r, p, offset(p, 0, trunk_height, 0), true, logs)?;
                for &(q, base_y) in &branches {
                    let base = [p[0], base_y, p[2]];
                    if base != q && (base_y - p[1]) as f64 >= total as f64 * 0.2 {
                        self.limb(w, r, base, q, true, logs)?;
                    }
                }
                for (q, base_y) in branches {
                    if (base_y - p[1]) as f64 >= total as f64 * 0.2 {
                        result.push((q, 0, false));
                    }
                }
            }
            "dark_oak_trunk_placer" => {
                for [x, z] in [[0, 0], [1, 0], [0, 1], [1, 1]] {
                    self.dirt_at(w, r, offset(p, x, -1, z), logs)?;
                }
                let [dx, dz] = DIRECTIONS[r.next_bounded(4) as usize];
                let bend = height - r.next_bounded(4);
                let mut amount = 2 - r.next_bounded(3);
                let mut x = p[0];
                let mut z = p[2];
                let top = p[1] + height - 1;
                for y in 0..height {
                    if y >= bend && amount > 0 {
                        x += dx;
                        z += dz;
                        amount -= 1;
                    }
                    let q = [x, p[1] + y, z];
                    let b = w.get(q);
                    if w.palette.is_air(b) || w.palette.tag(b, "minecraft:leaves") {
                        for [ox, oz] in [[0, 0], [1, 0], [0, 1], [1, 1]] {
                            self.log_at(w, r, offset(q, ox, 0, oz), logs)?;
                        }
                    }
                }
                result.push(([x, top, z], 0, true));
                for ox in -1..=2 {
                    for oz in -1..=2 {
                        if (ox < 0 || ox > 1 || oz < 0 || oz > 1) && r.next_bounded(3) <= 0 {
                            let len = r.next_bounded(3) + 2;
                            for y in 0..len {
                                self.log_at(w, r, [p[0] + ox, top - y - 1, p[2] + oz], logs)?;
                            }
                            result.push(([x + ox, top, z + oz], 0, false));
                        }
                    }
                }
            }
            _ => return Err("unsupported trunk type".into()),
        }
        Ok(result)
    }
    fn limb(
        &self,
        w: &mut Volume,
        r: &mut Random,
        a: Pos,
        b: Pos,
        write: bool,
        logs: &mut Vec<Pos>,
    ) -> Result<bool> {
        if !write && a == b {
            return Ok(true);
        }
        let d: Pos = std::array::from_fn(|i| b[i] - a[i]);
        let steps = d.iter().map(|v| v.abs()).max().unwrap();
        for j in 0..=steps {
            let q = std::array::from_fn(|i| {
                a[i] + (0.5f32 + j as f32 * (d[i] as f32 / steps as f32)).floor() as i32
            });
            let block = w.get(q);
            if write {
                if w.palette.tree_replaceable(block) {
                    let mut state = self.trunk.state(r, q, &mut w.palette)?;
                    let x = (q[0] - a[0]).abs();
                    let z = (q[2] - a[2]).abs();
                    if w.palette.state(state).properties.contains_key("axis") {
                        state = w.palette.with(
                            state,
                            "axis",
                            if x.max(z) == 0 {
                                "y"
                            } else if x >= z {
                                "x"
                            } else {
                                "z"
                            },
                        )?;
                    }
                    w.set(q, state);
                    logs.push(q);
                }
            } else if !w.palette.tree_replaceable(block) && !w.palette.tag(block, "minecraft:logs")
            {
                return Ok(false);
            }
        }
        Ok(true)
    }
    fn leaf(&self, w: &mut Volume, r: &mut Random, q: Pos, leaves: &mut Vec<Pos>) -> Result<()> {
        let b = w.get(q);
        if w.palette.tree_replaceable(b) {
            let mut state = self.foliage.state(r, q, &mut w.palette)?;
            if w.palette
                .state(state)
                .properties
                .contains_key("waterlogged")
            {
                let water = w.palette.source_water(b);
                state =
                    w.palette
                        .with(state, "waterlogged", if water { "true" } else { "false" })?;
            }
            w.set(q, state);
            leaves.push(q);
        }
        Ok(())
    }
    fn row(
        &self,
        w: &mut Volume,
        r: &mut Random,
        top: Pos,
        radius: i32,
        y: i32,
        blob: bool,
        leaves: &mut Vec<Pos>,
    ) -> Result<()> {
        self.row_wide(w, r, top, radius, y, blob, false, leaves)
    }
    fn row_wide(
        &self,
        w: &mut Volume,
        r: &mut Random,
        top: Pos,
        radius: i32,
        y: i32,
        blob: bool,
        double: bool,
        leaves: &mut Vec<Pos>,
    ) -> Result<()> {
        for sx in -radius..=radius + i32::from(double) {
            for sz in -radius..=radius + i32::from(double) {
                let x = if double {
                    sx.abs().min((sx - 1).abs())
                } else {
                    sx.abs()
                };
                let z = if double {
                    sz.abs().min((sz - 1).abs())
                } else {
                    sz.abs()
                };
                let corner = x.abs() == radius && z.abs() == radius;
                if self.foliage_type == "cherry_foliage_placer" {
                    let skip = if y == -1
                        && (x == radius || z == radius)
                        && r.next_float() < self.cherry_chances[0]
                    {
                        true
                    } else if radius > 2 {
                        corner
                            || (x + z > radius * 2 - 2 && r.next_float() < self.cherry_chances[1])
                    } else {
                        corner && r.next_float() < self.cherry_chances[1]
                    };
                    if !skip {
                        self.leaf(w, r, offset(top, sx, y, sz), leaves)?;
                    }
                    continue;
                }
                if self.foliage_type == "mega_pine_foliage_placer"
                    || self.foliage_type == "jungle_foliage_placer"
                {
                    if x + z < 7 && x * x + z * z <= radius * radius {
                        self.leaf(w, r, offset(top, sx, y, sz), leaves)?;
                    }
                    continue;
                }
                if self.foliage_type == "fancy_foliage_placer"
                    || self.foliage_type == "dark_oak_foliage_placer"
                {
                    let skip = if self.foliage_type == "fancy_foliage_placer" {
                        (x as f32 + 0.5).powi(2) + (z as f32 + 0.5).powi(2)
                            > (radius * radius) as f32
                    } else {
                        (y == 0
                            && double
                            && (sx == -radius || sx >= radius)
                            && (sz == -radius || sz >= radius))
                            || (y == -1 && !double && corner)
                            || (y == 1 && x + z > radius * 2 - 2)
                    };
                    if !skip {
                        self.leaf(w, r, offset(top, sx, y, sz), leaves)?;
                    }
                    continue;
                }
                if self.foliage_type == "acacia_foliage_placer" {
                    if if y == 0 {
                        (x.abs() > 1 || z.abs() > 1) && x != 0 && z != 0
                    } else {
                        corner && radius > 0
                    } {
                        continue;
                    }
                    self.leaf(w, r, offset(top, sx, y, sz), leaves)?;
                    continue;
                }
                if corner
                    && if blob {
                        r.next_bounded(2) == 0
                            || (y == 0 && self.foliage_type != "bush_foliage_placer")
                    } else {
                        radius > 0
                    }
                {
                    continue;
                }
                let q = offset(top, sx, y, sz);
                let b = w.get(q);
                if w.palette.tree_replaceable(b) {
                    let mut state = self.foliage.state(r, q, &mut w.palette)?;
                    if w.palette
                        .state(state)
                        .properties
                        .contains_key("waterlogged")
                    {
                        let water = w.palette.source_water(b);
                        state = w.palette.with(
                            state,
                            "waterlogged",
                            if water { "true" } else { "false" },
                        )?;
                    }
                    w.set(q, state);
                    leaves.push(q);
                }
            }
        }
        Ok(())
    }
}
fn update_leaves(w: &mut Volume, logs: &[Pos], leaves: &[Pos], decorated: &[Pos]) -> Result<()> {
    let mut min = [i32::MAX; 3];
    let mut max = [i32::MIN; 3];
    for p in logs.iter().chain(leaves).chain(decorated) {
        for i in 0..3 {
            min[i] = min[i].min(p[i]);
            max[i] = max[i].max(p[i]);
        }
    }
    // Vanilla can enqueue one position into several distance buckets. It
    // overwrites its distance again when a later bucket is drained. Preserve
    // this observable order rather than replacing it with shortest-path BFS.
    let mut original = JavaPosSet::new();
    for &p in logs {
        original.insert(p);
    }
    let mut buckets: Vec<_> = (0..7).map(|_| JavaPosSet::new()).collect();
    for bucket in original.buckets {
        for p in bucket {
            buckets[0].insert(p);
        }
    }
    let mut filled: HashSet<Pos> = decorated.iter().copied().collect();
    let mut d = 0;
    while d < 7 {
        let Some(p) = buckets[d].pop_first() else {
            d += 1;
            continue;
        };
        if (0..3).any(|i| p[i] < min[i] || p[i] > max[i]) {
            continue;
        }
        if d != 0 {
            let b = w.get(p);
            let state = w.palette.with(b, "distance", &d.to_string())?;
            w.set(p, state);
        }
        filled.insert(p);
        for dir in [
            [0, -1, 0],
            [0, 1, 0],
            [0, 0, -1],
            [0, 0, 1],
            [-1, 0, 0],
            [1, 0, 0],
        ] {
            let q = offset(p, dir[0], dir[1], dir[2]);
            if (0..3).any(|i| q[i] < min[i] || q[i] > max[i]) || filled.contains(&q) {
                continue;
            }
            let b = w.get(q);
            let distance = if w.palette.tag(b, "minecraft:logs") {
                Some(0)
            } else {
                w.palette
                    .state(b)
                    .properties
                    .get("distance")
                    .and_then(|v| v.parse::<usize>().ok())
            };
            if let Some(distance) = distance {
                let next = distance.min(d + 1);
                if next < 7 {
                    buckets[next].insert(q);
                    d = d.min(next);
                }
            }
        }
    }
    shape::update_edges(w, &filled, min, max)
}
fn mth_trig(angle: f32, cosine: bool) -> f32 {
    static TABLE: std::sync::OnceLock<Vec<f32>> = std::sync::OnceLock::new();
    let table = TABLE.get_or_init(|| {
        (0..65536)
            .map(|i| (i as f64 * std::f64::consts::PI * 2. / 65536.).sin() as f32)
            .collect()
    });
    table[((angle * 10430.378 + if cosine { 16384. } else { 0. }) as i32 & 65535) as usize]
}
struct JavaPosSet {
    buckets: Vec<Vec<Pos>>,
    len: usize,
}
impl JavaPosSet {
    fn new() -> Self {
        Self {
            buckets: vec![vec![]; 16],
            len: 0,
        }
    }
    fn hash(p: Pos) -> u32 {
        let h = p[1]
            .wrapping_add(p[2].wrapping_mul(31))
            .wrapping_mul(31)
            .wrapping_add(p[0]) as u32;
        h ^ (h >> 16)
    }
    fn insert(&mut self, p: Pos) {
        let index = Self::hash(p) as usize & (self.buckets.len() - 1);
        if self.buckets[index].contains(&p) {
            return;
        }
        self.buckets[index].push(p);
        self.len += 1;
        if self.len > self.buckets.len() * 3 / 4
            || (self.buckets[index].len() >= 9 && self.buckets.len() < 64)
        {
            let size = self.buckets.len() * 2;
            let old = std::mem::replace(&mut self.buckets, vec![vec![]; size]);
            for bucket in old {
                for q in bucket {
                    self.buckets[Self::hash(q) as usize & (size - 1)].push(q);
                }
            }
        }
    }
    fn pop_first(&mut self) -> Option<Pos> {
        for bucket in &mut self.buckets {
            if !bucket.is_empty() {
                self.len -= 1;
                return Some(bucket.remove(0));
            }
        }
        None
    }
}
impl Modifier {
    fn parse(v: &Value, p: &mut Palette) -> Result<Self> {
        Ok(match minecraft_type(v)? {
            "count" => Self::Count(IntProvider::parse(&v["count"])?),
            "in_square" => Self::Square,
            "heightmap" => Self::Height(match string(v, "heightmap")? {
                "WORLD_SURFACE" | "WORLD_SURFACE_WG" => 0,
                "OCEAN_FLOOR" | "OCEAN_FLOOR_WG" => 1,
                "MOTION_BLOCKING" => 2,
                "MOTION_BLOCKING_NO_LEAVES" => 3,
                s => return Err(format!("unsupported heightmap {s}")),
            }),
            "surface_water_depth_filter" => Self::WaterDepth(integer(v, "max_water_depth")?),
            "rarity_filter" => {
                let n = integer(v, "chance")?;
                if n <= 0 {
                    return Err("invalid rarity".into());
                }
                Self::Rarity(n)
            }
            "random_offset" => Self::Offset(
                IntProvider::parse(&v["xz_spread"])?,
                IntProvider::parse(&v["y_spread"])?,
            ),
            "block_predicate_filter" => Self::Filter(Predicate::parse(&v["predicate"], p, 0)?),
            "biome" => Self::Biome,
            "noise_based_count" => Self::NoiseCount(
                integer(v, "noise_to_count_ratio")?,
                number(v, "noise_factor")?,
                number(v, "noise_offset")?,
            ),
            "noise_threshold_count" => Self::NoiseThreshold(
                number(v, "noise_level")?,
                integer(v, "below_noise")?,
                integer(v, "above_noise")?,
            ),
            s => return Err(format!("unsupported placement modifier {s}")),
        })
    }
}
impl Predicate {
    fn parse(v: &Value, p: &mut Palette, depth: usize) -> Result<Self> {
        if depth > 64 {
            return Err("block predicate depth budget".into());
        }
        let off = if let Some(v) = v.get("offset") {
            serde_json::from_value(v.clone()).map_err(|_| "invalid predicate offset")?
        } else {
            [0; 3]
        };
        Ok(match minecraft_type(v)? {
            "true" => Self::True,
            "all_of" | "any_of" => {
                let values = v["predicates"]
                    .as_array()
                    .ok_or("missing predicates")?
                    .iter()
                    .map(|v| Self::parse(v, p, depth + 1))
                    .collect::<Result<_>>()?;
                if minecraft_type(v)? == "all_of" {
                    Self::All(values)
                } else {
                    Self::Any(values)
                }
            }
            "not" => Self::Not(Box::new(Self::parse(&v["predicate"], p, depth + 1)?)),
            "matching_blocks" => {
                if let Some(s) = v["blocks"].as_str() {
                    if let Some(tag) = s.strip_prefix('#') {
                        Self::Tag(off, tag.into())
                    } else {
                        Self::Blocks(off, vec![s.into()])
                    }
                } else {
                    Self::Blocks(
                        off,
                        serde_json::from_value(v["blocks"].clone())
                            .map_err(|_| "invalid matching blocks")?,
                    )
                }
            }
            "matching_block_tag" => Self::Tag(off, string(v, "tag")?.into()),
            "would_survive" => Self::Survive(off, p.intern(&v["state"])?),
            "solid" => Self::Solid(off),
            s => return Err(format!("unsupported block predicate {s}")),
        })
    }
    fn test(&self, w: &mut Volume, p: Pos) -> Result<bool> {
        Ok(match self {
            Self::True => true,
            Self::All(values) => {
                for v in values {
                    if !v.test(w, p)? {
                        return Ok(false);
                    }
                }
                true
            }
            Self::Any(values) => {
                for v in values {
                    if v.test(w, p)? {
                        return Ok(true);
                    }
                }
                false
            }
            Self::Not(v) => !v.test(w, p)?,
            Self::Blocks(off, names) => {
                let id = w.get(offset(p, off[0], off[1], off[2]));
                names.iter().any(|n| w.palette.is(id, n))
            }
            Self::Tag(off, tag) => {
                let id = w.get(offset(p, off[0], off[1], off[2]));
                w.palette.tag(id, tag)
            }
            Self::Survive(off, state) => survives(w, offset(p, off[0], off[1], off[2]), *state)?,
            Self::Solid(off) => {
                let id = w.get(offset(p, off[0], off[1], off[2]));
                w.palette.definition(id).motion_blocking
            }
        })
    }
}
