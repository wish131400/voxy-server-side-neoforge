# Third-party licenses

## VSS source-built native core

`vss_native_core.dll`, `libvss_native_core.so` and `libvss_native_core.dylib` are built from the source-controlled crate at
`tools/rust/vss-native-core`. Its production worldgen interface is JNI ABI 2;
the independent noise verification probe uses ABI 1. Minecraft method mapping
and directly executed oracles are documented in `tools/rust/README.md` and
`tools/rust/VANILLA_PIPELINE.md`.

The crate uses `jni`, `md-5`, `serde_json`, `fastnoise-lite` and their dependencies at the exact
versions recorded in `Cargo.lock`. Dependency license declarations and notices
are supplied by the corresponding crates. Source-built binaries are currently
packaged for Windows x86_64, Linux x86_64/aarch64 and macOS x86_64/aarch64.
Cross compilation uses Zig 0.13.0 (MIT) and cargo-zigbuild 0.23.4 (MIT).
Linux builds include compiler-rt (Apache-2.0 WITH LLVM-exception) and the
Rust standard library (MIT OR Apache-2.0). Build tools and test fixtures are not packaged.

## FastNoise Lite 1.1.1

https://github.com/Auburn/FastNoiseLite

MIT License

Copyright (c) 2023 Jordan Peck (jordan.me2@gmail.com)
Copyright (c) 2023 Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## FreeTerraForged / ReTerraForged computation port

https://github.com/ETcodehome/FreeTerraForged

The native tile filters, noise modules and density operators derive from
ReTerraForged's MIT-licensed sources and preserve their numerical operations.

MIT License

Copyright (c) 2023 ReTerraForged

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
