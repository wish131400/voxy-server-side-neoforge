//! Minecraft 1.21.1 `old_blended_noise` density kernel. The three Perlin
//! instances share one stream and are constructed in min/max/main order.
use crate::{
    noise::{wrap, PerlinNoise},
    random::Random,
};

#[derive(Clone, Copy, Debug)]
pub struct BlendedParameters {
    pub xz_scale: f64,
    pub y_scale: f64,
    pub xz_factor: f64,
    pub y_factor: f64,
    pub smear_scale_multiplier: f64,
}
impl BlendedParameters {
    fn valid(&self) -> bool {
        [self.xz_scale, self.y_scale, self.xz_factor, self.y_factor]
            .iter()
            .all(|v| v.is_finite() && (0.001..=1000.0).contains(v))
            && self.smear_scale_multiplier.is_finite()
            && (1.0..=8.0).contains(&self.smear_scale_multiplier)
    }
}

pub struct BlendedNoise {
    min_limit: PerlinNoise,
    max_limit: PerlinNoise,
    main: PerlinNoise,
    parameters: BlendedParameters,
    xz_multiplier: f64,
    y_multiplier: f64,
}
impl BlendedNoise {
    pub fn new(random: &mut Random, parameters: BlendedParameters) -> Option<Self> {
        if !parameters.valid() {
            return None;
        }
        let min_limit = PerlinNoise::new(random, -15, &[1.0; 16], true)?;
        let max_limit = PerlinNoise::new(random, -15, &[1.0; 16], true)?;
        let main = PerlinNoise::new(random, -7, &[1.0; 8], true)?;
        Some(Self {
            min_limit,
            max_limit,
            main,
            parameters,
            xz_multiplier: 684.412 * parameters.xz_scale,
            y_multiplier: 684.412 * parameters.y_scale,
        })
    }
    pub fn sample(&self, x: i32, y: i32, z: i32) -> f64 {
        #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
        if std::arch::is_x86_feature_detected!("sse4.1") {
            // SAFETY: the octave loop is entered only on a supported CPU.
            return unsafe { self.sample_sse41(x, y, z) };
        }
        self.sample_inner(x, y, z)
    }
    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    #[target_feature(enable = "sse4.1")]
    unsafe fn sample_sse41(&self, x: i32, y: i32, z: i32) -> f64 {
        self.sample_inner(x, y, z)
    }
    #[inline(always)]
    fn sample_inner(&self, x: i32, y: i32, z: i32) -> f64 {
        let x = x as f64 * self.xz_multiplier;
        let y = y as f64 * self.y_multiplier;
        let z = z as f64 * self.xz_multiplier;
        let main_x = x / self.parameters.xz_factor;
        let main_y = y / self.parameters.y_factor;
        let main_z = z / self.parameters.xz_factor;
        let smear = self.y_multiplier * self.parameters.smear_scale_multiplier;
        let main_smear = smear / self.parameters.y_factor;
        let mut selector = 0.0;
        let mut frequency = 1.0;
        for i in 0..8 {
            if let Some(noise) = self.main.octave(i) {
                selector += noise.sample_scaled(
                    wrap(main_x * frequency),
                    wrap(main_y * frequency),
                    wrap(main_z * frequency),
                    main_smear * frequency,
                    main_y * frequency,
                ) / frequency;
            }
            frequency /= 2.0;
        }
        let blend = (selector / 10.0 + 1.0) / 2.0;
        let mut min = 0.0;
        let mut max = 0.0;
        frequency = 1.0;
        for i in 0..16 {
            let xx = wrap(x * frequency);
            let yy = wrap(y * frequency);
            let zz = wrap(z * frequency);
            let scale = smear * frequency;
            if blend < 1.0 {
                if let Some(noise) = self.min_limit.octave(i) {
                    min += noise.sample_scaled(xx, yy, zz, scale, y * frequency) / frequency;
                }
            }
            if blend > 0.0 {
                if let Some(noise) = self.max_limit.octave(i) {
                    max += noise.sample_scaled(xx, yy, zz, scale, y * frequency) / frequency;
                }
            }
            frequency /= 2.0;
        }
        let low = min / 512.0;
        let high = max / 512.0;
        let value = if blend < 0.0 {
            low
        } else if blend > 1.0 {
            high
        } else {
            low + blend * (high - low)
        };
        value / 128.0
    }
    pub fn max_value(&self) -> f64 {
        self.min_limit.max_broken_value(self.y_multiplier)
    }
    pub fn min_value(&self) -> f64 {
        -self.max_value()
    }
}

#[cfg(all(test, any(target_arch = "x86", target_arch = "x86_64")))]
mod cpu_dispatch_tests {
    use super::*;
    #[test]
    fn blended_dispatch_matches_generic_bits() {
        if !std::arch::is_x86_feature_detected!("sse4.1") {
            return;
        }
        for seed in [0, -917, i64::MIN, i64::MAX] {
            let mut random = Random::new(seed, 0);
            let noise = BlendedNoise::new(
                &mut random,
                BlendedParameters {
                    xz_scale: 0.25,
                    y_scale: 0.125,
                    xz_factor: 80.,
                    y_factor: 160.,
                    smear_scale_multiplier: 8.,
                },
            )
            .unwrap();
            for _ in 0..2048 {
                let [x, y, z] =
                    std::array::from_fn(|_| ((random.next_double() - 0.5) * 60000000.) as i32);
                let expected = noise.sample_inner(x, y, z).to_bits();
                assert_eq!(unsafe { noise.sample_sse41(x, y, z) }.to_bits(), expected);
                assert_eq!(noise.sample(x, y, z).to_bits(), expected);
            }
        }
    }
}
