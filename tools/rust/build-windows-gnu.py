"""Linux-hosted Windows GNU JNI build with explicit, audited exports (Zig 0.13)."""
import os
import pathlib
import re
import subprocess

root = pathlib.Path(__file__).resolve().parents[2]
crate = root / 'tools/rust/vss-native-core'
work = root / 'build/windows-gnu-jni'
work.mkdir(parents=True, exist_ok=True)
names = set()
for source in ['jni_worldgen.rs', 'jni_noise.rs']:
    names.update(re.findall(r'pub extern "system" fn (Java_[A-Za-z0-9_]+)', (crate / 'src' / source).read_text()))
exports = work / 'vss-jni.def'
exports.write_text('EXPORTS\n' + '\n'.join(sorted(names)) + '\n')
# rustc's -Wl,list.def did not export JNI functions with the tested Zig wrapper.
# Passing our .def as a positional linker input keeps the same public ABI.
subprocess.run([os.environ.get('CARGO_ZIGBUILD', 'cargo-zigbuild'), 'rustc',
                '--manifest-path', str(crate / 'Cargo.toml'), '--locked', '--release', '--lib',
                '--target', 'x86_64-pc-windows-gnu', '--', '-C', 'strip=symbols',
                '-C', 'link-arg=' + str(exports)], cwd=root, check=True)
target = pathlib.Path(os.environ.get('CARGO_TARGET_DIR', str(crate / 'target')))
if not target.is_absolute():
    target = root / target
library = target / 'x86_64-pc-windows-gnu/release/vss_native_core.dll'
pe = subprocess.check_output(['objdump', '-p', str(library)], text=True)
actual = set(re.findall(r'Java_[A-Za-z0-9_]+', pe))
missing = names - actual
if missing:
    raise SystemExit('JNI exports missing: ' + ', '.join(sorted(missing)))
imports = re.findall(r'DLL Name: (\S+)', pe)
if any(name.lower().startswith(('libgcc', 'libwinpthread', 'libstdc++')) for name in imports):
    raise SystemExit('Unexpected external runtime DLL: ' + ', '.join(imports))
print(f'JNI exports checked: {len(names)}; {library}')
