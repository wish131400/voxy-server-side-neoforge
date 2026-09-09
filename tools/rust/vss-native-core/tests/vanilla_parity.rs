use serde_json::Value;
use vss_native_core::{
    noise::{ImprovedNoise, NormalNoise, PerlinNoise, SimplexNoise},
    random::Random,
};

fn signed(v: &Value) -> i64 {
    v.as_str().unwrap().parse().unwrap()
}
fn bits(v: &Value) -> u64 {
    u64::from_str_radix(v.as_str().unwrap(), 16).unwrap()
}
fn check(actual: f64, expected: &Value, label: &str) {
    assert_eq!(
        actual.to_bits(),
        bits(expected),
        "{label}: actual={actual:?} expected={:?}",
        f64::from_bits(bits(expected))
    );
}

#[test]
fn minecraft_1211_random_and_noise_are_bit_exact() {
    let input = include_str!("fixtures/minecraft-1.21.1-kernels.jsonl");
    let mut checks = 0;
    for (line, row) in input.lines().enumerate() {
        let v: Value = serde_json::from_str(row).unwrap();
        let seed = signed(&v["seed"]);
        let kind = v["kind"].as_u64().unwrap() as u8;
        let mut random = Random::new(seed, kind);
        let label = format!("line {} seed {seed} kind {kind}", line + 1);
        let values = v["values"].as_array().unwrap();
        match v["type"].as_str().unwrap() {
            "random" => {
                for (i, row) in values.iter().enumerate() {
                    assert_eq!(
                        random.next_int() as i64,
                        row[0].as_i64().unwrap(),
                        "{label}"
                    );
                    assert_eq!(random.next_long(), signed(&row[1]), "{label}");
                    assert_eq!(
                        random.next_bounded([1, 2, 3, 17, 256, 1073741825, i32::MAX][i % 7]) as i64,
                        row[2].as_i64().unwrap(),
                        "{label}"
                    );
                    check(random.next_double(), &row[3], &label);
                    assert_eq!(
                        random.next_float().to_bits() as i32 as i64,
                        row[4].as_i64().unwrap(),
                        "{label}"
                    );
                    assert_eq!(random.next_bool(), row[5].as_bool().unwrap(), "{label}");
                    checks += 6;
                }
            }
            "hash" | "position" => {
                let factory = random.positional();
                let mut child = if v["type"] == "hash" {
                    factory.from_hash(v["name"].as_str().unwrap())
                } else {
                    let p = &v["position"];
                    factory.at(
                        p[0].as_i64().unwrap() as i32,
                        p[1].as_i64().unwrap() as i32,
                        p[2].as_i64().unwrap() as i32,
                    )
                };
                for value in values {
                    assert_eq!(child.next_long(), signed(value), "{label}");
                    checks += 1;
                }
            }
            "worldgen" => {
                let decoration = random.decoration_seed(seed, -272, 368);
                assert_eq!(decoration, signed(&values[0]), "{label}");
                random.feature_seed(decoration, 92, 9);
                assert_eq!(random.next_long(), signed(&values[1]), "{label}");
                random.large_feature_seed(seed, -17, 23);
                assert_eq!(random.next_long(), signed(&values[2]), "{label}");
                random.large_feature_salt(seed, -17, 23, 10387312);
                assert_eq!(random.next_long(), signed(&values[3]), "{label}");
                checks += 4;
            }
            "base_noise" => {
                let improved = ImprovedNoise::new(&mut random);
                let simplex = SimplexNoise::new(&mut Random::new(seed, kind));
                for row in values {
                    let p = &row[0];
                    let x = p[0].as_f64().unwrap();
                    let y = p[1].as_f64().unwrap();
                    let z = p[2].as_f64().unwrap();
                    check(improved.sample(x, y, z), &row[1], &label);
                    check(improved.sample_scaled(x, y, z, 0.25, 0.1), &row[2], &label);
                    check(simplex.sample2(x, z), &row[3], &label);
                    check(simplex.sample3(x, y, z), &row[4], &label);
                    checks += 4;
                }
            }
            "octaves" => {
                let first = v["first"].as_i64().unwrap() as i32;
                let legacy = v["legacy"].as_bool().unwrap();
                let amps: Vec<f64> = v["amplitudes"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|a| a.as_f64().unwrap())
                    .collect();
                let perlin = PerlinNoise::new(&mut random, first, &amps, legacy).unwrap();
                let normal =
                    NormalNoise::new(&mut Random::new(seed, kind), first, &amps, legacy).unwrap();
                for row in values {
                    let p = &row[0];
                    let x = p[0].as_f64().unwrap();
                    let y = p[1].as_f64().unwrap();
                    let z = p[2].as_f64().unwrap();
                    check(perlin.sample(x, y, z), &row[1], &label);
                    check(normal.sample(x, y, z), &row[2], &label);
                    check(
                        perlin.sample_scaled(x, y, z, 0.25, 0.1, true),
                        &row[3],
                        &label,
                    );
                    checks += 3;
                }
            }
            other => panic!("unknown fixture {other}"),
        }
    }
    assert!(checks > 90_000);
    println!("{checks} bit-exact Minecraft checks passed");
}

#[test]
fn invalid_parameters_and_batch_do_not_write_output() {
    let mut r = Random::new(42, 1);
    assert!(NormalNoise::new(&mut r, 0, &[], false).is_none());
    assert!(NormalNoise::new(&mut r, -7, &[f64::NAN], false).is_none());
    assert!(NormalNoise::new(&mut r, 1, &[1.0], true).is_none());
    let n = NormalNoise::new(&mut r, -7, &[1.0, 0.0, 1.0], false).unwrap();
    let mut output = [123.0];
    assert!(!n.batch(&[[f64::NAN, 0.0, 0.0]], &mut output));
    assert_eq!(output, [123.0]);
    assert!(!n.batch(&[], &mut output));
    assert_eq!(output, [123.0]);
    assert!(n.batch(&[[-123.0, 63.5, 16777216.0]], &mut output));
    assert_eq!(
        output[0].to_bits(),
        n.sample(-123.0, 63.5, 16777216.0).to_bits()
    );
}

#[test]
fn captured_java_world_noise_and_random_match_when_supplied() {
    let Ok(path) = std::env::var("VSS_CAPTURE_KERNELS") else {
        return;
    };
    let input = std::fs::read_to_string(path).unwrap();
    let mut cache = std::collections::HashMap::new();
    let mut checks = 0;
    for line in input.lines() {
        let v: Value = serde_json::from_str(line).unwrap();
        let seed = signed(&v["seed"]);
        if v["type"] == "captured_noise" {
            let kind = v["kind"].as_u64().unwrap() as u8;
            let first = v["first"].as_i64().unwrap() as i32;
            let amps: Vec<f64> = v["amplitudes"]
                .as_array()
                .unwrap()
                .iter()
                .map(|a| a.as_f64().unwrap())
                .collect();
            let name = v["name"].as_str().unwrap();
            let key = (
                seed,
                kind,
                name.to_owned(),
                first,
                v["amplitudes"].to_string(),
            );
            let n = cache.entry(key).or_insert_with(|| {
                let mut random = Random::new(seed, kind).positional().from_hash(name);
                NormalNoise::new(&mut random, first, &amps, false).unwrap()
            });
            let p = &v["point"];
            check(
                n.sample(
                    p[0].as_f64().unwrap(),
                    p[1].as_f64().unwrap(),
                    p[2].as_f64().unwrap(),
                ),
                &v["expected"],
                &format!("{} {name} {p}", v["capture"]),
            );
            checks += 1;
        } else {
            assert_eq!(v["type"], "captured_random");
            let mut random = Random::new(seed, 3);
            let decoration = random.decoration_seed(
                seed,
                v["x"].as_i64().unwrap() as i32,
                v["z"].as_i64().unwrap() as i32,
            );
            assert_eq!(decoration, signed(&v["decorationSeed"]));
            checks += 1;
            random.feature_seed(decoration, v["featureIndex"].as_i64().unwrap() as i32, 9);
            for value in v["nextLongSequence"].as_array().unwrap() {
                assert_eq!(random.next_long(), signed(value));
                checks += 1;
            }
        }
    }
    assert!(checks > 0);
    println!("{checks} bit-exact checks against captured Java world results");
}
