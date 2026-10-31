// End-to-end API test (v4): Access admin, event codes, live window, guest + photographer uploads,
// AI moderation (mock moderator), organiser panel, live status, scanner. Run: cd server && npm test
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'livephoto-'));

// --- fake Cloudflare Access (certs) + fake AI moderator ---
const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'k1', alg: 'RS256' };
let modCalls = 0, modVerdict = () => ({ verdict: 'ok', labels: [] });
const aux = http.createServer(async (req, res) => {
  if (req.url === '/certs') return res.end(JSON.stringify({ keys: [jwk] }));
  if (req.url === '/check') {
    let b = ''; for await (const c of req) b += c;
    modCalls++;
    return res.end(JSON.stringify(modVerdict(JSON.parse(b))));
  }
  res.statusCode = 404; res.end();
});
await new Promise((r) => aux.listen(0, '127.0.0.1', r));
const auxUrl = `http://127.0.0.1:${aux.address().port}`;

Object.assign(process.env, {
  DATA_DIR: path.join(tmp, 'data'), PHOTOS_DIR: path.join(tmp, 'photos'), COOKIE_SECURE: '0', PUBLIC_URL: 'http://localhost',
  SCAN_INTERVAL: '3600', ACCESS_AUD: 'aud-livephoto', ACCESS_TEAM_DOMAIN: 'johorstudio.cloudflareaccess.com',
  ACCESS_CERTS_URL: `${auxUrl}/certs`, ADMIN_EMAILS: 'rafierosliputra@gmail.com,johorstudio@gmail.com',
  MODERATOR_URL: auxUrl, GUEST_UPLOADS_PER_EVENT: '3',
});
const { handler } = await import('../src/server.js');
const db = await import('../src/db.js');
const storage = await import('../src/storage.js');

function jwt(email, { aud = 'aud-livephoto', exp = Math.floor(Date.now() / 1000) + 3600 } = {}) {
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const head = enc({ alg: 'RS256', kid: 'k1', typ: 'JWT' });
  const body = enc({ email, aud: [aud], iss: 'https://johorstudio.cloudflareaccess.com', exp });
  const sig = crypto.sign('RSA-SHA256', Buffer.from(`${head}.${body}`), privateKey).toString('base64url');
  return `${head}.${body}.${sig}`;
}
const ADMIN = { 'cf-access-jwt-assertion': jwt('rafierosliputra@gmail.com') };

let base, server;
before(async () => {
  db.openDb();
  storage.ensureStorage();
  server = http.createServer(handler);
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => { server.closeAllConnections(); server.close(); aux.close(); db.closeDb(); fs.rmSync(tmp, { recursive: true, force: true }); });

// Cookie jar per "device"
function device(ip) {
  const jar = {};
  return async function call(method, url, { body, raw, headers = {} } = {}) {
    const h = { 'x-forwarded-for': ip, ...headers };
    const ck = Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; ');
    if (ck) h.cookie = ck;
    if (body !== undefined) h['content-type'] = 'application/json';
    const r = await fetch(base + url, { method, headers: h, body: raw ?? (body !== undefined ? JSON.stringify(body) : undefined) });
    for (const c of r.headers.getSetCookie()) { const [kv] = c.split(';'); const [k, v] = kv.split('='); if (v) jar[k] = v; else delete jar[k]; }
    const type = r.headers.get('content-type') || '';
    return { status: r.status, body: type.includes('json') ? await r.json() : Buffer.from(await r.arrayBuffer()), headers: r.headers };
  };
}
const admin = device('10.0.0.1');
const A = (m, u, o = {}) => admin(m, u, { ...o, headers: { ...ADMIN, ...(o.headers || {}) } });

function fakeWebp(w, h, pad = 0) {
  const vp8x = Buffer.alloc(18); vp8x.write('VP8X', 0); vp8x.writeUInt32LE(10, 4); vp8x[8] = 0x08;
  vp8x.writeUIntLE(w - 1, 12, 3); vp8x.writeUIntLE(h - 1, 15, 3);
  const exif = Buffer.alloc(14); exif.write('EXIF', 0); exif.writeUInt32LE(6, 4); exif.write('GPS!!!', 8);
  const data = Buffer.alloc(8 + pad); data.write('VP8L', 0); data.writeUInt32LE(pad, 4);
  const body = Buffer.concat([Buffer.from('WEBP'), vp8x, exif, data]);
  const head = Buffer.alloc(8); head.write('RIFF', 0); head.writeUInt32LE(body.length, 4);
  return Buffer.concat([head, body]);
}
async function upload(call, startUrl, file, startBody = {}) {
  const s = await call('POST', startUrl, { body: { name: 'IMG_1.webp', size: file.length, ...startBody } });
  if (s.status !== 201) return s;
  await call('PUT', `/api/uploads/${s.body.id}?offset=0`, { raw: file });
  return call('POST', `/api/uploads/${s.body.id}/complete`);
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

let ev, codes;
const guest = device('10.0.0.2'), photog = device('10.0.0.3'), client = device('10.0.0.4'), stranger = device('10.0.0.5');

test('admin API needs a valid Cloudflare Access login', async () => {
  assert.equal((await admin('GET', '/api/admin/me')).status, 401);
  assert.equal((await admin('GET', '/api/admin/me', { headers: { 'cf-access-jwt-assertion': jwt('someone@gmail.com') } })).status, 403);
  assert.equal((await admin('GET', '/api/admin/me', { headers: { 'cf-access-jwt-assertion': jwt('rafierosliputra@gmail.com', { aud: 'other' }) } })).status, 401);
  assert.equal((await admin('GET', '/api/admin/me', { headers: { 'cf-access-jwt-assertion': jwt('rafierosliputra@gmail.com', { exp: 1 }) } })).status, 401);
  const forged = jwt('johorstudio@gmail.com').replace(/\.[^.]+$/, '.AAAA');
  assert.equal((await admin('GET', '/api/admin/me', { headers: { 'cf-access-jwt-assertion': forged } })).status, 401);
  const ok = await A('GET', '/api/admin/me');
  assert.equal(ok.status, 200); assert.equal(ok.body.email, 'rafierosliputra@gmail.com');
});

test('admin creates an event with three different codes; not live yet', async () => {
  const r = await A('POST', '/api/admin/events', { body: { date: '20261101', title: 'Annual Dinner ABC', organiser: 'ABC Sdn Bhd' } });
  assert.equal(r.status, 201, JSON.stringify(r.body));
  ev = r.body.event; codes = ev.codes;
  assert.equal(ev.status, 'draft');
  assert.equal(new Set(Object.values(codes)).size, 3);
  assert.ok(Object.values(codes).every((c) => /^\d{6}$/.test(c)));
  const g = await guest('POST', '/auth/code', { body: { code: codes.public } });
  assert.equal(g.status, 403); // not live
  assert.equal((await stranger('GET', '/api/live-status')).body.count, 0);
});

test('activate: new codes, live status, wrong-code limit with tries left', async () => {
  const r = await A('POST', `/api/admin/events/${ev.id}/activate`, { body: {} });
  assert.equal(r.body.event.status, 'live');
  assert.notEqual(r.body.event.codes.public, codes.public);
  codes = r.body.event.codes;
  assert.ok(r.body.event.endsAt - r.body.event.startsAt === 8 * 3600e3);
  const ls = await stranger('GET', '/api/live-status', { headers: { origin: 'https://johorstudio.com' } });
  assert.equal(ls.body.count, 1); assert.equal(ls.body.live[0].name, 'Annual Dinner ABC');
  assert.equal(ls.headers.get('access-control-allow-origin'), 'https://johorstudio.com');
  await A('PATCH', `/api/admin/events/${ev.id}`, { body: { hideName: true } });
  assert.equal((await stranger('GET', '/api/live-status')).body.live[0].name, 'Event in progress');
  const bad = String((Number(codes.public) + 1) % 1e6).padStart(6, '0');
  const w1 = await stranger('POST', '/auth/code', { body: { code: bad === codes.photographer || bad === codes.client ? '000001' : bad } });
  assert.equal(w1.status, 401); assert.equal(w1.body.triesLeft, 2); assert.match(w1.body.error, /2 tries left/);
  await stranger('POST', '/auth/code', { body: { code: '999999' } });
  const w3 = await stranger('POST', '/auth/code', { body: { code: '999998' } });
  assert.equal(w3.status, 429);
  assert.equal((await stranger('POST', '/auth/code', { body: { code: codes.public } })).status, 429); // locked even with the right code
});

test('guest: code -> gallery, must accept notice, upload goes to AI, limit 3', async () => {
  const pc = await guest('POST', '/auth/code', { body: { code: codes.photographer, want: 'guest' } });
  assert.equal(pc.status, 400); assert.match(pc.body.error, /photographer code/);
  const r = await guest('POST', '/auth/code', { body: { code: codes.public, want: 'guest' } });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.next, `/e/${ev.token}`);
  const g = await guest('GET', `/api/e/${ev.token}`);
  assert.equal(g.body.guest.canUpload, true); assert.equal(g.body.guest.agreed, false);
  assert.equal((await upload(guest, `/api/e/${ev.token}/uploads`, fakeWebp(3000, 4000))).status, 428);
  await guest('POST', `/api/e/${ev.token}/agree`);
  modVerdict = () => ({ verdict: 'ok', labels: [] });
  const up = await upload(guest, `/api/e/${ev.token}/uploads`, fakeWebp(3000, 4000), { uploader: 'Siti' });
  assert.equal(up.status, 200, JSON.stringify(up.body));
  assert.equal(up.body.photo.status, 'pending');
  await wait(200);
  const after1 = await guest('GET', `/api/e/${ev.token}`);
  assert.equal(after1.body.photos.length, 1); assert.equal(after1.body.photos[0].by, 'Siti');
  // flagged by the AI: not public, waits for review
  modVerdict = () => ({ verdict: 'flag', labels: ['document'] });
  await upload(guest, `/api/e/${ev.token}/uploads`, fakeWebp(1000, 1400));
  await wait(200);
  // blocked (NSFW)
  modVerdict = () => ({ verdict: 'block', labels: ['nsfw'] });
  await upload(guest, `/api/e/${ev.token}/uploads`, fakeWebp(1000, 1400));
  await wait(200);
  assert.equal((await guest('GET', `/api/e/${ev.token}`)).body.photos.length, 1);
  const lim = await upload(guest, `/api/e/${ev.token}/uploads`, fakeWebp(1000, 1000));
  assert.equal(lim.status, 429); assert.match(lim.body.error, /limit of 3/);
  assert.equal(modCalls, 3);
});

test('photographer: code + name -> Photographer N, upload is approved without AI', async () => {
  assert.equal((await photog('POST', '/auth/code', { body: { code: codes.photographer } })).status, 400); // name needed
  const r = await photog('POST', '/auth/code', { body: { code: codes.photographer, name: 'Aiman', app: true } });
  assert.equal(r.status, 200); assert.equal(r.body.slot, 1); assert.ok(r.body.token);
  const second = device('10.0.0.9');
  assert.equal((await second('POST', '/auth/code', { body: { code: codes.photographer, name: 'Rafi' } })).body.slot, 2);
  assert.equal((await second('POST', '/auth/code', { body: { code: codes.photographer, name: 'aiman' } })).body.slot, 1); // same name, same slot
  // the app uses the bearer token
  const bearer = device('10.0.0.10');
  const me = await bearer('GET', '/api/shoot', { headers: { authorization: `Bearer ${r.body.token}` } });
  assert.equal(me.body.slot, 1); assert.equal(me.body.folder, 'Photographer 1');
  const before = modCalls;
  const up = await upload(photog, '/api/shoot/uploads', fakeWebp(6000, 4000, 5000), { name: 'DSC01234.webp' });
  assert.equal(up.status, 200); assert.equal(up.body.photo.status, 'approved'); assert.equal(up.body.photo.by, 'Aiman');
  assert.equal(modCalls, before);
  assert.ok(fs.existsSync(path.join(process.env.PHOTOS_DIR, 'EVENTS', ev.folder, 'Photographer 1', 'DSC01234.webp')));
  // admin moves Aiman to slot 3; his session follows
  await A('POST', `/api/admin/events/${ev.id}/slots`, { body: { from: 1, to: 3 } });
  assert.equal((await photog('GET', '/api/shoot')).body.slot, 3);
});

test('organiser panel: review queue, allow / remove, request to close', async () => {
  assert.equal((await client('GET', `/api/client/events/${ev.id}`)).status, 401);
  const r = await client('POST', '/auth/code', { body: { code: codes.client } });
  assert.equal(r.status, 200); assert.equal(r.body.next, '/panel');
  const review = await client('GET', `/api/client/events/${ev.id}/photos?filter=review`);
  assert.equal(review.body.photos.length, 1); assert.deepEqual(review.body.photos[0].flags, ['document']);
  const blocked = await client('GET', `/api/client/events/${ev.id}/photos?filter=blocked`);
  assert.equal(blocked.body.photos.length, 1);
  await client('PATCH', `/api/client/events/${ev.id}/photos/${review.body.photos[0].id}`, { body: { status: 'approved' } });
  assert.equal((await guest('GET', `/api/e/${ev.token}`)).body.photos.length, 3);
  assert.equal((await client('DELETE', `/api/client/events/${ev.id}/photos/${blocked.body.photos[0].id}`)).status, 200);
  // organiser cannot use admin routes or change settings
  assert.equal((await client('POST', `/api/admin/events/${ev.id}/close`)).status, 401);
  const rq = await client('POST', `/api/client/events/${ev.id}/request`, { body: { type: 'close' } });
  assert.equal(rq.body.event.request.type, 'close');
  assert.equal((await A('GET', `/api/admin/events/${ev.id}`)).body.event.request.type, 'close');
});

test('settings, extend, close: guests and photographers logged out, guest code changes, gallery stays', async () => {
  const s = await A('PATCH', `/api/admin/events/${ev.id}`, { body: { settings: { newSec: 4, loopSec: 6, loopSource: 'public', idleMin: 2 } } });
  assert.deepEqual(s.body.event.settings, { newSec: 4, loopSec: 6, loopSource: 'public', idleMin: 2 });
  const manual = await A('PATCH', `/api/admin/events/${ev.id}`, { body: { codes: { public: codes.client } } });
  assert.equal(manual.status, 409);
  const before = (await A('GET', `/api/admin/events/${ev.id}`)).body.event;
  const ex = await A('POST', `/api/admin/events/${ev.id}/extend`, { body: { hours: 2 } });
  assert.equal(ex.body.event.endsAt, before.endsAt + 2 * 3600e3);
  const c = await A('POST', `/api/admin/events/${ev.id}/close`);
  assert.equal(c.body.event.status, 'closed'); assert.equal(c.body.event.request, null);
  assert.notEqual(c.body.event.codes.public, codes.public);
  assert.equal((await photog('GET', '/api/shoot')).status, 401);
  const g = await guest('GET', `/api/e/${ev.token}`);
  assert.equal(g.status, 200); assert.equal(g.body.guest.canUpload, false); assert.equal(g.body.photos.length, 3);
  assert.equal((await client('GET', `/api/client/events/${ev.id}`)).status, 200); // organiser keeps access 7 days
  assert.equal((await stranger('GET', '/api/live-status')).body.count, 0);
});

test('scanner adopts SMB folders with their codes', async () => {
  const meta = JSON.parse(fs.readFileSync(path.join(process.env.PHOTOS_DIR, 'EVENTS', ev.folder, '.livephoto.json'), 'utf8'));
  assert.equal(meta.photographers.length, 2);
  fs.mkdirSync(path.join(process.env.PHOTOS_DIR, 'EVENTS', '20261225 Hari Keluarga'));
  storage.scanAll();
  const list = (await A('GET', '/api/admin/events')).body.events;
  assert.equal(list.length, 2);
  assert.ok(list.every((e) => e.codes.public && e.codes.client));
});

test('pages are served', async () => {
  for (const p of ['/', '/c/123456', '/admin', '/panel', '/shoot', `/show/${ev.id}`, `/e/${ev.token}`]) {
    assert.equal((await stranger('GET', p)).status, 200, p);
  }
});
