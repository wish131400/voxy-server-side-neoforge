//! Isolated ordered material program; unknown/world-dependent leaves delegate.
use super::*;
enum Op { Test(Condition,usize), Emit(Rule), Jump(usize), Namespace(String,usize) }
pub(super) struct Program { ops: Vec<Op> }
impl Program {
    pub fn compile(rule: &Rule) -> Self {
        fn emit(rule:&Rule, ops:&mut Vec<Op>) {
            match rule {
                Rule::Sequence(rules)=>for rule in rules { emit(rule,ops); },
                Rule::Condition(test,rule)=>{
                    let at=ops.len();ops.push(Op::Test(test.clone(),0));emit(rule,ops);
                    let end=ops.len();if let Op::Test(_,target)=&mut ops[at] { *target=end; }
                },
                Rule::Namespaced{base,sources}=>{
                    let mut exits=Vec::new();
                    for (namespace,rule) in sources {
                        let at=ops.len();ops.push(Op::Namespace(namespace.clone(),0));emit(rule,ops);
                        exits.push(ops.len());ops.push(Op::Jump(0));
                        let end=ops.len();if let Op::Namespace(_,target)=&mut ops[at] { *target=end; }
                    }
                    let base_at=ops.len();
                    for at in exits { ops[at]=Op::Jump(base_at); }
                    emit(base,ops);
                },
                _=>ops.push(Op::Emit(rule.clone())),
            }
        }
        let mut ops=Vec::new();emit(rule,&mut ops);Self{ops}
    }
    pub fn eval(&self,surface:&Surface,graph:&Graph,c:&Context<'_>,scratch:&mut Option<&mut Scratch>)->Option<StateId> {
        let mut pc=0;
        while pc<self.ops.len() {
            match &self.ops[pc] {
                Op::Test(test,target)=>if !test.matches(graph,c) { pc=*target;continue; },
                Op::Namespace(namespace,target)=>if c.biome.split(':').next().unwrap_or("")!=namespace { pc=*target;continue; },
                Op::Jump(target)=>{pc=*target;continue;},
                Op::Emit(rule)=>if let Some(state)=surface.rule(rule,graph,c,scratch) { return Some(state); },
            }
            pc+=1;
        }
        None
    }
}
