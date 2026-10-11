# Upstream watch: what can change under the app, and how we hear about it

Three listeners cover three kinds of change. None of them involve the phone: the app still sends
nothing anywhere.

## 1. Game data (weekly, automatic)

`.github/workflows/watch-upstream.yml` runs `tools/watch_upstream.py` every Monday and opens or
updates the issue **"Upstream Pokémon GO data changed"** when any of these moved since the
recorded baseline (`tools/upstream.json`):

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

## 2. Game mechanics we hold as constants (manual, on the issue's checklist)

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

## 3. Screen layout (on the phone, automatic)

The scanner reads fixed regions of the storage page (`SCAN_REGIONS`, the AUTO fingerprint zones,
the pixel detectors). A client update that moves the layout breaks reads silently, so the app
counts its own failures: when most recent storage reads find the white card but no CP or species,
the pill says **READS FAILING · GAME UPDATE? CHECK FOR AN APP UPDATE** and My Log shows the same
notice (with the counts) and includes it in the diagnostics file. That is the signal to take fresh
screenshots and re-calibrate the regions.
