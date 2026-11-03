use super::*;
use serde_json::json;

fn document() -> Value {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let mut doc: Value=serde_json::from_str(&std::fs::read_to_string(root.join("overworld.json")).unwrap()).unwrap();
    doc["block_definitions"]=serde_json::from_str(&std::fs::read_to_string(root.join("blocks.json")).unwrap()).unwrap();
    doc["biomes"]=serde_json::from_str(&std::fs::read_to_string(root.join("biomes.json")).unwrap()).unwrap();
    doc
}

#[test]
fn compiled_safety_and_depth_match_recursive_analysis() {
    let doc=document();
    let mut terrain=Terrain::from_document(42,&doc).unwrap();
    let mut palette=Palette::from_json(&doc["block_definitions"]).unwrap();
    let mut surface=Surface::new(&mut terrain,&doc,&mut palette).unwrap();
    // Includes namespaced dispatch, reachable air/water, negated Hole, and
    // negative/overflowing stone thresholds. Preserve conservative semantics.
    let air=palette.air;
    let water=palette.named("minecraft:water").unwrap();
    let stone=palette.named("minecraft:stone").unwrap();
    let vanilla=std::mem::replace(&mut surface.rule,Rule::Block(stone));
    let mut rules=vec![vanilla,Rule::Block(air),Rule::Block(water),
        Rule::Condition(Condition::Not(Box::new(Condition::Hole)),Box::new(Rule::Block(air))),
        Rule::Namespaced {base:Box::new(Rule::Block(stone)),sources:vec![
            ("minecraft".into(),Rule::Condition(Condition::Biome(vec!["minecraft:plains".into()]),Box::new(Rule::Block(air)))),
            ("custom".into(),Rule::Block(water))]}];
    for (offset,add,range) in [(0,false,0),(-20,true,5),(2,true,64),(i32::MAX,true,0),(i32::MIN,true,0)] {
        rules.push(Rule::Condition(Condition::Not(Box::new(Condition::Stone(offset,add,range,true))),Box::new(Rule::Block(stone))));
    }
    for rule in rules {
        surface.rule=rule;
        surface.global_summary=surface.analyse_summaries(&palette,None);
        surface.summaries.clear();
        for name in ["minecraft:plains","minecraft:badlands","custom:test"] {
            surface.summaries.insert(name.into(),surface.analyse_summaries(&palette,Some(name)));
        }
        for name in [None,Some("minecraft:plains"),Some("minecraft:badlands"),Some("custom:test"),Some("unregistered:test")] {
            for dry in [false,true] {for hole in [None,Some(false),Some(true)] {
                assert_eq!(surface.summary_safe_for_biome(&palette,name,dry,hole),
                    surface.analyse_summary(&palette,name,dry,hole));
            }}
        }
        let mut predicates=vec![];
        surface.collect_depth_predicates(&surface.rule,&mut predicates);
        surface.depth_predicates=predicates;
        for x in [-131072,-17,-1,0,15,16,131072] {for z in [-131072,-1,0,31,131072] {
            assert_eq!(surface.exterior_padding(&terrain.graph,x,z),surface.exterior_padding_recursive(&terrain.graph,x,z));
        }}
    }
}

#[test]
fn cached_summary_preserves_unknown_biome_fallback() {
    let mut doc=document();
    doc["settings"]["surface_rule"]=json!({"type":"minecraft:condition",
        "if_true":{"type":"minecraft:biome","biome_is":["custom:unknown"]},
        "then_run":{"type":"minecraft:block","result_state":{"Name":"minecraft:air"}}});
    let mut terrain=Terrain::from_document(42,&doc).unwrap();
    let mut palette=Palette::from_json(&doc["block_definitions"]).unwrap();
    let surface=Surface::new(&mut terrain,&doc,&mut palette).unwrap();
    assert!(!surface.sparse_safe_for_biome(&palette,Some("custom:unknown")));
    assert!(surface.sparse_safe_for_biome(&palette,Some("minecraft:plains")));
}
