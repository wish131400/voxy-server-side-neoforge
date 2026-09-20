"""Decode /vssclient prediction rendercapture crops without launching Minecraft.

Usage: python tools/prediction/inspect-render-capture.py frame-123.zip output-dir
Requires Pillow. PNGs show clamped raw channels; packed shader metadata is not display RGB.
"""
import argparse
import json
import math
from pathlib import Path
import struct
import zipfile
from PIL import Image, ImageDraw


def inspect(archive, output):
    output.mkdir(parents=True, exist_ok=True)
    thumbnails = []
    with zipfile.ZipFile(archive) as capture:
        report = json.loads(capture.read("report.json"))
        (output / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        for number, snapshot in enumerate(report["snapshots"], 1):
            for attachment in snapshot.get("attachments", []):
                name = attachment.get("file")
                if not name:
                    continue
                _, _, width, height = attachment["crop"]
                components = attachment["components"]
                byteorder = "<" if attachment["byteOrder"] == "LITTLE_ENDIAN" else ">"
                datatype = {"f32": "f", "i32": "i", "u32": "I"}[name.rsplit(".", 1)[1]]
                data = capture.read(name)
                expected = width * height * components
                if len(data) != expected * 4:
                    raise ValueError(f"Invalid crop length: {name}")
                values = struct.unpack(f"{byteorder}{expected}{datatype}", data)
                finite = [v for v in values if math.isfinite(v)]
                lo, hi = (min(finite), max(finite)) if finite else (0, 1)
                pixels = []
                for i in range(0, expected, components):
                    pixel = values[i:i + components]
                    if components == 1 or datatype != "f":
                        pixel = [((pixel[0] - lo) / (hi - lo)) if hi > lo else 0] * 3
                    pixels.append(tuple(round(max(0, min(1, v)) * 255) if math.isfinite(v) else 255 for v in pixel[:3]))
                image = Image.new("RGB", (width, height))
                image.putdata(pixels)
                image = image.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
                # Use locally generated names; never extract archive paths.
                png = f"{number:02d}-{attachment['attachment']}.png"
                image.save(output / png)
                tile = Image.new("RGB", (256, 310), "#222222")
                tile.paste(image.resize((256, 256)), (0, 0))
                text = f"{snapshot['stage']} / {attachment['attachment']}\nFBO {snapshot['framebuffer']} tex {attachment['texture']}\nraw range {lo:.5g} .. {hi:.5g}"
                ImageDraw.Draw(tile).text((4, 259), text, fill="white")
                thumbnails.append(tile)
        if thumbnails:
            columns = 4
            sheet = Image.new("RGB", (columns * 256, math.ceil(len(thumbnails) / columns) * 310), "#111111")
            for index, tile in enumerate(thumbnails):
                sheet.paste(tile, ((index % columns) * 256, (index // columns) * 310))
            sheet.save(output / "contact.png")
        print(f"{len(report['snapshots'])} snapshots, {len(thumbnails)} attachment crops, {report['readbackBytes']} bytes")
        print(output.resolve())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    inspect(args.archive, args.output)
