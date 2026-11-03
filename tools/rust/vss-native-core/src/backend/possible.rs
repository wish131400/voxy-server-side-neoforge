use super::*;
use std::collections::VecDeque;

/// World-owned, immutable proofs; never stores placed blocks or final surface
/// answers. Bounded independently of terrain/display caches.
#[derive(Default)]
pub(super) struct Columns {
    values: HashMap<(i32, i32), Arc<Vec<i32>>>,
    order: VecDeque<(i32, i32)>,
    bytes: usize,
}

impl World {
    pub fn possible_biomes(
        &self,
        min_x: i32,
        min_z: i32,
        max_x: i32,
        max_z: i32,
    ) -> Result<Option<Vec<i32>>> {
        self.check_active()?;
        let nx = max_x as i64 - min_x as i64 + 1;
        let nz = max_z as i64 - min_z as i64 + 1;
        if nx <= 0
            || nz <= 0
            || nx > 32
            || nz > 32
            || nx * nz > 1024
            || [min_x, min_z, max_x, max_z]
                .iter()
                .any(|q| q.abs_diff(0) > 7_500_000)
        {
            return Err("biome certificate bounds".into());
        }
        let mut scratch = self.take_raw_work()?;
        let result = (|| {
            let mut union = BTreeSet::new();
            for x in min_x..=max_x {
                for z in min_z..=max_z {
                    self.check_active()?;
                    let cached = self
                        .possible_columns
                        .lock()
                        .map_err(|_| "biome certificate lock")?
                        .values
                        .get(&(x, z))
                        .cloned();
                    let values = if let Some(values) = cached {
                        values
                    } else {
                        let Some(names) =
                            self.source
                                .possible_column(&self.terrain.graph, x, z, &mut scratch)
                        else {
                            return Ok(None);
                        };
                        let mut values = Vec::with_capacity(names.len());
                        for name in names {
                            let Some(&index) = self.biome_indices.get(name) else {
                                return Ok(None);
                            };
                            values.push(index);
                        }
                        let values = Arc::new(values);
                        let mut cache = self
                            .possible_columns
                            .lock()
                            .map_err(|_| "biome certificate lock")?;
                        let bytes = values.capacity() * std::mem::size_of::<i32>();
                        if bytes <= 2 * 1024 * 1024 && !cache.values.contains_key(&(x, z)) {
                            while cache.values.len() >= 4096
                                || cache.bytes + bytes > 2 * 1024 * 1024
                            {
                                if let Some(key) = cache.order.pop_front() {
                                    if let Some(old) = cache.values.remove(&key) {
                                        cache.bytes -= old.capacity() * std::mem::size_of::<i32>();
                                    }
                                }
                            }
                            cache.bytes += bytes;
                            cache.order.push_back((x, z));
                            cache.values.insert((x, z), values.clone());
                        }
                        values
                    };
                    union.extend(values.iter().copied());
                }
            }
            Ok(Some(union.into_iter().collect()))
        })();
        self.return_raw_work(scratch)?;
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn legacy_certificates_cover_actual_quart_queries_and_never_generate_surface() {
        let root=std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
        let mut doc=exterior_tests::document();
        let overworld:Value=serde_json::from_str(&std::fs::read_to_string(root.join("overworld.json")).unwrap()).unwrap();
        for key in ["settings","density_functions","noises","biome_source"] {doc[key]=overworld[key].clone();}
        doc.as_object_mut().unwrap().remove("possible_biomes");
        let world = World::new(42, 0, doc).unwrap();
        let mut narrower = 0;
        for (x, z) in [(0, 0), (-17, 31), (32768, -32768), (49152, -49152)] {
            let before = world.work_counts();
            let possible = world
                .possible_biomes(x, z, x, z)
                .unwrap()
                .expect("legacy vanilla must produce proof");
            assert_eq!(
                world.work_counts(),
                before,
                "certificate must not query surface"
            );
            if possible.len() < world.biome_names.len() {
                narrower += 1;
            }
            for y in (-20..=90).step_by(3) {
                assert!(possible.contains(&world.noise_biome_index([x, y, z]).unwrap()));
            }
            assert_eq!(
                world.possible_biomes(x, z, x, z).unwrap().unwrap(),
                possible
            );
        }
        assert!(narrower > 0);
        world.cancel();
        assert!(world.possible_biomes(0, 0, 0, 0).is_err());
    }
}
