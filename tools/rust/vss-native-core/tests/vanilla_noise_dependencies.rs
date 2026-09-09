use serde_json::Value;
use vss_native_core::{
    blended_noise::{BlendedNoise, BlendedParameters},
    noise::{ImprovedNoise, PerlinSimplexNoise},
    random::Random,
};

fn bits(v: &Value) -> u64 {
    u64::from_str_radix(v.as_str().unwrap(), 16).unwrap()
}
fn signed(v: &Value) -> i64 {
    v.as_str().unwrap().parse().unwrap()
}
fn exact(value: f64, v: &Value, label: &str) {
    assert_eq!(
        value.to_bits(),
        bits(v),
        "{label}: {value} expected {}",
        f64::from_bits(bits(v))
    );
}
fn parameters(v: &Value) -> BlendedParameters {
    BlendedParameters {
        xz_scale: v[0].as_f64().unwrap(),
        y_scale: v[1].as_f64().unwrap(),
        xz_factor: v[2].as_f64().unwrap(),
        y_factor: v[3].as_f64().unwrap(),
        smear_scale_multiplier: v[4].as_f64().unwrap(),
    }
}

#[test]
fn original_minecraft_noise_dependencies() {
    let input = include_str!("fixtures/minecraft-1.21.1-noise-dependencies.jsonl");
    let mut exact_checks = 0;
    let mut gaussian_checks = 0;
    let mut gaussian_bit_exact = 0;
    let mut max_gaussian_ulps = 0;
    for (line, row) in input.lines().enumerate() {
        let v: Value = serde_json::from_str(row).unwrap();
        let seed = signed(&v["seed"]);
        let kind = v["kind"].as_u64().unwrap() as u8;
        let mut random = Random::new(seed, kind);
        let label = format!("line {} seed {seed} kind {kind}", line + 1);
        let values = v["values"].as_array().unwrap();
        match v["type"].as_str().unwrap() {
            "gaussian" => {
                for (i, row) in values.iter().enumerate() {
                    if i % 13 == 1 {
                        random.set_seed(seed.wrapping_add(i as i64));
                    }
                    let value = random.next_gaussian();
                    let expected = bits(&row[0]);
                    let ulps = value.to_bits().abs_diff(expected);
                    // Java Math.log and the platform log may differ by a few ulps.
                    // Stream consumption, cached pairs and reseed state stay exact.
                    assert!(ulps <= 4, "{label}: Gaussian index {i}, error {ulps} ulps");
                    max_gaussian_ulps = max_gaussian_ulps.max(ulps);
                    gaussian_bit_exact += usize::from(ulps == 0);
                    gaussian_checks += 1;
                    assert_eq!(
                        random.next_long(),
                        signed(&row[1]),
                        "{label}: Gaussian RNG stream"
                    );
                    exact_checks += 1;
                }
            }
            "derivative" => {
                let noise = ImprovedNoise::new(&mut random);
                for row in values {
                    let p = &row[0];
                    let mut derivative = [1.25, -2.5, 3.75];
                    let value = noise.sample_with_derivative(
                        p[0].as_f64().unwrap(),
                        p[1].as_f64().unwrap(),
                        p[2].as_f64().unwrap(),
                        &mut derivative,
                    );
                    exact(value, &row[1], &label);
                    for i in 0..3 {
                        exact(derivative[i], &row[2][i], &label);
                    }
                    exact_checks += 4;
                }
            }
            "blended" => {
                if v["wired"].as_bool().unwrap() && kind == 1 {
                    random = random.positional().from_hash("minecraft:terrain");
                }
                let noise = BlendedNoise::new(&mut random, parameters(&v["parameters"])).unwrap();
                exact(noise.min_value(), &v["min"], &label);
                exact(noise.max_value(), &v["max"], &label);
                assert_eq!(
                    random.next_long(),
                    signed(&v["after"]),
                    "{label}: blended construction stream"
                );
                exact_checks += 3;
                for row in values {
                    let p = &row[0];
                    let value = noise.sample(
                        p[0].as_i64().unwrap() as i32,
                        p[1].as_i64().unwrap() as i32,
                        p[2].as_i64().unwrap() as i32,
                    );
                    exact(value, &row[1], &format!("{label} point {p}"));
                    exact_checks += 1;
                }
            }
            "perlin_simplex" => {
                let octaves: Vec<i32> = v["octaves"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|o| o.as_i64().unwrap() as i32)
                    .collect();
                let noise = PerlinSimplexNoise::new(&mut random, &octaves).unwrap();
                assert_eq!(
                    random.next_long(),
                    signed(&v["after"]),
                    "{label}: simplex construction stream"
                );
                exact_checks += 1;
                for row in values {
                    let p = &row[0];
                    let x = p[0].as_f64().unwrap();
                    let z = p[2].as_f64().unwrap();
                    exact(noise.sample(x, z, false), &row[1], &label);
                    exact(noise.sample(x, z, true), &row[2], &label);
                    exact_checks += 2;
                }
            }
            other => panic!("unknown oracle {other}"),
        }
    }
    assert!(exact_checks > 50_000);
    assert_eq!(gaussian_checks, 7168);
    println!("Noise dependencies: {exact_checks} exact checks; Gaussian {gaussian_bit_exact}/{gaussian_checks} exact, maximum {max_gaussian_ulps} ulps");
}

#[test]
fn invalid_noise_configuration_does_not_consume_random_state() {
    let mut random = Random::new(42, 1);
    let mut control = random.clone();
    let valid = BlendedParameters {
        xz_scale: 0.25,
        y_scale: 0.125,
        xz_factor: 80.0,
        y_factor: 160.0,
        smear_scale_multiplier: 8.0,
    };
    for invalid in [
        BlendedParameters {
            xz_scale: f64::NAN,
            ..valid
        },
        BlendedParameters {
            y_factor: 0.0,
            ..valid
        },
        BlendedParameters {
            smear_scale_multiplier: 9.0,
            ..valid
        },
    ] {
        assert!(BlendedNoise::new(&mut random, invalid).is_none());
    }
    for octaves in [&[][..], &[-64, 64][..], &[i32::MIN][..], &[i32::MAX][..]] {
        assert!(PerlinSimplexNoise::new(&mut random, octaves).is_none());
    }
    assert_eq!(random.next_long(), control.next_long());
}
