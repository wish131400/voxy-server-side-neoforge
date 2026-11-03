//! An exact dry-air certificate for the current Aquifer pressure formula.
use super::*;

#[derive(Default)]
pub(super) struct DryProof {
    first: Option<i32>,
    highest: i32,
    unequal: bool,
}
impl DryProof {
    pub(super) fn include(&mut self, fluid: Fluid, bottom: i32) -> bool {
        if fluid.at(bottom) != Substance::Air { return false; }
        if let Some(first) = self.first {
            self.unequal |= first != fluid.level;
            self.highest = self.highest.max(fluid.level);
        } else {
            self.first = Some(fluid.level);
            self.highest = fluid.level;
        }
        // Dry equal levels imply pressure == 0. With different levels and
        // y >= max(levels)+5, edge <= -5.5 and edge/2.5 < -2. The original
        // pressure() therefore does not sample barrier noise and is negative.
        !self.unequal || bottom as i64 - self.highest as i64 >= 5
    }
}

#[cfg(test)]
mod checks {
    use super::*;
    fn terrain() -> Terrain {
        let doc: Value = serde_json::from_str(include_str!("../../tests/fixtures/worldgen/overworld.json")).unwrap();
        Terrain::from_document(42, &doc).unwrap()
    }
    #[test]
    fn certificate_preserves_the_actual_pressure_branch_at_all_edges() {
        let terrain=terrain();let mut job=terrain.job(0,0,false).unwrap();
        let levels=[-32512,-64,-1,0,31,63,120,256];
        let kinds=[Substance::Air,Substance::Water,Substance::Lava];
        let mut accepted=0;
        for a in levels {for b in levels {for ka in kinds {for kb in kinds {
            for margin in [0,1,4,5,6,8,15] {
                let bottom=a.max(b)+margin;
                let aa=Fluid{level:a,kind:ka};let bb=Fluid{level:b,kind:kb};
                let mut proof=DryProof::default();
                let allowed=proof.include(aa,bottom)&&proof.include(bb,bottom);
                // Air-kind levels can also be dry below the level, but are
                // deliberately rejected unless this stronger proof applies.
                if !allowed {continue;}
                accepted+=1;
                for y in bottom..bottom+16 {
                    let mut barrier=None;
                    let value=job.pressure([0,y,0],aa,bb,&mut barrier);
                    assert!(value<=0.,"a={a} b={b} bottom={bottom} y={y} p={value}");
                    assert!(barrier.is_none(),"certificate must not need barrier noise");
                }
            }
        }}}}
        assert!(accepted>1000);
    }
    #[test]
    fn dry_proof_rejects_nearby_unequal_or_wet_levels() {
        for bottom in 64..68 {
            let mut proof=DryProof::default();
            assert!(proof.include(Fluid{level:63,kind:Substance::Water},bottom));
            assert!(!proof.include(Fluid{level:-32512,kind:Substance::Water},bottom));
        }
        let mut proof=DryProof::default();
        assert!(!proof.include(Fluid{level:69,kind:Substance::Water},68));
        let mut proof=DryProof::default();
        assert!(proof.include(Fluid{level:63,kind:Substance::Water},68));
        assert!(proof.include(Fluid{level:-32512,kind:Substance::Water},68));
    }
    #[test]
    fn candidate_heights_match_exhaustive_block_scans() {
        let original: Value=serde_json::from_str(include_str!("../../tests/fixtures/worldgen/overworld.json")).unwrap();
        for seed in [42,-917] {
            let mut doc=original.clone();doc["vss_experiment_dry"]=serde_json::json!(true);
            let t=Terrain::from_document(seed,&doc).unwrap();
            for (ox,oz) in [(0,0),(-32,16),(131072,-131072)] {
                let mut candidate=t.job(ox,oz,false).unwrap();let mut oracle=t.job(ox,oz,false).unwrap();
                for i in 0..32 {
                    let x=ox+i%8;let z=oz+i/8;
                    let actual=candidate.surface_top(x,z);
                    let expected=(t.min_y..t.min_y+t.height).rev().find_map(|y|{
                        let state=oracle.block([x,y,z]);(state!=Substance::Air).then_some((y,state))
                    });
                    assert_eq!(actual,expected,"seed={seed} x={x} z={z}");
                }
            }
        }
    }
    #[test]
    fn single_block_floating_layers_and_raised_fluids_are_not_skipped() {
        use serde_json::json;
        let original: Value=serde_json::from_str(include_str!("../../tests/fixtures/worldgen/overworld.json")).unwrap();
        for raised_fluid in [false,true] {
            let mut doc=original.clone();doc["vss_experiment_dry"]=json!(true);
            doc["settings"]["noise_router"]["final_density"]=if raised_fluid {json!(-1.)} else {
                json!({"type":"minecraft:range_choice","input":"minecraft:y","min_inclusive":200.,"max_exclusive":201.,"when_in_range":1.,"when_out_of_range":-1.})
            };
            if raised_fluid {doc["settings"]["sea_level"]=json!(203);}
            let t=Terrain::from_document(42,&doc).unwrap();
            for (x,z) in [(-17,15),(0,0),(15,15),(16,-16)] {
                let mut candidate=t.job(x&!15,z&!15,false).unwrap();
                let mut oracle=t.job(x&!15,z&!15,false).unwrap();
                let expected=(t.min_y..t.min_y+t.height).rev().find_map(|y|{
                    let state=oracle.block([x,y,z]);(state!=Substance::Air).then_some((y,state))
                });
                assert_eq!(candidate.surface_top(x,z),expected,"raised_fluid={raised_fluid} x={x} z={z}");
                if !raised_fluid {assert_eq!(expected,Some((200,Substance::Default)));}
            }
        }
    }
}
