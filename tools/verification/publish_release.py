"""Promote a verified paired release to both projects' lib directories."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import zipfile

from verify_release import verify


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def replace_file(source, target):
    # Write beside the destination so replacement stays on the same filesystem.
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as stream:
        temporary = Path(stream.name)
    try:
        shutil.copy2(source, temporary)
        if sha(temporary) != sha(source):
            raise ValueError("Copy checksum mismatch: " + str(target))
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def publish(release, projects, sources):
    manifest = json.loads((release / "manifest.json").read_text(encoding="utf-8"))
    snapshot = json.loads((release / "source-snapshot.json").read_text(encoding="utf-8"))
    build_id = manifest["buildId"]
    version = manifest["version"]
    bundle = release / ("vss-" + version + ".zip")
    files = {item["path"]: item for item in manifest["files"]}
    artifacts = {}
    copies = []
    # Complete all validation before updating either project.
    with zipfile.ZipFile(bundle) as archive:
        if archive.testzip() is not None:
            raise ValueError("Corrupt release ZIP")
        for platform, project in projects.items():
            filename = "vss-" + version + "-" + platform + ("-1.21.1.jar" if platform == "neoforge" else "-1.20.1.jar")
            relative = platform + "/" + filename
            jar = release / relative
            if sha(jar) != files[relative]["sha256"]:
                raise ValueError("Release manifest checksum mismatch: " + relative)
            if hashlib.sha256(archive.read(relative)).hexdigest() != sha(jar):
                raise ValueError("ZIP contains a different JAR: " + relative)
            for name, expected in snapshot[platform].items():
                # Release documentation/tools may advance after compilation.
                if name.startswith("src/") or name in {"build.gradle", "settings.gradle", "gradle.properties"}:
                    path = project / name
                    if not path.is_file() or sha(path) != expected:
                        raise ValueError("Main project differs from tested source: " + platform + ": " + name)
            extra = [p.relative_to(project).as_posix() for p in (project / "src").rglob("*")
                     if p.is_file() and p.relative_to(project).as_posix() not in snapshot[platform]]
            if extra:
                raise ValueError("Untested files in main source tree: " + str(extra))
            artifacts[platform] = verify(jar, platform, build_id, sources[platform])
            copies.extend([(jar, project / "lib" / filename), (bundle, project / "lib" / bundle.name)])

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    backup = projects["neoforge"] / "tmp/release-promotions" / build_id / timestamp
    backup.mkdir(parents=True)
    before = []
    for platform, project in projects.items():
        for _, target in copies:
            if target.parent != project / "lib" or not target.is_file():
                continue
            saved = backup / platform / target.name
            saved.parent.mkdir(exist_ok=True)
            shutil.copy2(target, saved)
            before.append({"path": str(target), "sha256": sha(target), "backup": str(saved)})
    for source, target in copies:
        target.parent.mkdir(parents=True, exist_ok=True)
        replace_file(source, target)
    after = []
    for source, target in copies:
        if sha(source) != sha(target):
            raise ValueError("Published checksum mismatch: " + str(target))
        after.append({"path": str(target), "sha256": sha(target), "bytes": target.stat().st_size})
    record = {"buildId": build_id, "publishedAtUtc": timestamp, "sourceRelease": str(release),
              "artifacts": artifacts, "before": before, "after": after}
    (backup / "promotion.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for platform, project in projects.items():
        sidecar = {"buildId": build_id, "publishedAtUtc": timestamp,
                   "jar": artifacts[platform], "bundle": {"name": bundle.name, "sha256": sha(bundle)}}
        (project / ("lib/vss-" + version + "-release.json")).write_text(json.dumps(sidecar, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"buildId": build_id, "backup": str(backup), "published": after}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", type=Path, required=True)
    parser.add_argument("--neoforge-project", type=Path, required=True)
    parser.add_argument("--forge-project", type=Path, required=True)
    parser.add_argument("--neoforge-source", type=Path, required=True)
    parser.add_argument("--forge-source", type=Path, required=True)
    args = parser.parse_args()
    publish(args.release_dir.resolve(),
            {"neoforge": args.neoforge_project.resolve(), "forge": args.forge_project.resolve()},
            {"neoforge": args.neoforge_source.resolve(), "forge": args.forge_source.resolve()})
