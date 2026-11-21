'use strict';
// Lucky Wheel endpoints.  GET /api/spin/status  and  POST /api/spin
// The reward is picked and credited HERE (server side, Firebase Admin) - the app can never choose its own reward,
// because players are not allowed to raise their own wallet in the database rules.
const express = require('express');
const rateLimit = require('express-rate-limit');
const lib = require('./lib');
const { ApiError } = require('./util');
const core = require('./spin-core');

const router = express.Router();
const db = lib.db;

const limited = (windowMs, limit) => rateLimit({
  windowMs, limit, standardHeaders: true, legacyHeaders: false,
  handler: (_req, res) => res.status(429).json({ success: false, error: 'rate_limited', message: 'Too many requests. Please wait a moment' }),
});
const statusLimiter = limited(60 * 1000, 60);
const spinLimiter = limited(60 * 1000, 12);

async function verifiedPlayer(req) {
  const identity = await lib.verifyPlayer(req.headers.authorization);
  let snap;
  try { snap = await db.ref(`users/${identity.uid}`).get(); }
  catch { throw new ApiError(503, 'firebase_error', 'Could not verify your account'); }
  if (!snap.exists()) throw new ApiError(403, 'forbidden', 'Player account not found');
  const user = snap.val() || {};
  if (user.banned === true || (user.role && user.role !== 'user')) throw new ApiError(403, 'forbidden', 'This account cannot use Lucky Wheel');
  return { uid: identity.uid, user };
}

// Short cache: the status screen can be opened many times; the spin itself always recounts (fresh = true).
const qCache = new Map(); // key -> { n, exp }

/** Matches this player joined that count for the task (ongoing / completed and entry fee >= admin minimum). */
async function qualifiedFor(uid, cfg, fresh) {
  const key = `${uid}|${cfg.minEntryFee}`;
  const hit = qCache.get(key);
  if (!fresh && hit && hit.exp > Date.now()) return hit.n;
  let joined;
  try { joined = await db.ref(`joinedByUser/${uid}`).get(); }
  catch { throw new ApiError(503, 'firebase_error', 'Could not read your matches'); }
  const entries = joined.exists() ? Object.entries(joined.val() || {}).slice(-600) : [];
  const matches = [];
  for (let i = 0; i < entries.length; i += 25) {
    const chunk = entries.slice(i, i + 25);
    const rows = await Promise.all(chunk.map(async ([id, saved]) => {
      try {
        const status = (await db.ref(`matches/${id}/status`).get()).val();
        if (status === null || status === undefined) return null; // match deleted by the admin
        let fee = saved && typeof saved === 'object' ? saved.entryFee : undefined;
        let type = saved && typeof saved === 'object' ? saved.type : undefined;
        if (fee === undefined) {
          const [f, t] = await Promise.all([db.ref(`matches/${id}/entryFee`).get(), db.ref(`matches/${id}/type`).get()]);
          fee = f.val(); type = t.val();
        }
        return { status, entryFee: fee, type };
      } catch { return null; }
    }));
    matches.push(...rows);
  }
  const n = core.countQualified(matches, cfg);
  qCache.set(key, { n, exp: Date.now() + 15 * 1000 });
  if (qCache.size > 2000) qCache.clear();
  return n;
}

async function loadAll(uid) {
  let c, u, s, b;
  try {
    [c, u, s, b] = await Promise.all([db.ref('spinConfig').get(), db.ref(`spinUserCfg/${uid}`).get(), db.ref(`spinState/${uid}`).get(), db.ref(`spinBonus/${uid}`).get()]);
  } catch { throw new ApiError(503, 'firebase_error', 'Could not read Lucky Wheel settings'); }
  return { cfg: core.normalizeConfig(c.val()), userCfg: core.normalizeUserCfg(u.val()), state: s.exists() ? s.val() : null, bonus: core.cleanBonus(b.val()) };
}

function view(cfg, ev, creditTo) {
  return {
    enabled: cfg.enabled, canSpin: ev.canSpin, reason: ev.reason, message: core.lockedMessage(ev, cfg),
    required: ev.required, available: ev.available, minEntryFee: cfg.minEntryFee,
    dailyLimit: ev.dailyLimit, todayCount: ev.todayCount, cooldownDays: cfg.cooldownDays, nextSpinAt: ev.nextSpinAt,
    totalEarned: ev.state.totalEarned, totalSpins: ev.state.totalSpins, bonus: ev.bonus,
    segments: cfg.segments.map((s) => s.amount), creditTo,
  };
}

router.get('/status', statusLimiter, async (req, res, next) => {
  try {
    const { uid } = await verifiedPlayer(req);
    const { cfg, userCfg, state, bonus } = await loadAll(uid);
    const qualified = await qualifiedFor(uid, cfg, false);
    const ev = core.evaluate({ cfg, userCfg, state, qualified, now: Date.now(), bonus });
    // First visit: remember the starting point so old matches are not counted (unless the admin allowed it).
    if (cfg.enabled && !(state && state.init === true)) {
      await db.ref(`spinState/${uid}`).transaction((cur) => (cur && cur.init === true ? undefined : core.initialState(cfg, qualified))).catch(() => {});
    }
    res.json({ success: true, ...view(cfg, ev, cfg.creditTo) });
  } catch (e) { next(e); }
});

router.post('/', spinLimiter, async (req, res, next) => {
  try {
    const { uid, user } = await verifiedPlayer(req);
    const { cfg, userCfg, bonus } = await loadAll(uid);
    if (!cfg.enabled) throw new ApiError(403, 'spin_disabled', core.lockedMessage({ reason: 'disabled' }, cfg));
    if (userCfg.blocked) throw new ApiError(403, 'spin_blocked', core.lockedMessage({ reason: 'blocked' }, cfg));
    const qualified = await qualifiedFor(uid, cfg, true);
    const cap = core.effectiveCap(cfg, userCfg);
    const index = core.pickIndex(cfg.segments, cap);
    if (index < 0) throw new ApiError(503, 'spin_not_configured', core.lockedMessage({ reason: 'not_configured' }, cfg));
    const amount = cfg.segments[index].amount;
    const now = Date.now();

    // 1) Claim the spin. An admin-given bonus spin is used first (no match task / daily limit / cooldown needed).
    //    Otherwise the daily limit, cooldown and match task are re-checked inside the transaction.
    const stateRef = db.ref(`spinState/${uid}`);
    const bonusRef = db.ref(`spinBonus/${uid}`);
    let usedBonus = false; let prev = null; let claimed = null;
    if (bonus > 0) {
      // Firebase first runs this with a local guess (often null) and re-runs it with the real value, so a null guess must NOT abort.
      let took = false;
      const bt = await bonusRef.transaction((cur) => {
        const n = core.cleanBonus(cur);
        if (n > 0) { took = true; return n - 1 > 0 ? n - 1 : null; }
        took = false;
        return cur === null || cur === undefined ? null : undefined;
      });
      usedBonus = bt.committed && took;
    }
    if (usedBonus) {
      // Totals only (always allowed). If this write fails the bonus spin is given back below.
      try {
        const tr = await stateRef.transaction((cur) => {
          prev = cur === undefined ? null : cur;
          claimed = core.claimSpin({ cfg, userCfg, state: cur, qualified, now, amount, bonus: 1 });
          return claimed || undefined;
        });
        if (!tr.committed || !claimed) throw new Error('state not committed');
      } catch (e) {
        await bonusRef.transaction((cur) => core.cleanBonus(cur) + 1).catch(() => {});
        throw new ApiError(503, 'spin_failed', 'Could not start the spin. Please try again');
      }
    } else {
      const tr = await stateRef.transaction((cur) => {
        prev = cur === undefined ? null : cur;
        claimed = core.claimSpin({ cfg, userCfg, state: cur, qualified, now, amount, bonus: 0 });
        return claimed || undefined;
      });
      if (!tr.committed || !claimed) {
        const ev = core.evaluate({ cfg, userCfg, state: tr.snapshot ? tr.snapshot.val() : null, qualified, now, bonus: 0 });
        throw new ApiError(409, 'spin_locked', core.lockedMessage(ev, cfg) || 'Spin is not available right now');
      }
    }

    // 2) Credit the wallet. If this fails the claim is undone, so the player does not lose the spin.
    try {
      const credit = await db.ref(`users/${uid}/${cfg.creditTo}`).transaction((cur) => core.round2((Number(cur) || 0) + amount));
      if (!credit.committed) throw new Error('wallet not committed');
    } catch (e) {
      console.error('[spin] wallet credit failed', uid, e && e.message);
      if (usedBonus) {
        await bonusRef.transaction((cur) => core.cleanBonus(cur) + 1).catch(() => {});
        await stateRef.transaction((cur) => (cur === null || cur === undefined ? null : { ...cur, totalSpins: Math.max(0, (Number(cur.totalSpins) || 0) - 1), totalEarned: core.round2(Math.max(0, (Number(cur.totalEarned) || 0) - amount)) })).catch(() => {});
      } else {
        await stateRef.transaction((cur) => (cur === null || cur === undefined ? null : (cur.lastSpinTs === now ? (prev === null ? null : prev) : undefined))).catch(() => {});
      }
      throw new ApiError(503, 'wallet_credit_failed', 'Could not add the reward. Please try again');
    }

    // 3) Records (wallet history, spin history, admin log). The coins are already added, so a failure here is only logged.
    try {
      const key = db.ref('spinLog').push().key;
      await db.ref().update({
        [`transactions/${uid}/spin_${key}`]: { id: `spin_${key}`, type: 'spin', amount, note: 'Lucky Wheel reward', wallet: cfg.creditTo, ts: now, status: 'success' },
        [`spinHistory/${uid}/${key}`]: { amount, ts: now, wallet: cfg.creditTo, bonus: usedBonus },
        [`spinLog/${key}`]: { uid, username: String(user.username || ''), amount, ts: now, cap: Number.isFinite(cap) ? cap : 0, bonus: usedBonus, wallet: cfg.creditTo },
      });
    } catch (e) { console.error('[spin] history write failed', uid, e && e.message); }

    const ev = core.evaluate({ cfg, userCfg, state: claimed, qualified, now, bonus: usedBonus ? bonus - 1 : 0 });
    res.json({ success: true, amount, index, status: view(cfg, ev, cfg.creditTo) });
  } catch (e) { next(e); }
});

module.exports = { router };
