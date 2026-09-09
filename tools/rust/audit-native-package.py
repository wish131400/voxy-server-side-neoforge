"""Verify all embedded native targets against built files and source JNI exports.

Requires lief 1.0.0. This validates artifacts, not execution on target hardware.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile
import lief

parser = argparse.ArgumentParser()
parser.add_argument("jar", type=Path)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
crate = root / "tools/rust/vss-native-core"
expected = set()
for source in (crate / "src").glob("jni_*.rs"):
    expected.update(re.findall(r'pub extern "system" fn (Java_\w+)', source.read_text(encoding="utf-8")))
targets = {
    "windows-x86_64": ("x86_64-pc-windows-msvc", "vss_native_core.dll"),
    "linux-x86_64": ("x86_64-unknown-linux-gnu", "libvss_native_core.so"),
    "linux-aarch64": ("aarch64-unknown-linux-gnu", "libvss_native_core.so"),
    "macos-x86_64": ("x86_64-apple-darwin", "libvss_native_core.dylib"),
    "macos-aarch64": ("aarch64-apple-darwin", "libvss_native_core.dylib"),
}

def verify_signature(data):
    offset = 32
    for _ in range(struct.unpack_from("<I", data, 16)[0]):
        command, size = struct.unpack_from("<II", data, offset)
        if command == 0x1D:
            start, length = struct.unpack_from("<II", data, offset + 8)
            blob = data[start:start + length]
            magic, _, count = struct.unpack_from(">III", blob)
            assert magic == 0xFADE0CC0
            for index in range(count):
                kind, location = struct.unpack_from(">II", blob, 12 + 8 * index)
                if kind != 0:
                    continue
                directory = blob[location:]
                fields = struct.unpack_from(">9I4B", directory)
                magic, _, _, flags, hashes, _, _, slots, limit, hash_size, hash_type, _, page_bits = fields
                assert magic == 0xFADE0C02 and flags & 2 and hash_type == 2 and hash_size == 32
                assert limit <= start and slots == (limit + (1 << page_bits) - 1) >> page_bits
                for slot in range(slots):
                    page = data[slot << page_bits:min((slot + 1) << page_bits, limit)]
                    assert hashlib.sha256(page).digest() == directory[hashes + slot * 32:hashes + (slot + 1) * 32]
                return True
        offset += size
    return False

report = []
with zipfile.ZipFile(args.jar) as archive:
    packaged = {name for name in archive.namelist() if name.startswith("META-INF/vss-natives/") and not name.endswith("/")}
    assert packaged == {f"META-INF/vss-natives/{key}/{value[1]}" for key, value in targets.items()}
    for platform, (target, filename) in targets.items():
        path = crate / "target" / target / "release" / filename
        data = path.read_bytes()
        entry = f"META-INF/vss-natives/{platform}/{filename}"
        assert archive.read(entry) == data
        binary = lief.parse(str(path))
        detail = {}
        if isinstance(binary, lief.PE.Binary):
            assert int(binary.header.machine) == 0x8664
            names = {symbol.name for symbol in binary.get_export().entries}
            detail["dependencies"] = [item.name for item in binary.imports]
        elif isinstance(binary, lief.ELF.Binary):
            assert int(binary.header.machine_type) == (183 if "aarch64" in platform else 62)
            names = {symbol.name for symbol in binary.exported_symbols}
            detail["dependencies"] = list(binary.libraries)
            versions = {symbol.name for requirement in binary.symbols_version_requirement for symbol in requirement.get_auxiliary_symbols()}
            assert all(not version.startswith("GLIBC_") or tuple(map(int, version[6:].split("."))) <= (2, 28) for version in versions)
            detail["symbol_versions"] = sorted(versions)
        else:
            assert isinstance(binary, lief.MachO.Binary)
            assert int(binary.header.cpu_type) == (0x100000C if "aarch64" in platform else 0x1000007)
            names = {symbol.name.removeprefix("_") for symbol in binary.exported_symbols}
            detail["dependencies"] = [item.name for item in binary.libraries]
            assert detail["dependencies"] == ["@rpath/libvss_native_core.dylib", "/usr/lib/libSystem.B.dylib"]
            versions = [command for command in binary.commands if isinstance(command, lief.MachO.BuildVersion)]
            assert len(versions) == 1 and list(versions[0].minos) == [11, 0, 0]
            detail["minimum_macos"] = "11.0"
            detail["ad_hoc_signature_verified"] = verify_signature(data)
            if "aarch64" in platform:
                assert detail["ad_hoc_signature_verified"]
        assert names == expected, (platform, names - expected, expected - names)
        report.append(dict(platform=platform, bytes=len(data), compressed=archive.getinfo(entry).compress_size,
                           sha256=hashlib.sha256(data).hexdigest(), jni_exports=len(names), **detail))
args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report, indent=2))
