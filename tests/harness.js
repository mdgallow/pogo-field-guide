// Runs the page's own script (index.html) in Node with a stand-in DOM, so the scan → verdict →
// pill pipeline can be exercised exactly as the native shells drive it. No dependencies.
const fs = require('fs');
const path = require('path');
const vm = require('vm');

function blackhole() {
  const store = {};
  const fn = function () {};
  return new Proxy(fn, {
    get(_, k) {
      if (k === Symbol.toPrimitive) return () => '';
      if (k === Symbol.iterator) return function* () {};
      if (k === 'then') return undefined;
      if (k in store) return store[k];
      return (store[k] = blackhole());
    },
    set(_, k, v) { store[k] = v; return true; },
    apply() { return blackhole(); },
  });
}

function element(id) {
  const classes = new Set();
  const el = {
    id, value: '', innerText: '', innerHTML: '', textContent: '', placeholder: '', title: '', checked: false,
    style: {}, dataset: {}, children: [],
    classList: { add: c => classes.add(c), remove: c => classes.delete(c), contains: c => classes.has(c),
      toggle: c => (classes.has(c) ? classes.delete(c) : classes.add(c)) },
    querySelectorAll: () => [], querySelector: () => null,
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0 }),
  };
  return new Proxy(el, {
    get(t, k) { if (k in t) return t[k]; if (typeof k === 'symbol') return undefined; return (t[k] = blackhole()); },
    // The real DOM stores these as strings whatever is assigned.
    set(t, k, v) { t[k] = TEXT_PROPS.has(k) ? String(v ?? '') : v; return true; },
  });
}
const TEXT_PROPS = new Set(['value', 'innerText', 'innerHTML', 'textContent', 'placeholder']);

function loadApp({ storage = {} } = {}) {
  const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
  const script = html.match(/<script>\s*(const POKEMON_DATA[\s\S]*?)<\/script>/)[1];
  const els = new Map();
  const byId = id => { if (!els.has(id)) els.set(id, element(id)); return els.get(id); };
  const pill = [];   // every state the page hands to the native pill, newest last
  const net = [];    // every network request the page attempts
  const store = { ...storage };
  const localStorage = {
    getItem: k => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); },
    removeItem: k => { delete store[k]; },
  };
  const record = (mode, target, slotsJson, actionsJson) => pill.push({
    mode, target: target.split('\n').filter(Boolean),
    slots: JSON.parse(slotsJson).map(([caption, value, color]) => ({ caption, value, color })),
    actions: actionsJson ? JSON.parse(actionsJson) : [],
  });
  const document = {
    getElementById: byId, createElement: () => element(''), querySelectorAll: () => [], querySelector: () => null,
    addEventListener() {}, body: element('body'), documentElement: element('html'), hidden: false,
  };
  const window = {
    AndroidBridge: {
      minimizeToPill() {}, autoTrail: () => '', checkForUpdate() {},
      updatePill: (m, t, s) => record(m, t, s), updatePillEx: (m, t, s, a) => record(m, t, s, a),
    },
    addEventListener() {}, scrollTo() {}, scrollY: 0, innerWidth: 400, innerHeight: 800,
    matchMedia: () => ({ matches: false, addEventListener() {} }), location: { reload() {}, href: 'app://test/' },
  };
  const ctx = vm.createContext({
    window, document, localStorage, console, JSON, Math, Date, Promise, URL,
    navigator: { userAgent: 'test', serviceWorker: undefined },
    setTimeout: () => 0, clearTimeout() {}, setInterval: () => 0, clearInterval() {},
    requestAnimationFrame: () => 0, fetch: url => { net.push(String(url)); return Promise.reject(new Error('offline')); }, alert() {}, confirm: () => true,
    AndroidBridge: window.AndroidBridge,
  });
  ctx.window.document = document; ctx.window.localStorage = localStorage; ctx.window.navigator = ctx.navigator;
  ctx.self = ctx.window; ctx.globalThis = ctx;
  // Expose top-level let/const state the tests need to reach.
  vm.runInContext(script + '\n;globalThis.__app = { scan: p => window.assessNativeOcr(p), action: a => window.pillAction(a),' +
    ' foreground: () => window.appForegrounded(), log: () => pokeLog, mode: () => evaluationMode };', ctx, { filename: 'index.html' });
  return { app: ctx.__app, pill, net, last: () => pill[pill.length - 1], store, ctx };
}

module.exports = { loadApp };
