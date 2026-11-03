//! Isolated differential tests against recursive rules and uncached noise.
use super::*;

#[test]
fn linear_keeps_dynamic_order_first_namespace_and_empty_fallbacks() {
    let (terrain, surface, mut palette, _, _) = specialization_tests::fixture(false,731);
    let dirt=palette.named("minecraft:dirt").unwrap();
    let water=palette.named("minecraft:water").unwrap();
    let rules=[
        Rule::Sequence(vec![]),
        Rule::Sequence(vec![Rule::Condition(Condition::Y(64,0,false),Box::new(Rule::Block(dirt))),Rule::Bands]),
        Rule::Namespaced{base:Box::new(Rule::Bands),sources:vec![
            ("minecraft".into(),Rule::Condition(Condition::Cold,Box::new(Rule::Block(dirt)))),
            ("minecraft".into(),Rule::Block(water)),
            ("custom".into(),Rule::Condition(Condition::Steep,Box::new(Rule::Block(water))))]},
        Rule::Condition(Condition::Not(Box::new(Condition::Noise(surface.depth,-0.1,0.1,false))),Box::new(Rule::Sequence(vec![
            Rule::Condition(Condition::Above,Box::new(Rule::Sequence(vec![]))),Rule::Block(dirt)]))),
    ];
    for rule in &rules {
        let program=linear::Program::compile(rule);
        for name in ["minecraft:plains","custom:forest","unknown:biome",""] { for i in 0..256 {
            let count=std::cell::Cell::new(0);let query=||{count.set(count.get()+1);i%2==0};
            let memo=RefCell::new(NoiseMemo::new());
            let c=Context{noise:&memo,p:[i*17-3000,i-80,2000-i*31],biome:name,cold:i&1!=0,
                steep:Steepness::Lazy(&query),depth:3,secondary:0.,above:i%9+1,below:1,water:i32::MIN,min_surface:0};
            let expected=surface.rule(rule,&terrain.graph,&c,&mut None);let calls=count.replace(0);
            assert_eq!(program.eval(&surface,&terrain.graph,&c,&mut None),expected);
            assert_eq!(count.get(),calls,"dynamic condition evaluation order changed");
        }}
    }
}

#[test]
fn material_noise_answers_keep_exact_coordinates_dimension_and_eviction() {
    for modern in [false,true] { for seed in [0,-731] {
        let (terrain,surface,_,_,_)=specialization_tests::fixture(modern,seed);
        let mut memo=NoiseMemo::new();
        for pass in 0..3 {for id in [surface.depth,surface.secondary,surface.band_offset] {
            for i in 0..64 { for y in [0.,-0.,i as f64/8.,-33.5] {
                let x=i as f64*0.125-17.;let z=(i*17+pass) as f64;
                let expected=terrain.graph.noise(id,x,y,z).to_bits();
                assert_eq!(memo.sample(&terrain.graph,id,x,y,z).to_bits(),expected);
                assert_eq!(memo.sample(&terrain.graph,id,x,y,z).to_bits(),expected);
            }}
        }}
    }}
}

#[test]
fn linear_ore_rules_leave_later_density_identical() {
    let (mut terrain,surface,mut palette,_,_)=specialization_tests::fixture(false,731);
    let doc:Value=serde_json::from_str(include_str!("../../tests/fixtures/worldgen/overworld.json")).unwrap();
    let rule=Rule::Sequence(vec![Rule::Ore(material::OreRule::parse(&serde_json::json!({
        "density":0.7,"richness":0.4,"filler_gap":-1.,"raw_ore_chance":0.35,
        "ore_block":"minecraft:copper_ore","raw_ore_block":"minecraft:raw_copper_block","filler_block":"minecraft:granite"
    }),&mut terrain.graph,&doc,&mut palette).unwrap()),Rule::Bands]);
    let program=linear::Program::compile(&rule);
    let mut a=terrain.graph.scratch(0,0,terrain.cell_width,terrain.cell_height).unwrap();let mut b=terrain.graph.scratch(0,0,terrain.cell_width,terrain.cell_height).unwrap();
    terrain.graph.initialize_column_caches(&mut a);terrain.graph.initialize_column_caches(&mut b);
    let root=terrain.graph.root("final_density").unwrap();
    for i in 0..256 {
        let c=Context{noise:&RefCell::new(NoiseMemo::new()),p:[i%16,i%128-32,i/16],biome:"minecraft:plains",cold:false,steep:Steepness::Known(false),depth:3,secondary:0.,above:1,below:1,water:i32::MIN,min_surface:0};
        assert_eq!(surface.rule(&rule,&terrain.graph,&c,&mut Some(&mut a)),program.eval(&surface,&terrain.graph,&c,&mut Some(&mut b)));
        assert_eq!(terrain.graph.compute(root,c.p,Mode::Block,&mut a).to_bits(),terrain.graph.compute(root,c.p,Mode::Block,&mut b).to_bits());
    }
}
