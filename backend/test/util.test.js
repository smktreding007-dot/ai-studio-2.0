'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { sniff, cleanType, cleanRef, isImageId, mapTgError, cleanEnv, parseServiceAccount, inspectBackendEnv, pickPrune, ApiError } = require('../util');

const pad = (b) => Buffer.concat([Buffer.from(b), Buffer.alloc(32)]);

test('sniff detects real image types and rejects others', () => {
  assert.equal(sniff(pad([0xff, 0xd8, 0xff, 0xe0])).mime, 'image/jpeg');
  assert.equal(sniff(pad([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])).mime, 'image/png');
  assert.equal(sniff(Buffer.concat([Buffer.from('RIFF'), Buffer.alloc(4), Buffer.from('WEBP'), Buffer.alloc(8)])).mime, 'image/webp');
  assert.equal(sniff(Buffer.concat([Buffer.from('GIF89a'), Buffer.alloc(20)])).mime, 'image/gif');
  assert.equal(sniff(Buffer.from('<html><script>alert(1)</script></html>')), null);
  assert.equal(sniff(Buffer.from('MZ-exe-file-bytes-here')), null);
  assert.equal(sniff(Buffer.alloc(3)), null);
  assert.equal(sniff(null), null);
});

test('input cleaners', () => {
  assert.equal(cleanType('Matches'), 'matches');
  assert.equal(cleanType('../etc'), 'image');
  assert.equal(cleanRef('-Nabc_123'), '-Nabc_123');
  assert.equal(cleanRef('a/b'), '');
  assert.ok(isImageId('0123456789abcdef01234567'));
  assert.ok(!isImageId('../../etc/passwd'));
  assert.ok(!isImageId('0123456789ABCDEF01234567'));
});

test('telegram errors map to app-friendly codes', () => {
  assert.equal(mapTgError({ error_code: 400, description: 'Bad Request: chat not found' }).code, 'telegram_channel');
  assert.equal(mapTgError({ error_code: 403, description: 'Forbidden: bot is not a member of the channel chat' }).code, 'telegram_channel');
  assert.equal(mapTgError({ error_code: 400, description: 'Bad Request: not enough rights to send documents to the chat' }).code, 'telegram_channel');
  assert.equal(mapTgError({ error_code: 429, description: 'Too Many Requests' }).code, 'telegram_busy');
  assert.equal(mapTgError({ error_code: 401, description: 'Unauthorized' }).code, 'telegram_token');
  assert.ok(mapTgError({ error_code: 500, description: 'boom' }) instanceof ApiError);
});

test('service account parsing (raw json and base64, escaped newlines)', () => {
  const sa = { type: 'service_account', project_id: 'p', private_key: '-----BEGIN PRIVATE KEY-----\\nabc\\n-----END PRIVATE KEY-----\\n' };
  const raw = JSON.stringify(sa);
  assert.equal(parseServiceAccount(raw).private_key.includes('\n'), true);
  assert.equal(parseServiceAccount(`'${raw}'`).project_id, 'p');
  assert.equal(parseServiceAccount(Buffer.from(raw).toString('base64')).project_id, 'p');
  assert.throws(() => parseServiceAccount('not json'));
});

test('inspectBackendEnv detects missing vars and client google-services.json mistakes', () => {
  const empty = inspectBackendEnv({});
  assert.deepEqual(empty.missing, ['BOT_TOKEN', 'CHANNEL_ID', 'FIREBASE_CONFIG']);
  assert.equal(empty.telegramReady, false);
  assert.equal(empty.firebaseConfigValid, false);

  const wrongJson = inspectBackendEnv({
    BOT_TOKEN: '"123:ABC"',
    CHANNEL_ID: '-100123456',
    FIREBASE_CONFIG: JSON.stringify({ project_info: { project_id: 'doux24-c9bb3' }, client: [] }),
  });
  assert.equal(wrongJson.botToken, '123:ABC');
  assert.equal(wrongJson.telegramReady, true);
  assert.equal(wrongJson.firebaseConfigValid, false);
  assert.ok(wrongJson.issues[0].includes('google-services.json'));

  const ok = inspectBackendEnv({
    BOT_TOKEN: '123:ABC',
    CHANNEL_ID: '-100123456',
    FIREBASE_CONFIG: JSON.stringify({
      type: 'service_account',
      project_id: 'doux24-c9bb3',
      client_email: 'firebase-adminsdk@doux24-c9bb3.iam.gserviceaccount.com',
      private_key: '-----BEGIN PRIVATE KEY-----\\nkey\\n-----END PRIVATE KEY-----\\n',
    }),
  });
  assert.deepEqual(ok.missing, []);
  assert.deepEqual(ok.issues, []);
  assert.equal(ok.telegramReady, true);
  assert.equal(ok.firebaseConfigValid, true);
  assert.equal(ok.databaseUrl, 'https://doux24-c9bb3-default-rtdb.firebaseio.com');
  assert.equal(cleanEnv(" 'hello' "), 'hello');

  const withPath = inspectBackendEnv({
    BOT_TOKEN: '123:ABC',
    CHANNEL_ID: '-100123456',
    FIREBASE_CONFIG: JSON.stringify({
      type: 'service_account',
      project_id: 'doux24-c9bb3',
      client_email: 'firebase-adminsdk@doux24-c9bb3.iam.gserviceaccount.com',
      private_key: '-----BEGIN PRIVATE KEY-----\\nkey\\n-----END PRIVATE KEY-----\\n',
    }),
    FIREBASE_DATABASE_URL: 'https://doux24-c9bb3-default-rtdb.firebaseio.com/test.json',
  });
  assert.equal(withPath.databaseUrl, 'https://doux24-c9bb3-default-rtdb.firebaseio.com');
  assert.equal(withPath.firebaseConfigValid, true);

  const badDbUrl = inspectBackendEnv({
    BOT_TOKEN: '123:ABC',
    CHANNEL_ID: '-100123456',
    FIREBASE_CONFIG: JSON.stringify({
      type: 'service_account',
      project_id: 'doux24-c9bb3',
      client_email: 'firebase-adminsdk@doux24-c9bb3.iam.gserviceaccount.com',
      private_key: '-----BEGIN PRIVATE KEY-----\\nkey\\n-----END PRIVATE KEY-----\\n',
    }),
    FIREBASE_DATABASE_URL: 'https://console.firebase.google.com/project/doux24-c9bb3/database',
  });
  assert.equal(badDbUrl.firebaseConfigValid, false);
  assert.ok(badDbUrl.issues[0].includes('FIREBASE_DATABASE_URL'));
});

test('cache pruning removes oldest files first', () => {
  const files = [{ name: 'a', size: 40, mtimeMs: 1 }, { name: 'b', size: 40, mtimeMs: 2 }, { name: 'c', size: 40, mtimeMs: 3 }];
  assert.deepEqual(pickPrune(files, 200), []);
  assert.deepEqual(pickPrune(files, 100), ['a']);
  assert.deepEqual(pickPrune(files, 60), ['a', 'b']);
});
