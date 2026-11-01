"""Render the pinned iOS icon's embedded bitmap/viewBox; no redrawing or background removal.

Requires Pillow. Input: MyApp/AppIcon.icon/Assets/center.svg at the documented Git blob SHA.
"""
import argparse
import base64
import hashlib
from io import BytesIO
from pathlib import Path
import xml.etree.ElementTree as ET

from PIL import Image


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    source = args.source.read_bytes()
    expected = "906ec207c8a230247088d5a4a1313d345ac2dd35"
    actual = hashlib.sha1(b"blob " + str(len(source)).encode() + b"\0" + source).hexdigest()
    if actual != expected:
        raise SystemExit("Source is not the pinned iOS icon blob; review before updating the importer.")
    root = ET.fromstring(source)
    if root.attrib.get("viewBox") != "0 0 1024 1024" or len(root) != 1:
        raise SystemExit("Unexpected SVG geometry")
    element = root[0]
    if element.tag != "{http://www.w3.org/2000/svg}image":
        raise SystemExit("Expected a single embedded image")
    uri = element.attrib["href"]
    if not uri.startswith("data:image/png;base64,"):
        raise SystemExit("External image references are not supported")
    image = Image.open(BytesIO(base64.b64decode(uri.split(",", 1)[1], validate=True))).convert("RGB")
    x, y = float(element.attrib["x"]), float(element.attrib["y"])
    w, h = float(element.attrib["width"]), float(element.attrib["height"])
    # Render precisely the SVG's 1024-square viewBox, including its existing 1.1x image scale.
    extent = (-x / w * image.width, -y / h * image.height,
              (1024 - x) / w * image.width, (1024 - y) / h * image.height)
    rendered = image.transform((512, 512), Image.Transform.EXTENT, extent, Image.Resampling.BICUBIC)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    rendered.save(args.output, format="PNG", optimize=True)
    print("Pinned iOS source:", actual)
    print("Android icon SHA-256:", hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
