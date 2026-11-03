//! Bounded, column-local exact material noise answers, not density-mode answers.
use super::*;
#[derive(Clone,Copy)]
struct Entry { id:usize, x:u64, y:u64, z:u64, value:f64 }
pub(super) struct NoiseMemo { entries:[Option<Entry>;16], hand:usize }
impl NoiseMemo {
    pub fn new()->Self { Self { entries:[None;16], hand:0 } }
    pub fn sample(&mut self,g:&Graph,id:usize,x:f64,y:f64,z:f64)->f64 {
        if !crate::evaluation::enabled("memo") { return g.noise(id,x,y,z); }
        let (xx,yy,zz)=(x.to_bits(),y.to_bits(),z.to_bits());
        for entry in self.entries.iter().flatten() {
            if entry.id==id && entry.x==xx && entry.y==yy && entry.z==zz { crate::evaluation::count(if y==0. {10}else{11});return entry.value; }
        }
        crate::evaluation::count(if y==0. {12}else{13});
        let value=g.noise(id,x,y,z);
        self.entries[self.hand]=Some(Entry{id,x:xx,y:yy,z:zz,value});self.hand=(self.hand+1)%16;
        value
    }
}
