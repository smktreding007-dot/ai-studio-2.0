'use strict';
// Checks the Firebase rules of the Lucky Wheel by evaluating the rule expressions on small fake databases.
// (The app does the spin on the phone; these rules are what stops a changed app from cheating.)
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const RULES_FILE = path.join(__dirname, '..', '..', 'database.rules.json');
if (!fs.existsSync(RULES_FILE)) { console.log('database.rules.json not found next to backend/ - rules test skipped'); process.exit(0); }
const RULES = JSON.parse(fs.readFileSync(RULES_FILE, 'utf8')).rules;
const TS = { '.sv': 'timestamp' };
const DAY = 86400000;

const clone = (o) => JSON.parse(JSON.stringify(o));
function getAt(tree, parts) { let t = tree; for (const p of parts) { if (t === null || typeof t !== 'object' || !(p in t)) return undefined; t = t[p]; } return t; }
function setAt(tree, parts, v) {
  let t = tree;
  for (let i = 0; i < parts.length - 1; i++) { if (typeof t[parts[i]] !== 'object' || t[parts[i]] === null) t[parts[i]] = {}; t = t[parts[i]]; }
  if (v === null) delete t[parts[parts.length - 1]]; else t[parts[parts.length - 1]] = v;
}
class Snap {
  constructor(tree, parts) { this.tree = tree; this.parts = parts; }
  _v() { return getAt(this.tree, this.parts); }
  child(p) { return new Snap(this.tree, this.parts.concat(String(p).split('/'))); }
  parent() { return new Snap(this.tree, this.parts.slice(0, -1)); }
  exists() { const v = this._v(); return v !== undefined && v !== null; }
  val() { const v = this._v(); return v === undefined ? null : v; }
  hasChildren(list) { const v = this._v(); if (v === null || typeof v !== 'object') return false; return list ? list.every((k) => k in v) : Object.keys(v).length > 0; }
  isNumber() { return typeof this._v() === 'number'; }
  isString() { return typeof this._v() === 'string'; }
  isBoolean() { return typeof this._v() === 'boolean'; }
}
function resolveTs(v, now) { if (v && typeof v === 'object') { if (v['.sv'] === 'timestamp') return now; for (const k of Object.keys(v)) v[k] = resolveTs(v[k], now); } return v; }

/** Applies a multi-path update; returns the new tree. */
function apply(old, updates, now) {
  const t = clone(old);
  for (const [p, v] of Object.entries(updates)) setAt(t, p.split('/'), v === null ? null : resolveTs(clone(v), now));
  return t;
}
/** true only when every written location may be written (own or an ancestor's .write) and passes the .validate rules below it. */
function allowed(old, updates, uid, now) {
  const neu = apply(old, updates, now);
  const evalExpr = (expr, vars, ps) => {
    if (typeof expr === 'boolean') return expr;
    const names = ['auth', 'now', 'root', 'data', 'newData', ...Object.keys(vars)];
    try { return !!new Function(...names, `return (${expr});`)({ uid }, now, new Snap(old, []), new Snap(old, ps), new Snap(neu, ps), ...Object.values(vars)); } catch { return false; }
  };
  for (const p of Object.keys(updates)) {
    const parts = p.split('/');
    let node = RULES; let vars = {}; let writable = false; let validNode = null; let validVars = {};
    if (node['.write'] !== undefined && evalExpr(node['.write'], vars, [])) writable = true;
    for (let i = 0; i < parts.length && node; i++) {
      const part = parts[i];
      let next = node[part];
      if (!next) { const w = Object.keys(node).find((k) => k.startsWith('$')); if (w) { vars = { ...vars, [w]: part }; next = node[w]; } }
      node = next;
      if (node && node['.write'] !== undefined && evalExpr(node['.write'], vars, parts.slice(0, i + 1))) writable = true;
      if (node && i === parts.length - 1) { validNode = node; validVars = vars; }
    }
    if (!writable) return false;
    const walk = (n, ps, vs) => {
      if (n['.validate'] !== undefined && new Snap(neu, ps).exists() && !evalExpr(n['.validate'], vs, ps)) return false;
      for (const k of Object.keys(n)) {
        if (k.startsWith('.')) continue;
        if (k.startsWith('$')) {
          const v = new Snap(neu, ps)._v();
          if (v && typeof v === 'object') for (const ck of Object.keys(v)) { if (Object.prototype.hasOwnProperty.call(n, ck)) continue; if (!walk(n[k], ps.concat(ck), { ...vs, [k]: ck })) return false; }
        } else if (!walk(n[k], ps.concat(k), vs)) return false;
      }
      return true;
    };
    if (validNode && !walk(validNode, parts, validVars)) return false;
  }
  return true;
}

const NOW = Date.UTC(2026, 9, 27, 6, 0, 0);
const dayStart = (n) => (n + 19800000) - ((n + 19800000) % DAY);
const U = 'u1';
function base(extra = {}) {
  return clone(Object.assign({
    users: { [U]: { username: 'amit001', deposited: 20, winning: 0, role: 'user' } },
    spinConfig: { enabled: true, matchesRequired: 8, minEntryFee: 10, dailyLimit: 3, cooldownDays: 0, lockAll: 100, maxReward: 1000, creditTo: 'deposited' },
    spinState: { [U]: { init: true, consumed: 5, dayStart: 0, dayCount: 0, totalSpins: 0, totalEarned: 0, lastAt: 0, lastNormalAt: 0, lastAmount: 0, lastBonus: false, lastWallet: 'none' } },
  }, extra));
}
/** The same write the app makes. */
function spinUpdate(db, o = {}) {
  const st = db.spinState[U];
  const amount = o.amount === undefined ? 5 : o.amount;
  const bonus = !!o.bonus;
  const today = dayStart(NOW);
  const wallet = o.wallet || 'deposited';
  const u = {
    [`spinState/${U}`]: {
      init: true,
      consumed: bonus ? st.consumed : st.consumed + (o.required === undefined ? 8 : o.required),
      dayStart: bonus ? st.dayStart : today,
      dayCount: bonus ? st.dayCount : (st.dayStart === today ? st.dayCount : 0) + 1,
      totalSpins: st.totalSpins + 1, totalEarned: st.totalEarned + amount,
      lastAt: TS, lastNormalAt: bonus ? st.lastNormalAt : TS, lastAmount: amount, lastBonus: bonus, lastWallet: wallet,
    },
    [`users/${U}/${wallet}`]: db.users[U][wallet] + (o.walletAdd === undefined ? amount : o.walletAdd),
    [`spinHistory/${U}/k1`]: { amount, ts: TS, wallet, bonus },
    [`spinLog/k1`]: { uid: U, username: 'amit001', amount, ts: TS },
    [`transactions/${U}/spin_k1`]: { id: 'spin_k1', type: 'spin', amount, ts: TS, status: 'success' },
  };
  if (bonus) { const b = db.spinBonus[U]; u[`spinBonus/${U}`] = b > 1 ? b - 1 : null; }
  return u;
}
const okSpin = (db, o) => allowed(db, spinUpdate(db, o), U, NOW);

test('a normal spin is accepted (state, wallet, history, log, transaction in one write)', () => {
  const db = base({ spinState: { [U]: { ...base().spinState[U], consumed: 5 } } });
  assert.strictEqual(okSpin(db), true);
});
test('wallet must go up by exactly the reward', () => {
  assert.strictEqual(okSpin(base(), { walletAdd: 500 }), false);
  assert.strictEqual(okSpin(base(), { walletAdd: 4 }), false);
});
test('wallet cannot be raised without a spin', () => {
  assert.strictEqual(allowed(base(), { [`users/${U}/deposited`]: 9999 }, U, NOW), false);
});
test('spin write cannot reuse an old spin (lastAt must be now)', () => {
  const db = base(); db.spinState[U].lastAt = NOW - 1000; db.spinState[U].lastAmount = 5; db.spinState[U].lastWallet = 'deposited';
  assert.strictEqual(allowed(db, { [`users/${U}/deposited`]: 25 }, U, NOW), false);
});
test('reward above the highest wheel reward is refused', () => {
  const db = base(); db.spinConfig.maxReward = 100;
  assert.strictEqual(okSpin(db, { amount: 500 }), false);
  assert.strictEqual(okSpin(db, { amount: 100 }), true);
});
test('lock for all players: lock 5 -> only up to 5', () => {
  const db = base(); db.spinConfig.lockAll = 5;
  assert.strictEqual(okSpin(db, { amount: 5 }), true);
  assert.strictEqual(okSpin(db, { amount: 2 }), true);
  assert.strictEqual(okSpin(db, { amount: 10 }), false);
});
test('lock of one player beats the lock for all', () => {
  const db = base(); db.spinConfig.lockAll = 5; db.spinUserCfg = { [U]: { lock: 50 } };
  assert.strictEqual(okSpin(db, { amount: 50 }), true);
  assert.strictEqual(okSpin(db, { amount: 100 }), false);
  db.spinUserCfg[U].lock = 2;
  assert.strictEqual(okSpin(db, { amount: 10 }), false);
});
test('lock 0 -> only 2 coins', () => {
  const db = base(); db.spinConfig.lockAll = 0;
  assert.strictEqual(okSpin(db, { amount: 2 }), true);
  assert.strictEqual(okSpin(db, { amount: 5 }), false);
  delete db.spinConfig.lockAll;
  assert.strictEqual(okSpin(db, { amount: 2 }), true);
  assert.strictEqual(okSpin(db, { amount: 10 }), false);
});
test('lock steps in the rules: 6-9 -> up to 10, up to 80 -> max 150, 81+ -> whole wheel', () => {
  const db = base(); db.spinConfig.lockAll = 7;
  assert.strictEqual(okSpin(db, { amount: 10 }), true);
  assert.strictEqual(okSpin(db, { amount: 25 }), false);
  db.spinConfig.lockAll = 80;
  assert.strictEqual(okSpin(db, { amount: 100 }), true);
  assert.strictEqual(okSpin(db, { amount: 500 }), false);
  assert.strictEqual(okSpin(db, { amount: 1000 }), false);
  db.spinConfig.lockAll = 81;
  assert.strictEqual(okSpin(db, { amount: 1000 }), true);
  db.spinConfig.lockAll = 100;
  assert.strictEqual(okSpin(db, { amount: 1000 }), true);
  const p = base(); p.spinConfig.lockAll = 5; p.spinUserCfg = { [U]: { lock: 100 } };
  assert.strictEqual(okSpin(p, { amount: 1000 }), true);
});
test('blocked player and switched-off wheel are refused', () => {
  const a = base(); a.spinUserCfg = { [U]: { blocked: true } };
  assert.strictEqual(okSpin(a), false);
  const b = base(); b.spinConfig.enabled = false;
  assert.strictEqual(okSpin(b), false);
});
test('daily limit', () => {
  const db = base(); db.spinState[U].dayStart = dayStart(NOW); db.spinState[U].dayCount = 2;
  assert.strictEqual(okSpin(db), true);
  db.spinState[U].dayCount = 3;
  assert.strictEqual(okSpin(db), false);
  db.spinState[U].dayStart = dayStart(NOW) - DAY; // yesterday's count does not matter
  assert.strictEqual(okSpin(db), true);
});
test('cannot say a different day to dodge the daily limit', () => {
  const db = base(); db.spinState[U].dayStart = dayStart(NOW); db.spinState[U].dayCount = 3;
  const u = spinUpdate(db);
  u[`spinState/${U}`].dayStart = dayStart(NOW) + DAY; u[`spinState/${U}`].dayCount = 1;
  assert.strictEqual(allowed(db, u, U, NOW), false);
});
test('minimum days between spins', () => {
  const db = base(); db.spinConfig.cooldownDays = 1; db.spinState[U].lastNormalAt = NOW - 3600 * 1000;
  assert.strictEqual(okSpin(db), false);
  db.spinState[U].lastNormalAt = NOW - 25 * 3600 * 1000;
  assert.strictEqual(okSpin(db), true);
});
test('a spin must use up the required matches', () => {
  assert.strictEqual(okSpin(base(), { required: 0 }), false);
  assert.strictEqual(okSpin(base(), { required: 8 }), true);
});
test('task of 0 matches', () => {
  const db = base(); db.spinConfig.matchesRequired = 0;
  assert.strictEqual(okSpin(db, { required: 0 }), true);
});
test('admin free spin: skips limits, takes exactly one', () => {
  const db = base({ spinBonus: { [U]: 2 } }); db.spinState[U].dayStart = dayStart(NOW); db.spinState[U].dayCount = 3; db.spinConfig.cooldownDays = 5; db.spinState[U].lastNormalAt = NOW - 1000;
  assert.strictEqual(okSpin(db, { bonus: true }), true);
  const one = base({ spinBonus: { [U]: 1 } });
  assert.strictEqual(okSpin(one, { bonus: true }), true);
});
test('free spin without having one is refused; taking two at once is refused', () => {
  assert.strictEqual(okSpin(base({ spinBonus: { [U]: 0 } }), { bonus: true }), false);
  const db = base({ spinBonus: { [U]: 5 } });
  const u = spinUpdate(db, { bonus: true }); u[`spinBonus/${U}`] = 3;
  assert.strictEqual(allowed(db, u, U, NOW), false);
});
test('player cannot give himself free spins or change the lock', () => {
  const db = base();
  assert.strictEqual(allowed(db, { [`spinBonus/${U}`]: 50 }, U, NOW), false);
  assert.strictEqual(allowed(db, { [`spinUserCfg/${U}`]: { lock: 5000 } }, U, NOW), false);
  assert.strictEqual(allowed(db, { 'spinConfig/enabled': false }, U, NOW), false);
});
test('first visit: only an empty starting state may be created', () => {
  const db = base(); delete db.spinState;
  const init = { init: true, consumed: 3, dayStart: 0, dayCount: 0, totalSpins: 0, totalEarned: 0, lastAt: 0, lastNormalAt: 0, lastAmount: 0, lastBonus: false, lastWallet: 'none' };
  assert.strictEqual(allowed(db, { [`spinState/${U}`]: init }, U, NOW), true);
  assert.strictEqual(allowed(db, { [`spinState/${U}`]: { ...init, totalEarned: 100 } }, U, NOW), false);
});
test('winning wallet works when the admin chose it', () => {
  const db = base(); db.spinConfig.creditTo = 'winning';
  assert.strictEqual(okSpin(db, { wallet: 'winning' }), true);
  assert.strictEqual(okSpin(db, { wallet: 'deposited' }), false); // must be the wallet the admin chose
});
test('another player cannot spin for me', () => {
  const db = base(); db.users.u2 = { username: 'x', deposited: 0, winning: 0, role: 'user' };
  assert.strictEqual(allowed(db, spinUpdate(db), 'u2', NOW), false);
});
