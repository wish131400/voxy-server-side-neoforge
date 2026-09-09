//! Minecraft Climate.RTree, including stable sorting and last-result tie rules.
use crate::density::{minecraft_type, string, Graph, Id, Mode, Result, Scratch};
use serde_json::Value;
use std::cmp::Ordering;
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
    End(Id),
    Checker(Vec<String>, u32),
}
impl BiomeSource {
    pub fn from_document(doc: &Value, g: &Graph) -> Result<Self> {
        let v = &doc["biome_source"];
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
            Self::Checker(names, shift) => {
                &names[(quart[0]
                    .wrapping_shr(*shift)
                    .wrapping_add(quart[2].wrapping_shr(*shift)))
                .rem_euclid(names.len() as i32) as usize]
            }
        }
    }
}
