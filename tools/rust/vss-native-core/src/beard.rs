//! Structure terrain adaptation. Structure starts supply rigid pieces/junctions;
//! these contributions are evaluated natively before aquifer and ore selection.
use crate::{
    blocks::Pos,
    density::{integer, string, Result},
};
use serde_json::Value;
use std::sync::OnceLock;
enum Adjustment {
    None,
    Bury,
    Thin,
    Box,
    Encapsulate,
}
struct Piece {
    bounds: [i32; 6],
    ground: i32,
    kind: Adjustment,
}
pub struct Beard {
    pieces: Vec<Piece>,
    junctions: Vec<Pos>,
}
impl Beard {
    pub fn parse(value: &Value) -> Result<Self> {
        let mut pieces = vec![];
        for v in value["pieces"].as_array().ok_or("missing rigid pieces")? {
            let bounds: [i32; 6] =
                serde_json::from_value(v["box"].clone()).map_err(|_| "invalid structure box")?;
            if (0..3).any(|i| bounds[i] > bounds[i + 3])
                || bounds.iter().any(|v| v.abs_diff(0) > 30_000_000)
            {
                return Err("invalid structure bounds".into());
            }
            let kind = match string(v, "adjustment")? {
                "none" => Adjustment::None,
                "bury" => Adjustment::Bury,
                "beard_thin" => Adjustment::Thin,
                "beard_box" => Adjustment::Box,
                "encapsulate" => Adjustment::Encapsulate,
                _ => return Err("unsupported structure terrain adjustment".into()),
            };
            let ground = integer(v, "ground_delta")?;
            if ground.abs_diff(0) > 4096 {
                return Err("structure ground delta range".into());
            }
            pieces.push(Piece {
                bounds,
                ground,
                kind,
            });
        }
        let junctions: Vec<Pos> =
            serde_json::from_value(value["junctions"].clone()).map_err(|_| "invalid junctions")?;
        if pieces.len() > 4096
            || junctions.len() > 16384
            || junctions
                .iter()
                .flatten()
                .any(|v| v.abs_diff(0) > 30_000_000)
        {
            return Err("structure adjustment budget".into());
        }
        Ok(Self { pieces, junctions })
    }
    pub fn compute(&self, p: Pos) -> f64 {
        let mut result = 0.;
        for piece in &self.pieces {
            let b = piece.bounds;
            let x = 0.max(b[0] - p[0]).max(p[0] - b[3]);
            let z = 0.max(b[2] - p[2]).max(p[2] - b[5]);
            let base = b[1] + piece.ground;
            let delta = p[1] - base;
            let y = match piece.kind {
                Adjustment::None => 0,
                Adjustment::Bury | Adjustment::Thin => delta,
                Adjustment::Box => 0.max(base - p[1]).max(p[1] - b[4]),
                Adjustment::Encapsulate => 0.max(b[1] - p[1]).max(p[1] - b[4]),
            };
            result += match piece.kind {
                Adjustment::None => 0.,
                Adjustment::Bury => bury(x as f64, y as f64 / 2., z as f64),
                Adjustment::Thin | Adjustment::Box => beard(x, y, z, delta) * 0.8,
                Adjustment::Encapsulate => bury(x as f64 / 2., y as f64 / 2., z as f64 / 2.) * 0.8,
            };
        }
        for q in &self.junctions {
            result += beard(p[0] - q[0], p[1] - q[1], p[2] - q[2], p[1] - q[1]) * 0.4;
        }
        result
    }
}
fn bury(x: f64, y: f64, z: f64) -> f64 {
    let t = ((x * x + y * y + z * z).sqrt() / 6.).clamp(0., 1.);
    1. + t * (-1.)
}
fn beard(x: i32, y: i32, z: i32, delta: i32) -> f64 {
    if !(-12..12).contains(&x) || !(-12..12).contains(&y) || !(-12..12).contains(&z) {
        return 0.;
    }
    static KERNEL: OnceLock<Vec<f32>> = OnceLock::new();
    let table = KERNEL.get_or_init(|| {
        let mut v = vec![];
        for z in -12..12 {
            for x in -12..12 {
                for y in -12..12 {
                    let yy = y as f64 + 0.5;
                    v.push(
                        std::f64::consts::E.powf(-((x * x) as f64 + yy * yy + (z * z) as f64) / 16.)
                            as f32,
                    );
                }
            }
        }
        v
    });
    let d = delta as f64 + 0.5;
    let length = ((x * x) as f64 + d * d + (z * z) as f64) / 2.;
    let half = 0.5 * length;
    let approx = f64::from_bits((6910469410427058090i64 - ((length.to_bits() as i64) >> 1)) as u64);
    let inverse = approx * (1.5 - half * approx * approx);
    -d * inverse / 2. * table[((z + 12) * 24 * 24 + (x + 12) * 24 + y + 12) as usize] as f64
}
