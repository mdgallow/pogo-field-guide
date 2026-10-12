#!/usr/bin/env python3
"""Stamp which species have a shiny released in Pokémon GO (`sh: 1`) into POKEMON_DATA.

    python tools/build_shiny.py

Source: tools/shiny_released.json, a dated snapshot of Serebii's "Currently Available Shiny
Pokémon" list (https://www.serebii.net/pokemongo/shiny.shtml, credited in README). Refresh the
snapshot by hand when a new shiny debuts (the upstream watcher's Community Day / Raid Day
notices are the cue); it is not scraped on a schedule.

Why the app needs it: a species whose shiny has not been released cannot be shiny, so neither
the colour check nor the badge detector may mark it, whatever the pixels said. That is the
cheapest false-positive guard there is.

Matching: by dex number. A regional entry ("Slowpoke (Galar)") is released when the list has
the matching regional form ("Galarian Form"); any other entry is released when the base form
is, or when the list names its form ("Origin Forme" for "Giratina (Origin)").
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAGE = ROOT / "index.html"
LIST = Path(__file__).resolve().parent / "shiny_released.json"
REGION_WORDS = {"Alola": "Alola", "Galar": "Galarian", "Hisui": "Hisuian", "Paldea": "Paldean"}


def released(p, by_dex):
    row = by_dex.get(str(p["id"]))
    if not row:
        return False
    m = re.match(r"^(.*?) \((.*)\)$", p["name"])
    form = m.group(2) if m else ""
    region = next((w for r, w in REGION_WORDS.items() if form.startswith(r)), None)
    if region:
        return any(f.startswith(region) for f in row["forms"])
    if row.get("base"):
        return True
    return bool(form) and any(form.split()[0] in f for f in row["forms"])


def main():
    by_dex = json.loads(LIST.read_text(encoding="utf-8"))["species"]
    with open(PAGE, encoding="utf-8", newline="") as f:
        html = f.read()
    m = re.search(r"const POKEMON_DATA = (\[.*?\]);(\r?\n)", html, flags=re.S)
    data = json.loads(m.group(1))
    n = 0
    for p in data:
        if released(p, by_dex):
            p["sh"] = 1
            n += 1
        else:
            p.pop("sh", None)
    print(f"shiny released for {n} of {len(data)} entries")
    for name in ("Charizard", "Garchomp", "Slowpoke", "Slowpoke (Galar)", "Hydrapple", "Kingambit"):
        p = next((x for x in data if x["name"] == name), None)
        print(f"  {name}: {'released' if p and p.get('sh') else 'not released'}")
    new = "const POKEMON_DATA = " + json.dumps(data, ensure_ascii=False) + ";" + m.group(2)
    with open(PAGE, "w", encoding="utf-8", newline="") as f:
        f.write(html[:m.start()] + new + html[m.end():])
    print("index.html updated")


if __name__ == "__main__":
    main()
