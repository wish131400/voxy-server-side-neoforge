//! AbstractHugeMushroomFeature and its brown/red cap implementations.
use super::*;
pub struct Mushroom {
    red: bool,
    radius: i32,
    cap: StateProvider,
    stem: StateProvider,
}
impl Mushroom {
    pub(super) fn parse(v: &Value, red: bool, p: &mut Palette) -> Result<Self> {
        let radius = v["foliage_radius"].as_i64().unwrap_or(2);
        if !(1..=8).contains(&radius) {
            return Err("unsupported mushroom foliage radius".into());
        }
        Ok(Self {
            red,
            radius: radius as i32,
            cap: StateProvider::parse(&v["cap_provider"], p)?,
            stem: StateProvider::parse(&v["stem_provider"], p)?,
        })
    }
    pub(super) fn place(&self, w: &mut Volume, r: &mut Random, p: Pos) -> Result<bool> {
        let mut height = r.next_bounded(3) + 4;
        if r.next_bounded(12) == 0 {
            height *= 2;
        }
        if p[1] < w.origin[1] + 1 || p[1] + height + 1 >= w.origin[1] + w.size[1] as i32 {
            return Ok(false);
        }
        let below = w.get(offset(p, 0, -1, 0));
        if !w.palette.tag(below, "minecraft:dirt")
            && !w.palette.tag(below, "minecraft:mushroom_grow_block")
        {
            return Ok(false);
        }
        for y in 0..=height {
            // Vanilla passes -1 as both height arguments. For red mushrooms
            // this checks only the stem; do not substitute the cap radius.
            let radius = if !self.red && y > 3 { self.radius } else { 0 };
            for x in -radius..=radius {
                for z in -radius..=radius {
                    let b = w.get(offset(p, x, y, z));
                    if w.palette.definition(b).motion_blocking
                        && !w.palette.tag(b, "minecraft:leaves")
                    {
                        return Ok(false);
                    }
                }
            }
        }
        if self.red {
            for y in height - 3..=height {
                let radius = if y < height {
                    self.radius
                } else {
                    self.radius - 1
                };
                let side = self.radius - 2;
                for x in -radius..=radius {
                    for z in -radius..=radius {
                        if y < height
                            && ((x == -radius || x == radius) == (z == -radius || z == radius))
                        {
                            continue;
                        }
                        let q = offset(p, x, y, z);
                        let old = w.get(q);
                        if blocked(&w.palette, old) {
                            continue;
                        }
                        let mut state = self.cap.state(r, p, &mut w.palette)?;
                        let props = [
                            ("up", y >= height - 1),
                            ("west", x < -side),
                            ("east", x > side),
                            ("north", z < -side),
                            ("south", z > side),
                        ];
                        state = apply_flags(&mut w.palette, state, &props)?;
                        w.set(q, state);
                    }
                }
            }
        } else {
            let radius = self.radius;
            for x in -radius..=radius {
                for z in -radius..=radius {
                    let west = x == -radius;
                    let east = x == radius;
                    let north = z == -radius;
                    let south = z == radius;
                    let edge_x = west || east;
                    let edge_z = north || south;
                    if edge_x && edge_z {
                        continue;
                    }
                    let q = offset(p, x, height, z);
                    let old = w.get(q);
                    if blocked(&w.palette, old) {
                        continue;
                    }
                    let mut state = self.cap.state(r, p, &mut w.palette)?;
                    let props = [
                        ("west", west || edge_z && x == 1 - radius),
                        ("east", east || edge_z && x == radius - 1),
                        ("north", north || edge_x && z == 1 - radius),
                        ("south", south || edge_x && z == radius - 1),
                    ];
                    state = apply_flags(&mut w.palette, state, &props)?;
                    w.set(q, state);
                }
            }
        }
        for y in 0..height {
            let q = offset(p, 0, y, 0);
            let old = w.get(q);
            if !blocked(&w.palette, old) {
                let state = self.stem.state(r, p, &mut w.palette)?;
                w.set(q, state);
            }
        }
        Ok(true)
    }
}
fn apply_flags(p: &mut Palette, mut state: StateId, flags: &[(&str, bool)]) -> Result<StateId> {
    if flags
        .iter()
        .all(|(key, _)| p.state(state).properties.contains_key(*key))
    {
        for &(key, yes) in flags {
            state = p.with(state, key, if yes { "true" } else { "false" })?;
        }
    }
    Ok(state)
}

fn blocked(p: &Palette, id: StateId) -> bool {
    p.definition(id).motion_blocking && !p.tag(id, "minecraft:leaves")
}
