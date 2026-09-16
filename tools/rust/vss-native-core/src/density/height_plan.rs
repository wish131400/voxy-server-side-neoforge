//! Small exact evaluator for initial-density height searches. Horizontal
//! subexpressions are resolved once per query. Unsupported or stateful initial
//! subtrees retain their original search; unrelated roots do not disable this
//! plan. This never approximates a noise sample.
use super::{clamped_lerp, java_max, java_min, unary, Binary, Graph, Id, Marker, Mode, Node, Scratch, Unary};

const LIMIT: usize = 128;
enum Op {
    Y,
    Gradient(f64, f64, f64, f64),
    Unary(Unary, usize),
    Binary(Binary, usize, usize),
    Clamp(usize, f64, f64),
    Range(usize, f64, f64, usize, usize),
}
pub(super) struct HeightPlan {
    pub root: Id,
    output: usize,
    inputs: Vec<(usize, Id)>,
    constants: Vec<(usize, f64)>,
    ops: Vec<(usize, Op)>,
}
impl HeightPlan {
    pub fn compile(g: &Graph, horizontal: &[usize]) -> Option<Self> {
        let root = *g.roots.get("initial_density_without_jaggedness")?;
        let mut plan = Self { root, output: 0, inputs: vec![], constants: vec![], ops: vec![] };
        let mut slots = std::collections::HashMap::new();
        fn visit(g: &Graph, horizontal: &[usize], id: Id, p: &mut HeightPlan,
                 slots: &mut std::collections::HashMap<Id,usize>) -> Option<usize> {
            if let Some(&slot) = slots.get(&id) { return Some(slot); }
            let op = match &g.nodes[id] {
                Node::Constant(v) => {
                    let slot = slots.len();
                    if slot >= LIMIT { return None; }
                    slots.insert(id, slot); p.constants.push((slot,*v)); return Some(slot);
                }
                _ if horizontal[id] != super::column_plan::NONE
                    || matches!(g.nodes[id], Node::Axis(0 | 2)) => {
                    let slot = slots.len();
                    if slot >= LIMIT { return None; }
                    slots.insert(id, slot); p.inputs.push((slot,id)); return Some(slot);
                }
                Node::Axis(1) => Op::Y,
                Node::Gradient(a,b,c,d) => Op::Gradient(*a as f64,*b as f64-*a as f64,*c,*d),
                Node::Unary(op,n) => Op::Unary(*op,visit(g,horizontal,*n,p,slots)?),
                Node::Binary(op,a,b) => Op::Binary(*op,visit(g,horizontal,*a,p,slots)?,visit(g,horizontal,*b,p,slots)?),
                Node::Clamp(n,a,b) => Op::Clamp(visit(g,horizontal,*n,p,slots)?,*a,*b),
                Node::Range(n,a,b,yes,no) => Op::Range(visit(g,horizontal,*n,p,slots)?,*a,*b,
                    visit(g,horizontal,*yes,p,slots)?,visit(g,horizontal,*no,p,slots)?),
                // Single queries unwrap these markers. A vertical Cache2d
                // cannot be unwrapped: it remembers the first visited Y.
                // Horizontal Cache2d/Flat subtrees were accepted above.
                // Unrelated stateful roots do not invalidate this plan.
                Node::Marker(Marker::Once | Marker::Cell | Marker::Interpolated(_), n) => {
                    let slot = visit(g,horizontal,*n,p,slots)?;
                    // Aliases are not inserted: slots.len() is the next free
                    // register, so it must count only allocated registers.
                    return Some(slot);
                }
                _ => return None,
            };
            let slot = slots.len();
            if slot >= LIMIT { return None; }
            slots.insert(id, slot); p.ops.push((slot,op)); Some(slot)
        }
        plan.output = visit(g,horizontal,root,&mut plan,&mut slots)?;
        Some(plan)
    }

    pub fn first_above(&self, g: &Graph, x:i32, z:i32, min:i32, max:i32, step:i32,
                       threshold:f64, s:&mut Scratch) -> Option<i32> {
        let mut values = self.prepare(g,x,z,max,s);
        let mut high = values;
        self.search(min,max,step,threshold,&mut values,&mut high)
    }

    fn prepare(&self, g:&Graph, x:i32, z:i32, y:i32, s:&mut Scratch) -> [f64;LIMIT] {
        let mut values = [0.; LIMIT];
        for &(slot,value) in &self.constants { values[slot] = value; }
        for &(slot,id) in &self.inputs { values[slot] = g.compute(id,[x,y,z],Mode::Single,s); }
        values
    }

    pub fn range(&self, g:&Graph, x:i32, z:i32, min:i32, max:i32, s:&mut Scratch) -> (f64,f64) {
        let mut low = self.prepare(g,x,z,min,s);
        let mut high = low;
        self.eval_range(min,max,&mut low,&mut high)
    }

    fn search(&self,min:i32,max:i32,step:i32,threshold:f64,low:&mut [f64;LIMIT],high:&mut [f64;LIMIT]) -> Option<i32> {
        if min==max { return (self.eval(max,low)>threshold).then_some(max); }
        let (a,b)=self.eval_range(min,max,low,high);
        if b<=threshold { return None; }
        if a>threshold { return Some(max); }
        let middle=min+((max-min)/step/2)*step;
        self.search(middle+step,max,step,threshold,low,high)
            .or_else(||self.search(min,middle,step,threshold,low,high))
    }

    fn eval(&self,y:i32,values:&mut [f64;LIMIT]) -> f64 {
            for (slot,op) in &self.ops {
                values[*slot] = match *op {
                    Op::Y => y as f64,
                    Op::Gradient(a,denom,c,d) => clamped_lerp((y as f64-a)/denom,c,d),
                    Op::Unary(op,n) => unary(op,values[n]),
                    Op::Binary(op,a,b) => match op {
                        Binary::Add => values[a]+values[b],
                        Binary::Mul => if values[a]==0. {0.} else {values[a]*values[b]},
                        Binary::Min => java_min(values[a],values[b]),
                        Binary::Max => java_max(values[a],values[b]),
                    },
                    Op::Clamp(n,a,b) => values[n].clamp(a,b),
                    Op::Range(n,a,b,yes,no) => values[if values[n]>=a && values[n]<b {yes} else {no}],
                };
            }
        values[self.output]
    }

    fn eval_range(&self,min:i32,max:i32,low:&mut [f64;LIMIT],high:&mut [f64;LIMIT]) -> (f64,f64) {
        let unknown=(f64::NEG_INFINITY,f64::INFINITY);
        if self.inputs.iter().any(|(slot,_)| !low[*slot].is_finite()) { return unknown; }
        for &(slot,ref op) in &self.ops {
            let (a,b)=match *op {
                Op::Y => (min as f64,max as f64),
                Op::Gradient(a,denom,c,d) => {
                    let l=clamped_lerp((min as f64-a)/denom,c,d);
                    let h=clamped_lerp((max as f64-a)/denom,c,d);
                    (l.min(h),l.max(h))
                }
                Op::Unary(op,n) => {
                    let (a,b)=(low[n],high[n]);
                    match op {
                        Unary::Half | Unary::Quarter | Unary::Squeeze | Unary::Sqrt | Unary::Floor | Unary::Ceil | Unary::Cube => (unary(op,a),unary(op,b)),
                        Unary::Abs | Unary::Square => (
                            if a<=0. && b>=0. {0.} else {unary(op,a).min(unary(op,b))},
                            unary(op,a).max(unary(op,b))),
                        _ => unknown,
                    }
                }
                Op::Binary(op,a,b) => match op {
                    Binary::Add => (low[a]+low[b],high[a]+high[b]),
                    Binary::Min => (low[a].min(low[b]),high[a].min(high[b])),
                    Binary::Max => (low[a].max(low[b]),high[a].max(high[b])),
                    Binary::Mul if low[a]==0. && high[a]==0. => (0.,0.),
                    Binary::Mul => {
                        let v=[low[a]*low[b],low[a]*high[b],high[a]*low[b],high[a]*high[b]];
                        if v.iter().any(|v|v.is_nan()) {unknown} else {
                            (v.into_iter().fold(f64::INFINITY,f64::min),v.into_iter().fold(f64::NEG_INFINITY,f64::max))
                        }
                    }
                },
                Op::Clamp(n,a,b) => (low[n].clamp(a,b),high[n].clamp(a,b)),
                Op::Range(n,a,b,yes,no) => {
                    if low[n]>=a && high[n]<b {(low[yes],high[yes])}
                    else if high[n]<a || low[n]>=b {(low[no],high[no])}
                    else {(low[yes].min(low[no]),high[yes].max(high[no]))}
                }
            };
            let pad=|v:f64| if v.is_finite() {(1.+v.abs())*1e-12} else {0.};
            // An unbounded child can contain NaN (overflow times zero, for
            // example). Do not narrow it through min/max/clamp afterward.
            if !a.is_finite() || !b.is_finite() { return unknown; }
            let (a,b)=if a==b { (a,b) } else {(a-pad(a),b+pad(b))};
            low[slot]=a; high[slot]=b;
        }
        (low[self.output],high[self.output])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{json,Value};

    fn doc(file:&str) -> Value {
        let path=std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen").join(file);
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
    }

    fn check(d:&Value,seed:i64) {
        let g=Graph::from_document(seed,d).unwrap();
        let Some(plan)=g.column_plan.height.as_ref() else { return; };
        let min=d["settings"]["noise"]["min_y"].as_i64().unwrap() as i32;
        let max=min+d["settings"]["noise"]["height"].as_i64().unwrap() as i32;
        for i in 0..24 {
            let x=-100_003+i*103;
            let z=99_997-i*137;
            let mut scratch=g.scratch(x & !15,z & !15,4,8).unwrap();
            let mut oracle=g.scratch(x & !15,z & !15,4,8).unwrap();
            oracle.disable_column_plan();
            let mut values=plan.prepare(&g,x,z,min,&mut scratch);
            for y in min..=max {
                let a=plan.eval(y,&mut values);
                let b=g.compute(plan.root,[x,y,z],Mode::Single,&mut oracle);
                assert!(a.to_bits()==b.to_bits() || (a.is_nan() && b.is_nan()),
                    "seed={seed} {x},{y},{z}: {a} != {b}");
            }
            for start in (min..=max).step_by(17) {
                let (a,b)=plan.range(&g,x,z,start,max,&mut scratch);
                for y in (start..=max).step_by(3) {
                    let v=g.compute(plan.root,[x,y,z],Mode::Single,&mut oracle);
                    assert!((!a.is_finite() && !b.is_finite()) || (v>=a && v<=b),
                        "invalid range {a}..{b} for {v} at {x},{y},{z}");
                }
            }
            for step in [4,8,16] {
                let expected=(0..=(max-min)/step).rev().map(|k|min+k*step)
                    .find(|&y|g.compute(plan.root,[x,y,z],Mode::Single,&mut oracle)>0.390625);
                assert_eq!(plan.first_above(&g,x,z,min,max,step,0.390625,&mut scratch),expected);
            }
        }
    }

    #[test]
    fn compact_height_plan_preserves_every_sample_and_interval() {
        for file in ["overworld.json","amplified.json","nether.json","end.json"] {
            for seed in [0,-917] {check(&doc(file),seed);}
        }
        assert!(Graph::from_document(0,&doc("overworld.json")).unwrap().column_plan.height.is_some());
    }

    #[test]
    fn compact_height_plan_keeps_thin_layers_and_nonfinite_branches() {
        let y=json!({"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,
            "from_value":-64.,"to_value":320.});
        let overflow=json!({"type":"minecraft:mul","argument1":1e308,"argument2":1e308});
        let nan=json!({"type":"minecraft:mul","argument1":overflow,"argument2":y});
        for input in [
            json!({"type":"minecraft:range_choice","input":y,"min_inclusive":112,"max_exclusive":113,
                "when_in_range":1.,"when_out_of_range":-1.}),
            json!({"type":"minecraft:min","argument1":-1.,"argument2":nan}),
            json!({"type":"minecraft:mul","argument1":0.,"argument2":nan}),
            json!({"type":"minecraft:abs","argument":y}),
            json!({"type":"minecraft:square","argument":y}),
        ] {
            let mut d=doc("overworld.json");
            d["settings"]["noise_router"]["initial_density_without_jaggedness"]=input;
            check(&d,0);
        }
    }

    #[test]
    fn unrelated_vertical_caches_do_not_disable_pure_initial_height_search() {
        let mut d=doc("overworld.json");
        let vertical=json!({"type":"minecraft:cache_2d","argument":{
            "type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,
            "from_value":-1.,"to_value":1.}});
        d["settings"]["noise_router"]["final_density"]=json!({
            "type":"minecraft:flat_cache","argument":vertical});
        for seed in [0,-917] {
            let g=Graph::from_document(seed,&d).unwrap();
            assert!(g.has_stateful_queries());
            assert!(g.column_plan.height.is_some(), "independent initial root must keep its plan");
            check(&d,seed);
        }
        // Single mode outside the Flat lattice still observes vertical cache
        // history. Neither a direct nor Flat-sheltered vertical initial root
        // may be hoisted or unwrapped by the compiled evaluator.
        for initial in [vertical.clone(),json!({"type":"minecraft:flat_cache","argument":vertical})] {
            d["settings"]["noise_router"]["initial_density_without_jaggedness"]=initial;
            assert!(Graph::from_document(0,&d).unwrap().column_plan.height.is_none());
        }
    }
}
