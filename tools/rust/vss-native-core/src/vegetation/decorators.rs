//! Vanilla tree decorators, applied in configuration order on the same random stream.
use super::*;
pub(super) enum Decorator {
    TrunkVine,
    LeafVine(f32),
    Cocoa(f32),
    Beehive(f32),
    Ground(StateProvider),
    Attached {
        chance: f32,
        xz: i32,
        y: i32,
        provider: StateProvider,
        empty: i32,
        directions: Vec<Pos>,
    },
}
impl Decorator {
    pub fn parse(v: &Value, palette: &mut Palette) -> Result<Self> {
        Ok(match minecraft_type(v)? {
            "trunk_vine" => Self::TrunkVine,
            "leave_vine" => Self::LeafVine(number(v, "probability")? as f32),
            "cocoa" => Self::Cocoa(number(v, "probability")? as f32),
            "beehive" => Self::Beehive(number(v, "probability")? as f32),
            "alter_ground" => Self::Ground(StateProvider::parse(&v["provider"], palette)?),
            "attached_to_leaves" => {
                let xz = integer(v, "exclusion_radius_xz")?;
                let y = integer(v, "exclusion_radius_y")?;
                let empty = integer(v, "required_empty_blocks")?;
                if !(0..=16).contains(&xz) || !(0..=16).contains(&y) || !(1..=16).contains(&empty) {
                    return Err("attached decoration budget".into());
                }
                let directions = v["directions"]
                    .as_array()
                    .ok_or("missing directions")?
                    .iter()
                    .map(|v| direction(v.as_str().ok_or("invalid direction")?))
                    .collect::<Result<Vec<_>>>()?;
                if directions.is_empty() || directions.len() > 64 {
                    return Err("direction count out of range".into());
                }
                Self::Attached {
                    chance: number(v, "probability")? as f32,
                    xz,
                    y,
                    provider: StateProvider::parse(&v["block_provider"], palette)?,
                    empty,
                    directions,
                }
            }
            s => return Err(format!("unsupported tree decorator {s}")),
        })
    }
    pub fn place(
        &self,
        w: &mut Volume,
        r: &mut Random,
        logs: &[Pos],
        leaves: &[Pos],
        roots: &[Pos],
        out: &mut Vec<Pos>,
    ) -> Result<()> {
        match self {
            Self::Beehive(chance) => {
                if r.next_float() < *chance {
                    let first = *logs.first().ok_or("beehive requires tree logs")?;
                    let y = if let Some(leaf) = leaves.first() {
                        (leaf[1] - 1).max(first[1] + 1)
                    } else {
                        (first[1] + 1 + r.next_bounded(3)).min(logs.last().unwrap()[1])
                    };
                    let mut candidates = Vec::new();
                    // Direction.Plane.HORIZONTAL, with NORTH removed.
                    for &p in logs.iter().filter(|p| p[1] == y) {
                        for d in [[1, 0, 0], [0, 0, 1], [-1, 0, 0]] {
                            candidates.push(offset(p, d[0], d[1], d[2]));
                        }
                    }
                    if !candidates.is_empty() {
                        let entropy = w
                            .decoration_entropy
                            .as_mut()
                            .ok_or("beehive placement requires external shuffle entropy")?;
                        for i in (2..=candidates.len()).rev() {
                            let j = entropy.next_bounded(i as i32) as usize;
                            candidates.swap(i - 1, j);
                        }
                        for p in candidates {
                            let a = w.get(p);
                            let b = w.get(offset(p, 0, 0, 1));
                            if w.palette.is_air(a) && w.palette.is_air(b) {
                                let nest = w.palette.named("minecraft:bee_nest")?;
                                let nest = w.palette.with(nest, "facing", "south")?;
                                w.set(p, nest);
                                out.push(p);
                                // Prediction worlds have no block entities, so
                                // vanilla's Optional.ifPresent bee loop is absent.
                                break;
                            }
                        }
                    }
                }
            }
            Self::TrunkVine | Self::LeafVine(_) => {
                let (positions, hanging) = if matches!(self, Self::TrunkVine) {
                    (logs, false)
                } else {
                    (leaves, true)
                };
                let vine = w.palette.named("minecraft:vine")?;
                for &p in positions {
                    for (dx, dz, face) in [
                        (-1, 0, "east"),
                        (1, 0, "west"),
                        (0, -1, "south"),
                        (0, 1, "north"),
                    ] {
                        let place = match self {
                            Self::LeafVine(chance) => r.next_float() < *chance,
                            _ => r.next_bounded(3) > 0,
                        };
                        if place {
                            let mut q = offset(p, dx, 0, dz);
                            let b = w.get(q);
                            if w.palette.is_air(b) {
                                let state = w.palette.with(vine, face, "true")?;
                                w.set(q, state);
                                out.push(q);
                                if hanging {
                                    for _ in 0..4 {
                                        q[1] -= 1;
                                        let b = w.get(q);
                                        if !w.palette.is_air(b) {
                                            break;
                                        }
                                        w.set(q, state);
                                        out.push(q);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Self::Cocoa(chance) => {
                if r.next_float() >= *chance {
                    return Ok(());
                }
                let Some(first) = logs.first() else {
                    return Err("cocoa decorator requires logs".into());
                };
                let cocoa = w.palette.named("minecraft:cocoa")?;
                for &p in logs.iter().filter(|p| p[1] - first[1] <= 2) {
                    for (dx, dz, face) in [
                        (0, 1, "north"),
                        (-1, 0, "east"),
                        (0, -1, "south"),
                        (1, 0, "west"),
                    ] {
                        if r.next_float() <= 0.25 {
                            let q = offset(p, dx, 0, dz);
                            let b = w.get(q);
                            if w.palette.is_air(b) {
                                let state =
                                    w.palette
                                        .with(cocoa, "age", &r.next_bounded(3).to_string())?;
                                let state = w.palette.with(state, "facing", face)?;
                                w.set(q, state);
                                out.push(q);
                            }
                        }
                    }
                }
            }
            Self::Ground(provider) => {
                let mut list = vec![];
                if roots.is_empty() {
                    list.extend_from_slice(logs);
                } else if !logs.is_empty() && logs[0][1] == roots[0][1] {
                    list.extend_from_slice(logs);
                    list.extend_from_slice(roots);
                } else {
                    list.extend_from_slice(roots);
                }
                if let Some(&first) = list.first() {
                    for p in list.into_iter().filter(|p| p[1] == first[1]) {
                        for (x, z) in [(-1, -1), (2, -1), (-1, 2), (2, 2)] {
                            circle(w, r, offset(p, x, 0, z), provider, out)?;
                        }
                        for _ in 0..5 {
                            let k = r.next_bounded(64);
                            let x = k % 8;
                            let z = k / 8;
                            if x == 0 || x == 7 || z == 0 || z == 7 {
                                circle(w, r, offset(p, x - 3, 0, z - 3), provider, out)?;
                            }
                        }
                    }
                }
            }
            Self::Attached {
                chance,
                xz,
                y,
                provider,
                empty,
                directions,
            } => {
                let mut excluded = HashSet::new();
                let mut list = leaves.to_vec();
                // Util.shuffle uses the supplied WorldgenRandom, unlike BeehiveDecorator.
                for i in (2..=list.len()).rev() {
                    let j = r.next_bounded(i as i32) as usize;
                    list.swap(i - 1, j);
                }
                for p in list {
                    let d = directions[r.next_bounded(directions.len() as i32) as usize];
                    let q = offset(p, d[0], d[1], d[2]);
                    if excluded.contains(&q) || r.next_float() >= *chance {
                        continue;
                    }
                    let mut clear = true;
                    for i in 1..=*empty {
                        let b = w.get(offset(p, d[0] * i, d[1] * i, d[2] * i));
                        if !w.palette.is_air(b) {
                            clear = false;
                            break;
                        }
                    }
                    if clear {
                        for dx in -*xz..=*xz {
                            for dy in -*y..=*y {
                                for dz in -*xz..=*xz {
                                    excluded.insert(offset(q, dx, dy, dz));
                                }
                            }
                        }
                        let state = provider.state(r, q, &mut w.palette)?;
                        w.set(q, state);
                        out.push(q);
                    }
                }
            }
        }
        Ok(())
    }
}
fn circle(
    w: &mut Volume,
    r: &mut Random,
    p: Pos,
    provider: &StateProvider,
    out: &mut Vec<Pos>,
) -> Result<()> {
    for dx in -2i32..=2 {
        for dz in -2i32..=2 {
            if dx.abs() == 2 && dz.abs() == 2 {
                continue;
            }
            let base = offset(p, dx, 0, dz);
            for y in (-3..=2).rev() {
                let q = offset(base, 0, y, 0);
                let b = w.get(q);
                if w.palette.tag(b, "minecraft:dirt") {
                    let state = provider.state(r, base, &mut w.palette)?;
                    w.set(q, state);
                    out.push(q);
                    break;
                }
                if !w.palette.is_air(b) && y < 0 {
                    break;
                }
            }
        }
    }
    Ok(())
}
pub(super) fn direction(name: &str) -> Result<Pos> {
    Ok(match name {
        "down" => [0, -1, 0],
        "up" => [0, 1, 0],
        "north" => [0, 0, -1],
        "south" => [0, 0, 1],
        "west" => [-1, 0, 0],
        "east" => [1, 0, 0],
        _ => return Err(format!("unknown direction {name}")),
    })
}
pub(super) fn sorted_positions(positions: &[Pos]) -> Vec<Pos> {
    let mut set = JavaPosSet::new();
    for &p in positions {
        set.insert(p);
    }
    let mut result: Vec<_> = set.buckets.into_iter().flatten().collect();
    result.sort_by_key(|p| p[1]);
    result
}
