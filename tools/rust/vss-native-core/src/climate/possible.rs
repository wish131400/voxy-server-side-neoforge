//! Conservative biome certificates for a whole vertical quart column.
//!
//! Leaves with the same depth interval have the same vertical distance at
//! every Y. A strictly worse horizontal distance can therefore never win.
//! Keep every horizontal tie, including the caller's possible last-result
//! leaf. This query does not invoke or mutate the real RTree resolver.
use super::*;
use std::collections::BTreeMap;

impl ParameterTree {
    fn possible_horizontal<'a>(
        &'a self,
        target: &[i64; 7],
        depth: Option<[i64; 2]>,
    ) -> Option<Vec<&'a str>> {
        let groups = self
            .horizontal_groups
            .get_or_init(|| {
                let mut groups = BTreeMap::<[i64; 2], Vec<Node>>::new();
                for leaf in &self.leaves {
                    let mut horizontal = leaf.clone();
                    horizontal.space[4] = [0, 0];
                    groups.entry(leaf.space[4]).or_default().push(horizontal);
                    if groups.len() > 128 {
                        return None;
                    }
                }
                Some(groups.into_values().map(build).collect())
            })
            .as_ref()?;
        fn visit(node: &Node, target: &[i64; 7], best: &mut i64, leaves: &mut Vec<usize>) {
            let distance = node.distance(target);
            if distance > *best {
                return;
            }
            if let Some(index) = node.leaf {
                if distance < *best {
                    *best = distance;
                    leaves.clear();
                }
                leaves.push(index);
            } else {
                for child in &node.children {
                    visit(child, target, best, leaves);
                }
            }
        }
        let mut found = Vec::new();
        let mut leaves = Vec::new();
        let mut winners = Vec::with_capacity(groups.len());
        for group in groups {
            leaves.clear();
            let mut best = i64::MAX;
            visit(group, target, &mut best, &mut leaves);
            let vertical = self.leaves[leaves[0]].space[4];
            winners.push((vertical, best, leaves.clone()));
        }
        for (index, (_, _, leaves)) in winners.iter().enumerate() {
            if let Some(depth) = depth {
                let mut budget = 64;
                if !can_win(index, depth[0], depth[1], &winners, &mut budget) {
                    continue;
                }
            }
            for &leaf in leaves {
                found.push(self.values[leaf].as_str());
            }
        }
        found.sort_unstable();
        found.dedup();
        Some(found)
    }
}

impl BiomeSource {
    pub(crate) fn possible_column<'a>(
        &'a self,
        g: &Graph,
        x: i32,
        z: i32,
        scratch: &mut Scratch,
    ) -> Option<Vec<&'a str>> {
        match self {
            Self::Fixed(name) => Some(vec![name]),
            Self::Multi(tree, ids) => {
                if (0..6).any(|i| i != 4 && !g.raw_y_independent(ids[i])) {
                    return None;
                }
                let p = [x.checked_mul(4)?, 0, z.checked_mul(4)?];
                let mut target = [0; 7];
                for i in 0..6 {
                    if i == 4 {
                        continue;
                    }
                    let value = g.compute(ids[i], p, Mode::Raw, scratch) as f32;
                    // Preserve the resolver's f32 conversion/quantization, and
                    // decline hostile values before squared integer distances.
                    if !value.is_finite() || value.abs() > 1000. {
                        return None;
                    }
                    target[i] = quantize(value);
                }
                let mut range = g.global_range(ids[4]);
                if !range.0.is_finite() || !range.1.is_finite() {
                    // Same coordinate budget as biomeIndices. Placement's
                    // candidate analyzer declines coordinates outside it.
                    range =
                        g.raw_column_range(ids[4], [p[0], -2_000_000, p[2]], 2_000_000, scratch);
                }
                let (a, b) = range;
                let depth = if a.is_finite() && b.is_finite() && a >= -1000. && b <= 1000. {
                    // f64 interval endpoints round monotonically to the same
                    // f32 values as sample(). One extra integer preserves
                    // outward coverage around quantization boundaries.
                    Some([
                        quantize(a as f32).saturating_sub(1),
                        quantize(b as f32).saturating_add(1),
                    ])
                } else {
                    None
                };
                tree.possible_horizontal(&target, depth)
            }
            // Positional routing and custom source state require their own
            // certificates. An unknown source always keeps normal placement.
            _ => None,
        }
    }
}

type Winner = ([i64; 2], i64, Vec<usize>);
fn distance(y: i64, range: [i64; 2]) -> i128 {
    let d = (range[0] as i128 - y as i128)
        .max(y as i128 - range[1] as i128)
        .max(0);
    d * d
}
/// Exact lower bound of cost(A)-cost(B) on an integer interval. Their
/// difference is quadratic between depth endpoints; inspect its vertex too.
fn min_difference(a: &Winner, b: &Winner, lo: i64, hi: i64) -> i128 {
    let value = |y| a.1 as i128 - b.1 as i128 + distance(y, a.0) - distance(y, b.0);
    let mut points = vec![lo, hi];
    points.extend(a.0.into_iter().chain(b.0).filter(|y| *y > lo && *y < hi));
    points.sort_unstable();
    points.dedup();
    let mut best = points.iter().map(|&y| value(y)).min().unwrap();
    for ends in points.windows(2) {
        let middle = ends[0] + (ends[1] - ends[0]) / 2;
        let terms = |r: [i64; 2]| -> (i128, i128) {
            if middle < r[0] {
                (1, -2 * r[0] as i128)
            } else if middle > r[1] {
                (1, -2 * r[1] as i128)
            } else {
                (0, 0)
            }
        };
        let (aa, ab) = terms(a.0);
        let (ba, bb) = terms(b.0);
        let q = aa - ba;
        let linear = ab - bb;
        if q > 0 {
            let root = (-linear).div_euclid(2 * q);
            for y in [root, root + 1] {
                if y >= ends[0] as i128 && y <= ends[1] as i128 {
                    best = best.min(value(y as i64));
                }
            }
        }
    }
    best
}
fn can_win(index: usize, lo: i64, hi: i64, groups: &[Winner], budget: &mut usize) -> bool {
    if *budget == 0 {
        return true;
    }
    *budget -= 1;
    if groups
        .iter()
        .enumerate()
        .any(|(i, b)| i != index && min_difference(&groups[index], b, lo, hi) > 0)
    {
        return false;
    }
    if lo == hi {
        return true;
    }
    let middle = lo + (hi - lo) / 2;
    can_win(index, lo, middle, groups, budget) || can_win(index, middle + 1, hi, groups, budget)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn raw_column_bounds_cover_legacy_climate_without_cell_assumptions() {
        for fixture in ["worldgen/overworld.json", "worldgen/large_biomes.json"] {
            let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("tests/fixtures")
                .join(fixture);
            let mut doc: Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
            doc["settings"]["noise_router"]["test_nested_interp"]=json!({"type":"minecraft:interpolated",
                "argument":{"type":"minecraft:interpolated","argument":{"type":"minecraft:y_clamped_gradient",
                    "from_y":-64,"to_y":320,"from_value":-2.,"to_value":3.}}});
            let graph = Graph::from_document(42, &doc).unwrap();
            for (x, z) in [(-17, 31), (0, 0), (100003, -100005)] {
                let mut scratch = graph.raw_scratch().unwrap();
                let depth = graph.root("depth").unwrap();
                let (lo, hi) =
                    graph.raw_column_range(depth, [x, -2_000_000, z], 2_000_000, &mut scratch);
                assert!(lo.is_finite() && hi.is_finite(), "{fixture} {lo} {hi}");
                for y in [-2_000_000, -65, -64, -1, 0, 63, 319, 320, 2_000_000] {
                    let value = graph.compute(depth, [x, y, z], Mode::Raw, &mut scratch);
                    assert!(
                        lo <= value && value <= hi,
                        "{fixture} {x} {y} {z}: {value} outside {lo}..{hi}"
                    );
                }
                if let Ok(root) = graph.root("test_nested_interp") {
                    let range = graph.raw_column_range(root, [x, -64, z], 319, &mut scratch);
                    for y in (-64..=319).step_by(7) {
                        let value = graph.compute(root, [x, y, z], Mode::Raw, &mut scratch);
                        assert!(range.0 <= value && value <= range.1);
                    }
                }
            }
        }
    }

    fn entry(name: &str, temperature: f32, depth: [f32; 2]) -> Value {
        json!({"biome":name,"parameters":{"temperature":temperature,"humidity":0,
            "continentalness":0,"erosion":0,"depth":depth,"weirdness":0,"offset":0}})
    }

    #[test]
    fn integer_depth_proofs_match_exhaustive_distance_extrema() {
        for a in [-30, -1, 0, 7, 31] {
            for b in [-22, -1, 0, 5, 28] {
                for width_a in [0, 1, 17] {
                    for width_b in [0, 1, 13] {
                        let first = ([a, a + width_a], 73, vec![]);
                        let second = ([b, b + width_b], 19, vec![]);
                        for (lo, hi) in [(-100, 100), (-3, 3), (0, 1), (1, 1), (20, 90)] {
                            let expected = (lo..=hi)
                                .map(|y| 54 + distance(y, first.0) - distance(y, second.0))
                                .min()
                                .unwrap();
                            assert_eq!(min_difference(&first, &second, lo, hi), expected);
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn certificates_keep_all_ties_and_every_depth_group() {
        let tree = ParameterTree::from_json(&json!([
            entry("mod:a", 0., [-1., 0.]),
            entry("mod:tie", 0., [-1., 0.]),
            entry("mod:far", 1., [-1., 0.]),
            entry("mod:cave", 1., [0.2, 1.])
        ]))
        .unwrap();
        let target = [0; 7];
        let possible = tree
            .possible_horizontal(&target, Some([-100000, 100000]))
            .unwrap();
        assert_eq!(possible, vec!["mod:a", "mod:cave", "mod:tie"]);
        for y in [-100000, -10000, -1, 0, 1, 2000, 5000, 10000, 100000] {
            let mut actual = target;
            actual[4] = y;
            for last in [None, Some(0), Some(1), Some(2), Some(3)] {
                let mut last = last;
                assert!(possible.contains(&tree.search(actual, &mut last)));
            }
        }
    }

    #[test]
    fn certificate_covers_random_modded_ranges_without_mutating_last_result() {
        let mut state = 17u64;
        let mut next = || {
            state = state.wrapping_mul(6364136223846793005).wrapping_add(1);
            (state >> 32) as i32
        };
        let entries: Vec<_> = (0..96)
            .map(|i| {
                let mut parameters = serde_json::Map::new();
                for key in [
                    "temperature",
                    "humidity",
                    "continentalness",
                    "erosion",
                    "weirdness",
                    "offset",
                ] {
                    let a = (next().unsigned_abs() % 200) as f32 / 100. - 1.;
                    parameters.insert(key.into(), json!([a, a + 0.15]));
                }
                let d = (i % 4) as f32 * 0.2;
                parameters.insert("depth".into(), json!([d, d + 0.1]));
                json!({"biome":format!("mod:biome_{i}"),"parameters":parameters})
            })
            .collect();
        let tree = ParameterTree::from_json(&json!(entries)).unwrap();
        for _ in 0..100 {
            let mut target = [0; 7];
            for i in [0, 1, 2, 3, 5] {
                target[i] = (next() as i64) % 15000;
            }
            let possible = tree
                .possible_horizontal(&target, Some([-20000, 20000]))
                .unwrap();
            let mut last = Some(next().unsigned_abs() as usize % 96);
            for depth in (-20000..20000).step_by(211) {
                target[4] = depth;
                assert!(possible.contains(&tree.search(target, &mut last)));
            }
        }
    }
}
