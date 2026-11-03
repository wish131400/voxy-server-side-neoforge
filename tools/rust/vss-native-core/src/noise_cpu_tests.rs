//! Exercise both compiled paths, even on a machine that selects SSE4.1.
use super::*;

#[test]
fn legacy_dispatch_matches_generic_bits_at_boundaries() {
    if !std::arch::is_x86_feature_detected!("sse4.1") {
        return;
    }
    let mut checked = 0;
    for seed in [0, -917, i64::MIN, i64::MAX] {
        let mut random = Random::new(seed, 1);
        let simplex = SimplexNoise::new(&mut random);
        let mut coords = vec![[0.; 3], [-0.; 3]];
        for edge in [-33554432., -16777216., -1., 0., 1., 16777216., 33554432.] {
            for delta in [-1e-7, 0., 1e-7] {
                coords.push([edge + delta, 63.5, -edge - delta]);
                coords.push([17.25, edge + delta, -31.875]);
            }
        }
        for _ in 0..2048 {
            coords.push(std::array::from_fn(|_| {
                (random.next_double() - 0.5) * 60000000.
            }));
        }
        for legacy in [false, true] {
            let noise = NormalNoise::new(&mut random, -4, &[1., 0.25, 0., -0.5], legacy).unwrap();
            for [x, y, z] in coords.iter().copied() {
                let expected = noise.sample_inner(x, y, z).to_bits();
                assert_eq!(unsafe { noise.sample_sse41(x, y, z) }.to_bits(), expected);
                assert_eq!(noise.sample(x, y, z).to_bits(), expected);
                checked += 1;
            }
        }
        for [x, _, z] in coords {
            let expected = simplex.sample2_inner(x, z).to_bits();
            assert_eq!(unsafe { simplex.sample2_sse41(x, z) }.to_bits(), expected);
            assert_eq!(simplex.sample2(x, z).to_bits(), expected);
            checked += 1;
        }
    }
    assert!(checked > 25000);
}
