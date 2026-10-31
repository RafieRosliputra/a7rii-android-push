// HTTP API routes. See docs/v3-live-photo-pelan.md §5 and server/README.md.
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';
import * as db from './db.js';
import * as auth from './auth.js';
import * as storage from './storage.js';
import { sniff, imageSizeOfFile, stripWebpMetadata } from './imageinfo.js';
import { subscribe, publish, setPhotoSerializer, viewerCount } from './live.js';
import { Router, HttpError, fail, sendJson, readJson, readBody, clientIp, randomToken, shortToken, uuid } from './http.js';

export const router = new Router();
const r = (m, p, h) => router.add(m, p, h);

const guestUploadLimit = rateLimiterPerHour();
function rateLimiterPerHour() {
  const hits = new Map();
  return (ip) => {
    const t = Date.now(), arr = (hits.get(ip) || []).filter((x) => t - x < 3600e3);
    arr.push(t); hits.set(ip, arr);
    return arr.length <= config.guestUploadsPerHour;
  };
}

// ---------- serializers ----------
export function photoJson(p) {
  return { id: p.id, seq: p.seq, w: p.width, h: p.height, src: p.source, slot: p.slot, t: p.created_at, th: p.thumbs, hidden: !!p.hidden, name: path.posix.basename(p.rel_path) };
}
setPhotoSerializer(photoJson);

const galleryUrl = (ev) => `${config.publicUrl}/e/${ev.public_token}`;
function eventJson(ev, user) {
  const out = {
    id: ev.id, folder: ev.folder_name, date: ev.date, title: ev.title, token: ev.public_token, url: galleryUrl(ev),
    publicUpload: !!ev.public_upload_on, showcase: !!ev.showcase, createdAt: ev.created_at,
    owner: ev.owner_email ? { email: ev.owner_email, name: ev.owner_name } : null,
    photoCount: ev.photo_count ?? undefined, guestCount: ev.guest_count ?? undefined,
  };
  if (user) {
    out.mySlot = db.memberSlot(ev.id, user.id);
    out.canManage = canManage(user, ev);
  }
  return out;
}
function userJson(u) {
  return { id: u.id, email: u.email, name: u.name, role: u.role, status: u.status, lastLoginAt: u.last_login_at, createdAt: u.created_at, eventCount: u.event_count };
}

// ---------- permissions ----------
const canManage = (u, ev) => u.role === 'admin' || ev.owner_id === u.id;
const canView = (u, ev) => canManage(u, ev) || db.memberSlot(ev.id, u.id) != null;
function eventFor(req, id, need = 'view') {
  const u = auth.requireUser(req);
  const ev = db.getEvent(id);
  if (!ev || !canView(u, ev)) fail(404, 'Event not found');
  if (need === 'manage' && !canManage(u, ev)) fail(403, 'Only the event owner or an admin can do this');
  return { u, ev };
}
function eventByToken(token) {
  const ev = db.getEventByToken(token);
  if (!ev) fail(404, 'This gallery link is not valid');
  return ev;
}
function requireStorage() { if (!storage.storageOnline()) fail(503, 'Storage offline, try again shortly', { retry: true }); }
const intOrNull = (v) => (v == null || v === '' ? null : Number.isFinite(Number(v)) ? Number(v) : null);

// ---------- auth ----------
r('GET', '/auth/config', (req, res) => sendJson(res, 200, {
  googleClientId: config.googleClientId, whatsapp: config.whatsapp, contactUrl: config.contactUrl,
}));

function finishLogin(res, user, body) {
  const headers = { 'set-cookie': auth.sessionCookie(auth.startSession(user, 'web', 'browser')) };
  const out = { user: userJson(user) };
  if (body.app) out.appToken = auth.startSession(user, 'app', String(body.device || 'android').slice(0, 60));
  sendJson(res, 200, out, headers);
}
r('POST', '/auth/google', async (req, res) => {
  const body = await readJson(req);
  finishLogin(res, await auth.verifyGoogle(body.credential), body);
});
r('POST', '/auth/code/request', async (req, res) => {
  const body = await readJson(req);
  sendJson(res, 200, await auth.requestCode(body.email, clientIp(req)));
});
r('POST', '/auth/code/verify', async (req, res) => {
  const body = await readJson(req);
  finishLogin(res, auth.verifyCode(body.email, body.code, clientIp(req)), body);
});
// Already logged in on the web (e.g. the app opened /login?app=1 in a browser that has a cookie).
r('POST', '/auth/app-token', async (req, res) => {
  const u = auth.requireUser(req);
  const body = await readJson(req);
  sendJson(res, 200, { appToken: auth.startSession(u, 'app', String(body.device || 'android').slice(0, 60)) });
});
r('POST', '/auth/logout', (req, res) => { auth.logout(req); sendJson(res, 200, { ok: true }, { 'set-cookie': auth.clearCookie() }); });
r('GET', '/api/me', (req, res) => {
  const u = auth.requireUser(req);
  sendJson(res, 200, { user: userJson(u), storageOnline: storage.storageOnline(), publicUrl: config.publicUrl });
});

// ---------- events ----------
r('GET', '/api/events', (req, res) => {
  const u = auth.requireUser(req);
  sendJson(res, 200, { events: db.listEventsFor(u).map((e) => eventJson(e, u)) });
});
r('POST', '/api/events', async (req, res) => {
  const u = auth.requireUser(req);
  const body = await readJson(req);
  const ev = storage.createEvent({ date: String(body.date || ''), title: body.title, owner: u });
  sendJson(res, 201, { event: eventJson(withCounts(ev, u), u) });
});
const withCounts = (ev, u) => db.listEventsFor(u).find((e) => e.id === ev.id) || ev;

r('GET', '/api/events/:id', (req, res, { params }) => {
  const { u, ev } = eventFor(req, params.id);
  sendJson(res, 200, {
    event: eventJson(withCounts(ev, u), u),
    members: db.listMembers(ev.id).map((m) => ({ slot: m.slot, userId: m.user_id, email: m.email, name: m.name, status: m.status })),
    viewers: viewerCount(ev.id),
  });
});
r('PATCH', '/api/events/:id', async (req, res, { params }) => {
  const { u, ev } = eventFor(req, params.id, 'manage');
  const body = await readJson(req);
  const f = {};
  if ('publicUpload' in body) f.public_upload_on = body.publicUpload ? 1 : 0;
  if ('showcase' in body) { if (u.role !== 'admin') fail(403, 'Only an admin can choose the sample event'); f.showcase = body.showcase ? 1 : 0; }
  if (body.newLink) f.public_token = shortToken();
  const out = db.updateEvent(ev.id, f);
  try { storage.writeMeta(out); } catch { /* storage offline: DB still updated */ }
  if (body.newLink) publish(ev.id, 'gone', {}); // old link viewers are told the link changed
  sendJson(res, 200, { event: eventJson(withCounts(out, u), u) });
});
r('DELETE', '/api/events/:id', async (req, res, { params }) => {
  const { ev } = eventFor(req, params.id, 'manage');
  const body = await readJson(req);
  if (String(body.confirm || '').trim() !== ev.folder_name) fail(400, 'Type the event name exactly to confirm');
  storage.deleteEvent(ev);
  sendJson(res, 200, { ok: true });
});
r('POST', '/api/events/:id/rescan', (req, res, { params }) => {
  const { ev } = eventFor(req, params.id, 'manage');
  requireStorage();
  sendJson(res, 200, { added: storage.scanEventPhotos(ev).length });
});

// Photographer slots (admin chooses who is Photographer 1, 2, 3...).
r('POST', '/api/events/:id/members', async (req, res, { params }) => {
  const { u, ev } = eventFor(req, params.id, 'manage');
  if (u.role !== 'admin' && ev.owner_id !== u.id) fail(403, 'Not allowed');
  const body = await readJson(req);
  const target = db.getUserByEmail(body.email);
  if (!target || target.status !== 'active') fail(404, 'That email is not an active user. Add it under Users first.');
  const slot = intOrNull(body.slot);
  if (slot != null && (slot < 1 || slot > 999)) fail(400, 'Slot must be 1 or more');
  db.setMember(ev.id, target.id, slot);
  try { storage.writeMeta(ev); } catch { /* ignore */ }
  sendJson(res, 200, { members: db.listMembers(ev.id) });
});
r('DELETE', '/api/events/:id/members/:userId', (req, res, { params }) => {
  const { ev } = eventFor(req, params.id, 'manage');
  db.removeMember(ev.id, Number(params.userId));
  try { storage.writeMeta(ev); } catch { /* ignore */ }
  sendJson(res, 200, { members: db.listMembers(ev.id) });
});

// ---------- photos (logged in) ----------
r('GET', '/api/events/:id/photos', (req, res, { params, query }) => {
  const { ev } = eventFor(req, params.id);
  const photos = db.galleryPhotos(ev.id, { before: intOrNull(query.get('before')), after: intOrNull(query.get('after')), includeHidden: true });
  sendJson(res, 200, { photos: photos.map(photoJson) });
});
function photoFor(req, id, need) {
  const p = db.getPhoto(id);
  if (!p) fail(404, 'Photo not found');
  const { u, ev } = eventFor(req, p.event_id, need);
  return { u, ev, p };
}
r('PATCH', '/api/photos/:id', async (req, res, { params }) => {
  const { ev, p } = photoFor(req, params.id, 'manage');
  const body = await readJson(req);
  if ('hidden' in body) {
    db.setPhotoHidden(p.id, body.hidden);
    publish(ev.id, body.hidden ? 'remove' : 'photo', body.hidden ? { id: p.id } : db.getPhoto(p.id));
  }
  sendJson(res, 200, { photo: photoJson(db.getPhoto(p.id)) });
});
r('DELETE', '/api/photos/:id', (req, res, { params }) => {
  const { ev, p } = photoFor(req, params.id, 'manage');
  storage.deletePhoto(ev, p);
  sendJson(res, 200, { ok: true });
});
r('GET', '/api/photos/:id/img/:size', (req, res, { params, query }) => {
  const { ev, p } = photoFor(req, params.id, 'view');
  serveImage(req, res, ev, p, params.size, query.get('dl') === '1', 'private, max-age=300');
});
// Photos added over SMB have no thumbnails; the admin's browser makes them (Rafi's choice).
r('GET', '/api/events/:id/missing-thumbs', (req, res, { params }) => {
  const { ev } = eventFor(req, params.id, 'manage');
  sendJson(res, 200, { photos: db.missingThumbs(ev.id).map(photoJson) });
});
r('PUT', '/api/photos/:id/thumb/:size', async (req, res, { params }) => {
  const { ev, p } = photoFor(req, params.id, 'manage');
  const bit = storage.THUMB_SIZES[params.size];
  if (!bit) fail(400, 'Size must be 480 or 1600');
  const buf = await readBody(req, 4 * 1024 * 1024);
  if (!sniff(buf)) fail(415, 'Thumbnail must be WebP or JPEG');
  fs.mkdirSync(storage.thumbDir(ev.id), { recursive: true });
  fs.writeFileSync(storage.thumbFile(ev.id, p.id, params.size), buf);
  db.addPhotoThumb(p.id, bit);
  sendJson(res, 200, { ok: true });
});

// ---------- chunked uploads (app photographers + web guests) ----------
// 1) POST create  -> {id, chunk}   2) PUT /api/uploads/:id?offset=N  (repeat, resumable)
// 3) PUT /api/uploads/:id/thumb/480|1600 (optional)   4) POST /api/uploads/:id/complete
function startUpload(res, ev, { source, slot, userId, name, size, ip }) {
  requireStorage();
  size = Number(size);
  const max = source === 'public' ? config.maxGuestBytes : config.maxUploadBytes;
  if (!Number.isInteger(size) || size <= 0) fail(400, 'File size is required');
  if (size > max) fail(413, `Photo is too large (max ${Math.round(max / 1048576)} MB)`);
  const fileName = source === 'public'
    ? `guest-${new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14)}-${shortToken(4)}.webp`
    : storage.cleanFileName(name);
  if (source === 'public' && !/\.webp$/i.test(String(name || '.webp'))) fail(415, 'Only WebP photos can be uploaded');
  const relPath = storage.uniqueRelPath(ev, storage.sourceFolder(source, slot), fileName);
  const up = db.createUpload({ id: randomToken(24), eventId: ev.id, userId, source, slot, relPath, size, clientIp: ip });
  fs.writeFileSync(storage.partFile(ev, relPath), '');
  sendJson(res, 201, { id: up.id, chunk: config.chunkBytes, received: 0, size, name: path.posix.basename(relPath), slot });
}
r('POST', '/api/events/:id/uploads', async (req, res, { params }) => {
  const { u, ev } = eventFor(req, params.id);
  const body = await readJson(req);
  let slot = db.memberSlot(ev.id, u.id);
  if (slot == null) {
    if (!canManage(u, ev)) fail(403, 'You are not a photographer for this event');
    slot = db.setMember(ev.id, u.id, null);
    try { storage.writeMeta(ev); } catch { /* ignore */ }
  }
  startUpload(res, ev, { source: 'photographer', slot, userId: u.id, name: body.name, size: body.size, ip: clientIp(req) });
});
r('POST', '/api/e/:token/uploads', async (req, res, { params }) => {
  const ev = eventByToken(params.token);
  if (!ev.public_upload_on) fail(403, 'Guest uploads are closed for this event');
  const ip = clientIp(req);
  if (!guestUploadLimit(ip)) fail(429, 'Too many uploads from this network. Try again later.');
  const body = await readJson(req);
  startUpload(res, ev, { source: 'public', slot: null, userId: null, name: body.name || 'photo.webp', size: body.size, ip });
});

function uploadFor(id) {
  const up = db.getUpload(id);
  if (!up) fail(404, 'Upload not found or expired. Start again.');
  const ev = db.getEvent(up.event_id);
  if (!ev) { db.deleteUpload(id); fail(410, 'The event was deleted'); }
  return { up, ev };
}
r('GET', '/api/uploads/:id', (req, res, { params }) => {
  const { up } = uploadFor(params.id);
  sendJson(res, 200, { id: up.id, received: up.received, size: up.size });
});
r('PUT', '/api/uploads/:id', async (req, res, { params, query }) => {
  const { up, ev } = uploadFor(params.id);
  requireStorage();
  const offset = Number(query.get('offset') || 0);
  if (offset !== up.received) return sendJson(res, 409, { error: 'Wrong offset, resume from received', received: up.received });
  const buf = await readBody(req, config.chunkBytes + 1024);
  if (offset + buf.length > up.size) fail(400, 'More data than the declared size');
  if (offset === 0) {
    const type = sniff(buf);
    if (up.source === 'public' ? type !== 'webp' : !type) {
      db.deleteUpload(up.id); fs.rmSync(storage.partFile(ev, up.rel_path), { force: true });
      fail(415, up.source === 'public' ? 'Only WebP photos can be uploaded' : 'Only WebP or JPEG photos can be uploaded');
    }
  }
  const part = storage.partFile(ev, up.rel_path);
  const fd = fs.openSync(part, 'r+');
  try { fs.writeSync(fd, buf, 0, buf.length, offset); fs.ftruncateSync(fd, offset + buf.length); } finally { fs.closeSync(fd); }
  db.setUploadReceived(up.id, offset + buf.length);
  sendJson(res, 200, { received: offset + buf.length, size: up.size });
});
r('PUT', '/api/uploads/:id/thumb/:size', async (req, res, { params }) => {
  const { up } = uploadFor(params.id);
  const bit = storage.THUMB_SIZES[params.size];
  if (!bit) fail(400, 'Size must be 480 or 1600');
  const buf = await readBody(req, 4 * 1024 * 1024);
  if (!sniff(buf)) fail(415, 'Thumbnail must be WebP or JPEG');
  const dir = path.join(config.dataDir, 'uploads', up.id);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, params.size), buf);
  db.addUploadThumb(up.id, bit);
  sendJson(res, 200, { ok: true });
});
r('POST', '/api/uploads/:id/complete', (req, res, { params }) => {
  const { up, ev } = uploadFor(params.id);
  requireStorage();
  if (up.received !== up.size) fail(400, `Upload incomplete (${up.received}/${up.size} bytes)`);
  const part = storage.partFile(ev, up.rel_path);
  if (up.source === 'public') stripWebpMetadata(part);
  const info = imageSizeOfFile(part);
  if (!info.width) { fs.rmSync(part, { force: true }); db.deleteUpload(up.id); fail(415, 'The file is not a readable photo'); }
  const final = path.join(storage.eventDir(ev), up.rel_path);
  fs.renameSync(part, final);
  const st = fs.statSync(final);
  const photoId = uuid();
  const tdir = path.join(config.dataDir, 'uploads', up.id);
  let thumbs = 0;
  for (const [size, bit] of Object.entries(storage.THUMB_SIZES)) {
    const t = path.join(tdir, size);
    if (fs.existsSync(t)) {
      fs.mkdirSync(storage.thumbDir(ev.id), { recursive: true });
      fs.renameSync(t, storage.thumbFile(ev.id, photoId, size));
      thumbs |= bit;
    }
  }
  fs.rmSync(tdir, { recursive: true, force: true });
  const { photo } = db.upsertPhoto({ id: photoId, eventId: ev.id, relPath: up.rel_path, source: up.source, slot: up.slot,
    width: info.width, height: info.height, bytes: st.size, mtime: Math.floor(st.mtimeMs), thumbs });
  db.deleteUpload(up.id);
  publish(ev.id, 'photo', photo);
  sendJson(res, 200, { photo: photoJson(photo) });
});
r('DELETE', '/api/uploads/:id', (req, res, { params }) => {
  const { up, ev } = uploadFor(params.id);
  fs.rmSync(storage.partFile(ev, up.rel_path), { force: true });
  fs.rmSync(path.join(config.dataDir, 'uploads', up.id), { recursive: true, force: true });
  db.deleteUpload(up.id);
  sendJson(res, 200, { ok: true });
});

// ---------- public gallery (by link token only; there is no public list) ----------
r('GET', '/api/e/:token', (req, res, { params }) => {
  const ev = eventByToken(params.token);
  const photos = db.galleryPhotos(ev.id, {});
  sendJson(res, 200, {
    event: { title: ev.title, date: ev.date, publicUpload: !!ev.public_upload_on, url: galleryUrl(ev) },
    photos: photos.map(photoJson), maxGuestBytes: config.maxGuestBytes, whatsapp: config.whatsapp,
  });
});
r('GET', '/api/e/:token/photos', (req, res, { params, query }) => {
  const ev = eventByToken(params.token);
  const photos = db.galleryPhotos(ev.id, { before: intOrNull(query.get('before')), after: intOrNull(query.get('after')) });
  sendJson(res, 200, { photos: photos.map(photoJson) });
});
r('GET', '/api/e/:token/stream', (req, res, { params }) => {
  const ev = eventByToken(params.token);
  subscribe(ev.id, req, res);
});
r('GET', '/p/:token/:photo/:size', (req, res, { params, query }) => {
  const ev = eventByToken(params.token);
  const p = db.getPhoto(params.photo);
  if (!p || p.event_id !== ev.id || p.hidden) fail(404, 'Photo not found');
  serveImage(req, res, ev, p, params.size, query.get('dl') === '1', 'public, max-age=86400');
});

// ---------- landing page sample event ----------
r('GET', '/api/showcase', (req, res) => {
  const ev = db.showcaseEvent();
  if (!ev) return sendJson(res, 200, { event: null, photos: [] });
  sendJson(res, 200, { event: { title: ev.title, date: ev.date }, photos: db.randomPhotos(ev.id, 24).map(photoJson) });
});
r('GET', '/s/:photo/:size', (req, res, { params }) => {
  const p = db.getPhoto(params.photo);
  const ev = p && db.getEvent(p.event_id);
  if (!ev || !ev.showcase || p.hidden || params.size === 'orig') fail(404, 'Not found');
  serveImage(req, res, ev, p, params.size, false, 'public, max-age=86400');
});

// ---------- admin ----------
r('GET', '/api/admin/users', (req, res) => {
  auth.requireAdmin(req);
  sendJson(res, 200, { users: db.listUsers().map(userJson) });
});
r('POST', '/api/admin/users', async (req, res) => {
  auth.requireAdmin(req);
  const body = await readJson(req);
  const email = db.normEmail(body.email);
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) fail(400, 'Enter a valid email');
  const role = body.role === 'admin' ? 'admin' : 'user';
  sendJson(res, 201, { user: userJson(db.upsertUser({ email, name: String(body.name || '').slice(0, 80), role })) });
});
r('PATCH', '/api/admin/users/:id', async (req, res, { params }) => {
  const me = auth.requireAdmin(req);
  const body = await readJson(req);
  if (Number(params.id) === me.id && (body.status === 'removed' || body.role === 'user')) fail(400, 'You cannot remove yourself');
  const f = {};
  if (['active', 'removed'].includes(body.status)) f.status = body.status;
  if (['admin', 'user'].includes(body.role)) f.role = body.role;
  if (typeof body.name === 'string') f.name = body.name.slice(0, 80);
  let u;
  try { u = db.updateUser(Number(params.id), f); } catch (e) { fail(e.status || 400, e.message); }
  if (!u) fail(404, 'User not found');
  sendJson(res, 200, { user: userJson(u) });
});
r('GET', '/api/admin/status', (req, res) => {
  auth.requireAdmin(req);
  sendJson(res, 200, { storageOnline: storage.storageOnline(), photosDir: config.photosDir, scan: storage.scanAll() });
});

// ---------- image serving ----------
function contentTypeOf(file) {
  const fd = fs.openSync(file, 'r');
  try { const b = Buffer.alloc(12); fs.readSync(fd, b, 0, 12, 0); return sniff(b) === 'jpeg' ? 'image/jpeg' : 'image/webp'; }
  finally { fs.closeSync(fd); }
}
function serveImage(req, res, ev, p, size, download, cacheControl) {
  let file;
  if (size === '480' || size === '1600') {
    const t = storage.thumbFile(ev.id, p.id, size);
    if (fs.existsSync(t)) file = t;
    else if (size === '480' && fs.existsSync(storage.thumbFile(ev.id, p.id, '1600'))) file = storage.thumbFile(ev.id, p.id, '1600');
  } else if (size !== 'orig') fail(400, 'Bad size');
  if (!file) file = storage.photoFile(ev, p); // no thumbnail yet: fall back to the original
  let st;
  try { st = fs.statSync(file); } catch { throw new HttpError(404, 'Photo file missing'); }
  const headers = { 'content-type': contentTypeOf(file), 'content-length': st.size, 'cache-control': cacheControl, etag: `"${p.id}-${size}-${Math.floor(st.mtimeMs)}"` };
  if (req.headers['if-none-match'] === headers.etag) { res.writeHead(304, headers); return res.end(); }
  if (download) headers['content-disposition'] = `attachment; filename="${encodeURIComponent(path.posix.basename(p.rel_path))}"`;
  res.writeHead(200, headers);
  if (req.method === 'HEAD') return res.end();
  fs.createReadStream(file).pipe(res);
}
