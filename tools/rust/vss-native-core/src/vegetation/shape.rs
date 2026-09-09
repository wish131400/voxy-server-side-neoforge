//! StructureTemplate.updateShapeAtEdge over the generated tree's filled shape.
//! Tick-only updates have no effect in the prediction world's blackhole tick lists.
use super::*;
const DIR: [Pos; 6] = [
    [0, -1, 0],
    [0, 1, 0],
    [0, 0, -1],
    [0, 0, 1],
    [-1, 0, 0],
    [1, 0, 0],
];
pub(super) fn update_edges(
    w: &mut Volume,
    filled: &HashSet<Pos>,
    min: Pos,
    max: Pos,
) -> Result<()> {
    for order in [[0, 1, 2], [2, 0, 1], [1, 2, 0]] {
        let [a, b, c] = order;
        let negative = match c {
            0 => 4,
            1 => 0,
            _ => 2,
        };
        for i in min[a]..=max[a] {
            for j in min[b]..=max[b] {
                let mut previous = false;
                for k in min[c]..=max[c] + 1 {
                    let mut p = [0; 3];
                    p[a] = i;
                    p[b] = j;
                    p[c] = k;
                    let current = k <= max[c] && filled.contains(&p);
                    if !previous && current {
                        face(w, p, negative)?;
                    }
                    if previous && !current {
                        p[c] -= 1;
                        face(w, p, negative + 1)?;
                    }
                    previous = current;
                }
            }
        }
    }
    Ok(())
}
fn face(w: &mut Volume, p: Pos, d: usize) -> Result<()> {
    let q = offset(p, DIR[d][0], DIR[d][1], DIR[d][2]);
    let a = w.get(p);
    let b = w.get(q);
    let changed = update(w, p, a, b, d)?;
    if a != changed {
        w.set(p, changed);
    }
    let neighbor = update(w, q, b, changed, d ^ 1)?;
    if b != neighbor {
        w.set(q, neighbor);
    }
    Ok(())
}
fn update(
    w: &mut Volume,
    p: Pos,
    mut state: StateId,
    neighbor: StateId,
    d: usize,
) -> Result<StateId> {
    match w.palette.definition(state).shape_update.as_str() {
        "none" => {}
        "vine" if d != 0 => {
            for (key, face) in [
                ("up", 1),
                ("north", 2),
                ("east", 5),
                ("south", 3),
                ("west", 4),
            ] {
                if w.palette
                    .state(state)
                    .properties
                    .get(key)
                    .is_some_and(|v| v == "true")
                {
                    let block = w.get(offset(p, DIR[face][0], DIR[face][1], DIR[face][2]));
                    let mut keep = w
                        .palette
                        .supports_face(block, if face == 1 { 0 } else { face })?;
                    if !keep && face != 1 {
                        let above = w.get(offset(p, 0, 1, 0));
                        keep = w.palette.is(above, "minecraft:vine")
                            && w.palette
                                .state(above)
                                .properties
                                .get(key)
                                .is_some_and(|v| v == "true");
                    }
                    if !keep {
                        state = w.palette.with(state, key, "false")?;
                    }
                }
            }
            if !w
                .palette
                .state(state)
                .properties
                .values()
                .any(|v| v == "true")
            {
                return Ok(w.palette.air);
            }
        }
        "vine" => {}
        "snowy" if d == 1 => {
            let snowy = w.palette.is(neighbor, "minecraft:snow_block")
                || w.palette.is(neighbor, "minecraft:snow");
            state = w
                .palette
                .with(state, "snowy", if snowy { "true" } else { "false" })?;
        }
        "snowy" => {}
        "cocoa" => {
            let facing = w
                .palette
                .state(state)
                .properties
                .get("facing")
                .cloned()
                .ok_or("cocoa facing")?;
            let dir = decorators::direction(&facing)?;
            let support = w.get(offset(p, dir[0], dir[1], dir[2]));
            if DIR[d] == dir && !w.palette.tag(support, "minecraft:jungle_logs") {
                return Ok(w.palette.air);
            }
        }
        "propagule" | "bush" => {
            if !super::survives(w, p, state)? {
                return Ok(w.palette.air);
            }
        }
        "carpet" => {
            let below = w.get(offset(p, 0, -1, 0));
            if w.palette.is_air(below) {
                return Ok(w.palette.air);
            }
        }
        "huge_mushroom" => {
            if w.palette.state(state).name == w.palette.state(neighbor).name {
                state = w.palette.with(
                    state,
                    ["down", "up", "north", "south", "west", "east"][d],
                    "false",
                )?;
            }
        }
        "double_plant" => {
            let lower = w
                .palette
                .state(state)
                .properties
                .get("half")
                .is_some_and(|v| v == "lower");
            if d < 2 && lower == (d == 1) {
                if w.palette.state(state).name != w.palette.state(neighbor).name
                    || w.palette.state(state).properties.get("half")
                        == w.palette.state(neighbor).properties.get("half")
                {
                    return Ok(w.palette.air);
                }
            }
            // The successful pairing branch also delegates to BushBlock.updateShape.
            if !super::survives(w, p, state)? {
                return Ok(w.palette.air);
            }
        }
        _ => {
            return Err(format!(
                "block neighbour update needs compatibility provider: {}",
                w.palette.state(state).name
            ))
        }
    }
    Ok(state)
}
