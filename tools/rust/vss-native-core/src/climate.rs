//! Minecraft Climate.RTree, including stable sorting and last-result tie rules.
use crate::density::{minecraft_type, string, Graph, Id, Mode, Result, Scratch};
use serde_json::{json, Value};
use std::cmp::Ordering;
use std::collections::HashSet;
use std::sync::atomic::{AtomicUsize, Ordering as AtomicOrdering};
type Space = [[i64; 2]; 7];
#[derive(Clone)]
struct Node {
    space: Space,
    children: Vec<Node>,
    leaf: Option<usize>,
}
impl Node {
    fn group(children: Vec<Node>) -> Self {
        let mut space = [[i64::MAX, i64::MIN]; 7];
        for n in &children {
            for i in 0..7 {
                space[i][0] = space[i][0].min(n.space[i][0]);
                space[i][1] = space[i][1].max(n.space[i][1]);
            }
        }
        Self {
            space,
            children,
            leaf: None,
        }
    }
    fn distance(&self, target: &[i64; 7]) -> i64 {
        self.space
            .iter()
            .zip(target)
            .map(|(&[min, max], &v)| {
                let d = (v - max).max(min - v).max(0);
                d * d
            })
            .sum()
    }
}
pub struct ParameterTree {
    root: Node,
    leaves: Vec<Node>,
    values: Vec<String>,
}
impl ParameterTree {
    pub fn from_json(values: &Value) -> Result<Self> {
        let entries = values
            .as_array()
            .ok_or("biome parameters must be expanded by registry codec")?;
        if entries.is_empty() || entries.len() > 65536 {
            return Err("biome parameter count budget".into());
        }
        let mut leaves = vec![];
        let mut names = vec![];
        for entry in entries {
            let p = &entry["parameters"];
            let mut space = [[0; 2]; 7];
            for (i, key) in [
                "temperature",
                "humidity",
                "continentalness",
                "erosion",
                "depth",
                "weirdness",
                "offset",
            ]
            .iter()
            .enumerate()
            {
                let v = &p[*key];
                let read = |v: &Value| -> Result<i64> {
                    let f = v.as_f64().ok_or("invalid climate range")?;
                    if !(-4.0..=4.0).contains(&f) {
                        return Err("climate parameter out of range".into());
                    }
                    Ok(quantize(f as f32))
                };
                space[i] = if v.is_number() {
                    let q = read(v)?;
                    [q, q]
                } else {
                    let a = read(&v[0])?;
                    let b = read(&v[1])?;
                    if a > b {
                        return Err("inverted climate interval".into());
                    }
                    [a, b]
                };
            }
            leaves.push(Node {
                space,
                children: vec![],
                leaf: Some(names.len()),
            });
            names.push(string(entry, "biome")?.into());
        }
        let root = build(leaves.clone());
        Ok(Self {
            root,
            leaves,
            values: names,
        })
    }
    pub fn search<'a>(&'a self, target: [i64; 7], last: &mut Option<usize>) -> &'a str {
        let mut best = last.filter(|i| *i < self.leaves.len());
        let mut distance = best.map_or(i64::MAX, |i| self.leaves[i].distance(&target));
        search(&self.root, &target, &mut best, &mut distance);
        *last = best;
        &self.values[best.expect("nonempty climate tree")]
    }
}
fn search(node: &Node, target: &[i64; 7], best: &mut Option<usize>, distance: &mut i64) {
    let d = node.distance(target);
    if d >= *distance {
        return;
    }
    if let Some(i) = node.leaf {
        *best = Some(i);
        *distance = d;
    } else {
        for c in &node.children {
            search(c, target, best, distance);
        }
    }
}
fn compare(a: &Node, b: &Node, axis: usize, absolute: bool) -> Ordering {
    for offset in 0..7 {
        let i = (axis + offset) % 7;
        let aa = (a.space[i][0] + a.space[i][1]) / 2;
        let bb = (b.space[i][0] + b.space[i][1]) / 2;
        let cmp = if absolute {
            aa.abs().cmp(&bb.abs())
        } else {
            aa.cmp(&bb)
        };
        if cmp != Ordering::Equal {
            return cmp;
        }
    }
    Ordering::Equal
}
fn build(mut nodes: Vec<Node>) -> Node {
    if nodes.len() == 1 {
        return nodes.pop().unwrap();
    }
    if nodes.len() <= 6 {
        nodes.sort_by_key(|n| {
            n.space
                .iter()
                .map(|p| ((p[0] + p[1]) / 2).abs())
                .sum::<i64>()
        });
        return Node::group(nodes);
    }
    let mut size = 1;
    while size * 6 < nodes.len() {
        size *= 6;
    }
    let mut best_cost = i64::MAX;
    let mut best = vec![];
    let mut best_axis = 0;
    for axis in 0..7 {
        nodes.sort_by(|a, b| compare(a, b, axis, false));
        let buckets: Vec<_> = nodes
            .chunks(size)
            .map(|c| Node::group(c.to_vec()))
            .collect();
        let cost = buckets
            .iter()
            .flat_map(|n| n.space)
            .map(|p| (p[1] - p[0]).abs())
            .sum();
        if cost < best_cost {
            best_cost = cost;
            best = buckets;
            best_axis = axis;
        }
    }
    best.sort_by(|a, b| compare(a, b, best_axis, true));
    Node::group(best.into_iter().map(|n| build(n.children)).collect())
}
pub fn quantize(v: f32) -> i64 {
    (v * 10000.) as i64
}
pub enum BiomeSource {
    Fixed(String),
    Multi(ParameterTree, [Id; 6]),
    /// TerraBlender positional regions: a uniqueness grid selects a region
    /// per quart column, then the region's own climate tree answers.
    TerraBlender {
        base: ParameterTree,
        ids: [Id; 6],
        regions: Vec<Option<ParameterTree>>,
        grid: Grid,
        possible: HashSet<String>,
        region_last: Vec<AtomicUsize>,
    },
    End(Id),
    Checker(Vec<String>, u32),
    /// Blueprint's `blueprint:modded`: wrap a source and route a share of the
    /// world through positional "slices". The slice array, its size and both
    /// seeds are constructor state on the Java side and are not part of the
    /// vanilla codecs, so they arrive through the `vss_blueprint` snapshot
    /// section rather than from `biome_source`.
    Blueprint {
        original: Box<BiomeSource>,
        slices: Vec<BlueprintSlice>,
        total_weight: i32,
        size: i32,
        slices_seed: i64,
        slices_zoom_seed: i64,
    },
}

/// One entry of Blueprint's slice array. Order is significant: `getSlice`
/// accumulates weights positionally, so the snapshot must be replayed in the
/// order the server registered the slices.
pub struct BlueprintSlice {
    weight: i32,
    provider: BlueprintProvider,
}

pub enum BlueprintProvider {
    /// `blueprint:original` — a singleton that forwards to the wrapped source.
    Original,
    /// `blueprint:multi_noise` — a climate tree, exactly like a vanilla
    /// multi-noise source but resolved against this provider's own table.
    MultiNoise(ParameterTree, [Id; 6]),
    /// `blueprint:overlay` — replace the wrapped source's answer when it falls
    /// inside one of the overlay biome sets.
    Overlay(Vec<(HashSet<String>, Box<BiomeSource>)>),
    /// `blueprint:biome_source` — delegate to a nested source outright.
    Source(Box<BiomeSource>),
}

/// The placeholder biome Blueprint returns to mean "this slice does not
/// apply". `ModdedBiomeSource.getSlice` recognises it by identity and retires
/// the slice for the rest of the call.
const ORIGINAL_SOURCE_MARKER: &str = "blueprint:original_source_marker";
/// Recursion guard for nested providers (an overlay may embed another modded
/// source). Mirrors the density graph's depth budget.
const MAX_BLUEPRINT_DEPTH: i32 = 16;
impl BiomeSource {
    pub fn from_document(doc: &Value, g: &Graph, world_seed: i64) -> Result<Self> {
        let v = &doc["biome_source"];
        if doc.get("vss_terrablender").map_or(false, |s| s.is_object()) {
            return Self::terrablender(&doc["vss_terrablender"], v, &doc["possible_biomes"], g, world_seed);
        }
        if doc.get("vss_blueprint").map_or(false, |s| s.is_object()) {
            return Self::blueprint(doc, g, world_seed);
        }
        Ok(match minecraft_type(v)? {
            "fixed" => Self::Fixed(string(v, "biome")?.into()),
            "multi_noise" => Self::Multi(
                ParameterTree::from_json(&v["biomes"])?,
                [
                    g.root("temperature")?,
                    g.root("vegetation")?,
                    g.root("continents")?,
                    g.root("erosion")?,
                    g.root("depth")?,
                    g.root("ridges")?,
                ],
            ),
            "the_end" => Self::End(g.root("erosion")?),
            "checkerboard" => {
                let biomes: Vec<String> = serde_json::from_value(v["biomes"].clone())
                    .map_err(|_| "missing checker biomes")?;
                let scale = v["scale"].as_i64().unwrap_or(2);
                if biomes.is_empty() || !(0..=62).contains(&scale) {
                    return Err("invalid checkerboard".into());
                }
                Self::Checker(biomes, (scale + 2) as u32)
            }
            s => return Err(format!("unsupported biome source {s}")),
        })
    }
    /// Builds the positional-region routing from the server's
    /// `vss_terrablender` snapshot: region index 0 keeps the world's own
    /// multi-noise list, other regions search their captured climate points,
    /// and a winner outside `possible_biomes` (the deferred placeholder or a
    /// biome missing on this client) falls back to the base search exactly
    /// like TerraBlender's `findValuePositional`.
    fn terrablender(section: &Value, v: &Value, possible: &Value, g: &Graph, world_seed: i64) -> Result<Self> {
        if minecraft_type(v)? != "multi_noise" {
            return Err("terrablender routing requires a multi_noise biome source".into());
        }
        let region_size = section["region_size"]
            .as_i64()
            .ok_or("missing terrablender region size")?;
        if !(0..=16).contains(&region_size) {
            return Err("terrablender region size out of range".into());
        }
        let possible: HashSet<String> =
            serde_json::from_value(possible.clone())
                .map_err(|_| "missing possible biomes for terrablender routing")?;
        let entries = section["regions"]
            .as_array()
            .ok_or("missing terrablender regions")?;
        if entries.is_empty() || entries.len() > 256 {
            return Err("terrablender region count budget".into());
        }
        let mut regions = Vec::with_capacity(entries.len());
        let mut weighted = Vec::new();
        let mut region_points = 0usize;
        for (index, entry) in entries.iter().enumerate() {
            if !entry["base"].as_bool().unwrap_or(index == 0) {
                let groups = entry["groups"]
                    .as_array()
                    .ok_or("missing terrablender region groups")?;
                if groups.len() > 4096 {
                    return Err("terrablender region group budget".into());
                }
                let mut expanded: Vec<Value> = Vec::new();
                for group in groups {
                    let biome = group["biome"]
                        .as_str()
                        .ok_or("missing terrablender group biome")?;
                    for flat in group["points"]
                        .as_array()
                        .ok_or("missing terrablender group points")?
                    {
                        let values = flat.as_array().ok_or("terrablender point layout")?;
                        if values.len() != 13 {
                            return Err("terrablender point layout".into());
                        }
                        region_points += 1;
                        if region_points > 65536 {
                            return Err("terrablender region point budget".into());
                        }
                        let mut v = [0f64; 13];
                        for (i, slot) in v.iter_mut().enumerate() {
                            *slot = values[i].as_f64().ok_or("invalid terrablender point")?;
                        }
                        expanded.push(json!({"biome": biome, "parameters": {
                            "temperature": [v[0], v[1]],
                            "humidity": [v[2], v[3]],
                            "continentalness": [v[4], v[5]],
                            "erosion": [v[6], v[7]],
                            "depth": [v[8], v[9]],
                            "weirdness": [v[10], v[11]],
                            "offset": v[12]}}));
                    }
                }
                // Empty snapshots stay unselectable; the uniqueness layer
                // carries the same region only when the server marked it
                // weighted, so an empty tree here cannot be reached anyway.
                regions.push(if expanded.is_empty() {
                    None
                } else {
                    Some(ParameterTree::from_json(&Value::Array(expanded))?)
                });
            } else {
                regions.push(None);
            }
            if entry["weighted"].as_bool().unwrap_or(false) {
                weighted.push((index, entry["weight"].as_i64().unwrap_or(0)));
            }
        }
        if weighted.is_empty() {
            return Err("terrablender uniqueness layer is empty".into());
        }
        Ok(Self::TerraBlender {
            base: ParameterTree::from_json(&v["biomes"])?,
            ids: [
                g.root("temperature")?,
                g.root("vegetation")?,
                g.root("continents")?,
                g.root("erosion")?,
                g.root("depth")?,
                g.root("ridges")?,
            ],
            regions,
            grid: uniqueness_grid(world_seed, region_size, weighted),
            possible,
            region_last: (0..entries.len())
                .map(|_| AtomicUsize::new(usize::MAX))
                .collect(),
        })
    }
    /// Builds the Blueprint backend from the `vss_blueprint` snapshot section.
    /// The section is produced server-side because Blueprint's own codec
    /// serializes only the wrapped source; the slice array, its size and both
    /// positional seeds are constructor state and never reach the codecs.
    fn blueprint(doc: &Value, g: &Graph, world_seed: i64) -> Result<Self> {
        let section = &doc["vss_blueprint"];
        let original = if section.get("original_terrablender").is_some() {
            Self::terrablender(&section["original_terrablender"], &section["original_biome_source"],
                &doc["possible_biomes"], g, world_seed)?
        } else {
            Self::nested(&section["original_biome_source"], g, 0)?
        };
        let raw = section["slices"]
            .as_array()
            .ok_or("missing blueprint slices")?;
        if raw.is_empty() || raw.len() > 256 {
            return Err("blueprint slice budget".into());
        }
        let mut slices = Vec::with_capacity(raw.len());
        let mut total_weight: i32 = 0;
        for entry in raw {
            let slice = &entry["slice"];
            let weight = slice["weight"].as_i64().ok_or("missing blueprint slice weight")?;
            if weight <= 0 || weight > i32::MAX as i64 {
                return Err("blueprint slice weight out of range".into());
            }
            let provider = provider(&slice["provider"], g, 0)?;
            total_weight += weight as i32;
            slices.push(BlueprintSlice {
                weight: weight as i32,
                provider,
            });
        }
        if total_weight <= 0 {
            return Err("blueprint total weight".into());
        }
        Ok(Self::Blueprint {
            original: Box::new(original),
            slices,
            total_weight,
            size: section["size"].as_i64().unwrap_or(9).clamp(0, 256) as i32,
            slices_seed: section["slices_seed"].as_i64().ok_or("missing slices seed")?,
            slices_zoom_seed: section["slices_zoom_seed"]
                .as_i64()
                .ok_or("missing slices zoom seed")?,
        })
    }

    pub fn uses_terrablender_routing(&self) -> bool {
        match self {
            Self::TerraBlender { .. } => true,
            Self::Blueprint { original, .. } => original.uses_terrablender_routing(),
            _ => false,
        }
    }

    /// Parses a source nested inside a Blueprint provider. Nested sources
    /// never carry `vss_*` sections of their own; those describe the outer
    /// generator, so only the plain vanilla shapes are accepted here.
    fn nested(v: &Value, g: &Graph, depth: i32) -> Result<Self> {
        if depth > MAX_BLUEPRINT_DEPTH {
            return Err("blueprint nesting depth".into());
        }
        Ok(match minecraft_type(v)? {
            "fixed" => Self::Fixed(string(v, "biome")?.into()),
            "multi_noise" => Self::Multi(ParameterTree::from_json(&v["biomes"])?, climate_ids(g)?),
            "the_end" => Self::End(g.root("erosion")?),
            s => return Err(format!("unsupported nested biome source {s}")),
        })
    }

    pub fn sample<'a>(
        &'a self,
        g: &Graph,
        quart: [i32; 3],
        scratch: &mut Scratch,
        last: &mut Option<usize>,
    ) -> &'a str {
        let p = quart.map(|v| v.wrapping_mul(4));
        match self {
            Self::Fixed(v) => v,
            Self::Multi(tree, ids) => {
                let mut target = [0; 7];
                for i in 0..6 {
                    target[i] = quantize(g.compute(ids[i], p, Mode::Raw, scratch) as f32);
                }
                tree.search(target, last)
            }
            Self::TerraBlender {
                base,
                ids,
                regions,
                grid,
                possible,
                region_last,
            } => {
                let mut target = [0; 7];
                for i in 0..6 {
                    target[i] = quantize(g.compute(ids[i], p, Mode::Raw, scratch) as f32);
                }
                let index = grid.get(quart[0], quart[2]);
                if index != 0 && index < regions.len() {
                    if let Some(Some(tree)) = regions.get(index) {
                        // Per-region tie slots mirror the vanilla RTree
                        // last-result rule; the slot is thread-safe because
                        // column locks allow concurrent native callers.
                        let previous = region_last[index].load(AtomicOrdering::Relaxed);
                        let mut slot = (previous != usize::MAX).then_some(previous);
                        let found = tree.search(target, &mut slot);
                        match slot {
                            Some(value) => {
                                region_last[index].store(value, AtomicOrdering::Relaxed);
                            }
                            None => {
                                region_last[index].store(usize::MAX, AtomicOrdering::Relaxed);
                            }
                        }
                        if possible.contains(found) {
                            return found;
                        }
                    }
                }
                base.search(target, last)
            }
            Self::End(id) => {
                let x = p[0] >> 4;
                let z = p[2] >> 4;
                if x as i64 * x as i64 + z as i64 * z as i64 <= 4096 {
                    "minecraft:the_end"
                } else {
                    let d = g.compute(
                        *id,
                        [(x * 2 + 1) * 8, p[1], (z * 2 + 1) * 8],
                        Mode::Raw,
                        scratch,
                    );
                    if d > 0.25 {
                        "minecraft:end_highlands"
                    } else if d >= -0.0625 {
                        "minecraft:end_midlands"
                    } else if d < -0.21875 {
                        "minecraft:small_end_islands"
                    } else {
                        "minecraft:end_barrens"
                    }
                }
            }
        Self::Blueprint {
            original,
            slices,
            total_weight,
            size,
            slices_seed,
            slices_zoom_seed,
        } => {
            // ModdedBiomeSource.getSlice, byte-for-byte: walk the slice array
            // accumulating weights until the running remainder goes negative,
            // and retire any slice whose provider answers with the placeholder
            // biome, then restart from the front with a rescaled remainder.
            let random = blueprint_random(quart[0], quart[2], *size, *slices_seed, *slices_zoom_seed);
            let mut removed = vec![false; slices.len()];
            let mut total = *total_weight;
            let mut remaining = random.rem_euclid(total as i64) as i32;
            let mut index = 0usize;
            let mut chosen: Option<&'a str> = None;
            while index < slices.len() {
                if !removed[index] {
                    remaining -= slices[index].weight;
                    if remaining < 0 {
                        let found = slices[index]
                            .provider
                            .eval(original, g, quart, scratch, last);
                        if found == ORIGINAL_SOURCE_MARKER {
                            removed[index] = true;
                            total -= slices[index].weight;
                            // Java evaluates Math.floorMod(random, 0) here and
                            // would throw. Production never reaches it, which
                            // means at least one slice always survives, so
                            // falling back keeps the reachable behaviour
                            // identical instead of panicking.
                            if total <= 0 {
                                break;
                            }
                            remaining = random.rem_euclid(total as i64) as i32;
                            index = 0;
                            continue;
                        }
                        chosen = Some(found);
                        break;
                    }
                }
                index += 1;
            }
            match chosen {
                Some(found) => found,
                None => original.sample(g, quart, scratch, last),
            }
        }
        Self::Checker(names, shift) => {
            &names[(quart[0]
                .wrapping_shr(*shift)
                .wrapping_add(quart[2].wrapping_shr(*shift)))
            .rem_euclid(names.len() as i32) as usize]
        }
    }
}
}

/// The six climate roots a multi-noise tree reads, in vanilla's order.
fn climate_ids(g: &Graph) -> Result<[Id; 6]> {
    Ok([
        g.root("temperature")?,
        g.root("vegetation")?,
        g.root("continents")?,
        g.root("erosion")?,
        g.root("depth")?,
        g.root("ridges")?,
    ])
}

impl BlueprintProvider {
    /// Mirrors `ModdedBiomeProvider.getNoiseBiome`. Answering with
    /// [`ORIGINAL_SOURCE_MARKER`] means "this slice does not apply", which the
    /// caller retires exactly as `getSlice` does.
    fn eval<'a>(
        &'a self,
        original: &'a BiomeSource,
        g: &Graph,
        quart: [i32; 3],
        scratch: &mut Scratch,
        last: &mut Option<usize>,
    ) -> &'a str {
        match self {
            Self::Original => original.sample(g, quart, scratch, last),
            Self::MultiNoise(tree, ids) => {
                let p = quart.map(|v| v.wrapping_mul(4));
                let mut target = [0i64; 7];
                for i in 0..6 {
                    target[i] = quantize(g.compute(ids[i], p, Mode::Raw, scratch) as f32);
                }
                tree.search(target, last)
            }
            Self::Overlay(overlays) => {
                let base = original.sample(g, quart, scratch, last);
                for (matches, source) in overlays {
                    if !matches.contains(base) {
                        continue;
                    }
                    return source.sample(g, quart, scratch, last);
                }
                ORIGINAL_SOURCE_MARKER
            }
            Self::Source(source) => source.sample(g, quart, scratch, last),
        }
    }
}

/// Parses one `ModdedBiomeProvider` dispatch entry.
fn provider(v: &Value, g: &Graph, depth: i32) -> Result<BlueprintProvider> {
    if depth > MAX_BLUEPRINT_DEPTH {
        return Err("blueprint provider depth".into());
    }
    let kind = v["type"].as_str().ok_or("missing blueprint provider type")?;
    let kind = kind.strip_prefix("blueprint:").unwrap_or(kind);
    Ok(match kind {
        "original" => BlueprintProvider::Original,
        "multi_noise" => {
            // The provider codec resolves `areas` while loading, before the
            // tree exists, and substitutes the placeholder for every entry it
            // does not map. Replaying that here lets ParameterTree consume the
            // array unchanged.
            let only_map_from_areas = v["only_map_from_areas"].as_bool().unwrap_or(false);
            let areas = v["areas"].as_object();
            let raw = v["biomes"].as_array().ok_or("missing blueprint biomes")?;
            let mut mapped = Vec::with_capacity(raw.len());
            for entry in raw {
                let key = entry["biome"].as_str().ok_or("missing blueprint biome id")?;
                let resolved = areas
                    .and_then(|map| map.get(key))
                    .and_then(|value| value.as_str())
                    .map(str::to_owned)
                    .unwrap_or_else(|| {
                        if only_map_from_areas {
                            ORIGINAL_SOURCE_MARKER.to_owned()
                        } else {
                            key.to_owned()
                        }
                    });
                let mut copy = entry.clone();
                copy["biome"] = Value::String(resolved);
                mapped.push(copy);
            }
            BlueprintProvider::MultiNoise(
                ParameterTree::from_json(&Value::Array(mapped))?,
                climate_ids(g)?,
            )
        }
        "overlay" => {
            let list = v["overlays"].as_array().ok_or("missing blueprint overlays")?;
            let mut overlays = Vec::with_capacity(list.len());
            for entry in list {
                let matches = biome_set(&entry["matches_biomes"])?;
                let source = BiomeSource::nested(&entry["biome_source"], g, depth + 1)?;
                overlays.push((matches, Box::new(source)));
            }
            BlueprintProvider::Overlay(overlays)
        }
        "biome_source" => BlueprintProvider::Source(Box::new(BiomeSource::nested(
            &v["biome_source"],
            g,
            depth + 1,
        )?)),
        other => return Err(format!("unsupported blueprint provider {other}")),
    })
}

/// Decodes a `HolderSet<Biome>` as Blueprint writes it: a bare id for the
/// singleton form, or an array of ids. Tag entries are refused rather than
/// approximated, because the snapshot carries no tag data.
fn biome_set(v: &Value) -> Result<HashSet<String>> {
    let mut set = HashSet::new();
    match v {
        Value::String(single) => {
            if single.starts_with('#') {
                return Err(format!("blueprint biome tag unsupported {single}"));
            }
            set.insert(single.clone());
        }
        Value::Array(entries) => {
            for entry in entries {
                let id = entry.as_str().ok_or("blueprint biome set entry")?;
                if id.starts_with('#') {
                    return Err(format!("blueprint biome tag unsupported {id}"));
                }
                set.insert(id.to_owned());
            }
        }
        _ => return Err("blueprint biome set".into()),
    }
    Ok(set)
}

/// `ModdedBiomeSource.computeZoomedPositionalRandom`. The incoming `x`/`z` are
/// quart coordinates and `QuartPos.toBlock` (`x << 2`) scales them to blocks
/// before the `size`-step shrink loop.
fn blueprint_random(x: i32, z: i32, size: i32, slices_seed: i64, slices_zoom_seed: i64) -> i64 {
    let mut cord_x = x << 2;
    let mut cord_z = z << 2;
    let mut step = 0;
    while step < size {
        let cell_pos_x = cord_x & 1;
        let cell_pos_z = cord_z & 1;
        let cell_x = cord_x >> 1;
        let cell_z = cord_z >> 1;
        if cell_pos_x == 0 && cell_pos_z == 0 {
            cord_x = cell_x;
            cord_z = cell_z;
        } else if cell_pos_x == 0 {
            cord_z = if blueprint_next_int(slices_zoom_seed, cell_x << 1, cell_z << 1, 2) == 0 {
                cell_z
            } else {
                (cord_z + 1) >> 1
            };
            cord_x = cell_x;
        } else if cell_pos_z == 0 {
            cord_x = if blueprint_next_int(slices_zoom_seed, cell_x << 1, cell_z << 1, 2) == 0 {
                cell_x
            } else {
                (cord_x + 1) >> 1
            };
            cord_z = cell_z;
        } else {
            match blueprint_next_int(slices_zoom_seed, cell_x << 1, cell_z << 1, 4) {
                0 => {
                    cord_x = cell_x;
                    cord_z = cell_z;
                }
                1 => {
                    cord_x = (cord_x + 1) >> 1;
                    cord_z = cell_z;
                }
                2 => {
                    cord_x = cell_x;
                    cord_z = (cord_z + 1) >> 1;
                }
                _ => {
                    cord_x = (cord_x + 1) >> 1;
                    cord_z = (cord_z + 1) >> 1;
                }
            }
        }
        step += 1;
    }
    blueprint_next(slices_seed, cord_x, cord_z)
}

fn blueprint_next_int(seed: i64, x: i32, z: i32, bound: i32) -> i32 {
    blueprint_next(seed, x, z).rem_euclid(bound as i64) as i32
}

/// `ModdedBiomeSource.next`: four LCG mixes then an arithmetic shift. The
/// shift must stay signed, matching Java's `>>` on a `long`.
fn blueprint_next(seed: i64, x: i32, z: i32) -> i64 {
    let mut value = lcg(seed, x as i64);
    value = lcg(value, z as i64);
    value = lcg(value, x as i64);
    lcg(value, z as i64) >> 24
}

/// Vanilla `net.minecraft.util.LinearCongruentialGenerator` with wrapping
/// long arithmetic, exactly as the old biome-layer RNG behaves in Java.
fn lcg(seed: i64, operand: i64) -> i64 {
    let seed = seed.wrapping_mul(
        seed.wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407),
    );
    seed.wrapping_add(operand)
}

/// TerraBlender `AreaContext.mixSeed`.
fn mix_seed(seed: i64, salt: i64) -> i64 {
    let l = {
        let l = lcg(salt, salt);
        let l = lcg(l, salt);
        lcg(l, salt)
    };
    let mixed = lcg(seed, l);
    let mixed = lcg(mixed, l);
    lcg(mixed, l)
}

/// Per-pixel draw sequence. TerraBlender keeps one mutable context per layer;
/// a fresh cursor per pixel reproduces the same draws without shared state.
struct Rng {
    seed: i64,
    rval: i64,
}
impl Rng {
    fn new(mixed_seed: i64, x: i64, z: i64) -> Self {
        let mut l = mixed_seed;
        l = lcg(l, x);
        l = lcg(l, z);
        l = lcg(l, x);
        l = lcg(l, z);
        Self {
            seed: mixed_seed,
            rval: l,
        }
    }
    fn next_random(&mut self, bound: i64) -> i64 {
        let value = (self.rval >> 24).rem_euclid(bound);
        self.rval = lcg(self.rval, self.seed);
        value
    }
    fn random2(&mut self, first: usize, second: usize) -> usize {
        if self.next_random(2) == 0 {
            first
        } else {
            second
        }
    }
    fn random4(&mut self, a: usize, b: usize, c: usize, d: usize) -> usize {
        match self.next_random(4) {
            0 => a,
            1 => b,
            2 => c,
            _ => d,
        }
    }
}

/// TerraBlender's uniqueness zoom stack (4.1.x `LayeredNoiseUtil`): initial
/// weighted pick with salt 1, one fuzzy zoom with salt 2000, three normal
/// zooms from salt 2001 and `region_size` normal zooms from salt 1001.
pub enum Grid {
    Initial {
        seed: i64,
        entries: Vec<(usize, i64)>,
        total: i64,
    },
    Zoom {
        parent: Box<Grid>,
        seed: i64,
        fuzzy: bool,
    },
}
impl Grid {
    pub fn get(&self, x: i32, z: i32) -> usize {
        match self {
            Grid::Initial {
                seed,
                entries,
                total,
            } => {
                if *total == 0 {
                    return 0;
                }
                let mut rng = Rng::new(*seed, x as i64, z as i64);
                let mut pick = rng.next_random(*total);
                for (index, weight) in entries {
                    pick -= weight;
                    if pick < 0 {
                        return *index;
                    }
                }
                0
            }
            Grid::Zoom {
                parent,
                seed,
                fuzzy,
            } => {
                let first = parent.get(x >> 1, z >> 1);
                // ZoomLayer.apply seeds the quadrant corners, not the pixel.
                let mut rng = Rng::new(*seed, (x & -2) as i64, (z & -2) as i64);
                let half_x = x & 1;
                let half_z = z & 1;
                if half_x == 0 && half_z == 0 {
                    return first;
                }
                let second = parent.get(x >> 1, z.wrapping_add(1) >> 1);
                let pick = rng.random2(first, second);
                if half_x == 0 {
                    return pick;
                }
                let third = parent.get(x.wrapping_add(1) >> 1, z >> 1);
                let pick = rng.random2(first, third);
                if half_z == 0 {
                    return pick;
                }
                let fourth = parent.get(x.wrapping_add(1) >> 1, z.wrapping_add(1) >> 1);
                if *fuzzy {
                    rng.random4(first, third, second, fourth)
                } else {
                    mode_or_random(&mut rng, first, third, second, fourth)
                }
            }
        }
    }
}
/// `ZoomLayer.modeOrRandom`; arguments follow the call site order.
fn mode_or_random(rng: &mut Rng, a: usize, b: usize, c: usize, d: usize) -> usize {
    if b == c && c == d {
        return b;
    }
    if a == b && b == c {
        return a;
    }
    if a == b && b == d {
        return a;
    }
    if a == c && c == d {
        return a;
    }
    if a == b && c != d {
        return a;
    }
    if a == c && b != d {
        return a;
    }
    if a == d && b != c {
        return a;
    }
    if b == c && a != d {
        return b;
    }
    if b == d && a != c {
        return b;
    }
    if c == d && a != b {
        return c;
    }
    rng.random4(a, b, c, d)
}
pub fn uniqueness_grid(world_seed: i64, region_size: i64, entries: Vec<(usize, i64)>) -> Grid {
    let total = entries.iter().map(|(_, weight)| weight).sum();
    let mut grid = Grid::Initial {
        seed: mix_seed(world_seed, 1),
        entries,
        total,
    };
    grid = Grid::Zoom {
        parent: Box::new(grid),
        seed: mix_seed(world_seed, 2000),
        fuzzy: true,
    };
    for i in 0..3 {
        grid = Grid::Zoom {
            parent: Box::new(grid),
            seed: mix_seed(world_seed, 2001 + i),
            fuzzy: false,
        };
    }
    for i in 0..region_size {
        grid = Grid::Zoom {
            parent: Box::new(grid),
            seed: mix_seed(world_seed, 1001 + i),
            fuzzy: false,
        };
    }
    grid
}
