//! Complete material columns shared by JNI sampling and native decoration.
use super::*;
use crate::surface::ExteriorColumn;

impl World {
    pub fn interior_column(&self, x: i32, z: i32) -> Result<Arc<Vec<StateId>>> {
        self.check_active()?;
        let key = (x, z);
        if let Some(v) = self
            .interior_columns
            .lock()
            .map_err(|_| "interior cache lock")?
            .0
            .get(&key)
        {
            return Ok(Arc::clone(v));
        }
        let t = &self.terrain;
        let mut job = t.job(x, z, true)?;
        let base = job.column(x, z)?;
        let mut column = ExteriorColumn {
            bottom: t.min_y,
            top: base.surface_height,
            blocks: base
                .blocks
                .into_iter()
                .map(|b| self.base_ids[b as usize])
                .collect(),
        };
        let mut scratch = t
            .graph
            .scratch(x & !15, z & !15, t.cell_width, t.cell_height)?;
        let mut last = None;
        let mut cache = HashMap::new();
        let biome = |p| {
            let mut q = zoom_quart(self.zoom_seed, p);
            q[1] = q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
            let name = *cache
                .entry(q)
                .or_insert_with(|| self.source.sample(&t.graph, q, &mut scratch, &mut last));
            Ok((name, self.biomes.get(name).ok_or("missing interior biome")?))
        };
        let mut heights = [column.top; 4];
        for (i, (xx, zz)) in [
            (x, (z - 1).max(z & !15)),
            (x, (z + 1).min((z & !15) + 15)),
            ((x - 1).max(x & !15), z),
            ((x + 1).min((x & !15) + 15), z),
        ]
        .into_iter()
        .enumerate()
        {
            if (xx, zz) != key {
                heights[i] = job.surface_top(xx, zz).map_or(t.min_y, |(y, _)| y + 1);
            }
        }
        self.surface.apply_retained(
            &mut job,
            &mut column,
            &self.palette,
            x,
            z,
            heights,
            &self.colors,
            biome,
            false,
        )?;
        self.check_active()?;
        let result = Arc::new(column.blocks);
        let mut cache = self
            .interior_columns
            .lock()
            .map_err(|_| "interior cache lock")?;
        // At most 512 columns and 2 MiB of state data per world; proxy owners
        // retain their own referenced columns only until the bounded job ends.
        let limit = (2 * 1024 * 1024 / (t.height as usize * 4)).clamp(1, 512);
        if !cache.0.contains_key(&key) {
            while cache.0.len() >= limit {
                if let Some(old) = cache.1.pop_front() {
                    cache.0.remove(&old);
                } else {
                    break;
                }
            }
            cache.1.push_back(key);
            cache.0.insert(key, Arc::clone(&result));
        }
        Ok(result)
    }

    pub fn interior_proxy(self: &Arc<Self>, cx: i32, cz: i32) -> Result<Volume> {
        self.check_active()?;
        if !(-1874996..=1874995).contains(&cx) || !(-1874996..=1874995).contains(&cz) {
            return Err("proxy origin outside range".into());
        }
        let owner = Arc::clone(self);
        let t = &self.terrain;
        let ox = (cx - 2) * 16;
        let oz = (cz - 2) * 16;
        let mut v = Volume::interior_proxy(
            [ox, t.min_y, oz],
            80,
            80,
            t.height as usize,
            self.palette.clone(),
            Box::new(move |x, z| owner.interior_column(ox + x as i32, oz + z as i32)),
        )?;
        v.write_bounds = Some((
            [cx * 16 - 16, t.min_y, cz * 16 - 16],
            [cx * 16 + 32, t.min_y + t.height, cz * 16 + 32],
        ));
        v.write_budget = 65536;
        Ok(v)
    }
}
