//! Independent experimental JNI ABI. Capabilities advertise noise only, never terrain.
//! Handles are registry IDs, not Java-supplied pointers. Arc leases keep in-flight
//! batches alive across close; later calls on a removed ID fail deterministically.
use crate::{noise::NormalNoise, random::Random};
use jni::{
    objects::{JByteBuffer, JClass, JDoubleArray},
    sys::{jint, jlong},
    JNIEnv,
};
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicI64, Ordering},
        Arc, Mutex, OnceLock,
    },
};

static NOISES: OnceLock<Mutex<HashMap<i64, Arc<NormalNoise>>>> = OnceLock::new();
static IDS: AtomicI64 = AtomicI64::new(1);
fn noises() -> &'static Mutex<HashMap<i64, Arc<NormalNoise>>> {
    NOISES.get_or_init(|| Mutex::new(HashMap::new()))
}

#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_NativeNoiseReference_abi(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    1
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_NativeNoiseReference_capabilities(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    1
}

#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_NativeNoiseReference_create(
    env: JNIEnv,
    _class: JClass,
    seed: jlong,
    kind: jint,
    first: jint,
    amplitudes: JDoubleArray,
    legacy: jint,
) -> jlong {
    if !(0..=1).contains(&kind) || !(0..=1).contains(&legacy) {
        return 0;
    }
    let Ok(count) = env.get_array_length(&amplitudes) else {
        return 0;
    };
    if !(1..=64).contains(&count) {
        return 0;
    }
    let mut amps = vec![0.0; count as usize];
    if env
        .get_double_array_region(&amplitudes, 0, &mut amps)
        .is_err()
    {
        return 0;
    }
    let Some(noise) = NormalNoise::new(
        &mut Random::new(seed, kind as u8),
        first,
        &amps,
        legacy != 0,
    ) else {
        return 0;
    };
    let Ok(mut map) = noises().lock() else {
        return 0;
    };
    if map.len() >= 4096 {
        return 0;
    }
    let id = IDS.fetch_add(1, Ordering::Relaxed);
    if id <= 0 {
        return 0;
    }
    map.insert(id, Arc::new(noise));
    id
}

#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_NativeNoiseReference_close(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jint {
    let Ok(mut map) = noises().lock() else {
        return -1;
    };
    i32::from(map.remove(&id).is_some())
}

#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_NativeNoiseReference_sample(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    points: JByteBuffer,
    output: JByteBuffer,
    count: jint,
) -> jint {
    if !(0..=65536).contains(&count) {
        return -1;
    }
    let noise = match noises().lock().ok().and_then(|map| map.get(&id).cloned()) {
        Some(n) => n,
        None => return -2,
    };
    let Ok(in_capacity) = env.get_direct_buffer_capacity(&points) else {
        return -1;
    };
    let Ok(out_capacity) = env.get_direct_buffer_capacity(&output) else {
        return -1;
    };
    let Ok(read_only) = env
        .call_method(&output, "isReadOnly", "()Z", &[])
        .and_then(|r| r.z())
    else {
        return -1;
    };
    let count = count as usize;
    let in_len = count * 24;
    let out_len = count * 8;
    if read_only || in_capacity < in_len || out_capacity < out_len {
        return -1;
    }
    if count == 0 {
        return 0;
    }
    let Ok(input) = env.get_direct_buffer_address(&points) else {
        return -1;
    };
    let Ok(out) = env.get_direct_buffer_address(&output) else {
        return -1;
    };
    let a = input as usize;
    let b = out as usize;
    let Some(a_end) = a.checked_add(in_len) else {
        return -1;
    };
    let Some(b_end) = b.checked_add(out_len) else {
        return -1;
    };
    if a < b_end && b < a_end {
        return -1;
    }
    // SAFETY: JNI pins neither buffer but both Java references remain live for
    // this call. Capacities and disjoint byte ranges were checked. Accesses are
    // unaligned byte copies; read-only input and sliced buffers are supported.
    let input = unsafe { std::slice::from_raw_parts(input, in_len) };
    let value = |at: usize| {
        f64::from_le_bytes(
            input[at..at + 8]
                .try_into()
                .expect("bounded eight-byte coordinate"),
        )
    };
    for at in (0..in_len).step_by(8) {
        let n = value(at);
        if !n.is_finite() || n.abs() > 30_000_000.0 {
            return -3;
        }
    }
    let out = unsafe { std::slice::from_raw_parts_mut(out, out_len) };
    for i in 0..count {
        let at = i * 24;
        let v = noise.sample(value(at), value(at + 8), value(at + 16));
        out[i * 8..i * 8 + 8].copy_from_slice(&v.to_le_bytes());
    }
    count as jint
}
