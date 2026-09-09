//! Vanilla IntProvider and block-state providers used by vegetation.
use crate::{
    blocks::{Palette, Pos, StateId},
    density::{clamped_lerp, integer, minecraft_type, number, string, Result},
    noise::NormalNoise,
    random::Random,
};
use serde_json::Value;
pub enum IntProvider {
    Constant(i32),
    Uniform(i32, i32),
    Biased(i32, i32),
    Clamped(Box<Self>, i32, i32),
    Normal(f32, f32, i32, i32),
    Weighted(Vec<(Self, i32)>, i32),
}
impl IntProvider {
    pub fn parse(v: &Value) -> Result<Self> {
        Self::parse_depth(v, 0)
    }
    fn parse_depth(v: &Value, depth: usize) -> Result<Self> {
        if depth > 64 {
            return Err("int provider recursion limit".into());
        }
        if let Some(c) = v.as_i64() {
            return Ok(Self::Constant(
                i32::try_from(c).map_err(|_| "integer constant overflow")?,
            ));
        }
        let t = minecraft_type(v)?;
        let value = &v["value"];
        let value = if value.is_null() { v } else { value };
        let range = |v: &Value| -> Result<(i32, i32)> {
            let a = integer(v, "min_inclusive")?;
            let b = integer(v, "max_inclusive")?;
            if b < a || b as i64 - a as i64 >= i32::MAX as i64 {
                return Err("invalid int provider interval".into());
            }
            Ok((a, b))
        };
        Ok(match t {
            "constant" => Self::Constant(if value.is_number() {
                value
                    .as_i64()
                    .and_then(|v| i32::try_from(v).ok())
                    .ok_or("constant out of range")?
            } else {
                integer(value, "value")?
            }),
            "uniform" => {
                let (a, b) = range(value)?;
                Self::Uniform(a, b)
            }
            "biased_to_bottom" => {
                let (a, b) = range(value)?;
                Self::Biased(a, b)
            }
            "clamped" => {
                let (a, b) = range(value)?;
                Self::Clamped(
                    Box::new(Self::parse_depth(&value["source"], depth + 1)?),
                    a,
                    b,
                )
            }
            "clamped_normal" => {
                let (a, b) = range(value)?;
                Self::Normal(
                    number(value, "mean")? as f32,
                    number(value, "deviation")? as f32,
                    a,
                    b,
                )
            }
            "weighted_list" => {
                let mut entries = vec![];
                let mut total = 0i32;
                for entry in value["distribution"]
                    .as_array()
                    .ok_or("missing distribution")?
                {
                    let weight = integer(entry, "weight")?;
                    if weight < 0 {
                        return Err("negative weight".into());
                    }
                    total = total.checked_add(weight).ok_or("weight overflow")?;
                    entries.push((Self::parse_depth(&entry["data"], depth + 1)?, weight));
                }
                if total <= 0 {
                    return Err("empty weighted distribution".into());
                }
                Self::Weighted(entries, total)
            }
            s => return Err(format!("unsupported int provider {s}")),
        })
    }
    pub fn sample(&self, r: &mut Random) -> i32 {
        match self {
            Self::Constant(v) => *v,
            Self::Uniform(a, b) => a + r.next_bounded(b - a + 1),
            Self::Biased(a, b) => {
                let bound = r.next_bounded(b - a + 1) + 1;
                a + r.next_bounded(bound)
            }
            Self::Clamped(v, a, b) => v.sample(r).clamp(*a, *b),
            Self::Normal(mean, dev, a, b) => {
                ((*mean + r.next_gaussian() as f32 * dev).clamp(*a as f32, *b as f32)) as i32
            }
            Self::Weighted(entries, total) => {
                let mut v = r.next_bounded(*total);
                for (entry, weight) in entries {
                    v -= weight;
                    if v < 0 {
                        return entry.sample(r);
                    }
                }
                unreachable!()
            }
        }
    }
}
pub enum StateProvider {
    Simple(StateId),
    Weighted(Vec<(StateId, i32)>, i32),
    Rotated(StateId),
    RandomInt(Box<Self>, String, IntProvider),
    Noise {
        noise: NormalNoise,
        scale: f64,
        states: Vec<StateId>,
    },
    Threshold {
        noise: NormalNoise,
        scale: f64,
        threshold: f64,
        chance: f32,
        default: StateId,
        low: Vec<StateId>,
        high: Vec<StateId>,
    },
    Dual {
        noise: NormalNoise,
        scale: f64,
        slow: NormalNoise,
        slow_scale: f32,
        variety: [i32; 2],
        states: Vec<StateId>,
    },
}
fn provider_noise(v: &Value, key: &str) -> Result<NormalNoise> {
    let seed = v["seed"].as_i64().ok_or("missing provider seed")?;
    let n = &v[key];
    let first = integer(n, "firstOctave")?;
    let amplitudes: Vec<f64> = serde_json::from_value(n["amplitudes"].clone())
        .map_err(|e| format!("noise amplitudes: {e}"))?;
    NormalNoise::new(&mut Random::new(seed, 2), first, &amplitudes, false)
        .ok_or("invalid state provider noise".into())
}
fn state_list(v: &Value, p: &mut Palette) -> Result<Vec<StateId>> {
    let values = v.as_array().ok_or("missing provider states")?;
    if values.is_empty() || values.len() > 4096 {
        return Err("provider state count limit".into());
    }
    values.iter().map(|v| p.intern(v)).collect()
}
impl StateProvider {
    pub fn parse(v: &Value, p: &mut Palette) -> Result<Self> {
        Self::parse_depth(v, p, 0)
    }
    fn parse_depth(v: &Value, p: &mut Palette, depth: usize) -> Result<Self> {
        if depth > 64 {
            return Err("state provider recursion limit".into());
        }
        Ok(match minecraft_type(v)? {
            "simple_state_provider" => Self::Simple(p.intern(&v["state"])?),
            "rotated_block_provider" => {
                let name = string(&v["state"], "Name")?;
                Self::Rotated(p.named(name)?)
            }
            "weighted_state_provider" => {
                let mut entries = vec![];
                let mut total = 0i32;
                for e in v["entries"].as_array().ok_or("missing weighted states")? {
                    let weight = integer(e, "weight")?;
                    if weight < 0 {
                        return Err("negative state weight".into());
                    }
                    total = total.checked_add(weight).ok_or("state weight overflow")?;
                    entries.push((p.intern(&e["data"])?, weight));
                }
                if total <= 0 {
                    return Err("zero state weight".into());
                }
                Self::Weighted(entries, total)
            }
            "randomized_int_state_provider" => Self::RandomInt(
                Box::new(Self::parse_depth(&v["source"], p, depth + 1)?),
                string(v, "property")?.into(),
                IntProvider::parse(&v["values"])?,
            ),
            "noise_provider" => Self::Noise {
                noise: provider_noise(v, "noise")?,
                scale: number(v, "scale")? as f32 as f64,
                states: state_list(&v["states"], p)?,
            },
            "noise_threshold_provider" => Self::Threshold {
                noise: provider_noise(v, "noise")?,
                scale: number(v, "scale")? as f32 as f64,
                threshold: number(v, "threshold")? as f32 as f64,
                chance: number(v, "high_chance")? as f32,
                default: p.intern(&v["default_state"])?,
                low: state_list(&v["low_states"], p)?,
                high: state_list(&v["high_states"], p)?,
            },
            "dual_noise_provider" => {
                let range = &v["variety"];
                let (a, b) = if range.is_array() {
                    (
                        range[0].as_i64().ok_or("invalid noise variety")? as i32,
                        range[1].as_i64().ok_or("invalid noise variety")? as i32,
                    )
                } else {
                    (
                        integer(range, "min_inclusive")?,
                        integer(range, "max_inclusive")?,
                    )
                };
                if a < 1 || b < a || b > 64 {
                    return Err("invalid noise variety".into());
                }
                Self::Dual {
                    noise: provider_noise(v, "noise")?,
                    scale: number(v, "scale")? as f32 as f64,
                    slow: provider_noise(v, "slow_noise")?,
                    slow_scale: number(v, "slow_scale")? as f32,
                    variety: [a, b],
                    states: state_list(&v["states"], p)?,
                }
            }
            s => return Err(format!("unsupported state provider {s}")),
        })
    }
    pub fn state(&self, r: &mut Random, pos: Pos, p: &mut Palette) -> Result<StateId> {
        let sample = |noise: &NormalNoise, scale: f64| {
            noise.sample(
                pos[0] as f64 * scale,
                pos[1] as f64 * scale,
                pos[2] as f64 * scale,
            )
        };
        Ok(match self {
            Self::Simple(id) => *id,
            Self::Weighted(entries, total) => {
                let mut v = r.next_bounded(*total);
                let mut state = entries[0].0;
                for &(id, w) in entries {
                    v -= w;
                    if v < 0 {
                        state = id;
                        break;
                    }
                }
                state
            }
            Self::Rotated(id) => {
                let axis = ["x", "y", "z"][r.next_bounded(3) as usize];
                if p.state(*id).properties.contains_key("axis") {
                    p.with(*id, "axis", axis)?
                } else {
                    *id
                }
            }
            Self::RandomInt(source, key, values) => {
                let id = source.state(r, pos, p)?;
                if p.state(id).properties.contains_key(key) {
                    let value = values.sample(r);
                    p.with(id, key, &value.to_string())?
                } else {
                    id
                }
            }
            Self::Noise {
                noise,
                scale,
                states,
            } => choose(states, sample(noise, *scale)),
            Self::Threshold {
                noise,
                scale,
                threshold,
                chance,
                default,
                low,
                high,
            } => {
                if sample(noise, *scale) < *threshold {
                    low[r.next_bounded(low.len() as i32) as usize]
                } else if r.next_float() < *chance {
                    high[r.next_bounded(high.len() as i32) as usize]
                } else {
                    *default
                }
            }
            Self::Dual {
                noise,
                scale,
                slow,
                slow_scale,
                variety,
                states,
            } => {
                let slow_at = |pos: Pos| {
                    slow.sample(
                        (pos[0] as f32 * slow_scale) as f64,
                        (pos[1] as f32 * slow_scale) as f64,
                        (pos[2] as f32 * slow_scale) as f64,
                    )
                };
                let count = clamped_lerp(
                    (slow_at(pos) + 1.) / 2.,
                    variety[0] as f64,
                    (variety[1] + 1) as f64,
                ) as usize;
                // Vanilla constructs a list; only its selected entry is needed.
                let index =
                    (((1. + sample(noise, *scale)) / 2.).clamp(0., 0.9999) * count as f64) as i32;
                choose(
                    states,
                    slow_at([
                        pos[0].wrapping_add(index * 54545),
                        pos[1],
                        pos[2].wrapping_add(index * 34234),
                    ]),
                )
            }
        })
    }
}
fn choose(states: &[StateId], noise: f64) -> StateId {
    states[(((1. + noise) / 2.).clamp(0., 0.9999) * states.len() as f64) as usize]
}
