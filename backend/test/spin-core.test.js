'use strict';
const test = require('node:test');
const assert = require('node:assert');
const c = require('../spin-core');

const cfg = (o = {}) => c.normalizeConfig({ enabled: true, matchesRequired: 8, minEntryFee: 10, dailyLimit: 3, ...o });
const NOW = Date.UTC(2026, 9, 27, 6, 0, 0);

test('default wheel is used when the admin list is broken', () => {
  assert.strictEqual(c.parseSegments(null).length, 8);
  assert.strictEqual(c.parseSegments([{ amount: 5, weight: 1 }]).length, 8); // needs at least 2
  assert.strictEqual(c.parseSegments([{ amount: 5, weight: 1 }, { amount: -3 }, { amount: 7, weight: 2 }]).length, 2);
});

test('lock 5 for everyone: only 2 or 5 can be won', () => {
  const segs = c.parseSegments(null);
  const seen = new Set();
  for (let i = 0; i < 3000; i++) seen.add(segs[c.pickIndex(segs, 5)].amount);
  assert.deepStrictEqual([...seen].sort((a, b) => a - b), [2, 5]);
});

test('whole wheel (cap Infinity): every reward can come', () => {
  const segs = c.parseSegments(null);
  const seen = new Set();
  for (let i = 0; i < 40000; i++) seen.add(segs[c.pickIndex(segs, Infinity)].amount);
  assert.strictEqual(seen.size, 8);
});

test('player lock beats the lock for all', () => {
  assert.strictEqual(c.effectiveCap(cfg({ lockAll: 5 }), { lock: 50 }), 50);
  assert.strictEqual(c.effectiveCap(cfg({ lockAll: 5 }), { lock: 100 }), Infinity); // lock 81+ = whole wheel
  assert.strictEqual(c.effectiveCap(cfg({ lockAll: 5 }), { lock: 0 }), 5);
  assert.strictEqual(c.effectiveCap(cfg({ lockAll: 0 }), { lock: 0 }), 2); // lock 0 = only 2 coins
  const segs0 = c.parseSegments(null);
  assert.deepStrictEqual(c.eligibleIndexes(segs0, c.effectiveCap(cfg({ lockAll: 0 }), null)).map((i) => segs0[i].amount), [2]);
});

test('lock steps: 6-9 -> up to 10, up to 80 never 200/500/1000, 81+ whole wheel', () => {
  const cap = (l) => c.effectiveCap(cfg({ lockAll: l }), null);
  [[5, 5], [6, 10], [7, 10], [9, 10], [10, 10], [11, 25], [25, 25], [26, 50], [50, 50], [51, 150], [80, 150], [81, Infinity], [1000, Infinity]]
    .forEach(([l, want]) => assert.strictEqual(cap(l), want, 'lock ' + l));
  const segs = c.parseSegments(null);
  const wins = (l) => c.eligibleIndexes(segs, cap(l)).map((i) => segs[i].amount);
  assert.deepStrictEqual(wins(7), [2, 5, 10]);
  assert.ok(Math.max(...wins(80)) <= 100 && !wins(80).includes(500) && !wins(80).includes(1000));
  assert.ok(wins(81).includes(1000) && wins(81).includes(500));
});

test('lock below the smallest reward -> nothing to win', () => {
  assert.strictEqual(c.pickIndex(c.parseSegments(null), 1), -1);
});

test('weights are respected (zero weight never wins)', () => {
  const segs = [{ amount: 1, weight: 0 }, { amount: 2, weight: 1 }];
  for (let i = 0; i < 200; i++) assert.strictEqual(c.pickIndex(segs, Infinity), 1);
});

test('only ongoing/completed matches with enough entry fee count', () => {
  const ms = [
    { status: 'upcoming', entryFee: 50 }, { status: 'ongoing', entryFee: 10 }, { status: 'completed', entryFee: 20 },
    { status: 'ongoing', entryFee: 5 }, { status: 'cancelled', entryFee: 50 }, { status: 'completed', entryFee: 50, type: 'FREE' }, null,
  ];
  assert.strictEqual(c.countQualified(ms, cfg()), 2);
});

test('locked until the player has played enough matches; old matches are ignored by default', () => {
  const k = cfg();
  const ev = c.evaluate({ cfg: k, userCfg: c.normalizeUserCfg(null), state: null, qualified: 20, now: NOW });
  assert.strictEqual(ev.reason, 'need_matches');
  assert.strictEqual(ev.available, 0);
  const ev2 = c.evaluate({ cfg: cfg({ countPast: true }), userCfg: c.normalizeUserCfg(null), state: null, qualified: 20, now: NOW });
  assert.strictEqual(ev2.canSpin, true);
});

test('a spin uses up the required matches and counts the day', () => {
  const k = cfg();
  const u = c.normalizeUserCfg(null);
  const st = { init: true, consumed: 4 };
  const a = c.claimSpin({ cfg: k, userCfg: u, state: st, qualified: 12, now: NOW, amount: 5 });
  assert.strictEqual(a.consumed, 12);
  assert.strictEqual(a.dayCount, 1);
  assert.strictEqual(a.totalEarned, 5);
  assert.strictEqual(c.claimSpin({ cfg: k, userCfg: u, state: a, qualified: 12, now: NOW + 1000, amount: 5 }), null); // no matches left
});

test('daily limit and the next day reset', () => {
  const k = cfg({ matchesRequired: 0, dailyLimit: 2 });
  const u = c.normalizeUserCfg(null);
  let s = { init: true, consumed: 0 };
  s = c.claimSpin({ cfg: k, userCfg: u, state: s, qualified: 0, now: NOW, amount: 2 });
  s = c.claimSpin({ cfg: k, userCfg: u, state: s, qualified: 0, now: NOW + 1000, amount: 2 });
  assert.strictEqual(c.claimSpin({ cfg: k, userCfg: u, state: s, qualified: 0, now: NOW + 2000, amount: 2 }), null);
  assert.ok(c.claimSpin({ cfg: k, userCfg: u, state: s, qualified: 0, now: NOW + 26 * 3600 * 1000, amount: 2 }));
});

test('cooldown days between spins', () => {
  const k = cfg({ matchesRequired: 0, dailyLimit: 0, cooldownDays: 2 });
  const u = c.normalizeUserCfg(null);
  const s = c.claimSpin({ cfg: k, userCfg: u, state: { init: true }, qualified: 0, now: NOW, amount: 2 });
  const ev = c.evaluate({ cfg: k, userCfg: u, state: s, qualified: 0, now: NOW + 24 * 3600 * 1000 });
  assert.strictEqual(ev.reason, 'cooldown');
  assert.ok(ev.nextSpinAt > NOW);
  assert.ok(c.claimSpin({ cfg: k, userCfg: u, state: s, qualified: 0, now: NOW + 49 * 3600 * 1000, amount: 2 }));
});

test('admin switch off / blocked player / disabled', () => {
  assert.strictEqual(c.evaluate({ cfg: cfg({ enabled: false }), userCfg: c.normalizeUserCfg(null), state: null, qualified: 99, now: NOW }).reason, 'disabled');
  assert.strictEqual(c.evaluate({ cfg: cfg({ countPast: true }), userCfg: c.normalizeUserCfg({ blocked: true }), state: null, qualified: 99, now: NOW }).reason, 'blocked');
});

test('bad numbers from the database never break the config', () => {
  const k = c.normalizeConfig({ enabled: true, matchesRequired: 'abc', dailyLimit: -5, minEntryFee: null, lockAll: 'x' });
  assert.strictEqual(k.matchesRequired, 8);
  assert.strictEqual(k.dailyLimit, 0);
  assert.strictEqual(k.lockAll, 0);
});

test('admin bonus spin: works without matches, ignores daily limit and cooldown, never ignores OFF / blocked', () => {
  const c0 = cfg({ dailyLimit: 1, cooldownDays: 5 });
  const used = { init: true, consumed: 0, lastSpinTs: NOW - 1000, day: c.dayKey(NOW), dayCount: 1 };
  assert.strictEqual(c.evaluate({ cfg: c0, userCfg: {}, state: used, qualified: 0, now: NOW }).canSpin, false);
  const ev = c.evaluate({ cfg: c0, userCfg: {}, state: used, qualified: 0, now: NOW, bonus: 2 });
  assert.strictEqual(ev.canSpin, true);
  assert.strictEqual(ev.useBonus, true);
  assert.strictEqual(c.evaluate({ cfg: cfg({ enabled: false }), userCfg: {}, state: used, qualified: 0, now: NOW, bonus: 2 }).canSpin, false);
  assert.strictEqual(c.evaluate({ cfg: c0, userCfg: { blocked: true }, state: used, qualified: 0, now: NOW, bonus: 2 }).canSpin, false);
});

test('admin bonus spin does not use up matches or the daily count', () => {
  const c0 = cfg();
  const st = { init: true, consumed: 3, lastSpinTs: 0, day: c.dayKey(NOW), dayCount: 2, totalEarned: 5, totalSpins: 2 };
  const n = c.claimSpin({ cfg: c0, userCfg: {}, state: st, qualified: 3, now: NOW, amount: 10, bonus: 1 });
  assert.strictEqual(n.consumed, 3);
  assert.strictEqual(n.dayCount, 2);
  assert.strictEqual(n.lastSpinTs, 0);
  assert.strictEqual(n.totalSpins, 3);
  assert.strictEqual(n.totalEarned, 15);
});

test('cleanBonus keeps a whole number between 0 and 1000', () => {
  assert.strictEqual(c.cleanBonus(undefined), 0);
  assert.strictEqual(c.cleanBonus(-4), 0);
  assert.strictEqual(c.cleanBonus(3.9), 3);
  assert.strictEqual(c.cleanBonus(99999), 1000);
});
