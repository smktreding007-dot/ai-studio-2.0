'use strict';

const crypto = require('crypto');
const https = require('https');
const express = require('express');
const rateLimit = require('express-rate-limit');
const lib = require('./lib');
const { ApiError } = require('./util');
const { parsePaise, formatRupees, makeGatewayOrderId, validCustomDepositAmount, isLocalOrderId, isZapupiPaymentUrl, amountsMatch } = require('./zapupi-core');

const router = express.Router();
const db = lib.db;
// The admin app can save the ZapUPI API key in secrets/zapupi/apiKey (admin-only in the database rules).
// It is read here with a short cache; the Railway variable ZAPUPI_API_KEY is still used when nothing is saved.
let dbKey = '';
let dbKeyAt = 0;
async function refreshKey() {
  if (Date.now() - dbKeyAt < 15000) return;
  try { const s = await db.ref('secrets/zapupi/apiKey').get(); dbKey = String(s.val() || '').trim(); } catch { /* keep the previous value */ }
  dbKeyAt = Date.now();
}
const API_ROOT = 'https://pay.zapupi.com/api';
const limited = (windowMs, limit) => rateLimit({
  windowMs, limit, standardHeaders: true, legacyHeaders: false,
  handler: (_req, res) => res.status(429).json({ success: false, error: 'rate_limited', message: 'Too many payment requests. Please wait and try again.' }),
});
const createLimiter = limited(10 * 60 * 1000, 8);
const statusLimiter = limited(60 * 1000, 30);
const webhookLimiter = limited(60 * 1000, 120);

function apiKey() {
  let key = String(dbKey || process.env.ZAPUPI_API_KEY || '').trim();
  if (key.length >= 2 && ((key[0] === '"' && key[key.length - 1] === '"') || (key[0] === "'" && key[key.length - 1] === "'")))
    key = key.slice(1, -1).trim();
  if (!key) throw new ApiError(503, 'payments_not_configured', 'Online payments are not configured yet');
  return key;
}

function safeProviderText(value, limit = 160) {
  if (typeof value !== 'string' && typeof value !== 'number') return '';
  let text = String(value).replace(/[\r\n\t]+/g, ' ').replace(/\s+/g, ' ').trim();
  const secret = String(dbKey || process.env.ZAPUPI_API_KEY || '').trim().replace(/^(["'])(.*)\1$/, '$2');
  if (secret) text = text.split(secret).join('[redacted]');
  return text.replace(/\b(zap[_ -]?key|api[_ -]?key|secret|token|authorization)\b\s*[:=]\s*[^,;\s]+/gi, '$1=[redacted]').slice(0, limit);
}

function publicOrigin(req) {
  const override = process.env.ZAPUPI_WEBHOOK_URL || '';
  const raw = override || `${req.protocol}://${req.get('host')}/api/payments/zapupi/webhook`;
  try {
    const url = new URL(raw);
    if (url.protocol !== 'https:' || !url.hostname || url.hostname === 'localhost' || url.username || url.password)
      throw new Error('invalid');
    return url.origin;
  } catch { throw new ApiError(503, 'payments_not_configured', 'The payment server address is not HTTPS, so ZapUPI cannot call it back'); }
}

// The callback address is built automatically from the address the request came in on, so nothing has to be
// configured in Railway. ZAPUPI_WEBHOOK_URL is still honoured when set (optional override).
function webhookUrl(req) {
  const override = process.env.ZAPUPI_WEBHOOK_URL || '';
  if (override) {
    try {
      const url = new URL(override);
      if (url.protocol === 'https:' && url.hostname && url.hostname !== 'localhost' && !url.username && !url.password)
        return url.toString();
    } catch { /* fallback below */ }
  }
  return `${publicOrigin(req)}/api/payments/zapupi/webhook`;
}

function returnUrl(req, orderId, result = 'complete') {
  return `${publicOrigin(req)}/api/payments/zapupi/return?orderId=${encodeURIComponent(orderId)}&result=${encodeURIComponent(result)}`;
}

function httpsPostJsonOnce(urlString, bodyObj, timeoutMs = 15000) {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify(bodyObj);
    const u = new URL(urlString);
    const req = https.request({
      hostname: u.hostname,
      port: u.port || 443,
      path: u.pathname + u.search,
      method: 'POST',
      family: 4,
      agent: false,
      timeout: timeoutMs,
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        'Content-Length': Buffer.byteLength(payload),
        'Connection': 'close',
      },
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        resolve({ status: res.statusCode || 0, ok: (res.statusCode || 0) >= 200 && (res.statusCode || 0) < 300, text: Buffer.concat(chunks).toString('utf8') });
      });
    });
    req.on('timeout', () => {
      const err = new Error('ZapUPI request timed out');
      err.name = 'TimeoutError';
      err.code = 'ETIMEDOUT';
      req.destroy(err);
    });
    req.on('error', reject);
    req.write(payload);
    req.end();
  });
}

async function zapupiPost(endpoint, body) {
  const urlString = `${API_ROOT}/${endpoint}`;
  const maxAttempts = endpoint === 'order-status' ? 2 : 1;
  let res;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      res = await httpsPostJsonOnce(urlString, body, 15000);
      break;
    } catch (e) {
      const code = safeProviderText(e && (e.code || (e.cause && e.cause.code)), 40);
      if (attempt < maxAttempts) {
        await new Promise((r) => setTimeout(r, 350));
        continue;
      }
      console.error('[ZapUPI] provider request failed', JSON.stringify({
        endpoint,
        errorType: safeProviderText(e && e.name, 40),
        errorCode: code,
        errorMessage: safeProviderText(e && e.message, 120),
      }));
      throw new ApiError(e && (e.name === 'TimeoutError' || e.code === 'ETIMEDOUT') ? 504 : 502,
        'gateway_unavailable', 'Could not reach the payment provider. Please try again.');
    }
  }
  let data;
  try { data = JSON.parse(res.text); } catch {
    console.error('[ZapUPI] provider returned non-JSON response', JSON.stringify({ endpoint, httpStatus: res.status }));
    throw new ApiError(502, 'gateway_reply_invalid', 'The payment provider returned an invalid response');
  }
  if (!res.ok || !data || data.status !== 'success') {
    const diagnostic = {
      endpoint,
      httpStatus: res.status,
      providerStatus: safeProviderText(data && data.status, 40),
      providerCode: safeProviderText(data && (data.code || data.error_code || data.error), 80),
      providerMessage: safeProviderText(data && data.message, 160),
    };
    console.error('[ZapUPI] provider rejected request', JSON.stringify(diagnostic));
    throw new ApiError(502, 'gateway_rejected', `ZapUPI rejected the request (HTTP ${res.status}). Check Railway logs for the sanitized provider reason.`);
  }
  return data;
}

async function verifiedPlayer(req) {
  const identity = await lib.verifyPlayer(req.headers.authorization);
  let snap;
  try { snap = await db.ref(`users/${identity.uid}`).get(); }
  catch { throw new ApiError(503, 'firebase_error', 'Could not verify your account'); }
  if (!snap.exists()) throw new ApiError(403, 'forbidden', 'Player account not found');
  const user = snap.val() || {};
  if (user.banned === true || (user.role && user.role !== 'user')) throw new ApiError(403, 'forbidden', 'This account cannot make wallet payments');
  return { uid: identity.uid, user };
}

async function findLocalOrder(orderId) {
  if (!isLocalOrderId(orderId)) return null;
  const snap = await db.ref(`zapupiPayments/${orderId}`).get();
  return snap.exists() ? snap.val() : null;
}

async function setOrderFailure(orderId, status, detail, remote = {}) {
  const now = Date.now();
  await db.ref().update({
    [`zapupiPayments/${orderId}/status`]: status,
    [`zapupiPayments/${orderId}/gatewayStatus`]: String(remote.status || '').slice(0, 32),
    [`zapupiPayments/${orderId}/gatewayTxnId`]: String(remote.txn_id || '').slice(0, 100),
    [`zapupiPayments/${orderId}/updatedAt`]: now,
    [`zapupiPayments/${orderId}/reviewNote`]: detail || null,
    [`deposits/${orderId}/status`]: status === 'failed' || status === 'test' ? 'rejected' : 'pending',
    [`deposits/${orderId}/utr`]: String(remote.utr || '').slice(0, 100),
    [`deposits/${orderId}/gatewayStatus`]: String(remote.status || '').slice(0, 32),
    [`deposits/${orderId}/gatewayReview`]: detail || null,
  });
  return status;
}

async function creditOnce(orderId, order, remote) {
  const uid = String(order.uid || '');
  const userRef = db.ref(`users/${uid}`);
  const amountPaise = Number(order.amountPaise);
  const amount = amountPaise / 100;
  if (!uid || !Number.isSafeInteger(amountPaise) || amountPaise < 1)
    throw new ApiError(503, 'payment_record_invalid', 'The payment order could not be verified');

  // A transaction on the user record combines the wallet increment and its idempotency marker.
  // If delivery is retried after a crash, the marker prevents a duplicate credit.
  const result = await userRef.transaction((current) => {
    if (!current) return;
    const credited = current.zapupiCreditedOrders || {};
    if (credited[orderId] === true) return;
    const oldBalance = Number(current.deposited || 0);
    if (!Number.isFinite(oldBalance) || oldBalance < 0) return;
    current.deposited = Math.round((oldBalance + amount) * 100) / 100;
    credited[orderId] = true;
    current.zapupiCreditedOrders = credited;
    return current;
  }, undefined, false);
  if (!result.committed) {
    const marker = await userRef.child(`zapupiCreditedOrders/${orderId}`).get();
    if (marker.val() !== true) throw new ApiError(503, 'wallet_credit_failed', 'Payment confirmed, but wallet update needs retry');
  }

  const now = Date.now();
  const txnId = `zapupi_${orderId}`;
  const gatewayTxnId = String(remote.txn_id || '').slice(0, 100);
  const utr = String(remote.utr || '').slice(0, 100);
  await db.ref().update({
    [`zapupiPayments/${orderId}/status`]: 'success',
    [`zapupiPayments/${orderId}/gatewayStatus`]: 'Success',
    [`zapupiPayments/${orderId}/gatewayTxnId`]: gatewayTxnId,
    [`zapupiPayments/${orderId}/utr`]: utr,
    [`zapupiPayments/${orderId}/environment`]: String(remote.environment || '').slice(0, 32),
    [`zapupiPayments/${orderId}/verifiedAt`]: now,
    [`zapupiPayments/${orderId}/updatedAt`]: now,
    [`deposits/${orderId}/status`]: 'approved',
    [`deposits/${orderId}/utr`]: utr,
    [`deposits/${orderId}/gatewayTxnId`]: gatewayTxnId,
    [`deposits/${orderId}/gatewayStatus`]: 'Success',
    [`transactions/${uid}/${txnId}`]: {
      id: txnId, type: 'deposit', amount, note: String(order.description || 'ZapUPI wallet top-up').slice(0, 100), ts: now, status: 'success',
    },
  });
  return 'success';
}

async function reconcileOrder(orderId, order) {
  if (order.status === 'success' || order.status === 'failed' || order.status === 'test') return order.status;
  const statusRequestId = String(order.gatewayOrderId || orderId);
  const result = await zapupiPost('order-status', { zap_key: apiKey(), order_id: statusRequestId });
  const remote = result.data;
  if (!remote || !['Pending', 'Success', 'Failed'].includes(String(remote.status)))
    throw new ApiError(502, 'gateway_status_invalid', 'Could not verify the payment status');
  if (remote.order_id !== undefined && String(remote.order_id) !== statusRequestId)
    throw new ApiError(502, 'gateway_order_mismatch', 'Could not verify the payment order');

  if (remote.status === 'Pending') {
    await db.ref(`zapupiPayments/${orderId}`).update({ gatewayStatus: 'Pending', updatedAt: Date.now() });
    return 'pending';
  }
  if (remote.status === 'Failed') return setOrderFailure(orderId, 'failed', null, remote);

  if (!amountsMatch(remote, Number(order.amountPaise)))
    return setOrderFailure(orderId, 'amount_mismatch', 'Gateway amount requires manual review', remote);
  const environment = String(remote.environment || '').toLowerCase();
  if (environment === 'test' && process.env.ZAPUPI_ALLOW_TEST_PAYMENTS !== 'true')
    return setOrderFailure(orderId, 'test', 'Test-environment payments are not credited', remote);
  if (!['cashier', 'zappay', 'test'].includes(environment))
    return setOrderFailure(orderId, 'amount_mismatch', 'Gateway environment requires manual review', remote);

  return creditOnce(orderId, order, remote);
}

// Admin app "TEST PAYMENT SETUP" button: tells the admin exactly what is missing.
router.post('/diagnose', async (req, res, next) => {
  try {
    await lib.verifyAdmin(req.headers.authorization);
    dbKeyAt = 0; await refreshKey();
    const settings = (await db.ref('settings').get()).val() || {};
    const keyOk = !!String(dbKey || process.env.ZAPUPI_API_KEY || '').trim();
    let hook = '';
    try { hook = webhookUrl(req); } catch (e) { hook = ''; }
    const enabled = String(settings.zapupiEnabled || '').toLowerCase() === 'true';
    const problems = [];
    if (!keyOk) problems.push('ZapUPI key is not saved');
    if (!enabled) problems.push('ZapUPI deposits are set to Disabled in Settings');
    if (!hook) problems.push('Server address is not HTTPS');
    res.json({ success: true, server: 'online', keySaved: keyOk, enabled, callback: hook, ready: problems.length === 0, problems });
  } catch (e) { next(e); }
});

router.post('/create-order', createLimiter, async (req, res, next) => {
  try {
    await refreshKey();
    const { uid, user } = await verifiedPlayer(req);
    const settingsSnapshot = await db.ref('settings').get();
    const settings = settingsSnapshot.val() || {};
    if (String(settings.zapupiEnabled || '').toLowerCase() === 'false')
      throw new ApiError(503, 'payments_disabled', 'Online payments are temporarily disabled');
    const amountPaise = parsePaise(req.body && req.body.amount);
    const configuredMin = parsePaise(settings.minDeposit || '10') || 1000;
    const maxPaise = parsePaise(process.env.ZAPUPI_MAX_AMOUNT_INR || '100000') || 10000000;
    if (!validCustomDepositAmount(amountPaise, configuredMin, maxPaise))
      throw new ApiError(400, 'invalid_amount', `Amount must be between ₹${formatRupees(Math.max(configuredMin, 100))} and ₹${formatRupees(maxPaise)}`);

    const key = apiKey();
    const callback = webhookUrl(req);
    const orderId = `M4ZAP-${crypto.randomBytes(12).toString('hex').toUpperCase()}`;
    const providerOrderId = makeGatewayOrderId(Date.now(), crypto.randomInt(0, 1000));
    const amount = amountPaise / 100;
    const username = String(user.username || 'Player').slice(0, 64);
    const description = String(settings.zapupiDescription || 'DOUx24 wallet top-up').replace(/[\r\n\t]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 80) || 'DOUx24 wallet top-up';
    const now = Date.now();
    const order = {
      orderId, uid, username, amount, amountPaise, description, status: 'creating', gateway: 'zapupi',
      createdAt: now, updatedAt: now,
    };
    await db.ref().update({
      [`zapupiPayments/${orderId}`]: order,
      [`deposits/${orderId}`]: { uid, username, amount, description, utr: '', status: 'pending', ts: now, paymentMethod: 'ZapUPI', gateway: 'zapupi', orderId },
    });

    let gatewayOrder;
    try {
      const payload = {
        zap_key: key,
        order_id: providerOrderId,
        amount: formatRupees(amountPaise),
        remark: description,
        webhook_url: callback,
        redirect_url: returnUrl(req, orderId, 'complete'),
        success_url: returnUrl(req, orderId, 'success'),
        failed_url: returnUrl(req, orderId, 'failed'),
        timeout_url: returnUrl(req, orderId, 'timeout'),
      };
      const mobile = String(user.phone || '').replace(/\D/g, '');
      if (/^\d{10}$/.test(mobile)) payload.customer_mobile = mobile;
      gatewayOrder = await zapupiPost('create-order', payload);
      if (!isZapupiPaymentUrl(gatewayOrder.payment_url)) throw new ApiError(502, 'gateway_reply_invalid', 'Payment provider returned an invalid checkout address');
    } catch (e) {
      // Preserve the local record so a late webhook can still be independently reconciled.
      await db.ref(`zapupiPayments/${orderId}`).update({ status: 'create_error', updatedAt: Date.now() }).catch(() => {});
      if (e instanceof ApiError) throw e;
      throw new ApiError(502, 'gateway_rejected', 'Could not create a payment order');
    }

    const gatewayOrderId = String(gatewayOrder.order_id || providerOrderId).slice(0, 128);
    const orderMapUpdates = {};
    for (const externalId of new Set([providerOrderId, gatewayOrderId])) {
      const mappingKey = crypto.createHash('sha256').update(externalId).digest('hex');
      orderMapUpdates[`zapupiOrderMap/${mappingKey}`] = orderId;
    }
    await db.ref().update({
      [`zapupiPayments/${orderId}/status`]: 'pending',
      [`zapupiPayments/${orderId}/providerOrderId`]: providerOrderId,
      [`zapupiPayments/${orderId}/gatewayOrderId`]: gatewayOrderId,
      [`zapupiPayments/${orderId}/gatewayCreateTxnId`]: String(gatewayOrder.txn_id || '').slice(0, 100),
      [`zapupiPayments/${orderId}/updatedAt`]: Date.now(),
      ...orderMapUpdates,
    });
    res.json({ success: true, orderId, paymentUrl: gatewayOrder.payment_url });
  } catch (e) { next(e); }
});

router.post('/status', statusLimiter, async (req, res, next) => {
  try {
    await refreshKey();
    const { uid } = await verifiedPlayer(req);
    const orderId = String((req.body && req.body.orderId) || '');
    if (!isLocalOrderId(orderId)) throw new ApiError(400, 'invalid_order', 'Invalid payment order');
    const order = await findLocalOrder(orderId);
    if (!order || order.uid !== uid) throw new ApiError(404, 'not_found', 'Payment order not found');
    const status = await reconcileOrder(orderId, order);
    res.json({ success: true, orderId, status });
  } catch (e) { next(e); }
});

router.post('/webhook', webhookLimiter, async (req, res) => {
  await refreshKey();
  const body = req.body || {};
  const externalOrderId = String(body.order_id || '').slice(0, 128);
  let orderId = externalOrderId;
  try {
    if (!isLocalOrderId(orderId)) {
      if (!externalOrderId) return res.status(200).json({ status: 'ok' });
      const mappingKey = crypto.createHash('sha256').update(externalOrderId).digest('hex');
      const mapped = await db.ref(`zapupiOrderMap/${mappingKey}`).get();
      orderId = String(mapped.val() || '');
    }
    if (!isLocalOrderId(orderId)) return res.status(200).json({ status: 'ok' });
    const order = await findLocalOrder(orderId);
    if (!order) return res.status(200).json({ status: 'ok' });
    await reconcileOrder(orderId, order);
    return res.status(200).json({ status: 'ok' });
  } catch (_e) {
    // No payment is credited from webhook fields. A failed server-side status check is retriable.
    return res.status(503).json({ status: 'retry' });
  }
});

router.get('/return', statusLimiter, async (req, res) => {
  res.set({ 'Cache-Control': 'no-store', 'Content-Type': 'text/html; charset=utf-8' });
  await refreshKey();
  const rawOrderId = String((req.query && (req.query.orderId || req.query.order_id)) || '').slice(0, 128);
  const hint = String((req.query && req.query.result) || '').toLowerCase();
  const tries = Math.max(0, Math.min(5, Number(req.query && req.query.t) || 0));
  let orderId = isLocalOrderId(rawOrderId) ? rawOrderId : '';
  if (!orderId && rawOrderId) {
    try {
      const mappingKey = crypto.createHash('sha256').update(rawOrderId).digest('hex');
      const mapped = await db.ref(`zapupiOrderMap/${mappingKey}`).get();
      const cand = String(mapped.val() || '');
      if (isLocalOrderId(cand)) orderId = cand;
    } catch { /* ignore */ }
  }
  let status = hint === 'failed' || hint === 'timeout' ? hint : 'pending';
  let amountText = '';
  if (orderId) {
    try {
      const order = await findLocalOrder(orderId);
      if (order) {
        if (Number.isFinite(Number(order.amount))) amountText = `₹${Number(order.amount).toFixed(2)}`;
        status = await reconcileOrder(orderId, order);
      }
    } catch { /* keep current status so user sees clean return page instead of error */ }
  }
  const ok = status === 'success';
  const bad = status === 'failed' || status === 'timeout' || status === 'test' || status === 'amount_mismatch';
  const badgeColor = ok ? '#2ECC71' : bad ? '#FF4D4F' : '#F59E0B';
  const title = ok ? 'Payment Confirmed!' : bad ? 'Payment Not Completed' : 'Verifying Payment...';
  const subtitle = ok
    ? `${amountText ? `${amountText} has been` : 'Coins have been'} added to your DOUx24 wallet.`
    : bad
      ? 'If any money was debited, it will auto-refund or update shortly. Return to your DOUx24 Wallet to check.'
      : 'Confirming your UPI payment with ZapUPI. Wait a few seconds or return to DOUx24 Wallet and tap Check Status.';
  const autoRefresh = !ok && !bad && orderId && tries < 3
    ? `<meta http-equiv="refresh" content="3;url=/api/payments/zapupi/return?orderId=${encodeURIComponent(orderId)}&t=${tries + 1}">`
    : '';
  return res.status(200).send(`<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1" />
  ${autoRefresh}
  <title>DOUx24 Payment</title>
  <style>
    body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#080412;color:#fff;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;padding:24px;box-sizing:border-box}
    .card{width:100%;max-width:400px;background:#140B29;border:1px solid rgba(155,92,255,.35);border-radius:18px;padding:28px 22px;text-align:center;box-shadow:0 14px 40px rgba(0,0,0,.5)}
    .badge{width:64px;height:64px;margin:0 auto 16px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:30px;font-weight:800;background:${badgeColor}22;color:${badgeColor};border:2px solid ${badgeColor}}
    h1{margin:0 0 10px;font-size:22px;font-weight:800}
    p{margin:0 0 20px;color:#C8C2D6;font-size:14px;line-height:1.55}
    .btn{display:inline-block;width:100%;box-sizing:border-box;padding:13px 18px;border:0;border-radius:12px;background:#7C3AED;color:#fff;font-size:15px;font-weight:700;cursor:pointer}
    .hint{margin-top:12px;font-size:12px;color:#8E87A3}
  </style>
</head>
<body>
  <div class="card">
    <div class="badge">${ok ? '✓' : bad ? '!' : '⏳'}</div>
    <h1>${title}</h1>
    <p>${subtitle}</p>
    <button class="btn" onclick="window.close();">Close &amp; Return to DOUx24</button>
    <div class="hint">If this screen stays open, tap the top-left Back arrow (←) or your phone Back button.</div>
  </div>
</body>
</html>`);
});

module.exports = { router };
