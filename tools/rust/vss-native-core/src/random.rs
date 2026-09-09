//! Minecraft 1.21.1 random semantics, including Java's signed overflow and
//! WorldgenRandom's *two* source calls per nextLong when wrapping xoroshiro.
use md5::{Digest, Md5};

const GOLDEN: u64 = 0x9e3779b97f4a7c15;
const SILVER: u64 = 0x6a09e667f3bcc909;
const MASK: u64 = (1 << 48) - 1;

#[derive(Clone)]
enum Core {
    Legacy(u64),
    Xoroshiro(u64, u64),
}

#[derive(Clone)]
pub struct Random {
    core: Core,
    worldgen: bool,
    gaussian: Option<f64>,
}

fn mix(mut v: u64) -> u64 {
    v = (v ^ (v >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
    v = (v ^ (v >> 27)).wrapping_mul(0x94d049bb133111eb);
    v ^ (v >> 31)
}

impl Random {
    /// kind: 0 legacy, 1 xoroshiro, 2 WorldgenRandom(legacy), 3 WorldgenRandom(xoroshiro).
    pub fn new(seed: i64, kind: u8) -> Self {
        assert!(kind < 4);
        let v = seed as u64 ^ SILVER;
        let core = if kind & 1 == 0 {
            Core::Legacy((seed as u64 ^ 25214903917) & MASK)
        } else {
            Core::Xoroshiro(mix(v), mix(v.wrapping_add(GOLDEN)))
        };
        Self {
            core,
            worldgen: kind >= 2,
            gaussian: None,
        }
    }
    pub fn xoroshiro(lo: u64, hi: u64) -> Self {
        Self {
            core: if lo | hi == 0 {
                Core::Xoroshiro(GOLDEN, SILVER)
            } else {
                Core::Xoroshiro(lo, hi)
            },
            worldgen: false,
            gaussian: None,
        }
    }
    pub fn set_seed(&mut self, seed: i64) {
        // WorldgenRandom overrides setSeed without resetting its inherited
        // Marsaglia cache. Preserve that vanilla behaviour, even on reseed.
        let gaussian = if self.worldgen { self.gaussian } else { None };
        *self = Self::new(
            seed,
            u8::from(matches!(self.core, Core::Xoroshiro(..))) + 2 * u8::from(self.worldgen),
        );
        self.gaussian = gaussian;
    }
    pub fn next_gaussian(&mut self) -> f64 {
        if let Some(value) = self.gaussian.take() {
            return value;
        }
        loop {
            let x = 2.0 * self.next_double() - 1.0;
            let y = 2.0 * self.next_double() - 1.0;
            let radius = x * x + y * y;
            if radius >= 1.0 || radius == 0.0 {
                continue;
            }
            let factor = (-2.0 * radius.ln() / radius).sqrt();
            self.gaussian = Some(y * factor);
            return x * factor;
        }
    }
    fn raw_long(&mut self) -> u64 {
        match &mut self.core {
            Core::Xoroshiro(lo, hi) => {
                let a = *lo;
                let b = *hi ^ a;
                let value = a.wrapping_add(*hi).rotate_left(17).wrapping_add(a);
                *lo = a.rotate_left(49) ^ b ^ (b << 21);
                *hi = b.rotate_left(28);
                value
            }
            Core::Legacy(_) => self.bit_long() as u64,
        }
    }
    pub fn next_bits(&mut self, bits: u32) -> i32 {
        assert!((1..=32).contains(&bits));
        match &mut self.core {
            Core::Legacy(seed) => {
                *seed = seed.wrapping_mul(25214903917).wrapping_add(11) & MASK;
                (*seed >> (48 - bits)) as i32
            }
            Core::Xoroshiro(..) => (self.raw_long() >> (64 - bits)) as i32,
        }
    }
    fn bit_long(&mut self) -> i64 {
        let hi = self.next_bits(32) as i64;
        let lo = self.next_bits(32) as i64;
        (hi << 32).wrapping_add(lo)
    }
    fn bit_semantics(&self) -> bool {
        self.worldgen || matches!(self.core, Core::Legacy(_))
    }
    pub fn next_long(&mut self) -> i64 {
        if self.bit_semantics() {
            self.bit_long()
        } else {
            self.raw_long() as i64
        }
    }
    pub fn next_int(&mut self) -> i32 {
        if self.bit_semantics() {
            self.next_bits(32)
        } else {
            self.raw_long() as i32
        }
    }
    pub fn next_bounded(&mut self, bound: i32) -> i32 {
        assert!(bound > 0);
        if self.bit_semantics() {
            if bound & (bound - 1) == 0 {
                return ((bound as i64 * self.next_bits(31) as i64) >> 31) as i32;
            }
            loop {
                let value = self.next_bits(31);
                let result = value % bound;
                if value.wrapping_sub(result).wrapping_add(bound - 1) >= 0 {
                    return result;
                }
            }
        }
        let bound = bound as u32;
        let threshold = bound.wrapping_neg() % bound;
        loop {
            let product = (self.next_int() as u32 as u64) * bound as u64;
            if product as u32 >= threshold {
                return (product >> 32) as i32;
            }
        }
    }
    pub fn next_double(&mut self) -> f64 {
        let value = if self.bit_semantics() {
            ((self.next_bits(26) as u64) << 27) + self.next_bits(27) as u64
        } else {
            self.raw_long() >> 11
        };
        value as f64 * (1.0 / 9007199254740992.0)
    }
    pub fn next_float(&mut self) -> f32 {
        self.next_bits(24) as f32 * (1.0 / 16777216.0)
    }
    pub fn next_bool(&mut self) -> bool {
        if self.bit_semantics() {
            self.next_bits(1) != 0
        } else {
            self.raw_long() & 1 != 0
        }
    }
    pub fn consume(&mut self, count: usize) {
        for _ in 0..count {
            self.next_int();
        }
    }
    pub fn positional(&mut self) -> Positional {
        match self.core {
            Core::Legacy(_) => Positional::Legacy(self.raw_long()),
            Core::Xoroshiro(..) => Positional::Xoroshiro(self.raw_long(), self.raw_long()),
        }
    }
    pub fn decoration_seed(&mut self, seed: i64, x: i32, z: i32) -> i64 {
        self.set_seed(seed);
        let a = self.next_long() | 1;
        let b = self.next_long() | 1;
        let result = (x as i64)
            .wrapping_mul(a)
            .wrapping_add((z as i64).wrapping_mul(b))
            ^ seed;
        self.set_seed(result);
        result
    }
    pub fn feature_seed(&mut self, seed: i64, index: i32, step: i32) {
        self.set_seed(
            seed.wrapping_add(index as i64)
                .wrapping_add(step.wrapping_mul(10000) as i64),
        );
    }
    pub fn large_feature_seed(&mut self, seed: i64, x: i32, z: i32) {
        self.set_seed(seed);
        let a = self.next_long();
        let b = self.next_long();
        self.set_seed((x as i64).wrapping_mul(a) ^ (z as i64).wrapping_mul(b) ^ seed);
    }
    pub fn large_feature_salt(&mut self, seed: i64, x: i32, z: i32, salt: i32) {
        self.set_seed(
            (x as i64)
                .wrapping_mul(341873128712)
                .wrapping_add((z as i64).wrapping_mul(132897987541))
                .wrapping_add(seed)
                .wrapping_add(salt as i64),
        );
    }
}

pub enum Positional {
    Legacy(u64),
    Xoroshiro(u64, u64),
}
impl Positional {
    pub fn at(&self, x: i32, y: i32, z: i32) -> Random {
        let mut hash =
            (x.wrapping_mul(3129871) as i64) ^ (z as i64).wrapping_mul(116129781) ^ y as i64;
        hash = hash
            .wrapping_mul(hash)
            .wrapping_mul(42317861)
            .wrapping_add(hash.wrapping_mul(11));
        let hash = (hash >> 16) as u64;
        match *self {
            Self::Legacy(seed) => Random::new((hash ^ seed) as i64, 0),
            Self::Xoroshiro(lo, hi) => Random::xoroshiro(hash ^ lo, hi),
        }
    }
    pub fn from_hash(&self, name: &str) -> Random {
        match *self {
            Self::Legacy(seed) => {
                let hash = name
                    .encode_utf16()
                    .fold(0i32, |a, b| a.wrapping_mul(31).wrapping_add(b as i32));
                Random::new((hash as i64 as u64 ^ seed) as i64, 0)
            }
            Self::Xoroshiro(lo, hi) => {
                let digest = Md5::digest(name.as_bytes());
                Random::xoroshiro(
                    lo ^ u64::from_be_bytes(digest[..8].try_into().unwrap()),
                    hi ^ u64::from_be_bytes(digest[8..].try_into().unwrap()),
                )
            }
        }
    }
}
