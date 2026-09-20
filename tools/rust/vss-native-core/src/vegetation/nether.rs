//! Nether placement uses the original feature order and random stream on full columns.
use super::*;

pub enum Anchor {
    Absolute(i32),
    Bottom(i32),
    Top(i32),
}
impl Anchor {
    fn parse(v: &Value) -> Result<Self> {
        if v.get("absolute").is_some() {
            Ok(Self::Absolute(integer(v, "absolute")?))
        } else if v.get("above_bottom").is_some() {
            Ok(Self::Bottom(integer(v, "above_bottom")?))
        } else if v.get("below_top").is_some() {
            Ok(Self::Top(integer(v, "below_top")?))
        } else {
            Err("unsupported vertical anchor".into())
        }
    }
    fn resolve(&self, w: &Volume) -> i32 {
        match *self {
            Self::Absolute(y) => y,
            Self::Bottom(y) => w.origin[1] + y,
            Self::Top(y) => w.origin[1] + w.size[1] as i32 - 1 - y,
        }
    }
}
pub enum HeightRange {
    Constant(Anchor),
    Uniform(Anchor, Anchor),
}
impl HeightRange {
    pub fn parse(v: &Value) -> Result<Self> {
        if v.get("type").is_none() {
            return Ok(Self::Constant(Anchor::parse(v)?));
        }
        match minecraft_type(v)? {
            "uniform" => Ok(Self::Uniform(
                Anchor::parse(&v["min_inclusive"])?,
                Anchor::parse(&v["max_inclusive"])?,
            )),
            "constant" => Ok(Self::Constant(Anchor::parse(&v["value"])?)),
            name => Err(format!("unsupported height provider {name}")),
        }
    }
    pub fn sample(&self, w: &Volume, r: &mut Random) -> Result<i32> {
        match self {
            Self::Constant(a) => Ok(a.resolve(w)),
            Self::Uniform(a, b) => {
                let lo = a.resolve(w);
                let hi = b.resolve(w);
                if hi < lo {
                    return Ok(lo);
                }
                let span = hi as i64 - lo as i64 + 1;
                if span > i32::MAX as i64 {
                    return Err("height range overflow".into());
                }
                Ok(lo + r.next_bounded(span as i32))
            }
        }
    }
}

fn air(w: &mut Volume, p: Pos) -> bool {
    let b = w.get(p);
    w.palette.is_air(b)
}
fn is(w: &mut Volume, p: Pos, names: &[&str]) -> bool {
    let b = w.get(p);
    names.iter().any(|n| w.palette.is(b, n))
}
fn empty(w: &mut Volume, p: Pos) -> bool {
    let b = w.get(p);
    w.palette.is_air(b) || w.palette.is(b, "minecraft:water") || w.palette.is(b, "minecraft:lava")
}
const DIRECTIONS: [Pos; 6] = [
    [0, -1, 0],
    [0, 1, 0],
    [0, 0, -1],
    [0, 0, 1],
    [-1, 0, 0],
    [1, 0, 0],
];

pub fn layer_positions(
    w: &mut Volume,
    r: &mut Random,
    p: Pos,
    count: &IntProvider,
    c: &mut PlacementContext<'_>,
) -> Result<Vec<Pos>> {
    let mut positions = Vec::new();
    for layer in 0..=w.size[1] {
        let mut found = false;
        let mut attempt = 0;
        // The count provider is sampled in the loop condition, as in vanilla.
        while attempt < count.sample(r) {
            c.charge()?;
            attempt += 1;
            let x = p[0] + r.next_bounded(16);
            let z = p[2] + r.next_bounded(16);
            let top = w.heightmap(x, z, 2);
            let mut before = empty(w, [x, top, z]);
            let mut seen = 0;
            for y in (w.origin[1] + 1..=top).rev() {
                let q = [x, y - 1, z];
                let now = empty(w, q);
                if !now && before && !is(w, q, &["minecraft:bedrock"]) {
                    if seen == layer {
                        positions.push([x, y, z]);
                        found = true;
                        break;
                    }
                    seen += 1;
                }
                before = now;
            }
        }
        if !found {
            return Ok(positions);
        }
    }
    Err("layer placement budget".into())
}

pub enum NetherFeature {
    Fungus {
        base: StateId,
        stem: StateId,
        hat: StateId,
        decor: StateId,
        planted: bool,
        replace: Predicate,
    },
    Forest(StateProvider, i32, i32),
    Glowstone,
    Weeping,
    Twisting(i32, i32, i32),
    Spring(StateId, bool, i32, i32, Predicate),
}
fn bounded(v: &Value, key: &str) -> Result<i32> {
    let n = integer(v, key)?;
    if !(1..=64).contains(&n) {
        return Err("nether feature extent".into());
    }
    Ok(n)
}
impl NetherFeature {
    pub fn parse(kind: &str, v: &Value, p: &mut Palette) -> Result<Self> {
        Ok(match kind {
            "huge_fungus" => Self::Fungus {
                base: p.intern(&v["valid_base_block"])?,
                stem: p.intern(&v["stem_state"])?,
                hat: p.intern(&v["hat_state"])?,
                decor: p.intern(&v["decor_state"])?,
                planted: v["planted"].as_bool().unwrap_or(false),
                replace: Predicate::parse(&v["replaceable_blocks"], p, 0)?,
            },
            "nether_forest_vegetation" => Self::Forest(
                StateProvider::parse(&v["state_provider"], p)?,
                bounded(v, "spread_width")?,
                bounded(v, "spread_height")?,
            ),
            "glowstone_blob" => Self::Glowstone,
            "weeping_vines" => Self::Weeping,
            "twisting_vines" => Self::Twisting(
                bounded(v, "spread_width")?,
                bounded(v, "spread_height")?,
                bounded(v, "max_height")?,
            ),
            "spring_feature" => {
                let name = string(&v["state"], "Name")?;
                if !matches!(name, "minecraft:lava" | "minecraft:water") {
                    return Err("unsupported spring fluid".into());
                }
                let base = p.named(name)?;
                // SourceFluid creates a level-zero block even if its falling property is true.
                let state = p.with(base, "level", "0")?;
                let test = serde_json::json!({"type":"minecraft:matching_blocks","blocks":v["valid_blocks"]});
                Self::Spring(
                    state,
                    v["requires_block_below"].as_bool().unwrap_or(true),
                    integer(v, "rock_count")?,
                    integer(v, "hole_count")?,
                    Predicate::parse(&test, p, 0)?,
                )
            }
            _ => return Err("unsupported nether feature".into()),
        })
    }
    pub fn place(
        &self,
        w: &mut Volume,
        r: &mut Random,
        p: Pos,
        c: &mut PlacementContext<'_>,
    ) -> Result<bool> {
        match self {
            Self::Fungus {
                base,
                stem,
                hat,
                decor,
                planted,
                replace,
            } => {
                let ground = w.get(offset(p, 0, -1, 0));
                if w.palette.state(ground).name != w.palette.state(*base).name {
                    return Ok(false);
                }
                let mut height = 4 + r.next_bounded(10);
                if r.next_bounded(12) == 0 {
                    height *= 2;
                }
                if !planted && p[1] + height + 1 >= w.size[1] as i32 {
                    return Ok(false);
                }
                let huge = !*planted && r.next_float() < 0.06;
                w.set(p, w.palette.air);
                let radius = if huge { 1i32 } else { 0 };
                for x in -radius..=radius {
                    for z in -radius..=radius {
                        let corner = huge && x.abs() == radius && z.abs() == radius;
                        for y in 0..height {
                            c.charge()?;
                            let q = offset(p, x, y, z);
                            if replaceable(w, q)? || replace.test(w, q)? {
                                if *planted || !corner || r.next_float() < 0.1 {
                                    w.set(q, *stem);
                                }
                            }
                        }
                    }
                }
                let crimson = w.palette.is(*hat, "minecraft:nether_wart_block");
                let layers = (r.next_bounded(1 + height / 3) + 5).min(height);
                let start = height - layers;
                for y in start..=height {
                    let mut radius = if y < height - r.next_bounded(3) { 2 } else { 1 };
                    if layers > 8 && y < start + 4 {
                        radius = 3;
                    }
                    if huge {
                        radius += 1;
                    }
                    for x in -radius..=radius {
                        for z in -radius..=radius {
                            c.charge()?;
                            let edge_x = x == -radius || x == radius;
                            let edge_z = z == -radius || z == radius;
                            let inside = !edge_x && !edge_z && y != height;
                            let corner = edge_x && edge_z;
                            let q = offset(p, x, y, z);
                            if !replaceable(w, q)? {
                                continue;
                            }
                            if y < start + 3 {
                                if !inside {
                                    let under = w.get(offset(q, 0, -1, 0));
                                    if w.palette.state(under).name == w.palette.state(*hat).name {
                                        w.set(q, *hat);
                                    } else if (r.next_float() as f64) < 0.15 {
                                        w.set(q, *hat);
                                        if crimson && r.next_bounded(11) == 0 {
                                            fungus_vines(w, r, q)?;
                                        }
                                    }
                                }
                            } else {
                                let (light, fill, vines) = if inside {
                                    (0.1, 0.2, 0.1)
                                } else if corner {
                                    (0.01, 0.7, 0.083)
                                } else {
                                    (0.0005, 0.98, 0.07)
                                };
                                if r.next_float() < light {
                                    w.set(q, *decor);
                                } else if r.next_float() < fill {
                                    w.set(q, *hat);
                                    if r.next_float() < if crimson { vines } else { 0.0 } {
                                        fungus_vines(w, r, q)?;
                                    }
                                }
                            }
                        }
                    }
                }
                Ok(true)
            }
            Self::Forest(provider, width, height) => {
                let below = w.get(offset(p, 0, -1, 0));
                if !w.palette.tag(below, "minecraft:nylium")
                    || p[1] < w.origin[1] + 1
                    || p[1] + 1 >= w.origin[1] + w.size[1] as i32
                {
                    return Ok(false);
                }
                let mut placed = false;
                for _ in 0..width * width {
                    c.charge()?;
                    let q = offset(
                        p,
                        r.next_bounded(*width) - r.next_bounded(*width),
                        r.next_bounded(*height) - r.next_bounded(*height),
                        r.next_bounded(*width) - r.next_bounded(*width),
                    );
                    let id = provider.state(r, q, &mut w.palette)?;
                    if air(w, q) && q[1] > w.origin[1] && survives(w, q, id)? {
                        w.set(q, id);
                        placed = true;
                    }
                }
                Ok(placed)
            }
            Self::Glowstone => {
                if !air(w, p)
                    || !is(
                        w,
                        offset(p, 0, 1, 0),
                        &[
                            "minecraft:netherrack",
                            "minecraft:basalt",
                            "minecraft:blackstone",
                        ],
                    )
                {
                    return Ok(false);
                }
                let glow = w.palette.named("minecraft:glowstone")?;
                w.set(p, glow);
                for _ in 0..1500 {
                    c.charge()?;
                    let q = offset(
                        p,
                        r.next_bounded(8) - r.next_bounded(8),
                        -r.next_bounded(12),
                        r.next_bounded(8) - r.next_bounded(8),
                    );
                    if air(w, q) {
                        let count = DIRECTIONS
                            .iter()
                            .filter(|d| {
                                is(w, offset(q, d[0], d[1], d[2]), &["minecraft:glowstone"])
                            })
                            .count();
                        if count == 1 {
                            w.set(q, glow);
                        }
                    }
                }
                Ok(true)
            }
            Self::Weeping => {
                if !air(w, p)
                    || !is(
                        w,
                        offset(p, 0, 1, 0),
                        &["minecraft:netherrack", "minecraft:nether_wart_block"],
                    )
                {
                    return Ok(false);
                }
                let wart = w.palette.named("minecraft:nether_wart_block")?;
                w.set(p, wart);
                for _ in 0..200 {
                    c.charge()?;
                    let q = offset(
                        p,
                        r.next_bounded(6) - r.next_bounded(6),
                        r.next_bounded(2) - r.next_bounded(5),
                        r.next_bounded(6) - r.next_bounded(6),
                    );
                    if air(w, q) {
                        let count = DIRECTIONS
                            .iter()
                            .filter(|d| {
                                is(
                                    w,
                                    offset(q, d[0], d[1], d[2]),
                                    &["minecraft:netherrack", "minecraft:nether_wart_block"],
                                )
                            })
                            .count();
                        if count == 1 {
                            w.set(q, wart);
                        }
                    }
                }
                for _ in 0..100 {
                    c.charge()?;
                    let q = offset(
                        p,
                        r.next_bounded(8) - r.next_bounded(8),
                        r.next_bounded(2) - r.next_bounded(7),
                        r.next_bounded(8) - r.next_bounded(8),
                    );
                    if air(w, q)
                        && is(
                            w,
                            offset(q, 0, 1, 0),
                            &["minecraft:netherrack", "minecraft:nether_wart_block"],
                        )
                    {
                        let mut n = 1 + r.next_bounded(8);
                        if r.next_bounded(6) == 0 {
                            n *= 2;
                        }
                        if r.next_bounded(5) == 0 {
                            n = 1;
                        }
                        vine_column(w, r, q, n, 17, false)?;
                    }
                }
                Ok(true)
            }
            Self::Twisting(width, height, max) => {
                if !twisting_ground(w, p) {
                    return Ok(false);
                }
                for _ in 0..width * width {
                    c.charge()?;
                    let mut q = offset(
                        p,
                        r.next_bounded(width * 2 + 1) - width,
                        r.next_bounded(height * 2 + 1) - height,
                        r.next_bounded(width * 2 + 1) - width,
                    );
                    loop {
                        q[1] -= 1;
                        if q[1] < w.origin[1]
                            || q[1] >= w.origin[1] + w.size[1] as i32
                            || !air(w, q)
                        {
                            break;
                        }
                    }
                    if q[1] < w.origin[1] || q[1] >= w.origin[1] + w.size[1] as i32 {
                        continue;
                    }
                    q[1] += 1;
                    if twisting_ground(w, q) {
                        let mut n = 1 + r.next_bounded(*max);
                        if r.next_bounded(6) == 0 {
                            n *= 2;
                        }
                        if r.next_bounded(5) == 0 {
                            n = 1;
                        }
                        vine_column(w, r, q, n, 17, true)?;
                    }
                }
                Ok(true)
            }
            Self::Spring(state, below, rocks, holes, valid) => {
                if !valid.test(w, offset(p, 0, 1, 0))?
                    || *below && !valid.test(w, offset(p, 0, -1, 0))?
                    || !air(w, p) && !valid.test(w, p)?
                {
                    return Ok(false);
                }
                let mut rock = 0;
                let mut hole = 0;
                for d in [[-1, 0, 0], [1, 0, 0], [0, 0, -1], [0, 0, 1], [0, -1, 0]] {
                    let q = offset(p, d[0], d[1], d[2]);
                    if valid.test(w, q)? {
                        rock += 1;
                    }
                    if air(w, q) {
                        hole += 1;
                    }
                }
                if rock == *rocks && hole == *holes {
                    w.set(p, *state);
                    Ok(true)
                } else {
                    Ok(false)
                }
            }
        }
    }
}
fn replaceable(w: &mut Volume, p: Pos) -> Result<bool> {
    let id = w.get(p);
    if w.palette.is_air(id) {
        return Ok(true);
    }
    w.palette
        .definition(id)
        .replaceable
        .ok_or_else(|| "missing block replaceability".into())
}
fn twisting_ground(w: &mut Volume, p: Pos) -> bool {
    air(w, p)
        && is(
            w,
            offset(p, 0, -1, 0),
            &[
                "minecraft:netherrack",
                "minecraft:warped_nylium",
                "minecraft:warped_wart_block",
            ],
        )
}
fn fungus_vines(w: &mut Volume, r: &mut Random, p: Pos) -> Result<()> {
    let q = offset(p, 0, -1, 0);
    if air(w, q) {
        let mut n = 1 + r.next_bounded(5);
        if r.next_bounded(7) == 0 {
            n *= 2;
        }
        vine_column(w, r, q, n, 23, false)?;
    }
    Ok(())
}
fn vine_column(
    w: &mut Volume,
    r: &mut Random,
    mut p: Pos,
    n: i32,
    age: i32,
    up: bool,
) -> Result<()> {
    let head = w.palette.named(if up {
        "minecraft:twisting_vines"
    } else {
        "minecraft:weeping_vines"
    })?;
    let body = w.palette.named(if up {
        "minecraft:twisting_vines_plant"
    } else {
        "minecraft:weeping_vines_plant"
    })?;
    let dy = if up { 1 } else { -1 };
    for i in if up { 1 } else { 0 }..=n {
        if air(w, p) {
            if i == n || !air(w, offset(p, 0, dy, 0)) {
                let id =
                    w.palette
                        .with(head, "age", &(age + r.next_bounded(26 - age)).to_string())?;
                w.set(p, id);
                break;
            }
            w.set(p, body);
        }
        p[1] += dy;
    }
    Ok(())
}
