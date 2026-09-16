//! Material evaluation on a retained exterior column, with explicit neighbour
//! heights. No 3D volume is needed for ordinary surface rules.
use super::*;

pub(crate) struct ExteriorColumn {
    pub bottom: i32,
    pub blocks: Vec<StateId>, // ascending Y
    pub top: i32,             // first air above the original non-air column
}
impl ExteriorColumn {
    pub fn get(&self, y: i32, air: StateId) -> StateId {
        y.checked_sub(self.bottom)
            .and_then(|i| usize::try_from(i).ok())
            .and_then(|i| self.blocks.get(i))
            .copied()
            .unwrap_or(air)
    }
}
impl Surface {
    pub(crate) fn apply_exterior<'b>(
        &self,
        job: &mut Job<'_>,
        column: &mut ExteriorColumn,
        palette: &Palette,
        x: i32,
        z: i32,
        neighbours: [i32; 4],
        colors: &ClimateColors,
        mut biome: impl FnMut(Pos) -> Result<(&'b str, &'b Biome)>,
    ) -> Result<()> {
        let graph = &job.terrain.graph;
        let depth = self.depth(graph, x, z);
        let secondary = graph.noise(self.secondary, x as f64, 0., z as f64);
        let min_surface = self.min_surface(job, x, z, depth);
        let [north, south, west, east] = neighbours;
        let steep = south >= north + 4 || west >= east + 4;
        let mut above = 0;
        let mut water = i32::MIN;
        let mut floor = i32::MAX;
        let mut output_floor = None;
        for y in (column.bottom..column.top).rev() {
            let state = column.get(y, palette.air);
            if palette.is_air(state) {
                above = 0;
                water = i32::MIN;
            } else if palette.fluid(state) {
                if water == i32::MIN {
                    water = y + 1;
                }
            } else {
                if floor >= y {
                    floor = column.bottom;
                    for yy in (column.bottom..y).rev() {
                        if !palette.stone(column.get(yy, palette.air)) {
                            floor = yy + 1;
                            break;
                        }
                    }
                }
                above += 1;
                if state == self.default {
                    let p = [x, y, z];
                    let (name, data) = biome(p)?;
                    let c = Context {
                        p,
                        biome: name,
                        cold: colors.temperature(data, p) < 0.15,
                        steep,
                        depth,
                        secondary,
                        above,
                        below: y - floor + 1,
                        water,
                        min_surface,
                    };
                    if let Some(id) = self.rule(&self.rule, graph, &c) {
                        column.blocks[(y - column.bottom) as usize] = id;
                    }
                }
            }
            // Rules above have already committed the actual block, including
            // air/water replacements. Once seven layers below the first final
            // solid are known, deeper surface writes cannot affect the record.
            let result = column.get(y, palette.air);
            if output_floor.is_none() && !palette.is_air(result) && !palette.fluid(result) {
                output_floor = Some(y + 1);
            }
            if output_floor.is_some_and(|top| y <= top - 7) {
                break;
            }
        }
        Ok(())
    }
}
