//! Java BiomeSpecialEffects int colors may carry an alpha byte. The tint
//! consumer extracts RGB; invalid JSON types and integers outside Java's
//! signed-int contract must still reject the native snapshot.
use serde_json::{json, Value};
use vss_native_core::biome::{Biome, ClimateColors};

fn definition(grass: Value, foliage: Value, water: Value) -> Value {
    json!({"temperature":0.5,"downfall":0.5,"effects":{
        "grass_color":grass,"foliage_color":foliage,"water_color":water
    }})
}

#[test]
fn scorched_earth_signed_argb_preserves_java_rgb_channels() {
    let biome = Biome::from_json(&definition(
        json!(-13_158_105), json!(-13_158_105), json!(-12_100_265),
    )).unwrap();
    let colors = ClimateColors::new(vec![0; 65536], vec![0; 65536]).unwrap();
    assert_eq!(colors.grass(&biome, -1513., 751.), 0x373927);
    assert_eq!(colors.foliage(&biome), 0x373927);
    assert_eq!(biome.water, 0x475d57);
    assert_eq!(ClimateColors::blend(2, 10, -10, |_, _| biome.water).unwrap(), 0x475d57);
}

#[test]
fn signed_int_boundaries_and_alpha_bytes_match_java_channel_extraction() {
    let colors = ClimateColors::new(vec![0; 65536], vec![0; 65536]).unwrap();
    for value in [i32::MIN, i32::MIN + 1, -1, 0, 0x373927, 0x7f373927, i32::MAX] {
        let biome = Biome::from_json(&definition(json!(value), json!(value), json!(value))).unwrap();
        // Java's color consumers use (color >> 16) & 255, etc., including
        // arithmetic right-shift for negative colors.
        let expected = (((value >> 16) & 255) << 16
            | ((value >> 8) & 255) << 8 | (value & 255)) as u32;
        assert_eq!(colors.grass(&biome, 0., 0.), expected);
        assert_eq!(colors.foliage(&biome), expected);
        assert_eq!(biome.water, expected);
    }
}

#[test]
fn signed_argb_uses_the_same_grass_modifier_as_rgb() {
    let colors = ClimateColors::new(vec![0; 65536], vec![0; 65536]).unwrap();
    for modifier in ["none", "dark_forest", "swamp"] {
        let mut signed = definition(json!(-13_158_105), json!(-13_158_105), json!(-12_100_265));
        signed["effects"]["grass_color_modifier"] = json!(modifier);
        let mut rgb = definition(json!(0x373927), json!(0x373927), json!(0x475d57));
        rgb["effects"]["grass_color_modifier"] = json!(modifier);
        let signed = Biome::from_json(&signed).unwrap();
        let rgb = Biome::from_json(&rgb).unwrap();
        for (x, z) in [(0.,0.), (-1513.,751.), (4096.,-1024.)] {
            assert_eq!(colors.grass(&signed, x, z), colors.grass(&rgb, x, z));
        }
    }
}

#[test]
fn colors_reject_bad_types_and_integers_outside_java_int_range() {
    for key in ["grass_color", "foliage_color", "water_color"] {
        for invalid in [json!(-2_147_483_649i64), json!(2_147_483_648u64),
            json!(u32::MAX), json!(u64::MAX), json!(-1.5), json!(1.0),
            json!("-13158105"), Value::Null, json!(true), json!([]), json!({})] {
            let mut doc = definition(json!(0x373927), json!(0x373927), json!(0x475d57));
            doc["effects"][key] = invalid;
            assert_eq!(Biome::from_json(&doc).unwrap_err(), format!("invalid biome color {key}"), "{doc}");
        }
    }
}

#[test]
fn optional_overrides_and_required_water_keep_their_contract() {
    let mut doc = definition(json!(0), json!(0), json!(0x475d57));
    doc["effects"].as_object_mut().unwrap().remove("grass_color");
    doc["effects"].as_object_mut().unwrap().remove("foliage_color");
    let biome = Biome::from_json(&doc).unwrap();
    assert_eq!(biome.grass, None);
    assert_eq!(biome.foliage, None);
    doc["effects"].as_object_mut().unwrap().remove("water_color");
    assert_eq!(Biome::from_json(&doc).unwrap_err(), "missing water color");
}
