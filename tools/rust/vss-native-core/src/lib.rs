//! VSS native kernels.
//!
//! The public ABI deliberately contains no Minecraft types. Java remains the
//! owner of registries, resource-pack models and worldgen objects; this crate
//! provides the source-built terrain, density, aquifer, surface, biome and
//! vegetation pipeline through JNI ABI 3, alongside the noise-only ABI 1 probe
//! for validation. World state is leased across calls and results
//! use bounded native volumes or caller-owned direct buffers.

#![deny(unsafe_op_in_unsafe_fn)]

pub mod backend;
pub mod beard;
pub mod biome;
pub mod blended_noise;
pub mod blocks;
pub mod climate;
pub mod decoration;
pub mod density;
pub mod freeterraforged_filters;
pub mod freeterraforged_noise;
mod jni_noise;
mod jni_worldgen;
pub mod lithostitched;
pub mod noise;
pub mod providers;
pub mod random;
pub mod surface;
pub mod terrain;
pub mod vegetation;
