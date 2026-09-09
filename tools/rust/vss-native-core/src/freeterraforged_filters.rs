//! FreeTerraForged 0.0.6005 optional tile filters (MIT, ReTerraForged 2023).
//! Cells use one compact allocation; erosion brushes are shared by edge shape.
use crate::density::{integer, number, Result};
use serde_json::Value;
use std::collections::HashMap;

#[derive(Clone, Copy)]
struct Cell {
    values: [f32; 8],
    flags: u32,
}
impl Cell {
    fn modify(&self, amount: f32, min: f32, max: f32, inverse: bool) -> f32 {
        let mut strength = 1.;
        if self.values[4] != 1. {
            let alpha = map(self.values[5], 0., 0.15, 0.15);
            strength = 1. + alpha * (self.values[4] - 1.);
        }
        if self.values[6] < 0.1 {
            strength *= map(self.values[6], 0.002, 0.1, 0.098);
        }
        let h = self.values[0];
        let factor = if h > max {
            1.
        } else if h < min {
            0.
        } else {
            (h - min) / (max - min)
        };
        (if inverse { 1. - factor } else { factor }) * strength * amount
    }
    fn deposit(&mut self, amount: f32, min: f32, max: f32) {
        if self.flags & 1 == 0 {
            let change = self.modify(amount, min, max, false);
            self.values[0] += change;
            self.values[1] += change;
        }
    }
    fn erode(&mut self, amount: f32, min: f32, max: f32) {
        if self.flags & 1 == 0 {
            let change = self.modify(amount, min, max, false);
            self.values[0] -= change;
            self.values[2] -= change;
        }
    }
}
fn map(value: f32, min: f32, max: f32, range: f32) -> f32 {
    let dif = value.clamp(min, max) - min;
    if dif >= range {
        1.
    } else {
        dif / range
    }
}
fn seed(x: i32, z: i32) -> u64 {
    x as u32 as u64 | ((z as u32 as u64) << 32)
}
fn gamma(mut z: u64) -> u64 {
    z = (z ^ (z >> 33)).wrapping_mul((-49064778989728563i64) as u64);
    z = (z ^ (z >> 33)).wrapping_mul((-4265267296055464877i64) as u64);
    z = (z ^ (z >> 33)) | 1;
    if (z ^ (z >> 1)).count_ones() < 24 {
        z ^ 0xaaaaaaaaaaaaaaaa
    } else {
        z
    }
}
fn next16(state: &mut u64, gamma: u64) -> i32 {
    *state = state.wrapping_add(gamma);
    let z = (*state ^ (*state >> 33)).wrapping_mul(7109453100751455733);
    (((z ^ (z >> 28)).wrapping_mul((-3808689974395783757i64) as u64) >> 32) & 15) as i32
}
struct Options {
    size: usize,
    x: i32,
    z: i32,
    border: i32,
    seed: i32,
    drops: i32,
    lifetime: i32,
    erosion: f32,
    deposit: f32,
    speed: f32,
    water: f32,
    ground: f32,
    erosion_max: f32,
    smoothing_min: f32,
    smoothing_max: f32,
    smoothing_iterations: i32,
    radius: f32,
    rate: f32,
    water_level: f32,
    beach: f32,
    optional: bool,
}
impl Options {
    fn parse(v: &Value) -> Result<Self> {
        let size = integer(v, "size")?;
        let height = integer(v, "world_height")?.max(1);
        let water = integer(v, "sea_level")?;
        let ground_y = water.min(height);
        let o = Self {
            size: size as usize,
            x: integer(v, "block_x")?,
            z: integer(v, "block_z")?,
            border: integer(v, "border")?,
            seed: integer(v, "seed")?.wrapping_add(12768),
            drops: integer(v, "droplets_per_chunk")?,
            lifetime: integer(v, "droplet_lifetime")?,
            erosion: number(v, "erosion_rate")? as f32,
            deposit: number(v, "deposit_rate")? as f32,
            speed: number(v, "droplet_velocity")? as f32,
            water: number(v, "droplet_volume")? as f32,
            ground: ground_y as f32 / height as f32,
            erosion_max: (ground_y + 15) as f32 / height as f32,
            smoothing_min: (ground_y + 1) as f32 / height as f32,
            smoothing_max: (ground_y + 120) as f32 / height as f32,
            smoothing_iterations: integer(v, "smoothing_iterations")?,
            radius: number(v, "smoothing_radius")? as f32,
            rate: number(v, "smoothing_rate")? as f32,
            water_level: (water - 1).min(height) as f32 / height as f32,
            beach: number(v, "beach_transition")? as f32,
            optional: v["optional"]
                .as_bool()
                .ok_or("missing optional filters flag")?,
        };
        if !(16..=1024).contains(&size)
            || size % 16 != 0
            || o.border < 0
            || o.border >= size
            || !(0..=4096).contains(&o.drops)
            || !(0..=4096).contains(&o.lifetime)
            || !(0..=128).contains(&o.smoothing_iterations)
            || !(0.0..=64.0).contains(&o.radius)
            || [o.erosion, o.deposit, o.speed, o.water, o.rate]
                .iter()
                .any(|v| !v.is_finite())
        {
            return Err("invalid FreeTerraForged filter dimensions/settings".into());
        }
        Ok(o)
    }
}
pub fn byte_count(document: &Value) -> Result<usize> {
    Ok(Options::parse(document)?.size.pow(2) * 36)
}
pub fn run(input: &[u8], document: &Value) -> Result<Vec<u8>> {
    let o = Options::parse(document)?;
    if input.len() != o.size.pow(2) * 36 {
        return Err("invalid FreeTerraForged tile buffer length".into());
    }
    let mut cells: Vec<Cell> = input
        .chunks_exact(36)
        .map(|v| Cell {
            values: std::array::from_fn(|i| {
                f32::from_le_bytes(v[i * 4..i * 4 + 4].try_into().unwrap())
            }),
            flags: u32::from_le_bytes(v[32..36].try_into().unwrap()),
        })
        .collect();
    if cells
        .iter()
        .any(|c| c.values.iter().any(|v| !v.is_finite()))
    {
        return Err("nonfinite FreeTerraForged tile input".into());
    }
    let mut brushes = HashMap::new();
    let length = o.size as i32 >> 4;
    for iteration in 0..if o.optional { o.drops } else { 0 } {
        let gamma = gamma(seed(o.seed, iteration));
        for cz in 0..length {
            for cx in 0..length {
                let mut random = seed(
                    (o.x >> 4).wrapping_add(cx).wrapping_sub(o.border >> 4),
                    (o.z >> 4).wrapping_add(cz).wrapping_sub(o.border >> 4),
                );
                let x = ((cx << 4) + next16(&mut random, gamma)) as f32;
                let z = ((cz << 4) + next16(&mut random, gamma)) as f32;
                drop(
                    &mut cells,
                    &o,
                    x.clamp(1., (o.size - 2) as f32),
                    z.clamp(1., (o.size - 2) as f32),
                    &mut brushes,
                );
            }
        }
    }
    if o.optional {
        smooth(&mut cells, &o);
    }
    required(&mut cells, &o);
    let mut out = Vec::with_capacity(input.len());
    for cell in cells {
        for value in cell.values {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&cell.flags.to_le_bytes());
    }
    Ok(out)
}
fn terrain(cells: &[Cell], size: usize, px: f32, pz: f32) -> [f32; 3] {
    let x = px - px as i32 as f32;
    let z = pz - pz as i32 as f32;
    let index = pz as usize * size + px as usize;
    let nw = cells[index].values[0];
    let ne = cells[index + 1].values[0];
    let sw = cells[index + size].values[0];
    let se = cells[index + size + 1].values[0];
    [
        nw * (1. - x) * (1. - z) + ne * x * (1. - z) + sw * (1. - x) * z + se * x * z,
        (ne - nw) * (1. - z) + (se - sw) * z,
        (sw - nw) * (1. - x) + (se - ne) * x,
    ]
}
type Brush = Vec<(isize, f32)>;
fn brush(size: usize, x: usize, z: usize, cache: &mut HashMap<[usize; 4], Brush>) -> &Brush {
    let key = [
        x.min(4),
        (size - 1 - x).min(4),
        z.min(4),
        (size - 1 - z).min(4),
    ];
    cache.entry(key).or_insert_with(|| {
        let mut result = vec![];
        let mut sum = 0.;
        for dz in -4i32..=4 {
            for dx in -4i32..=4 {
                let square = dx * dx + dz * dz;
                if square < 16
                    && (0..size as i32).contains(&(x as i32 + dx))
                    && (0..size as i32).contains(&(z as i32 + dz))
                {
                    let weight = 1. - (square as f32).sqrt() / 4.;
                    sum += weight;
                    result.push((dz as isize * size as isize + dx as isize, weight));
                }
            }
        }
        for (_, weight) in &mut result {
            *weight /= sum;
        }
        result
    })
}
fn drop(
    cells: &mut [Cell],
    o: &Options,
    mut x: f32,
    mut z: f32,
    brushes: &mut HashMap<[usize; 4], Brush>,
) {
    let (mut dx, mut dz, mut sediment, mut speed, mut water) = (0., 0., 0., o.speed, o.water);
    for _ in 0..o.lifetime {
        let nx = x as usize;
        let nz = z as usize;
        let index = nz * o.size + nx;
        let ox = x - nx as f32;
        let oz = z - nz as f32;
        let old = terrain(cells, o.size, x, z);
        dx = dx * 0.05 - old[1] * 0.95;
        dz = dz * 0.05 - old[2] * 0.95;
        let len = (dx * dx + dz * dz).sqrt();
        if len != 0. && !len.is_nan() {
            dx /= len;
            dz /= len;
        }
        x += dx;
        z += dz;
        if (dx == 0. && dz == 0.)
            || x < 0.
            || z < 0.
            || x >= (o.size - 1) as f32
            || z >= (o.size - 1) as f32
        {
            return;
        }
        let delta = terrain(cells, o.size, x, z)[0] - old[0];
        let capacity = (-delta * speed * water * 4.).max(0.01);
        if sediment > capacity || delta > 0. {
            let amount = if delta > 0. {
                delta.min(sediment)
            } else {
                (sediment - capacity) * o.deposit
            };
            sediment -= amount;
            cells[index].deposit(amount * (1. - ox) * (1. - oz), o.ground, o.erosion_max);
            cells[index + 1].deposit(amount * ox * (1. - oz), o.ground, o.erosion_max);
            cells[index + o.size].deposit(amount * (1. - ox) * oz, o.ground, o.erosion_max);
            cells[index + o.size + 1].deposit(amount * ox * oz, o.ground, o.erosion_max);
        } else {
            let amount = ((capacity - sediment) * o.erosion).min(-delta);
            for &(offset, weight) in brush(o.size, nx, nz, brushes) {
                let cell = &mut cells[(index as isize + offset) as usize];
                let delta = cell.values[0].min(amount * weight);
                cell.erode(delta, o.ground, o.erosion_max);
                sediment += delta;
            }
        }
        speed = (speed * speed + delta * 3.).sqrt();
        water *= 0.99;
        if speed.is_nan() {
            speed = 0.;
        }
    }
}
fn smooth(cells: &mut [Cell], o: &Options) {
    // Preserve upstream in-place Z/X traversal; Jacobi updates change the terrain.
    let radius = (o.radius + 1.) as usize;
    if radius * 2 >= o.size {
        return;
    }
    let square = o.radius * o.radius;
    for _ in 0..o.smoothing_iterations {
        for z in radius..o.size - radius {
            for x in radius..o.size - radius {
                let index = z * o.size + x;
                if cells[index].flags & 1 != 0 {
                    continue;
                }
                let (mut total, mut weights) = (0., 0.);
                for dz in -(radius as i32)..=radius as i32 {
                    for dx in -(radius as i32)..=radius as i32 {
                        let distance = (dx * dx + dz * dz) as f32;
                        if distance <= square {
                            let other = &cells
                                [(z as i32 + dz) as usize * o.size + (x as i32 + dx) as usize];
                            if other.flags & 2 == 0 {
                                let weight = 1. - distance / square;
                                total += other.values[0] * weight;
                                weights += weight;
                            }
                        }
                    }
                }
                if weights > 0. {
                    let difference = cells[index].values[0] - total / weights;
                    let change = cells[index].modify(
                        difference * o.rate,
                        o.smoothing_min,
                        o.smoothing_max,
                        true,
                    );
                    cells[index].values[0] -= change;
                }
            }
        }
    }
}

fn neighbour(cells: &[Cell], size: usize, x: i32, z: i32) -> Option<&Cell> {
    // Tile.getCellRaw bounds-checks the flattened index, not each coordinate.
    let index = z as i64 * size as i64 + x as i64;
    if index < 0 || index >= cells.len() as i64 {
        return None;
    }
    let cell = &cells[index as usize];
    if cell.flags & 2 != 0 {
        None
    } else {
        Some(cell)
    }
}
fn required(cells: &mut [Cell], o: &Options) {
    for z in 0..o.size {
        for x in 0..o.size {
            let index = z * o.size + x;
            let mut total = 0.;
            for dz in -1..=2 {
                for dx in -1..=2 {
                    if dx == 0 && dz == 0 {
                        continue;
                    }
                    if let Some(other) = neighbour(cells, o.size, x as i32 + dx, z as i32 + dz) {
                        total +=
                            (cells[index].values[0] - other.values[0].max(o.water_level)).abs();
                    }
                }
            }
            cells[index].values[3] = total * 10.;
        }
    }
    for x in 0..o.size {
        for z in 0..o.size {
            let index = z * o.size + x;
            let cell = cells[index];
            if cell.flags & 4 == 0 || cell.flags & 8 != 0 || cell.values[7] >= o.beach {
                continue;
            }
            let gradient = |ax, az, bx, bz| {
                let a = neighbour(cells, o.size, ax, az);
                let b = neighbour(cells, o.size, bx, bz);
                let distance =
                    17 - if a.is_none() { 8 } else { 0 } - if b.is_none() { 8 } else { 0 };
                (a.unwrap_or(&cell).values[0] - b.unwrap_or(&cell).values[0]) / distance as f32
            };
            let gx = gradient(x as i32 + 8, z as i32, x as i32 - 8, z as i32);
            let gz = gradient(x as i32, z as i32 - 8, x as i32, z as i32 + 8);
            if gx * gx + gz * gz < 0.275 {
                cells[index].flags |= 32 | 256;
            }
        }
    }
    if !o.optional {
        return;
    }
    for x in (0..o.size).step_by(4) {
        for z in (0..o.size).step_by(4) {
            let mut beach = false;
            for dx in 0..4 {
                for dz in 0..4 {
                    let cell = cells[(z + dz) * o.size + x + dx];
                    if cell.flags & 32 != 0
                        || (cell.flags & 16 != 0 && cell.values[0] > o.water_level)
                    {
                        beach = true;
                    }
                }
            }
            if beach {
                for dx in 0..4 {
                    for dz in 0..4 {
                        cells[(z + dz) * o.size + x + dx].flags |= 256;
                    }
                }
            }
        }
    }
}
