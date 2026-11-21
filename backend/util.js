'use strict';

class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

/** Detects the real image type from the first bytes (the file name / client type is never trusted). */
function sniff(buf) {
  if (!Buffer.isBuffer(buf) || buf.length < 12) return null;
  if (buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return { mime: 'image/jpeg', ext: 'jpg' };
  if (buf.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return { mime: 'image/png', ext: 'png' };
  if (buf.subarray(0, 4).toString('latin1') === 'RIFF' && buf.subarray(8, 12).toString('latin1') === 'WEBP') return { mime: 'image/webp', ext: 'webp' };
  if (buf.subarray(0, 3).toString('latin1') === 'GIF') return { mime: 'image/gif', ext: 'gif' };
  return null;
}

const cleanType = (t) => (/^[a-z0-9_-]{1,32}$/i.test(t || '') ? String(t).toLowerCase() : 'image');
const cleanRef = (r) => (/^[A-Za-z0-9_-]{1,64}$/.test(r || '') ? r : '');
const isImageId = (id) => /^[a-f0-9]{24}$/.test(id || '');

/** Turns a Telegram Bot API error into a clear error for the app. */
function mapTgError(d) {
  const desc = String((d && d.description) || '').toLowerCase();
  const code = d && d.error_code;
  if (code === 429) return new ApiError(503, 'telegram_busy', 'Telegram is busy. Try again in a moment');
  if (code === 403 || desc.includes('chat not found') || desc.includes('not enough rights') || desc.includes('not a member') ||
      desc.includes('need administrator rights') || desc.includes('have no rights')) {
    return new ApiError(502, 'telegram_channel', 'The bot is not an admin of the Telegram channel (or CHANNEL_ID is wrong)');
  }
  if (code === 401) return new ApiError(502, 'telegram_token', 'Telegram rejected the BOT_TOKEN');
  return new ApiError(502, 'telegram_error', 'Telegram error: ' + ((d && d.description) || 'unknown'));
}

/** Strips leading/trailing whitespace and matching single/double quotes from an env value. */
function cleanEnv(raw) {
  let v = String(raw || '').trim();
  if (v.length >= 2 && ((v[0] === '"' && v[v.length - 1] === '"') || (v[0] === "'" && v[v.length - 1] === "'"))) {
    v = v.slice(1, -1).trim();
  }
  return v;
}

/** FIREBASE_CONFIG may be the raw service-account JSON or its base64. */
function parseServiceAccount(raw) {
  let txt = cleanEnv(raw);
  if (!txt) throw new Error('empty value');
  if (!txt.startsWith('{')) txt = Buffer.from(txt, 'base64').toString('utf8').trim();
  const sa = JSON.parse(txt);
  if (!sa || typeof sa !== 'object' || Array.isArray(sa)) throw new Error('expected JSON object');
  if (typeof sa.private_key === 'string') sa.private_key = sa.private_key.replace(/\\n/g, '\n');
  return sa;
}

/** Validates required Railway/env variables without exiting the process. */
function inspectBackendEnv(env = process.env) {
  const botToken = cleanEnv(env.BOT_TOKEN);
  const channelId = cleanEnv(env.CHANNEL_ID);
  const firebaseRaw = cleanEnv(env.FIREBASE_CONFIG);
  const databaseUrlOverride = cleanEnv(env.FIREBASE_DATABASE_URL);
  const missing = [];
  const issues = [];

  if (!botToken) missing.push('BOT_TOKEN');
  if (!channelId) missing.push('CHANNEL_ID');
  if (!firebaseRaw) missing.push('FIREBASE_CONFIG');

  let serviceAccount = null;
  if (firebaseRaw) {
    try {
      const sa = parseServiceAccount(firebaseRaw);
      if (sa.project_info && !sa.private_key) {
        issues.push('FIREBASE_CONFIG looks like Android google-services.json; paste Firebase Admin SDK service account JSON instead');
      } else if (!sa.project_id || typeof sa.project_id !== 'string' || !sa.private_key || typeof sa.private_key !== 'string' || !sa.client_email || typeof sa.client_email !== 'string') {
        issues.push('FIREBASE_CONFIG is missing required service account fields (project_id, client_email, private_key)');
      } else {
        serviceAccount = sa;
      }
    } catch (e) {
      issues.push(`FIREBASE_CONFIG is not valid JSON/base64 (${e.message})`);
    }
  }

  let databaseUrl = serviceAccount ? `https://${serviceAccount.project_id}-default-rtdb.firebaseio.com` : '';
  if (databaseUrlOverride) {
    try {
      const parsed = new URL(databaseUrlOverride);
      const host = parsed.hostname.toLowerCase();
      if (parsed.protocol === 'https:' && (host.endsWith('.firebaseio.com') || host.endsWith('.firebasedatabase.app'))) {
        databaseUrl = parsed.origin;
      } else {
        issues.push('FIREBASE_DATABASE_URL must be your Firebase RTDB root URL (e.g. https://doux24-c9bb3-default-rtdb.firebaseio.com), or remove it to auto-detect');
      }
    } catch {
      issues.push('FIREBASE_DATABASE_URL is not a valid URL. Use https://doux24-c9bb3-default-rtdb.firebaseio.com or remove it');
    }
  }
  const telegramReady = Boolean(botToken && channelId);
  const firebaseConfigValid = Boolean(serviceAccount) && issues.length === 0;

  return {
    botToken,
    channelId,
    databaseUrl,
    serviceAccount,
    missing,
    issues,
    telegramReady,
    firebaseConfigValid,
  };
}

/** Given cached files, returns the names to delete (oldest first) so the folder drops to 80% of the limit. */
function pickPrune(files, maxBytes) {
  let total = files.reduce((s, f) => s + f.size, 0);
  if (total <= maxBytes) return [];
  const out = [];
  for (const f of [...files].sort((a, b) => a.mtimeMs - b.mtimeMs)) {
    if (total <= maxBytes * 0.8) break;
    out.push(f.name);
    total -= f.size;
  }
  return out;
}

module.exports = { ApiError, sniff, cleanType, cleanRef, isImageId, mapTgError, cleanEnv, parseServiceAccount, inspectBackendEnv, pickPrune };
