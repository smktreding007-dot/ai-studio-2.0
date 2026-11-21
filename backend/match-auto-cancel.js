'use strict';
// Automatic cancel + refund for matches that did not get enough players.
// Admin sets per match:  minPlayers  (0 = never auto-cancel)  and  cancelAt  (ms time when the check happens).
// Every 30 seconds this job looks at upcoming matches whose cancelAt has passed; if fewer than minPlayers joined,
// the match is cancelled and every player gets the entry fee back to the SAME wallet part it was paid from.
const lib = require('./lib');
const db = lib.db;

const EVERY_MS = 30 * 1000;
let running = false;

function num(v) { const n = Number(v); return Number.isFinite(n) ? n : 0; }



/** Pure helper (tested): who gets how much back. Slots without a saved dep/win split are refunded to the deposit wallet. */
function buildRefunds(match) {
  const fee = String(match.type || '').toUpperCase() === 'FREE' ? 0 : num(match.entryFee);
  const dep = {}; const win = {};
  for (const s of Object.values(match.slots || {})) {
    const uid = s && s.uid ? String(s.uid) : '';
    if (!uid) continue;
    if (s.dep !== undefined || s.win !== undefined) { dep[uid] = (dep[uid] || 0) + num(s.dep); win[uid] = (win[uid] || 0) + num(s.win); }
    else dep[uid] = (dep[uid] || 0) + fee;
  }
  const out = [];
  for (const uid of new Set([...Object.keys(dep), ...Object.keys(win)])) {
    const d = dep[uid] || 0; const w = win[uid] || 0;
    if (d + w > 1e-9) out.push({ uid, dep: d, win: w });
  }
  return out;
}

/** Should this match be auto-cancelled right now? (pure, tested) */
function shouldCancel(match, now) {
  if (!match || match.status !== 'upcoming') return false;
  if (match.refunded === true || match.resultDeclared === true) return false;
  const min = num(match.minPlayers); const at = num(match.cancelAt);
  if (min <= 0 || at <= 0 || now < at) return false;
  const joined = Object.keys(match.slots || {}).length;
  return joined < min;
}

async function cancelOne(mid) {
  const ref = db.ref(`matches/${mid}`);
  // claim: only one server run can cancel a match
  const claim = await ref.child('refunded').transaction((cur) => (cur === true ? undefined : true));
  if (!claim.committed) return false;
  try {
    const m = (await ref.get()).val();
    if (!shouldCancelAfterClaim(m)) { await ref.child('refunded').remove(); return false; }
    const refunds = buildRefunds(m);
    const now = Date.now();
    const title = `${m.title || 'Match'} #${m.matchNo || ''}`.trim();
    const joined = Object.keys(m.slots || {}).length;
    const ups = {};
    let total = 0;
    const inc = lib.ServerValue && lib.ServerValue.increment;
    for (const r of refunds) {
      if (r.dep > 1e-9) ups[`users/${r.uid}/deposited`] = inc(r.dep);
      if (r.win > 1e-9) ups[`users/${r.uid}/winning`] = inc(r.win);
      const tid = db.ref(`transactions/${r.uid}`).push().key;
      ups[`transactions/${r.uid}/${tid}`] = { id: tid, type: 'refund', amount: r.dep + r.win, note: `Refund: ${title} cancelled (not enough players)`, ts: now, status: 'success' };
      total += r.dep + r.win;
    }
    ups[`matches/${mid}/status`] = 'cancelled';
    ups[`matches/${mid}/cancelledAt`] = now;
    ups[`matches/${mid}/cancelReason`] = `Auto cancelled: only ${joined}/${num(m.minPlayers)} players joined`;
    await db.ref().update(ups);
    console.log(`Auto-cancelled ${title} (${mid}): ${refunds.length} players refunded, ${total} coins`);
    notify(refunds.map((r) => r.uid), title).catch(() => {});
    return true;
  } catch (e) {
    await ref.child('refunded').remove().catch(() => {}); // release the claim so the next run can retry
    throw e;
  }
}

// re-check after the claim: somebody may have joined in the last second
function shouldCancelAfterClaim(m) {
  if (!m || m.status !== 'upcoming' || m.resultDeclared === true) return false;
  const min = num(m.minPlayers);
  return min > 0 && Object.keys(m.slots || {}).length < min;
}

async function notify(uids, title) {
  const tokens = [];
  for (const uid of uids) {
    const t = (await db.ref(`users/${uid}/fcmToken`).get()).val();
    if (typeof t === 'string' && t) tokens.push(t);
  }
  for (let i = 0; i < tokens.length; i += 500) {
    await lib.messaging.sendEachForMulticast({
      tokens: tokens.slice(i, i + 500),
      notification: { title: 'Match cancelled', body: `${title} was cancelled (not enough players). Your entry fee is refunded.` },
      android: { priority: 'high' },
    });
  }
}

async function sweep() {
  if (running) return;
  running = true;
  try {
    const snap = await db.ref('matches').orderByChild('status').equalTo('upcoming').get();
    const now = Date.now();
    const due = [];
    snap.forEach((c) => { if (shouldCancel(c.val(), now)) due.push(c.key); });
    for (const mid of due) { try { await cancelOne(mid); } catch (e) { console.error('Auto-cancel failed for', mid, e.message); } }
  } catch (e) { console.error('Auto-cancel sweep failed:', e.message); }
  finally { running = false; }
}

function start() {
  setInterval(sweep, EVERY_MS).unref?.();
  setTimeout(sweep, 5000);
  console.log('Match auto-cancel job started (every 30s)');
}

module.exports = { start, sweep, buildRefunds, shouldCancel };
