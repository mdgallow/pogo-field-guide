# Upstream watch: what can change under the app, and how we hear about it

Three listeners cover three kinds of change. None of them involve the phone: the app still sends
nothing anywhere.

## 0. Compliance

Every listener reads **public files hosted on GitHub by third parties**: PokeMiners' game-master
mirror, PvPoke (MIT), the pokemon-go-api sprite repo, and ScrapedDuck, which mirrors LeekDuck's
event calendar with LeekDuck's permission and asks only for credit (given in README) and that the
app is free and ad-free (it is). The watcher never contacts Niantic / Scopely servers, their
websites or the game; it does not crawl, log in, or automate anything of theirs. It runs in CI,
twice a week, one request per file. The app on the phone takes no part in any of this: it only
ever contacts our own site for `version.json`. So nothing here can count as accessing the game
"through automation", which is the clause the game's terms care about.

## 1. Announcements (twice weekly, automatic) → prepare, do not release

The same workflow reads the community event calendar and opens or updates the issue
**"Prepare: upcoming Pokémon GO changes"** for events starting within 28 days that can affect
the app, each with what to prepare. This is working material, not a release trigger:

| Announced | Prepare ahead |
|---|---|
| Community Day | the exclusive (Elite) move of the featured line, its shiny palette, and the flood of that species in logs |
| Raid Day / raid rotation | the boss and its shiny: fixed-level perfect check, role and moves, dataset row if it is a debut |
| Season start | a move rebalance and GBL reset usually follow: plan a `build_moves.py` run for the day the data lands |
| GO Tour / GO Fest / Wild Area / research | debuts and new forms: add dataset rows (name, dex, types, `cps`) before they appear |
| Max Battles / Max Mondays | Dynamax species for the Dynamax pool |

**Maintenance cadence: one release, aligned with the game.** Users must not feel they are
patching all the time, so nothing ships ahead of the game. When an announcement is confirmed and
carries enough detail, the preparation is done on the branch (dataset rows, elite-move markers,
palettes, any rule change the event implies) with the assumptions written on the issue, and it
stays unreleased. When the game data actually moves (§2), everything is regenerated from the real
data, each assumption is confirmed or corrected, and the prepared work and the true-up go out as
**one bundled release** on or just after the day the change is live in the game. Only a verdict
rule that is wrong today (a bug) is released on its own.

## 2. Game data (twice weekly, automatic) → true-up

`.github/workflows/watch-upstream.yml` runs `tools/watch_upstream.py` on Mondays and Thursdays
and opens or updates the issue **"Release: Pokémon GO data changed"** when any of these moved
since the recorded baseline (`tools/upstream.json`). This is the release trigger: bundle the
prepared work with the regenerated data and ship once.

| Source | What a change means | Refresh |
|---|---|---|
| Game master timestamp (PokeMiners) | a client or data release; the storage page layout may have moved | re-check SCAN on a phone (see §3) |
| Species / forms / base stats | new Pokémon, new regional forms, rebalanced stats | `tools/build_moves.py` (also rebuilds `bs`, ranks, roles) |
| Moves (power / energy / duration) | move rebalance; raid movesets and PvP sets shift | `tools/build_moves.py` |
| Evolution lines / candy costs | new evolutions, cheaper or dearer evolutions | `tools/build_moves.py` (`ec`, `ecr`, `te`) |
| Purification costs | shadow verdicts | `tools/build_moves.py` (`pd`, `pc`) |
| PvPoke timestamp + league rankings | league movesets, PVP roles, cap-level values | `tools/build_moves.py` |
| Icon assets commit | new sprites, shiny palettes | `tools/build_palettes.py` |

After refreshing: `node tests/run.js`, a release with `tools/release.py`, then
`python tools/watch_upstream.py --update` to record the new baseline, and close the issue.

The new species list is also the cue to extend the dataset itself: `build_moves.py` only updates
species already in `POKEMON_DATA`; a brand-new Pokémon needs a row (name, dex id, types, region,
`cps` table from `cpOf`, icon URL) before the generators can fill the rest.

## 3. Game mechanics we hold as constants (manual, on the issue's checklist)

These are not in any feed; when the game changes them, edit the constant and run the tests:

| Constant | Where | Source of truth |
|---|---|---|
| `CPM` per level | `index.html` | game master `playerLevel.cpMultiplier` (watch script flags a game-master change) |
| `DUST_PER_POWERUP`, `candyPerPowerUp` | `index.html` | game master `pokemonUpgrades` |
| `LUCKY_ODDS` by age | `index.html` | community research; placeholder 5/10/15/20 % |
| `CANDY_DUST` (dust per candy), trade ticket values, `TRADE_STACK` | `index.html` | our model; revisit if candy or trade rules change |
| `BASE_CATCH_RATES` snapshot + `estimateBaseCatchRate` | `index.html` | no longer published in the game master; refresh from a community dump when one appears |
| `RAID_CATCH_LEVELS` (20/25/15), raid IV floor 10 | `index.html` | game rules |
| Ball thresholds (`POKE_BALL_MIN`, `GREAT_BALL_MIN`), ring colour bands | `index.html` | catch-ring colour research |
| Shadow bonus (+20% / x1.2 cost), purified +2 IV, floors | `index.html` | game rules |
| Elite / event move markers | generator output | game master `eliteQuickMove` / `eliteCinematicMove` / `nonTmCinematicMoves` |
| Shinies released (`sh`) | `tools/shiny_released.json` → `tools/build_shiny.py` | Serebii's shiny list, snapshot by hand when a shiny debuts (Community Day / Raid Day notices are the cue) |

## 4. Screen layout (on the phone, automatic)

The scanner reads fixed regions of the storage page (`SCAN_REGIONS`, the AUTO fingerprint zones,
the pixel detectors). A client update that moves the layout breaks reads silently, so the app
counts its own failures: when most recent storage reads find the white card but no CP or species,
the pill says **READS FAILING · GAME UPDATE? CHECK FOR AN APP UPDATE** and My Log shows the same
notice (with the counts) and includes it in the diagnostics file. That is the signal to take fresh
screenshots and re-calibrate the regions.
