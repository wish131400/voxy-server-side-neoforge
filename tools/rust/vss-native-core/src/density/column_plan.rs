//! Compiled dependency plan for surface queries. Only expressions proven
//! independent of Y are shared vertically; cache modes remain distinct.
use super::{Binary, Graph, Marker, Mode, Node, Spline, Unary};

pub(super) const NONE: usize = usize::MAX;
pub(super) struct ColumnPlan {
    pub slots: Vec<usize>,
    pub count: usize,
    pub bounds: Vec<(f64, f64)>,
    pub height: Option<super::height_plan::HeightPlan>,
}
impl ColumnPlan {
    pub fn empty() -> Self {
        Self {
            slots: vec![],
            count: 0,
            bounds: vec![],
            height: None,
        }
    }
    pub fn compile(g: &Graph) -> Self {
        fn spline_y(s: &Spline, d: &[bool]) -> bool {
            match s {
                Spline::Constant(_) => false,
                Spline::Multipoint { coordinate, points } => {
                    d[*coordinate] || points.iter().any(|(_, s, _)| spline_y(s, d))
                }
            }
        }
        let mut dependent = Vec::with_capacity(g.nodes.len());
        let mut slots = Vec::with_capacity(g.nodes.len());
        let mut count = 0;
        for node in &g.nodes {
            let y = match node {
                Node::Constant(_) | Node::End(_) | Node::FtfNoise(_) => false,
                Node::Axis(a) => *a == 1,
                Node::Gradient(_, _, a, b) => a != b,
                Node::Noise(_, _, scale) => *scale != 0.,
                Node::Shift(_, kind) => *kind == 0,
                Node::Shifted(_, _, scale, shifts) | Node::FastNoise(_, _, scale, shifts) => {
                    *scale != 0. || shifts.iter().any(|n| dependent[*n])
                }
                Node::Unary(_, n) | Node::Clamp(n, _, _) | Node::FtfUnit(n, _) => dependent[*n],
                Node::Binary(_, a, b) => dependent[*a] || dependent[*b],
                Node::Range(a, _, _, b, c) | Node::Mix(a, b, c) => {
                    dependent[*a] || dependent[*b] || dependent[*c]
                }
                Node::Select(a, b, choices) => {
                    dependent[*a] || dependent[*b] || choices.iter().any(|(_, _, n)| dependent[*n])
                }
                Node::LinearSpline(n, points) => {
                    dependent[*n] || points.iter().any(|(_, n)| dependent[*n])
                }
                Node::Spline(s) => spline_y(s, &dependent),
                // Even Flat must inherit Y here: outside its covered region,
                // and in Raw mode, it evaluates its child at the input Y.
                Node::Marker(Marker::Interpolated(_), _) => true,
                Node::Marker(_, n) => dependent[*n],
                // Transforms can introduce vertical dependencies in coordinates.
                Node::CoordinateShift(..) | Node::Beard | Node::Blended(_) | Node::Weird(..) => {
                    true
                }
            };
            dependent.push(y);
            let worth_caching =
                !matches!(node, Node::Constant(_) | Node::Axis(_) | Node::Gradient(..));
            if !g.exposed_column_order && !y && worth_caching {
                slots.push(count);
                count += 1;
            } else {
                slots.push(NONE);
            }
        }
        let mut bounds: Vec<(f64, f64)> = Vec::with_capacity(g.nodes.len());
        let unknown = (f64::NEG_INFINITY, f64::INFINITY);
        fn spline_bound(s: &Spline, bounds: &[(f64, f64)]) -> Option<f64> {
            match s {
                Spline::Constant(v) => v.is_finite().then_some((*v as f64).abs()),
                Spline::Multipoint { coordinate, points } => {
                    let (lo,hi)=bounds[*coordinate];
                    if !lo.is_finite() || !hi.is_finite() || points.is_empty()
                        || points.windows(2).any(|p| p[0].0 > p[1].0) { return None; }
                    let mut magnitude=lo.abs().max(hi.abs());
                    let (mut value,mut derivative)=(0f64,0f64);
                    for (x,s,d) in points {
                        if !x.is_finite() || !d.is_finite() { return None; }
                        magnitude=magnitude.max((*x as f64).abs());
                        value=value.max(spline_bound(s,bounds)?);
                        derivative=derivative.max((*d as f64).abs());
                    }
                    // Covers endpoint extrapolation and every Hermite term,
                    // with generous f32 rounding headroom. Tiny knot gaps can
                    // underflow their subtraction; leave those graphs unknown.
                    // Equal knots are safe: the upper-bound search selects the
                    // last <= coordinate and the first > coordinate, so an
                    // interpolation interval never has two equal endpoints.
                    if points.windows(2).any(|p| {
                        let gap=p[1].0-p[0].0;
                        gap>0. && gap<f32::MIN_POSITIVE
                    }) { return None; }
                    let bound=16.*(1.+value+derivative*magnitude);
                    (bound < f32::MAX as f64 / 16. && magnitude < f32::MAX as f64 / 16.)
                        .then_some(bound)
                }
            }
        }
        for node in &g.nodes {
            let value = match node {
                Node::Constant(v) => (*v, *v),
                Node::Gradient(_, _, a, b) => (a.min(*b), a.max(*b)),
                Node::Noise(n, xz, y) if xz.is_finite() && y.is_finite()
                    && xz.abs()<1e100 && y.abs()<1e100 => {
                    let v = g.noises[*n].absolute_bound();
                    (-v, v)
                }
                Node::Shifted(n, xz, y, shifts) if xz.is_finite() && y.is_finite()
                    && xz.abs()<1e100 && y.abs()<1e100
                    && shifts.iter().all(|id| bounds[*id].0.abs().max(bounds[*id].1.abs())<1e100) => {
                    let v = g.noises[*n].absolute_bound();
                    (-v, v)
                }
                Node::Shift(n, _) => {
                    let v = g.noises[*n].absolute_bound() * 4.;
                    (-v, v)
                }
                Node::Blended(n) => (n.min_value(), n.max_value()),
                Node::Weird(n, _, two) => {
                    let v=g.noises[*n].absolute_bound()*if *two {3.} else {2.};
                    (0.,v)
                }
                Node::Spline(s) => spline_bound(s,&bounds).map_or(unknown,|v|(-v,v)),
                Node::Marker(_, n) => bounds[*n],
                Node::Clamp(n, a, b) if bounds[*n].0.is_finite() && bounds[*n].1.is_finite() => {
                    let (l, h) = bounds[*n];
                    (l.clamp(*a, *b), h.clamp(*a, *b))
                }
                Node::Unary(op, n) if bounds[*n].0.is_finite() && bounds[*n].1.is_finite() => {
                    let (l, h) = bounds[*n];
                    match op {
                        Unary::Abs => (
                            if l <= 0. && h >= 0. {
                                0.
                            } else {
                                l.abs().min(h.abs())
                            },
                            l.abs().max(h.abs()),
                        ),
                        Unary::Square => (
                            if l <= 0. && h >= 0. {
                                0.
                            } else {
                                (l * l).min(h * h)
                            },
                            (l * l).max(h * h),
                        ),
                        Unary::Cube => (l * l * l, h * h * h),
                        Unary::Half => (
                            if l > 0. { l } else { l * 0.5 },
                            if h > 0. { h } else { h * 0.5 },
                        ),
                        Unary::Quarter => (
                            if l > 0. { l } else { l * 0.25 },
                            if h > 0. { h } else { h * 0.25 },
                        ),
                        Unary::Squeeze => (-0.458333333333334, 0.458333333333334),
                        Unary::Reciprocal if l>0. || h<0. => (1./h,1./l),
                        _ => unknown,
                    }
                }
                Node::Binary(op, a, b)
                    if [bounds[*a].0, bounds[*a].1, bounds[*b].0, bounds[*b].1]
                        .iter()
                        .all(|v| v.is_finite()) =>
                {
                    let (l, h) = bounds[*a];
                    let (a, b) = bounds[*b];
                    match op {
                        Binary::Add => (l + a, h + b),
                        Binary::Min => (l.min(a), h.min(b)),
                        Binary::Max => (l.max(a), h.max(b)),
                        Binary::Mul => {
                            let v = [l * a, l * b, h * a, h * b];
                            if v.iter().any(|v| v.is_nan()) {
                                unknown
                            } else {
                                (
                                    v.into_iter().fold(f64::INFINITY, f64::min),
                                    v.into_iter().fold(f64::NEG_INFINITY, f64::max),
                                )
                            }
                        }
                    }
                }
                Node::Range(_, _, _, a, b)
                    if [bounds[*a].0, bounds[*a].1, bounds[*b].0, bounds[*b].1]
                        .iter()
                        .all(|v| v.is_finite()) =>
                {
                    let (l, h) = bounds[*a];
                    let (a, b) = bounds[*b];
                    (l.min(a), h.max(b))
                }
                _ => unknown,
            };
            let pad = |v: f64| {
                if v.is_finite() {
                    (1. + v.abs()) * 1e-12
                } else {
                    0.
                }
            };
            bounds.push(if value.0.is_nan() || value.1.is_nan() {
                unknown
            } else {
                (value.0 - pad(value.0), value.1 + pad(value.1))
            });
        }
        Self {
            height: super::height_plan::HeightPlan::compile(g, &slots),
            slots,
            count,
            bounds,
        }
    }
}

#[derive(Clone, Copy)]
struct Entry {
    x: i32,
    z: i32,
    mode: Mode,
    value: f64,
}
#[derive(Clone, Default)]
pub(super) struct HorizontalCache {
    entries: [Option<Entry>; 4],
    next: usize,
}
impl HorizontalCache {
    pub fn get(&self, x: i32, z: i32, mode: Mode) -> Option<f64> {
        self.entries
            .iter()
            .flatten()
            .find(|e| e.x == x && e.z == z && e.mode == mode)
            .map(|e| e.value)
    }
    pub fn put(&mut self, x: i32, z: i32, mode: Mode, value: f64) {
        self.entries[self.next] = Some(Entry { x, z, mode, value });
        self.next = (self.next + 1) & 3;
    }
}
