#!/usr/bin/env python3
"""Colour palettes of every species' normal and shiny sprite, for on-device shiny detection.

    python tools/build_palettes.py            download icons to tools/.cache/icons and rebuild
    python tools/build_palettes.py --offline  rebuild from cached icons

Writes `pal` into POKEMON_DATA for each species that has a Pokémon GO icon with a shiny variant:
    pal: { "n": [[hue 0-359, sat 0-100, val 0-100, weight 0-100] x3], "s": [...same for shiny],
           "d": how different the two palettes are (0-100) }
The phone samples the colours of the 3D model on the storage page and the page compares them with
these: clearly closer to the shiny palette than to the normal one (and palettes that differ enough
to tell) marks the Pokémon shiny. Needs Pillow (developer machine only; the app has no dependency).
"""
import colorsys
import json
import math
import re
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
PAGE = ROOT / "index.html"
CACHE = Path(__file__).resolve().parent / ".cache" / "icons"


def fetch(url, path, offline):
    if path.exists():
        return path.read_bytes()
    if offline:
        return None
    try:
        with urllib.request.urlopen(url, timeout=60) as r:
            data = r.read()
        path.write_bytes(data)
        return data
    except Exception:
        return None


def palette(png_bytes, k=3):
    """k dominant colours of the opaque, non-outline pixels: [[hue, sat, val, weight], ...]."""
    img = Image.open(__import__("io").BytesIO(png_bytes)).convert("RGBA")
    img.thumbnail((96, 96))
    pts = []
    for r, g, b, a in img.get_flattened_data() if hasattr(img, "get_flattened_data") else img.getdata():
        if a < 200:
            continue
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        if v < 0.18 or (s < 0.12 and v > 0.92):      # outlines and white highlights say nothing
            continue
        pts.append((math.cos(h * 2 * math.pi) * s, math.sin(h * 2 * math.pi) * s, v, h, s))
    if len(pts) < 20:
        return None
    # k-means in (hue-as-circle x saturation, value)
    centres = [pts[int(i * len(pts) / k)][:3] for i in range(k)]
    for _ in range(12):
        groups = [[] for _ in range(k)]
        for p in pts:
            j = min(range(k), key=lambda c: (p[0] - centres[c][0]) ** 2 + (p[1] - centres[c][1]) ** 2 + (p[2] - centres[c][2]) ** 2 * 0.5)
            groups[j].append(p)
        centres = [tuple(sum(p[i] for p in g) / len(g) for i in range(3)) if g else centres[c] for c, g in enumerate(groups)]
    out = []
    for c, g in zip(centres, groups):
        if not g:
            continue
        hue = (math.degrees(math.atan2(c[1], c[0])) + 360) % 360
        sat = min(1.0, math.hypot(c[0], c[1]))
        out.append([round(hue), round(sat * 100), round(c[2] * 100), round(100 * len(g) / len(pts))])
    out.sort(key=lambda x: -x[3])
    return out


def distance(a, b):
    """0-100: how far palette b is from palette a (weighted nearest-colour hue/sat/value gap)."""
    def gap(x, y):
        dh = abs(x[0] - y[0]) % 360
        dh = min(dh, 360 - dh) / 180              # 0..1
        chroma = min(x[1], y[1]) / 100            # hue matters only when both are coloured
        return dh * chroma * 3.0 + abs(x[1] - y[1]) / 100 * 0.6 + abs(x[2] - y[2]) / 100 * 0.6
    total = 0
    for x in a:
        total += min(gap(x, y) for y in b) * x[3] / 100
    return round(min(100, total * 100))


def main():
    offline = "--offline" in sys.argv
    CACHE.mkdir(parents=True, exist_ok=True)
    with open(PAGE, encoding="utf-8", newline="") as f:
        html = f.read()
    m = re.search(r"const POKEMON_DATA = (\[.*?\]);(\r?\n)", html, flags=re.S)
    data = json.loads(m.group(1))
    jobs = [p for p in data if "pokemon-go-api" in (p.get("img") or "")]

    def work(p):
        normal_url = p["img"]
        shiny_url = normal_url.replace(".icon.png", ".s.icon.png")
        key = normal_url.rsplit("/", 1)[1]
        n = fetch(normal_url, CACHE / key, offline)
        s = fetch(shiny_url, CACHE / key.replace(".icon.png", ".s.icon.png"), offline)
        if not n or not s:
            return p, None
        pn, ps = palette(n), palette(s)
        if not pn or not ps:
            return p, None
        return p, {"n": pn, "s": ps, "d": max(distance(pn, ps), distance(ps, pn))}

    done = 0
    with ThreadPoolExecutor(max_workers=16) as ex:
        for p, pal in ex.map(work, jobs):
            if pal:
                p["pal"] = pal
                done += 1
            else:
                p.pop("pal", None)
    tellable = sum(1 for p in data if p.get("pal") and p["pal"]["d"] >= 20)
    print(f"palettes for {done} of {len(jobs)} icon species; shiny visibly different for {tellable}")
    for name in ("Charizard", "Gyarados", "Pikachu", "Gengar", "Magikarp", "Bulbasaur"):
        p = next((x for x in data if x["name"] == name), None)
        if p and p.get("pal"):
            print(f"  {name}: d={p['pal']['d']} normal={p['pal']['n'][0]} shiny={p['pal']['s'][0]}")
    new = "const POKEMON_DATA = " + json.dumps(data, ensure_ascii=False) + ";" + m.group(2)
    with open(PAGE, "w", encoding="utf-8", newline="") as f:
        f.write(html[:m.start()] + new + html[m.end():])
    print("index.html updated")


if __name__ == "__main__":
    main()
