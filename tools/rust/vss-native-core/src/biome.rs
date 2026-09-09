//! Biome climate tinting, temperature and BiomeManager's quart-cell zoom.
//! Colormaps and effective biome effects come from the current resource pack
//! and registry snapshot; no guessed RGB palette is embedded here.
use crate::{
    density::{number, Result},
    noise::PerlinSimplexNoise,
    random::Random,
};
use serde_json::Value;

pub struct ClimateColors {
    maps: std::sync::RwLock<(Box<[u32]>, Box<[u32]>)>,
    info: PerlinSimplexNoise,
    temperature: PerlinSimplexNoise,
    frozen: PerlinSimplexNoise,
}
#[derive(Clone, Debug)]
pub struct Biome {
    pub temperature: f32,
    pub downfall: f32,
    pub frozen: bool,
    pub grass: Option<u32>,
    pub foliage: Option<u32>,
    pub water: u32,
    pub modifier: GrassModifier,
}
#[derive(Clone, Copy, Debug)]
pub enum GrassModifier {
    None,
    DarkForest,
    Swamp,
}
impl Biome {
    pub fn from_json(v: &Value) -> Result<Self> {
        let e = &v["effects"];
        let modifier = match e["grass_color_modifier"].as_str().unwrap_or("none") {
            "none" => GrassModifier::None,
            "dark_forest" => GrassModifier::DarkForest,
            "swamp" => GrassModifier::Swamp,
            s => return Err(format!("unsupported grass modifier {s}")),
        };
        let frozen = match v["temperature_modifier"].as_str().unwrap_or("none") {
            "none" => false,
            "frozen" => true,
            s => return Err(format!("unsupported temperature modifier {s}")),
        };
        let color = |key: &str| -> Result<Option<u32>> {
            match e.get(key) {
                None => Ok(None),
                Some(v) => v
                    .as_u64()
                    .filter(|v| *v <= 0xffffff)
                    .map(|v| Some(v as u32))
                    .ok_or_else(|| format!("invalid biome color {key}")),
            }
        };
        Ok(Self {
            temperature: number(v, "temperature")? as f32,
            downfall: number(v, "downfall")? as f32,
            frozen,
            grass: color("grass_color")?,
            foliage: color("foliage_color")?,
            water: color("water_color")?.ok_or("missing water color")?,
            modifier,
        })
    }
}
impl ClimateColors {
    pub fn new(grass: Vec<u32>, foliage: Vec<u32>) -> Result<Self> {
        if grass.len() != 65536 || foliage.len() != 65536 {
            return Err("colormaps must be 256 by 256".into());
        }
        Ok(Self {
            maps: std::sync::RwLock::new((grass.into_boxed_slice(), foliage.into_boxed_slice())),
            info: PerlinSimplexNoise::new(&mut Random::new(2345, 2), &[0]).unwrap(),
            temperature: PerlinSimplexNoise::new(&mut Random::new(1234, 2), &[0]).unwrap(),
            frozen: PerlinSimplexNoise::new(&mut Random::new(3456, 2), &[-2, -1, 0]).unwrap(),
        })
    }
    fn index(b: &Biome) -> usize {
        let t = b.temperature.clamp(0., 1.) as f64;
        let rain = b.downfall.clamp(0., 1.) as f64 * t;
        ((1. - rain) * 255.) as usize * 256 + ((1. - t) * 255.) as usize
    }
    pub fn grass(&self, b: &Biome, x: f64, z: f64) -> u32 {
        let base = b
            .grass
            .unwrap_or_else(|| self.maps.read().expect("colormap lock").0[Self::index(b)]);
        match b.modifier {
            GrassModifier::None => base,
            GrassModifier::DarkForest => ((base & 16711422) + 2634762) >> 1,
            GrassModifier::Swamp => {
                if self.info.sample(x * 0.0225, z * 0.0225, false) < -0.1 {
                    5011004
                } else {
                    6975545
                }
            }
        }
    }
    pub fn foliage(&self, b: &Biome) -> u32 {
        b.foliage
            .unwrap_or_else(|| self.maps.read().expect("colormap lock").1[Self::index(b)])
    }
    pub fn replace_colormaps(&self, grass: Vec<u32>, foliage: Vec<u32>) -> Result<()> {
        if grass.len() != 65536 || foliage.len() != 65536 {
            return Err("colormaps must be 256 by 256".into());
        }
        *self.maps.write().map_err(|_| "colormap lock")? =
            (grass.into_boxed_slice(), foliage.into_boxed_slice());
        Ok(())
    }
    /// BlockColors has fixed tints for these leaves, irrespective of biome.
    pub fn leaves(&self, b: &Biome, block: &str) -> u32 {
        match block {
            "minecraft:spruce_leaves" => (-10380959i32) as u32,
            "minecraft:birch_leaves" => (-8345771i32) as u32,
            _ => self.foliage(b),
        }
    }
    pub fn temperature(&self, b: &Biome, p: [i32; 3]) -> f32 {
        let [x, y, z] = p;
        let mut t = b.temperature;
        if b.frozen {
            let a = self.frozen.sample(x as f64 * 0.05, z as f64 * 0.05, false) * 7.;
            let c = self.info.sample(x as f64 * 0.2, z as f64 * 0.2, false);
            if a + c < 0.3 && self.info.sample(x as f64 * 0.09, z as f64 * 0.09, false) < 0.8 {
                t = 0.2;
            }
        }
        if y > 80 {
            let noise =
                (self
                    .temperature
                    .sample((x as f32 / 8.) as f64, (z as f32 / 8.) as f64, false)
                    * 8.) as f32;
            t - (noise + y as f32 - 80.) * 0.05 / 40.
        } else {
            t
        }
    }
    /// Vanilla square biome blend, integer division after summing channels.
    pub fn blend(
        radius: i32,
        x: i32,
        z: i32,
        mut sample: impl FnMut(i32, i32) -> u32,
    ) -> Result<u32> {
        if !(0..=7).contains(&radius) {
            return Err("biome blend radius out of range".into());
        }
        let mut sum = [0u32; 3];
        for xx in x - radius..=x + radius {
            for zz in z - radius..=z + radius {
                let c = sample(xx, zz);
                sum[0] += (c >> 16) & 255;
                sum[1] += (c >> 8) & 255;
                sum[2] += c & 255;
            }
        }
        let count = ((radius * 2 + 1) * (radius * 2 + 1)) as u32;
        Ok((sum[0] / count) << 16 | (sum[1] / count) << 8 | sum[2] / count)
    }
}
fn next(seed: i64, salt: i64) -> i64 {
    seed.wrapping_mul(
        seed.wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407),
    )
    .wrapping_add(salt)
}
fn fiddle(seed: i64) -> f64 {
    (((seed >> 24).rem_euclid(1024) as f64 / 1024.) - 0.5) * 0.9
}
/// zoom_seed is BiomeManager.obfuscateSeed(worldSeed), supplied once in the
/// world snapshot (it is also sent by the vanilla multiplayer protocol).
pub fn zoom_quart(zoom_seed: i64, p: [i32; 3]) -> [i32; 3] {
    let shifted = p.map(|v| v.wrapping_sub(2));
    let base = shifted.map(|v| v >> 2);
    let frac = shifted.map(|v| (v & 3) as f64 / 4.);
    let mut best = f64::INFINITY;
    let mut selected = base;
    for i in 0..8 {
        let delta = [(i >> 2) & 1, (i >> 1) & 1, i & 1];
        let q = std::array::from_fn(|j| base[j] + delta[j]);
        let mut state = zoom_seed;
        for v in q.into_iter().chain(q) {
            state = next(state, v as i64)
        }
        let a = fiddle(state);
        state = next(state, zoom_seed);
        let b = fiddle(state);
        state = next(state, zoom_seed);
        let c = fiddle(state);
        let dx = frac[0] - delta[0] as f64 + a;
        let dy = frac[1] - delta[1] as f64 + b;
        let dz = frac[2] - delta[2] as f64 + c;
        let distance = dz * dz + dy * dy + dx * dx;
        if best > distance {
            best = distance;
            selected = q
        }
    }
    selected
}
