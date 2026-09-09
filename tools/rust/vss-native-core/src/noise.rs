//! Scalar noise kernels following Minecraft 1.21.1 evaluation order.
//! No fast-math or fused multiply-add: reference fixtures compare double bits.
use crate::random::Random;

const GRAD: [[i32; 3]; 16] = [
    [1, 1, 0],
    [-1, 1, 0],
    [1, -1, 0],
    [-1, -1, 0],
    [1, 0, 1],
    [-1, 0, 1],
    [1, 0, -1],
    [-1, 0, -1],
    [0, 1, 1],
    [0, -1, 1],
    [0, 1, -1],
    [0, -1, -1],
    [1, 1, 0],
    [0, -1, 1],
    [-1, 1, 0],
    [0, -1, -1],
];
fn dot(i: usize, x: f64, y: f64, z: f64) -> f64 {
    let g = GRAD[i];
    g[0] as f64 * x + g[1] as f64 * y + g[2] as f64 * z
}
fn lerp(t: f64, a: f64, b: f64) -> f64 {
    a + t * (b - a)
}
fn smooth(t: f64) -> f64 {
    t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
}
fn smooth_derivative(t: f64) -> f64 {
    30.0 * t * t * (t - 1.0) * (t - 1.0)
}
fn lerp2(tx: f64, ty: f64, a: f64, b: f64, c: f64, d: f64) -> f64 {
    lerp(ty, lerp(tx, a, b), lerp(tx, c, d))
}
fn lerp3(tx: f64, ty: f64, tz: f64, v: [f64; 8]) -> f64 {
    lerp(
        tz,
        lerp2(tx, ty, v[0], v[1], v[2], v[3]),
        lerp2(tx, ty, v[4], v[5], v[6], v[7]),
    )
}

#[derive(Clone)]
pub struct ImprovedNoise {
    p: [u8; 256],
    pub offsets: [f64; 3],
}
impl ImprovedNoise {
    pub fn new(random: &mut Random) -> Self {
        let offsets = [
            random.next_double() * 256.0,
            random.next_double() * 256.0,
            random.next_double() * 256.0,
        ];
        let mut p = core::array::from_fn(|i| i as u8);
        for i in 0..256 {
            let j = random.next_bounded((256 - i) as i32) as usize;
            p.swap(i, i + j);
        }
        Self { p, offsets }
    }
    fn p(&self, i: i32) -> i32 {
        self.p[(i & 255) as usize] as i32
    }
    pub fn sample(&self, x: f64, y: f64, z: f64) -> f64 {
        self.sample_scaled(x, y, z, 0.0, 0.0)
    }
    /// Accumulates the analytical derivative into the supplied vector, as the
    /// vanilla method does; it does not replace an existing derivative.
    pub fn sample_with_derivative(&self, x: f64, y: f64, z: f64, derivative: &mut [f64; 3]) -> f64 {
        let x = x + self.offsets[0];
        let y = y + self.offsets[1];
        let z = z + self.offsets[2];
        let ix = x.floor() as i32;
        let iy = y.floor() as i32;
        let iz = z.floor() as i32;
        let x = x - ix as f64;
        let y = y - iy as f64;
        let z = z - iz as f64;
        let a = self.p(ix);
        let b = self.p(ix + 1);
        let aa = self.p(a + iy);
        let ab = self.p(a + iy + 1);
        let ba = self.p(b + iy);
        let bb = self.p(b + iy + 1);
        let hashes = [
            self.p(aa + iz),
            self.p(ba + iz),
            self.p(ab + iz),
            self.p(bb + iz),
            self.p(aa + iz + 1),
            self.p(ba + iz + 1),
            self.p(ab + iz + 1),
            self.p(bb + iz + 1),
        ];
        let dots = core::array::from_fn(|i| {
            dot(
                (hashes[i] & 15) as usize,
                x - (i & 1) as f64,
                y - ((i >> 1) & 1) as f64,
                z - ((i >> 2) & 1) as f64,
            )
        });
        let sx = smooth(x);
        let sy = smooth(y);
        let sz = smooth(z);
        let gradients: [f64; 3] = core::array::from_fn(|axis| {
            lerp3(
                sx,
                sy,
                sz,
                core::array::from_fn(|i| GRAD[(hashes[i] & 15) as usize][axis] as f64),
            )
        });
        let dx = lerp2(
            sy,
            sz,
            dots[1] - dots[0],
            dots[3] - dots[2],
            dots[5] - dots[4],
            dots[7] - dots[6],
        );
        let dy = lerp2(
            sz,
            sx,
            dots[2] - dots[0],
            dots[6] - dots[4],
            dots[3] - dots[1],
            dots[7] - dots[5],
        );
        let dz = lerp2(
            sx,
            sy,
            dots[4] - dots[0],
            dots[5] - dots[1],
            dots[6] - dots[2],
            dots[7] - dots[3],
        );
        derivative[0] += gradients[0] + smooth_derivative(x) * dx;
        derivative[1] += gradients[1] + smooth_derivative(y) * dy;
        derivative[2] += gradients[2] + smooth_derivative(z) * dz;
        lerp3(sx, sy, sz, dots)
    }
    pub fn sample_scaled(&self, x: f64, y: f64, z: f64, y_scale: f64, y_max: f64) -> f64 {
        let x = x + self.offsets[0];
        let y = y + self.offsets[1];
        let z = z + self.offsets[2];
        let ix = x.floor() as i32;
        let iy = y.floor() as i32;
        let iz = z.floor() as i32;
        let x = x - ix as f64;
        let frac_y = y - iy as f64;
        let z = z - iz as f64;
        let shift = if y_scale != 0.0 {
            let limit = if y_max >= 0.0 && y_max < frac_y {
                y_max
            } else {
                frac_y
            };
            (limit / y_scale + 1.0e-7_f32 as f64).floor() * y_scale
        } else {
            0.0
        };
        let y = frac_y - shift;
        let a = self.p(ix);
        let b = self.p(ix + 1);
        let aa = self.p(a + iy);
        let ab = self.p(a + iy + 1);
        let ba = self.p(b + iy);
        let bb = self.p(b + iy + 1);
        let grad = |p, x, y, z| dot((self.p(p) & 15) as usize, x, y, z);
        let corners = [
            grad(aa + iz, x, y, z),
            grad(ba + iz, x - 1.0, y, z),
            grad(ab + iz, x, y - 1.0, z),
            grad(bb + iz, x - 1.0, y - 1.0, z),
            grad(aa + iz + 1, x, y, z - 1.0),
            grad(ba + iz + 1, x - 1.0, y, z - 1.0),
            grad(ab + iz + 1, x, y - 1.0, z - 1.0),
            grad(bb + iz + 1, x - 1.0, y - 1.0, z - 1.0),
        ];
        let sx = smooth(x);
        let sy = smooth(frac_y);
        let sz = smooth(z);
        lerp(
            sz,
            lerp(
                sy,
                lerp(sx, corners[0], corners[1]),
                lerp(sx, corners[2], corners[3]),
            ),
            lerp(
                sy,
                lerp(sx, corners[4], corners[5]),
                lerp(sx, corners[6], corners[7]),
            ),
        )
    }
}

#[derive(Clone)]
pub struct SimplexNoise(ImprovedNoise);
impl SimplexNoise {
    pub fn new(random: &mut Random) -> Self {
        Self(ImprovedNoise::new(random))
    }
    fn corner(&self, hash: i32, x: f64, y: f64, z: f64, radius: f64) -> f64 {
        let mut t = radius - x * x - y * y - z * z;
        if t < 0.0 {
            0.0
        } else {
            t *= t;
            t * t * dot((hash % 12) as usize, x, y, z)
        }
    }
    pub fn sample2(&self, x: f64, y: f64) -> f64 {
        let f = 0.5 * (3.0_f64.sqrt() - 1.0);
        let g = (3.0 - 3.0_f64.sqrt()) / 6.0;
        let skew = (x + y) * f;
        let i = (x + skew).floor() as i32;
        let j = (y + skew).floor() as i32;
        let unskew = i.wrapping_add(j) as f64 * g;
        let dx = x - (i as f64 - unskew);
        let dy = y - (j as f64 - unskew);
        let (a, b) = if dx > dy { (1, 0) } else { (0, 1) };
        let p = |v| self.0.p(v);
        let i = i & 255;
        let j = j & 255;
        let n0 = self.corner(p(i + p(j)), dx, dy, 0.0, 0.5);
        let n1 = self.corner(
            p(i + a + p(j + b)),
            dx - a as f64 + g,
            dy - b as f64 + g,
            0.0,
            0.5,
        );
        let n2 = self.corner(
            p(i + 1 + p(j + 1)),
            dx - 1.0 + 2.0 * g,
            dy - 1.0 + 2.0 * g,
            0.0,
            0.5,
        );
        70.0 * (n0 + n1 + n2)
    }
    pub fn sample3(&self, x: f64, y: f64, z: f64) -> f64 {
        let skew = (x + y + z) * (1.0 / 3.0);
        let i = (x + skew).floor() as i32;
        let j = (y + skew).floor() as i32;
        let k = (z + skew).floor() as i32;
        let unskew = i.wrapping_add(j).wrapping_add(k) as f64 * (1.0 / 6.0);
        let dx = x - (i as f64 - unskew);
        let dy = y - (j as f64 - unskew);
        let dz = z - (k as f64 - unskew);
        let (a, b) = if dx >= dy {
            if dy >= dz {
                ([1, 0, 0], [1, 1, 0])
            } else if dx >= dz {
                ([1, 0, 0], [1, 0, 1])
            } else {
                ([0, 0, 1], [1, 0, 1])
            }
        } else if dy < dz {
            ([0, 0, 1], [0, 1, 1])
        } else if dx < dz {
            ([0, 1, 0], [0, 1, 1])
        } else {
            ([0, 1, 0], [1, 1, 0])
        };
        let p = |v| self.0.p(v);
        let i = i & 255;
        let j = j & 255;
        let k = k & 255;
        let n0 = self.corner(p(i + p(j + p(k))), dx, dy, dz, 0.6);
        let n1 = self.corner(
            p(i + a[0] + p(j + a[1] + p(k + a[2]))),
            dx - a[0] as f64 + 1.0 / 6.0,
            dy - a[1] as f64 + 1.0 / 6.0,
            dz - a[2] as f64 + 1.0 / 6.0,
            0.6,
        );
        let n2 = self.corner(
            p(i + b[0] + p(j + b[1] + p(k + b[2]))),
            dx - b[0] as f64 + 1.0 / 3.0,
            dy - b[1] as f64 + 1.0 / 3.0,
            dz - b[2] as f64 + 1.0 / 3.0,
            0.6,
        );
        let n3 = self.corner(
            p(i + 1 + p(j + 1 + p(k + 1))),
            dx - 1.0 + 0.5,
            dy - 1.0 + 0.5,
            dz - 1.0 + 0.5,
            0.6,
        );
        32.0 * (n0 + n1 + n2 + n3)
    }
}

pub struct PerlinNoise {
    levels: Vec<Option<ImprovedNoise>>,
    amplitudes: Vec<f64>,
    input: f64,
    value: f64,
}
impl PerlinNoise {
    pub fn new(random: &mut Random, first: i32, amplitudes: &[f64], legacy: bool) -> Option<Self> {
        let n = amplitudes.len();
        if n == 0
            || n > 64
            || !(-64..=64).contains(&first)
            || amplitudes.iter().any(|a| !a.is_finite())
        {
            return None;
        }
        let mut levels = vec![None; n];
        if legacy {
            let zero = -first;
            if zero < n as i32 - 1 {
                return None;
            }
            let base = ImprovedNoise::new(random);
            if (0..n as i32).contains(&zero) && amplitudes[zero as usize] != 0.0 {
                levels[zero as usize] = Some(base);
            }
            for i in (0..zero).rev() {
                if i < n as i32 && amplitudes[i as usize] != 0.0 {
                    levels[i as usize] = Some(ImprovedNoise::new(random));
                } else {
                    random.consume(262);
                }
            }
        } else {
            let factory = random.positional();
            for (i, a) in amplitudes.iter().enumerate() {
                if *a != 0.0 {
                    levels[i] = Some(ImprovedNoise::new(
                        &mut factory.from_hash(&format!("octave_{}", first + i as i32)),
                    ));
                }
            }
        }
        Some(Self {
            levels,
            amplitudes: amplitudes.to_vec(),
            input: 2.0_f64.powi(first),
            value: 2.0_f64.powi(n as i32 - 1) / (2.0_f64.powi(n as i32) - 1.0),
        })
    }
    pub fn sample(&self, x: f64, y: f64, z: f64) -> f64 {
        self.sample_scaled(x, y, z, 0.0, 0.0, false)
    }
    pub fn octave(&self, index: usize) -> Option<&ImprovedNoise> {
        self.levels
            .get(self.levels.len().checked_sub(index.checked_add(1)?)?)
            .and_then(Option::as_ref)
    }
    pub fn max_broken_value(&self, y_scale: f64) -> f64 {
        let mut value = self.value;
        let mut result = 0.0;
        for (i, level) in self.levels.iter().enumerate() {
            if level.is_some() {
                result += self.amplitudes[i] * (y_scale + 2.0) * value;
            }
            value /= 2.0;
        }
        result
    }
    pub fn sample_scaled(
        &self,
        x: f64,
        y: f64,
        z: f64,
        y_scale: f64,
        y_max: f64,
        fixed_y: bool,
    ) -> f64 {
        let mut result = 0.0;
        let mut input = self.input;
        let mut value = self.value;
        for (i, level) in self.levels.iter().enumerate() {
            if let Some(noise) = level {
                let sample = noise.sample_scaled(
                    wrap(x * input),
                    if fixed_y {
                        -noise.offsets[1]
                    } else {
                        wrap(y * input)
                    },
                    wrap(z * input),
                    y_scale * input,
                    y_max * input,
                );
                result += self.amplitudes[i] * sample * value;
            }
            input *= 2.0;
            value /= 2.0;
        }
        result
    }
}
pub fn wrap(v: f64) -> f64 {
    v - (v / 33554432.0 + 0.5).floor() * 33554432.0
}

/// Octave Simplex used by biome temperature/detail noise. Positive octaves
/// use vanilla's secondary WorldgenRandom(Legacy) stream derived from octave 0.
pub struct PerlinSimplexNoise {
    levels: Vec<Option<SimplexNoise>>,
    input: f64,
    value: f64,
}
impl PerlinSimplexNoise {
    pub fn new(random: &mut Random, octaves: &[i32]) -> Option<Self> {
        let octaves: std::collections::BTreeSet<i32> = octaves.iter().copied().collect();
        let min = *octaves.first()?;
        let max = *octaves.last()?;
        if min < -64 || max > 64 || max - min + 1 > 64 {
            return None;
        }
        let count = max - min + 1;
        let base = SimplexNoise::new(random);
        let mut levels = vec![None; count as usize];
        if (0..count).contains(&max) && octaves.contains(&0) {
            levels[max as usize] = Some(base.clone());
        }
        for i in max + 1..count {
            if i >= 0 && octaves.contains(&(max - i)) {
                levels[i as usize] = Some(SimplexNoise::new(random));
            } else {
                random.consume(262);
            }
        }
        if max > 0 {
            let [x, y, z] = base.0.offsets;
            let seed = (base.sample3(x, y, z) * 9.223372e18_f32 as f64) as i64;
            let mut secondary = Random::new(seed, 2);
            for i in (0..max).rev() {
                if i < count && octaves.contains(&(max - i)) {
                    levels[i as usize] = Some(SimplexNoise::new(&mut secondary));
                } else {
                    secondary.consume(262);
                }
            }
        }
        Some(Self {
            levels,
            input: 2.0_f64.powi(max),
            value: 1.0 / (2.0_f64.powi(count) - 1.0),
        })
    }
    pub fn sample(&self, x: f64, z: f64, use_offsets: bool) -> f64 {
        let mut result = 0.0;
        let mut input = self.input;
        let mut value = self.value;
        for level in &self.levels {
            if let Some(noise) = level {
                result += noise.sample2(
                    x * input + if use_offsets { noise.0.offsets[0] } else { 0.0 },
                    z * input + if use_offsets { noise.0.offsets[1] } else { 0.0 },
                ) * value;
            }
            input /= 2.0;
            value *= 2.0;
        }
        result
    }
}

pub struct NormalNoise {
    first: PerlinNoise,
    second: PerlinNoise,
    factor: f64,
}
impl NormalNoise {
    pub fn new(random: &mut Random, first: i32, amplitudes: &[f64], legacy: bool) -> Option<Self> {
        let a = PerlinNoise::new(random, first, amplitudes, legacy)?;
        let b = PerlinNoise::new(random, first, amplitudes, legacy)?;
        let min = amplitudes.iter().position(|a| *a != 0.0);
        let max = amplitudes.iter().rposition(|a| *a != 0.0);
        let factor = match (min, max) {
            (Some(min), Some(max)) => (1.0 / 6.0) / (0.1 * (1.0 + 1.0 / ((max - min + 1) as f64))),
            _ => 0.0,
        };
        Some(Self {
            first: a,
            second: b,
            factor,
        })
    }
    pub fn sample(&self, x: f64, y: f64, z: f64) -> f64 {
        const SCALE: f64 = 1.0181268882175227;
        (self.first.sample(x, y, z) + self.second.sample(x * SCALE, y * SCALE, z * SCALE))
            * self.factor
    }
    pub fn batch(&self, points: &[[f64; 3]], output: &mut [f64]) -> bool {
        if output.len() != points.len()
            || points
                .iter()
                .flatten()
                .any(|v| !v.is_finite() || v.abs() > 30_000_000.0)
        {
            return false;
        }
        for (point, out) in points.iter().zip(output) {
            *out = self.sample(point[0], point[1], point[2]);
        }
        true
    }
}
