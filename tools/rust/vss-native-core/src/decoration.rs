//! Vanilla FeatureSorter. Registry keys preserve holder identity; source order
//! is the generator's possibleBiomes iteration order supplied in the snapshot.
use crate::density::Result;
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet, HashMap};
type Node = (usize, usize);
pub struct Schedule {
    pub steps: Vec<Vec<String>>,
    pub biome_steps: HashMap<String, Vec<Vec<String>>>,
}
impl Schedule {
    pub fn build(document: &Value, possible: &[String]) -> Result<Self> {
        if possible.len() > 65536 {
            return Err("possible biome budget".into());
        }
        let mut names = vec![];
        let mut ids = HashMap::new();
        let mut edges: BTreeMap<Node, BTreeSet<Node>> = BTreeMap::new();
        let mut count = 0;
        let mut biome_steps = HashMap::new();
        for biome in possible {
            let steps = document["biomes"][biome]["features"]
                .as_array()
                .ok_or_else(|| format!("missing biome feature steps {biome}"))?;
            if steps.len() > 32 {
                return Err("decoration step budget".into());
            }
            count = count.max(steps.len());
            let mut sequence = vec![];
            let mut stored = vec![];
            for (step, features) in steps.iter().enumerate() {
                let mut current = vec![];
                for value in features
                    .as_array()
                    .ok_or("feature holder tag must be expanded")?
                {
                    let name = value
                        .as_str()
                        .filter(|s| !s.starts_with('#'))
                        .ok_or("inline feature identity must be supplied by registry snapshot")?
                        .to_owned();
                    let id = *ids.entry(name.clone()).or_insert_with(|| {
                        let id = names.len();
                        names.push(name.clone());
                        id
                    });
                    sequence.push((step, id));
                    current.push(name);
                }
                stored.push(current);
            }
            if names.len() > 65536 || sequence.len() > 65536 {
                return Err("feature graph budget".into());
            }
            for (i, node) in sequence.iter().enumerate() {
                let next = edges.entry(*node).or_default();
                if let Some(&q) = sequence.get(i + 1) {
                    next.insert(q);
                }
            }
            biome_steps.insert(biome.clone(), stored);
        }
        let mut states = HashMap::new();
        let mut result = vec![];
        // Iterative DFS preserves sorted successor order without a native-stack
        // overflow on a long custom registry graph.
        for &root in edges.keys() {
            if states.get(&root) == Some(&2) {
                continue;
            }
            let mut stack = vec![(root, false)];
            while let Some((node, exit)) = stack.pop() {
                if exit {
                    states.insert(node, 2);
                    result.push(node);
                    continue;
                }
                match states.get(&node) {
                    Some(2) => continue,
                    Some(1) => return Err("feature order cycle".into()),
                    _ => {}
                }
                states.insert(node, 1);
                stack.push((node, true));
                if let Some(children) = edges.get(&node) {
                    for &next in children.iter().rev() {
                        stack.push((next, false));
                    }
                }
            }
        }
        let mut steps = vec![vec![]; count];
        for (step, id) in result.into_iter().rev() {
            steps[step].push(names[id].clone());
        }
        Ok(Self { steps, biome_steps })
    }
    pub fn allowed(&self, biome: &str, feature: &str) -> bool {
        self.biome_steps
            .get(biome)
            .is_some_and(|steps| steps.iter().flatten().any(|s| s == feature))
    }
    pub fn indices(&self, step: usize, biomes: &BTreeSet<String>) -> Vec<usize> {
        let Some(features) = self.steps.get(step) else {
            return vec![];
        };
        let allowed: BTreeSet<_> = biomes
            .iter()
            .filter_map(|b| self.biome_steps.get(b))
            .filter_map(|steps| steps.get(step))
            .flatten()
            .collect();
        features
            .iter()
            .enumerate()
            .filter_map(|(i, f)| allowed.contains(f).then_some(i))
            .collect()
    }
}
