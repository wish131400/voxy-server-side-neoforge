//! Reuse the same aquifer neighbourhood while descending within its Y grid.
use super::*;

#[derive(Default)]
pub(super) struct Nearby {
    pub(super) key: Option<[i32;3]>,
    positions: [[i32;3];12],
    horizontal: [i32;12],
}
impl Job<'_> {
    pub(super) fn nearby_nearest(&mut self,p:[i32;3])->([i32;3],[[i32;3];3]) {
        if !self.terrain.reuse_aquifer_nearby { return self.original_nearest(p); }
        let [x,y,z]=p;
        let grid=[(x-5).div_euclid(16),(y+1).div_euclid(12),(z-5).div_euclid(16)];
        let key=[x,grid[1],z];
        if self.nearby.key!=Some(key) {
            let mut next=Nearby{key:Some(key),..Nearby::default()};
            let mut i=0;
            for dx in 0..=1 {for dy in -1..=1 {for dz in 0..=1 {
                let q=self.aquifer_location([grid[0]+dx,grid[1]+dy,grid[2]+dz]);
                let xx=q[0]-x;let zz=q[2]-z;
                next.positions[i]=q;next.horizontal[i]=xx*xx+zz*zz;i+=1;
            }}}
            self.nearby=next;
        }
        let mut distances=[i32::MAX;3];let mut positions=[[0;3];3];
        for i in 0..12 {
            let q=self.nearby.positions[i];let dy=q[1]-y;
            let distance=self.nearby.horizontal[i]+dy*dy;
            // Original enumeration and >= tie handling are preserved exactly.
            for j in 0..3 {if distances[j]>=distance {
                for k in (j+1..3).rev(){distances[k]=distances[k-1];positions[k]=positions[k-1];}
                distances[j]=distance;positions[j]=q;break;
            }}
        }
        (distances,positions)
    }
    pub(super) fn original_nearest(&mut self,p:[i32;3])->([i32;3],[[i32;3];3]) {
        let [x,y,z]=p;
        let grid=[(x-5).div_euclid(16),(y+1).div_euclid(12),(z-5).div_euclid(16)];
        let mut distances=[i32::MAX;3];let mut positions=[[0;3];3];
        for dx in 0..=1 {for dy in -1..=1 {for dz in 0..=1 {
            let q=self.aquifer_location([grid[0]+dx,grid[1]+dy,grid[2]+dz]);
            let xx=q[0]-x;let yy=q[1]-y;let zz=q[2]-z;let d=xx*xx+yy*yy+zz*zz;
            for i in 0..3 {if distances[i]>=d {
                for j in (i+1..3).rev(){distances[j]=distances[j-1];positions[j]=positions[j-1];}
                distances[i]=d;positions[i]=q;break;
            }}
        }}}
        (distances,positions)
    }

}

#[cfg(test)]
mod checks {
    use super::*;
    #[test]
    fn nearest_candidates_distances_and_ties_match_original_integer_loop() {
        let doc:Value=serde_json::from_str(include_str!("../../tests/fixtures/worldgen/overworld.json")).unwrap();
        for seed in [42,-917] {
            let t=Terrain::from_document(seed,&doc).unwrap();
            let mut fast=t.job(0,0,false).unwrap();let mut slow=t.job(0,0,false).unwrap();
            for (x,z) in [(0,0),(-17,15),(15,16),(16,-16),(131072,-131072),(-32,-32),(0,0)] {
                for y in (-64..320).rev() {
                    let grid=[(x-5i32).div_euclid(16),(y+1i32).div_euclid(12),(z-5i32).div_euclid(16)];
                    let mut distances=[i32::MAX;3];let mut positions=[[0;3];3];
                    for dx in 0..=1 {for dy in -1..=1 {for dz in 0..=1 {
                        let q=slow.aquifer_location([grid[0]+dx,grid[1]+dy,grid[2]+dz]);
                        let xx=q[0]-x;let yy=q[1]-y;let zz=q[2]-z;let d=xx*xx+yy*yy+zz*zz;
                        for i in 0..3 {if distances[i]>=d {
                            for j in (i+1..3).rev(){distances[j]=distances[j-1];positions[j]=positions[j-1];}
                            distances[i]=d;positions[i]=q;break;
                        }}
                    }}}
                    assert_eq!(fast.nearby_nearest([x,y,z]),(distances,positions),"seed={seed} p={x},{y},{z}");
                }
            }
        }
    }
}

#[cfg(test)]
mod lifecycle {
    use super::*;
    #[test]
    fn parked_rebased_and_independent_jobs_keep_original_candidates() {
        let doc:Value=serde_json::from_str(include_str!("../../tests/fixtures/worldgen/overworld.json")).unwrap();
        let t=Terrain::from_document(42,&doc).unwrap();
        let mut job=t.job(0,0,false).unwrap();
        job.nearby_nearest([0,63,0]);
        let mut resumed=Job::resume(&t,job.park());
        assert!(resumed.nearby.key.is_none());
        for (x,z) in [(-17,15),(0,0),(131072,-131072),(0,0)] {
            resumed.reset_display_chunk(x,z).unwrap();assert!(resumed.nearby.key.is_none());
            for y in [-64,-1,0,10,11,12,63,319] {
                let mut original=t.job(x,z,false).unwrap();
                assert_eq!(resumed.nearby_nearest([x,y,z]),original.original_nearest([x,y,z]));
            }
        }
        std::thread::scope(|scope| { for worker in 0..4 {let t=&t;scope.spawn(move || {
            let mut a=t.job(worker*16,0,false).unwrap();let mut b=t.job(worker*16,0,false).unwrap();
            for y in -64..320 {assert_eq!(a.nearby_nearest([worker*16,y,-17]),b.original_nearest([worker*16,y,-17]));}
        });}});
    }
}
