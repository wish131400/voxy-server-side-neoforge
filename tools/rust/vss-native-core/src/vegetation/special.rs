//! Cherry branches, mangrove branches and mangrove roots from the vanilla placers.
use super::*;
pub(super) const HORIZONTAL: [Pos; 4] = [[0, 0, -1], [1, 0, 0], [0, 0, 1], [-1, 0, 0]];
pub(super) struct BlockSet {
    tag: Option<String>,
    names: Vec<String>,
}
impl BlockSet {
    pub fn parse(v: &Value) -> Result<Self> {
        if let Some(tag) = v.as_str().and_then(|s| s.strip_prefix('#')) {
            return Ok(Self {
                tag: Some(tag.into()),
                names: vec![],
            });
        }
        let names = if let Some(s) = v.as_str() {
            vec![s.into()]
        } else {
            serde_json::from_value(v.clone()).map_err(|_| "invalid block holder set")?
        };
        Ok(Self { tag: None, names })
    }
    pub fn contains(&self, p: &Palette, id: StateId) -> bool {
        self.tag.as_ref().is_some_and(|t| p.tag(id, t)) || self.names.iter().any(|s| p.is(id, s))
    }
}
pub(super) enum Branches {
    None,
    Cherry {
        count: IntProvider,
        length: IntProvider,
        end: IntProvider,
        start: [i32; 2],
    },
    Upwards {
        steps: IntProvider,
        chance: f32,
        length: IntProvider,
        through: BlockSet,
    },
}
impl Branches {
    pub fn parse(v: &Value) -> Result<Self> {
        Ok(match minecraft_type(v)? {
            "cherry_trunk_placer" => {
                let start = &v["branch_start_offset_from_top"];
                let a = integer(start, "min_inclusive")?;
                let b = integer(start, "max_inclusive")?;
                if !(-16..=0).contains(&a) || b <= a || b > 0 {
                    return Err("invalid cherry branch interval".into());
                }
                Self::Cherry {
                    count: IntProvider::parse(&v["branch_count"])?,
                    length: IntProvider::parse(&v["branch_horizontal_length"])?,
                    end: IntProvider::parse(&v["branch_end_offset_from_top"])?,
                    start: [a, b],
                }
            }
            "upwards_branching_trunk_placer" => Self::Upwards {
                steps: IntProvider::parse(&v["extra_branch_steps"])?,
                chance: number(v, "place_branch_per_log_probability")? as f32,
                length: IntProvider::parse(&v["extra_branch_length"])?,
                through: BlockSet::parse(&v["can_grow_through"])?,
            },
            _ => Self::None,
        })
    }
}
pub(super) struct Roots {
    pub offset: IntProvider,
    provider: StateProvider,
    above: Option<(StateProvider, f32)>,
    through: BlockSet,
    muddy: BlockSet,
    muddy_provider: StateProvider,
    width: i32,
    length: i32,
    skew: f32,
}
impl Roots {
    pub fn parse(v: &Value, p: &mut Palette) -> Result<Self> {
        if minecraft_type(v)? != "mangrove_root_placer" {
            return Err("unsupported root placer".into());
        }
        let m = &v["mangrove_root_placement"];
        let width = integer(m, "max_root_width")?;
        let length = integer(m, "max_root_length")?;
        if !(1..=8).contains(&width) || !(1..=15).contains(&length) {
            return Err("root bounds out of range".into());
        }
        let above = v
            .get("above_root_placement")
            .map(|v| -> Result<_> {
                Ok((
                    StateProvider::parse(&v["above_root_provider"], p)?,
                    number(v, "above_root_placement_chance")? as f32,
                ))
            })
            .transpose()?;
        Ok(Self {
            offset: IntProvider::parse(&v["trunk_offset_y"])?,
            provider: StateProvider::parse(&v["root_provider"], p)?,
            above,
            through: BlockSet::parse(&m["can_grow_through"])?,
            muddy: BlockSet::parse(&m["muddy_roots_in"])?,
            muddy_provider: StateProvider::parse(&m["muddy_roots_provider"], p)?,
            width,
            length,
            skew: number(m, "random_skew_chance")? as f32,
        })
    }
    fn can_place(&self, w: &mut Volume, p: Pos) -> bool {
        let b = w.get(p);
        w.palette.tree_replaceable(b) || self.through.contains(&w.palette, b)
    }
    fn simulate(
        &self,
        w: &mut Volume,
        r: &mut Random,
        p: Pos,
        d: Pos,
        origin: Pos,
        list: &mut Vec<Pos>,
        depth: i32,
    ) -> bool {
        if depth == self.length || list.len() > self.length as usize {
            return false;
        }
        let below = offset(p, 0, -1, 0);
        let side = offset(p, d[0], 0, d[2]);
        let dist: i32 = (0..3).map(|i| (p[i] - origin[i]).abs()).sum();
        let positions = if dist > self.width - 3 && dist <= self.width {
            if r.next_float() < self.skew {
                vec![below, offset(side, 0, -1, 0)]
            } else {
                vec![below]
            }
        } else if dist > self.width || r.next_float() < self.skew {
            vec![below]
        } else if r.next_bool() {
            vec![side]
        } else {
            vec![below]
        };
        for q in positions {
            if self.can_place(w, q) {
                list.push(q);
                if !self.simulate(w, r, q, d, origin, list, depth + 1) {
                    return false;
                }
            }
        }
        true
    }
    pub fn place(
        &self,
        w: &mut Volume,
        r: &mut Random,
        base: Pos,
        trunk: Pos,
        out: &mut Vec<Pos>,
    ) -> Result<bool> {
        for y in base[1]..trunk[1] {
            if !self.can_place(w, [base[0], y, base[2]]) {
                return Ok(false);
            }
        }
        let mut list = vec![offset(trunk, 0, -1, 0)];
        for d in HORIZONTAL {
            let q = offset(trunk, d[0], 0, d[2]);
            let mut branch = vec![];
            if !self.simulate(w, r, q, d, trunk, &mut branch, 0) {
                return Ok(false);
            }
            list.extend(branch);
            list.push(q);
        }
        for q in list {
            let b = w.get(q);
            if self.muddy.contains(&w.palette, b) {
                let state = self.muddy_provider.state(r, q, &mut w.palette)?;
                set_root(w, q, state, out)?;
            } else if self.can_place(w, q) {
                let state = self.provider.state(r, q, &mut w.palette)?;
                set_root(w, q, state, out)?;
                if let Some((provider, chance)) = &self.above {
                    let above = offset(q, 0, 1, 0);
                    if r.next_float() < *chance {
                        let b = w.get(above);
                        if w.palette.is_air(b) {
                            let state = provider.state(r, above, &mut w.palette)?;
                            set_root(w, above, state, out)?;
                        }
                    }
                }
            }
        }
        Ok(true)
    }
}
fn set_root(w: &mut Volume, p: Pos, mut state: StateId, out: &mut Vec<Pos>) -> Result<()> {
    if w.palette
        .state(state)
        .properties
        .contains_key("waterlogged")
    {
        let b = w.get(p);
        let water = w.palette.water(b);
        state = w
            .palette
            .with(state, "waterlogged", if water { "true" } else { "false" })?;
    }
    w.set(p, state);
    out.push(p);
    Ok(())
}
impl Tree {
    pub(super) fn branch_trunks(
        &self,
        w: &mut Volume,
        r: &mut Random,
        p: Pos,
        height: i32,
        logs: &mut Vec<Pos>,
    ) -> Result<Vec<(Pos, i32, bool)>> {
        let mut out = vec![];
        match &self.branches {
            Branches::Cherry {
                count,
                length,
                end,
                start,
            } => {
                self.dirt_at(w, r, offset(p, 0, -1, 0), logs)?;
                let a = (height - 1 + start[0] + r.next_bounded(start[1] - start[0] + 1)).max(0);
                let mut b = (height - 1 + start[0] + r.next_bounded(start[1] - start[0])).max(0);
                if b >= a {
                    b += 1;
                }
                let count = count.sample(r);
                if !(1..=3).contains(&count) {
                    return Err("cherry branch count".into());
                }
                let len = if count == 3 {
                    height
                } else if count >= 2 {
                    a.max(b) + 1
                } else {
                    a + 1
                };
                for y in 0..len {
                    self.log_at(w, r, offset(p, 0, y, 0), logs)?;
                }
                if count == 3 {
                    out.push((offset(p, 0, len, 0), 0, false));
                }
                let dir = HORIZONTAL[r.next_bounded(4) as usize];
                for (index, start) in
                    [a, b]
                        .into_iter()
                        .enumerate()
                        .take(if count >= 2 { 2 } else { 1 })
                {
                    let d = if index == 0 {
                        dir
                    } else {
                        [-dir[0], 0, -dir[2]]
                    };
                    let mut q = offset(p, 0, start, 0);
                    let target_y = height - 1 + end.sample(r);
                    let bent = start < len - 1 || target_y < start;
                    let horizontal = length.sample(r) + i32::from(bent);
                    if !(1..=32).contains(&horizontal) || !(-32..=128).contains(&target_y) {
                        return Err("cherry branch length budget".into());
                    }
                    let target = offset(p, d[0] * horizontal, target_y, d[2] * horizontal);
                    for _ in 0..if bent { 2 } else { 1 } {
                        q = offset(q, d[0], 0, d[2]);
                        self.axis_log(w, r, q, if d[0] != 0 { "x" } else { "z" }, logs)?;
                    }
                    let dy = if target[1] > q[1] { 1 } else { -1 };
                    for _ in 0..256 {
                        let distance: i32 = (0..3).map(|i| (target[i] - q[i]).abs()).sum();
                        if distance == 0 {
                            break;
                        }
                        let vertical =
                            r.next_float() < (target[1] - q[1]).abs() as f32 / distance as f32;
                        if vertical {
                            q[1] += dy;
                            self.log_at(w, r, q, logs)?;
                        } else {
                            q = offset(q, d[0], 0, d[2]);
                            self.axis_log(w, r, q, if d[0] != 0 { "x" } else { "z" }, logs)?;
                        }
                    }
                    if q != target {
                        return Err("cherry branch failed to converge".into());
                    }
                    out.push((offset(target, 0, 1, 0), 0, false));
                }
            }
            Branches::Upwards {
                steps,
                chance,
                length,
                ..
            } => {
                for y in 0..height {
                    let base = offset(p, 0, y, 0);
                    if self.log_at(w, r, base, logs)? && y < height - 1 && r.next_float() < *chance
                    {
                        let d = HORIZONTAL[r.next_bounded(4) as usize];
                        let k = length.sample(r);
                        let start = (k - length.sample(r) - 1).max(0);
                        let mut amount = steps.sample(r);
                        if !(0..=64).contains(&start) || !(0..=64).contains(&amount) {
                            return Err("upwards branch budget".into());
                        }
                        let mut top = base[1] + start;
                        let mut x = p[0];
                        let mut z = p[2];
                        for l in start..height {
                            if amount <= 0 {
                                break;
                            }
                            if l >= 1 {
                                x += d[0];
                                z += d[2];
                                let q = [x, base[1] + l, z];
                                top = q[1];
                                if self.log_at(w, r, q, logs)? {
                                    top += 1;
                                };
                                out.push((q, 0, false));
                            }
                            amount -= 1;
                        }
                        if top - base[1] > 1 {
                            out.push(([x, top, z], 0, false));
                            out.push(([x, top - 2, z], 0, false));
                        }
                    }
                    if y == height - 1 {
                        out.push((offset(p, 0, y + 1, 0), 0, false));
                    }
                }
            }
            Branches::None => return Err("missing branch definition".into()),
        }
        Ok(out)
    }
    fn axis_log(
        &self,
        w: &mut Volume,
        r: &mut Random,
        p: Pos,
        axis: &str,
        logs: &mut Vec<Pos>,
    ) -> Result<()> {
        let b = w.get(p);
        if !self.valid_trunk(&w.palette, b) {
            return Ok(());
        }
        let mut state = self.trunk.state(r, p, &mut w.palette)?;
        if w.palette.state(state).properties.contains_key("axis") {
            state = w.palette.with(state, "axis", axis)?;
        }
        w.set(p, state);
        logs.push(p);
        Ok(())
    }
    pub(super) fn cherry_foliage(
        &self,
        w: &mut Volume,
        r: &mut Random,
        top: Pos,
        radius: i32,
        height: i32,
        double: bool,
        leaves: &mut Vec<Pos>,
    ) -> Result<()> {
        self.row_wide(w, r, top, radius - 2, height - 3, false, double, leaves)?;
        self.row_wide(w, r, top, radius - 1, height - 4, false, double, leaves)?;
        for y in (0..=height - 5).rev() {
            self.row_wide(w, r, top, radius, y, false, double, leaves)?;
        }
        for (size, y) in [(radius, -1), (radius - 1, -2)] {
            self.row_wide(w, r, top, size, y, false, double, leaves)?;
            let center = offset(top, 0, -1, 0);
            for i in 0..4 {
                let d = HORIZONTAL[i];
                let clockwise = HORIZONTAL[(i + 1) % 4];
                let extent = size
                    + if clockwise[0] > 0 || clockwise[2] > 0 {
                        i32::from(double)
                    } else {
                        0
                    };
                let mut q = offset(
                    top,
                    clockwise[0] * extent - d[0] * size,
                    y - 1,
                    clockwise[2] * extent - d[2] * size,
                );
                for _ in -size..size + i32::from(double) {
                    if leaves.contains(&offset(q, 0, 1, 0))
                        && self.extension(w, r, q, center, self.cherry_chances[2], leaves)?
                    {
                        self.extension(
                            w,
                            r,
                            offset(q, 0, -1, 0),
                            center,
                            self.cherry_chances[3],
                            leaves,
                        )?;
                    }
                    q = offset(q, d[0], 0, d[2]);
                }
            }
        }
        Ok(())
    }
    fn extension(
        &self,
        w: &mut Volume,
        r: &mut Random,
        p: Pos,
        center: Pos,
        chance: f32,
        leaves: &mut Vec<Pos>,
    ) -> Result<bool> {
        if (0..3).map(|i| (p[i] - center[i]).abs()).sum::<i32>() >= 7 || r.next_float() > chance {
            return Ok(false);
        }
        let b = w.get(p);
        if !w.palette.tree_replaceable(b) {
            return Ok(false);
        }
        self.leaf(w, r, p, leaves)?;
        Ok(true)
    }
}
