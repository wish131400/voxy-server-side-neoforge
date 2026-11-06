//! Optional calls to the installed mod's surface predicate. No mod noise/configuration is approximated.
use crate::density::Result;
use jni::{objects::{GlobalRef, JClass, JValue}, JNIEnv, JavaVM};
use serde_json::Value;
use std::sync::OnceLock;

struct Bridge { vm: JavaVM, class: GlobalRef }
static BRIDGE: OnceLock<Bridge> = OnceLock::new();
fn needs_bridge(v: &Value) -> bool {
    match v {
        Value::Object(o) => o.get("type").and_then(Value::as_str) == Some("alexscaves:ac_biome")
            || o.values().any(needs_bridge),
        Value::Array(a) => a.iter().any(needs_bridge),
        _ => false,
    }
}
pub fn prepare(env: &mut JNIEnv, doc: &Value) -> Result<()> {
    if doc["vss_java_surface_dimension"] != "minecraft:overworld" || !needs_bridge(doc) { return Ok(()); }
    let class = env.find_class("dev/xantha/vss/client/prediction/JavaSurfaceConditions")
        .map_err(|e| format!("Java surface adapter: {e}"))?;
    env.call_static_method(&class, "verifyAlexCaves", "()V", &[])
        .map_err(|e| format!("Alex's Caves surface API: {e}"))?;
    let bridge = Bridge { vm: env.get_java_vm().map_err(|e| e.to_string())?,
        class: env.new_global_ref(class).map_err(|e| e.to_string())? };
    let _ = BRIDGE.set(bridge);
    Ok(())
}
pub fn require_bridge() -> Result<()> {
    BRIDGE.get().map(|_| ()).ok_or("ac_biome requires the verified Java surface adapter".into())
}
pub fn alex_caves(seed: i64, p: [i32; 3], offset: i32) -> bool {
    let result = (|| -> Result<bool> {
        let bridge = BRIDGE.get().ok_or("missing Java surface adapter")?;
        let mut env = bridge.vm.attach_current_thread().map_err(|e| e.to_string())?;
        let class: &JClass = bridge.class.as_obj().into();
        env.call_static_method(class, "alexCavesBiome", "(JIIII)Z", &[
            JValue::Long(seed), JValue::Int(p[0]), JValue::Int(p[1]), JValue::Int(p[2]), JValue::Int(offset),
        ]).and_then(|v| v.z()).map_err(|e| e.to_string())
    })();
    // All exported worldgen jobs have a catch_unwind boundary. Keep the Java exception
    // pending and abort this job; returning false here would publish incorrect materials.
    result.unwrap_or_else(|e| panic!("Java surface predicate failed: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn discovers_nested_conditions_without_enabling_unrelated_codecs() {
        assert!(needs_bridge(&serde_json::json!({"rules":[{"if_true":{"type":"alexscaves:ac_biome"}}]})));
        assert!(!needs_bridge(&serde_json::json!({"type":"alexscaves:ac_simplex"})));
    }
}
