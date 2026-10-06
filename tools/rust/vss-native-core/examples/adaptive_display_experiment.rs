//! Compare the visual-envelope display grid with the exact surface oracle.
//!
//! Usage:
//!   cargo run --release --example adaptive_display_experiment -- <document.json> [seed]
//!
//! Construction is outside each timer.  The strict A/B is selected with
//! `VSS_STRICT_DISPLAY=1`, which restores the pre-experiment step<=2 path.
use serde_json::{json, Value};
use std::{env, fs, time::Instant};
use vss_native_core::backend::World;

const DEFAULT_ORIGINS: [(i32, i32); 4] = [(0, 0), (1605, -1456), (-4588, -1531), (10000, 10000)];

fn origins() -> Vec<(i32, i32)> {
    let bases = env::var("VSS_DISPLAY_ORIGINS")
        .ok()
        .map(|value| {
            serde_json::from_str::<Vec<(i32, i32)>>(&value)
                .expect("origins must be JSON coordinate pairs")
        })
        .unwrap_or_else(|| DEFAULT_ORIGINS.to_vec());
    assert!(!bases.is_empty(), "at least one origin is required");
    let radius = env::var("VSS_DISPLAY_SCAN_RADIUS")
        .ok()
        .and_then(|value| value.parse::<i32>().ok())
        .unwrap_or(0)
        .max(0);
    let spacing = env::var("VSS_DISPLAY_SCAN_SPACING")
        .ok()
        .and_then(|value| value.parse::<i32>().ok())
        .unwrap_or(2048)
        .max(1);
    if radius == 0 {
        return bases;
    }
    bases
        .into_iter()
        .flat_map(|(base_x, base_z)| {
            (-radius..=radius).flat_map(move |dx| {
                (-radius..=radius).map(move |dz| (base_x + dx * spacing, base_z + dz * spacing))
            })
        })
        .collect()
}

fn steps() -> Vec<i32> {
    let parsed: Vec<_> = env::var("VSS_DISPLAY_STEPS")
        .ok()
        .into_iter()
        .flat_map(|value| value.split(',').map(str::to_owned).collect::<Vec<_>>())
        .filter_map(|value| value.parse::<i32>().ok())
        .filter(|&step| step > 0)
        .collect();
    if parsed.is_empty() {
        vec![1, 2, 4, 8, 16, 64]
    } else {
        parsed
    }
}

fn percentile(values: &mut [i32], p: f64) -> i32 {
    if values.is_empty() {
        return 0;
    }
    values.sort_unstable();
    let index = ((values.len() - 1) as f64 * p).round() as usize;
    values[index]
}

fn synthetic_document(base: &Value, ridge: bool) -> Value {
    let mut document = base.clone();
    document["biome_source"] = json!({"type":"minecraft:fixed","biome":"minecraft:plains"});
    document["settings"]["aquifers_enabled"] = json!(false);
    document["settings"]["surface_rule"] =
        json!({"type":"minecraft:block","result_state":{"Name":"minecraft:stone"}});
    let flat = json!({"type":"minecraft:y_clamped_gradient","from_y":64,"to_y":65,
        "from_value":1.0,"to_value":-1.0});
    document["settings"]["noise_router"]["final_density"] = if ridge {
        json!({"type":"minecraft:max","argument1":flat,"argument2":{
            "type":"minecraft:range_choice","input":{"type":"lithostitched:axis","axis":"x"},
            "min_inclusive":-1,"max_exclusive":1,"when_out_of_range":-1.0,
            "when_in_range":{"type":"minecraft:y_clamped_gradient","from_y":100,"to_y":101,
                "from_value":1.0,"to_value":-1.0}}})
    } else {
        flat
    };
    document
}

fn main() {
    let args: Vec<_> = env::args().collect();
    let path = args.get(1).expect("document path");
    let seed = args.get(2).map_or(0, |v| v.parse::<i64>().unwrap());
    let rounds = args.get(3).map_or(5, |v| v.parse::<usize>().unwrap());
    assert!(rounds > 0, "at least one round is required");
    let zoom_seed = env::var("VSS_DISPLAY_ZOOM_SEED")
        .ok()
        .map_or(0, |value| value.parse::<i64>().expect("invalid zoom seed"));
    let compare_strict = env::var_os("VSS_DISPLAY_COMPARE_STRICT").is_some();
    let skip_exact = env::var_os("VSS_DISPLAY_SKIP_EXACT").is_some();
    let selected_scenarios = env::var("VSS_DISPLAY_SCENARIOS").ok();
    let document: Value = serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap();
    let strict = env::var_os("VSS_STRICT_DISPLAY").is_some();
    let origins = origins();
    let steps = steps();

    println!(
        "mode={} seed={} zoom_seed={} rounds={} origins={} paired_strict={} exact={}",
        if strict { "strict" } else { "experimental" },
        seed,
        zoom_seed,
        rounds,
        origins.len(),
        compare_strict,
        !skip_exact
    );
    println!("scenario,step,columns,adaptive_grids,display_records,median_display_ms,median_exact_ms,saved_columns,height_changed,height_mae,height_p95,height_max,protected_changed,color_changed,boundary_changed,batches,hit_percent,median_strict_ms,total_display_ms,total_strict_ms,new_height_changed,new_height_mae,new_height_p95,new_height_max,new_protected_changed,new_color_changed,new_boundary_changed,reject_non_display,reject_metadata,reject_color,reject_height");

    let scenarios = [
        ("captured", document.clone()),
        ("flat", synthetic_document(&document, false)),
        ("ridge", synthetic_document(&document, true)),
    ];
    for (scenario, document) in scenarios {
        if selected_scenarios
            .as_ref()
            .is_some_and(|selected| !selected.split(',').any(|name| name == scenario))
        {
            continue;
        }
        for &step in &steps {
            let mut display_times = Vec::new();
            let mut exact_times = Vec::new();
            let mut adaptive_grids = Vec::new();
            let mut display_records = Vec::new();
            let mut saved_columns = 0usize;
            let mut all_errors = Vec::new();
            let mut height_changed = 0usize;
            let mut protected_changed = 0usize;
            let mut color_changed = 0usize;
            let mut boundary_changed = 0usize;
            let mut total_columns = 0usize;
            let mut strict_times = Vec::new();
            let mut total_display_ms = 0.;
            let mut total_strict_ms = 0.;
            let mut new_errors = Vec::new();
            let mut new_height_changed = 0usize;
            let mut new_protected_changed = 0usize;
            let mut new_color_changed = 0usize;
            let mut new_boundary_changed = 0usize;
        let mut rejects = [0usize; 4];

            for round in 0..rounds {
                let visual = World::new(seed, zoom_seed, document.clone()).unwrap();
                let exact =
                    (!skip_exact).then(|| World::new(seed, zoom_seed, document.clone()).unwrap());
                let strict_world =
                    compare_strict.then(|| World::new(seed, zoom_seed, document.clone()).unwrap());
                let mut previous_stats = [0; 6];
                for &(origin_x, origin_z) in &origins {
                    let points: Vec<_> = (0..64)
                        .map(|i| (origin_x + (i % 8) * step, origin_z + (i / 8) * step))
                        .collect();
                    // Reuse each route's world like the game does. Origins should be disjoint
                    // when measuring uncached columns. Alternate the paired execution order.
                    let mut strict_rows = None;
                    let mut strict_ms = 0.;
                if let Some(world) = strict_world.as_ref().filter(|_| round % 2 == 0) {
                    env::set_var("VSS_STRICT_DISPLAY", "1");
                        let start = Instant::now();
                        strict_rows = Some(world.display_points(&points).unwrap());
                    strict_ms = start.elapsed().as_secs_f64() * 1000.;
                        if !strict {
                            env::remove_var("VSS_STRICT_DISPLAY");
                        }
                    }

                let start = Instant::now();
                let visual_rows = visual.display_points(&points).unwrap();
                let display_ms = start.elapsed().as_secs_f64() * 1000.;
                    let counters = visual.display_query_stats();
                    let stats: [u64; 6] = std::array::from_fn(|i| counters[i] - previous_stats[i]);
                    previous_stats = counters;

                if let Some(world) = strict_world.as_ref().filter(|_| round % 2 != 0) {
                    env::set_var("VSS_STRICT_DISPLAY", "1");
                        let start = Instant::now();
                        strict_rows = Some(world.display_points(&points).unwrap());
                    strict_ms = start.elapsed().as_secs_f64() * 1000.;
                        if !strict {
                            env::remove_var("VSS_STRICT_DISPLAY");
                        }
                    }
                    let start = Instant::now();
                    let exact_rows = exact
                        .as_ref()
                        .map(|world| world.surface_points(&points).unwrap());
                    let exact_ms = if skip_exact {
                        0.
                    } else {
                        start.elapsed().as_secs_f64() * 1000.
                    };
                    let exact_rows = exact_rows.as_ref().unwrap_or(&visual_rows);
                    assert_eq!(visual_rows.len(), exact_rows.len());

                    if rounds == 1 || round >= 1 {
                        display_times.push(display_ms);
                        exact_times.push(exact_ms);
                        strict_times.push(strict_ms);
                        total_display_ms += display_ms;
                        total_strict_ms += strict_ms;
                    }
                    adaptive_grids.push(stats[2]);
                    display_records.push(stats[5]);
                    total_columns += points.len();
                if stats[2] > 0 {
                        saved_columns += stats[2] as usize * if step >= 4 { 28 } else { 32 };
                    }
                    if let Some(strict_rows) = &strict_rows {
                    if stats[2] == 0 && (1..=4).contains(&step) {
                            // Diagnose the observations used by the admission test, outside its timer.
                            let probes: Vec<_> = strict_rows
                                .iter()
                                .enumerate()
                                .filter(|(i, _)| {
                                    let (x, z) = (i % 8, i / 8);
                                    x == 0
                                        || x == 7
                                        || z == 0
                                        || z == 7
                                        || ((x == 3 || x == 4) && (z == 3 || z == 4))
                                        || (step >= 4 && (x == 2 || x == 5) && (z == 2 || z == 5))
                                })
                                .collect();
                            let first = probes[0].1.values;
                            let reason = if probes.iter().any(|(_, row)| {
                                row.values[3] & (1 << 26) == 0 || row.values[3] & (1 << 29) != 0
                            }) {
                                0
                            } else if probes.iter().any(|(_, row)| {
                                row.values[2] != first[2]
                                    || row.values[3] != first[3]
                                    || row.values[4..7] != first[4..7]
                                    || (first[2] != 0 && row.values[1] != first[1])
                            }) {
                                1
                            } else if probes.iter().any(|(_, row)| {
                                (7..10).any(|channel| {
                                    [0, 8, 16, 24].iter().any(|shift| {
                                        let a =
                                            ((row.values[channel] as u32 >> shift) & 255) as i32;
                                        let b = ((first[channel] as u32 >> shift) & 255) as i32;
                                        (a - b).abs() > if step >= 4 { 12 } else { 0 }
                                    })
                                })
                            }) {
                                2
                            } else {
                                3
                            };
                            rejects[reason] += 1;
                        }
                        for (index, (visual, baseline)) in
                            visual_rows.iter().zip(strict_rows).enumerate()
                        {
                            let delta = (visual.values[0] - baseline.values[0]).abs();
                            new_errors.push(delta);
                        new_height_changed += usize::from(delta != 0);
                            new_boundary_changed += usize::from(
                                delta != 0
                                    && (index % 8 == 0
                                        || index % 8 == 7
                                        || index / 8 == 0
                                        || index / 8 == 7),
                            );
                            new_protected_changed += usize::from(
                                visual.values[2] != baseline.values[2]
                                    || (visual.values[2] != 0
                                        && visual.values[1] != baseline.values[1])
                                    || visual.values[3..7] != baseline.values[3..7],
                            );
                            new_color_changed +=
                                usize::from(visual.values[7..10] != baseline.values[7..10]);
                        }
                    }
                    for (index, (visual, exact)) in visual_rows.iter().zip(exact_rows).enumerate() {
                        let delta = (visual.values[0] - exact.values[0]).abs();
                        all_errors.push(delta);
                        height_changed += usize::from(delta != 0);
                        let x = index % 8;
                        let z = index / 8;
                        let boundary = x == 0 || x == 7 || z == 0 || z == 7;
                        boundary_changed += usize::from(boundary && delta != 0);
                        let visual_flags = visual.values[3] & !(1 << 26 | 1 << 27 | 1 << 28);
                        let exact_flags = exact.values[3] & !(1 << 26 | 1 << 27 | 1 << 28);
                        protected_changed += usize::from(
                            visual.values[1] != exact.values[1]
                                || visual.values[2] != exact.values[2]
                                || visual_flags != exact_flags
                                || visual.values[4..7] != exact.values[4..7],
                        );
                        color_changed += usize::from(visual.values[7..10] != exact.values[7..10]);
                    }
                }
            }
            let median = |values: &mut Vec<f64>| {
                values.sort_by(f64::total_cmp);
                values[values.len() / 2]
            };
            let mut errors = all_errors;
            let p95 = percentile(&mut errors, 0.95);
            let mae = errors.iter().map(|v| *v as f64).sum::<f64>() / errors.len() as f64;
            let new_p95 = percentile(&mut new_errors, 0.95);
            let new_mae = if new_errors.is_empty() {
                0.
            } else {
                new_errors.iter().map(|v| *v as f64).sum::<f64>() / new_errors.len() as f64
            };
            let batches = rounds * origins.len();
            let accepted = adaptive_grids.iter().sum::<u64>();
            println!(
            "{scenario},{step},{total_columns},{},{},{:.4},{:.4},{saved_columns},{height_changed},{mae:.4},{p95},{},{protected_changed},{color_changed},{boundary_changed},{batches},{:.4},{:.4},{total_display_ms:.4},{total_strict_ms:.4},{new_height_changed},{new_mae:.4},{new_p95},{},{new_protected_changed},{new_color_changed},{new_boundary_changed},{},{},{},{}",
            accepted,
            display_records.iter().sum::<u64>(),
            median(&mut display_times),
            median(&mut exact_times),
            errors.iter().max().copied().unwrap_or(0),
            accepted as f64 * 100. / batches as f64,
            median(&mut strict_times),
            new_errors.iter().max().copied().unwrap_or(0),
            rejects[0], rejects[1], rejects[2], rejects[3],
        );
        }
    }
}
