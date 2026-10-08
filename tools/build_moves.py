#!/usr/bin/env python3
"""Rebuild every species' recommended movesets inside index.html's POKEMON_DATA.

Run by a developer when the game rebalances moves; the app itself never downloads anything.

    python tools/build_moves.py            download the sources to tools/.cache and rebuild
    python tools/build_moves.py --offline  rebuild from the cached copies
    python tools/build_moves.py --report   show what would change, write nothing

Sources
  * Game master (PokeMiners mirror of the game's own data): every species' move pool, which
    moves are Elite-TM-only or cannot be TM'd at all, base stats, and raid (PvE) move numbers.
  * PvPoke (MIT): the recommended moveset and rank per league (Great / Ultra / Master).

What is written per species
  pve_q / pve_c   best raid fast / charged move ("(Elite)" = Elite TM or event only, "(Special)" =
                  cannot be TM'd; "[Alt: X]" = the best move an ordinary TM can roll)
  pvp_q / pvp_c   moveset for the league where the species ranks best
  lg              {"gl"|"ul"|"ml": [rank, fast, charged1, charged2]} for each league it is ranked in
  mv              {"f": [...], "c": [...], "e": [...], "x": [...]} move pool: fast, charged,
                  elite-only, not-TM-able (display names), so the app can tell a wrong move
                  from a fine one
  bs              base attack / defense / stamina (for CP and IV-odds maths)
  rr / rt / rty   raid rank overall, rank among attackers of the same move type, and that type
                  (used to say whether a species is a RAID pick, a PVP pick, or BOTH)
The older ranks (pvp_r / pve_r / pvp_n / pve_n) and the action tags are left untouched.
"""
import json
import math
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path(__file__).resolve().parent / ".cache"
PAGE = ROOT / "index.html"
SOURCES = {
    "game_master.json": "https://raw.githubusercontent.com/PokeMiners/game_masters/master/latest/latest.json",
    "pvpoke_gamemaster.json": "https://raw.githubusercontent.com/pvpoke/pvpoke/master/src/data/gamemaster.json",
    "rank_gl.json": "https://raw.githubusercontent.com/pvpoke/pvpoke/master/src/data/rankings/all/overall/rankings-1500.json",
    "rank_ul.json": "https://raw.githubusercontent.com/pvpoke/pvpoke/master/src/data/rankings/all/overall/rankings-2500.json",
    "rank_ml.json": "https://raw.githubusercontent.com/pvpoke/pvpoke/master/src/data/rankings/all/overall/rankings-10000.json",
}
CPM_40 = 0.7903
ENEMY_DEF = 160.0          # a typical raid boss, as in the usual DPS spreadsheets
REGION_WORDS = {"alola": "alolan", "galar": "galarian", "hisui": "hisuian", "paldea": "paldean"}


def load(name, offline):
    path = CACHE / name
    if not path.exists() or not offline:
        if offline:
            sys.exit(f"{path} is missing; run once without --offline")
        CACHE.mkdir(exist_ok=True)
        print("downloading", name)
        with urllib.request.urlopen(SOURCES[name], timeout=180) as r:
            path.write_bytes(r.read())
    return json.loads(path.read_text(encoding="utf-8"))


def title(move_id):
    return " ".join(w.capitalize() for w in move_id.replace("_FAST", "").split("_"))


class GameMaster:
    def __init__(self, raw, pvpoke):
        self.names = {m["moveId"].replace("_", ""): m["name"] for m in pvpoke["moves"]}   # FUTURESIGHT == FUTURE_SIGHT
        self.moves = {}          # id (string, FAST suffix kept) -> dict
        by_number = {}
        for item in raw:
            ms = item.get("data", {}).get("moveSettings")
            if not ms:
                continue
            m = re.match(r"V(\d+)_MOVE_(.+)", item["templateId"])
            if m:
                move_id = m.group(2)
                by_number[int(m.group(1))] = move_id
            elif isinstance(ms.get("movementId"), str):
                move_id = ms["movementId"]
            else:
                continue
            self.moves[move_id] = {
                "id": move_id,
                "type": ms.get("pokemonType", "").replace("POKEMON_TYPE_", ""),
                "power": float(ms.get("power", 0)),
                "dur": ms.get("durationMs", 1000) / 1000.0,
                "energy": float(ms.get("energyDelta", 0)),
                "dws": ms.get("damageWindowStartMs", 0) / 1000.0,
            }
        fix = lambda x: by_number.get(x, str(x)) if isinstance(x, int) else x
        self.species = {}        # dex -> [settings]
        for item in raw:
            ps = item.get("data", {}).get("pokemonSettings")
            if not ps or "stats" not in ps or "baseAttack" not in ps["stats"]:
                continue
            dex = int(re.match(r"V(\d+)_POKEMON_", item["templateId"]).group(1))
            form = ps.get("form")
            self.species.setdefault(dex, []).append({
                "template": item["templateId"], "form": form if isinstance(form, str) else None,
                "types": [t.replace("POKEMON_TYPE_", "") for t in (ps.get("type"), ps.get("type2")) if t],
                "atk": ps["stats"]["baseAttack"], "def": ps["stats"]["baseDefense"], "sta": ps["stats"]["baseStamina"],
                "fast": [fix(x) for x in ps.get("quickMoves", [])],
                "charged": [fix(x) for x in ps.get("cinematicMoves", [])],
                "elite_fast": [fix(x) for x in ps.get("eliteQuickMove", [])],
                "elite_charged": [fix(x) for x in ps.get("eliteCinematicMove", [])],
                "special": [fix(x) for x in ps.get("nonTmCinematicMoves", [])],
            })

    def display(self, move_id):
        return self.names.get(move_id.replace("_FAST", "").replace("_", "")) or title(move_id)

    def find(self, dex, app_name):
        """The game-master entry for an app species name ("Raichu (Alola)", "HO_OH_S", "Bulbasaur")."""
        cands = self.species.get(dex, [])
        if not cands:
            return None
        m = re.search(r"\((.+)\)$", app_name)
        if m:
            key = "_" + re.sub(r"[^A-Z0-9]+", "_", m.group(1).upper()).strip("_")
            # Shortest match wins, so "(Standard)" is DARMANITAN_STANDARD, not ..._GALARIAN_STANDARD.
            ends = [c for c in cands if c["form"] and c["form"].endswith(key)]
            # "(Galar)" -> MEOWTH_GALARIAN, "(Hisui)" -> ARCANINE_HISUIAN
            inside = [c for c in cands if c["form"] and key in c["form"]]
            for group in (ends, inside):
                if group:
                    return min(group, key=lambda c: len(c["form"]))
        for c in cands:
            if c["form"] and c["form"] == app_name:
                return c
        for c in cands:
            if c["form"] and c["form"].endswith("_NORMAL"):
                return c
        for c in cands:
            if c["form"] is None:
                return c
        return cands[0]


def raid_dps(sp, fast, charged, gm):
    """Comprehensive DPS of one moveset at level 40, 15/15/15, no weather.

    Raid attackers are picked for a type, so the moveset is scored against a boss that is weak
    to the charged move's type: the charged move hits for x1.6, and so does the fast move when it
    shares that type. This is what makes Bullet Punch (not a higher-energy off-type fast move)
    the partner of Meteor Mash."""
    f, c = gm.moves.get(fast), gm.moves.get(charged)
    if not f or not c or f["dur"] <= 0 or c["dur"] <= 0 or c["energy"] >= 0:
        return 0.0
    atk = (sp["atk"] + 15) * CPM_40
    dfn = (sp["def"] + 15) * CPM_40
    hp = (sp["sta"] + 15) * CPM_40
    stab = lambda mv: 1.2 if mv["type"] in sp["types"] else 1.0
    f_dmg = 0.5 * f["power"] * atk / ENEMY_DEF * stab(f) * (1.6 if f["type"] == c["type"] else 1.0) + 0.5
    c_dmg = 0.5 * c["power"] * atk / ENEMY_DEF * stab(c) * 1.6 + 0.5
    fe = max(f["energy"], 0.5)
    ce = -c["energy"]
    y = 900.0 / dfn                                 # damage taken per second
    fdps, feps = f_dmg / f["dur"], fe / f["dur"]
    cdps = c_dmg / c["dur"]
    ceps = (ce + 0.5 * fe + 0.5 * y * c["dws"]) / c["dur"] if ce >= 100 else ce / c["dur"]
    dps0 = (fdps * ceps + cdps * feps) / (ceps + feps)
    x = 0.5 * ce + 0.5 * fe
    return dps0 + (cdps - fdps) / (ceps + feps) * (0.5 - x / hp) * y


def best_raid(sp, gm):
    """((fast, charged) best overall, (fast, charged) best an ordinary TM can reach)."""
    fasts = sp["fast"] + sp["elite_fast"]
    chargeds = sp["charged"] + sp["elite_charged"] + sp["special"]
    scored = sorted(((raid_dps(sp, f, c, gm), f, c) for f in fasts for c in chargeds), reverse=True)
    scored = [s for s in scored if s[0] > 0]
    if not scored:
        return None, None
    plain = [s for s in scored if s[1] in sp["fast"] and s[2] in sp["charged"]]
    return scored[0][1:], (plain[0][1:] if plain else None)


def raid_score(sp, best, gm):
    """How good a raid attacker the species is with its best set: (DPS^3 x TDO)^(1/4), the usual
    blend of damage and staying power. Returns (score, type of the charged move)."""
    dps = raid_dps(sp, best[0], best[1], gm)
    hp = (sp["sta"] + 15) * CPM_40
    dfn = (sp["def"] + 15) * CPM_40
    tdo = dps * hp / (900.0 / dfn)
    return (dps ** 3 * tdo) ** 0.25, gm.moves[best[1]]["type"]


def tag(move_id, sp, gm, alt=None):
    """Display name with the markers the page already understands."""
    bare = move_id
    name = gm.display(move_id)
    if bare in sp["special"]:
        name += " (Special)"
    elif bare in sp["elite_fast"] or bare in sp["elite_charged"]:
        name += " (Elite)"
    if alt and alt != move_id and "(" in name:
        name += f" [Alt: {gm.display(alt)}]"
    return name


def pvpoke_index(pvpoke):
    by_dex = {}
    for p in pvpoke["pokemon"]:
        sid = p["speciesId"]
        if sid.endswith("_shadow") or "_mega" in sid or sid.endswith("_primal"):
            continue
        by_dex.setdefault(p["dex"], []).append(sid)
    return by_dex


def pvpoke_id(by_dex, dex, app_name):
    cands = by_dex.get(dex, [])
    if not cands:
        return None
    if len(cands) == 1:
        return cands[0]
    base = min(cands, key=len)
    m = re.search(r"\((.+)\)$", app_name)
    form = (m.group(1) if m else ("" if "_" not in app_name else app_name.split("_", 1)[1])).lower()
    if not form:
        plain = [c for c in cands if "_" not in c[len(base.split("_")[0]):]]
        return plain[0] if plain else base
    words = [REGION_WORDS.get(w, w) for w in re.split(r"[^a-z0-9]+", form) if w]
    best, best_hits = None, 0
    for c in cands:
        suffix = c.split("_")[1:] if "_" in c else []
        joined = "".join(suffix)
        hits = sum(1 for w in words if w in suffix or w in joined)
        # Most form words matched; on a tie the shorter id ("(Standard)" is darmanitan_standard).
        if hits > best_hits or (hits == best_hits and hits and len(c) < len(best)):
            best, best_hits = c, hits
    return best


def to_fast_id(pvpoke_move):          # PvPoke drops the game's _FAST suffix
    return pvpoke_move + "_FAST"


def main():
    args = set(sys.argv[1:])
    offline, report_only = "--offline" in args, "--report" in args
    raw = load("game_master.json", offline)
    pvpoke = load("pvpoke_gamemaster.json", offline)
    ranks = {lg: load(f"rank_{lg}.json", offline) for lg in ("gl", "ul", "ml")}
    gm = GameMaster(raw, pvpoke)
    by_dex = pvpoke_index(pvpoke)
    rank_of = {lg: {r["speciesId"]: (i + 1, r) for i, r in enumerate(rows)} for lg, rows in ranks.items()}

    with open(PAGE, encoding="utf-8", newline="") as f:
        html = f.read()
    m = re.search(r"const POKEMON_DATA = (\[.*?\]);(\r?\n)", html, flags=re.S)
    data = json.loads(m.group(1))

    changed_pve = changed_pvp = no_gm = no_pvp = 0
    raid_scores = []
    examples = []
    for p in data:
        sp = gm.find(p["id"], p["name"])
        if not sp:
            no_gm += 1
            print("  not in the game master:", p["name"])
            continue
        elite = set(sp["elite_fast"] + sp["elite_charged"])
        p["bs"] = [sp["atk"], sp["def"], sp["sta"]]     # base stats: exact CP maths in the page
        p["mv"] = {
            "f": [gm.display(x) for x in sp["fast"]],
            "c": [gm.display(x) for x in sp["charged"]],
            "e": [gm.display(x) for x in sp["elite_fast"] + sp["elite_charged"]],
            "x": [gm.display(x) for x in sp["special"]],
        }
        # ---- raids
        best, plain = best_raid(sp, gm)
        if best:
            score, mtype = raid_score(sp, best, gm)
            raid_scores.append((score, mtype, p["id"], round(score, 3), p))
            q = tag(best[0], sp, gm, plain[0] if plain else None)
            c = tag(best[1], sp, gm, plain[1] if plain else None)
            if (q, c) != (p.get("pve_q"), p.get("pve_c")):
                changed_pve += 1
                if len(examples) < 12 and p.get("pve_n", 9999) <= 150:
                    examples.append(f"  PvE {p['name']}: {p.get('pve_q')} + {p.get('pve_c')}  ->  {q} + {c}")
            p["pve_q"], p["pve_c"] = q, c
        # ---- leagues
        sid = pvpoke_id(by_dex, p["id"], p["name"])
        leagues = {}
        for lg in ("gl", "ul", "ml"):
            hit = rank_of[lg].get(sid) if sid else None
            if not hit:
                continue
            rank, row = hit
            fast = to_fast_id(row["moveset"][0])
            charged = row["moveset"][1:]
            usage = [x["moveId"] for x in sorted(row["moves"]["chargedMoves"], key=lambda x: -(x.get("uses") or 0))]
            fast_usage = [to_fast_id(x["moveId"]) for x in sorted(row["moves"]["fastMoves"], key=lambda x: -(x.get("uses") or 0))]
            alt_fast = next((x for x in fast_usage if x not in elite), None)
            alt_charged = next((x for x in usage if x not in elite and x not in sp["special"] and x not in charged), None)
            leagues[lg] = [rank, tag(fast, sp, gm, alt_fast)] + [tag(c, sp, gm, alt_charged) for c in charged]
        if leagues:
            p["lg"] = leagues
            best_lg = min(leagues, key=lambda k: leagues[k][0])
            q, c = leagues[best_lg][1], ", ".join(leagues[best_lg][2:])
            if (q, c) != (p.get("pvp_q"), p.get("pvp_c")):
                changed_pvp += 1
            p["pvp_q"], p["pvp_c"] = q, c
        else:
            p.pop("lg", None)
            no_pvp += 1

    # ---- raid ranks: overall (rr) and among attackers of the same charged-move type (rt).
    # Cosmetic forms share one place (same dex + same score), so 20 Vivillon patterns count once.
    for p in data:
        p.pop("rr", None); p.pop("rt", None); p.pop("rty", None)
    raid_scores.sort(key=lambda x: -x[0])
    seen, place, per_type_seen, per_type_place = {}, 0, {}, {}
    for score, mtype, dex, key, p in raid_scores:
        k = (dex, key)
        if k not in seen:
            place += 1
            seen[k] = place
        p["rr"] = seen[k]
        tk = (mtype, dex, key)
        if tk not in per_type_seen:
            per_type_place[mtype] = per_type_place.get(mtype, 0) + 1
            per_type_seen[tk] = per_type_place[mtype]
        p["rt"] = per_type_seen[tk]
        p["rty"] = mtype.capitalize()
    top = [x[4]["name"] for x in raid_scores[:12]]
    print("top raid attackers by this measure:", ", ".join(top))

    print(f"{len(data)} species: raid moveset changed for {changed_pve}, league moveset changed for {changed_pvp}")
    print(f"not in the game master: {no_gm}; not ranked in any league: {no_pvp}")
    print("\n".join(examples))
    if report_only:
        return
    new = "const POKEMON_DATA = " + json.dumps(data, ensure_ascii=False) + ";" + m.group(2)
    with open(PAGE, "w", encoding="utf-8", newline="") as f:
        f.write(html[:m.start()] + new + html[m.end():])
    print("index.html updated")


if __name__ == "__main__":
    main()
