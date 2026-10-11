#!/usr/bin/env python3
"""Upstream change watcher: tells us when Pokémon GO data moved so the app can be refreshed.

    python tools/watch_upstream.py            compare the live sources with tools/upstream.json, print a report
    python tools/watch_upstream.py --update   also record the live state as the new baseline (after refreshing the app)

Exit code 3 when something changed (CI opens / updates the issues), 0 when nothing did. Two
reports: UPCOMING (announced events: prepare on the branch, no release) and CONFIRMED (data that
actually moved in the game: bundle the prepared work and release once).

Where the data comes from, and why none of it can get the app flagged: every source is a public
file on GitHub maintained by a third party (PokeMiners' game-master mirror, PvPoke under MIT, the
pokemon-go-api sprite repo, ScrapedDuck which mirrors LeekDuck's event calendar with permission
and asks for credit, which README gives). Nothing here talks to Niantic / Scopely servers or to
the game, nothing runs on a phone, and the app itself only ever contacts our own site.

What is watched
  * Game master (PokeMiners): its timestamp, every species/form with stats (new Pokémon, new
    forms, stat rebalances), every move (new moves, power/energy changes), evolution branches
    (new evolutions, candy costs), purification costs. Any change here can move the raid
    movesets, trade values and shiny palettes -> run tools/build_moves.py (and build_palettes.py).
  * PvPoke: gamemaster timestamp and the three league rankings -> league movesets and PVP roles.
  * Icon assets (pokemon-go-api/assets): latest commit -> new sprites / shiny palettes.
A game-master timestamp bump without data changes usually means a client update: that is the
cue to re-check the screen layout (SCAN_REGIONS / fingerprint zones) on a phone.
"""
import hashlib
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATE = Path(__file__).resolve().parent / "upstream.json"
SOURCES = {
    "gm_timestamp": "https://raw.githubusercontent.com/PokeMiners/game_masters/master/latest/timestamp.txt",
    "gm": "https://raw.githubusercontent.com/PokeMiners/game_masters/master/latest/latest.json",
    "pvpoke": "https://raw.githubusercontent.com/pvpoke/pvpoke/master/src/data/gamemaster.json",
    "rank_gl": "https://raw.githubusercontent.com/pvpoke/pvpoke/master/src/data/rankings/all/overall/rankings-1500.json",
    "rank_ul": "https://raw.githubusercontent.com/pvpoke/pvpoke/master/src/data/rankings/all/overall/rankings-2500.json",
    "rank_ml": "https://raw.githubusercontent.com/pvpoke/pvpoke/master/src/data/rankings/all/overall/rankings-10000.json",
    "icons_commit": "https://api.github.com/repos/pokemon-go-api/assets/commits?per_page=1",
    # Community event calendar (LeekDuck via ScrapedDuck, credited in README): announcements ahead of time.
    "events": "https://raw.githubusercontent.com/bigfoott/ScrapedDuck/data/events.json",
}
LOOKAHEAD_DAYS = 28
# What each kind of announced event may change, and what can be patched BEFORE it lands.
PREPATCH = {
    "community-day": "exclusive (Elite) move for the featured line: mark it in the data if the game master "
                     "does not yet; shiny palette for the species; expect many of them in logs (keep slots)",
    "raid-day": "raid boss and its shiny: raid catch odds (fixed levels), shiny palette, role/moves",
    "raid-battles": "boss rotation: legendary raid catches use the fixed-level perfect check; confirm the "
                    "species row exists (new debuts) and its role/moves",
    "season": "a season usually brings a move rebalance and a GBL reset: re-run build_moves.py once the "
              "game master and PvPoke move; until then verdicts rest on last season's data",
    "go-battle-league": "league rotation: PVP roles use overall rankings, so nothing to patch unless a new "
                        "cup becomes the main format",
    "pokemon-go-tour": "debuts and regional-form releases: add dataset rows (name, dex, types, cps) ahead",
    "event": "watch the text for debuts / new forms: add dataset rows ahead",
    "max-battles": "Dynamax / Gigantamax species: the Dynamax pool and badge detector",
    "max-mondays": "Dynamax species of the week: Dynamax pool",
    "research": "special research debuts (often new species / mythicals): dataset rows",
    "wild-area": "mass debuts and shinies: dataset rows and palettes",
}


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "pogo-companion-watch"})
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def digest(b):
    return hashlib.sha256(b).hexdigest()[:16]


def snapshot():
    gm = json.loads(get(SOURCES["gm"]))
    species, moves, evolutions, purify = {}, {}, {}, {}
    for item in gm:
        d = item.get("data", {})
        ps = d.get("pokemonSettings")
        ms = d.get("moveSettings")
        if ps and "stats" in ps and "baseAttack" in ps["stats"]:
            tid = item["templateId"]
            st = ps["stats"]
            species[tid] = f"{st.get('baseAttack')}/{st.get('baseDefense')}/{st.get('baseStamina')}"
            evolutions[tid] = sorted(f"{b.get('evolution')}:{b.get('candyCost', 0)}" for b in ps.get("evolutionBranch", []) or [] if "evolution" in b)
            sh = ps.get("shadow") or {}
            if sh:
                purify[tid] = f"{sh.get('purificationStardustNeeded')}/{sh.get('purificationCandyNeeded')}"
        if ms:
            moves[item["templateId"]] = f"{ms.get('power', 0)}|{ms.get('energyDelta', 0)}|{ms.get('durationMs', 0)}"
    pvpoke = json.loads(get(SOURCES["pvpoke"]))
    icons = json.loads(get(SOURCES["icons_commit"]))
    return {
        "gm_timestamp": get(SOURCES["gm_timestamp"]).decode().strip(),
        "species": species, "moves": moves, "evolutions": evolutions, "purify": purify,
        "pvpoke_timestamp": pvpoke.get("timestamp"),
        "rank_gl": digest(get(SOURCES["rank_gl"])), "rank_ul": digest(get(SOURCES["rank_ul"])), "rank_ml": digest(get(SOURCES["rank_ml"])),
        "icons_commit": icons[0]["sha"][:12] if icons else None,
        "icons_date": icons[0]["commit"]["committer"]["date"] if icons else None,
    }


def upcoming(seen_ids):
    """Announced events starting within LOOKAHEAD_DAYS that we have not reported before."""
    import datetime as dt
    try:
        events = json.loads(get(SOURCES["events"]))
    except Exception as e:
        return [f"- (event calendar unavailable: {e})"], seen_ids
    now = dt.datetime.utcnow()
    horizon = now + dt.timedelta(days=LOOKAHEAD_DAYS)
    lines, ids = [], list(seen_ids)
    for ev in sorted(events, key=lambda x: x.get("start") or ""):
        try:
            start = dt.datetime.fromisoformat((ev.get("start") or "").replace("Z", ""))
        except ValueError:
            continue
        if start > horizon or start < now - dt.timedelta(days=1) or ev.get("eventID") in seen_ids:
            continue       # only what is still ahead (or started today) and not yet reported
        kind = ev.get("eventType", "event")
        if kind not in PREPATCH:
            continue
        extra = ev.get("extraData") or {}
        names = []
        for block in extra.values():
            if isinstance(block, dict):
                for key in ("spawns", "bosses", "shinies"):
                    names += [x.get("name") for x in block.get(key, []) if isinstance(x, dict) and x.get("name")]
        who = (" — " + ", ".join(dict.fromkeys(names))) if names else ""
        lines.append(f"- {start.date()} {kind}: {ev.get('name')}{who}\n    prepare: {PREPATCH[kind]}\n    {ev.get('link', '')}")
        ids.append(ev.get("eventID"))
    return lines, ids


def diff_map(old, new, label, show=12):
    added = sorted(k for k in new if k not in old)
    removed = sorted(k for k in old if k not in new)
    changed = sorted(k for k in new if k in old and old[k] != new[k])
    lines = []
    if added:
        lines.append(f"- {len(added)} new {label}: " + ", ".join(added[:show]) + (" …" if len(added) > show else ""))
    if changed:
        lines.append(f"- {len(changed)} changed {label}: " + ", ".join(changed[:show]) + (" …" if len(changed) > show else ""))
    if removed:
        lines.append(f"- {len(removed)} removed {label}: " + ", ".join(removed[:show]) + (" …" if len(removed) > show else ""))
    return lines


def main():
    update = "--update" in sys.argv
    live = snapshot()
    old = json.loads(STATE.read_text(encoding="utf-8")) if STATE.exists() else {}
    report = []
    if old.get("gm_timestamp") != live["gm_timestamp"]:
        report.append(f"- Game master timestamp {old.get('gm_timestamp')} -> {live['gm_timestamp']} (a client/data release: re-check the storage page layout on a phone)")
    report += diff_map(old.get("species", {}), live["species"], "species/forms or base stats")
    report += diff_map(old.get("moves", {}), live["moves"], "moves (power/energy/duration)")
    report += diff_map(old.get("evolutions", {}), live["evolutions"], "evolution lines / candy costs")
    report += diff_map(old.get("purify", {}), live["purify"], "purification costs")
    if old.get("pvpoke_timestamp") != live["pvpoke_timestamp"]:
        report.append(f"- PvPoke data {old.get('pvpoke_timestamp')} -> {live['pvpoke_timestamp']}")
    for lg in ("rank_gl", "rank_ul", "rank_ml"):
        if old.get(lg) != live[lg]:
            report.append(f"- PvPoke {lg[-2:].upper()} rankings changed (league movesets / PVP roles)")
    if old.get("icons_commit") != live["icons_commit"]:
        report.append(f"- Icon assets updated ({live['icons_date']}): new sprites, re-run build_palettes.py")

    coming, seen = upcoming(old.get("events_seen", []))
    live["events_seen"] = seen[-400:]

    if coming:
        print("UPCOMING (prepare on the branch; release only when the game data moves)\n" + "\n".join(coming) + "\n")
    if report:
        print("CONFIRMED CHANGES (in the game now: bundle the prepared work and release once)\n" + "\n".join(report))
        print("\nTo refresh: python tools/build_moves.py && python tools/build_palettes.py --offline, re-run the tests, "
              "check the constants in docs/UPSTREAM-WATCH.md, then python tools/watch_upstream.py --update and release.")
    if not coming and not report:
        print("No upstream changes since the last baseline.")
    if update:
        STATE.write_text(json.dumps(live, indent=0, sort_keys=True), encoding="utf-8")
        print("baseline recorded in tools/upstream.json")
    sys.exit(3 if (report or coming) and not update else 0)


if __name__ == "__main__":
    main()
