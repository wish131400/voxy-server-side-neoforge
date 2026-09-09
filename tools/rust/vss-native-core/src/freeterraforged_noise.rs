//! FreeTerraForged 0.0.6005 noise modules. Float arithmetic and seed wrapping
//! follow the released source, including its non-mathematical negative floor.
use crate::density::{integer, number, string, Result};
use serde_json::Value;
use std::collections::HashSet;
use std::sync::Arc;

pub struct Noise {
    node: Node,
    min: f32,
    max: f32,
}
type N = Arc<Noise>;
enum Node {
    Constant(f32),
    Fractal {
        kind: u8,
        frequency: f32,
        octaves: i32,
        lacunarity: f32,
        gain: f32,
        curve: u8,
        seed: i32,
        bound: f32,
    },
    White(f32),
    Shift(N, i32),
    Frequency(N, N, N),
    Binary(u8, N, N),
    Abs(N),
    Invert(N),
    Power(N, f32),
    Clamp(N, N, N),
    Map(N, N, N),
    Alpha(N, N),
    Threshold(N, N, N, N),
}

impl Noise {
    pub fn parse(value: &Value, doc: &Value) -> Result<N> {
        Self::read(value, doc, &mut HashSet::new(), &mut 0, 0)
    }
    fn read(
        value: &Value,
        doc: &Value,
        active: &mut HashSet<String>,
        count: &mut usize,
        depth: usize,
    ) -> Result<N> {
        *count += 1;
        if *count > 65536 || depth > 128 {
            return Err("FTF noise graph budget exceeded".into());
        }
        if let Some(name) = value.as_str() {
            if !active.insert(name.into()) {
                return Err(format!("cyclic FTF noise reference {name}"));
            }
            let resolved = doc["custom_registries"]["reterraforged:worldgen/noise"]
                .get(name)
                .ok_or_else(|| format!("missing FTF noise {name}"))?;
            let result = Self::read(resolved, doc, active, count, depth + 1);
            active.remove(name);
            return result;
        }
        if let Some(n) = value.as_f64() {
            return constant(n as f32);
        }
        let mut child = |key: &str| Self::read(&value[key], doc, active, count, depth + 1);
        let kind = string(value, "type")?;
        let f = |key| -> Result<f32> {
            let n = number(value, key)? as f32;
            if n.is_finite() {
                Ok(n)
            } else {
                Err(format!("invalid FTF float {key}"))
            }
        };
        let (node, min, max) = match kind {
            "reterraforged:constant" => return constant(f("value")?),
            "reterraforged:perlin"
            | "reterraforged:perlin2"
            | "reterraforged:simplex"
            | "reterraforged:simplex2" => {
                let k = match kind {
                    "reterraforged:perlin" => 0,
                    "reterraforged:perlin2" => 1,
                    "reterraforged:simplex" => 2,
                    _ => 3,
                };
                let octaves = integer(value, "octaves")?;
                if !(1..=128).contains(&octaves) {
                    return Err("invalid FTF octave count".into());
                }
                let gain = f("gain")?;
                let signals = if k < 2 {
                    [1., 0.9, 0.83, 0.75, 0.64, 0.62, 0.61]
                } else {
                    [1., 0.989, 0.81, 0.781, 0.708, 0.702, 0.696]
                };
                let mut amp = if k < 2 { gain } else { 1. };
                let mut bound = 0.;
                for _ in 0..octaves {
                    bound += amp * signals[(octaves as usize).min(6)];
                    amp *= gain;
                }
                let curve = match string(&value["interpolation"], "value")? {
                    "LINEAR" => 0,
                    "CURVE3" => 1,
                    "CURVE4" => 2,
                    s => return Err(format!("invalid FTF interpolation {s}")),
                };
                (
                    Node::Fractal {
                        kind: k,
                        frequency: f("frequency")?,
                        octaves,
                        lacunarity: f("lacunarity")?,
                        gain,
                        curve,
                        seed: if k < 2 { integer(value, "seed")? } else { 0 },
                        bound,
                    },
                    0.,
                    1.,
                )
            }
            "reterraforged:white" => (Node::White(f("frequency")?), 0., 1.),
            "reterraforged:shift" => {
                let n = child("input")?;
                let bounds = (n.min, n.max);
                (Node::Shift(n, integer(value, "shift")?), bounds.0, bounds.1)
            }
            "reterraforged:frequency" => {
                let n = child("input")?;
                let bounds = (n.min, n.max);
                (
                    Node::Frequency(n, child("x_freq")?, child("z_freq")?),
                    bounds.0,
                    bounds.1,
                )
            }
            "reterraforged:add"
            | "reterraforged:multiply"
            | "reterraforged:min"
            | "reterraforged:max" => {
                let a = child("input1")?;
                let b = child("input2")?;
                let op = match kind {
                    "reterraforged:add" => 0,
                    "reterraforged:multiply" => 1,
                    "reterraforged:min" => 2,
                    _ => 3,
                };
                let min = binary(op, a.min, b.min);
                let max = binary(op, a.max, b.max);
                (Node::Binary(op, a, b), min, max)
            }
            "reterraforged:abs" | "reterraforged:invert" | "reterraforged:power" => {
                let n = child("input")?;
                let (min, max) = (n.min, n.max);
                if kind.ends_with(":abs") {
                    (Node::Abs(n), min.abs(), max.abs())
                } else if kind.ends_with(":invert") {
                    (Node::Invert(n), min, max)
                } else {
                    (Node::Power(n, f("power")?), min, max)
                }
            }
            "reterraforged:clamp" | "reterraforged:map" => {
                let mapping = kind.ends_with(":map");
                let n = child(if mapping { "alpha" } else { "input" })?;
                let a = child(if mapping { "from" } else { "min" })?;
                let b = child(if mapping { "to" } else { "max" })?;
                let (min, max) = (a.min, b.max);
                (
                    if mapping {
                        Node::Map(n, a, b)
                    } else {
                        Node::Clamp(n, a, b)
                    },
                    min,
                    max,
                )
            }
            "reterraforged:alpha" => {
                let n = child("input")?;
                let bounds = (n.min, n.max);
                (Node::Alpha(n, child("alpha")?), bounds.0, bounds.1)
            }
            "reterraforged:threshold" => {
                let n = child("input")?;
                let bounds = (n.min, n.max);
                (
                    Node::Threshold(n, child("lower")?, child("upper")?, child("threshold")?),
                    bounds.0,
                    bounds.1,
                )
            }
            _ => return Err(format!("unsupported FTF noise codec {kind}")),
        };
        Ok(Arc::new(Self { node, min, max }))
    }

    pub fn compute(&self, x: f32, z: f32, seed: i32) -> f32 {
        let ev = |n: &N| n.compute(x, z, seed);
        match &self.node {
            Node::Constant(n) => *n,
            Node::Fractal {
                kind,
                frequency,
                octaves,
                lacunarity,
                gain,
                curve,
                seed: fixed,
                bound,
            } => {
                let mut x = x * frequency;
                let mut z = z * frequency;
                let mut amplitude = if *kind < 2 { *gain } else { 1. };
                let base = if *kind < 2 { *fixed } else { seed };
                let mut sum = 0.;
                for i in 0..*octaves {
                    sum += if *kind < 2 {
                        perlin(x, z, base.wrapping_add(i), *curve, *kind == 1) * amplitude
                    } else {
                        simplex(
                            x,
                            z,
                            base.wrapping_add(i),
                            if *kind == 2 { 79.869484 } else { 99.83685 },
                        ) * amplitude
                    };
                    x *= lacunarity;
                    z *= lacunarity;
                    amplitude *= gain;
                }
                map(sum, -*bound, *bound)
            }
            Node::White(frequency) => val(seed, round(x * frequency), round(z * frequency)).abs(),
            Node::Shift(n, shift) => n.compute(x, z, seed.wrapping_add(*shift)),
            Node::Frequency(n, a, b) => n.compute(x * ev(a), z * ev(b), seed),
            Node::Binary(op, a, b) => {
                let a = ev(a);
                if *op == 1 && a == 0. {
                    0.
                } else {
                    binary(*op, a, ev(b))
                }
            }
            Node::Abs(n) => ev(n).abs(),
            Node::Invert(n) => n.max - clamp(ev(n), n.min, n.max),
            Node::Power(n, power) => (ev(n) as f64).powf(*power as f64) as f32,
            Node::Clamp(n, a, b) => clamp(ev(n), ev(a), ev(b)),
            Node::Map(n, a, b) => {
                let alpha = (ev(n) - n.min) / (n.max - n.min);
                let min = ev(a);
                min + alpha * (ev(b) - min)
            }
            Node::Alpha(n, a) => {
                let n = ev(n);
                let a = ev(a);
                n * a + (1. - a)
            }
            Node::Threshold(n, lo, hi, t) => {
                if ev(n) > ev(t) {
                    ev(hi)
                } else {
                    ev(lo)
                }
            }
        }
    }
}
fn constant(n: f32) -> Result<N> {
    if !n.is_finite() {
        return Err("nonfinite FTF constant".into());
    }
    Ok(Arc::new(Noise {
        node: Node::Constant(n),
        min: n,
        max: n,
    }))
}
fn binary(op: u8, a: f32, b: f32) -> f32 {
    match op {
        0 => a + b,
        1 => a * b,
        2 => {
            if a.is_nan() || b.is_nan() {
                f32::NAN
            } else {
                a.min(b)
            }
        }
        _ => {
            if a.is_nan() || b.is_nan() {
                f32::NAN
            } else {
                a.max(b)
            }
        }
    }
}
fn clamp(n: f32, min: f32, max: f32) -> f32 {
    if n < min {
        min
    } else if n > max {
        max
    } else {
        n
    }
}
fn map(n: f32, min: f32, max: f32) -> f32 {
    let d = clamp(n, min, max) - min;
    let range = max - min;
    if d >= range {
        1.
    } else {
        d / range
    }
}
fn floor(n: f32) -> i32 {
    if n >= 0. {
        n as i32
    } else {
        (n as i32).wrapping_sub(1)
    }
}
fn round(n: f32) -> i32 {
    if n >= 0. {
        (n + 0.5) as i32
    } else {
        (n - 0.5) as i32
    }
}
fn raw_hash(seed: i32, x: i32, z: i32) -> i32 {
    let n = seed ^ x.wrapping_mul(1619) ^ z.wrapping_mul(31337);
    n.wrapping_mul(n).wrapping_mul(n).wrapping_mul(60493)
}
fn val(seed: i32, x: i32, z: i32) -> f32 {
    raw_hash(seed, x, z) as f32 / 2147483648.
}
fn grad(seed: i32, x: i32, z: i32, dx: f32, dz: f32, wide: bool) -> f32 {
    let n = raw_hash(seed, x, z);
    let hash = n ^ (n >> 13);
    let (a, b) = if wide {
        GRAD24[(((hash & 0x3fffff) as f32 * 1.3333334) as i32 & 31) as usize]
    } else {
        [
            (-1., -1.),
            (1., -1.),
            (-1., 1.),
            (1., 1.),
            (0., -1.),
            (-1., 0.),
            (0., 1.),
            (1., 0.),
        ][(hash & 7) as usize]
    };
    dx * a + dz * b
}
fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + t * (b - a)
}
fn curve(t: f32, c: u8) -> f32 {
    match c {
        0 => t,
        1 => t * t * (3. - 2. * t),
        _ => t * t * t * (t * (t * 6. - 15.) + 10.),
    }
}
fn perlin(x: f32, z: f32, seed: i32, c: u8, wide: bool) -> f32 {
    let ix = floor(x);
    let iz = floor(z);
    let ix1 = ix.wrapping_add(1);
    let iz1 = iz.wrapping_add(1);
    let dx = x - ix as f32;
    let dz = z - iz as f32;
    let sx = curve(dx, c);
    let sz = curve(dz, c);
    let a = lerp(
        grad(seed, ix, iz, dx, dz, wide),
        grad(seed, ix1, iz, dx - 1., dz, wide),
        sx,
    );
    let b = lerp(
        grad(seed, ix, iz1, dx, dz - 1., wide),
        grad(seed, ix1, iz1, dx - 1., dz - 1., wide),
        sx,
    );
    lerp(a, b, sz)
}
fn simplex(x: f32, z: f32, seed: i32, scaler: f32) -> f32 {
    let t = (x + z) * 0.36602542;
    let i = floor(x + t);
    let j = floor(z + t);
    let t = i.wrapping_add(j) as f32 * 0.21132487;
    let x0 = x - (i as f32 - t);
    let z0 = z - (j as f32 - t);
    let (di, dj) = if x0 > z0 { (1, 0) } else { (0, 1) };
    let x1 = x0 - di as f32 + 0.21132487;
    let z1 = z0 - dj as f32 + 0.21132487;
    let x2 = x0 - 1. + 0.42264974;
    let z2 = z0 - 1. + 0.42264974;
    let corner = |dx, dz, i, j| {
        let t = 0.5 - dx * dx - dz * dz;
        if t < 0. {
            0.
        } else {
            let t = t * t;
            t * t * grad(seed, i, j, dx, dz, true)
        }
    };
    scaler
        * (corner(x0, z0, i, j)
            + corner(x1, z1, i.wrapping_add(di), j.wrapping_add(dj))
            + corner(x2, z2, i.wrapping_add(1), j.wrapping_add(1)))
}
const GRAD24: [(f32, f32); 32] = [
    (0.13052619, 0.9914449),
    (0.38268343, 0.9238795),
    (0.6087614, 0.7933533),
    (0.6087614, 0.7933533),
    (0.7933533, 0.6087614),
    (0.9238795, 0.38268343),
    (0.9914449, 0.13052619),
    (0.9914449, 0.13052619),
    (0.9914449, -0.13052619),
    (0.9238795, -0.38268343),
    (0.7933533, -0.6087614),
    (0.7933533, -0.6087614),
    (0.6087614, -0.7933533),
    (0.38268343, -0.9238795),
    (0.13052619, -0.9914449),
    (0.13052619, -0.9914449),
    (-0.13052619, -0.9914449),
    (-0.38268343, -0.9238795),
    (-0.6087614, -0.7933533),
    (-0.6087614, -0.7933533),
    (-0.7933533, -0.6087614),
    (-0.9238795, -0.38268343),
    (-0.9914449, -0.13052619),
    (-0.9914449, -0.13052619),
    (-0.9914449, 0.13052619),
    (-0.9238795, 0.38268343),
    (-0.7933533, 0.6087614),
    (-0.7933533, 0.6087614),
    (-0.6087614, 0.7933533),
    (-0.38268343, 0.9238795),
    (-0.13052619, 0.9914449),
    (-0.13052619, 0.9914449),
];
