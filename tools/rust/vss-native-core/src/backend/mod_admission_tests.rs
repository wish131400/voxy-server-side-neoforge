use super::*;

fn document()->Value {
    let mut doc=super::exterior_tests::document();
    doc["settings"]["aquifers_enabled"]=json!(false);
    doc["settings"]["ore_veins_enabled"]=json!(false);
    doc["settings"]["noise_router"]["final_density"]=json!({"type":"minecraft:y_clamped_gradient",
        "from_y":0,"to_y":128,"from_value":1.,"to_value":-1.});
    doc["settings"]["surface_rule"]=json!({"type":"minecraft:sequence","sequence":[
        {"type":"minecraft:condition","if_true":{"type":"minecraft:hole"},
         "then_run":{"type":"minecraft:block","result_state":{"Name":"minecraft:lava"}}},
        {"type":"minecraft:block","result_state":{"Name":"minecraft:granite"}}]});
    doc
}

#[test]
fn hole_proof_admits_safe_columns_and_keeps_unsafe_exact_fallback_and_caches() {
    let doc=document();let world=World::new(42,0,doc.clone()).unwrap();
    assert!(world.display_hint_eligible("minecraft:plains"));
    assert!(!world.display_biomes.contains("minecraft:plains"));
    let mut safe=vec![];let mut unsafe_points=vec![];
    for i in 0..65536 {
        let p=(i%256*73-8192,i/256*79-8192);
        if world.surface.depth(&world.terrain.graph,p.0,p.1)<=0 {
            if unsafe_points.len()<4 {unsafe_points.push(p);}
        } else if safe.len()<4 {safe.push(p);}
        if safe.len()==4 && unsafe_points.len()==4 {break;}
    }
    assert_eq!(unsafe_points.len(),4);
    let points:Vec<_>=safe.iter().chain(&unsafe_points).copied().collect();
    let display=world.display_points(&points).unwrap();
    for (i,row) in display.iter().enumerate() {
        assert_eq!(row.values[3]&(1<<26)!=0,i<4,"point={:?}",points[i]);
    }
    let oracle=World::new(42,0,doc).unwrap();
    let exact=world.surface_points(&points).unwrap();
    assert_eq!(exact.iter().map(|r|r.values).collect::<Vec<_>>(),oracle.surface_points(&points).unwrap().iter().map(|r|r.values).collect::<Vec<_>>());
    for i in 4..8 {assert_eq!(display[i].values,exact[i].values);}
    assert_eq!(display.iter().map(|r|r.values).collect::<Vec<_>>(),world.display_points(&points).unwrap().iter().map(|r|r.values).collect::<Vec<_>>());
}

#[test]
fn bwg_geometry_uses_full_surface_and_preserves_eroded_extension() {
    let mut doc=document();doc["settings"]["surface_rule"]=json!({"type":"minecraft:block","result_state":{"Name":"minecraft:granite"}});
    let template=doc["biomes"]["minecraft:plains"].clone();
    let mut baseline=None;
    for name in ["minecraft:eroded_badlands","biomeswevegone:shattered_glacier","biomeswevegone:eroded_borealis"] {
        doc["biomes"][name]=template.clone();doc["biome_source"]=json!({"type":"minecraft:fixed","biome":name});
        let world=World::new(42,0,doc.clone()).unwrap();
        assert!(!world.display_hint_eligible(name));
        assert!(!world.display_biome_at(name,0,0));
        let points=[(-17,-31),(0,0),(12288,-8192),(100000,-100000)];
        let rows=world.surface_points(&points).unwrap().iter().map(|r|r.values).collect::<Vec<_>>();
        if let Some(ref expected)=baseline {assert_eq!(&rows,expected);}else{baseline=Some(rows.clone());}
        let shown=world.display_points(&points).unwrap().iter().map(|r|r.values).collect::<Vec<_>>();
        assert_eq!(rows,shown);
        let t=&world.terrain;
        for &(x,z) in &points {
            let mut a=t.job(x&!15,z&!15,false).unwrap();let mut b=t.job(x&!15,z&!15,false).unwrap();
            assert_eq!(world.generate_surface_point_depth(x,z,&mut a,false).unwrap().values,
                world.generate_surface_point_depth(x,z,&mut b,true).unwrap().values);
        }
    }
}

#[test]
fn adaptive_grid_cannot_transfer_safe_perimeter_hole_proofs_to_its_interior() {
    let world=World::new(42,0,document()).unwrap();
    let probes:Vec<usize>=(0..64).filter(|i| {
        let(x,z)=(i%8,i/8);
        x==0||x==7||z==0||z==7||((x==3||x==4)&&(z==3||z==4))
    }).collect();
    // A real seed-42 noise boundary has one unsafe interior column while all
    // perimeter/centre probes admit the same flat visual material record.
    let points:Vec<_>=(0..64).map(|i|(-2240+i%8,-6009+i/8)).collect();
    assert!(world.surface.depth(&world.terrain.graph,points[9].0,points[9].1)<=0);
    let probe_points:Vec<_>=probes.iter().map(|&i|points[i]).collect();
    let probe_records=world.display_points(&probe_points).unwrap();
    assert!(probe_records.iter().all(|r|r.values==probe_records[0].values));
    assert_ne!(probe_records[0].values[3]&(1<<26),0);
    assert!(world.adaptive_display_grid(&points).unwrap().is_none());
    let records=world.display_points(&points).unwrap();
    assert_eq!(records[9].values[3]&(1<<26),0);
    assert_eq!(records[9].values,world.surface_point(points[9].0,points[9].1).unwrap().values);
    assert!(probes.iter().all(|&i|records[i].values[3]&(1<<26)!=0));
    let safe:Vec<_>=(0..64).map(|i|(i%8,i/8)).collect();
    assert!(world.adaptive_display_grid(&safe).unwrap().is_some());
}
