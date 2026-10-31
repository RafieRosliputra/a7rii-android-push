// End-to-end API test: login by code, events, slots, chunked upload, guest upload, SSE, scanner.
// Run: cd server && npm test
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'livephoto-'));
Object.assign(process.env, {
  DATA_DIR: path.join(tmp, 'data'), PHOTOS_DIR: path.join(tmp, 'photos'), DEV_LOG_CODES: '1',
  COOKIE_SECURE: '0', ADMIN_EMAIL: 'admin@example.com', PUBLIC_URL: 'http://localhost', SCAN_INTERVAL: '3600',
});
const { handler } = await import('../src/server.js');
const db = await import('../src/db.js');
const storage = await import('../src/storage.js');

let base, server;
const codes = {};
const origLog = console.log;
console.log = (...a) => { const m = /login code for (\S+): (\d{6})/.exec(a.join(' ')); if (m) codes[m[1]] = m[2]; else origLog(...a); };

before(async () => {
  db.openDb();
  storage.ensureStorage();
  server = http.createServer(handler);
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => { server.closeAllConnections(); server.close(); db.closeDb(); console.log = origLog; fs.rmSync(tmp, { recursive: true, force: true }); });

async function call(method, url, { token, body, raw, headers = {} } = {}) {
  const h = { ...headers };
  if (token) h.authorization = `Bearer ${token}`;
  if (body !== undefined) h['content-type'] = 'application/json';
  const r = await fetch(base + url, { method, headers: h, body: raw ?? (body !== undefined ? JSON.stringify(body) : undefined) });
  const type = r.headers.get('content-type') || '';
  return { status: r.status, body: type.includes('json') ? await r.json() : Buffer.from(await r.arrayBuffer()), headers: r.headers };
}
async function login(email) {
  const req = await call('POST', '/auth/code/request', { body: { email } });
  assert.equal(req.status, 200);
  const v = await call('POST', '/auth/code/verify', { body: { email, code: codes[email], app: true } });
  assert.equal(v.status, 200, JSON.stringify(v.body));
  return v.body.appToken;
}
// Minimal WebP: RIFF + VP8X (size) + EXIF chunk (to check it is stripped for guests).
function fakeWebp(w, h, pad = 0) {
  const vp8x = Buffer.alloc(18); vp8x.write('VP8X', 0); vp8x.writeUInt32LE(10, 4); vp8x[8] = 0x08;
  vp8x.writeUIntLE(w - 1, 12, 3); vp8x.writeUIntLE(h - 1, 15, 3);
  const exif = Buffer.alloc(8 + 6); exif.write('EXIF', 0); exif.writeUInt32LE(6, 4); exif.write('GPS!!!', 8);
  const data = Buffer.alloc(8 + pad); data.write('VP8L', 0); data.writeUInt32LE(pad, 4);
  const body = Buffer.concat([Buffer.from('WEBP'), vp8x, exif, data]);
  const head = Buffer.alloc(8); head.write('RIFF', 0); head.writeUInt32LE(body.length, 4);
  return Buffer.concat([head, body]);
}

let adminTok, userTok, eventId, token;

test('unknown email gets the same answer but no code', async () => {
  const r = await call('POST', '/auth/code/request', { body: { email: 'stranger@example.com' } });
  assert.equal(r.status, 200);
  assert.equal(codes['stranger@example.com'], undefined);
});

test('admin logs in with email code; wrong code rejected', async () => {
  await call('POST', '/auth/code/request', { body: { email: 'admin@example.com' } });
  const bad = await call('POST', '/auth/code/verify', { body: { email: 'admin@example.com', code: '000000' } });
  assert.equal(bad.status, 401);
  const ok = await call('POST', '/auth/code/verify', { body: { email: 'admin@example.com', code: codes['admin@example.com'], app: true } });
  assert.equal(ok.status, 200);
  assert.match(ok.headers.get('set-cookie'), /lp_s=.*HttpOnly/);
  adminTok = ok.body.appToken;
  const me = await call('GET', '/api/me', { token: adminTok });
  assert.equal(me.body.user.role, 'admin');
});

test('admin adds a user, user creates an event', async () => {
  const add = await call('POST', '/api/admin/users', { token: adminTok, body: { email: 'Ali@Example.com', name: 'Ali' } });
  assert.equal(add.status, 201);
  userTok = await login('ali@example.com');
  const bad = await call('POST', '/api/events', { token: userTok, body: { date: '2026131', title: 'x' } });
  assert.equal(bad.status, 400);
  const ev = await call('POST', '/api/events', { token: userTok, body: { date: '20261031', title: 'Konvo Tadika / Bandar Putra' } });
  assert.equal(ev.status, 201, JSON.stringify(ev.body));
  eventId = ev.body.event.id; token = ev.body.event.token;
  assert.equal(ev.body.event.folder, '20261031 Konvo Tadika Bandar Putra');
  assert.equal(ev.body.event.mySlot, 1);
  assert.equal(ev.body.event.publicUpload, true);
  const dir = path.join(process.env.PHOTOS_DIR, 'EVENTS', '20261031 Konvo Tadika Bandar Putra');
  assert.ok(fs.existsSync(path.join(dir, '.livephoto.json')));
  assert.ok(fs.existsSync(path.join(dir, 'public upload')));
});

test('other users cannot see the event; admin sees all', async () => {
  await call('POST', '/api/admin/users', { token: adminTok, body: { email: 'abu@example.com' } });
  const abu = await login('abu@example.com');
  assert.equal((await call('GET', '/api/events', { token: abu })).body.events.length, 0);
  assert.equal((await call('GET', `/api/events/${eventId}`, { token: abu })).status, 404);
  assert.equal((await call('GET', '/api/events', { token: adminTok })).body.events.length, 1);
  // admin makes Abu Photographer 2
  const m = await call('POST', `/api/events/${eventId}/members`, { token: adminTok, body: { email: 'abu@example.com', slot: 2 } });
  assert.equal(m.status, 200);
  assert.equal((await call('GET', '/api/events', { token: abu })).body.events[0].mySlot, 2);
});

test('photographer chunked upload with resume and thumbnail; SSE gets it', async () => {
  const file = fakeWebp(6000, 4000, 9000);
  const sse = await new Promise((resolve) => {
    http.get(`${base}/api/e/${token}/stream`, (res) => {
      let buf = '';
      res.on('data', (c) => { buf += c; if (buf.includes('event: photo')) { res.destroy(); resolve(buf); } });
    });
    setTimeout(async () => {
      const s = await call('POST', `/api/events/${eventId}/uploads`, { token: userTok, body: { name: 'DSC01234.webp', size: file.length } });
      assert.equal(s.status, 201, JSON.stringify(s.body));
      const id = s.body.id;
      const half = 5000;
      assert.equal((await call('PUT', `/api/uploads/${id}?offset=0`, { raw: file.subarray(0, half) })).body.received, half);
      const wrong = await call('PUT', `/api/uploads/${id}?offset=0`, { raw: file.subarray(0, half) });
      assert.equal(wrong.status, 409); assert.equal(wrong.body.received, half);
      assert.equal((await call('GET', `/api/uploads/${id}`)).body.received, half);
      await call('PUT', `/api/uploads/${id}?offset=${half}`, { raw: file.subarray(half) });
      assert.equal((await call('PUT', `/api/uploads/${id}/thumb/480`, { raw: fakeWebp(480, 320) })).status, 200);
      const done = await call('POST', `/api/uploads/${id}/complete`);
      assert.equal(done.status, 200, JSON.stringify(done.body));
      assert.equal(done.body.photo.w, 6000);
      assert.equal(done.body.photo.th, 1);
    }, 100);
  });
  assert.match(sse, /"w":6000/);
  const f = path.join(process.env.PHOTOS_DIR, 'EVENTS', '20261031 Konvo Tadika Bandar Putra', 'Photographer 1', 'DSC01234.webp');
  assert.equal(fs.statSync(f).size, file.length);
});

test('guest upload: webp only, EXIF stripped, appears in public gallery', async () => {
  const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 0, 0, 0]);
  const s1 = await call('POST', `/api/e/${token}/uploads`, { body: { name: 'a.webp', size: jpeg.length } });
  assert.equal((await call('PUT', `/api/uploads/${s1.body.id}?offset=0`, { raw: jpeg })).status, 415);
  const file = fakeWebp(3000, 4000, 100);
  const s = await call('POST', `/api/e/${token}/uploads`, { body: { name: 'IMG_1.webp', size: file.length } });
  await call('PUT', `/api/uploads/${s.body.id}?offset=0`, { raw: file });
  const done = await call('POST', `/api/uploads/${s.body.id}/complete`);
  assert.equal(done.status, 200, JSON.stringify(done.body));
  assert.equal(done.body.photo.src, 'public');
  const g = await call('GET', `/api/e/${token}`);
  assert.equal(g.body.photos.length, 2);
  assert.equal(g.body.photos[0].src, 'public'); // newest first
  const img = await call('GET', `/p/${token}/${done.body.photo.id}/orig`);
  assert.equal(img.status, 200);
  assert.ok(!img.body.includes(Buffer.from('GPS!!!')));
  // thumbnail exists for the photographer photo, original used as fallback for the guest one
  assert.equal((await call('GET', `/p/${token}/${g.body.photos[1].id}/480`)).body.length < 200, true);
});

test('hide photo, closed guest upload, new link', async () => {
  const g = await call('GET', `/api/e/${token}`);
  const pid = g.body.photos[0].id;
  assert.equal((await call('PATCH', `/api/photos/${pid}`, { token: userTok, body: { hidden: true } })).status, 200);
  assert.equal((await call('GET', `/api/e/${token}`)).body.photos.length, 1);
  assert.equal((await call('GET', `/p/${token}/${pid}/orig`)).status, 404);
  await call('PATCH', `/api/events/${eventId}`, { token: userTok, body: { publicUpload: false } });
  assert.equal((await call('POST', `/api/e/${token}/uploads`, { body: { size: 10 } })).status, 403);
  const nl = await call('PATCH', `/api/events/${eventId}`, { token: userTok, body: { newLink: true } });
  assert.notEqual(nl.body.event.token, token);
  assert.equal((await call('GET', `/api/e/${token}`)).status, 404);
  token = nl.body.event.token;
  // only admin may pick the landing-page sample
  assert.equal((await call('PATCH', `/api/events/${eventId}`, { token: userTok, body: { showcase: true } })).status, 403);
  assert.equal((await call('PATCH', `/api/events/${eventId}`, { token: adminTok, body: { showcase: true } })).status, 200);
  assert.equal((await call('GET', '/api/showcase')).body.photos.length, 1);
});

test('scanner: files and folders changed over SMB', async () => {
  const evDir = path.join(process.env.PHOTOS_DIR, 'EVENTS', '20261031 Konvo Tadika Bandar Putra');
  const f = path.join(evDir, 'Photographer 3', 'smb.webp');
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, fakeWebp(800, 1200));
  const old = new Date(Date.now() - 60e3); fs.utimesSync(f, old, old);
  storage.scanAll();
  const list = (await call('GET', `/api/events/${eventId}/photos`, { token: adminTok })).body.photos;
  const smb = list.find((p) => p.name === 'smb.webp');
  assert.equal(smb.slot, 3); assert.equal(smb.h, 1200);
  assert.equal((await call('GET', `/api/events/${eventId}/missing-thumbs`, { token: adminTok })).body.photos.length >= 1, true);
  // rename the folder over SMB: same event id, same owner, link still works
  const renamed = path.join(process.env.PHOTOS_DIR, 'EVENTS', '20261101 Konvo Renamed');
  fs.renameSync(evDir, renamed);
  storage.scanAll();
  const ev = (await call('GET', `/api/events/${eventId}`, { token: userTok })).body.event;
  assert.equal(ev.folder, '20261101 Konvo Renamed');
  assert.equal((await call('GET', `/api/e/${token}`)).status, 200);
  // a brand-new folder made over SMB is adopted, owned by the admin
  fs.mkdirSync(path.join(process.env.PHOTOS_DIR, 'EVENTS', '20261225 Annual Dinner'));
  storage.scanAll();
  assert.equal((await call('GET', '/api/events', { token: adminTok })).body.events.length, 2);
});

test('removed user loses access; re-adding restores their events', async () => {
  const users = (await call('GET', '/api/admin/users', { token: adminTok })).body.users;
  const ali = users.find((u) => u.email === 'ali@example.com');
  await call('PATCH', `/api/admin/users/${ali.id}`, { token: adminTok, body: { status: 'removed' } });
  assert.equal((await call('GET', '/api/events', { token: userTok })).status, 401);
  await call('POST', '/api/admin/users', { token: adminTok, body: { email: 'ali@example.com' } });
  const t = await login('ali@example.com');
  assert.equal((await call('GET', '/api/events', { token: t })).body.events.length, 1);
  userTok = t;
});

test('delete event needs the exact name and removes the folder', async () => {
  const ev = (await call('GET', `/api/events/${eventId}`, { token: userTok })).body.event;
  assert.equal((await call('DELETE', `/api/events/${eventId}`, { token: userTok, body: { confirm: 'nope' } })).status, 400);
  assert.equal((await call('DELETE', `/api/events/${eventId}`, { token: userTok, body: { confirm: ev.folder } })).status, 200);
  assert.ok(!fs.existsSync(path.join(process.env.PHOTOS_DIR, 'EVENTS', ev.folder)));
  assert.equal((await call('GET', `/api/e/${token}`)).status, 404);
});

test('pages are served', async () => {
  for (const p of ['/', '/login', '/admin', '/e/abcdefgh23']) assert.equal((await call('GET', p)).status, 200, p);
});
