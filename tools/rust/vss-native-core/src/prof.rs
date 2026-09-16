//! Offline profiling counters for the surface cold path.
//!
//! Everything here is inert unless the `profiling` feature is on: [`Scope`]
//! becomes a zero-sized no-op and no counter is ever written, so the shipping
//! library keeps its original code shape. Offline benchmarks read the totals
//! through [`snapshot`].
//!
//! Two caveats matter when reading the numbers:
//!
//! * these are wall-clock totals, not CPU time;
//! * they are process-wide, so concurrent generations from another lease on the
//!   same process would land in the same totals. The benchmarks drive one world
//!   from one thread, which is the intended reading.

use std::sync::atomic::{AtomicU64, Ordering};

/// Per-section nanosecond totals for the surface cold path.
pub struct Counters {
    /// Cold columns that entered `surface_point` or `surface_points` (cache
    /// misses only; a hit returns before any scope starts).
    pub columns: AtomicU64,
    /// `Terrain::job` plus `prepare_exterior_columns`.
    pub column_setup: AtomicU64,
    /// `Volume::new` for the `3 x height x 3` scratch volume.
    pub volume_new: AtomicU64,
    /// `Graph::scratch` for the column.
    pub scratch: AtomicU64,
    /// `Surface::exterior_padding` for the centre column.
    pub exterior_padding: AtomicU64,
    /// First pass: neighbour heights plus the centre padding march. Contains
    /// `neighbour_column` and `centre_march`, which are reported separately.
    pub neighbour_fill: AtomicU64,
    /// The centre column's padding march (`height` calls to `Job::block`).
    /// Nested inside `neighbour_fill`.
    pub centre_march: AtomicU64,
    /// `Job::block` inside the march. Nested inside `centre_march`.
    pub centre_block: AtomicU64,
    /// `Volume::set` inside the march. Nested inside `centre_march`.
    pub centre_set: AtomicU64,
    /// March iterations, i.e. the number of `Job::block` calls. A count, not a
    /// duration.
    pub centre_steps: AtomicU64,
    /// `Job::surface_top` for the four neighbours. Nested inside
    /// `neighbour_fill`.
    pub neighbour_top: AtomicU64,
    /// `Job::column` for a neighbour that needs real geometry. Nested inside
    /// `neighbour_fill`.
    pub neighbour_column: AtomicU64,
    /// Second pass: `Surface::geometry_column` on earlier neighbours.
    pub neighbour_geometry: AtomicU64,
    /// `Surface::apply_column`.
    pub apply_rules: AtomicU64,
    /// `column_record`: final column walk, biome lookup and colours.
    pub record: AtomicU64,
    /// Publishing into the exact point cache.
    pub cache_store: AtomicU64,
    /// Columns that fell back to a complete, untruncated rebuild.
    pub truncated_rebuilds: AtomicU64,
    /// `Graph::compute` calls indexed `[Raw, Single, Block, Cell, Slice]`. The
    /// counter itself is an atomic add, so a column making tens of thousands of
    /// calls inflates its own section; read these as ratios.
    pub evals: [AtomicU64; 5],
    /// `Job::block` calls, i.e. how many full per-block density evaluations a
    /// column actually pays for. A count, not a duration.
    pub block_calls: AtomicU64,
    /// `Job::fluid` calls, i.e. fluid lookups that missed the `status` cache
    /// and paid for the 13-offset preliminary march. A count.
    pub fluid_calls: AtomicU64,
    /// `Terrain::preliminary` calls, including cache hits. A count.
    pub preliminary_calls: AtomicU64,
    /// `Job::block` internals. These cover every `block` call in a column, not
    /// just the centre march, and four `Instant::now` per call inflate them.
    pub block_prepare: AtomicU64,
    /// `final_cell_value` / `compute(final_density, Cell)` plus the beardifier.
    pub block_density: AtomicU64,
    /// `Job::substance`, including the aquifer branch.
    pub block_substance: AtomicU64,
    /// `Job::ore` for a solid block with ores enabled.
    pub block_ore: AtomicU64,
    /// `Job::surface_top` calls that had to scan a column from the top.
    pub top_scans: AtomicU64,
    /// `Job::surface_top` calls answered from the neighbour top cache.
    pub top_reuses: AtomicU64,
    /// `Graph::compute` calls that landed on a stateless noise leaf
    /// (`Noise` / `Blended` / `End` / `FastNoise` / `FtfNoise`). These are the
    /// only nodes a GPU could take over without carrying per-call state, so
    /// their share of `evals` is the ceiling for an offload of that kind.
    pub noise_evals: AtomicU64,
    /// Wall time inside those stateless noise leaves. Two `Instant::now` per
    /// leaf inflate this, so read it as a share of `block_density` rather than
    /// as an absolute cost.
    pub noise_time: AtomicU64,
}

impl Counters {
    const fn new() -> Self {
        Self {
            columns: AtomicU64::new(0),
            column_setup: AtomicU64::new(0),
            volume_new: AtomicU64::new(0),
            scratch: AtomicU64::new(0),
            exterior_padding: AtomicU64::new(0),
            neighbour_fill: AtomicU64::new(0),
            centre_march: AtomicU64::new(0),
            centre_block: AtomicU64::new(0),
            centre_set: AtomicU64::new(0),
            centre_steps: AtomicU64::new(0),
            neighbour_top: AtomicU64::new(0),
            neighbour_column: AtomicU64::new(0),
            neighbour_geometry: AtomicU64::new(0),
            apply_rules: AtomicU64::new(0),
            record: AtomicU64::new(0),
            cache_store: AtomicU64::new(0),
            truncated_rebuilds: AtomicU64::new(0),
            evals: [
                AtomicU64::new(0),
                AtomicU64::new(0),
                AtomicU64::new(0),
                AtomicU64::new(0),
                AtomicU64::new(0),
            ],
            block_calls: AtomicU64::new(0),
            fluid_calls: AtomicU64::new(0),
            preliminary_calls: AtomicU64::new(0),
            block_prepare: AtomicU64::new(0),
            block_density: AtomicU64::new(0),
            block_substance: AtomicU64::new(0),
            block_ore: AtomicU64::new(0),
            top_scans: AtomicU64::new(0),
            top_reuses: AtomicU64::new(0),
            noise_evals: AtomicU64::new(0),
            noise_time: AtomicU64::new(0),
        }
    }
    /// Section totals in reporting order. Names with a leading two-space
    /// indent are nested inside the section above them.
    fn sections(&self) -> [(&'static str, &AtomicU64); 14] {
        [
            ("column_setup", &self.column_setup),
            ("volume_new", &self.volume_new),
            ("scratch", &self.scratch),
            ("exterior_padding", &self.exterior_padding),
            ("neighbour_fill", &self.neighbour_fill),
            ("  centre_march", &self.centre_march),
            ("    centre_block", &self.centre_block),
            ("    centre_set", &self.centre_set),
            ("  neighbour_top", &self.neighbour_top),
            ("  neighbour_column", &self.neighbour_column),
            ("neighbour_geometry", &self.neighbour_geometry),
            ("apply_rules", &self.apply_rules),
            ("record", &self.record),
            ("cache_store", &self.cache_store),
        ]
    }
    fn block_sections(&self) -> [(&'static str, &AtomicU64); 4] {
        [
            ("prepare_cell", &self.block_prepare),
            ("density value", &self.block_density),
            ("substance", &self.block_substance),
            ("ore", &self.block_ore),
        ]
    }
}

/// Process-wide totals. Offline benchmarks are the intended reader.
pub static SURFACE: Counters = Counters::new();

/// Clear every section total and both event counters.
pub fn reset() {
    for (_, slot) in SURFACE.sections() {
        slot.store(0, Ordering::Relaxed);
    }
    SURFACE.columns.store(0, Ordering::Relaxed);
    SURFACE.truncated_rebuilds.store(0, Ordering::Relaxed);
    SURFACE.centre_steps.store(0, Ordering::Relaxed);
    for slot in &SURFACE.evals {
        slot.store(0, Ordering::Relaxed);
    }
    SURFACE.block_calls.store(0, Ordering::Relaxed);
    SURFACE.fluid_calls.store(0, Ordering::Relaxed);
    SURFACE.preliminary_calls.store(0, Ordering::Relaxed);
    SURFACE.top_scans.store(0, Ordering::Relaxed);
    SURFACE.top_reuses.store(0, Ordering::Relaxed);
    SURFACE.noise_evals.store(0, Ordering::Relaxed);
    SURFACE.noise_time.store(0, Ordering::Relaxed);
    for (_, slot) in SURFACE.block_sections() {
        slot.store(0, Ordering::Relaxed);
    }
}

/// `(section, nanoseconds)` in reporting order.
pub fn snapshot() -> Vec<(&'static str, u64)> {
    SURFACE
        .sections()
        .into_iter()
        .map(|(name, slot)| (name, slot.load(Ordering::Relaxed)))
        .collect()
}

/// Cold columns observed since the last [`reset`].
pub fn columns() -> u64 {
    SURFACE.columns.load(Ordering::Relaxed)
}

/// Untruncated rebuilds observed since the last [`reset`]. These are counted
/// separately from `columns` because they redo a whole column.
pub fn truncated_rebuilds() -> u64 {
    SURFACE.truncated_rebuilds.load(Ordering::Relaxed)
}

/// Centre-march iterations since the last [`reset`], i.e. `Job::block` calls.
pub fn centre_steps() -> u64 {
    SURFACE.centre_steps.load(Ordering::Relaxed)
}

/// `Graph::compute` calls per mode since the last [`reset`], indexed
/// `[Raw, Single, Block, Cell, Slice]`.
pub fn evals() -> [u64; 5] {
    std::array::from_fn(|i| SURFACE.evals[i].load(Ordering::Relaxed))
}

/// `Job::block` calls since the last [`reset`].
pub fn block_calls() -> u64 {
    SURFACE.block_calls.load(Ordering::Relaxed)
}

/// `Job::fluid` calls (status cache misses) since the last [`reset`].
pub fn fluid_calls() -> u64 {
    SURFACE.fluid_calls.load(Ordering::Relaxed)
}

/// `Terrain::preliminary` calls, including cache hits, since the last
/// [`reset`].
pub fn preliminary_calls() -> u64 {
    SURFACE.preliminary_calls.load(Ordering::Relaxed)
}

/// Stateless noise-leaf evaluations since the last [`reset`].
pub fn noise_evals() -> u64 {
    SURFACE.noise_evals.load(Ordering::Relaxed)
}

/// Wall time inside those noise leaves since the last [`reset`].
pub fn noise_time() -> u64 {
    SURFACE.noise_time.load(Ordering::Relaxed)
}

/// `(scans, reuses)` for `Job::surface_top` since the last [`reset`].
pub fn surface_top_counts() -> (u64, u64) {
    (
        SURFACE.top_scans.load(Ordering::Relaxed),
        SURFACE.top_reuses.load(Ordering::Relaxed),
    )
}

/// `(section, nanoseconds)` for the internals of every `Job::block` call. Four
/// `Instant::now` per call make these totals noticeably larger than the true
/// cost; read them as a breakdown, not as absolute values.
pub fn block_snapshot() -> Vec<(&'static str, u64)> {
    SURFACE
        .block_sections()
        .into_iter()
        .map(|(name, slot)| (name, slot.load(Ordering::Relaxed)))
        .collect()
}

/// Counts one `Graph::compute` call. Indexed by [`evals`] order.
#[cfg(feature = "profiling")]
pub fn hit_mode(index: usize) {
    SURFACE.evals[index].fetch_add(1, Ordering::Relaxed);
}

/// Adds this scope's wall time to `slot` when dropped, including on early
/// returns and panics. Without the `profiling` feature this is a zero-sized
/// no-op that `new` still accepts, so call sites are identical in both builds.
#[cfg(feature = "profiling")]
pub struct Scope {
    slot: &'static AtomicU64,
    start: std::time::Instant,
}

#[cfg(feature = "profiling")]
impl Scope {
    pub fn new(slot: &'static AtomicU64) -> Self {
        Self {
            slot,
            start: std::time::Instant::now(),
        }
    }
    /// Ends the scope at an explicit point without re-indenting the body under
    /// a temporary block. Dropping the scope later would still record the time,
    /// so a missed call over-attributes instead of losing the measurement.
    pub fn finish(self) {
        drop(self);
    }
}

#[cfg(feature = "profiling")]
impl Drop for Scope {
    fn drop(&mut self) {
        self.slot
            .fetch_add(self.start.elapsed().as_nanos() as u64, Ordering::Relaxed);
    }
}

/// No-op stand-in for the unprofiled build.
#[cfg(not(feature = "profiling"))]
pub struct Scope;

#[cfg(not(feature = "profiling"))]
impl Scope {
    #[inline(always)]
    pub fn new(_slot: &'static AtomicU64) -> Self {
        Scope
    }
    #[inline(always)]
    pub fn finish(self) {}
}

/// Counts one event into `slot`. A separate primitive from [`Scope`] so the
/// unprofiled build never touches an atomic on the hot path.
#[cfg(feature = "profiling")]
pub fn hit(slot: &AtomicU64) {
    slot.fetch_add(1, Ordering::Relaxed);
}

#[cfg(not(feature = "profiling"))]
#[inline(always)]
pub fn hit(_slot: &AtomicU64) {}
