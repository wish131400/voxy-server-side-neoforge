"""Adapt rustc's Windows path separators to ld64.lld's Unix response parser."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

with tempfile.TemporaryDirectory(prefix="vss-ld64-") as directory:
    arguments = []
    for index, argument in enumerate(sys.argv[1:]):
        if argument.startswith("@"):
            raw = Path(argument[1:]).read_bytes()
            text = raw.decode("utf-16" if raw.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8")
            response = Path(directory) / f"{index}.rsp"
            response.write_text(text.replace("\\", "/"), encoding="utf-8")
            arguments.append("@" + response.as_posix())
        else:
            arguments.append(argument.replace("\\", "/"))
    result = subprocess.run([os.environ["VSS_RUST_LLD"], *arguments], check=False)
sys.exit(result.returncode)
