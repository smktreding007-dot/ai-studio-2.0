'use strict';
// Lucky Wheel (Spin) rules. Everything in this file is pure (no Firebase, no network) so it is unit tested.
// The admin controls everything from the admin app -> "Lucky Wheel" (database node `spinConfig`, per-player `spinUserCfg/<uid>`).

const DAY_MS = 24 * 60 * 60 * 1000;
const IST_OFFSET_MS = 330 * 60 * 1000;

const DEFAULT_SEGMENTS = [
  { amount: 2, weight: 30 }, { amount: 5, weight: 25 }, { amount: 10, weight: 18 }, { amount: 25, weight: 12 },
  { amount: 50, weight: 8 }, { amount: 100, weight: 4 }, { amount: 500, weight: 2 }, { amount: 1000, weight: 1 },
];

function num(v, d = 0) { const n = Number(v); return Number.isFinite(n) ? n : d; }
function clamp(v, lo, hi) { return Math.min(hi, Math.max(lo, v)); }
function round2(v) { return Math.round(v * 100) / 100; }

/** Wheel rewards from the database (array or object of {amount, weight}); wrong / empty data falls back to the default wheel. */
function parseSegments(raw) {
  const list = Array.isArray(raw) ? raw : (raw && typeof raw === 'object' ? Object.values(raw) : []);
  const out = [];
  for (const s of list) {
    if (!s || typeof s !== 'object') continue;
    const amount = round2(num(s.amount, NaN));
    const weight = num(s.weight, 1);
    if (!Number.isFinite(amount) || amount <= 0 || amount > 100000 || weight < 0) continue;
    out.push({ amount, weight });
    if (out.length >= 12) break;
  }
  return out.length >= 2 ? out : DEFAULT_SEGMENTS.map((s) => ({ ...s }));
}

/** Admin settings with safe defaults and limits (a missing / broken value can never crash the spin). */
function normalizeConfig(raw) {
  const c = raw && typeof raw === 'object' ? raw : {};
  return {
    enabled: c.enabled === true,
    matchesRequired: clamp(Math.floor(num(c.matchesRequired, 8)), 0, 100),
    minEntryFee: clamp(num(c.minEntryFee, 10), 0, 100000),
    dailyLimit: clamp(Math.floor(num(c.dailyLimit, 3)), 0, 100),     // 0 = no daily limit
    cooldownDays: clamp(num(c.cooldownDays, 0), 0, 365),              // minimum days between two spins, 0 = none
    lockAll: clamp(num(c.lockAll, 0), 0, 1000000),                    // highest coin a player can win, 0 = no lock (any reward)
    creditTo: c.creditTo === 'winning' ? 'winning' : 'deposited',
    countPast: c.countPast === true,                                  // true = matches played before the first spin also count
    segments: parseSegments(c.segments),
  };
}

function normalizeUserCfg(raw) {
  const c = raw && typeof raw === 'object' ? raw : {};
  return { blocked: c.blocked === true, lock: clamp(num(c.lock, 0), 0, 1000000) };
}

/**
 * Lock level -> highest reward that can come (the database rules use the SAME steps):
 * lock up to 5 -> 5, up to 10 -> 10, up to 25 -> 25, up to 50 -> 50, up to 80 -> 150 (never 200 / 500 / 1000),
 * 81 or more -> the whole wheel. So 6, 7, 8, 9 give the chance of 5 or 10 coins, and a bigger lock opens bigger rewards.
 */
function lockCeiling(lock) {
  if (lock <= 5) return 5;
  if (lock <= 10) return 10;
  if (lock <= 25) return 25;
  if (lock <= 50) return 50;
  if (lock <= 80) return 150;
  return Infinity;
}

/** Highest reward this player can win: the player's own lock first, then the lock for all players, otherwise no limit. */
function effectiveCap(cfg, userCfg) {
  if (userCfg && userCfg.lock > 0) return lockCeiling(userCfg.lock);
  if (cfg && cfg.lockAll > 0) return lockCeiling(cfg.lockAll);
  return 2; // lock 0 = only 2 coins
}

/** Indexes (in the full wheel) of the rewards this player may win. */
function eligibleIndexes(segments, cap) {
  const out = [];
  segments.forEach((s, i) => { if (s.weight > 0 && s.amount <= cap + 1e-9) out.push(i); });
  return out;
}

/** Weighted random pick. `rnd` is injectable for tests. Returns the index in the full wheel, or -1 when nothing can be won. */
function pickIndex(segments, cap, rnd = Math.random) {
  const idx = eligibleIndexes(segments, cap);
  if (!idx.length) return -1;
  const total = idx.reduce((a, i) => a + segments[i].weight, 0);
  if (total <= 0) return idx[Math.floor(rnd() * idx.length)];
  let r = rnd() * total;
  for (const i of idx) { r -= segments[i].weight; if (r < 0) return i; }
  return idx[idx.length - 1];
}

/** IST calendar day, used for the daily spin limit. */
function dayKey(ts) {
  const d = new Date(ts + IST_OFFSET_MS);
  return d.getUTCFullYear() * 10000 + (d.getUTCMonth() + 1) * 100 + d.getUTCDate();
}

/** How many of the player's joined matches count for the spin task (match is ongoing/completed and the entry fee is high enough). */
function countQualified(matches, cfg) {
  let n = 0;
  for (const m of matches) {
    if (!m) continue;
    const st = String(m.status || '');
    if (st !== 'ongoing' && st !== 'completed') continue;
    const fee = String(m.type || '').toUpperCase() === 'FREE' ? 0 : num(m.entryFee);
    if (fee + 1e-9 >= cfg.minEntryFee) n++;
  }
  return n;
}

/** Spins given by the admin (database `spinBonus/<uid>`): a whole number 0..1000. */
function cleanBonus(raw) {
  return clamp(Math.floor(num(raw, 0)), 0, 1000);
}

function cleanState(raw) {
  const s = raw && typeof raw === 'object' ? raw : {};
  return {
    init: s.init === true,
    consumed: Math.max(0, Math.floor(num(s.consumed))),
    lastSpinTs: num(s.lastSpinTs),
    day: num(s.day),
    dayCount: Math.max(0, Math.floor(num(s.dayCount))),
    totalEarned: round2(Math.max(0, num(s.totalEarned))),
    totalSpins: Math.max(0, Math.floor(num(s.totalSpins))),
  };
}

/** State seen by a player the first time. Old matches are not counted unless the admin turned `countPast` on. */
function initialState(cfg, qualified) {
  return { init: true, consumed: cfg.countPast ? 0 : qualified, lastSpinTs: 0, day: 0, dayCount: 0, totalEarned: 0, totalSpins: 0 };
}

/**
 * Can this player spin right now? Returns the numbers shown in the app and, when locked, the reason:
 * disabled | blocked | need_matches | daily_limit | cooldown | not_configured
 */
function evaluate({ cfg, userCfg, state, qualified, now, cap, bonus }) {
  const st = state && state.init ? cleanState(state) : initialState(cfg, qualified);
  const available = Math.max(0, qualified - st.consumed);
  const today = dayKey(now);
  const todayCount = st.day === today ? st.dayCount : 0;
  const nextByCooldown = cfg.cooldownDays > 0 && st.lastSpinTs > 0 ? st.lastSpinTs + Math.round(cfg.cooldownDays * DAY_MS) : 0;
  const c = cap === undefined ? effectiveCap(cfg, userCfg) : cap;
  const bonusSpins = cleanBonus(bonus);
  let reason = '';
  let useBonus = false;
  if (!cfg.enabled) reason = 'disabled';
  else if (userCfg && userCfg.blocked) reason = 'blocked';
  else if (eligibleIndexes(cfg.segments, c).length === 0) reason = 'not_configured';
  else if (bonusSpins > 0) useBonus = true; // spins given by the admin skip the match task, daily limit and cooldown
  else if (available < cfg.matchesRequired) reason = 'need_matches';
  else if (cfg.dailyLimit > 0 && todayCount >= cfg.dailyLimit) reason = 'daily_limit';
  else if (nextByCooldown > now) reason = 'cooldown';
  return {
    canSpin: reason === '', reason, state: st, bonus: bonusSpins, useBonus,
    required: cfg.matchesRequired, available, qualified,
    todayCount, dailyLimit: cfg.dailyLimit,
    nextSpinAt: reason === 'cooldown' ? nextByCooldown : 0,
  };
}

/**
 * Used inside the database transaction: returns the new state when this spin is allowed, or null (and nothing changes).
 * It is pure and re-checks everything, so two quick taps or two phones can never spin more than the rules allow.
 */
function claimSpin({ cfg, userCfg, state, qualified, now, amount, bonus }) {
  const ev = evaluate({ cfg, userCfg, state, qualified, now, bonus });
  if (!ev.canSpin) return null;
  const s = ev.state;
  if (ev.useBonus) {
    // Admin-given spin: the match task, the daily count and the cooldown clock are NOT touched.
    return { ...s, totalSpins: s.totalSpins + 1, totalEarned: round2(s.totalEarned + amount) };
  }
  const today = dayKey(now);
  return {
    ...s,
    consumed: s.consumed + cfg.matchesRequired,
    lastSpinTs: now,
    day: today,
    dayCount: (s.day === today ? s.dayCount : 0) + 1,
    totalSpins: s.totalSpins + 1,
    totalEarned: round2(s.totalEarned + amount),
  };
}

function lockedMessage(ev, cfg) {
  switch (ev.reason) {
    case 'disabled': return 'Lucky Wheel is not available right now';
    case 'blocked': return 'Lucky Wheel is not available for your account';
    case 'not_configured': return 'Lucky Wheel is being set up. Try again later';
    case 'need_matches': {
      const more = Math.max(0, cfg.matchesRequired - ev.available);
      return `Play ${more} more match${more === 1 ? '' : 'es'} to unlock Lucky Wheel`;
    }
    case 'daily_limit': return 'Daily spin limit reached. Come back tomorrow';
    case 'cooldown': return 'Next spin is not available yet';
    default: return '';
  }
}

module.exports = {
  DEFAULT_SEGMENTS, parseSegments, normalizeConfig, normalizeUserCfg, effectiveCap, lockCeiling, eligibleIndexes, pickIndex,
  dayKey, countQualified, cleanBonus, cleanState, initialState, evaluate, claimSpin, lockedMessage, round2,
};
