//! Compile the sign-only part of final_density once per graph. Leaves keep
//! the existing interval evaluator and interpolation cache; no noise changes.
use super::{Binary, Graph, Id, Marker, Node, Scratch, Unary};
pub(super) enum SurfacePlan {
    Leaf(Id),
    Min(Box<Self>, Box<Self>),
    Scale(f64, Box<Self>),
    Squeeze(Box<Self>),
}
#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum Sign {
    Positive,
    NonPositive,
    Unknown,
}
impl SurfacePlan {
    pub fn compile(g: &Graph) -> Option<Self> {
        if g.requires_complete_column_order() {
            return None;
        }
        fn node(g: &Graph, id: Id, depth: usize) -> SurfacePlan {
            if depth > 64 {
                return SurfacePlan::Leaf(id);
            }
            match &g.nodes[id] {
                Node::Binary(Binary::Min, a, b) => SurfacePlan::Min(
                    Box::new(node(g, *a, depth + 1)),
                    Box::new(node(g, *b, depth + 1)),
                ),
                Node::Binary(Binary::Mul, a, b) if matches!(g.nodes[*a],Node::Constant(v) if v>0. && v.is_finite()) =>
                {
                    let Node::Constant(k) = g.nodes[*a] else {
                        unreachable!()
                    };
                    SurfacePlan::Scale(k, Box::new(node(g, *b, depth + 1)))
                }
                Node::Unary(Unary::Squeeze, n) => {
                    SurfacePlan::Squeeze(Box::new(node(g, *n, depth + 1)))
                }
                Node::Marker(Marker::Once | Marker::Cell, n) => node(g, *n, depth + 1),
                _ => SurfacePlan::Leaf(id),
            }
        }
        Some(node(g, *g.roots.get("final_density")?, 0))
    }
    pub fn classify(
        &self,
        g: &Graph,
        p: [i32; 3],
        top: i32,
        s: &mut Scratch,
        epsilon: f64,
        whole_cell: bool,
    ) -> Sign {
        if !epsilon.is_finite() {
            return Sign::Unknown;
        }
        match self {
            Self::Leaf(id) => {
                let (low, high) = if whole_cell { g.cell_range(*id, p, top, s) }
                    else { g.column_range(*id, p, top, s) };
                if high <= 0. {
                    Sign::NonPositive
                } else if low > epsilon && low.is_finite() && high.is_finite() {
                    Sign::Positive
                } else {
                    Sign::Unknown
                }
            }
            Self::Min(a, b) => {
                let x = a.classify(g, p, top, s, epsilon, whole_cell);
                if x == Sign::NonPositive {
                    return x;
                }
                let y = b.classify(g, p, top, s, epsilon, whole_cell);
                if y == Sign::NonPositive {
                    return y;
                }
                if x == Sign::Positive && y == Sign::Positive {
                    Sign::Positive
                } else {
                    Sign::Unknown
                }
            }
            Self::Scale(k, n) => n.classify(g, p, top, s, (epsilon + 1e-12) / k, whole_cell),
            // squeeze(clamp(x)) preserves sign; its positive slope is >=3/8
            // on [0,1]. Margin protects rounding and subsequent tiny scales.
            Self::Squeeze(n) => {
                if epsilon >= 0.4 {
                    return Sign::Unknown;
                }
                n.classify(g, p, top, s, (epsilon + 1e-12) * 3., whole_cell)
            }
        }
    }
}

#[cfg(test)]
mod probe {
    use super::*;
    fn document() -> serde_json::Value {
        serde_json::from_str(&std::fs::read_to_string(
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen/overworld.json")
        ).unwrap()).unwrap()
    }
    #[test]
    fn lattice_ranges_use_two_corners_and_enclose_full_interpolation() {
        let mut d=document();
        d["settings"]["noise_router"]["final_density"]=serde_json::json!({"type":"minecraft:interpolated",
            "argument":{"type":"minecraft:add",
                "argument1":{"type":"minecraft:noise","noise":"minecraft:temperature","xz_scale":0.1,"y_scale":0.1},
                "argument2":{"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":1.,"to_value":-1.}}});
        let g=Graph::from_document(917,&d).unwrap();
        let id=g.root("final_density").unwrap();
        for x in [-8,0,4] { for z in [-4,0,8] { for base in [-64,0,64,248] {
            let mut fast=g.scratch(x,z,4,8).unwrap();
            let mut full=g.scratch(x,z,4,8).unwrap();
            let (lo,hi)=g.column_range(id,[x,base,z],base+7,&mut fast);
            assert_eq!(fast.corners.len(),2,"must actually omit zero-weight corners");
            for y in base..base+8 {
                let v=g.compute(id,[x,y,z],super::super::Mode::Cell,&mut full);
                assert!(v>=lo && v<=hi,"{x},{y},{z}: {v} outside {lo}..{hi}");
            }
            assert_eq!(full.corners.len(),8);
            // Subsequent fluid-band queries reuse the proven edge in both
            // interpolation orders and leave the full-cell cache untouched.
            for mode in [super::super::Mode::Cell,super::super::Mode::Block] {
                for y in base..base+8 {
                    fast.advance_block(); full.advance_block();
                    let a=g.compute(id,[x,y,z],mode,&mut fast);
                    let b=g.compute(id,[x,y,z],mode,&mut full);
                    assert_eq!(a.to_bits(),b.to_bits(),"edge reuse {x},{y},{z} {mode:?}");
                }
            }
            assert_eq!(fast.corners.len(),2);
            assert!(fast.cells.iter().all(Option::is_none));
            // A following non-lattice request must load a complete cell; no
            // partial cache entry may masquerade as eight valid corners.
            let a=g.compute(id,[x+1,base+3,z+1],super::super::Mode::Cell,&mut fast);
            let b=g.compute(id,[x+1,base+3,z+1],super::super::Mode::Cell,&mut full);
            assert_eq!(a.to_bits(),b.to_bits());
        } } }
    }
    #[test]
    fn duplicate_spline_knots_have_finite_bounds_without_changing_values() {
        for locations in [vec![-1.,0.,0.,1.],vec![0.,0.,0.]] {
            let mut d=document();
            let points:Vec<_>=locations.iter().enumerate().map(|(i,&x)|serde_json::json!({
                "location":x,"value":i as f64-1.5,"derivative":i as f64*0.2-0.3})).collect();
            d["settings"]["noise_router"]["final_density"]=serde_json::json!({"type":"minecraft:interpolated",
                "argument":{"type":"minecraft:spline","spline":{
                    "coordinate":{"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":64,"from_value":-1.,"to_value":1.},
                    "points":points}}});
            let g=Graph::from_document(0,&d).unwrap();
            let root=g.root("final_density").unwrap();
            let Node::Marker(_,child)=g.nodes[root] else {panic!()};
            assert!(g.column_plan.bounds[child].0.is_finite());
            let mut s=g.scratch(0,0,4,8).unwrap();
            let mut oracle=g.scratch(0,0,4,8).unwrap();
            oracle.disable_column_plan();
            for y in (-64..64).step_by(8) {
                let (a,b)=g.column_range(root,[0,y,0],y+7,&mut s);
                for yy in y..y+8 {
                    let v=g.compute(root,[0,yy,0],super::super::Mode::Cell,&mut oracle);
                    assert!(v.is_finite() && v>=a && v<=b,"{yy} {v} outside {a}..{b}");
                }
            }
        }
    }
    #[test]
    fn flat_sheltered_caches_keep_corner_pruning_inside_their_covered_region() {
        let mut d=document();
        d["settings"]["noise_router"]["final_density"]=serde_json::json!({"type":"minecraft:interpolated",
            "argument":{"type":"minecraft:flat_cache","argument":{"type":"minecraft:cache_2d",
                "argument":{"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":64,"from_value":1.,"to_value":-1.}}}});
        let g=Graph::from_document(0,&d).unwrap();
        assert!(g.stateful_columns && !g.exposed_column_order);
        let root=g.root("final_density").unwrap();
        let mut s=g.scratch(0,0,4,8).unwrap();
        let mut oracle=g.scratch(0,0,4,8).unwrap();
        for y in [0,16,-16,0] {
            let (lo,hi)=g.column_range(root,[4,y,4],y+7,&mut s);
            for yy in y..y+8 {
                let v=g.compute(root,[4,yy,4],super::super::Mode::Cell,&mut oracle);
                assert!(v>=lo && v<=hi);
            }
        }
        // A fresh inside query resolves one edge; an outside range remains
        // unknown, preserving the existing order-sensitive fallback boundary.
        let mut s=g.scratch(0,0,4,8).unwrap();
        g.column_range(root,[4,0,4],7,&mut s);
        assert_eq!(s.corners.len(),2);
        assert_eq!(g.column_range(root,[20,0,20],7,&mut s),(f64::NEG_INFINITY,f64::INFINITY));
    }
    #[test]
    fn unbounded_off_axis_corners_cannot_be_discarded() {
        let mut d=document();
        d["settings"]["noise_router"]["final_density"]=serde_json::json!({"type":"minecraft:interpolated",
            "argument":{"type":"minecraft:range_choice","input":{"type":"lithostitched:axis","axis":"x"},
            "min_inclusive":0,"max_exclusive":1,"when_in_range":1.,
            "when_out_of_range":{"type":"minecraft:mul","argument1":1e308,"argument2":1e308}}});
        let g=Graph::from_document(0,&d).unwrap();
        let mut s=g.scratch(0,0,4,8).unwrap();
        let range=g.column_range(g.root("final_density").unwrap(),[0,0,0],7,&mut s);
        assert_eq!(range,(f64::NEG_INFINITY,f64::INFINITY));
        assert_eq!(s.corners.len(),8);
    }
    #[test]
    #[ignore = "requires VSS_DISPLAY_DOCUMENT"]
    fn interpolation_bounds() {
        let d=serde_json::from_str(&std::fs::read_to_string(std::env::var("VSS_DISPLAY_DOCUMENT").unwrap()).unwrap()).unwrap();
        let g=Graph::from_document(0,&d).unwrap();
        eprintln!("stateful={} exposed={}",g.stateful_columns,g.exposed_column_order);
        fn trace(g:&Graph,id:Id,seen:&mut std::collections::HashSet<Id>) {
            if !seen.insert(id) || g.column_plan.bounds[id].0.is_finite() && g.column_plan.bounds[id].1.is_finite() { return; }
            let children=match &g.nodes[id] {
                Node::Unary(_,a)|Node::Marker(_,a)|Node::Clamp(a,_,_)=>vec![*a],
                Node::Binary(_,a,b)=>vec![*a,*b],
                Node::Range(a,_,_,b,c)=>vec![*a,*b,*c],
                Node::Spline(s)=>{
                    fn visit(s:&super::super::Spline,out:&mut Vec<Id>) {
                        if let super::super::Spline::Multipoint{coordinate,points}=s {
                            out.push(*coordinate);
                            for (_,s,_) in points {visit(s,out);}
                            if points.windows(2).any(|p|p[0].0>=p[1].0) {eprintln!("unordered spline knots");}
                        }
                    }
                    let mut out=vec![];visit(s,&mut out);out
                },
                Node::Shifted(_,_,_,shifts)=>shifts.to_vec(),
                _=>vec![],
            };
            if children.is_empty() {eprintln!("unknown leaf={id} kind={}",match &g.nodes[id] {
                Node::Beard=>"beard",Node::Axis(_)=>"axis",Node::Noise(..)=>"noise",Node::FastNoise(..)=>"fastnoise",_=>"other"});}
            for child in children {trace(g,child,seen);}
        }
        trace(&g,*g.roots.get("final_density").unwrap(),&mut std::collections::HashSet::new());
        for (id,n) in g.nodes.iter().enumerate() {
            if let Node::Marker(Marker::Interpolated(_),child)=n {
                eprintln!("interpolator={id} child={child} bound={:?}",g.column_plan.bounds[*child]);
            }
        }
    }
}
