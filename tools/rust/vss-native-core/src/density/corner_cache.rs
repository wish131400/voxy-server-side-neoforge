//! Compact local lattice, with the original coordinate map for neighbours,
//! unusually tall graphs and non-lattice samples. No values are evicted.
use super::Id;
use rustc_hash::FxHashMap;
use std::sync::Arc;

const LAYERS: usize = 64;
const DENSE_BYTES: usize = 256 * 1024;
const NONE: usize = usize::MAX;
struct Page {
    bottom: Option<i32>,
    values: Vec<f64>,
    valid: Vec<u64>,
}
pub(super) struct CornerCache {
    slots: Arc<[usize]>,
    pages: Vec<Option<Page>>,
    origin: [i32; 2],
    width: i32,
    height: i32,
    side: usize,
    count: usize,
    enabled: bool,
    dense_bytes: usize,
    overflow: FxHashMap<(Id, [i32; 3]), f64>,
}
impl CornerCache {
    pub fn new(slots: Arc<[usize]>, count: usize, x: i32, z: i32, width: i32, height: i32) -> Self {
        Self { slots, pages: (0..count).map(|_| None).collect(),
            origin: [x.div_euclid(width)*width, z.div_euclid(width)*width],
            width, height, side: (16/width+2) as usize, count: 0, enabled: false, dense_bytes: 0, overflow: FxHashMap::default() }
    }
    #[inline]
    fn location(&self, key: &(Id, [i32; 3])) -> Option<(usize, usize, i32)> {
        if !self.enabled { return None; }
        let slot = *self.slots.get(key.0)?;
        if slot == NONE || slot >= self.pages.len() { return None; }
        let [x,y,z] = key.1;
        let dx = x as i64-self.origin[0] as i64;
        let dz = z as i64-self.origin[1] as i64;
        if dx < 0 || dz < 0 || dx >= self.side as i64*self.width as i64
            || dz >= self.side as i64*self.width as i64
            || dx%self.width as i64 != 0 || dz%self.width as i64 != 0 || y%self.height != 0 { return None; }
        Some((slot, dx as usize/self.width as usize*self.side+dz as usize/self.width as usize,
            y.div_euclid(self.height)))
    }
    #[inline]
    pub fn get(&self, key: &(Id, [i32; 3])) -> Option<&f64> {
        if let Some((slot,xz,y)) = self.location(key) {
            if let Some(page) = &self.pages[slot] {
                if let Some(bottom) = page.bottom {
                    let dy = y as i64-bottom as i64;
                    if (0..LAYERS as i64).contains(&dy) {
                        let i = dy as usize*self.side*self.side+xz;
                        return (page.valid[i/64] & (1u64 << (i%64)) != 0).then(|| &page.values[i]);
                    }
                }
            }
        }
        self.overflow.get(key)
    }
    pub fn insert(&mut self, key: (Id, [i32; 3]), value: f64) {
        if let Some((slot,xz,y)) = self.location(&key) {
            let size = LAYERS*self.side*self.side;
            let bytes=size*8+size.div_ceil(64)*8;
            if self.pages[slot].is_none() && self.dense_bytes+bytes>DENSE_BYTES {
                if self.overflow.insert(key,value).is_none() {self.count+=1;}
                return;
            }
            if self.pages[slot].is_none() {self.dense_bytes+=bytes;}
            let page = self.pages[slot].get_or_insert_with(|| Page {
                bottom: None, values: vec![0.;size], valid: vec![0;size.div_ceil(64)] });
            // Exterior walks descend. Keep some room above the first corner
            // for adjacent cells, and retain the rest below it.
            let bottom = *page.bottom.get_or_insert(y-56);
            let dy = y as i64-bottom as i64;
            if (0..LAYERS as i64).contains(&dy) {
                let i = dy as usize*self.side*self.side+xz;
                let bit = 1u64 << (i%64);
                if page.valid[i/64] & bit == 0 { self.count += 1; }
                page.valid[i/64] |= bit;
                page.values[i] = value;
                return;
            }
        }
        if self.overflow.insert(key,value).is_none() { self.count += 1; }
    }
    // Never reinterpret a populated cache. Resumed work keeps its previous
    // representation until reset; new sparse jobs retain the original map.
    pub fn enable_dense(&mut self, enabled:bool) { if self.count==0 {self.enabled=enabled;} }
    pub fn len(&self) -> usize { self.count }
    pub fn clear(&mut self) {
        self.count = 0;
        self.overflow.clear();
        for page in self.pages.iter_mut().flatten() { page.bottom = None; page.valid.fill(0); }
    }
    pub fn reset(&mut self, x:i32, z:i32) {
        self.clear();
        self.origin = [x.div_euclid(self.width)*self.width,z.div_euclid(self.width)*self.width];
    }
    pub fn retained_bytes(&self) -> usize {
        self.pages.capacity()*std::mem::size_of::<Option<Page>>() + self.overflow.capacity()*64
            + self.pages.iter().flatten().map(|p| p.values.capacity()*8+p.valid.capacity()*8).sum::<usize>()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn large_graphs_keep_dense_allocations_bounded_and_retain_overflow_values() {
        let mut cache=CornerCache::new((0..1024).collect::<Vec<_>>().into(),1024,0,0,4,8);
        cache.enable_dense(true);
        for node in 0..1024 {cache.insert((node,[0,64,0]),node as f64);}
        assert!(cache.dense_bytes<=DENSE_BYTES);
        assert_eq!(cache.len(),1024);
        for node in 0..1024 {assert_eq!(cache.get(&(node,[0,64,0])),Some(&(node as f64)));}
        cache.clear();
        for node in (0..1024).rev() {cache.insert((node,[4,-64,4]),-(node as f64));}
        assert!(cache.dense_bytes<=DENSE_BYTES);
        for node in 0..1024 {assert_eq!(cache.get(&(node,[4,-64,4])),Some(&(-(node as f64))));}
    }
    #[test]
    fn local_lattice_and_overflow_preserve_every_bit_and_clear_on_teleport() {
        for width in [1,4,16,32] {
            let mut cache = CornerCache::new(Arc::from([NONE,0,NONE]),1,-20,-20,width,8);
            cache.enable_dense(true);
            let mut oracle = FxHashMap::default();
            for x in -24..=16 { for z in -24..=16 { for y in [-4096,-512,-64,0,7,64,128,512,4096] {
                let key = (1,[x,y,z]);
                let value = if x%3==0 {-0.} else {f64::from_bits(0x7ff8000000000017)};
                cache.insert(key,value); oracle.insert(key,value);
            }}}
            assert_eq!(cache.len(),oracle.len());
            for (key,value) in oracle { assert_eq!(cache.get(&key).unwrap().to_bits(),value.to_bits()); }
            let bytes = cache.retained_bytes();
            cache.reset(100000,-100000);
            assert_eq!(cache.len(),0);
            assert!(cache.get(&(1,[0,0,0])).is_none());
            cache.insert((1,[100000,0,-100000]),-0.);
            assert_eq!(cache.get(&(1,[100000,0,-100000])).unwrap().to_bits(),(-0f64).to_bits());
            assert!(cache.retained_bytes() >= bytes);
        }
    }
}
