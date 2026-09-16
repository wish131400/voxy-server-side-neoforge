//! Prints the same probe points as `SimplexRef.java` so the two can be
//! compared bit-for-bit. Used to validate the `ac_simplex` port.
use vss_native_core::ac_simplex::noise3d;

fn main() {
    let points: [(f64, f64, f64); 8] = [
        (0.0, 0.0, 0.0),
        (1.0, 2.0, 3.0),
        (1.5, 2.5, 3.5),
        (-4.25, 7.75, -0.125),
        (100.5, -200.25, 50.0),
        (0.1, 0.2, 0.3),
        (-0.5, 0.5, -0.5),
        (1234.5678, -8765.4321, 0.001),
    ];
    for (x, y, z) in points {
        println!("NOISE {} {} {} {}", x, y, z, noise3d(x, y, z).to_bits());
    }
}
