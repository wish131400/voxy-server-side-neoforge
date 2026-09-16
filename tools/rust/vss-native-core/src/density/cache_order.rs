//! Flat caches evaluate their covered horizontal lattice at Y=0. A vertical
//! cache hidden behind that boundary need not force whole-cell block arrays.
//! Single-position queries outside the covered lattice keep stateful semantics.
use super::{Graph, Marker, Node, Spline};

pub(super) fn exposed(g: &Graph, vertical: &[bool]) -> bool {
    if !g.stateful_columns { return false; }
    // A transform below a Flat boundary can still move an inner cache outside
    // that boundary, or change its evaluation height. Keep the original order
    // for transformed graphs until their coordinate domains can be proven.
    if g.wide_noise_cells || g.nodes.iter().any(|n| matches!(n,Node::CoordinateShift(..))) {
        return true;
    }
    let e=dependencies(g,vertical);
    g.roots.values().any(|id|e[*id])
}

fn dependencies(g:&Graph,vertical:&[bool]) -> Vec<bool> {
    fn spline(s: &Spline, exposed: &[bool]) -> bool {
        match s {
            Spline::Constant(_) => false,
            Spline::Multipoint {coordinate,points} => exposed[*coordinate]
                || points.iter().any(|(_,s,_)|spline(s,exposed)),
        }
    }
    let mut e=Vec::with_capacity(g.nodes.len());
    for n in &g.nodes {
        let v=match n {
            Node::Marker(Marker::Flat,_) => false,
            Node::Marker(Marker::Cache2d,n) if vertical[*n] => true,
            Node::Marker(_,n) | Node::Unary(_,n) | Node::Clamp(n,..)
                | Node::Weird(_,n,_) | Node::FtfUnit(n,_) => e[*n],
            Node::Binary(_,a,b) => e[*a] || e[*b],
            Node::Range(n,_,_,a,b) | Node::Mix(n,a,b) => e[*n] || e[*a] || e[*b],
            Node::Select(n,f,choices) => e[*n] || e[*f] || choices.iter().any(|(_,_,n)|e[*n]),
            Node::LinearSpline(n,points) => e[*n] || points.iter().any(|(_,n)|e[*n]),
            Node::Spline(s) => spline(s,&e),
            Node::Shifted(_,_,_,shifts) | Node::FastNoise(_,_,_,shifts) => shifts.iter().any(|n|e[*n]),
            // A coordinate transform may leave a FlatCache's covered lattice.
            Node::CoordinateShift(..) => true,
            _ => false,
        };
        e.push(v);
    }
    e
}

#[cfg(test)]
mod tests {
    use super::*;
    use super::super::Mode;
    use crate::terrain::Terrain;
    use serde_json::{json,Value};

    fn fixture() -> Value {
        serde_json::from_str(&std::fs::read_to_string(std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures/worldgen/overworld.json")).unwrap()).unwrap()
    }
    fn vertical_cache() -> Value {
        json!({"type":"minecraft:cache_2d","argument":{"type":"minecraft:y_clamped_gradient",
            "from_y":-64,"to_y":320,"from_value":-1.,"to_value":1.}})
    }
    fn sheltered() -> Value { json!({"type":"minecraft:flat_cache","argument":vertical_cache()}) }

    #[test]
    fn only_flat_sheltered_caches_can_avoid_complete_arrays() {
        for (input,required) in [
            (vertical_cache(),true),
            (sheltered(),false),
            (json!({"type":"minecraft:add","argument1":sheltered(),"argument2":vertical_cache()}),true),
            (json!({"type":"minecraft:interpolated","argument":vertical_cache()}),true),
            (json!({"type":"minecraft:interpolated","argument":sheltered()}),false),
        ] {
            let mut d=fixture();d["settings"]["noise_router"]["final_density"]=input;
            let g=Graph::from_document(0,&d).unwrap();
            assert!(g.has_stateful_queries());
            assert_eq!(g.requires_complete_column_order(),required);
        }
        let mut wide=fixture();
        wide["settings"]["noise"]["size_horizontal"]=json!(8);
        wide["settings"]["noise_router"]["final_density"]=sheltered();
        assert!(Graph::from_document(0,&wide).unwrap().requires_complete_column_order());
        let mut transformed=fixture();
        transformed["settings"]["noise_router"]["final_density"]=sheltered();
        let mut g=Graph::from_document(0,&transformed).unwrap();
        let root=g.root("final_density").unwrap();
        let zero=g.nodes.len();g.nodes.push(Node::Constant(0.));
        let shifted=g.nodes.len();g.nodes.push(Node::CoordinateShift(root,[zero;3]));
        let flat=g.nodes.len();g.nodes.push(Node::Marker(Marker::Flat,shifted));
        g.roots.insert("final_density".into(),flat);
        g.analyze_column_caches();
        assert!(g.requires_complete_column_order(),"even transforms under Flat retain the fallback");
    }

    #[test]
    fn covered_flat_queries_preserve_full_array_blocks_and_preliminary() {
        let mut d=fixture();
        let final_density=d["settings"]["noise_router"]["final_density"].clone();
        d["settings"]["noise_router"]["final_density"]=json!({"type":"minecraft:add",
            "argument1":final_density,"argument2":{"type":"minecraft:mul","argument1":0.05,"argument2":sheltered()}});
        for seed in [0,-917] {
            let fast=Terrain::from_document(seed,&d).unwrap();
            let mut full=Terrain::from_document(seed,&d).unwrap();
            full.graph.exposed_column_order=true;
            full.graph.column_plan=super::super::column_plan::ColumnPlan::compile(&full.graph);
            assert!(!fast.graph.requires_complete_column_order());
            for (x0,z0) in [(-32,16),(100000,-100000)] {
                let mut a=fast.job(x0,z0,false).unwrap();
                let mut b=full.job(x0,z0,false).unwrap();
                // Boundary columns and reversed order exercise flat lattice
                // edges, aquifer neighbours and revisit after far queries.
                for (dx,dz) in [(0,0),(15,15),(0,15),(15,0),(7,8),(0,0)] {
                    let (x,z)=(x0+dx,z0+dz);
                    for y in (fast.min_y..fast.min_y+fast.height).rev() {
                        assert_eq!(a.block([x,y,z]),b.block([x,y,z]),"seed={seed} at {x},{y},{z}");
                    }
                    for (xx,zz) in [(x,z),(x+64,z-64),(x,z)] {
                        assert_eq!(a.preliminary(xx,zz),b.preliminary(xx,zz));
                    }
                }
            }
        }
    }

    #[test]
    fn outside_flat_lattice_keeps_last_column_single_query_semantics() {
        let mut d=fixture();d["settings"]["noise_router"]["final_density"]=sheltered();
        let g=Graph::from_document(0,&d).unwrap();
        let root=g.root("final_density").unwrap();
        let mut s=g.scratch(0,0,4,8).unwrap();
        g.initialize_column_caches(&mut s);
        let v=g.compute(root,[100,20,100],Mode::Single,&mut s);
        assert_eq!(g.compute(root,[100,80,100],Mode::Single,&mut s),v);
        g.compute(root,[101,80,100],Mode::Single,&mut s);
        assert_ne!(g.compute(root,[100,80,100],Mode::Single,&mut s),v);
    }

    #[test]
    fn uncovered_cells_restore_arrays_and_reentering_clears_them() {
        let mut d=fixture();
        d["settings"]["noise_router"]["final_density"]=json!({"type":"minecraft:cache_all_in_cell",
            "argument":{"type":"minecraft:add","argument1":sheltered(),
                "argument2":{"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":0.,"to_value":1.}}});
        let g=Graph::from_document(0,&d).unwrap();
        let id=g.root("final_density").unwrap();
        let mut s=g.scratch(0,0,4,8).unwrap();
        g.initialize_column_caches(&mut s);
        g.prepare_cell(id,[0,80,0],-64,384,&mut s);
        assert!(s.prepared_cell.is_none());
        g.prepare_cell(id,[32,80,32],-64,384,&mut s);
        assert!(s.prepared_cell.is_some());
        assert!(g.final_cell_value([32,80,32],&s).is_some());
        g.prepare_cell(id,[0,80,0],-64,384,&mut s);
        assert!(g.final_cell_value([0,80,0],&s).is_none());
        assert!(s.prepared_cell.is_none());
        let mut wide=g.scratch(0,0,32,8).unwrap();
        g.prepare_cell(id,[0,80,0],-64,384,&mut wide);
        assert!(wide.prepared_cell.is_some());
    }

    #[test]
    fn lazy_flat_and_horizontal_reuse_preserve_all_modes_and_revisits() {
        let mut d=fixture();
        d["settings"]["noise_router"]["final_density"]=json!({"type":"minecraft:add",
            "argument1":d["settings"]["noise_router"]["final_density"].clone(),"argument2":sheltered()});
        let g=Graph::from_document(719,&d).unwrap();
        let mut a=g.scratch(0,0,4,8).unwrap();
        let mut b=g.scratch(0,0,4,8).unwrap();
        b.disable_column_plan();
        assert!(!g.column_plan.slots.is_empty());
        assert!(g.column_plan.count>0,"must exercise horizontal reuse in a sheltered graph");
        for mode in [Mode::Raw,Mode::Single,Mode::Cell,Mode::Block,Mode::Single,Mode::Raw] {
            for x in [0,15,16,20,-17,100,0] {
                for z in [0,16,-20,100,0] {
                    for y in (-64..=320).step_by(7) {
                        a.advance_block();b.advance_block();
                        for &id in g.roots.values() {
                            let av=g.compute(id,[x,y,z],mode,&mut a);
                            let bv=g.compute(id,[x,y,z],mode,&mut b);
                            assert_eq!(av.to_bits(),bv.to_bits(),"{mode:?} at {x},{y},{z}");
                        }
                    }
                }
            }
        }
    }
}
