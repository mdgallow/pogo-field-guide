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

test('catch: a "could be perfect" with hopeless odds does not demand an Ultra Ball', async t => {
  const run = code => require('vm').runInContext(code, t.ctx);
  const ball = (n, cp) => run(`(() => { const p = POKEMON_DATA.find(x => x.name === ${JSON.stringify(n)}); return getBallAdvice(p, ${cp}, evaluateCpMatch(p, ${cp})).text; })()`);
  check(!/ULTRA/.test(ball('Duskull', 10)), `10 CP Duskull is a Poké Ball catch (${ball('Duskull', 10)})`);
  has(ball('Bulbasaur', 1035), 'ULTRA BALL', 'a CP only a hundo can have gets the Ultra Ball');
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

test('lucky: once seen, a lucky stays lucky even when a later read misses the label', async t => {
  const g = extra => card({ cp: 2900, name: 'Giratina', kg: 750, m: 4.5, types: 'GHOST / DRAGON', ivs: [12, 12, 13], ...extra });
  await t.app.scan(g({ lucky: true }));
  eq(t.app.log()[0].lucky, true, 'logged as lucky');
  await t.app.scan(g({ lucky: false }));
  eq(t.app.log()[0].lucky, true, 'still lucky after a read without the label'); has(slot(t.last(), 'REASON'), 'LUCKY', 'pill still says lucky');
  const u = loadApp();
  await u.app.scan({ ...g({ lucky: false }), lucky: true });   // gold background seen by the shell, label missed
  eq(u.app.log()[0].lucky, true, 'gold background alone marks it lucky');
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

test('trade order inside a family follows CP: higher CP trades, lower CP makes room', async t => {
  // Six Machamp keepers, then non-keepers below the floor at mixed CP, IVs and ages.
  const machamp = (cp, ivs, i, date) => card({ cp, name: 'Machamp', kg: 120 + i, m: 1.6, types: 'FIGHTING', ivs, date: date || `9/${10 + i}/2026` });
  const keepers = [[15, 14, 13], [14, 14, 13], [14, 13, 13], [13, 13, 13], [13, 13, 12], [13, 12, 12]];
  for (let i = 0; i < keepers.length; i++) await t.app.scan(machamp(2500 + i * 10, keepers[i], i));
  const extras = [[2900, [10, 10, 10], 20, '9/1/2026'], [2000, [12, 12, 11], 21, '9/1/2023'], [1200, [11, 11, 11], 22, '9/1/2026'], [600, [12, 12, 12], 23, '9/1/2022']];
  for (const [cp, ivs, i, d] of extras) await t.app.scan(machamp(cp, ivs, i, d));
  const verdicts = [];
  for (const [cp, ivs, i, d] of extras) { await t.app.scan(machamp(cp, ivs, i, d)); verdicts.push(cp + ':' + slot(t.last(), 'VERDICT')); }
  eq(verdicts[0], '2900:TRADE', 'highest CP non-keeper trades');
  eq(verdicts[1], '2000:TRADE', 'next trades');
  const worth = s => Number((s.match(/~(\d+)K/) || [])[1]);
  const reasons = [];
  for (const [cp, ivs, i, d] of extras) { await t.app.scan(machamp(cp, ivs, i, d)); reasons.push(slot(t.last(), 'REASON')); }
  check(worth(reasons[0]) > worth(reasons[1]) && worth(reasons[1]) > worth(reasons[3]), `value follows level, not IVs or age: ${reasons.map(worth).join(' > ')}`);
});

test('trade value: evolution candy owed and free trade evolutions are priced in', async t => {
  const run = code => require('vm').runInContext(code, t.ctx);
  const v = (n, cp) => JSON.parse(run(`JSON.stringify(tradeValue(POKEMON_DATA.find(x => x.name === ${JSON.stringify(n)}), ${cp}, null, '9/1/2026', 0))`));
  const charmander = v('Charmander', 715), charizard = v('Charizard', 2200);     // both about level 30
  check(charmander.parts.evoLeft === 125 && charizard.parts.evoLeft === 0, 'candy still owed: 125 vs 0');
  check(charizard.score > charmander.score * 1.5, `a Charizard at the same level is worth far more (${charizard.score}k vs ${charmander.score}k)`);
  const machoke = v('Machoke', 1200);
  has(run(`POKEMON_DATA.find(x => x.name === 'Machoke').te`), true, 'Machoke is a trade evolution');
  check(machoke.parts.candy > 0, 'the free evolution turns its candy term positive');
  const low = v('Charmander', 150);
  eq(low.tier, 'LOW', 'a low-level unevolved common-ish catch is transfer fodder');
});

test('trade value: a league pick above its cap level earns nothing for that league', async t => {
  const run = code => require('vm').runInContext(code, t.ctx);
  const cap = run(`capLevel(POKEMON_DATA.find(x => x.name === 'Azumarill'), 1500)`);
  check(cap >= 25 && cap <= 45, `Azumarill GL cap level is plausible (${cap})`);
  const over = JSON.parse(run(`JSON.stringify(tradeValue(POKEMON_DATA.find(x => x.name === 'Azumarill'), 1800, null, '9/1/2026', 0))`));
  eq(over.parts.level, 0, 'level worth nothing above the cap');
});

// ---------------------------------------------------------------- shadows
test('shadow: ranked among shadows; best of a raid species keeps as shadow, extras purify-to-trade or transfer', async t => {
  const sh = (ivs, i, cp) => ({ ...card({ cp, name: 'Machamp', kg: 120 + i, m: 1.6, types: 'FIGHTING', ivs, date: `9/${10 + i}/2026` }), shadow: true });
  // Six normal keepers first: they must not push the shadows out.
  for (let i = 0; i < 6; i++) await t.app.scan(card({ cp: 2500 + i * 10, name: 'Machamp', kg: 100 + i, m: 1.6, types: 'FIGHTING', ivs: [15, 14, 13], date: `8/${10 + i}/2026` }));
  await t.app.scan(sh([12, 10, 11], 0, 2600));
  eq(slot(t.last(), 'VERDICT'), 'KEEP', 'a 33-IV shadow of a raid species is a keeper'); has(slot(t.last(), 'REASON'), 'SHADOW', 'as a shadow');
  check(movesSlots(t.last()).length >= 1, 'shadow keeper gets its moves');
  await t.app.scan(sh([5, 4, 6], 1, 2400));
  eq(slot(t.last(), 'VERDICT'), 'PURIFY', 'a 15-IV shadow at level ~30 is worth purifying to trade'); has(slot(t.last(), 'REASON'), 'THEN TRADE', 'purify, then trade');
  await t.app.scan(sh([5, 4, 6], 3, 400));
  eq(slot(t.last(), 'VERDICT'), 'SURPLUS', 'a 15-IV low-level shadow is a transfer'); has(slot(t.last(), 'REASON'), 'SHADOW', 'named as shadow');
  await t.app.scan(sh([13, 13, 14], 2, 2650));
  has(slot(t.last(), 'REASON'), 'PURIFY', 'a shadow that purifies to 45 mentions it'); eq(slot(t.last(), 'VERDICT'), 'KEEP', 'but stays a shadow keeper for raids');
  eq(t.app.log().filter(e => e.shadow).length, 4, 'four shadows logged');
});

test('shadow: no raid use -> purify when the +2 makes a 44, otherwise keep one', async t => {
  const run = code => require('vm').runInContext(code, t.ctx);
  const noRaid = run(`POKEMON_DATA.find(p => !roleOf(p).raid && !roleOf(p).pvp && p.pd && p.finals.length === 0 && p.types.length === 1 && p.name.indexOf(' ') < 0 && p.name.indexOf('(') < 0 && baseCatchRate(p) <= 0.3).name`);
  const types = run(`POKEMON_DATA.find(p => p.name === ${JSON.stringify(noRaid)}).types.join(' / ').toUpperCase()`);
  await t.app.scan({ ...card({ cp: 1500, name: noRaid, kg: 30, m: 1, types, ivs: [13, 13, 13] }), shadow: true });
  eq(slot(t.last(), 'VERDICT'), 'PURIFY', `${noRaid} 13/13/13 shadow purifies to 45`); has(slot(t.last(), 'REASON'), '45/45', 'says what it becomes');
  const u = loadApp();
  await u.app.scan({ ...card({ cp: 1500, name: noRaid, kg: 30, m: 1, types, ivs: [11, 10, 11] }), shadow: true });
  eq(slot(u.last(), 'VERDICT'), 'KEEP BEST', 'a lone decent shadow of a no-use species is kept as the one');
});

// ---------------------------------------------------------------- shiny by colour
test('shiny by colour: hue evidence only; night lighting cannot fake a shiny; colour marks are reversible', async t => {
  const run = code => require('vm').runInContext(code, t.ctx);
  const cz = (extra) => ({ ...card({ cp: 2200, name: 'Charizard', kg: 90, m: 1.7, types: 'FIRE / FLYING', ivs: [10, 10, 10] }), ...extra });
  // What the phone sees of a shiny Charizard: the black body is too dull to count, so cream and red wings.
  await t.app.scan(cz({ colors: [[40, 53, 91, 70], [1, 75, 37, 30]] }));
  eq(t.app.log()[0].shiny, true, 'red wings mark it shiny'); has(slot(t.last(), 'REASON'), 'SHINY', 'verdict from the shiny branch');
  eq(t.app.log()[0].shinyAuto, true, 'noted as a colour mark');
  // The same Pokémon read again in plain daylight orange: the colour mark is undone.
  await t.app.scan(cz({ colors: [[31, 71, 91, 80], [186, 86, 48, 10]] }));
  check(!t.app.log()[0].shiny && !t.app.log()[0].shinyAuto, 'a clearly normal read undoes a colour mark');
  const u = loadApp();
  await u.app.scan(cz({ colors: [[31, 71, 91, 74], [40, 39, 87, 18]] }));
  check(!u.app.log()[0].shiny, 'orange daylight Charizard is not shiny');
  // The tester's case: a normal Charizard rendered dark and dull (night scene / dim screen).
  await u.app.scan(cz({ colors: [[35, 25, 45, 80], [30, 40, 60, 15]] }));
  check(!u.app.log()[0].shiny, 'a dark, dull orange is never a shiny (brightness is not evidence)');
  eq(run(`shinyFromColors(POKEMON_DATA.find(x => x.name === 'Charizard'), [[35, 25, 45, 80]])`), null, 'without a shiny-only hue the colour says nothing');
  // A hand-set mark is never changed by colour.
  const v = loadApp();
  await v.app.scan(cz({}));
  require('vm').runInContext(`setLogMark(pokeLog[0].id, 'shiny', true)`, v.ctx);
  await v.app.scan(cz({ colors: [[31, 71, 91, 80]] }));
  eq(v.app.log()[0].shiny, true, 'the player ticked shiny: colour leaves it alone');
  eq(run(`shinyFromColors(POKEMON_DATA.find(x => x.name === 'Gengar'), POKEMON_DATA.find(x => x.name === 'Gengar').pal.s)`), null, 'Gengar: shiny differs only in shade, cannot be told');
  eq(run(`shinyFromColors(POKEMON_DATA.find(x => x.name === 'Pikachu'), POKEMON_DATA.find(x => x.name === 'Pikachu').pal.s)`), null, 'Pikachu: shiny is a slightly deeper yellow, cannot be told');
  eq(run(`shinyFromColors(POKEMON_DATA.find(x => x.name === 'Turtwig'), [[159, 44, 76, 60], [67, 70, 69, 20]])`), true, 'teal Turtwig is shiny');
  eq(run(`shinyFromColors(POKEMON_DATA.find(x => x.name === 'Turtwig'), [[81, 20, 40, 70], [52, 30, 36, 20]])`), false, 'a dark green Turtwig at night is normal');
});

test('shiny pass: with the game\'s shiny search on screen every read is shiny; colour never undoes it; the pass expires', async t => {
  const run = code => require('vm').runInContext(code, t.ctx);
  const gc = (extra) => ({ ...card({ cp: 3000, name: 'Garchomp', kg: 95, m: 1.9, types: 'DRAGON / GROUND', ivs: [14, 13, 15] }), ...extra });
  await t.app.scan(gc({}));
  check(!t.app.log()[0].shiny, 'a normal-looking Garchomp is not shiny on its own');
  run(`startShinyPass()`);
  await t.app.scan(gc({ colors: [[220, 60, 70, 80]] }));
  eq(t.app.log()[0].shiny, true, 'read during the pass: shiny'); eq(t.app.log()[0].shinyPass, true, 'noted as a pass mark');
  has(slot(t.last(), 'PASS'), 'SHINY PASS ON', 'the pill says the pass is on'); has(slot(t.last(), 'REASON'), 'SHINY', 'verdict from the shiny branch');
  run(`endShinyPass()`);
  await t.app.scan(gc({ colors: [[220, 60, 70, 80]] }));
  eq(t.app.log()[0].shiny, true, 'a pass mark is never undone by colour');
  eq(slot(t.last(), 'PASS'), '', 'no pass line once it has ended');
  const u = loadApp();
  require('vm').runInContext(`localStorage.setItem(SHINY_PASS_KEY, String(Date.now() - 1000))`, u.ctx);
  await u.app.scan(gc({}));
  check(!u.app.log()[0].shiny, 'an expired pass marks nothing');
  has(run(`buildSearch({ shiny: 1 })`), 'shiny', 'the search builder knows the shiny flag');
});

test('shiny released list: unreleased species are never marked; tell = hue, badge or none', async t => {
  const run = code => require('vm').runInContext(code, t.ctx);
  eq(run(`shinyTell(POKEMON_DATA.find(x => x.name === 'Charizard'))`), 'hue', 'Charizard: by hue');
  eq(run(`shinyTell(POKEMON_DATA.find(x => x.name === 'Garchomp'))`), 'badge', 'Garchomp: badge only');
  eq(run(`shinyTell(POKEMON_DATA.find(x => x.name === 'Slowpoke'))`), 'badge', 'Slowpoke: badge only');
  const unreleased = run(`POKEMON_DATA.find(p => !p.sh && p.types.length === 1 && p.name.indexOf('(') < 0 && p.finals.length === 0).name`);
  eq(run(`shinyTell(POKEMON_DATA.find(x => x.name === ${JSON.stringify(unreleased)}))`), 'none', `${unreleased}: no shiny released`);
  const types = run(`POKEMON_DATA.find(p => p.name === ${JSON.stringify(unreleased)}).types.join(' / ').toUpperCase()`);
  await t.app.scan({ ...card({ cp: 1500, name: unreleased, kg: 30, m: 1, types, ivs: [12, 12, 12] }), shinyBadge: true, colors: [[1, 90, 90, 90]] });
  check(!t.app.log()[0].shiny, 'badge and colour cannot mark a species with no shiny released');
  eq(run(`POKEMON_DATA.filter(p => p.sh).length`), 1193, 'released count matches the snapshot');
});

// ---------------------------------------------------------------- shinies
test('shiny: own branch, prized best two then trade stock, never transfer, never blended', async t => {
  const run = code => require('vm').runInContext(code, t.ctx);
  const mk = (ivs, i, cp) => card({ cp, name: 'Charmander', kg: 8 + i * 0.3, m: 0.6, types: 'FIRE', ivs, date: `9/${10 + i}/2026` });
  for (let i = 0; i < 6; i++) await t.app.scan(mk([14, 14, 13], i, 600 + i));          // six normal keepers
  for (let i = 0; i < 3; i++) { await t.app.scan(mk([6, 7, 8], 10 + i, 300 + i)); run(`pokeLog[pokeLog.length - 1].shiny = true`); }
  await t.app.scan(mk([6, 7, 8], 12, 302));   // equal IVs: the highest CP ranks first
  eq(slot(t.last(), 'VERDICT'), 'KEEP', 'a low-IV shiny can still be prized'); has(slot(t.last(), 'REASON'), 'SHINY PRIZED', 'as shiny');
  await t.app.scan(mk([6, 7, 8], 10, 300));
  eq(slot(t.last(), 'VERDICT'), 'SHINY STOCK', 'the third shiny is trade stock'); check(!/TRANSFER/.test(slot(t.last(), 'REASON')), 'never transfer');
  await t.app.scan(mk([14, 14, 13], 0, 600));
  eq(slot(t.last(), 'VERDICT'), 'KEEP', 'normal keepers untouched by shinies'); has(slot(t.last(), 'REASON'), '/6', 'normal pool counts six, not nine');
  await t.app.scan(mk([15, 15, 14], 20, 700)); run(`pokeLog[pokeLog.length - 1].shiny = true`); await t.app.scan(mk([15, 15, 14], 20, 700));
  has(slot(t.last(), 'REASON'), 'SHINY 98%', 'a 44 shiny is the prize');
});

test('shadow legendary: keep up to the raid slots, surplus transfers (no purify-then-trade)', async t => {
  const sh = (ivs, i) => ({ ...card({ cp: 2200 + i, name: 'Lunala', kg: 120 + i, m: 4, types: 'PSYCHIC / GHOST', ivs, date: `9/${10 + i}/2026` }), shadow: true });
  for (let i = 0; i < 7; i++) await t.app.scan(sh([12, 12, 12], i));
  await t.app.scan(sh([12, 12, 12], 6));   // equal IVs: the highest CP ranks first
  eq(slot(t.last(), 'VERDICT'), 'KEEP', 'a shadow legendary in the raid slots keeps'); has(slot(t.last(), 'REASON'), 'SHADOW', 'as shadow');
  await t.app.scan(sh([12, 12, 12], 0));
  eq(slot(t.last(), 'VERDICT'), 'SURPLUS', 'the seventh transfers'); has(slot(t.last(), 'REASON'), 'SHADOW LEGENDARY', 'and says why');
  check(!/PURIFY/.test(slot(t.last(), 'VERDICT')), 'no purify-then-trade for a shadow-raid legendary');
});

test('fringe tier: a near-miss family (Torterra) keeps half the slots, not one', async t => {
  const run = code => require('vm').runInContext(code, t.ctx);
  eq(run(`roleOf(POKEMON_DATA.find(x => x.name === 'Torterra')).role`), 'FRINGE', 'Torterra is fringe');
  eq(run(`keepSlots('Turtwig', false).raid`), 3, 'three of six at ~43% utility');
  eq(run(`keepSlots('Beldum', false).raid`), 6, 'a top pick keeps all six'); eq(run(`keepSlots('Rattata', false).raid`), 1, 'a collection species keeps one');
  check(run(`keepSlots('Pidgey', false).raid`) === 2, `a minor pick keeps two (${run(`keepSlots('Pidgey', false).raid`)})`);
  const tw = (ivs, i) => card({ cp: 900 - i * 20, name: 'Turtwig', kg: 10 + i * 0.2, m: 0.4, types: 'GRASS', ivs, date: `9/${10 + i}/2026` });
  const ivs = [[15, 15, 15], [14, 15, 14], [13, 14, 13], [13, 13, 13], [12, 12, 12]];
  for (let i = 0; i < ivs.length; i++) await t.app.scan(tw(ivs[i], i));
  await t.app.scan(tw(ivs[1], 1));
  eq(slot(t.last(), 'VERDICT'), 'KEEP', 'the 43 (#2) is a keeper'); has(slot(t.last(), 'ROLE'), 'FRINGE', 'labelled fringe');
  await t.app.scan(tw(ivs[3], 3));
  check(slot(t.last(), 'VERDICT') !== 'KEEP', 'the #4 (39) is outside the three fringe slots');
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
  eq(slot(t.last(), 'VERDICT'), 'TRADE', '#7 is outside the six slots');
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
  has(slot(high, 'REASON'), 'L31', 'pill names the level as the driver');
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
  check(slot(u.last(), 'REASON').length <= 40, `fits the pill (${slot(u.last(), 'REASON').length}: ${slot(u.last(), 'REASON')})`);
});

test('trade value: the IVs being traded away do not change the score', async t => {
  const score = s => (slot(s, 'REASON').match(/TRADE(?: 1ST)? ~(\d+)K/) || [])[1];
  await t.app.scan(card({ cp: 1384, name: 'Raticate', kg: 18.5, m: 0.7, types: 'NORMAL', ivs: [1, 1, 1] }));
  const low = score(t.last());
  const u = loadApp();
  // Both are level 30 (1384 CP at 1/1/1, 1577 CP at 12/12/11): same trade.
  await u.app.scan(card({ cp: 1577, name: 'Raticate', kg: 18.5, m: 0.7, types: 'NORMAL', ivs: [12, 12, 11] }));
  const high = score(u.last());
  check(low && high && Math.abs(low - high) <= 3, `scores match within rounding (got ${low} vs ${high})`);
});

test('trade value: an old catch is worth more (better lucky odds)', async t => {
  const score = s => Number((slot(s, 'REASON').match(/(?:TRADE(?: 1ST)?|TRANSFER ·) ~(\d+)K/) || [])[1]);
  await t.app.scan(card({ cp: 700, name: 'Raticate', kg: 18.5, m: 0.7, types: 'NORMAL', ivs: [5, 5, 5], date: '8/4/2026' }));
  const fresh = score(t.last());
  const u = loadApp();
  await u.app.scan(card({ cp: 700, name: 'Raticate', kg: 18.5, m: 0.7, types: 'NORMAL', ivs: [5, 5, 5], date: '8/4/2017' }));
  check(score(u.last()) > fresh && score(u.last()) - fresh < 15, `an old catch is worth a little more (${fresh} -> ${score(u.last())}), never a level's worth`);
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
  has(slot(s, 'ROLE'), 'PVP (GREAT)', 'role');
  const m = movesSlots(s);
  eq(m.length, 1, 'one moveset'); eq(m[0].caption, 'GREAT LEAGUE MOVES', 'caption'); has(m[0].value, 'FAST: BUBBLE · CHARGED: ', 'fast and charged named');
});

test('role + moves: a raid-only pick gets only its raid set', async t => {
  await t.app.scan(card({ cp: 3000, name: 'Machamp', kg: 130, m: 1.6, types: 'FIGHTING', ivs: [15, 15, 15] }));
  const s = t.last();
  has(slot(s, 'ROLE'), 'RAID ·', 'role');
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

// ---------------------------------------------------------------- layout listener
test('layout listener: mostly failed storage reads raise the game-update warning', async t => {
  const broken = { storage: true, favorite: false, ivs: null, auto: false, shadow: false, dynamax: false, lines: [line('???', 0.4, 0.41, 0.2, 0.03)] };
  for (let i = 0; i < 8; i++) await t.app.scan(broken);
  const run = code => require('vm').runInContext(code, t.ctx);
  eq(run('layoutSuspect()'), true, 'alarm after 8 failed reads');
  check(t.last().slots.some(x => /READS FAILING/.test(x.value)), 'pill carries the warning');
  await t.app.scan(card({ cp: 3000, name: 'Charizard', kg: 90.5, m: 1.7, types: 'FIRE / FLYING', ivs: [15, 15, 15] }));
  check(!t.last().slots.some(x => /READS FAILING/.test(x.value)), 'a successful read shows no warning on its own pill');
  for (let i = 0; i < 8; i++) await t.app.scan(card({ cp: 3000, name: 'Charizard', kg: 90.5, m: 1.7, types: 'FIRE / FLYING', ivs: [15, 15, 15] }));
  eq(run('layoutSuspect()'), false, 'clears after healthy reads');
});

// ---------------------------------------------------------------- tester diagnostics (test builds)
test('diagnostics file: log without catch places, plus trail and last scan; off in non-test shells', async t => {
  await t.app.scan(card({ cp: 3000, name: 'Charizard', kg: 90.5, m: 1.7, types: 'FIRE / FLYING', ivs: [15, 15, 15] }));
  const bundle = JSON.parse(require('vm').runInContext('diagnosticsBundle()', t.ctx));
  eq(bundle.log.length, 1, 'log included'); eq(bundle.log[0].where, undefined, 'no catch place'); eq(bundle.log[0].ivs.join('/'), '15/15/15', 'IVs included');
  check(typeof bundle.lastScan === 'string' && bundle.lastScan.length > 0, 'last scan text included');
  eq(require('vm').runInContext('Native.testSharing', t.ctx), false, 'the stub shell does not offer sharing');
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

test('identity: a height hidden behind the appraisal leader (".41m") is unknown, not 41 m', async t => {
  const run = code => require('vm').runInContext(code, t.ctx);
  eq(run(`JSON.stringify(parseStorageDetails('8.81kg WEIGHT ROCK / .41m', ''))`), '{"weight":8.81,"height":null,"date":null,"hp":null}', 'truncated height dropped');
  eq(run(`JSON.stringify(parseStorageDetails('8.81kg WEIGHT ROCK / WATER 0.41m HEIGHT', ''))`), '{"weight":8.81,"height":0.41,"date":null,"hp":null}', 'full height kept');
  // Appraised with the leader covering the height, then scanned again with the appraisal closed.
  const base = (ivs, extra) => card({ cp: 1329, name: 'Omanyte 42', species: 'Omanyte', kg: 8.81, m: extra, types: 'ROCK / WATER', ivs, lucky: true, date: '7/27/2023' });
  const first = base([14, 14, 14], '.41'); first.lines = first.lines.filter(l => l.t !== '.41m'); first.lines.push(line('.41m', 0.78, 0.545, 0.08, 0.02));
  await t.app.scan(first);
  await t.app.scan({ ...base(null, 0.41), lines: base(null, 0.41).lines.filter(l => !/caught on/.test(l.t)) });
  eq(t.app.log().length, 1, 'same Omanyte, not a second entry'); has(slot(t.last(), 'IV CHECK'), '(SAVED)', 'saved IVs reused');
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

test('form unsure: no regional moves are recommended until the type row is read', async t => {
  const c = card({ cp: 1800, name: 'Sneasel', kg: 27, m: 0.9, types: null, ivs: [15, 15, 15] });
  await t.app.scan(c);
  has(t.last().target[0], 'Sneasel?', 'form flagged');
  eq(slot(t.last(), 'ROLE'), 'FORM? (TYPE ROW NOT READ)', 'role withheld');
  has(movesSlots(t.last())[0].value, 'FORM UNSURE', 'moves withheld');
  const u = loadApp();
  await u.app.scan(card({ cp: 1800, name: 'Sneasel', kg: 27, m: 0.9, types: 'FIGHTING / POISON', ivs: [15, 15, 15] }));
  has(u.last().target[0], 'Hisui', 'Hisuian form from the type row');
  has(movesSlots(u.last())[0].caption, 'SNEASLER', 'advised on Sneasler, not Weavile');
});

test('identity: the HP line links a re-scan when size and date are hidden', async t => {
  const first = card({ cp: 1329, name: 'Omanyte', kg: 8.81, m: 0.41, types: 'ROCK / WATER', ivs: [14, 14, 14], date: '7/27/2023' });
  first.lines.push(line('92 / 92 HP', 0.43, 0.49, 0.14, 0.02));
  await t.app.scan(first);
  // Appraisal closed, pill over the weight/height, no date badge: only name, CP and HP remain.
  const again = { storage: true, favorite: false, ivs: null, auto: false, shadow: false, dynamax: false, lines: [
    line('CP1329', 0.34, 0.05, 0.26, 0.04), line('Omanyte', 0.36, 0.41, 0.28, 0.035), line('92 / 92 HP', 0.43, 0.49, 0.14, 0.02),
    line('ROCK / WATER', 0.39, 0.59, 0.22, 0.015), line('OMANYTE CANDY', 0.4, 0.69, 0.26, 0.015), line('POWER UP', 0.15, 0.765, 0.22, 0.03), line('EVOLVE', 0.25, 0.83, 0.16, 0.03)] };
  await t.app.scan(again);
  eq(t.app.log().length, 1, 'same Pokémon'); has(slot(t.last(), 'IV CHECK'), '(SAVED)', 'saved IVs reused');
});

// ---------------------------------------------------------------- ranking
test('ranking: a better duplicate outranks a worse one', async t => {
  await t.app.scan(card({ cp: 2600, name: 'Charizard', kg: 90.5, m: 1.7, types: 'FIRE / FLYING', ivs: [13, 13, 13] }));
  await t.app.scan(card({ cp: 2700, name: 'Charizard', kg: 95.1, m: 1.8, types: 'FIRE / FLYING', ivs: [15, 15, 14], date: '9/2/2026' }));
  has(slot(t.last(), 'REASON'), '#1', 'the 44 is first');
  await t.app.scan(card({ cp: 2600, name: 'Charizard', kg: 90.5, m: 1.7, types: 'FIRE / FLYING', ivs: [13, 13, 13] }));
  has(slot(t.last(), 'REASON'), '#2', 'the 39 is second');
});

test('pill over the appraisal box: the page asks to move it instead of guessing', async t => {
  const c = card({ cp: 3000, name: 'Charizard', kg: 90.5, m: 1.7, types: 'FIRE / FLYING', ivs: null });
  await t.app.scan({ ...c, blocked: ['appraisal'] });
  has(slot(t.last(), 'IV CHECK'), 'MOVE THE PILL', 'asks to move the pill');
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
