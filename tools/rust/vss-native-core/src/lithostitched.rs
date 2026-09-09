//! Lithostitched 1.8 FastNoiseConfig, using the same upstream FastNoise Lite.
use crate::density::{integer, number, string, Result};
use fastnoise_lite::{
    CellularDistanceFunction, CellularReturnType, FastNoiseLite, FractalType, NoiseType,
};
use serde_json::Value;

pub fn noise(seed: i64, value: &Value, doc: &Value) -> Result<FastNoiseLite> {
    let config = if let Some(name) = value.as_str() {
        doc["custom_registries"]["lithostitched:fast_noise_config"]
            .get(name)
            .ok_or_else(|| format!("missing Lithostitched FastNoise config {name}"))?
    } else {
        value
    };
    let salt = if config.get("salt").is_some() {
        integer(config, "salt")?
    } else {
        0
    };
    let mut noise = FastNoiseLite::with_seed((seed as i32).wrapping_add(salt));
    noise.set_frequency(Some(number(config, "frequency")? as f32));
    noise.set_fractal_type(Some(FractalType::None));
    match string(config, "type")? {
        "lithostitched:perlin" => noise.set_noise_type(Some(NoiseType::Perlin)),
        "lithostitched:simplex" => {
            noise.set_noise_type(Some(NoiseType::OpenSimplex2S));
            noise.set_fractal_type(Some(
                match config["fractal_type"].as_str().unwrap_or("none") {
                    "none" => FractalType::None,
                    "fbm" => FractalType::FBm,
                    "ridged" => FractalType::Ridged,
                    "ping_pong" => FractalType::PingPong,
                    "domain_warp_progressive" => FractalType::DomainWarpProgressive,
                    "domain_warp_independent" => FractalType::DomainWarpIndependent,
                    kind => return Err(format!("unknown FastNoise fractal type {kind}")),
                },
            ));
            if config.get("octaves").is_some() {
                let octaves = integer(config, "octaves")?;
                if !(0..=128).contains(&octaves) {
                    return Err("FastNoise octave budget exceeded".into());
                }
                noise.set_fractal_octaves(Some(octaves));
            }
            if config.get("lacunarity").is_some() {
                noise.set_fractal_lacunarity(Some(number(config, "lacunarity")? as f32));
            }
            if config.get("gain").is_some() {
                noise.set_fractal_gain(Some(number(config, "gain")? as f32));
            }
        }
        "lithostitched:cellular" => {
            noise.set_noise_type(Some(NoiseType::Cellular));
            noise.set_cellular_distance_function(Some(
                match string(config, "distance_function")? {
                    "euclidean" => CellularDistanceFunction::Euclidean,
                    "euclidean_squared" => CellularDistanceFunction::EuclideanSq,
                    "manhattan" => CellularDistanceFunction::Manhattan,
                    "hybrid" => CellularDistanceFunction::Hybrid,
                    kind => return Err(format!("unknown FastNoise distance function {kind}")),
                },
            ));
            noise.set_cellular_return_type(Some(match string(config, "return_type")? {
                "cell_value" => CellularReturnType::CellValue,
                "distance" => CellularReturnType::Distance,
                "distance_2" => CellularReturnType::Distance2,
                "distance_2_add" => CellularReturnType::Distance2Add,
                "distance_2_sub" => CellularReturnType::Distance2Sub,
                "distance_2_mul" => CellularReturnType::Distance2Mul,
                "distance_2_div" => CellularReturnType::Distance2Div,
                kind => return Err(format!("unknown FastNoise return type {kind}")),
            }));
            let jitter = number(config, "jitter")?;
            if !(-1.0..=1.0).contains(&jitter) {
                return Err("invalid FastNoise jitter".into());
            }
            noise.set_cellular_jitter(Some(jitter as f32));
        }
        kind => return Err(format!("unsupported FastNoise config {kind}")),
    }
    Ok(noise)
}
