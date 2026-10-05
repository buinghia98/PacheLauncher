#!/usr/bin/env python3
"""Convert the Kenney "Mobile Controls" (CC0) Style C / Icons SVGs used by the on-screen gamepad
into Android VectorDrawables, plus the derived FILL silhouettes the overlay paints under them.

Why vectors: the dials are drawn at up to 190 dp (about 500 px on a 2.6x panel); the largest PNGs in
the pack are 256 px, so they would be upscaled 2x and the thin Style C outlines would go soft.

Why derived fills: Style C is outline-only (white strokes, transparent inside). Over a busy game frame
an outline alone reads poorly, and the overlay's look -- and its PRESSED look -- has always been a
filled body (RadialGamePad's `normalColor` / `pressedColor`). The fill of a shape is exactly its OUTER
contours: every subpath whose bounding box is not inside another subpath's bounding box. Holes and
inner glyphs (arrows, dots) are always nested inside an outer contour, so they drop out.

The d-pad is split per ARM (outline and fill), so a pressed direction can be shown on its own arm:
every subpath is assigned to the arm whose outer contour's bounding box contains it.

The Kenney SVGs only use absolute M/L/Q commands with implicit repeats, one <path> per file, no
transforms -- this script asserts that rather than trusting it. Fill rule is evenOdd: it gives the
same result as the source's nonzero winding for these shapes (verified by --check, which rasterises
both the VectorDrawable path data and compares it with Kenney's own 2x PNG).

Usage:
  python touchpad/tools/kenney_controls_to_vector.py <mobile-controls-1 dir> touchpad/src/main/res/drawable-nodpi [--check]
"""
import math
import os
import re
import sys

# (source file under Vector/, output base name, split mode)
SOURCES = [
    ("Style C/button_circle.svg", "pl_kenney_button_circle", "fill"),
    ("Style C/button_circle_wide.svg", "pl_kenney_button_circle_wide", "fill"),
    ("Style C/dpad_separate.svg", "pl_kenney_dpad_separate", "arms"),
    ("Style C/joystick_circle_pad_a.svg", "pl_kenney_joystick_circle_pad_a", "fill"),
    ("Style C/joystick_circle_nub_a.svg", "pl_kenney_joystick_circle_nub_a", "fill"),
    ("Icons/icon_button_a.svg", "pl_kenney_icon_button_a", None),
    ("Icons/icon_button_b.svg", "pl_kenney_icon_button_b", None),
    ("Icons/icon_button_x.svg", "pl_kenney_icon_button_x", None),
    ("Icons/icon_button_y.svg", "pl_kenney_icon_button_y", None),
]
# PNG twin used by --check (Large (2x) sprites are exactly 2x the SVG canvas).
PNG_TWIN = {
    "Style C/": "Sprites/Style C/Large (2×)/",
    "Icons/": "Sprites/Icons/Large (2×)/",
}

TOKEN = re.compile(r"[MLQ]|-?\d*\.?\d+(?:[eE][-+]?\d+)?")


def read_svg(path):
    text = open(path, encoding="utf-8").read()
    assert text.count("<path") == 1, f"{path}: expected exactly one <path>"
    assert "transform=" not in text and "fill-rule" not in text, f"{path}: unsupported attribute"
    w = float(re.search(r'<svg[^>]*\bwidth="([\d.]+)"', text).group(1))
    h = float(re.search(r'<svg[^>]*\bheight="([\d.]+)"', text).group(1))
    d = re.search(r'\bd="([^"]+)"', text).group(1)
    assert set(re.findall(r"[A-Za-z]", d)) <= {"M", "L", "Q"}, f"{path}: unsupported command"
    return w, h, d


def subpaths(d):
    """Split path data into subpaths: [(d_string, points_for_bbox, polyline_for_raster)]."""
    out, cmd, nums, cur = [], None, [], None

    def flush_cmd():
        nonlocal nums
        if cmd is None:
            return
        if cmd == "M":
            assert len(nums) >= 2
            out.append({"d": [], "pts": [], "poly": []})
            x, y = nums[0], nums[1]
            out[-1]["d"].append(f"M{fmt(x)} {fmt(y)}")
            out[-1]["pts"].append((x, y)); out[-1]["poly"].append((x, y))
            rest = nums[2:]  # implicit lineto after moveto
            if rest:
                emit("L", rest)
        else:
            emit(cmd, nums)
        nums = []

    def emit(c, values):
        sp = out[-1]
        step = 2 if c == "L" else 4
        assert len(values) % step == 0, f"bad arg count for {c}"
        sp["d"].append(c + " ".join(fmt(v) for v in values))
        for i in range(0, len(values), step):
            seg = values[i:i + step]
            sp["pts"].extend(zip(seg[0::2], seg[1::2]))
            if c == "L":
                sp["poly"].append((seg[0], seg[1]))
            else:  # flatten the quadratic for the --check raster
                x0, y0 = sp["poly"][-1]
                cx, cy, x1, y1 = seg
                for k in range(1, 9):
                    t = k / 8
                    sp["poly"].append(((1 - t) ** 2 * x0 + 2 * (1 - t) * t * cx + t * t * x1,
                                       (1 - t) ** 2 * y0 + 2 * (1 - t) * t * cy + t * t * y1))

    for tok in TOKEN.findall(d):
        if tok in ("M", "L", "Q"):
            flush_cmd()
            cmd = tok
        else:
            nums.append(float(tok))
    flush_cmd()
    for sp in out:
        xs = [p[0] for p in sp["pts"]]; ys = [p[1] for p in sp["pts"]]
        sp["bbox"] = (min(xs), min(ys), max(xs), max(ys))
        sp["d"] = " ".join(sp["d"])
    return out


def fmt(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


def inside(a, b, eps=0.01):
    """Is bbox a strictly inside bbox b (and not the same box)?"""
    return a != b and a[0] >= b[0] - eps and a[1] >= b[1] - eps and a[2] <= b[2] + eps and a[3] <= b[3] + eps


def outers(sps):
    return [sp for sp in sps if not any(inside(sp["bbox"], o["bbox"]) for o in sps if o is not sp)]


def vector_xml(w, h, path_d, crop=None):
    """crop = (x0, y0, x1, y1) in source units: emit only that window of the canvas (the d-pad arms)."""
    x0, y0, x1, y1 = crop or (0, 0, w, h)
    cw, ch = x1 - x0, y1 - y0
    note = ""
    if crop:
        note = (f"\n     Cropped to source window ({fmt(x0)}, {fmt(y0)})-({fmt(x1)}, {fmt(y1)}) of {fmt(w)}x{fmt(h)},"
                f"\n     i.e. fractions ({x0 / w:.4f}, {y0 / h:.4f})-({x1 / w:.4f}, {y1 / h:.4f});"
                f" TouchGamepadSprite.ARMS must match.")
    path = (f'<path android:fillColor="#FFFFFFFF" android:fillType="evenOdd"\n'
            f'        android:pathData="{path_d}"/>')
    if crop:
        body = (f'    <group android:translateX="{fmt(-x0)}" android:translateY="{fmt(-y0)}">\n'
                f'        {path.replace(chr(10), chr(10) + "    ")}\n'
                f'    </group>\n')
    else:
        body = f"    {path}\n"
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Generated by touchpad/tools/kenney_controls_to_vector.py from Kenney "Mobile Controls" (CC0,\n'
        f"     www.kenney.nl). Do not edit by hand; see touchpad/KENNEY-LICENSE.txt.{note} -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{fmt(cw)}dp" android:height="{fmt(ch)}dp"\n'
        f'    android:viewportWidth="{fmt(cw)}" android:viewportHeight="{fmt(ch)}">\n'
        + body
        + "</vector>\n"
    )


def arm_name(bbox, w, h):
    cx = (bbox[0] + bbox[2]) / 2 - w / 2
    cy = (bbox[1] + bbox[3]) / 2 - h / 2
    if abs(cx) > abs(cy):
        return "east" if cx > 0 else "west"
    return "south" if cy > 0 else "north"


def build(pack, out_dir):
    outputs = {}  # name -> (w, h, [subpaths])
    for rel, name, mode in SOURCES:
        w, h, d = read_svg(os.path.join(pack, "Vector", rel))
        sps = subpaths(d)
        outputs[name] = (w, h, sps, rel, None)
        if mode == "fill":
            outputs[name + "_fill"] = (w, h, outers(sps), None, None)
        elif mode == "arms":
            arms = outers(sps)
            assert len(arms) == 4, f"{rel}: expected 4 arms, got {len(arms)}"
            for arm in arms:
                a = arm_name(arm["bbox"], w, h)
                members = [sp for sp in sps if sp is arm or inside(sp["bbox"], arm["bbox"])]
                # Each arm is cropped to its own box: a VectorDrawable caches a bitmap the size of its
                # bounds, and eight full-canvas layers on a 190 dp d-pad would cost about 8 MB.
                bb = arm["bbox"]
                crop = (math.floor(bb[0]), math.floor(bb[1]), math.ceil(bb[2]), math.ceil(bb[3]))
                outputs[f"{name}_{a}"] = (w, h, members, None, crop)
                outputs[f"{name}_{a}_fill"] = (w, h, [arm], None, crop)
                print(f"{name}_{a}: window {crop} -> fractions "
                      f"({crop[0] / w:.4f}f, {crop[1] / h:.4f}f, {crop[2] / w:.4f}f, {crop[3] / h:.4f}f)")
            assert sum(len(outputs[f"{name}_{a}"][2]) for a in ("north", "east", "south", "west")) == len(sps), \
                f"{rel}: some subpaths belong to no arm"
    os.makedirs(out_dir, exist_ok=True)
    for name, (w, h, sps, rel, crop) in outputs.items():
        if rel is not None and dict((r, m) for r, _, m in SOURCES)[rel] == "arms":
            continue  # only the per-arm pieces are used; the whole is kept for --check
        with open(os.path.join(out_dir, name + ".xml"), "w", encoding="utf-8", newline="\n") as f:
            f.write(vector_xml(w, h, " ".join(sp["d"] for sp in sps), crop))
    return outputs


def raster(w, h, sps, scale):
    from PIL import Image, ImageChops, ImageDraw
    ss = 4
    acc = Image.new("1", (int(w * scale * ss), int(h * scale * ss)), 0)
    for sp in sps:
        m = Image.new("1", acc.size, 0)
        ImageDraw.Draw(m).polygon([(x * scale * ss, y * scale * ss) for x, y in sp["poly"]], fill=1)
        acc = ImageChops.logical_xor(acc, m)  # evenOdd
    return acc.convert("L").resize((int(w * scale), int(h * scale)), Image.LANCZOS)


def check(pack, outputs):
    from PIL import Image
    ok = True
    for name, (w, h, sps, rel, crop) in outputs.items():
        if rel is None:
            continue
        prefix = next(p for p in PNG_TWIN if rel.startswith(p))
        png = os.path.join(pack, PNG_TWIN[prefix] + rel[len(prefix):].replace(".svg", ".png"))
        ref = Image.open(png).convert("RGBA").getchannel("A")
        mine = raster(w, h, sps, ref.size[0] / w)
        a = [p > 127 for p in ref.getdata()]; b = [p > 127 for p in mine.getdata()]
        inter = sum(x and y for x, y in zip(a, b)); union = sum(x or y for x, y in zip(a, b))
        iou = inter / union if union else 1
        print(f"{name:40s} IoU vs Kenney PNG = {iou:.4f}")
        ok &= iou > 0.95  # thin rings (the nub, 3/64 stroke) lose a few % to edge AA alone
    return ok


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    outs = build(sys.argv[1], sys.argv[2])
    print(f"wrote {len(outs)} VectorDrawables to {sys.argv[2]}")
    if "--check" in sys.argv[3:] and not check(sys.argv[1], outs):
        sys.exit("raster check FAILED")
