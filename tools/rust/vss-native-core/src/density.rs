//! Minecraft 1.21.1 DensityFunctions, CubicSpline and RandomState wiring.
//! Input is the world's codec document, not a fitted table of sampled heights.
//! A graph is immutable; each sampling job owns its bounded scratch caches.
use crate::{
    blended_noise::{BlendedNoise, BlendedParameters},
    noise::{NormalNoise, SimplexNoise},
    random::{Positional, Random},
};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
pub type Id = usize;
pub type Result<T> = std::result::Result<T, String>;
pub fn number(v: &Value, key: &str) -> Result<f64> {
    v.get(key)
        .and_then(Value::as_f64)
        .filter(|x| x.is_finite())
        .ok_or_else(|| format!("missing/invalid number: {key}"))
}
pub fn integer(v: &Value, key: &str) -> Result<i32> {
    v.get(key)
        .and_then(Value::as_i64)
        .and_then(|x| i32::try_from(x).ok())
        .ok_or_else(|| format!("missing/invalid integer: {key}"))
}
pub fn string<'a>(v: &'a Value, key: &str) -> Result<&'a str> {
    v.get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| format!("missing string: {key}"))
}
pub fn minecraft_type(v: &Value) -> Result<&str> {
    let t = string(v, "type")?;
    t.strip_prefix("minecraft:")
        .ok_or_else(|| format!("unsupported codec {t}"))
}
#[derive(Clone, Copy)]
enum Unary {
    Abs,
    Square,
    Cube,
    Half,
    Quarter,
    Squeeze,
    Sin,
    Cos,
    Sqrt,
    Floor,
    Ceil,
    Reciprocal,
}
#[derive(Clone, Copy)]
enum Binary {
    Add,
    Mul,
    Min,
    Max,
}
#[derive(Clone, Copy)]
enum Marker {
    Interpolated(usize),
    Flat,
    Cache2d,
    Once,
    Cell,
}
enum Node {
    Constant(f64),
    Gradient(i32, i32, f64, f64),
    Unary(Unary, Id),
    Binary(Binary, Id, Id),
    Clamp(Id, f64, f64),
    Range(Id, f64, f64, Id, Id),
    Marker(Marker, Id),
    Noise(usize, f64, f64),
    Shift(usize, u8),
    Shifted(usize, f64, f64, [Id; 3]),
    Weird(usize, Id, bool),
    Blended(Box<BlendedNoise>),
    End(Box<SimplexNoise>),
    Spline(Spline),
    Beard,
    Axis(usize),
    Mix(Id, Id, Id),
    Select(Id, Id, Vec<(f64, f64, Id)>),
    CoordinateShift(Id, [Id; 3]),
    FastNoise(Box<fastnoise_lite::FastNoiseLite>, f64, f64, [Id; 3]),
    FtfNoise(std::sync::Arc<crate::freeterraforged_noise::Noise>),
    FtfUnit(Id, i32),
    LinearSpline(Id, Vec<(f64, Id)>),
}
enum Spline {
    Constant(f32),
    Multipoint {
        coordinate: Id,
        points: Vec<(f32, Spline, f32)>,
    },
}
pub struct Graph {
    nodes: Vec<Node>,
    stateful_columns: bool,
    interpolator_count: usize,
    noises: Vec<NormalNoise>,
    noise_ids: HashMap<String, usize>,
    pub roots: HashMap<String, Id>,
    pub random: Positional,
    pub seed: i64,
    pub legacy: bool,
}
impl Graph {
    pub fn requires_complete_column_order(&self) -> bool {
        self.stateful_columns
    }
    pub fn supports_surface_slices(&self) -> bool {
        !self
            .nodes
            .iter()
            .any(|n| matches!(n, Node::CoordinateShift(..)))
    }
    pub fn plan_surface_slices(&self, s: &mut Scratch, columns: &[(i32, i32)]) {
        // Coordinate transforms can read outside the requested noise cells.
        // Keep their complete slice traversal until those dependencies are known.
        s.surface_slices = if !self.supports_surface_slices() {
            None
        } else {
            let mut slices = std::collections::BTreeSet::new();
            for &(_, z) in columns {
                let z = z.div_euclid(s.width) * s.width;
                slices.extend([z, z + s.width]);
            }
            Some(slices.into_iter().collect())
        };
        s.prepared_x = None;
        s.prepared_cell = None;
    }
    fn fill_array(&self, id: Id, points: &[[i32; 3]], mode: Mode, s: &mut Scratch) -> Vec<f64> {
        match &self.nodes[id] {
            Node::Marker(Marker::Cache2d, child) => self.fill_array(*child, points, mode, s),
            Node::Marker(Marker::Once, child) => {
                if let Some(values) = s.arrays.get(&id) {
                    return values.clone();
                }
                let values = self.fill_array(*child, points, mode, s);
                s.arrays.insert(id, values.clone());
                values
            }
            Node::Unary(op, child) => self
                .fill_array(*child, points, mode, s)
                .into_iter()
                .map(|v| unary(*op, v))
                .collect(),
            Node::Clamp(child, min, max) => self
                .fill_array(*child, points, mode, s)
                .into_iter()
                .map(|v| v.clamp(*min, *max))
                .collect(),
            Node::FtfUnit(child, resolution) => self
                .fill_array(*child, points, mode, s)
                .into_iter()
                .map(|v| ftf_unit(v, *resolution))
                .collect(),
            Node::Binary(op, a, b) => {
                // Vanilla converts constant add/multiply to PureTransformer.
                if matches!(op, Binary::Add | Binary::Mul) {
                    let constant = match (&self.nodes[*a], &self.nodes[*b]) {
                        (Node::Constant(c), _) => Some((*c, *b)),
                        (_, Node::Constant(c)) => Some((*c, *a)),
                        _ => None,
                    };
                    if let Some((c, child)) = constant {
                        return self
                            .fill_array(child, points, mode, s)
                            .into_iter()
                            .map(|v| {
                                if matches!(op, Binary::Add) {
                                    v + c
                                } else {
                                    v * c
                                }
                            })
                            .collect();
                    }
                }
                let mut values = self.fill_array(*a, points, mode, s);
                if matches!(op, Binary::Add) {
                    for (v, b) in values.iter_mut().zip(self.fill_array(*b, points, mode, s)) {
                        *v += b;
                    }
                } else {
                    for (i, v) in values.iter_mut().enumerate() {
                        s.array_index = i;
                        if mode == Mode::Slice && !(matches!(op, Binary::Mul) && *v == 0.) {
                            s.advance_block();
                        }
                        *v = match op {
                            Binary::Mul if *v == 0. => 0.,
                            Binary::Mul => *v * self.compute(*b, points[i], mode, s),
                            Binary::Min => java_min(*v, self.compute(*b, points[i], mode, s)),
                            Binary::Max => java_max(*v, self.compute(*b, points[i], mode, s)),
                            _ => unreachable!(),
                        };
                    }
                }
                values
            }
            Node::Range(input, min, max, yes, no) => {
                let mut values = self.fill_array(*input, points, mode, s);
                for (i, v) in values.iter_mut().enumerate() {
                    s.array_index = i;
                    if mode == Mode::Slice {
                        s.advance_block();
                    }
                    *v = self.compute(
                        if *v >= *min && *v < *max { *yes } else { *no },
                        points[i],
                        mode,
                        s,
                    );
                }
                values
            }
            _ => points
                .iter()
                .enumerate()
                .map(|(i, p)| {
                    s.array_index = i;
                    if mode == Mode::Slice {
                        s.advance_block();
                    }
                    self.compute(id, *p, mode, s)
                })
                .collect(),
        }
    }

    pub fn prepare_cell(
        &self,
        final_density: Id,
        p: [i32; 3],
        min_y: i32,
        height: i32,
        s: &mut Scratch,
    ) {
        if !self.stateful_columns {
            return;
        }
        let base = [
            p[0].div_euclid(s.width) * s.width,
            p[1].div_euclid(s.height) * s.height,
            p[2].div_euclid(s.width) * s.width,
        ];
        if s.prepared_cell == Some(base) {
            return;
        }
        if s.prepared_x != Some(base[0]) {
            s.corners.clear();
            s.cells.fill(None);
            for x in [base[0], base[0] + s.width] {
                let slices = s.surface_slices.clone().unwrap_or_else(|| {
                    (s.origin_z..=s.origin_z + s.span)
                        .step_by(s.width as usize)
                        .collect()
                });
                for z in slices {
                    s.arrays.clear();
                    let points: Vec<_> = (min_y..=min_y + height)
                        .step_by(s.height as usize)
                        .map(|y| [x, y, z])
                        .collect();
                    for node in &self.nodes {
                        if let Node::Marker(Marker::Interpolated(_), child) = node {
                            let values = self.fill_array(*child, &points, Mode::Slice, s);
                            for (p, value) in points.iter().zip(values) {
                                s.corners.insert((*child, *p), value);
                            }
                        }
                    }
                }
            }
            s.prepared_x = Some(base[0]);
        }
        s.arrays.clear();
        s.prepared_cell = Some(base);
        let mut points = Vec::with_capacity((s.width * s.width * s.height) as usize);
        for y in (base[1]..base[1] + s.height).rev() {
            for x in base[0]..base[0] + s.width {
                for z in base[2]..base[2] + s.width {
                    points.push([x, y, z]);
                }
            }
        }
        for (id, node) in self.nodes.iter().enumerate() {
            if let Node::Marker(Marker::Cell, child) = node {
                let values = self.fill_array(*child, &points, Mode::Cell, s);
                s.cell_values.insert(id, values);
            }
        }
        s.final_values = self.fill_array(final_density, &points, Mode::Cell, s);
        s.arrays.clear();
    }

    pub fn final_cell_value(&self, p: [i32; 3], s: &Scratch) -> Option<f64> {
        s.final_values.get(s.cell_index(p)?).copied()
    }
    pub fn from_document(seed: i64, doc: &Value) -> Result<Self> {
        Self::build_document(seed, doc, true)
    }
    fn build_document(seed: i64, doc: &Value, intern_values: bool) -> Result<Self> {
        let legacy = doc["settings"]["legacy_random_source"]
            .as_bool()
            .ok_or("missing legacy_random_source")?;
        let graph = Self {
            nodes: vec![],
            stateful_columns: false,
            interpolator_count: 0,
            noises: vec![],
            noise_ids: HashMap::new(),
            roots: HashMap::new(),
            random: Random::new(seed, u8::from(!legacy)).positional(),
            seed,
            legacy,
        };
        let mut builder = Builder {
            graph,
            doc,
            refs: HashMap::new(),
            active: HashSet::new(),
            intern: HashMap::new(),
            depth: 0,
            intern_values,
        };
        let router = doc["settings"]["noise_router"]
            .as_object()
            .ok_or("missing noise_router")?;
        let order = [
            "barrier",
            "fluid_level_floodedness",
            "fluid_level_spread",
            "lava",
            "temperature",
            "vegetation",
            "continents",
            "erosion",
            "depth",
            "ridges",
            "initial_density_without_jaggedness",
            "final_density",
            "vein_toggle",
            "vein_ridged",
            "vein_gap",
        ];
        for name in order
            .iter()
            .copied()
            .filter(|name| router.contains_key(*name))
            .chain(
                router
                    .keys()
                    .map(String::as_str)
                    .filter(|name| !order.contains(name)),
            )
        {
            let value = &router[name];
            let id = builder.parse(value)?;
            builder.graph.roots.insert(name.to_string(), id);
        }
        builder.graph.analyze_column_caches();
        if intern_values && builder.graph.stateful_columns {
            return Self::build_document(seed, doc, false);
        }
        Ok(builder.graph)
    }

    fn analyze_column_caches(&mut self) {
        fn spline_y(spline: &Spline, dependencies: &[bool]) -> bool {
            match spline {
                Spline::Constant(_) => false,
                Spline::Multipoint { coordinate, points } => {
                    dependencies[*coordinate]
                        || points
                            .iter()
                            .any(|(_, value, _)| spline_y(value, dependencies))
                }
            }
        }
        // Only graphs with vertical cache_2d dependencies need stateful evaluation.
        let mut dependencies = Vec::with_capacity(self.nodes.len());
        for node in &self.nodes {
            let uses_y = match node {
                Node::Constant(_) | Node::End(_) | Node::FtfNoise(_) => false,
                Node::FtfUnit(input, _) => dependencies[*input],
                Node::LinearSpline(input, points) => {
                    dependencies[*input] || points.iter().any(|(_, n)| dependencies[*n])
                }
                Node::Axis(axis) => *axis == 1,
                Node::Mix(input, a, b) => {
                    dependencies[*input] || dependencies[*a] || dependencies[*b]
                }
                Node::Select(input, fallback, choices) => {
                    dependencies[*input]
                        || dependencies[*fallback]
                        || choices.iter().any(|(_, _, n)| dependencies[*n])
                }
                Node::CoordinateShift(input, shifts) => {
                    dependencies[*input] || shifts.iter().any(|n| dependencies[*n])
                }
                Node::FastNoise(_, _, y, shifts) => {
                    *y != 0. || shifts.iter().any(|n| dependencies[*n])
                }
                Node::Gradient(_, _, a, b) => a != b,
                Node::Beard | Node::Blended(_) | Node::Weird(_, _, _) => true,
                Node::Noise(_, _, scale) => *scale != 0.,
                Node::Shift(_, kind) => *kind == 0,
                Node::Shifted(_, _, scale, shifts) => {
                    *scale != 0. || shifts.iter().any(|id| dependencies[*id])
                }
                Node::Unary(_, id) | Node::Clamp(id, _, _) => dependencies[*id],
                Node::Binary(_, a, b) => dependencies[*a] || dependencies[*b],
                Node::Range(input, _, _, yes, no) => {
                    dependencies[*input] || dependencies[*yes] || dependencies[*no]
                }
                Node::Spline(spline) => spline_y(spline, &dependencies),
                Node::Marker(Marker::Flat, _) => false,
                Node::Marker(Marker::Cache2d, id) if dependencies[*id] => {
                    self.stateful_columns = true;
                    true
                }
                Node::Marker(_, id) => dependencies[*id],
            };
            dependencies.push(uses_y);
        }
    }

    pub fn initialize_column_caches(&self, scratch: &mut Scratch) {
        if !self.stateful_columns {
            return;
        }
        // NoiseChunk constructs each FlatCache after visiting its children.
        for (id, node) in self.nodes.iter().enumerate() {
            if let Node::Marker(Marker::Flat, child) = node {
                for x in (scratch.origin_x..=scratch.origin_x + scratch.span).step_by(4) {
                    for z in (scratch.origin_z..=scratch.origin_z + scratch.span).step_by(4) {
                        let value = self.compute(*child, [x, 0, z], Mode::Single, scratch);
                        scratch.flat.insert((*child, x, z), value);
                    }
                }
                scratch.memo[id] = None;
            }
        }
    }
    pub fn add_root(&mut self, name: &str, value: &Value, doc: &Value) -> Result<Id> {
        let placeholder = Self {
            nodes: vec![],
            stateful_columns: false,
            interpolator_count: 0,
            noises: vec![],
            noise_ids: HashMap::new(),
            roots: HashMap::new(),
            random: Random::new(0, 0).positional(),
            seed: 0,
            legacy: false,
        };
        let graph = std::mem::replace(self, placeholder);
        let mut b = Builder {
            intern_values: !graph.stateful_columns,
            graph,
            doc,
            refs: HashMap::new(),
            active: HashSet::new(),
            intern: HashMap::new(),
            depth: 0,
        };
        let result = b.parse(value);
        if let Ok(id) = result {
            b.graph.roots.insert(name.into(), id);
        }
        b.graph.analyze_column_caches();
        *self = b.graph;
        result
    }
    pub fn noise(&self, id: usize, x: f64, y: f64, z: f64) -> f64 {
        self.noises[id].sample(x, y, z)
    }
    pub fn registered_noise(&mut self, name: &str, doc: &Value) -> Result<usize> {
        if let Some(&id) = self.noise_ids.get(name) {
            return Ok(id);
        }
        let p = &doc["noises"][name];
        let first = integer(p, "firstOctave")?;
        let amplitudes: Vec<f64> = p["amplitudes"]
            .as_array()
            .ok_or("invalid noise amplitudes")?
            .iter()
            .map(|v| {
                v.as_f64()
                    .filter(|v| v.is_finite())
                    .ok_or("invalid amplitude".into())
            })
            .collect::<Result<_>>()?;
        if amplitudes.len() > 64 || !(-64..=64).contains(&first) {
            return Err("noise octave budget exceeded".into());
        }
        // Runtime mods can seed a noise under another resource name while
        // retaining its own octave parameters (Tectonic NoisesMixin).
        // Only apply aliases explicitly supplied by the server snapshot.
        let seed_name = match doc.get("noise_seed_aliases").and_then(|v| v.get(name)) {
            Some(value) => value.as_str().ok_or("invalid noise seed alias")?,
            None => name,
        };
        let mut r = self.random.from_hash(seed_name);
        let noise =
            if self.legacy && (name == "minecraft:temperature" || name == "minecraft:vegetation") {
                NormalNoise::new(
                    &mut Random::new(
                        self.seed
                            .wrapping_add(i64::from(name == "minecraft:vegetation")),
                        0,
                    ),
                    -7,
                    &[1., 1.],
                    true,
                )
            } else if self.legacy && name == "minecraft:offset" {
                NormalNoise::new(&mut r, 0, &[0.], false)
            } else {
                NormalNoise::new(&mut r, first, &amplitudes, false)
            }
            .ok_or_else(|| format!("invalid noise {name}"))?;
        let id = self.noises.len();
        self.noises.push(noise);
        self.noise_ids.insert(name.into(), id);
        Ok(id)
    }
    pub fn root(&self, name: &str) -> Result<Id> {
        self.roots
            .get(name)
            .copied()
            .ok_or_else(|| format!("missing router node {name}"))
    }
    pub fn scratch(
        &self,
        origin_x: i32,
        origin_z: i32,
        width: i32,
        height: i32,
    ) -> Result<Scratch> {
        if !(1..=64).contains(&width) || !(1..=64).contains(&height) {
            return Err("invalid noise cell size".into());
        }
        Ok(Scratch {
            origin_x,
            origin_z,
            span: 16,
            width,
            height,
            memo: vec![None; self.nodes.len()],
            corners: HashMap::new(),
            cells: vec![None; self.interpolator_count],
            flat: HashMap::new(),
            cached_2d: HashMap::new(),
            last_2d: vec![
                None;
                if self.stateful_columns {
                    self.nodes.len()
                } else {
                    0
                }
            ],
            arrays: HashMap::new(),
            once: vec![
                (0, 0.);
                if self.stateful_columns {
                    self.nodes.len()
                } else {
                    0
                }
            ],
            counter: 0,
            array_index: 0,
            prepared_x: None,
            surface_slices: None,
            prepared_cell: None,
            cell_values: HashMap::new(),
            final_values: vec![],
            beard: 0.,
        })
    }
    pub fn compute(&self, id: Id, p: [i32; 3], mode: Mode, s: &mut Scratch) -> f64 {
        if let Some((q, m, v)) = s.memo[id].filter(|_| !self.stateful_columns || mode == Mode::Raw)
        {
            if q == p && m == mode {
                return v;
            }
        }
        let [x, y, z] = p;
        let ev = |n, s: &mut Scratch| self.compute(n, p, mode, s);
        let v = match &self.nodes[id] {
            Node::FtfNoise(noise) => noise.compute(x as f32, z as f32, self.seed as i32) as f64,
            Node::FtfUnit(input, resolution) => ftf_unit(ev(*input, s), *resolution),
            Node::LinearSpline(input, points) => {
                let value = ev(*input, s);
                if value <= points[0].0 {
                    ev(points[0].1, s)
                } else if value >= points[points.len() - 1].0 {
                    ev(points[points.len() - 1].1, s)
                } else {
                    let i = points
                        .partition_point(|(x, _)| *x <= value)
                        .saturating_sub(1)
                        .min(points.len() - 2);
                    let (x0, a) = points[i];
                    let (x1, b) = points[i + 1];
                    let from = ev(a, s);
                    let to = ev(b, s);
                    from + ((value - x0) / (x1 - x0)).clamp(0., 1.) * (to - from)
                }
            }
            Node::Axis(axis) => p[*axis] as f64,
            Node::Mix(input, a, b) => {
                let t = ev(*input, s);
                if t <= 0. {
                    ev(*a, s)
                } else if t >= 1. {
                    ev(*b, s)
                } else {
                    ev(*a, s) * (1. - t) + ev(*b, s) * t
                }
            }
            Node::Select(input, fallback, choices) => {
                let t = ev(*input, s);
                ev(
                    choices
                        .iter()
                        .find(|(a, b, _)| t >= *a && t <= *b)
                        .map_or(*fallback, |(_, _, n)| *n),
                    s,
                )
            }
            Node::CoordinateShift(input, shifts) => {
                let q = std::array::from_fn(|i| (p[i] as f64 + ev(shifts[i], s)) as i32);
                self.compute(
                    *input,
                    q,
                    if mode == Mode::Raw {
                        Mode::Raw
                    } else {
                        Mode::Single
                    },
                    s,
                )
            }
            Node::FastNoise(noise, xz_scale, y_scale, shifts) => {
                let nx = x as f64 * xz_scale + ev(shifts[0], s);
                let ny = y as f64 * y_scale + ev(shifts[1], s);
                let nz = z as f64 * xz_scale + ev(shifts[2], s);
                noise.get_noise_3d(nx, ny, nz) as f64
            }
            Node::Constant(v) => *v,
            Node::Beard => {
                if mode == Mode::Raw {
                    0.
                } else {
                    s.beard
                }
            }
            Node::Gradient(a, b, c, d) => {
                clamped_lerp((y as f64 - *a as f64) / (*b as f64 - *a as f64), *c, *d)
            }
            Node::Unary(op, n) => {
                let v = ev(*n, s);
                match op {
                    Unary::Sin
                    | Unary::Cos
                    | Unary::Sqrt
                    | Unary::Floor
                    | Unary::Ceil
                    | Unary::Reciprocal => unary(*op, v),
                    Unary::Abs => v.abs(),
                    Unary::Square => v * v,
                    Unary::Cube => v * v * v,
                    Unary::Half => {
                        if v > 0. {
                            v
                        } else {
                            v * 0.5
                        }
                    }
                    Unary::Quarter => {
                        if v > 0. {
                            v
                        } else {
                            v * 0.25
                        }
                    }
                    Unary::Squeeze => {
                        let v = v.clamp(-1., 1.);
                        v / 2. - v * v * v / 24.
                    }
                }
            }
            Node::Binary(op, a, b) => {
                let a = ev(*a, s);
                match op {
                    Binary::Add => a + ev(*b, s),
                    Binary::Mul => {
                        if a == 0. {
                            0.
                        } else {
                            a * ev(*b, s)
                        }
                    }
                    Binary::Min => java_min(a, ev(*b, s)),
                    Binary::Max => java_max(a, ev(*b, s)),
                }
            }
            Node::Clamp(n, a, b) => ev(*n, s).clamp(*a, *b),
            Node::Range(n, a, b, yes, no) => {
                let v = ev(*n, s);
                ev(if v >= *a && v < *b { *yes } else { *no }, s)
            }
            Node::Noise(n, a, b) => self.noise(*n, x as f64 * a, y as f64 * b, z as f64 * a),
            Node::Shift(n, k) => {
                let [a, b, c] = match k {
                    0 => [x, y, z],
                    1 => [x, 0, z],
                    _ => [z, x, 0],
                };
                self.noise(*n, a as f64 * 0.25, b as f64 * 0.25, c as f64 * 0.25) * 4.
            }
            Node::Shifted(n, a, b, shifts) => self.noise(
                *n,
                x as f64 * a + ev(shifts[0], s),
                y as f64 * b + ev(shifts[1], s),
                z as f64 * a + ev(shifts[2], s),
            ),
            Node::Weird(n, input, two) => {
                let v = ev(*input, s);
                let r = if *two {
                    if v < -0.75 {
                        0.5
                    } else if v < -0.5 {
                        0.75
                    } else if v < 0.5 {
                        1.
                    } else if v < 0.75 {
                        2.
                    } else {
                        3.
                    }
                } else if v < -0.5 {
                    0.75
                } else if v < 0. {
                    1.
                } else if v < 0.5 {
                    1.5
                } else {
                    2.
                };
                r * self
                    .noise(*n, x as f64 / r, y as f64 / r, z as f64 / r)
                    .abs()
            }
            Node::Blended(n) => n.sample(x, y, z),
            Node::End(n) => end_islands(n, x / 8, z / 8),
            Node::Spline(sp) => self.spline(sp, p, mode, s) as f64,
            Node::Marker(kind, n) => match kind {
                Marker::Once
                    if matches!(mode, Mode::Slice | Mode::Cell | Mode::Block)
                        && s.arrays.contains_key(&id) =>
                {
                    s.arrays[&id][s.array_index]
                }
                Marker::Once
                    if self.stateful_columns
                        && matches!(mode, Mode::Slice | Mode::Cell | Mode::Block) =>
                {
                    if s.once[id].0 == s.counter {
                        s.once[id].1
                    } else {
                        let v = ev(*n, s);
                        s.once[id] = (s.counter, v);
                        v
                    }
                }
                Marker::Cell
                    if matches!(mode, Mode::Cell | Mode::Block) && self.stateful_columns =>
                {
                    if let Some(i) = s.cell_index(p) {
                        s.cell_values
                            .get(&id)
                            .and_then(|v| v.get(i))
                            .copied()
                            .unwrap_or(0.)
                    } else {
                        ev(*n, s)
                    }
                }
                Marker::Cache2d if mode != Mode::Raw => {
                    if self.stateful_columns {
                        if let Some((qx, qz, value)) = s.last_2d[id] {
                            if qx == x && qz == z {
                                return value;
                            }
                        }
                        let value = ev(*n, s);
                        s.last_2d[id] = Some((x, z, value));
                        return value;
                    }
                    let key = (*n, x, z);
                    if let Some(&v) = s.cached_2d.get(&key) {
                        v
                    } else {
                        let v = ev(*n, s);
                        if s.cached_2d.len() < 16384 {
                            s.cached_2d.insert(key, v);
                        }
                        v
                    }
                }
                Marker::Flat
                    if mode != Mode::Raw
                        && (s.origin_x..=s.origin_x + s.span).contains(&(x & !3))
                        && (s.origin_z..=s.origin_z + s.span).contains(&(z & !3)) =>
                {
                    let key = (*n, x & !3, z & !3);
                    if let Some(&v) = s.flat.get(&key) {
                        v
                    } else {
                        let v = self.compute(*n, [x & !3, 0, z & !3], Mode::Single, s);
                        s.flat.insert(key, v);
                        v
                    }
                }
                Marker::Interpolated(slot) if mode == Mode::Block || mode == Mode::Cell => {
                    let base = [
                        x.div_euclid(s.width) * s.width,
                        y.div_euclid(s.height) * s.height,
                        z.div_euclid(s.width) * s.width,
                    ];
                    // Adjacent block samples share eight cell corners. Resolve
                    // their hashes once per cell, retaining the exact vanilla
                    // interpolation order below (Cell differs from Block).
                    let v = if let Some((_, values)) = s.cells[*slot].filter(|(p, _)| *p == base) {
                        values
                    } else {
                        let mut values = [0.; 8];
                        for (i, value) in values.iter_mut().enumerate() {
                            let q = [
                                base[0] + (i as i32 & 1) * s.width,
                                base[1] + ((i as i32 >> 1) & 1) * s.height,
                                base[2] + ((i as i32 >> 2) & 1) * s.width,
                            ];
                            let key = (*n, q);
                            *value = if let Some(&v) = s.corners.get(&key) {
                                v
                            } else {
                                let v = self.compute(*n, q, Mode::Single, s);
                                if s.corners.len() < 262144 {
                                    s.corners.insert(key, v);
                                }
                                v
                            };
                        }
                        s.cells[*slot] = Some((base, values));
                        values
                    };
                    let fx = (x - base[0]) as f64 / s.width as f64;
                    let fy = (y - base[1]) as f64 / s.height as f64;
                    let fz = (z - base[2]) as f64 / s.width as f64;
                    // CacheAllInCell uses X,Y,Z; the block loop uses Y,X,Z.
                    if mode == Mode::Cell {
                        lerp(
                            fz,
                            lerp(fy, lerp(fx, v[0], v[1]), lerp(fx, v[2], v[3])),
                            lerp(fy, lerp(fx, v[4], v[5]), lerp(fx, v[6], v[7])),
                        )
                    } else {
                        lerp(
                            fz,
                            lerp(fx, lerp(fy, v[0], v[2]), lerp(fy, v[1], v[3])),
                            lerp(fx, lerp(fy, v[4], v[6]), lerp(fy, v[5], v[7])),
                        )
                    }
                }
                Marker::Cell if mode == Mode::Block => self.compute(*n, p, Mode::Cell, s),
                _ => ev(*n, s),
            },
        };
        s.memo[id] = Some((p, mode, v));
        v
    }
    fn spline(&self, sp: &Spline, p: [i32; 3], mode: Mode, s: &mut Scratch) -> f32 {
        match sp {
            Spline::Constant(v) => *v,
            Spline::Multipoint { coordinate, points } => {
                let f = self.compute(*coordinate, p, mode, s) as f32;
                // Match CubicSpline.findIntervalStart / Mth.binarySearch exactly.
                // Codec-loaded packs may contain duplicate or unordered knots;
                // vanilla preserves their order (only its Builder rejects them).
                let mut next = 0;
                let mut remaining = points.len();
                while remaining > 0 {
                    let half = remaining / 2;
                    let middle = next + half;
                    if f < points[middle].0 {
                        remaining = half;
                    } else {
                        next = middle + 1;
                        remaining -= half + 1;
                    }
                }
                if next == 0 || next == points.len() {
                    let (loc, value, d) = &points[if next == 0 { 0 } else { next - 1 }];
                    let v = self.spline(value, p, mode, s);
                    return if *d == 0. { v } else { v + d * (f - loc) };
                }
                let (a, av, ad) = &points[next - 1];
                let (b, bv, bd) = &points[next];
                let t = (f - a) / (b - a);
                let va = self.spline(av, p, mode, s);
                let vb = self.spline(bv, p, mode, s);
                let d0 = ad * (b - a) - (vb - va);
                let d1 = -bd * (b - a) + (vb - va);
                (va + t * (vb - va)) + t * (1. - t) * (d0 + t * (d1 - d0))
            }
        }
    }
}
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Mode {
    Raw,
    Single,
    Block,
    Cell,
    Slice,
}
pub struct Scratch {
    origin_x: i32,
    origin_z: i32,
    pub span: i32,
    width: i32,
    height: i32,
    memo: Vec<Option<([i32; 3], Mode, f64)>>,
    corners: HashMap<(Id, [i32; 3]), f64>,
    // One entry per interpolator, not per block or graph node.
    cells: Vec<Option<([i32; 3], [f64; 8])>>,
    flat: HashMap<(Id, i32, i32), f64>,
    cached_2d: HashMap<(Id, i32, i32), f64>,
    last_2d: Vec<Option<(i32, i32, f64)>>,
    arrays: HashMap<Id, Vec<f64>>,
    once: Vec<(u64, f64)>,
    counter: u64,
    array_index: usize,
    prepared_x: Option<i32>,
    surface_slices: Option<Vec<i32>>,
    prepared_cell: Option<[i32; 3]>,
    cell_values: HashMap<Id, Vec<f64>>,
    final_values: Vec<f64>,
    pub beard: f64,
}
impl Scratch {
    pub fn clear_surface_slices(&mut self) {
        self.surface_slices = None;
        self.prepared_x = None;
        self.prepared_cell = None;
    }
    fn cell_index(&self, p: [i32; 3]) -> Option<usize> {
        let base = self.prepared_cell?;
        let [x, y, z] = std::array::from_fn(|i| p[i] as i64 - base[i] as i64);
        if !(0..self.width as i64).contains(&x)
            || !(0..self.width as i64).contains(&z)
            || !(0..self.height as i64).contains(&y)
        {
            return None;
        }
        Some(
            (((self.height as i64 - 1 - y) * self.width as i64 + x) * self.width as i64 + z)
                as usize,
        )
    }
    pub fn advance_block(&mut self) {
        self.counter = self.counter.wrapping_add(1);
    }
    pub fn set_beard(&mut self, value: f64) {
        if self.beard != value {
            self.memo.fill(None);
            self.cells.fill(None);
            self.beard = value
        }
    }
}
struct Builder<'a> {
    intern_values: bool,
    graph: Graph,
    doc: &'a Value,
    refs: HashMap<String, Id>,
    active: HashSet<String>,
    intern: HashMap<String, Id>,
    depth: usize,
}
impl Builder<'_> {
    fn parse(&mut self, v: &Value) -> Result<Id> {
        if self.depth >= 128 || self.graph.nodes.len() >= 65536 {
            return Err("density graph budget exceeded".into());
        }
        self.depth += 1;
        let result = self.parse_inner(v);
        self.depth -= 1;
        result
    }
    fn parse_inner(&mut self, v: &Value) -> Result<Id> {
        if let Some(name) = v.as_str() {
            if let Some(&id) = self.refs.get(name) {
                return Ok(id);
            }
            if !self.active.insert(name.into()) {
                return Err(format!("cyclic density reference {name}"));
            }
            let value = self.doc["density_functions"]
                .get(name)
                .ok_or_else(|| format!("missing density {name}"))?;
            let id = self.parse(value)?;
            self.active.remove(name);
            self.refs.insert(name.into(), id);
            return Ok(id);
        }
        let key = v.to_string();
        if let Some(&id) = self.intern.get(&key).filter(|_| self.intern_values) {
            return Ok(id);
        }
        let node = if let Some(c) = v.as_f64() {
            if !c.is_finite() {
                return Err("nonfinite constant".into());
            }
            Node::Constant(c)
        } else {
            let full_type = string(v, "type")?;
            let t = full_type.strip_prefix("minecraft:").unwrap_or(full_type);
            match t {
                "reterraforged:noise" => Node::FtfNoise(
                    crate::freeterraforged_noise::Noise::parse(&v["noise"], self.doc)?,
                ),
                "reterraforged:clamp_to_nearest_unit" => {
                    Node::FtfUnit(self.parse(&v["function"])?, integer(v, "resolution")?)
                }
                "reterraforged:linear_spline" => {
                    let input = self.parse(&v["input"])?;
                    let mut points = Vec::new();
                    for point in v["points"].as_array().ok_or("missing FTF spline points")? {
                        let pair = point
                            .as_array()
                            .filter(|v| v.len() == 2)
                            .ok_or("invalid FTF spline pair")?;
                        let x = pair[0]
                            .as_f64()
                            .filter(|v| v.is_finite())
                            .ok_or("invalid FTF spline position")?;
                        if points.last().is_some_and(|(previous, _)| *previous >= x) {
                            return Err("unordered FTF spline points".into());
                        }
                        points.push((x, self.parse(&pair[1])?));
                    }
                    if points.is_empty() {
                        return Err("empty FTF spline".into());
                    }
                    Node::LinearSpline(input, points)
                }
                "lithostitched:axis" => Node::Axis(match string(v, "axis")? {
                    "x" => 0,
                    "y" => 1,
                    "z" => 2,
                    _ => return Err("invalid axis".into()),
                }),
                "tectonic:invert" => Node::Unary(Unary::Reciprocal, self.parse(&v["argument"])?),
                "lithostitched:sin"
                | "lithostitched:cos"
                | "lithostitched:sqrt"
                | "lithostitched:floor"
                | "lithostitched:ceil" => Node::Unary(
                    match t {
                        "lithostitched:sin" => Unary::Sin,
                        "lithostitched:cos" => Unary::Cos,
                        "lithostitched:sqrt" => Unary::Sqrt,
                        "lithostitched:floor" => Unary::Floor,
                        _ => Unary::Ceil,
                    },
                    self.parse(&v["argument"])?,
                ),
                "lithostitched:mix" => Node::Mix(
                    self.parse(&v["input"])?,
                    self.parse(&v["argument1"])?,
                    self.parse(&v["argument2"])?,
                ),
                "lithostitched:shift" => Node::CoordinateShift(
                    self.parse(&v["input"])?,
                    [
                        self.parse(&v["shift_x"])?,
                        self.parse(&v["shift_y"])?,
                        self.parse(&v["shift_z"])?,
                    ],
                ),
                "lithostitched:select" => {
                    let input = self.parse(&v["input"])?;
                    let fallback = self.parse(&v["fallback"])?;
                    let mut choices = vec![];
                    for choice in v["selections"].as_array().ok_or("missing selections")? {
                        let range = &choice["range"];
                        let (min, max) = if let Some(c) = range.as_f64() {
                            (c, c)
                        } else if let Some(a) = range.as_array() {
                            if a.len() != 2 {
                                return Err("invalid selection range".into());
                            }
                            (
                                a[0].as_f64().ok_or("invalid minimum")?,
                                a[1].as_f64().ok_or("invalid maximum")?,
                            )
                        } else {
                            (
                                number(range, "min_inclusive")?,
                                number(range, "max_inclusive")?,
                            )
                        };
                        if min > max {
                            return Err("inverted selection range".into());
                        }
                        choices.push((min, max, self.parse(&choice["function"])?));
                    }
                    if choices.is_empty() {
                        return Err("empty selections".into());
                    }
                    Node::Select(input, fallback, choices)
                }
                "lithostitched:fast_noise" => Node::FastNoise(
                    Box::new(crate::lithostitched::noise(
                        self.graph.seed,
                        &v["config"],
                        self.doc,
                    )?),
                    v.get("xz_scale")
                        .map_or(Ok(1.), |_| number(v, "xz_scale"))?,
                    v.get("y_scale").map_or(Ok(1.), |_| number(v, "y_scale"))?,
                    [
                        self.parse(v.get("shift_x").unwrap_or(&Value::from(0)))?,
                        self.parse(v.get("shift_y").unwrap_or(&Value::from(0)))?,
                        self.parse(v.get("shift_z").unwrap_or(&Value::from(0)))?,
                    ],
                ),
                "constant" => Node::Constant(number(v, "argument")?),
                "blend_alpha" => Node::Constant(1.),
                "blend_offset" => Node::Constant(0.),
                "beardifier" => Node::Beard,
                "blend_density" => {
                    return self.parse(&v["argument"]);
                }
                "y_clamped_gradient" => Node::Gradient(
                    integer(v, "from_y")?,
                    integer(v, "to_y")?,
                    number(v, "from_value")?,
                    number(v, "to_value")?,
                ),
                "abs" | "square" | "cube" | "half_negative" | "quarter_negative" | "squeeze" => {
                    Node::Unary(
                        match t {
                            "abs" => Unary::Abs,
                            "square" => Unary::Square,
                            "cube" => Unary::Cube,
                            "half_negative" => Unary::Half,
                            "quarter_negative" => Unary::Quarter,
                            _ => Unary::Squeeze,
                        },
                        self.parse(&v["argument"])?,
                    )
                }
                "add" | "mul" | "min" | "max" => Node::Binary(
                    match t {
                        "add" => Binary::Add,
                        "mul" => Binary::Mul,
                        "min" => Binary::Min,
                        _ => Binary::Max,
                    },
                    self.parse(&v["argument1"])?,
                    self.parse(&v["argument2"])?,
                ),
                "clamp" => {
                    let a = number(v, "min")?;
                    let b = number(v, "max")?;
                    if a > b {
                        return Err("inverted clamp".into());
                    }
                    Node::Clamp(self.parse(&v["input"])?, a, b)
                }
                "range_choice" => Node::Range(
                    self.parse(&v["input"])?,
                    number(v, "min_inclusive")?,
                    number(v, "max_exclusive")?,
                    self.parse(&v["when_in_range"])?,
                    self.parse(&v["when_out_of_range"])?,
                ),
                "interpolated" | "flat_cache" | "cache_2d" | "cache_once" | "cache_all_in_cell" => {
                    Node::Marker(
                        match t {
                            "interpolated" => {
                                let slot = self.graph.interpolator_count;
                                self.graph.interpolator_count += 1;
                                Marker::Interpolated(slot)
                            }
                            "flat_cache" => Marker::Flat,
                            "cache_2d" => Marker::Cache2d,
                            "cache_once" => Marker::Once,
                            _ => Marker::Cell,
                        },
                        self.parse(&v["argument"])?,
                    )
                }
                "noise" => Node::Noise(
                    self.graph.registered_noise(string(v, "noise")?, self.doc)?,
                    number(v, "xz_scale")?,
                    number(v, "y_scale")?,
                ),
                "shift" | "shift_a" | "shift_b" => Node::Shift(
                    self.graph
                        .registered_noise(string(v, "argument")?, self.doc)?,
                    match t {
                        "shift" => 0,
                        "shift_a" => 1,
                        _ => 2,
                    },
                ),
                "shifted_noise" => Node::Shifted(
                    self.graph.registered_noise(string(v, "noise")?, self.doc)?,
                    number(v, "xz_scale")?,
                    number(v, "y_scale")?,
                    [
                        self.parse(&v["shift_x"])?,
                        self.parse(&v["shift_y"])?,
                        self.parse(&v["shift_z"])?,
                    ],
                ),
                "weird_scaled_sampler" => {
                    let kind = string(v, "rarity_value_mapper")?;
                    if kind != "type_1" && kind != "type_2" {
                        return Err("unknown rarity mapper".into());
                    }
                    Node::Weird(
                        self.graph.registered_noise(string(v, "noise")?, self.doc)?,
                        self.parse(&v["input"])?,
                        kind == "type_2",
                    )
                }
                "old_blended_noise" => {
                    let p = BlendedParameters {
                        xz_scale: number(v, "xz_scale")?,
                        y_scale: number(v, "y_scale")?,
                        xz_factor: number(v, "xz_factor")?,
                        y_factor: number(v, "y_factor")?,
                        smear_scale_multiplier: number(v, "smear_scale_multiplier")?,
                    };
                    let mut r = if self.graph.legacy {
                        Random::new(self.graph.seed, 0)
                    } else {
                        self.graph.random.from_hash("minecraft:terrain")
                    };
                    Node::Blended(Box::new(
                        BlendedNoise::new(&mut r, p).ok_or("invalid blended noise")?,
                    ))
                }
                "end_islands" => {
                    let mut r = Random::new(self.graph.seed, 0);
                    r.consume(17292);
                    Node::End(Box::new(SimplexNoise::new(&mut r)))
                }
                "spline" => Node::Spline(self.parse_spline(&v["spline"], 0)?),
                _ => return Err(format!("unsupported density codec {full_type}")),
            }
        };
        let id = self.graph.nodes.len();
        self.graph.nodes.push(node);
        self.intern.insert(key, id);
        Ok(id)
    }
    fn parse_spline(&mut self, v: &Value, depth: usize) -> Result<Spline> {
        if depth > 64 {
            return Err("spline depth exceeded".into());
        }
        if let Some(c) = v.as_f64() {
            return Ok(Spline::Constant(c as f32));
        }
        let coordinate = self.parse(&v["coordinate"])?;
        let mut points = vec![];
        let values = v["points"].as_array().ok_or("missing spline points")?;
        if values.is_empty() || values.len() > 1024 {
            return Err("invalid spline size".into());
        }
        for p in values {
            let loc = number(p, "location")? as f32;
            points.push((
                loc,
                self.parse_spline(&p["value"], depth + 1)?,
                number(p, "derivative")? as f32,
            ));
        }
        Ok(Spline::Multipoint { coordinate, points })
    }
}
pub fn lerp(t: f64, a: f64, b: f64) -> f64 {
    a + t * (b - a)
}
fn ftf_unit(value: f64, resolution: i32) -> f64 {
    let scaled = ((value * resolution as f64) as i32).wrapping_add(1) as f32;
    (scaled / resolution as f32) as f64
}
fn unary(op: Unary, v: f64) -> f64 {
    match op {
        // Tectonic Invert.transform uses IEEE division, including signed zero.
        Unary::Reciprocal => 1.0 / v,
        Unary::Sin => v.sin(),
        Unary::Cos => v.cos(),
        Unary::Sqrt => {
            // Lithostitched's released sqrt transformer clamps all non-positive
            // inputs to zero (including negative values).
            if v > 0. { v.sqrt() } else { 0. }
        }
        Unary::Floor => v.floor(),
        Unary::Ceil => v.ceil(),
        Unary::Abs => v.abs(),
        Unary::Square => v * v,
        Unary::Cube => v * v * v,
        Unary::Half => {
            if v > 0. {
                v
            } else {
                v * 0.5
            }
        }
        Unary::Quarter => {
            if v > 0. {
                v
            } else {
                v * 0.25
            }
        }
        Unary::Squeeze => {
            let v = v.clamp(-1., 1.);
            v / 2. - v * v * v / 24.
        }
    }
}

#[cfg(test)]
mod reciprocal_tests {
    use super::*;

    #[test]
    fn tectonic_reciprocal_matches_scalar_and_noise_cell_arrays() {
        let doc = serde_json::json!({"settings": {"legacy_random_source": false, "noise_router": {
            "final_density": {"type": "tectonic:invert", "argument": {
                "type": "minecraft:y_clamped_gradient", "from_y": -8, "to_y": 8,
                "from_value": -2.0, "to_value": 2.0
            }}
        }}});
        let graph = Graph::from_document(731, &doc).unwrap();
        let root = graph.root("final_density").unwrap();
        let points: Vec<_> = (-12..=12).map(|y| [-17, y, 256]).collect();
        let mut scratch = graph.scratch(-32, 256, 4, 8).unwrap();
        let batch = graph.fill_array(root, &points, Mode::Cell, &mut scratch);
        for (point, value) in points.into_iter().zip(batch) {
            let expected = 1.0 / (point[1] as f64 / 4.0).clamp(-2.0, 2.0);
            assert_eq!(expected.to_bits(), value.to_bits());
            assert_eq!(
                expected.to_bits(),
                graph
                    .compute(root, point, Mode::Raw, &mut scratch)
                    .to_bits()
            );
        }
        for value in [
            0.0,
            -0.0,
            f64::MIN_POSITIVE,
            -f64::MIN_POSITIVE,
            f64::INFINITY,
            f64::NEG_INFINITY,
        ] {
            assert_eq!(
                (1.0 / value).to_bits(),
                unary(Unary::Reciprocal, value).to_bits()
            );
        }
    }
}
pub fn clamped_lerp(t: f64, a: f64, b: f64) -> f64 {
    if t < 0. {
        a
    } else if t > 1. {
        b
    } else {
        lerp(t, a, b)
    }
}
fn java_min(a: f64, b: f64) -> f64 {
    if a == 0. && b == 0. {
        f64::from_bits(a.to_bits() | b.to_bits())
    } else if a <= b {
        a
    } else {
        b
    }
}
fn java_max(a: f64, b: f64) -> f64 {
    if a == 0. && b == 0. {
        f64::from_bits(a.to_bits() & b.to_bits())
    } else if a >= b {
        a
    } else {
        b
    }
}
pub fn end_islands(noise: &SimplexNoise, x: i32, z: i32) -> f64 {
    let mut value = (100. - (x.wrapping_mul(x).wrapping_add(z.wrapping_mul(z)) as f32).sqrt() * 8.)
        .clamp(-100., 80.);
    for dx in -12..=12 {
        for dz in -12..=12 {
            let a = (x / 2 + dx) as i64;
            let b = (z / 2 + dz) as i64;
            if a * a + b * b > 4096 && noise.sample2(a as f64, b as f64) < -0.9f32 as f64 {
                let scale = ((a as f32).abs() * 3439. + (b as f32).abs() * 147.) % 13. + 9.;
                let xx = (x % 2 - dx * 2) as f32;
                let zz = (z % 2 - dz * 2) as f32;
                value = value.max((100. - (xx * xx + zz * zz).sqrt() * scale).clamp(-100., 80.));
            }
        }
    }
    (value as f64 - 8.) / 128.
}
