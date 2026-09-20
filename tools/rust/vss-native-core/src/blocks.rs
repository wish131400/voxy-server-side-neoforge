//! Bounded native block storage shared by surfaces and feature placement.
//! Registry state definitions and tags are inputs; invalid properties are errors.
use crate::density::{string, Result};
use serde_json::{json, Value};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::{Arc, OnceLock};
pub type StateId = u32;
pub type Pos = [i32; 3];
#[derive(Clone, Debug, Hash, PartialEq, Eq)]
pub struct State {
    pub name: String,
    pub properties: BTreeMap<String, String>,
}
impl State {
    pub fn json(&self) -> Value {
        if self.properties.is_empty() {
            json!({"Name":self.name})
        } else {
            json!({"Name":self.name,"Properties":self.properties})
        }
    }
}
#[derive(Clone)]
pub struct Definition {
    defaults: BTreeMap<String, String>,
    properties: HashMap<String, HashSet<String>>,
    pub tags: HashSet<String>,
    pub air: bool,
    pub fluid: bool,
    pub motion_blocking: bool,
    pub double_plant: bool,
    pub replaceable: Option<bool>,
    pub support_faces: Option<u8>,
    pub shape_update: String,
}
#[derive(Clone)]
pub struct Palette {
    definitions: Arc<HashMap<String, usize>>,
    definition_values: Arc<Vec<Definition>>,
    pub states: Arc<Vec<State>>,
    state_info: Arc<Vec<StateInfo>>,
    ids: Arc<HashMap<State, StateId>>,
    support: Arc<HashMap<State, u8>>,
    pub air: StateId,
}
// Immutable properties resolved once at registration. Keep this compact and
// shared across volumes, so scanning a column never hashes a block name.
#[derive(Clone, Copy)]
struct StateInfo {
    definition: u32,
    source: i32,
    air: bool,
    fluid: bool,
}
impl Palette {
    pub fn from_json(doc: &Value) -> Result<Self> {
        let mut definitions = HashMap::new();
        let mut definition_values = vec![];
        for (name, value) in doc.as_object().ok_or("missing block definitions")? {
            let defaults = serde_json::from_value(value["defaults"].clone())
                .map_err(|e| format!("block defaults: {e}"))?;
            let properties = serde_json::from_value(value["properties"].clone())
                .map_err(|e| format!("block properties: {e}"))?;
            let tags = serde_json::from_value(value["tags"].clone())
                .map_err(|e| format!("block tags: {e}"))?;
            let flag = |s: &str| {
                value[s]
                    .as_bool()
                    .ok_or_else(|| format!("missing block flag {s}"))
            };
            definitions.insert(name.clone(), definition_values.len());
            definition_values.push(Definition {
                defaults,
                properties,
                tags,
                air: flag("air")?,
                fluid: flag("fluid")?,
                motion_blocking: flag("motion_blocking")?,
                double_plant: flag("double_plant")?,
                replaceable: value["replaceable"].as_bool(),
                support_faces: value["support_faces"]
                    .as_u64()
                    .filter(|n| *n <= 63)
                    .map(|n| n as u8),
                shape_update: value["shape_update"]
                    .as_str()
                    .unwrap_or("compatibility")
                    .into(),
            });
        }
        if definitions.len() > 65536 {
            return Err("block registry budget exceeded".into());
        }
        let mut p = Self {
            definitions: Arc::new(definitions),
            definition_values: Arc::new(definition_values),
            states: Arc::new(vec![]),
            state_info: Arc::new(vec![]),
            ids: Arc::new(HashMap::new()),
            support: Arc::new(HashMap::new()),
            air: 0,
        };
        p.air = p.named("minecraft:air")?;
        Ok(p)
    }
    /// Compact protocol preserves the caller's state order via explicit mixed-radix codes.
    /// No registry identity or property ordering is inferred across the JNI boundary.
    pub fn from_compact(doc: &Value) -> Result<Self> {
        let mut palette = Self::from_json(&doc["block_definitions"])?;
        let mut source = 0i32;
        for group in doc["groups"].as_array().ok_or("missing palette groups")? {
            let name = string(group, "name")?;
            let keys: Vec<String> = serde_json::from_value(group["properties"].clone()).map_err(|e| e.to_string())?;
            let values: Vec<Vec<String>> = serde_json::from_value(group["values"].clone()).map_err(|e| e.to_string())?;
            if keys.len() != values.len() || values.iter().any(Vec::is_empty) {
                return Err("invalid compact properties".into());
            }
            let def_index = *palette.definitions.get(name).ok_or("unknown compact block")?;
            let def = &palette.definition_values[def_index];
            let unique: HashSet<_> = keys.iter().collect();
            if unique.len() != keys.len() || keys.len() != def.properties.len() {
                return Err("incomplete compact properties".into());
            }
            for (key, choices) in keys.iter().zip(&values) {
                let allowed = def.properties.get(key).ok_or("unknown compact property")?;
                if choices.iter().collect::<HashSet<_>>().len() != choices.len()
                    || choices.iter().any(|v| !allowed.contains(v)) {
                    return Err("invalid compact property value".into());
                }
            }
            for code in group["codes"].as_array().ok_or("missing compact states")? {
                if source >= 2_000_000 { return Err("block state budget exceeded".into()); }
                let mut code = code.as_u64().ok_or("invalid compact state code")?;
                let mut properties = BTreeMap::new();
                for (key, choices) in keys.iter().zip(&values) {
                    properties.insert(key.clone(), choices[(code % choices.len() as u64) as usize].clone());
                    code /= choices.len() as u64;
                }
                if code != 0 { return Err("compact state code out of range".into()); }
                let id = palette.insert(State { name: name.into(), properties })?;
                let info = &mut Arc::make_mut(&mut palette.state_info)[id as usize];
                if info.source >= 0 { return Err("duplicate compact state".into()); }
                info.source = source;
                source += 1;
            }
        }
        Ok(palette)
    }
    pub fn compact_mapping(&self) -> Value {
        let sources: Vec<i32> = self.state_info.iter().map(|s| s.source).collect();
        let extra: Vec<Value> = self.states.iter().enumerate()
            .filter(|(i, _)| sources[*i] < 0)
            .map(|(i, state)| json!([i, state.json()])).collect();
        json!({"state_sources": sources, "extra_states": extra})
    }
    pub fn intern(&mut self, v: &Value) -> Result<StateId> {
        let name = string(v, "Name")?;
        let def_index = self
            .definitions
            .get(name)
            .ok_or_else(|| format!("unknown block {name}"))?;
        let def = &self.definition_values[*def_index];
        let mut props = def.defaults.clone();
        if let Some(values) = v.get("Properties") {
            for (key, value) in values.as_object().ok_or("invalid block properties")? {
                let text = value.as_str().ok_or("block property must be a string")?;
                if !def
                    .properties
                    .get(key)
                    .is_some_and(|values| values.contains(text))
                {
                    return Err(format!("invalid block state {name}[{key}={text}]"));
                }
                props.insert(key.clone(), text.into());
            }
        }
        let id = self.insert(State {
            name: name.into(),
            properties: props,
        })?;
        if let Some(faces) = v.get("vss_support_faces") {
            let faces = faces
                .as_u64()
                .filter(|n| *n <= 63)
                .ok_or("invalid support faces")? as u8;
            let state = self.state(id).clone();
            Arc::make_mut(&mut self.support).insert(state, faces);
        }
        Ok(id)
    }
    fn insert(&mut self, state: State) -> Result<StateId> {
        if let Some(&id) = self.ids.get(&state) {
            return Ok(id);
        }
        // A large modpack registers far more block states than vanilla: the
        // reference capture for this pack carries 606,321. The budget exists to
        // bound a hostile document, not to reject real registries, so it is set
        // well above what a heavy pack produces while still being finite.
        if self.states.len() >= 2_000_000 {
            return Err("block state budget exceeded".into());
        }
        let id = self.states.len() as u32;
        let definition = self.definitions[&state.name];
        let def = &self.definition_values[definition];
        let info = StateInfo {
            definition: definition as u32,
            source: -1,
            air: def.air,
            fluid: state
                .properties
                .get("waterlogged")
                .map_or(def.fluid, |v| v == "true"),
        };
        Arc::make_mut(&mut self.ids).insert(state.clone(), id);
        Arc::make_mut(&mut self.states).push(state);
        Arc::make_mut(&mut self.state_info).push(info);
        Ok(id)
    }
    pub fn named(&mut self, name: &str) -> Result<StateId> {
        self.intern(&json!({"Name":name}))
    }
    pub fn with(&mut self, id: StateId, key: &str, value: &str) -> Result<StateId> {
        let mut state = self.states[id as usize].clone();
        let def = self.definition(id);
        if !def.properties.get(key).is_some_and(|p| p.contains(value)) {
            return Err(format!("invalid state property {key}={value}"));
        }
        state.properties.insert(key.into(), value.into());
        self.insert(state)
    }
    pub fn state(&self, id: StateId) -> &State {
        &self.states[id as usize]
    }
    pub fn definition(&self, id: StateId) -> &Definition {
        &self.definition_values[self.state_info[id as usize].definition as usize]
    }
    pub fn supports_face(&self, id: StateId, direction: usize) -> Result<bool> {
        let faces = self
            .support
            .get(self.state(id))
            .copied()
            .or(self.definition(id).support_faces)
            .ok_or("missing block support faces")?;
        Ok(faces & (1 << direction) != 0)
    }
    pub fn is(&self, id: StateId, name: &str) -> bool {
        self.state(id).name == name
    }
    pub fn tag(&self, id: StateId, tag: &str) -> bool {
        self.definition(id).tags.contains(tag)
    }
    pub fn is_air(&self, id: StateId) -> bool {
        self.state_info[id as usize].air
    }
    pub fn fluid(&self, id: StateId) -> bool {
        self.state_info[id as usize].fluid
    }
    pub fn water(&self, id: StateId) -> bool {
        self.is(id, "minecraft:water")
            || self.is(id, "minecraft:bubble_column")
            || self
                .state(id)
                .properties
                .get("waterlogged")
                .is_some_and(|v| v == "true")
            || (self.fluid(id)
                && (self.is(id, "minecraft:seagrass")
                    || self.is(id, "minecraft:tall_seagrass")
                    || self.is(id, "minecraft:kelp")
                    || self.is(id, "minecraft:kelp_plant")))
    }
    pub fn source_water(&self, id: StateId) -> bool {
        self.water(id)
            && (!self.is(id, "minecraft:water")
                || self
                    .state(id)
                    .properties
                    .get("level")
                    .is_some_and(|v| v == "0"))
    }
    pub fn stone(&self, id: StateId) -> bool {
        !self.is_air(id) && !self.fluid(id)
    }
    pub fn tree_replaceable(&self, id: StateId) -> bool {
        self.is_air(id) || self.tag(id, "minecraft:replaceable_by_trees")
    }
}
/// Column-summary backing for a sparse proxy volume.
///
/// `surface_proxy` used to materialise an `80 x height x 80` array (about
/// 9.4 MiB at height 384) even though every cell is a pure function of its
/// column's ten summary words. A proxy keeps the summaries and computes cells
/// on demand, storing only the cells a feature actually changed.
type ChunkColumns = Arc<Vec<[i32; 10]>>;
type ColumnLoader = Box<dyn Fn(usize, usize) -> Result<ChunkColumns> + Send + Sync>;
type InteriorLoader = Box<dyn Fn(usize, usize) -> Result<Arc<Vec<StateId>>> + Send + Sync>;

pub struct ProxyBase {
    /// `width * depth` column summaries, `c[0..10]` as produced by
    /// `column_record`.
    columns: Vec<[i32; 10]>,
    depth: usize,
    lazy_chunks: Vec<OnceLock<Result<ChunkColumns>>>,
    page_axis: usize,
    loader: Option<ColumnLoader>,
    interior_loader: Option<InteriorLoader>,
    interior_columns: Vec<OnceLock<Result<Arc<Vec<StateId>>>>>,
    air: StateId,
    water: StateId,
    lava: StateId,
    ice: StateId,
}
impl ProxyBase {
    /// Value of flat index `i` before any edit. `origin_y` and `height` come
    /// from the owning volume; the column words are absolute Y.
    fn column(&self, column: usize) -> Result<[i32; 10]> {
        let qz = column % self.depth;
        let qx = column / self.depth;
        Ok(if let Some(loader) = &self.loader {
            let axis = self.page_axis;
            let slot = (qx / axis) * (self.depth / axis) + qz / axis;
            let columns = self.lazy_chunks[slot].get_or_init(|| {
                let columns = loader(qx / axis, qz / axis)?;
                if columns.len() != axis * axis { return Err("lazy proxy page column count".into()); }
                Ok(columns)
            });
            columns.as_ref().map_err(Clone::clone)?[(qx % axis) * axis + qz % axis]
        } else {
            self.columns[qx * self.depth + qz]
        })
    }
    fn value(&self, i: usize, origin_y: i32, height: usize) -> Result<StateId> {
        if let Some(loader) = &self.interior_loader {
            let c = i / height;
            let values = self.interior_columns[c].get_or_init(|| {
                let values = loader(c / self.depth, c % self.depth)?;
                if values.len() != height { return Err("interior proxy column height".into()); }
                Ok(values)
            });
            return Ok(values.as_ref().map_err(Clone::clone)?[i % height]);
        }
        let c = self.column(i / height)?;
        let y = origin_y + (i % height) as i32;
        let end = c[0].max(c[1]);
        Ok(if y >= end {
            // The original loop only wrote `min_y..end`; everything above kept
            // the palette's air initial value.
            self.air
        } else if y >= c[0] {
            if c[3] & 2 != 0 && y == c[1] - 1 {
                self.ice
            } else if c[2] == 2 {
                self.lava
            } else {
                self.water
            }
        } else if c[3] & (1 << 29) != 0 {
            self.air
        } else if y == c[0] - 1 {
            c[4] as StateId
        } else if c[0] - 1 - y < 4 {
            c[5] as StateId
        } else {
            c[6] as StateId
        })
    }
}
// A decoration step contains several independent feature transactions. Keep
// its rollback state private so dense and sparse storage cannot diverge.
pub(crate) struct DecorationCheckpoint {
    blocks: Vec<StateId>,
    proxy_edits: HashMap<usize, StateId>,
    published: BTreeMap<usize, StateId>,
    entropy: Option<crate::random::Random>,
    incomplete: bool,
}
pub struct Volume {
    pub origin: Pos,
    pub size: [usize; 3],
    /// Dense storage. Empty while `proxy` is set; call `materialize` before any
    /// path that reads this field directly.
    pub blocks: Vec<StateId>,
    pub palette: Palette,
    pub incomplete: bool,
    /// `None` records "the cell still held its proxy base value", so a rollback
    /// can drop the edit instead of writing a stale block back.
    changes: Vec<(usize, Option<StateId>)>,
    recording: bool,
    pub published: BTreeMap<usize, StateId>,
    /// Read neighbourhoods may be wider than the centre feature's write area.
    pub write_bounds: Option<(Pos, Pos)>,
    pub write_budget: usize,
    /// Collections.shuffle uses entropy independent of the world seed.
    pub decoration_entropy: Option<crate::random::Random>,
    /// Per-column height cache keyed by `(x, z, kind)`. A feature asks for the
    /// same column repeatedly while placing, and each miss walks from the top
    /// of the world down. Entries are dropped per column on write, so a `set`
    /// stays O(1) and the cache never serves a stale height.
    height_cache: std::collections::HashMap<(i32, i32, u8), i32>,
    /// Set for sparse proxy volumes; `blocks` is then empty.
    proxy: Option<ProxyBase>,
    /// Cells a feature wrote in a proxy volume, keyed by flat index.
    proxy_edits: std::collections::HashMap<usize, StateId>,
    // Conservative exclusive upper bound of writes per proxy column. It may
    // remain high after deletion/rollback; that costs scans, never hides blocks.
    proxy_write_tops: std::collections::HashMap<usize, i32>,
}
impl Volume {
    pub(crate) fn decoration_checkpoint(&self) -> Result<DecorationCheckpoint> {
        if self.recording {
            return Err("nested decoration transaction".into());
        }
        Ok(DecorationCheckpoint {
            blocks: self.blocks.clone(),
            proxy_edits: self.proxy_edits.clone(),
            published: self.published.clone(),
            entropy: self.decoration_entropy.clone(),
            incomplete: self.incomplete,
        })
    }
    pub(crate) fn restore_decoration(&mut self, checkpoint: DecorationCheckpoint) {
        self.blocks = checkpoint.blocks;
        self.proxy_edits = checkpoint.proxy_edits;
        self.published = checkpoint.published;
        self.decoration_entropy = checkpoint.entropy;
        self.incomplete = checkpoint.incomplete;
        self.height_cache.clear();
        self.changes.clear();
        self.recording = false;
    }

    pub fn new(origin: Pos, size: [usize; 3], palette: Palette) -> Result<Self> {
        let cells = size
            .into_iter()
            .try_fold(1usize, |a, b| a.checked_mul(b))
            .ok_or("volume overflow")?;
        if cells == 0 || cells > 16_777_216 || size[0] > 256 || size[2] > 256 || size[1] > 4096 {
            return Err("volume budget exceeded".into());
        }
        for i in 0..3 {
            if origin[i].checked_add(size[i] as i32).is_none() {
                return Err("volume coordinate overflow".into());
            }
        }
        Ok(Self {
            origin,
            size,
            blocks: vec![palette.air; cells],
            palette,
            incomplete: false,
            changes: vec![],
            recording: false,
            published: BTreeMap::new(),
            write_bounds: None,
            write_budget: 262144,
            decoration_entropy: None,
            height_cache: std::collections::HashMap::new(),
            proxy_write_tops: std::collections::HashMap::new(),
            proxy: None,
            proxy_edits: std::collections::HashMap::new(),
        })
    }
    /// Builds a sparse proxy volume: no dense array is allocated, cells are
    /// computed from `columns` until a feature edits them.
    ///
    /// `columns` is `width * depth` entries of the ten summary words produced
    /// by `column_record`, in `x * depth + z` order.
    pub fn proxy(
        origin: Pos,
        width: usize,
        depth: usize,
        height: usize,
        palette: Palette,
        columns: Vec<[i32; 10]>,
    ) -> Result<Self> {
        if width.checked_mul(depth) != Some(columns.len()) {
            return Err("proxy column table size".into());
        }
        let size = [width, height, depth];
        if height == 0 || width == 0 || depth == 0
            || width.checked_mul(height).and_then(|v| v.checked_mul(depth)).is_none()
            || width * height * depth > 16_777_216
            || width > 256 || depth > 256 || height > 4096
        {
            return Err("volume budget exceeded".into());
        }
        for i in 0..3 {
            if origin[i].checked_add(size[i] as i32).is_none() {
                return Err("volume coordinate overflow".into());
            }
        }
        let mut volume = Self {
            origin,
            size,
            blocks: Vec::new(),
            palette,
            incomplete: false,
            changes: Vec::new(),
            recording: false,
            published: BTreeMap::new(),
            write_bounds: None,
            write_budget: 262144,
            decoration_entropy: None,
            height_cache: std::collections::HashMap::new(),
            proxy_write_tops: std::collections::HashMap::new(),
            proxy: None,
            proxy_edits: std::collections::HashMap::new(),
        };
        let air = volume.palette.air;
        let water = volume.palette.named("minecraft:water")?;
        let lava = volume.palette.named("minecraft:lava")?;
        let ice = volume.palette.named("minecraft:ice")?;
        volume.proxy = Some(ProxyBase {
            columns,
            depth,
            lazy_chunks: Vec::new(),
            page_axis: 16,
            loader: None,
            interior_loader: None,
            interior_columns: Vec::new(),
            air,
            water,
            lava,
            ice,
        });
        Ok(volume)
    }
    /// Same bounded proxy, but sample each 16x16 chunk only on first read.
    pub fn lazy_proxy(origin: Pos, width: usize, depth: usize, height: usize,
                      palette: Palette, loader: ColumnLoader) -> Result<Self> {
        Self::paged_proxy(origin, width, depth, height, palette, 16, loader)
    }
    /// Sparse edits over lazy, complete 3D columns. No exterior heightfield approximation.
    pub fn interior_proxy(origin: Pos, width: usize, depth: usize, height: usize,
                          palette: Palette, loader: InteriorLoader) -> Result<Self> {
        let mut volume = Self::proxy(origin, width, depth, height, palette, vec![[0; 10]; width * depth])?;
        let proxy = volume.proxy.as_mut().unwrap();
        proxy.columns.clear();
        proxy.interior_columns = (0..width * depth).map(|_| OnceLock::new()).collect();
        proxy.interior_loader = Some(loader);
        Ok(volume)
    }
    /// Lazy pages use x-major column order. Small pages avoid sampling an
    /// entire neighbour chunk when only a canopy edge touches it.
    pub fn paged_proxy(origin: Pos, width: usize, depth: usize, height: usize,
                       palette: Palette, axis: usize, loader: ColumnLoader) -> Result<Self> {
        if width == 0 || depth == 0 || width > 256 || depth > 256
            || !matches!(axis, 4 | 16) || width % axis != 0 || depth % axis != 0 {
            return Err("lazy proxy chunk alignment".into());
        }
        let mut volume = Self::proxy(origin, width, depth, height, palette, vec![[0; 10]; width * depth])?;
        let proxy = volume.proxy.as_mut().unwrap();
        proxy.columns = Vec::new();
        proxy.page_axis = axis;
        proxy.lazy_chunks = (0..width / axis * (depth / axis)).map(|_| OnceLock::new()).collect();
        proxy.loader = Some(loader);
        Ok(volume)
    }
    /// Missing lazy data invalidates the transaction; it must never commit as air.
    fn value_at(&mut self, i: usize) -> StateId {
        if let Some(proxy) = &self.proxy {
            if let Some(&value) = self.proxy_edits.get(&i) { return value; }
            match proxy.value(i, self.origin[1], self.size[1]) {
                Ok(value) => value,
                Err(_) => { self.incomplete = true; self.palette.air }
            }
        } else {
            self.blocks[i]
        }
    }
    /// Explicit dense export loads all columns, and fails atomically on missing data.
    pub fn materialize(&mut self) -> Result<()> {
        let Some(proxy) = &self.proxy else { return Ok(()); };
        let cells = self.size[0] * self.size[1] * self.size[2];
        let mut blocks = vec![self.palette.air; cells];
        for (i, slot) in blocks.iter_mut().enumerate() {
            *slot = if let Some(&value) = self.proxy_edits.get(&i) { value }
                else { proxy.value(i, self.origin[1], self.size[1])? };
        }
        self.blocks = blocks;
        self.proxy = None;
        self.proxy_edits.clear();
        Ok(())
    }
    pub fn is_proxy(&self) -> bool {
        self.proxy.is_some()
    }
    fn index(&self, p: Pos) -> Option<usize> {
        let mut q = [0; 3];
        for i in 0..3 {
            let d = (p[i] as i64) - (self.origin[i] as i64);
            if d < 0 || d >= self.size[i] as i64 {
                return None;
            }
            q[i] = d as usize;
        }
        Some((q[0] * self.size[2] + q[2]) * self.size[1] + q[1])
    }
    pub fn get(&mut self, p: Pos) -> StateId {
        if let Some(i) = self.index(p) {
            self.value_at(i)
        } else {
            // Outside build height is vanilla air; missing horizontal neighbours
            // invalidate the transaction instead of inventing a free tree site.
            if p[0] < self.origin[0]
                || p[0] >= self.origin[0] + self.size[0] as i32
                || p[2] < self.origin[2]
                || p[2] >= self.origin[2] + self.size[2] as i32
            {
                self.incomplete = true
            }
            self.palette.air
        }
    }
    pub fn set(&mut self, p: Pos, id: StateId) {
        if self
            .write_bounds
            .is_some_and(|(min, max)| (0..3).any(|i| p[i] < min[i] || p[i] >= max[i]))
        {
            self.incomplete = true;
            return;
        }
        if id as usize >= self.palette.states.len() {
            self.incomplete = true;
            return;
        }
        if let Some(i) = self.index(p) {
            if self.proxy.is_some() {
                // Sparse: only edited cells are stored. `None` means the cell
                // still held its computed base value, which a rollback restores
                // by dropping the edit rather than writing a block back.
                if self.recording {
                    if self.changes.len() >= self.write_budget {
                        self.incomplete = true;
                        return;
                    }
                    self.changes.push((i, self.proxy_edits.get(&i).copied()));
                }
                self.proxy_edits.insert(i, id);
                self.proxy_write_tops.entry(i / self.size[1])
                    .and_modify(|top| *top = (*top).max(p[1] + 1)).or_insert(p[1] + 1);
                self.invalidate_column_at(i);
                return;
            }
            if self.recording {
                if self.changes.len() >= self.write_budget {
                    self.incomplete = true;
                    return;
                }
                self.changes.push((i, Some(self.blocks[i])));
            }
            self.blocks[i] = id;
            self.invalidate_column_at(i);
        } else if p[0] < self.origin[0]
            || p[0] >= self.origin[0] + self.size[0] as i32
            || p[2] < self.origin[2]
            || p[2] >= self.origin[2] + self.size[2] as i32
        {
            self.incomplete = true
        }
    }
    pub fn height(&mut self, x: i32, z: i32, ocean_floor: bool) -> i32 {
        self.heightmap(x, z, u8::from(ocean_floor))
    }
    /// Drops every cached height for the column holding flat index `i`.
    fn invalidate_column_at(&mut self, i: usize) {
        if self.height_cache.is_empty() {
            return;
        }
        let height = self.size[1];
        let qz = (i / height) % self.size[2];
        let qx = i / height / self.size[2];
        let x = self.origin[0] + qx as i32;
        let z = self.origin[2] + qz as i32;
        for kind in 0..4u8 {
            self.height_cache.remove(&(x, z, kind));
        }
    }
    pub fn heightmap(&mut self, x: i32, z: i32, kind: u8) -> i32 {
        if let Some(&cached) = self.height_cache.get(&(x, z, kind)) {
            return cached;
        }
        let value = self.scan_heightmap(x, z, kind);
        // Never memoise a failed lazy load or an out-of-bounds neighbour as air.
        if !self.incomplete { self.height_cache.insert((x, z, kind), value); }
        value
    }
    /// Oracle for the height cache: always performs the full uncached scan.
    /// Used by tests to prove the cache never changes a result.
    pub fn heightmap_uncached(&mut self, x: i32, z: i32, kind: u8) -> i32 {
        self.scan_heightmap_from(x, z, kind, self.origin[1] + self.size[1] as i32)
    }
    /// The uncached scan: identical semantics to the original loop.
    fn scan_heightmap(&mut self, x: i32, z: i32, kind: u8) -> i32 {
        let mut top = self.origin[1] + self.size[1] as i32;
        if let (Some(proxy), Some(i)) = (&self.proxy, self.index([x, self.origin[1], z])) {
            if proxy.interior_loader.is_some() {
                return self.scan_heightmap_from(x, z, kind, top);
            }
            let column = i / self.size[1];
            match proxy.column(column) {
                Ok(c) => top = top.min(c[0].max(c[1])
                    .max(self.proxy_write_tops.get(&column).copied().unwrap_or(self.origin[1]))),
                Err(_) => { self.incomplete = true; return self.origin[1]; }
            }
        }
        self.scan_heightmap_from(x, z, kind, top)
    }
    fn scan_heightmap_from(&mut self, x: i32, z: i32, kind: u8, top: i32) -> i32 {
        for y in (self.origin[1]..top).rev() {
            let b = self.get([x, y, z]);
            let motion = self.palette.definition(b).motion_blocking;
            if match kind {
                0 => !self.palette.is_air(b),
                1 => motion,
                2 => motion || self.palette.fluid(b),
                _ => !self.palette.tag(b, "minecraft:leaves") && (motion || self.palette.fluid(b)),
            } {
                return y + 1;
            }
        }
        self.origin[1]
    }
    pub fn begin(&mut self) -> Result<()> {
        if self.recording {
            return Err("nested block transaction".into());
        }
        self.changes.clear();
        self.incomplete = false;
        self.recording = true;
        // set() and rollback invalidate edited columns. Unchanged columns can
        // serve the next feature; a transaction boundary does not change blocks.
        Ok(())
    }
    pub fn finish(&mut self, success: bool) -> Result<()> {
        self.recording = false;
        // Taken out so the loop can call `&mut self` helpers below.
        let mut changes = std::mem::take(&mut self.changes);
        if !success || self.incomplete {
            for &(i, old) in changes.iter().rev() {
                match old {
                    Some(value) => {
                        if self.proxy.is_some() {
                            self.proxy_edits.insert(i, value);
                        } else {
                            self.blocks[i] = value;
                        }
                    }
                    // The cell held its proxy base value before the edit.
                    None => {
                        self.proxy_edits.remove(&i);
                    }
                }
                self.invalidate_column_at(i);
            }
        } else {
            for &(i, _) in &changes {
                let value = self.value_at(i);
                self.published.insert(i, value);
            }
        }
        changes.clear();
        self.changes = changes;
        if self.incomplete {
            Err("feature needs missing neighbour data or exceeded write budget".into())
        } else {
            Ok(())
        }
    }
}
pub fn offset(p: Pos, x: i32, y: i32, z: i32) -> Pos {
    [p[0] + x, p[1] + y, p[2] + z]
}
