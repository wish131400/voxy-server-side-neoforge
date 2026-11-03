//! Compact arithmetic regions with original marker and noise delegates.
use super::{Graph,Id,Mode,Node,Scratch,Unary,Binary,clamped_lerp,unary,java_min,java_max};
#[derive(Clone,Copy)]
enum Op { Leaf,Constant(f64),Gradient(f64,f64,f64,f64),Unary(Unary,Id),Binary(Binary,Id,Id),Clamp(Id,f64,f64),Range(Id,f64,f64,Id,Id) }
#[derive(Default)]
pub(super) struct Plan {ops:Vec<Op>,sizes:Vec<usize>}
impl Plan {
    pub fn compile(g:&Graph)->Self {
        let mut ops=vec![];let mut sizes:Vec<usize>=vec![];
        for node in &g.nodes {
            let (op,size)=match *node {
                Node::Constant(v)=>(Op::Constant(v),1),
                Node::Gradient(a,b,c,d)=>(Op::Gradient(a as f64,b as f64-a as f64,c,d),1),
                Node::Unary(op,n)=>(Op::Unary(op,n),1+sizes[n]),
                Node::Binary(op,a,b)=>(Op::Binary(op,a,b),1+sizes[a]+sizes[b]),
                Node::Clamp(n,a,b)=>(Op::Clamp(n,a,b),1+sizes[n]),
                Node::Range(n,a,b,y,z)=>(Op::Range(n,a,b,y,z),1+sizes[n]+sizes[y]+sizes[z]),
                _=>(Op::Leaf,0),
            };
            ops.push(op);sizes.push(size.min(128));
        }
        Self{ops,sizes}
    }
    pub fn selected(&self,id:Id)->bool {self.sizes.get(id).is_some_and(|&n|n>=4)}
    pub fn eval(&self,id:Id,g:&Graph,p:[i32;3],mode:Mode,s:&mut Scratch)->f64 {
        let ev=|n,s:&mut Scratch|self.eval(n,g,p,mode,s);
        match self.ops[id] {
            Op::Leaf=>g.compute(id,p,mode,s),
            Op::Constant(v)=>v,
            Op::Gradient(a,b,c,d)=>clamped_lerp((p[1] as f64-a)/b,c,d),
            Op::Unary(op,n)=>unary(op,ev(n,s)),
            Op::Clamp(n,a,b)=>ev(n,s).clamp(a,b),
            Op::Range(n,a,b,y,z)=>{let v=ev(n,s);ev(if v>=a&&v<b {y}else{z},s)},
            Op::Binary(op,a,b)=>{
                let a=ev(a,s);
                match op {
                    Binary::Add=>a+ev(b,s),
                    Binary::Mul=>if a==0. {0.}else{a*ev(b,s)},
                    Binary::Min=>if s.use_column_plan && !g.stateful_columns
                        && g.column_plan.bounds.get(b).is_some_and(|(min,_)|a<*min) {a}else{java_min(a,ev(b,s))},
                    Binary::Max=>if s.use_column_plan && !g.stateful_columns
                        && g.column_plan.bounds.get(b).is_some_and(|(_,max)|a>*max) {a}else{java_max(a,ev(b,s))},
                }
            }
        }
    }
}
