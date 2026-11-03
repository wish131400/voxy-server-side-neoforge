//! Classify immutable arithmetic subgraphs; stateful markers stay in the caller.
use super::{Graph,Node,Spline};
fn spline_children(s:&Spline,out:&mut Vec<usize>) {match s {
    Spline::Constant(_)=>{},
    Spline::Multipoint{coordinate,points}=> {out.push(*coordinate);for (_,s,_) in points {spline_children(s,out);}}
}}
fn children(n:&Node)->Vec<usize> {match n {
    Node::Unary(_,n)|Node::Clamp(n,..)|Node::Weird(_,n,_)|Node::FtfUnit(n,_)|Node::Marker(_,n)=>vec![*n],
    Node::Binary(_,a,b)=>vec![*a,*b],
    Node::Range(n,_,_,a,b)|Node::Mix(n,a,b)=>vec![*n,*a,*b],
    Node::Select(n,f,choices)=>std::iter::once(*n).chain(std::iter::once(*f)).chain(choices.iter().map(|(_,_,n)|*n)).collect(),
    Node::LinearSpline(n,points)=>std::iter::once(*n).chain(points.iter().map(|(_,n)|*n)).collect(),
    Node::Shifted(_,_,_,shifts)|Node::FastNoise(_,_,_,shifts)=>shifts.to_vec(),
    Node::CoordinateShift(n,shifts)=>std::iter::once(*n).chain(shifts.iter().copied()).collect(),
    Node::Spline(s)=>{let mut v=vec![];spline_children(s,&mut v);v},
    _=>vec![],
}}
pub(super) fn compile(g:&Graph)->Vec<bool> {
    let mut pure=Vec::with_capacity(g.nodes.len());
    let mut cost:Vec<usize>=Vec::with_capacity(g.nodes.len());
    for n in &g.nodes {
        let c=children(n);
        pure.push(!matches!(n,Node::Marker(..)|Node::Beard) && c.iter().all(|&id|pure[id]));
        let own=if matches!(n,Node::Noise(..)|Node::Shift(..)|Node::Shifted(..)|Node::Weird(..)|Node::Blended(..)|Node::End(..)|Node::FastNoise(..)|Node::FtfNoise(..)) {32}else{1};
        cost.push(c.iter().fold(own,|v,&id|v.saturating_add(cost[id]).min(4096)));
    }
    let mut selected=vec![false;g.nodes.len()];
    for (i,n) in g.nodes.iter().enumerate() {
        if !pure[i] {for child in children(n) {if pure[child] && cost[child]>=16 {selected[child]=true;}}}
    }
    for &id in g.roots.values() {if pure[id] && cost[id]>=16 {selected[id]=true;}}
    selected
}
