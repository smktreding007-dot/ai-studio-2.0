'use strict';
const test = require('node:test');
const assert = require('node:assert');
// lib.js needs env + firebase credentials at import time, so stub it for the pure-function tests
const Module = require('module');
const orig = Module._load;
Module._load = function (req, ...a) { if (req === './lib') return { db: {}, admin: {} }; return orig.call(this, req, ...a); };
const { buildRefunds, shouldCancel } = require('../match-auto-cancel');
Module._load = orig;

const base = { status: 'upcoming', minPlayers: 2, cancelAt: 1000, entryFee: 10, slots: { a: { uid: 'u1' } } };

test('cancels when fewer players than minimum after cancelAt', () => assert.strictEqual(shouldCancel(base, 2000), true));
test('does not cancel before cancelAt', () => assert.strictEqual(shouldCancel(base, 500), false));
test('minPlayers 0 never cancels', () => assert.strictEqual(shouldCancel({ ...base, minPlayers: 0 }, 2000), false));
test('enough players is kept', () => assert.strictEqual(shouldCancel({ ...base, slots: { a: { uid: 'u1' }, b: { uid: 'u2' } } }, 2000), false));
test('already refunded / not upcoming is skipped', () => {
  assert.strictEqual(shouldCancel({ ...base, refunded: true }, 2000), false);
  assert.strictEqual(shouldCancel({ ...base, status: 'ongoing' }, 2000), false);
});
test('refund goes back to the same wallet part', () => {
  const r = buildRefunds({ entryFee: 10, type: 'PAID', slots: { a: { uid: 'u1', dep: 4, win: 6 }, b: { uid: 'u2' } } });
  assert.deepStrictEqual(r.find((x) => x.uid === 'u1'), { uid: 'u1', dep: 4, win: 6 });
  assert.deepStrictEqual(r.find((x) => x.uid === 'u2'), { uid: 'u2', dep: 10, win: 0 });
});
test('free match refunds nothing', () => assert.deepStrictEqual(buildRefunds({ entryFee: 10, type: 'FREE', slots: { a: { uid: 'u1' } } }), []));
