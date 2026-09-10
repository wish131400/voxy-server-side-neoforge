//! ABI 3: separate declaration and handle namespace from
//! the noise probe. No raw Java pointer can become a world/volume handle.
use crate::{
    backend::World,
    blocks::Volume,
    density::{Mode, Result},
};
use jni::{
    objects::{JByteBuffer, JClass, JString},
    sys::{jint, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicI64, AtomicUsize, Ordering},
        Arc, Mutex, OnceLock,
    },
};
static WORLDS: OnceLock<Mutex<HashMap<i64, Arc<World>>>> = OnceLock::new();
static VOLUMES: OnceLock<Mutex<HashMap<i64, Arc<Mutex<NativeVolume>>>>> = OnceLock::new();
static IDS: AtomicI64 = AtomicI64::new(1);
static LIVE_VOLUMES: AtomicUsize = AtomicUsize::new(0);
struct Slot;
impl Slot {
    fn acquire() -> Result<Self> {
        LIVE_VOLUMES
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| {
                if n < 8 {
                    Some(n + 1)
                } else {
                    None
                }
            })
            .map(|_| Self)
            .map_err(|_| "native volume budget: release old result handles".into())
    }
}
impl Drop for Slot {
    fn drop(&mut self) {
        LIVE_VOLUMES.fetch_sub(1, Ordering::AcqRel);
    }
}
struct NativeVolume {
    owner: Arc<World>,
    volume: Volume,
    _slot: Slot,
}
fn worlds() -> &'static Mutex<HashMap<i64, Arc<World>>> {
    WORLDS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn volumes() -> &'static Mutex<HashMap<i64, Arc<Mutex<NativeVolume>>>> {
    VOLUMES.get_or_init(|| Mutex::new(HashMap::new()))
}
fn world(id: i64) -> Result<Arc<World>> {
    worlds()
        .lock()
        .map_err(|_| "world registry poisoned")?
        .get(&id)
        .cloned()
        .ok_or("closed/invalid native world handle".into())
}
fn volume(id: i64) -> Result<Arc<Mutex<NativeVolume>>> {
    volumes()
        .lock()
        .map_err(|_| "volume registry poisoned")?
        .get(&id)
        .cloned()
        .ok_or("closed/invalid native volume handle".into())
}
fn fail(env: &mut JNIEnv, e: String) {
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", e);
    }
}
fn text(env: &mut JNIEnv, v: JString, max: usize) -> Result<String> {
    let value: String = env.get_string(&v).map_err(|e| e.to_string())?.into();
    if value.len() > max {
        return Err("native input string budget".into());
    }
    Ok(value)
}
fn output(env: &mut JNIEnv, value: Result<Value>) -> jstring {
    match value {
        Ok(v) => match env.new_string(v.to_string()) {
            Ok(s) => s.into_raw(),
            Err(e) => {
                fail(env, e.to_string());
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            fail(env, e);
            std::ptr::null_mut()
        }
    }
}
fn guarded<T>(call: impl FnOnce() -> Result<T>) -> Result<T> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(call))
        .unwrap_or_else(|_| Err("native worldgen panicked; discard this job".into()))
}

#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_freeTerraForgedFilters(
    mut e: JNIEnv,
    _c: JClass,
    cells: JByteBuffer,
    settings: JString,
) {
    if let Err(err) = guarded(|| {
        let doc: Value =
            serde_json::from_str(&text(&mut e, settings, 16384)?).map_err(|e| e.to_string())?;
        let bytes = crate::freeterraforged_filters::byte_count(&doc)?;
        let ptr = buffer(&mut e, &cells, bytes, true)?;
        let result = crate::freeterraforged_filters::run(
            unsafe { std::slice::from_raw_parts(ptr, bytes) },
            &doc,
        )?;
        put(&mut e, &cells, &result)
    }) {
        fail(&mut e, err);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_colormaps(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    input: JString,
) {
    if let Err(err) = guarded(|| {
        let v: Value = serde_json::from_str(&text(&mut e, input, 4 * 1024 * 1024)?)
            .map_err(|e| e.to_string())?;
        let grass = serde_json::from_value(v["grass_colormap"].clone())
            .map_err(|e| format!("grass colormap: {e}"))?;
        let foliage = serde_json::from_value(v["foliage_colormap"].clone())
            .map_err(|e| format!("foliage colormap: {e}"))?;
        world(id)?.replace_colormaps(grass, foliage)
    }) {
        fail(&mut e, err);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_decorationEntropy(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    seed: jlong,
) {
    if let Err(err) = guarded(|| {
        let v = volume(id)?;
        let mut v = v.lock().map_err(|_| "volume lock")?;
        v.volume.decoration_entropy = Some(crate::random::Random::new(seed, 0));
        Ok(())
    }) {
        fail(&mut e, err);
    }
}
fn buffer(env: &mut JNIEnv, b: &JByteBuffer, bytes: usize, writable: bool) -> Result<*mut u8> {
    let size = env
        .get_direct_buffer_capacity(b)
        .map_err(|_| "native ABI requires a direct buffer")?;
    if size < bytes {
        return Err("native buffer too short".into());
    }
    if writable
        && env
            .call_method(b, "isReadOnly", "()Z", &[])
            .and_then(|v| v.z())
            .map_err(|e| e.to_string())?
    {
        return Err("native output buffer is read only".into());
    }
    env.get_direct_buffer_address(b).map_err(|e| e.to_string())
}
fn put(env: &mut JNIEnv, b: &JByteBuffer, data: &[u8]) -> Result<()> {
    let ptr = buffer(env, b, data.len(), true)?;
    if !data.is_empty() {
        unsafe {
            std::ptr::copy_nonoverlapping(data.as_ptr(), ptr, data.len());
        }
    }
    Ok(())
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_abi(
    _e: JNIEnv,
    _c: JClass,
) -> jint {
    3
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_create(
    mut e: JNIEnv,
    _c: JClass,
    seed: jlong,
    zoom: jlong,
    doc: JString,
) -> jlong {
    let result = guarded(|| {
        if worlds().lock().map_err(|_| "world lock")?.len() >= 4 {
            return Err("world handle budget exceeded".into());
        }
        let document = serde_json::from_str(&text(&mut e, doc, 64 * 1024 * 1024)?)
            .map_err(|e| format!("world document: {e}"))?;
        let world = Arc::new(World::new(seed, zoom, document)?);
        let mut map = worlds().lock().map_err(|_| "world lock")?;
        if map.len() >= 4 {
            return Err("world handle budget exceeded".into());
        }
        let id = IDS.fetch_add(1, Ordering::Relaxed);
        if id <= 0 {
            return Err("handle space exhausted".into());
        }
        map.insert(id, world);
        Ok(id)
    });
    match result {
        Ok(id) => id,
        Err(err) => {
            fail(&mut e, err);
            0
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_close(
    _e: JNIEnv,
    _c: JClass,
    id: jlong,
) -> jint {
    if worlds()
        .lock()
        .ok()
        .and_then(|mut m| m.remove(&id))
        .is_some()
    {
        return 1;
    }
    i32::from(
        volumes()
            .lock()
            .ok()
            .and_then(|mut m| m.remove(&id))
            .is_some(),
    )
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_describe(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
) -> jstring {
    let result = guarded(|| {
        if let Ok(w) = world(id) {
            Ok(
                json!({"kind":"world","abi":3,"min_y":w.terrain.min_y,"height":w.terrain.height,"states":World::state_table(&w.palette),"phases":["density","base_columns","surface_rules","biome_colors","configured_feature_subset","approximate_preview"],"complete_worldgen":false}),
            )
        } else {
            let v = volume(id)?;
            let v = v.lock().map_err(|_| "volume lock")?;
            Ok(
                json!({"kind":"volume","origin":v.volume.origin,"size":v.volume.size,"states":World::state_table(&v.volume.palette)}),
            )
        }
    });
    output(&mut e, result)
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_support(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    value: JString,
    placed: jint,
) -> jstring {
    let result = guarded(|| {
        let w = world(id)?;
        let value: Value =
            serde_json::from_str(&text(&mut e, value, 1024 * 1024)?).map_err(|e| e.to_string())?;
        let mut palette = w.palette.clone();
        let compiled = if placed != 0 {
            crate::vegetation::Placed::compile(&value, &w.document, &mut palette).map(|_| ())
        } else {
            crate::vegetation::Feature::compile(&value, &w.document, &mut palette).map(|_| ())
        };
        Ok(match compiled {
            Err(reason) => json!({"supported":false,"transactional":false,"reason":reason}),
            Ok(()) => match w.support(&value, placed != 0) {
                Ok(()) => json!({"supported":true,"transactional":true}),
                Err(reason) => json!({"supported":false,"transactional":true,"reason":reason}),
            },
        })
    });
    output(&mut e, result)
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_density(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    name: JString,
    points: JByteBuffer,
    out: JByteBuffer,
    count: jint,
) -> jint {
    let result = guarded(|| {
        if !(0..=65536).contains(&count) {
            return Err("density count out of range".into());
        }
        let w = world(id)?;
        let name = text(&mut e, name, 1024)?;
        let root = w.terrain.graph.root(&name)?;
        let input = buffer(&mut e, &points, count as usize * 12, false)?;
        buffer(&mut e, &out, count as usize * 8, true)?;
        let input = if count == 0 {
            &[][..]
        } else {
            unsafe { std::slice::from_raw_parts(input, count as usize * 12) }
        };
        let mut data = Vec::with_capacity(count as usize * 8);
        let mut scratch =
            w.terrain
                .graph
                .scratch(0, 0, w.terrain.cell_width, w.terrain.cell_height)?;
        for row in input.chunks_exact(12) {
            let p = std::array::from_fn(|i| {
                i32::from_le_bytes(row[i * 4..i * 4 + 4].try_into().unwrap())
            });
            if p[0].abs_diff(0) > 30_000_000
                || p[2].abs_diff(0) > 30_000_000
                || p[1].abs_diff(0) > 4096
            {
                return Err("density position outside range".into());
            }
            data.extend_from_slice(
                &w.terrain
                    .graph
                    .compute(root, p, Mode::Raw, &mut scratch)
                    .to_le_bytes(),
            );
        }
        put(&mut e, &out, &data)?;
        Ok(count)
    });
    match result {
        Ok(n) => n,
        Err(err) => {
            fail(&mut e, err);
            -1
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_columns(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    points: JByteBuffer,
    out: JByteBuffer,
    count: jint,
) -> jint {
    let result = guarded(|| {
        if !(0..=64).contains(&count) {
            return Err("column batch count out of range".into());
        }
        let w = world(id)?;
        let stride = 16 + w.terrain.height as usize * 4;
        let input = buffer(&mut e, &points, count as usize * 8, false)?;
        buffer(&mut e, &out, count as usize * stride, true)?;
        let input = if count == 0 {
            &[][..]
        } else {
            unsafe { std::slice::from_raw_parts(input, count as usize * 8) }
        };
        let mut data = Vec::with_capacity(count as usize * stride);
        for row in input.chunks_exact(8) {
            let x = i32::from_le_bytes(row[..4].try_into().unwrap());
            let z = i32::from_le_bytes(row[4..].try_into().unwrap());
            let col = w.terrain.base_column(x, z)?;
            for v in [
                col.surface_height,
                col.ocean_floor,
                col.fluid_height.unwrap_or(i32::MIN),
                w.terrain.height,
            ] {
                data.extend_from_slice(&v.to_le_bytes());
            }
            for block in col.blocks {
                data.extend_from_slice(&w.base_ids[block as usize].to_le_bytes());
            }
        }
        put(&mut e, &out, &data)?;
        Ok(count)
    });
    match result {
        Ok(n) => n,
        Err(err) => {
            fail(&mut e, err);
            -1
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_surfaceRegion(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    x: jint,
    z: jint,
    side: jint,
) -> jlong {
    let result = guarded(|| {
        let slot = Slot::acquire()?;
        let owner = world(id)?;
        let volume = owner.surface_region(x, z, side)?;
        let handle = IDS.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 {
            return Err("handle space exhausted".into());
        }
        volumes().lock().map_err(|_| "volume lock")?.insert(
            handle,
            Arc::new(Mutex::new(NativeVolume {
                owner,
                volume,
                _slot: slot,
            })),
        );
        Ok(handle)
    });
    match result {
        Ok(n) => n,
        Err(err) => {
            fail(&mut e, err);
            0
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_readVolume(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    out: JByteBuffer,
) -> jint {
    let result = guarded(|| {
        let v = volume(id)?;
        let v = v.lock().map_err(|_| "volume lock")?;
        let bytes = v.volume.blocks.len() * 4;
        let ptr = buffer(&mut e, &out, bytes, true)?;
        for (i, id) in v.volume.blocks.iter().enumerate() {
            unsafe {
                std::ptr::copy_nonoverlapping(id.to_le_bytes().as_ptr(), ptr.add(i * 4), 4);
            }
        }
        Ok(v.volume.blocks.len() as i32)
    });
    match result {
        Ok(n) => n,
        Err(err) => {
            fail(&mut e, err);
            -1
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_feature(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    value: JString,
    seed: jlong,
    x: jint,
    y: jint,
    z: jint,
) -> jstring {
    let result = guarded(|| {
        let value: Value =
            serde_json::from_str(&text(&mut e, value, 1024 * 1024)?).map_err(|e| e.to_string())?;
        let v = volume(id)?;
        let mut v = v.lock().map_err(|_| "volume lock")?;
        if x.abs_diff(0) > 30_000_000 || z.abs_diff(0) > 30_000_000 || y.abs_diff(0) > 4096 {
            return Err("feature origin outside range".into());
        }
        let owner = v.owner.clone();
        let (placed, after) = owner.feature(&mut v.volume, &value, seed, [x, y, z])?;
        Ok(json!({"placed":placed,"after":after.to_string()}))
    });
    output(&mut e, result)
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_colors(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    x: jint,
    y: jint,
    z: jint,
) -> jstring {
    let result = guarded(|| {
        if x.abs_diff(0) > 30_000_000 || z.abs_diff(0) > 30_000_000 || y.abs_diff(0) > 4096 {
            return Err("color position outside range".into());
        }
        Ok(json!(world(id)?.colors_at([x, y, z])?))
    });
    output(&mut e, result)
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_createVolume(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    x: jint,
    y: jint,
    z: jint,
    width: jint,
    height: jint,
    depth: jint,
    blocks: JByteBuffer,
) -> jlong {
    let result = guarded(|| {
        let slot = Slot::acquire()?;
        let owner = world(id)?;
        if width <= 0
            || depth <= 0
            || y != owner.terrain.min_y
            || height != owner.terrain.height
            || x.abs_diff(0) > 29_999_744
            || z.abs_diff(0) > 29_999_744
        {
            return Err("invalid volume bounds/build height".into());
        }
        let mut volume = Volume::new(
            [x, y, z],
            [width as usize, height as usize, depth as usize],
            owner.palette.clone(),
        )?;
        let bytes = volume.blocks.len() * 4;
        let ptr = buffer(&mut e, &blocks, bytes, false)?;
        let input = unsafe { std::slice::from_raw_parts(ptr, bytes) };
        for (i, row) in input.chunks_exact(4).enumerate() {
            let id = u32::from_le_bytes(row.try_into().unwrap());
            if id as usize >= volume.palette.states.len() {
                return Err("input references an invalid state ID".into());
            }
            volume.blocks[i] = id;
        }
        let handle = IDS.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 {
            return Err("handle space exhausted".into());
        }
        volumes().lock().map_err(|_| "volume lock")?.insert(
            handle,
            Arc::new(Mutex::new(NativeVolume {
                owner,
                volume,
                _slot: slot,
            })),
        );
        Ok(handle)
    });
    match result {
        Ok(id) => id,
        Err(err) => {
            fail(&mut e, err);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_schedule(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
) -> jstring {
    let result = guarded(|| {
        let w = world(id)?;
        let s = w.schedule.as_ref().ok_or("missing decoration schedule")?;
        Ok(json!(s.steps))
    });
    output(&mut e, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_placedFeature(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    name: JString,
    chunk_x: jint,
    chunk_z: jint,
    index: jint,
    step: jint,
) -> jstring {
    let result = guarded(|| {
        let name = text(&mut e, name, 32768)?;
        let v = volume(id)?;
        let mut v = v.lock().map_err(|_| "volume lock")?;
        let owner = v.owner.clone();
        let (placed, after) = owner.placed(&mut v.volume, &name, chunk_x, chunk_z, index, step)?;
        Ok(json!({"placed":placed,"after":after.to_string()}))
    });
    output(&mut e, result)
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_decorateStep(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    x: jint,
    z: jint,
    step: jint,
) -> jstring {
    let result = guarded(|| {
        let v = volume(id)?;
        let mut v = v.lock().map_err(|_| "volume lock")?;
        let owner = v.owner.clone();
        owner.decorate_step(&mut v.volume, x, z, step as usize)
    });
    output(&mut e, result)
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_surfaceColumns(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    x: jint,
    z: jint,
    out: JByteBuffer,
) -> jint {
    let result = guarded(|| {
        buffer(&mut e, &out, 256 * 40, true)?;
        let w = world(id)?;
        let rows = w.surface_columns(x, z)?;
        let mut bytes = Vec::with_capacity(256 * 40);
        for row in rows.iter() {
            for n in row.values {
                bytes.extend(n.to_le_bytes());
            }
        }
        put(&mut e, &out, &bytes)?;
        Ok(256)
    });
    match result {
        Ok(n) => n,
        Err(err) => {
            fail(&mut e, err);
            -1
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_surfacePoints(
    e: JNIEnv,
    _c: JClass,
    id: jlong,
    input: JByteBuffer,
    out: JByteBuffer,
    count: jint,
) -> jint {
    surface_points_jni(e, id, input, out, count, false)
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_previewPoints(
    e: JNIEnv,
    _c: JClass,
    id: jlong,
    input: JByteBuffer,
    out: JByteBuffer,
    count: jint,
) -> jint {
    surface_points_jni(e, id, input, out, count, true)
}
fn surface_points_jni(mut e: JNIEnv, id: jlong, input: JByteBuffer, out: JByteBuffer,
    count: jint, preview: bool) -> jint {
    let result = guarded(|| {
        if !(0..=64).contains(&count) {
            return Err("surface batch count".into());
        }
        let ptr = buffer(&mut e, &input, count as usize * 8, false)?;
        buffer(&mut e, &out, count as usize * 40, true)?;
        let bytes = unsafe { std::slice::from_raw_parts(ptr, count as usize * 8) };
        let mut points = vec![];
        for row in bytes.chunks_exact(8) {
            let x = i32::from_le_bytes(row[..4].try_into().unwrap());
            let z = i32::from_le_bytes(row[4..].try_into().unwrap());
            if x.abs_diff(0) > 29999990 || z.abs_diff(0) > 29999990 {
                return Err("surface batch coordinates".into());
            }
            points.push((x, z));
        }
        let w = world(id)?;
        let mut result = Vec::with_capacity(count as usize * 40);
        let columns = if preview { w.preview_points(&points)? } else { w.surface_points(&points)? };
        for column in columns {
            for n in column.values {
                result.extend(n.to_le_bytes());
            }
        }
        put(&mut e, &out, &result)?;
        Ok(count)
    });
    match result {
        Ok(n) => n,
        Err(err) => {
            fail(&mut e, err);
            -1
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_surfaceProxy(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    x: jint,
    z: jint,
) -> jlong {
    let result = guarded(|| {
        let slot = Slot::acquire()?;
        let owner = world(id)?;
        let volume = owner.surface_proxy(x, z)?;
        let handle = IDS.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 {
            return Err("handle space exhausted".into());
        }
        volumes().lock().map_err(|_| "volume lock")?.insert(
            handle,
            Arc::new(Mutex::new(NativeVolume {
                owner,
                volume,
                _slot: slot,
            })),
        );
        Ok(handle)
    });
    match result {
        Ok(id) => id,
        Err(err) => {
            fail(&mut e, err);
            0
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_applyEdits(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    input: JByteBuffer,
    count: jint,
) -> jint {
    let result = guarded(|| {
        if !(0..=262144).contains(&count) {
            return Err("edit count budget".into());
        }
        let ptr = buffer(&mut e, &input, count as usize * 16, false)?;
        let bytes = unsafe { std::slice::from_raw_parts(ptr, count as usize * 16) };
        let v = volume(id)?;
        let mut v = v.lock().map_err(|_| "volume lock")?;
        let mut edits = Vec::with_capacity(count as usize);
        for row in bytes.chunks_exact(16) {
            let n: [i32; 4] = std::array::from_fn(|i| {
                i32::from_le_bytes(row[i * 4..i * 4 + 4].try_into().unwrap())
            });
            if n[3] < 0
                || n[3] as usize >= v.volume.palette.states.len()
                || (0..3).any(|i| {
                    n[i] < v.volume.origin[i]
                        || n[i] >= v.volume.origin[i] + v.volume.size[i] as i32
                })
                || v.volume
                    .write_bounds
                    .is_some_and(|(min, max)| (0..3).any(|i| n[i] < min[i] || n[i] >= max[i]))
            {
                return Err("edit outside volume/state table".into());
            }
            edits.push(n);
        }
        // All inputs have been validated. Imported Java edits are already
        // published in the caller and must not be sent back as new output.
        for n in edits {
            v.volume.set([n[0], n[1], n[2]], n[3] as u32);
        }
        Ok(count)
    });
    match result {
        Ok(n) => n,
        Err(err) => {
            fail(&mut e, err);
            -1
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_readEdits(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    out: JByteBuffer,
) -> jint {
    let result = guarded(|| {
        let v = volume(id)?;
        let mut v = v.lock().map_err(|_| "volume lock")?;
        let bytes = v
            .volume
            .published
            .len()
            .checked_mul(16)
            .ok_or("edit output overflow")?;
        let ptr = buffer(&mut e, &out, bytes, true)?;
        for (entry, (&index, &id)) in v.volume.published.iter().enumerate() {
            let p = [
                v.volume.origin[0] + (index / (v.volume.size[1] * v.volume.size[2])) as i32,
                v.volume.origin[1] + (index % v.volume.size[1]) as i32,
                v.volume.origin[2] + (index / v.volume.size[1] % v.volume.size[2]) as i32,
                id as i32,
            ];
            for (j, n) in p.into_iter().enumerate() {
                unsafe {
                    std::ptr::copy_nonoverlapping(
                        n.to_le_bytes().as_ptr(),
                        ptr.add(entry * 16 + j * 4),
                        4,
                    );
                }
            }
        }
        let count = v.volume.published.len() as i32;
        v.volume.published.clear();
        Ok(count)
    });
    match result {
        Ok(n) => n,
        Err(err) => {
            fail(&mut e, err);
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_cancel(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
) {
    if let Err(err) = guarded(|| {
        world(id)?.cancel();
        Ok(())
    }) {
        fail(&mut e, err);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_tints(
    mut e: JNIEnv,
    _c: JClass,
    id: jlong,
    x: jint,
    y: jint,
    z: jint,
    out: JByteBuffer,
) -> jint {
    let result = guarded(|| {
        buffer(&mut e, &out, 12, true)?;
        if x.abs_diff(0) > 29999990 || z.abs_diff(0) > 29999990 || y.abs_diff(0) > 2000000 {
            return Err("tint coordinates".into());
        }
        let w = world(id)?;
        w.check_active()?;
        let colors = w.colors_at([x, y, z])?;
        let mut bytes = Vec::with_capacity(12);
        for c in colors {
            bytes.extend(c.to_le_bytes());
        }
        put(&mut e, &out, &bytes)?;
        Ok(3)
    });
    match result {
        Ok(n) => n,
        Err(err) => {
            fail(&mut e, err);
            -1
        }
    }
}
