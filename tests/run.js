// Shared behaviour tests: the same scan payloads every native shell (Android today, iOS later)
// sends to the page, and the pill state the page must answer with.
//
//   node tests/run.js            run everything
//   node tests/run.js -v         also print each pill state
//
// A payload is what FloatingOverlayService builds: OCR lines as fractions of the frame plus the
// pixel-detector results. card() lays the lines out where Pokémon GO draws them.
const { loadApp } = require('./harness');
const verbose = process.argv.includes('-v');

const line = (t, x, y, w = 0.3, h = 0.03) => ({ t, x, y, w, h });

/** A storage page with the appraisal panel open (the view AUTO sweeps). */
function card({ cp, name, species = name, kg, m, types, ivs = null, date = '8/4/2026', place = 'Rosemount',
  lucky = false, favorite = false, shadow = false, dynamax = false, auto = false }) {
  const lines = [line(`CP${cp}`, 0.34, 0.05, 0.26, 0.04), line(name, 0.36, 0.41, 0.28, 0.035)];
  if (lucky) lines.push(line('LUCKY POKÉMON', 0.38, 0.455, 0.24, 0.02));
  if (kg != null) lines.push(line(`${kg}kg`, 0.12, 0.545, 0.15, 0.02));
  if (m != null) lines.push(line(`${m}m`, 0.75, 0.545, 0.1, 0.02));
  if (types) lines.push(line(types, 0.38, 0.6, 0.25, 0.02));
  lines.push(line('Attack', 0.12, 0.74, 0.1, 0.02));
  lines.push(line(`This ${species} was caught on ${date} around ${place}.`, 0.02, 0.905, 0.9, 0.03));
  return { storage: true, favorite, ivs, auto, shadow, dynamax, lines };
}
const catchScreen = (name, cp) => ({ storage: false, lines: [line(`CP ${cp}`, 0.4, 0.25, 0.2, 0.04), line(name, 0.35, 0.31, 0.3, 0.04)] });
const notTheGame = () => ({ storage: false, lines: [line('SHOP', 0.4, 0.7, 0.2, 0.04)] });

const slot = (state, caption) => (state.slots.find(s => s.caption === caption) || {}).value || '';
const tests = [];
const test = (name, fn) => tests.push({ name, fn });
function check(cond, message) { if (!cond) throw new Error(message); }
const eq = (got, want, what) => check(got === want, `${what}: expected ${JSON.stringify(want)}, got ${JSON.stringify(got)}`);
const has = (got, want, what) => check(String(got).includes(want), `${what}: expected to contain ${JSON.stringify(want)}, got ${JSON.stringify(got)}`);

// ---------------------------------------------------------------- screens
test('catch screen: species, CP, berry and ball advice', async t => {
  await t.app.scan(catchScreen('Bulbasaur', 637));
  const s = t.last();
  eq(s.mode, 'catch', 'mode'); eq(s.target.join(' '), 'Bulbasaur 637 CP', 'target');
  check(slot(s, 'BERRY') && slot(s, 'BALL'), 'berry and ball slots are filled');
  has(slot(s, 'IV CHECK'), '100%', 'IV check for a hundo CP');
  eq(t.app.log().length, 0, 'catch screens are not logged');
});

test('anything that is not a Pokémon page: standby, nothing logged', async t => {
  await t.app.scan(notTheGame());
  eq(t.last().mode, 'STANDBY', 'mode'); eq(t.app.log().length, 0, 'log size');
});

test('catch: the ball follows the ring colour (Poké on green/yellow, Great on orange, Ultra on red)', async t => {
  const ball = (name, cp) => require('vm').runInContext(`(() => { const p = POKEMON_DATA.find(x => x.name === ${JSON.stringify(name)}); const b = pickBall(p, ${cp}); return b.text + ' | ' + Math.round(b.chance * 100); })()`, t.ctx);
  has(ball('Pidgey', 60), 'POKÉ BALL', 'low-level common');
  has(ball('Pidgey', 60), 'GREEN', 'ring named');
  has(ball('Charmander', 700), 'ULTRA BALL', 'high-level starter');
  check(/GREAT|ULTRA/.test(ball('Pidgey', 700)), `a near-max common is not a Poké Ball catch (${ball('Pidgey', 700)})`);
  has(ball('Pidgey', 0), 'GREEN RING: POKÉ', 'no CP read: follow the ring');
});

test('catch: CP-only IV checks state their odds instead of "maybe"', async t => {
  const run = code => require('vm').runInContext(code, t.ctx);
  // The CP maths reproduces the game's own perfect-CP table.
  eq(run(`cpOf(POKEMON_DATA.find(x => x.name === 'Bulbasaur').bs, 20, 15, 15, 15)`), 637, 'Bulbasaur hundo at L20 is 637');
  await t.app.scan(catchScreen('Bulbasaur', 637));
  const wild = slot(t.last(), 'IV CHECK');
  check(/1 IN \d+/.test(wild) && !/MAYBE/.test(wild), `wild hundo CP gives odds, not "maybe" (${wild})`);
  await t.app.scan(catchScreen('Bulbasaur', 300));
  const other = slot(t.last(), 'IV CHECK');
  check(!/100%/.test(other) || /1 IN/.test(other), `a CP with no hundo combination never claims 100% (${other})`);
  const o = JSON.parse(run(`JSON.stringify(perfectOdds(POKEMON_DATA.find(x => x.name === 'Mewtwo'), POKEMON_DATA.find(x => x.name === 'Mewtwo').cps[19], true))`));
  check(o.perfect >= 1, 'raid hundo CP contains the perfect combination');
});

test('raid boss: SAVE MASTER BALL, or PERFECT! USE MASTER BALL', async t => {
  const advice = (cp) => require('vm').runInContext(`(() => { const p = POKEMON_DATA.find(x => x.name === 'Mewtwo'); return getBallAdvice(p, ${cp}, evaluateCpMatch(p, ${cp})).text; })()`, t.ctx);
  const perfect = require('vm').runInContext(`POKEMON_DATA.find(x => x.name === 'Mewtwo').cps[19]`, t.ctx);   // level 20 = raid catch
  eq(advice(perfect), 'PERFECT! USE MASTER BALL', 'perfect raid CP');
  eq(advice(perfect - 40), 'SAVE MASTER BALL', 'anything else');
});

// ---------------------------------------------------------------- verdicts
test('perfect IVs: KEEP, logged once', async t => {
  await t.app.scan(card({ cp: 3000, name: 'Charizard', kg: 90.5, m: 1.7, types: 'FIRE / FLYING', ivs: [15, 15, 15] }));
  const s = t.last();
  eq(slot(s, 'VERDICT'), 'KEEP', 'verdict'); has(slot(s, 'IV CHECK'), '15/15/15', 'IVs'); has(slot(s, 'REASON'), 'PERFECT', 'reason');
  eq(s.actions.length, 0, 'no GONE button on a keeper'); eq(t.app.log().length, 1, 'log size');
});

test('below the IV floor: not kept, GONE offered, pill line fits', async t => {
  await t.app.scan(card({ cp: 266, name: 'Charmander', kg: 8.5, m: 0.6, types: 'FIRE', ivs: [10, 12, 9] }));
  const s = t.last();
  eq(slot(s, 'VERDICT'), 'TRADE', 'a 31-IV Charmander is a trade (meta family, empty keeper slots)');
  check(!slot(s, 'REASON').includes('FLOOR'), 'a trade line is about trade value, not the IVs that will re-roll');
  check(slot(s, 'REASON').length <= 40, `reason fits the pill (${slot(s, 'REASON').length} chars)`);
  eq(s.actions.join(), 'gone', 'GONE button offered');
});

test('GONE: entry leaves the ranking and the pill confirms', async t => {
  await t.app.scan(card({ cp: 266, name: 'Charmander', kg: 8.5, m: 0.6, types: 'FIRE', ivs: [10, 12, 9] }));
  t.app.action('gone');
  eq(t.app.log()[0].gone, true, 'entry.gone'); has(t.last().slots[0].value, 'GONE', 'pill confirmation');
  await t.app.scan(card({ cp: 266, name: 'Charmander', kg: 8.5, m: 0.6, types: 'FIRE', ivs: [10, 12, 9] }));
  eq(t.app.log()[0].gone, false, 'seen again: restored'); eq(t.app.log().length, 1, 'still one entry');
});

test('lucky: needs the label, and counts above its raw IVs', async t => {
  await t.app.scan(card({ cp: 2900, name: 'Giratina', kg: 750, m: 4.5, types: 'GHOST / DRAGON', ivs: [12, 12, 13], lucky: true }));
  has(slot(t.last(), 'REASON'), 'LUCKY', 'reason mentions lucky'); eq(t.app.log()[0].lucky, true, 'logged as lucky');
  const u = loadApp();
  await u.app.scan(card({ cp: 2900, name: 'Giratina', kg: 750, m: 4.5, types: 'GHOST / DRAGON', ivs: [12, 12, 13] }));
  check(!u.app.log()[0].lucky, 'no label, not lucky');
});

test('lucky: a 32-IV lucky clears the floor that a plain 32 fails', async t => {
  const dragonite = extra => card({ cp: 2400, name: 'Dragonite', kg: 210, m: 2.2, types: 'DRAGON / FLYING', ivs: [11, 10, 11], ...extra });
  await t.app.scan(dragonite({}));
  eq(slot(t.last(), 'VERDICT'), 'TRADE', 'plain 32 is below the floor: not kept');
  const u = loadApp();
  await u.app.scan(dragonite({ lucky: true }));
  const lucky = slot(u.last(), 'REASON');
  check(!lucky.includes('BELOW'), `lucky 32 counts as 40 and is not below the floor (got ${lucky})`);
  has(lucky, '≈40', 'pill shows the lucky-equivalent total');
});

test('trade evolution: says the trade evolves it for free', async t => {
  await t.app.scan(card({ cp: 900, name: 'Machoke', kg: 70.5, m: 1.5, types: 'FIGHTING', ivs: [8, 9, 10] }));
  const s = t.last();
  eq(slot(s, 'VERDICT'), 'TRADE', 'verdict'); has(slot(s, 'REASON'), 'FREE EVO', 'reason');
});

test('favorite star does not force a KEEP', async t => {
  await t.app.scan(card({ cp: 266, name: 'Charmander', kg: 8.5, m: 0.6, types: 'FIRE', ivs: [3, 4, 5], favorite: true }));
  check(slot(t.last(), 'VERDICT') !== 'KEEP', 'a favorited 12-IV Charmander is still not a KEEP');
});

// ---------------------------------------------------------------- keeper slots
// A legendary catch at ~level 20-25; CP scales with the IVs so every card is a different Pokémon.
const lunala = (ivs, i) => card({ cp: 2200 + ivs[0] + ivs[1] + ivs[2] + i, name: 'Lunala', kg: 120 + i, m: 4, types: 'PSYCHIC / GHOST', ivs, date: `9/${10 + i}/2026` });

test('keeper slots: the best of a raid/PvP species are KEEP, whatever its old "trade" tag says', async t => {
  const ivSets = [[14, 14, 14], [15, 13, 13], [13, 13, 14], [12, 13, 14], [12, 12, 14], [13, 12, 12], [11, 13, 12], [12, 12, 11], [10, 13, 12], [11, 11, 12], [10, 12, 11], [10, 10, 12]];
  for (let i = 0; i < ivSets.length; i++) await t.app.scan(lunala(ivSets[i], i));
  eq(t.app.log().length, 12, 'twelve Lunala logged');
  await t.app.scan(lunala(ivSets[0], 0));
  let s = t.last();
  eq(slot(s, 'VERDICT'), 'KEEP', 'the 42 is a keeper'); has(slot(s, 'REASON'), '#1/12', 'ranked first');
  check(slot(s, 'ROLE') && movesSlots(s).length >= 1, 'a keeper gets its role and moves');
  await t.app.scan(lunala(ivSets[5], 5));
  eq(slot(t.last(), 'VERDICT'), 'KEEP', '#6 still holds a slot');
  await t.app.scan(lunala(ivSets[6], 6));
  eq(slot(t.last(), 'VERDICT'), 'TRADE', '#7 is outside the six slots'); has(slot(t.last(), 'REASON'), 'LEGENDARY', 'traded as a legendary');
});

test('keeper slots: a better one scanned after six takes the slot; the old #6 becomes a trade', async t => {
  const machamp = (ivs, i, cp) => card({ cp, name: 'Machamp', kg: 130 + i, m: 1.6, types: 'FIGHTING', ivs, date: `9/${10 + i}/2026` });
  const sets = [[15, 14, 13], [14, 14, 13], [14, 13, 13], [13, 13, 13], [13, 13, 12], [13, 12, 12]];
  for (let i = 0; i < sets.length; i++) await t.app.scan(machamp(sets[i], i, 2500 + i * 20));
  has(slot(t.last(), 'REASON'), '#6/6', 'the sixth is #6 of 6');
  await t.app.scan(machamp([15, 15, 13], 9, 2800));
  eq(slot(t.last(), 'VERDICT'), 'KEEP', 'the better seventh is a keeper'); has(slot(t.last(), 'REASON'), '#1/7', 'and ranks first');
  await t.app.scan(machamp(sets[5], 5, 2600));
  check(slot(t.last(), 'VERDICT') !== 'KEEP', `the old #6 lost its slot (got ${slot(t.last(), 'VERDICT')})`); has(slot(t.last(), 'REASON'), '#7/7', 'now #7 of 7');
});

test('keeper slots: a role species scanned without appraisal asks for it instead of the old tag', async t => {
  await t.app.scan(card({ cp: 2290, name: 'Lunala', kg: 130, m: 4, types: 'PSYCHIC / GHOST', ivs: null }));
  eq(slot(t.last(), 'VERDICT'), 'CHECK FIRST', 'verdict'); has(slot(t.last(), 'REASON'), 'APPRAISE', 'asks for the appraisal');
});

test('keeper slots: a single good one of a role species is KEEP too', async t => {
  await t.app.scan(lunala([14, 14, 14], 0));
  eq(slot(t.last(), 'VERDICT'), 'KEEP', 'alone and above the floor');
});

test('keeper slots: a low-use species keeps only its single best', async t => {
  const rat = (ivs, i) => card({ cp: 900 + i * 10, name: 'Raticate', kg: 18 + i * 0.5, m: 0.7, types: 'NORMAL', ivs, date: `9/${10 + i}/2026` });
  await t.app.scan(rat([14, 13, 14], 0)); await t.app.scan(rat([13, 13, 13], 1)); await t.app.scan(rat([13, 12, 13], 2));
  await t.app.scan(rat([14, 13, 14], 0));
  eq(slot(t.last(), 'VERDICT'), 'KEEP BEST', 'the best one is kept'); has(slot(t.last(), 'REASON'), 'LOW USE', 'and says why only one');
  await t.app.scan(rat([13, 13, 13], 1));
  check(['TRADE', 'SURPLUS'].includes(slot(t.last(), 'VERDICT')), `the second-best (39 IV) is not kept (got ${slot(t.last(), 'VERDICT')})`);
});

// ---------------------------------------------------------------- trade value
test('trade value: a high-level common beats a low-level one with better IVs', async t => {
  // Rattata family is common and not a meta pick: only the level makes it worth a trade.
  await t.app.scan(card({ cp: 1300, name: 'Raticate', kg: 18.5, m: 0.7, types: 'NORMAL', ivs: [2, 3, 2] }));
  const high = t.last();
  eq(slot(high, 'VERDICT'), 'TRADE', 'level ~30 with 15% IVs is a trade');
  has(slot(high, 'REASON'), 'SAVES', 'pill says what the receiver saves');
  const u = loadApp();
  await u.app.scan(card({ cp: 100, name: 'Raticate', kg: 18.5, m: 0.7, types: 'NORMAL', ivs: [5, 5, 4] }));
  eq(slot(u.last(), 'VERDICT'), 'SURPLUS', 'level ~2 with 31% IVs is transfer fodder');
  has(slot(u.last(), 'REASON'), 'TRANSFER', 'pill says transfer');
});

test('trade value: rarity lifts every level, and level + rarity goes first', async t => {
  await t.app.scan(card({ cp: 120, name: 'Dratini', kg: 3.3, m: 1.8, types: 'DRAGON', ivs: [5, 5, 4] }));
  eq(slot(t.last(), 'VERDICT'), 'TRADE', 'a low-level meta-family Pokémon is still a trade');
  const u = loadApp();
  await u.app.scan(card({ cp: 2900, name: 'Dragonite', kg: 210, m: 2.2, types: 'DRAGON / FLYING', ivs: [5, 5, 4] }));
  has(slot(u.last(), 'REASON'), 'TRADE 1ST', 'high level + meta family is first in line');
  check(slot(u.last(), 'REASON').length <= 40, `fits the pill (${slot(u.last(), 'REASON').length})`);
});

test('trade value: the IVs being traded away do not change the score', async t => {
  const score = s => (slot(s, 'REASON').match(/TRADE(?: 1ST)? (\d+)/) || [])[1];
  await t.app.scan(card({ cp: 1384, name: 'Raticate', kg: 18.5, m: 0.7, types: 'NORMAL', ivs: [1, 1, 1] }));
  const low = score(t.last());
  const u = loadApp();
  // Both are level 30 (1384 CP at 1/1/1, 1577 CP at 12/12/11): same trade.
  await u.app.scan(card({ cp: 1577, name: 'Raticate', kg: 18.5, m: 0.7, types: 'NORMAL', ivs: [12, 12, 11] }));
  const high = score(u.last());
  check(low && high && Math.abs(low - high) <= 3, `scores match within rounding (got ${low} vs ${high})`);
});

test('trade value: an old catch is worth more (better lucky odds)', async t => {
  const score = s => Number((slot(s, 'REASON').match(/(?:TRADE(?: 1ST)?|VALUE) (\d+)/) || [])[1]);
  await t.app.scan(card({ cp: 700, name: 'Raticate', kg: 18.5, m: 0.7, types: 'NORMAL', ivs: [5, 5, 5], date: '8/4/2026' }));
  const fresh = score(t.last());
  const u = loadApp();
  await u.app.scan(card({ cp: 700, name: 'Raticate', kg: 18.5, m: 0.7, types: 'NORMAL', ivs: [5, 5, 5], date: '8/4/2017' }));
  eq(score(u.last()), fresh + 10, 'a 9-year-old catch scores 10 higher');
});

test('trade value: only the best few of a family are held for trading', async t => {
  // Seven surplus Charmanders, all tradeable on their own; the two lowest levels make room.
  for (let i = 0; i < 7; i++) {
    await t.app.scan(card({ cp: 300 + i * 100, name: 'Charmander', kg: 8 + i * 0.3, m: 0.6, types: 'FIRE', ivs: [5, 5, 4], date: `7/${10 + i}/2026` }));
  }
  eq(t.app.log().length, 7, 'seven different Charmanders');
  await t.app.scan(card({ cp: 300, name: 'Charmander', kg: 8, m: 0.6, types: 'FIRE', ivs: [5, 5, 4], date: '7/10/2026' }));
  eq(slot(t.last(), 'VERDICT'), 'SURPLUS', 'the lowest-level one is transferred');
  has(slot(t.last(), 'REASON'), 'BETTER TRADES', 'and the pill says why');
  await t.app.scan(card({ cp: 900, name: 'Charmander', kg: 9.8, m: 0.6, types: 'FIRE', ivs: [5, 5, 4], date: '7/16/2026' }));
  eq(slot(t.last(), 'VERDICT'), 'TRADE', 'the highest-level one is held for a trade');
});

// ---------------------------------------------------------------- moves (TM advice)
const movesSlots = s => s.slots.filter(x => /MOVES/.test(x.caption));

test('role + moves: a raid-and-PvP pick is labelled BOTH and gets both movesets', async t => {
  await t.app.scan(card({ cp: 3900, name: 'Metagross', kg: 550, m: 1.6, types: 'STEEL / PSYCHIC', ivs: [15, 15, 15] }));
  const s = t.last();
  has(slot(s, 'ROLE'), 'RAID + PVP', 'role');
  const m = movesSlots(s);
  eq(m.length, 2, 'two movesets');
  eq(m[0].caption, 'RAID MOVES', 'raid first');
  eq(m[0].value, 'FAST: BULLET PUNCH · CHARGED: PSYCHIC · BEST: METEOR MASH (ELITE TM)', 'normal-TM pick first, the Elite move as a note');
  has(m[1].caption, 'LEAGUE MOVES', 'league second'); has(m[1].value, ' / ', 'two charged moves');
});

test('role + moves: a PvP-only pick gets only its league set', async t => {
  await t.app.scan(card({ cp: 1490, name: 'Azumarill', kg: 28.5, m: 0.8, types: 'WATER / FAIRY', ivs: [15, 15, 15] }));
  const s = t.last();
  eq(slot(s, 'ROLE'), 'PVP (GREAT)', 'role');
  const m = movesSlots(s);
  eq(m.length, 1, 'one moveset'); eq(m[0].caption, 'GREAT LEAGUE MOVES', 'caption'); has(m[0].value, 'FAST: BUBBLE · CHARGED: ', 'fast and charged named');
});

test('role + moves: a raid-only pick gets only its raid set', async t => {
  await t.app.scan(card({ cp: 3000, name: 'Machamp', kg: 130, m: 1.6, types: 'FIGHTING', ivs: [15, 15, 15] }));
  const s = t.last();
  eq(slot(s, 'ROLE'), 'RAID', 'role');
  const m = movesSlots(s);
  eq(m.length, 1, 'one moveset'); eq(m[0].caption, 'RAID MOVES', 'caption');
});

test('role + moves: the reason no longer contradicts the role', async t => {
  await t.app.scan(card({ cp: 1572, name: 'Marshadow', kg: 33.06, m: 0.85, types: 'FIGHTING / GHOST', ivs: [15, 10, 13] }));
  const s = t.last();
  has(slot(s, 'ROLE'), 'RAID + PVP', 'Marshadow is both');
  check(!/PVP IVs/.test(slot(s, 'REASON')), `reason is not the old "PVP IVs" (${slot(s, 'REASON')})`);
  eq(movesSlots(s).length, 2, 'both movesets');
});

test('role + moves: an unevolved keeper is advised on its final form', async t => {
  await t.app.scan(card({ cp: 900, name: 'Beldum', kg: 95, m: 0.6, types: 'STEEL / PSYCHIC', ivs: [15, 15, 15] }));
  const m = movesSlots(t.last());
  eq(m[0].caption, 'RAID MOVES AS METAGROSS', 'caption names the final form');
});

test('IV line: the total players quote comes first', async t => {
  await t.app.scan(card({ cp: 1572, name: 'Marshadow', kg: 33.06, m: 0.85, types: 'FIGHTING / GHOST', ivs: [15, 10, 13] }));
  eq(slot(t.last(), 'IV CHECK'), '38/45 · 84%\n15/10/13', 'IV text');
});

test('moves: nothing is shown on a Pokémon that is not being kept', async t => {
  await t.app.scan(card({ cp: 266, name: 'Charmander', kg: 8.5, m: 0.6, types: 'FIRE', ivs: [10, 12, 9] }));
  eq(movesSlots(t.last()).length, 0, 'no MOVES slot on a trade'); eq(slot(t.last(), 'ROLE'), '', 'no ROLE slot on a trade');
});

// ---------------------------------------------------------------- reference page filters
test('reference page: the type and region filters return matching species', async t => {
  const run = code => require('vm').runInContext(code, t.ctx);
  const fire = JSON.parse(run(`filters.type = 'Fire'; applyFilters(); JSON.stringify({ n: currentFiltered.length, ok: currentFiltered.every(p => p.types.includes('Fire')), has: currentFiltered.some(p => p.name === 'Charizard') })`));
  check(fire.n > 50 && fire.ok && fire.has, `Fire filter: ${JSON.stringify(fire)}`);
  const both = JSON.parse(run(`filters.type = 'Dragon'; filters.region = 'Kanto'; applyFilters(); JSON.stringify(currentFiltered.map(p => p.name))`));
  check(both.includes('Dragonite') && !both.includes('Charizard'), `Dragon + Kanto: ${both.join(', ')}`);
  const all = run(`filters.type = 'ALL'; filters.region = 'ALL'; applyFilters(); currentFiltered.length`);
  eq(all, 1401, 'no filter shows everything');
  for (const key of ['evoStage', 'tier', 'action', 'berry']) {
    eq(run(`filters.${key}`), 'ALL', `${key} filter starts open`);
  }
});

// ---------------------------------------------------------------- search builder
test('search builder: OR inside a group, AND between groups, ! to exclude', async t => {
  const build = (state, extra) => require('vm').runInContext(`buildSearch(${JSON.stringify(state)}, ${JSON.stringify(extra || {})})`, t.ctx);
  eq(build({ '3*': 1, '4*': 1 }), '3*,4*', 'stars');
  eq(build({ '0attack': 1, '1attack': 1, '4defense': 1, '3hp': 1, '4hp': 1 }), '0attack,1attack&4defense&3hp,4hp', 'IV buckets');
  eq(build({ '4*': 1, shiny: -1, lucky: 1, fire: 1, water: 1 }), '4*&lucky&fire,water&!shiny', 'mixed');
  eq(build({}, { cpMin: '1000', cpMax: '', age: '7', family: 'Pikachu' }), 'cp1000-&age0-7&+pikachu', 'numbers and family');
  eq(build({}, { cpMin: '', cpMax: '1500' }), 'cp-1500', 'max CP only');
  eq(build({}), '', 'nothing picked');
});

// ---------------------------------------------------------------- identity
test('the same Pokémon scanned again is one log entry', async t => {
  const c = card({ cp: 1200, name: 'Charmeleon', kg: 19.2, m: 1.1, types: 'FIRE', ivs: [14, 13, 15] });
  await t.app.scan(c); await t.app.scan(c); await t.app.scan({ ...c, auto: true });
  eq(t.app.log().length, 1, 'log size'); check(t.app.log()[0].scans >= 3, 'scan count kept');
});

test('same IVs, different body: two Pokémon', async t => {
  await t.app.scan(card({ cp: 500, name: 'Charmander', kg: 8.5, m: 0.6, types: 'FIRE', ivs: [12, 12, 12] }));
  await t.app.scan(card({ cp: 500, name: 'Charmander', kg: 9.9, m: 0.7, types: 'FIRE', ivs: [12, 12, 12], date: '9/1/2026' }));
  eq(t.app.log().length, 2, 'log size');
});

test('nickname on the card: species comes from the catch banner', async t => {
  await t.app.scan(card({ cp: 3500, name: 'Robot 39', species: 'Melmetal', kg: 800, m: 2.5, types: 'STEEL', ivs: [13, 13, 13] }));
  eq(t.last().target[0], 'Melmetal', 'species'); eq(t.app.log()[0].species, 'Melmetal', 'logged species');
});

test('evolution: same IVs and catch date at a higher stage is the same Pokémon', async t => {
  await t.app.scan(card({ cp: 700, name: 'Charmander', kg: 8.5, m: 0.6, types: 'FIRE', ivs: [15, 14, 15] }));
  await t.app.scan(card({ cp: 1400, name: 'Charmeleon', kg: 19.0, m: 1.1, types: 'FIRE', ivs: [15, 14, 15] }));
  eq(t.app.log().length, 1, 'log size'); eq(t.app.log()[0].species, 'Charmeleon', 'species'); eq(t.app.log()[0].wasSpecies, 'Charmander', 'previous stage');
});

test('regional form: told apart by the type row', async t => {
  await t.app.scan(card({ cp: 2500, name: 'Exeggutor', kg: 415, m: 10.9, types: 'GRASS / DRAGON', ivs: [13, 14, 13] }));
  has(t.last().target[0], 'Alola', 'Alolan form chosen from GRASS / DRAGON');
  const u = loadApp();
  await u.app.scan(card({ cp: 2500, name: 'Exeggutor', kg: 120, m: 2.0, types: 'GRASS / PSYCHIC', ivs: [13, 14, 13] }));
  eq(u.last().target[0], 'Exeggutor', 'Kanto form chosen from GRASS / PSYCHIC');
});

// ---------------------------------------------------------------- ranking
test('ranking: a better duplicate outranks a worse one', async t => {
  await t.app.scan(card({ cp: 2600, name: 'Charizard', kg: 90.5, m: 1.7, types: 'FIRE / FLYING', ivs: [13, 13, 13] }));
  await t.app.scan(card({ cp: 2700, name: 'Charizard', kg: 95.1, m: 1.8, types: 'FIRE / FLYING', ivs: [15, 15, 14], date: '9/2/2026' }));
  has(slot(t.last(), 'REASON'), '#1', 'the 44 is first');
  await t.app.scan(card({ cp: 2600, name: 'Charizard', kg: 90.5, m: 1.7, types: 'FIRE / FLYING', ivs: [13, 13, 13] }));
  has(slot(t.last(), 'REASON'), '#2', 'the 39 is second');
});

// ---------------------------------------------------------------- shell contract
test('returning to the app restores the page; the pill keeps its result', async t => {
  eq(t.app.mode(), 'catch', 'page starts in catch mode');
  await t.app.scan(card({ cp: 266, name: 'Charmander', kg: 8.5, m: 0.6, types: 'FIRE', ivs: [10, 12, 9] }));
  eq(t.app.mode(), 'storage', 'scan drives the inspector');
  const before = t.pill.length;
  t.app.foreground();
  eq(t.app.mode(), 'catch', 'page mode restored'); eq(t.pill.length, before, 'no pill update from the restore');
});

test('trade value: power-up costs match the game (L40 = 270k dust, 304 candy)', async t => {
  const inv = require('vm').runInContext('JSON.stringify([investedAt(20), investedAt(30), investedAt(40)])', t.ctx);
  eq(inv, JSON.stringify([{ dust: 45000, candy: 56 }, { dust: 120000, candy: 122 }, { dust: 270000, candy: 304 }]), 'cumulative cost');
});

test('privacy: scanning never touches the network', async t => {
  await t.app.scan(card({ cp: 3000, name: 'Charizard', kg: 90.5, m: 1.7, types: 'FIRE / FLYING', ivs: [15, 15, 15] }));
  await t.app.scan(catchScreen('Bulbasaur', 637));
  eq(t.net.length, 0, 'network calls');
});

test('the log survives a restart (stored on the device)', async t => {
  await t.app.scan(card({ cp: 3000, name: 'Charizard', kg: 90.5, m: 1.7, types: 'FIRE / FLYING', ivs: [15, 15, 15] }));
  const again = loadApp({ storage: t.store });
  eq(again.app.log().length, 1, 'entries after reload'); eq(again.app.log()[0].ivs.join('/'), '15/15/15', 'IVs kept');
});

(async () => {
  let failed = 0;
  for (const { name, fn } of tests) {
    const t = loadApp();
    try {
      await fn(t);
      console.log(`  ok    ${name}`);
      if (verbose && t.last()) console.log('        ' + JSON.stringify(t.last()));
    } catch (e) {
      failed++;
      console.log(`  FAIL  ${name}\n        ${e.message}`);
      if (t.last()) console.log('        last pill: ' + JSON.stringify(t.last()));
    }
  }
  console.log(`\n${tests.length - failed}/${tests.length} passed`);
  process.exit(failed ? 1 : 0);
})();
