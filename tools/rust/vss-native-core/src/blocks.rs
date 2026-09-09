//! Bounded native block storage shared by surfaces and feature placement.
//! Registry state definitions and tags are inputs; invalid properties are errors.
use crate::density::{string, Result};
use serde_json::{json, Value};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::Arc;
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
        if self.states.len() >= 262144 {
            return Err("block state budget exceeded".into());
        }
        let id = self.states.len() as u32;
        let definition = self.definitions[&state.name];
        let def = &self.definition_values[definition];
        let info = StateInfo {
            definition: definition as u32,
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
pub struct Volume {
    pub origin: Pos,
    pub size: [usize; 3],
    pub blocks: Vec<StateId>,
    pub palette: Palette,
    pub incomplete: bool,
    changes: Vec<(usize, StateId)>,
    recording: bool,
    pub published: BTreeMap<usize, StateId>,
    /// Read neighbourhoods may be wider than the centre feature's write area.
    pub write_bounds: Option<(Pos, Pos)>,
    pub write_budget: usize,
    /// Collections.shuffle uses entropy independent of the world seed.
    pub decoration_entropy: Option<crate::random::Random>,
}
impl Volume {
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
        })
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
            self.blocks[i]
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
            if self.recording {
                if self.changes.len() >= self.write_budget {
                    self.incomplete = true;
                    return;
                }
                self.changes.push((i, self.blocks[i]));
            }
            self.blocks[i] = id;
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
    pub fn heightmap(&mut self, x: i32, z: i32, kind: u8) -> i32 {
        for y in (self.origin[1]..self.origin[1] + self.size[1] as i32).rev() {
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
        Ok(())
    }
    pub fn finish(&mut self, success: bool) -> Result<()> {
        self.recording = false;
        if !success || self.incomplete {
            for &(i, old) in self.changes.iter().rev() {
                self.blocks[i] = old;
            }
        } else {
            for &(i, _) in &self.changes {
                self.published.insert(i, self.blocks[i]);
            }
        }
        self.changes.clear();
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
