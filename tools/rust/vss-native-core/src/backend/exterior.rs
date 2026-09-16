//! Boundary-first exterior sampling. Special geometry and rules that erase
//! the retained shell fall back to the original full surface query.
use super::*;
use crate::surface::ExteriorColumn;

impl World {
    pub(super) fn exterior_record(
        &self,
        x: i32,
        z: i32,
        job: &mut Job<'_>,
        scratch: &mut crate::density::Scratch,
    ) -> Result<Option<SurfaceColumn>> {
        let t = &self.terrain;
        if t.graph.requires_complete_column_order() || job.beard.is_some() {
            return Ok(None);
        }
        let padding_scope = crate::prof::Scope::new(&crate::prof::SURFACE.exterior_padding);
        let Some(padding) = self.surface.exterior_padding(&t.graph, x, z) else {
            return Ok(None);
        };
        padding_scope.finish();
        // `scratch` is supplied by the caller: `Graph::scratch` allocates a memo
        // array sized to the node count plus the column-plan and interpolator
        // tables, and building one per column dominated the exterior path. Its
        // origin is the enclosing chunk, which is exactly the caller's grouping.
        let scratch = scratch;
        let mut last = None;
        let mut cache = HashMap::new();
        let mut biome = |p| {
            let mut q = zoom_quart(self.zoom_seed, p);
            q[1] = q[1].clamp(t.min_y >> 2, (t.min_y + t.height - 1) >> 2);
            let name = *cache
                .entry(q)
                .or_insert_with(|| self.source.sample(&t.graph, q, &mut *scratch, &mut last));
            Ok((name, self.biomes.get(name).ok_or("missing exterior biome")?))
        };
        let geometry = |name: &str| {
            matches!(
                name,
                "minecraft:eroded_badlands"
                    | "minecraft:frozen_ocean"
                    | "minecraft:deep_frozen_ocean"
            )
        };
        // Neighbour steepness has often already located this exact boundary.
        // Publish the centre through the same cache so subsequent columns can
        // reuse it too. Stateful graphs and beard adjustments returned above.
        let centre_scope = crate::prof::Scope::new(&crate::prof::SURFACE.neighbour_fill);
        let boundary = job.surface_top(x, z);
        let mut y = boundary.map_or(t.min_y - 1, |(y, _)| y);
        let mut top = t.min_y;
        let mut stop = t.min_y;
        let mut found = false;
        let mut blocks = Vec::with_capacity(32);
        while y >= t.min_y {
            if !found {
                if let Some(bottom) = job.proven_air_bottom([x, y, z]) {
                    if top > t.min_y {
                        blocks.resize(blocks.len() + (y - bottom + 1) as usize, self.palette.air);
                    }
                    y = bottom - 1;
                    continue;
                }
            }
            let b = if boundary.is_some_and(|(height, _)| height == y) {
                boundary.unwrap().1
            } else {
                job.block([x, y, z])
            };
            if top == t.min_y && b != Substance::Air {
                top = y + 1;
                let (name, _) = biome([x, if t.graph.legacy { 0 } else { top }, z])?;
                if geometry(name) {
                    return Ok(None);
                }
            }
            if top > t.min_y {
                blocks.push(self.base_ids[b as usize]);
            }
            if !found && b.solid() {
                found = true;
                stop = (y - 7 - padding).max(t.min_y);
            }
            if found && y == stop {
                break;
            }
            y -= 1;
        }
        blocks.reverse();
        centre_scope.finish();
        let bottom = top - blocks.len() as i32;
        let mut column = ExteriorColumn {
            bottom,
            blocks,
            top,
        };
        let positions = [
            (x, (z - 1).max(z & !15)),
            (x, (z + 1).min((z & !15) + 15)),
            ((x - 1).max(x & !15), z),
            ((x + 1).min((x & !15) + 15), z),
        ];
        let neighbour_scope = crate::prof::Scope::new(&crate::prof::SURFACE.neighbour_geometry);
        let mut heights = [top; 4];
        for (i, &(xx, zz)) in positions.iter().enumerate() {
            if (xx, zz) == (x, z) {
                continue;
            }
            let h = job.surface_top(xx, zz).map_or(t.min_y, |(y, _)| y + 1);
            heights[i] = h;
            if (xx, zz) < (x, z) {
                for yy in [if t.graph.legacy { 0 } else { h }, h - 1] {
                    let (name, _) = biome([xx, yy, zz])?;
                    if geometry(name) || !self.surface.sparse_safe_for_biome(&self.palette, Some(name))
                    {
                        return Ok(None);
                    }
                }
            }
        }
        neighbour_scope.finish();
        let rules_scope = crate::prof::Scope::new(&crate::prof::SURFACE.apply_rules);
        self.surface.apply_exterior(
            job,
            &mut column,
            &self.palette,
            x,
            z,
            heights,
            &self.colors,
            &mut biome,
        )?;
        rules_scope.finish();
        let _record_scope = crate::prof::Scope::new(&crate::prof::SURFACE.record);
        let mut floor = t.min_y;
        let mut fluid_y = t.min_y;
        let mut fluid = 0;
        for yy in (bottom..top).rev() {
            let b = column.get(yy, self.palette.air);
            if self.palette.is_air(b) {
                continue;
            }
            if self.palette.fluid(b) {
                if fluid == 0 {
                    fluid = if self.palette.is(b, "minecraft:lava") {
                        2
                    } else {
                        1
                    };
                    fluid_y = yy + 1;
                }
            } else {
                floor = yy + 1;
                break;
            }
        }
        if bottom > t.min_y && floor - 7 < bottom + padding {
            return Ok(None);
        }
        if fluid_y <= floor {
            fluid = 0;
            fluid_y = floor;
        }
        let materials = [1, 2, 7].map(|d| column.get(floor - d, self.palette.air));
        Ok(Some(self.record_surface(
            x,
            z,
            floor,
            fluid_y,
            fluid,
            materials,
            &mut *scratch,
            &mut last,
        )?))
    }
}
