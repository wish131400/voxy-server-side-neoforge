//! Alex's Caves `ac_simplex` surface condition support.
//!
//! `ACSurfaceRuleConditionRegistry$SimplexConditionSource` evaluates a stock
//! Gustavson simplex noise: the permutation table is the classic hard-coded
//! one and there is no per-world seeding, so only the arithmetic order has to
//! be reproduced exactly. Two thin wrappers sit on top of it — `ACMath`'s
//! `sampleNoise3D` scales in `f32`, and the condition itself truncates to
//! `int` before sampling — and both must keep their original precision.

/// `ACSimplexNoise.grad3`, in declaration order.
const GRAD3: [[f64; 3]; 12] = [
    [1.0, 1.0, 0.0],
    [-1.0, 1.0, 0.0],
    [1.0, -1.0, 0.0],
    [-1.0, -1.0, 0.0],
    [1.0, 0.0, 1.0],
    [-1.0, 0.0, 1.0],
    [1.0, 0.0, -1.0],
    [-1.0, 0.0, -1.0],
    [0.0, 1.0, 1.0],
    [0.0, -1.0, 1.0],
    [0.0, 1.0, -1.0],
    [0.0, -1.0, -1.0],
];

/// `ACSimplexNoise.p` — the canonical 0..255 permutation.
const P: [u8; 256] = [
    151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225, 140, 36, 103, 30, 69,
    142, 8, 99, 37, 240, 21, 10, 23, 190, 6, 148, 247, 120, 234, 75, 0, 26, 197, 62, 94, 252, 219,
    203, 117, 35, 11, 32, 57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125, 136, 171, 168, 68, 175,
    74, 165, 71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122, 60, 211, 133, 230,
    220, 105, 92, 41, 55, 46, 245, 40, 244, 102, 143, 54, 65, 25, 63, 161, 1, 216, 80, 73, 209, 76,
    132, 187, 208, 89, 18, 169, 200, 196, 135, 130, 116, 188, 159, 86, 164, 100, 109, 198, 173, 186,
    3, 64, 52, 217, 226, 250, 124, 123, 5, 202, 38, 147, 118, 126, 255, 82, 85, 212, 207, 206, 59,
    227, 47, 16, 58, 17, 182, 189, 28, 42, 223, 183, 170, 213, 119, 248, 152, 2, 44, 154, 163, 70,
    221, 153, 101, 155, 167, 43, 172, 9, 129, 22, 39, 253, 19, 98, 108, 110, 79, 113, 224, 232, 178,
    185, 112, 104, 218, 246, 97, 228, 251, 34, 242, 193, 238, 210, 144, 12, 191, 179, 162, 241, 81,
    51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31, 181, 199, 106, 157, 184, 84, 204, 176, 115,
    121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93, 222, 114, 67, 29, 24, 72, 243, 141, 128, 195,
    78, 66, 215, 61, 156, 180,
];

/// Java's `perm` is a 512-entry table built as `p[i & 0xFF]`; folding the mask
/// into the accessor reproduces every index the algorithm can produce.
#[inline]
fn perm(i: usize) -> usize {
    P[i & 0xFF] as usize
}

/// `permMod12[...]`, i.e. `perm[...] % 12`.
#[inline]
fn perm_mod12(i: usize) -> usize {
    (P[i & 0xFF] as usize) % 12
}

/// `ACSimplexNoise.fastfloor`: truncate toward zero, then step down for negatives.
#[inline]
fn fastfloor(x: f64) -> i32 {
    let xi = x as i32;
    if x < xi as f64 {
        xi - 1
    } else {
        xi
    }
}

#[inline]
fn dot3(g: [f64; 3], x: f64, y: f64, z: f64) -> f64 {
    g[0] * x + g[1] * y + g[2] * z
}

/// `ACSimplexNoise.noise(double, double, double)`.
pub fn noise3d(xin: f64, yin: f64, zin: f64) -> f64 {
    const F3: f64 = 0.3333333333333333;
    const G3: f64 = 0.16666666666666666;

    let s = (xin + yin + zin) * F3;
    let i = fastfloor(xin + s);
    let j = fastfloor(yin + s);
    let k = fastfloor(zin + s);
    let t = (i + j + k) as f64 * G3;
    let x0 = xin - (i as f64 - t);
    let y0 = yin - (j as f64 - t);
    let z0 = zin - (k as f64 - t);

    // The eight-way corner ranking, in the original branch order.
    let (i1, j1, k1, i2, j2, k2);
    if x0 >= y0 {
        if y0 >= z0 {
            (i1, j1, k1) = (1, 0, 0);
            (i2, j2, k2) = (1, 1, 0);
        } else if x0 >= z0 {
            (i1, j1, k1) = (1, 0, 0);
            (i2, j2, k2) = (1, 0, 1);
        } else {
            (i1, j1, k1) = (0, 0, 1);
            (i2, j2, k2) = (1, 0, 1);
        }
    } else if y0 < z0 {
        (i1, j1, k1) = (0, 0, 1);
        (i2, j2, k2) = (0, 1, 1);
    } else if x0 < z0 {
        (i1, j1, k1) = (0, 1, 0);
        (i2, j2, k2) = (0, 1, 1);
    } else {
        (i1, j1, k1) = (0, 1, 0);
        (i2, j2, k2) = (1, 1, 0);
    }

    let x1 = x0 - i1 as f64 + G3;
    let y1 = y0 - j1 as f64 + G3;
    let z1 = z0 - k1 as f64 + G3;
    let x2 = x0 - i2 as f64 + 2.0 * G3;
    let y2 = y0 - j2 as f64 + 2.0 * G3;
    let z2 = z0 - k2 as f64 + 2.0 * G3;
    let x3 = x0 - 1.0 + 0.5;
    let y3 = y0 - 1.0 + 0.5;
    let z3 = z0 - 1.0 + 0.5;

    let ii = (i & 0xFF) as usize;
    let jj = (j & 0xFF) as usize;
    let kk = (k & 0xFF) as usize;
    let gi0 = perm_mod12(ii + perm(jj + perm(kk)));
    let gi1 = perm_mod12(ii + i1 as usize + perm(jj + j1 as usize + perm(kk + k1 as usize)));
    let gi2 = perm_mod12(ii + i2 as usize + perm(jj + j2 as usize + perm(kk + k2 as usize)));
    let gi3 = perm_mod12(ii + 1 + perm(jj + 1 + perm(kk + 1)));

    let mut n = [0.0f64; 4];
    for (index, (x, y, z, gi)) in [
        (x0, y0, z0, gi0),
        (x1, y1, z1, gi1),
        (x2, y2, z2, gi2),
        (x3, y3, z3, gi3),
    ]
    .into_iter()
    .enumerate()
    {
        let mut t = 0.6 - x * x - y * y - z * z;
        if t >= 0.0 {
            t *= t;
            n[index] = t * t * dot3(GRAD3[gi], x, y, z);
        }
    }
    // Java's `n0 + n1 + n2 + n3` associates to the left; regrouping would
    // change the low bits.
    let sum = ((n[0] + n[1]) + n[2]) + n[3];
    32.0 * sum
}

/// `ACMath.sampleNoise3D(int, int, int, float)`.
///
/// The Java bytecode is `(float)v; +scale; /scale; -> double`, i.e. three
/// f32 operations. Dividing instead of multiplying by the reciprocal, and
/// keeping the intermediate in f32, are both required for bit parity.
pub fn sample_noise_3d(x: i32, y: i32, z: i32, scale: f32) -> f32 {
    let scaled = |v: i32| ((v as f32) + scale) / scale;
    noise3d(
        scaled(x) as f64,
        scaled(y) as f64,
        scaled(z) as f64,
    ) as f32
}

/// `SimplexConditionSource.NoiseCondition.test()`.
#[inline]
pub fn ac_simplex_test(
    block_x: i32,
    block_y: i32,
    block_z: i32,
    noise_min: f32,
    noise_max: f32,
    noise_scale: f32,
    y_scale: f32,
    offset_type: i32,
) -> bool {
    let sx = (block_x as f32 + offset_type as f32 * 1000.0) as i32;
    // `(int)((float)blockY * yScale + (float)(offsetType * 2000))`: the
    // multiply-add happens in f32 and only then truncates.
    let sy = (block_y as f32 * y_scale + (offset_type * 2000) as f32) as i32;
    let sz = (block_z as f32 - offset_type as f32 * 3000.0) as i32;
    let f = sample_noise_3d(sx, sy, sz, noise_scale);
    f > noise_min && f <= noise_max
}
