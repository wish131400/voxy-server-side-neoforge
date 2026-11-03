//! Experiment: derive dependencies from the world's real rule tree, never biome name allowlists.
use super::*;
const STEEP:u8=1;
const COLD:u8=2;
const SECONDARY:u8=4;
const ABOVE:u8=8;
#[derive(Default)]
pub(super) struct Demand {
    enabled:bool,
    global:u8,
    biomes:std::collections::HashMap<String,u8>,
}
fn known(c:&Condition,biome:Option<&str>)->Option<bool> {
    match c {
        Condition::Biome(names)=>biome.map(|b|names.iter().any(|n|n==b)),
        Condition::Not(c)=>known(c,biome).map(|v|!v),
        _=>None,
    }
}
fn inputs(c:&Condition)->u8 {
    match c {
        Condition::Steep=>STEEP,
        Condition::Cold=>COLD,
        Condition::Above=>ABOVE,
        Condition::Stone(_,_,range,_) if *range!=0=>SECONDARY,
        Condition::Not(c)=>inputs(c),
        _=>0,
    }
}
fn dependencies(rule:&Rule,biome:Option<&str>)->u8 {
    match rule {
        Rule::Block(_)|Rule::Bands=>0,
        Rule::Sequence(rules)=>rules.iter().map(|r|dependencies(r,biome)).fold(0,|a,b|a|b),
        Rule::Condition(c,r)=>inputs(c)|if known(c,biome)==Some(false){0}else{dependencies(r,biome)},
        Rule::Namespaced{base,sources}=>{
            let mut result=dependencies(base,biome);
            for (namespace,r) in sources {
                if biome.is_none_or(|b|b.split(':').next()==Some(namespace.as_str())){result|=dependencies(r,biome);}
            }
            result
        }
    }
}
impl Demand {
    pub(super) fn compile(rule:&Rule,doc:&Value)->Self {
        let mut result=Self{enabled:doc["vss_experiment_demand"].as_bool()==Some(true),global:dependencies(rule,None),biomes:Default::default()};
        if let Some(biomes)=doc["biomes"].as_object() {
            for name in biomes.keys(){result.biomes.insert(name.clone(),dependencies(rule,Some(name)));}
        }
        result
    }
    fn for_biome(&self,name:&str)->u8 {*self.biomes.get(name).unwrap_or(&self.global)}
}
