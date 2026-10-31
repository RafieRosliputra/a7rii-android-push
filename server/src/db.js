// SQLite metadata store (node:sqlite, no npm packages). Schema v2 (Live Photo v4).
// Folders on disk are the source of truth for events and photos; this DB holds event codes,
// live windows, sessions (guest / photographer / client), photographer slots, photo status
// (moderation) and dimensions. Admins are not stored here: they come in through Cloudflare Access.
import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';

const SCHEMA_VERSION = 2;

const SCHEMA = `
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);

CREATE TABLE IF NOT EXISTS events (
  id           TEXT PRIMARY KEY,
  folder_name  TEXT NOT NULL,
  date         TEXT NOT NULL DEFAULT '',
  title        TEXT NOT NULL DEFAULT '',
  organiser    TEXT NOT NULL DEFAULT '',
  hide_name    INTEGER NOT NULL DEFAULT 0,
  public_token TEXT NOT NULL UNIQUE,           -- gallery link /e/<token>
  public_code  TEXT NOT NULL,                  -- 6 digits, guests (changes every live window)
  photo_code   TEXT NOT NULL,                  -- 6 digits, photographers
  client_code  TEXT NOT NULL,                  -- 6 digits, organiser panel
  starts_at    INTEGER,                        -- live window (ms); NULL = not scheduled
  ends_at      INTEGER,
  closed_at    INTEGER,
  public_upload_on INTEGER NOT NULL DEFAULT 1,
  showcase     INTEGER NOT NULL DEFAULT 0,
  settings     TEXT NOT NULL DEFAULT '{}',     -- slideshow settings JSON
  request      TEXT,                           -- organiser request JSON {type, startsAt, at}
  created_at   INTEGER NOT NULL,
  deleted_at   INTEGER
);

CREATE TABLE IF NOT EXISTS photographers (
  event_id   TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  slot       INTEGER NOT NULL CHECK (slot >= 1),
  name       TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (event_id, slot)
);

CREATE TABLE IF NOT EXISTS sessions (
  id          INTEGER PRIMARY KEY,
  token_hash  TEXT NOT NULL UNIQUE,
  role        TEXT NOT NULL CHECK (role IN ('guest','photographer','client')),
  event_id    TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  name        TEXT NOT NULL DEFAULT '',
  slot        INTEGER,
  agreed      INTEGER NOT NULL DEFAULT 0,      -- guest accepted the disclaimer
  uploads     INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL,
  revoked_at  INTEGER
);
CREATE INDEX IF NOT EXISTS sessions_event ON sessions(event_id, role);

CREATE TABLE IF NOT EXISTS photos (
  id         TEXT PRIMARY KEY,
  event_id   TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  rel_path   TEXT NOT NULL,
  source     TEXT NOT NULL CHECK (source IN ('photographer','public','other')),
  slot       INTEGER,
  uploader   TEXT NOT NULL DEFAULT '',
  status     TEXT NOT NULL DEFAULT 'approved' CHECK (status IN ('approved','pending','flagged','blocked')),
  flags      TEXT NOT NULL DEFAULT '[]',
  mod_tries  INTEGER NOT NULL DEFAULT 0,
  width      INTEGER NOT NULL DEFAULT 0,
  height     INTEGER NOT NULL DEFAULT 0,
  bytes      INTEGER NOT NULL DEFAULT 0,
  mtime      INTEGER NOT NULL DEFAULT 0,
  hidden     INTEGER NOT NULL DEFAULT 0,
  thumbs     INTEGER NOT NULL DEFAULT 0,       -- bit 1 = 480px, bit 2 = 1600px
  seq        INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE (event_id, rel_path)
);
CREATE INDEX IF NOT EXISTS photos_event_seq ON photos(event_id, seq);
CREATE INDEX IF NOT EXISTS photos_status ON photos(status);

CREATE TABLE IF NOT EXISTS uploads (
  id         TEXT PRIMARY KEY,
  event_id   TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  session_id INTEGER,
  source     TEXT NOT NULL,
  slot       INTEGER,
  uploader   TEXT NOT NULL DEFAULT '',
  rel_path   TEXT NOT NULL,
  size       INTEGER NOT NULL,
  received   INTEGER NOT NULL DEFAULT 0,
  thumbs     INTEGER NOT NULL DEFAULT 0,
  client_ip  TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
`;

export const DEFAULT_SETTINGS = { newSec: 3, loopSec: 5, loopSource: 'all', idleMin: 3 };

let db;
const now = () => Date.now();

export function openDb(file = path.join(config.dataDir, 'livephoto.db')) {
  if (db) db.close();
  if (file !== ':memory:') {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    // v1 (v3 app) database: keep it aside and start fresh; the scanner re-adopts every folder.
    if (fs.existsSync(file)) {
      const old = new DatabaseSync(file);
      let v = null;
      try { v = old.prepare("SELECT value FROM meta WHERE key='schema'").get()?.value; } catch { /* no meta */ }
      old.close();
      if (v && Number(v) < SCHEMA_VERSION) {
        for (const ext of ['', '-wal', '-shm']) if (fs.existsSync(file + ext)) fs.renameSync(file + ext, file.replace(/\.db$/, `.v${v}.db`) + ext);
      }
    }
  }
  db = new DatabaseSync(file);
  db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;');
  db.exec(SCHEMA);
  db.prepare("INSERT OR IGNORE INTO meta(key,value) VALUES('schema',?)").run(String(SCHEMA_VERSION));
  return db;
}
export function closeDb() { if (db) { db.close(); db = undefined; } }

const q = (sql) => db.prepare(sql);
export function tx(fn) {
  db.exec('BEGIN IMMEDIATE');
  try { const r = fn(); db.exec('COMMIT'); return r; }
  catch (e) { db.exec('ROLLBACK'); throw e; }
}

// ---------- codes ----------
// Every 6-digit code is unique across all events that are not deleted (any role).
export function codeTaken(code, exceptEventId = '') {
  return !!q(`SELECT 1 FROM events WHERE deleted_at IS NULL AND id<>? AND (public_code=? OR photo_code=? OR client_code=?)`)
    .get(exceptEventId, code, code, code);
}
export function newCode(exceptEventId = '', avoid = []) {
  for (let i = 0; i < 1000; i++) {
    const c = String(Math.floor(Math.random() * 1e6)).padStart(6, '0');
    if (!avoid.includes(c) && !codeTaken(c, exceptEventId)) return c;
  }
  throw new Error('No free code');
}
export function eventByCode(code) {
  return q(`SELECT *, CASE WHEN public_code=? THEN 'guest' WHEN photo_code=? THEN 'photographer' ELSE 'client' END AS code_role
            FROM events WHERE deleted_at IS NULL AND (public_code=? OR photo_code=? OR client_code=?)`).get(code, code, code, code, code);
}

// ---------- events ----------
export function status(ev, t = now()) {
  if (!ev) return 'none';
  if (ev.closed_at && (!ev.starts_at || ev.closed_at >= ev.starts_at)) return 'closed';
  if (!ev.starts_at) return 'draft';
  if (t < ev.starts_at) return 'scheduled';
  if (t < ev.ends_at) return 'live';
  return 'ended';
}
export const isLive = (ev, t) => status(ev, t) === 'live';
// Organiser code works from creation until 7 days after the event ends or is closed.
export function clientWindowOpen(ev, t = now()) {
  const end = ev.closed_at || ev.ends_at;
  return !end || t < end + 7 * 86400e3;
}

export const getEvent = (id) => q('SELECT * FROM events WHERE id=? AND deleted_at IS NULL').get(id);
export const getEventAny = (id) => q('SELECT * FROM events WHERE id=?').get(id);
export const getEventByToken = (token) => q('SELECT * FROM events WHERE public_token=? AND deleted_at IS NULL').get(token);
export const getEventByFolder = (folder) => q('SELECT * FROM events WHERE folder_name=? AND deleted_at IS NULL').get(folder);
export const liveEvents = () => q('SELECT * FROM events WHERE deleted_at IS NULL').all();
export const showcaseEvent = () => q('SELECT * FROM events WHERE showcase=1 AND deleted_at IS NULL ORDER BY date DESC LIMIT 1').get();
export const nowLive = () => liveEvents().filter((e) => isLive(e)).sort((a, b) => a.starts_at - b.starts_at);

export function listEvents() {
  return q(`SELECT e.*,
      (SELECT COUNT(*) FROM photos p WHERE p.event_id=e.id) AS photo_count,
      (SELECT COUNT(*) FROM photos p WHERE p.event_id=e.id AND p.source='public') AS guest_count,
      (SELECT COUNT(*) FROM photos p WHERE p.event_id=e.id AND p.status IN ('flagged','pending')) AS review_count
    FROM events e WHERE e.deleted_at IS NULL ORDER BY e.date DESC, e.created_at DESC`).all();
}
export function eventCounts(id) {
  return q(`SELECT COUNT(*) AS photo_count, SUM(source='public') AS guest_count,
      SUM(status IN ('flagged','pending')) AS review_count, SUM(status='blocked') AS blocked_count
    FROM photos WHERE event_id=?`).get(id);
}

export function insertEvent({ id, folderName, date, title, token, publicCode, photoCode, clientCode, organiser = '',
  publicUploadOn = 1, createdAt = now() }) {
  const existing = getEventAny(id);
  if (existing) {
    q('UPDATE events SET folder_name=?, date=?, title=?, deleted_at=NULL WHERE id=?').run(folderName, date, title, id);
    return getEvent(id);
  }
  publicCode = publicCode && !codeTaken(publicCode) ? publicCode : newCode();
  photoCode = photoCode && !codeTaken(photoCode) && photoCode !== publicCode ? photoCode : newCode('', [publicCode]);
  clientCode = clientCode && !codeTaken(clientCode) && ![publicCode, photoCode].includes(clientCode) ? clientCode : newCode('', [publicCode, photoCode]);
  q(`INSERT INTO events(id,folder_name,date,title,organiser,public_token,public_code,photo_code,client_code,public_upload_on,created_at)
     VALUES(?,?,?,?,?,?,?,?,?,?,?)`).run(id, folderName, date, title, organiser, token, publicCode, photoCode, clientCode, publicUploadOn ? 1 : 0, createdAt);
  return getEvent(id);
}

const EDITABLE = ['folder_name', 'date', 'title', 'organiser', 'hide_name', 'public_token', 'public_code', 'photo_code', 'client_code',
  'starts_at', 'ends_at', 'closed_at', 'public_upload_on', 'showcase', 'settings', 'request'];
export function updateEvent(id, fields) {
  for (const [k, v] of Object.entries(fields)) {
    if (!EDITABLE.includes(k)) continue;
    q(`UPDATE events SET ${k}=? WHERE id=?`).run(typeof v === 'boolean' ? (v ? 1 : 0) : v, id);
  }
  if (fields.showcase) q('UPDATE events SET showcase=0 WHERE id<>?').run(id);
  return getEvent(id);
}
export function settingsOf(ev) {
  let s = {};
  try { s = JSON.parse(ev.settings || '{}'); } catch { /* bad JSON */ }
  return { ...DEFAULT_SETTINGS, ...s };
}

export function markEventDeleted(id) {
  tx(() => {
    q('DELETE FROM photos WHERE event_id=?').run(id);
    q('DELETE FROM uploads WHERE event_id=?').run(id);
    q('UPDATE sessions SET revoked_at=? WHERE event_id=? AND revoked_at IS NULL').run(now(), id);
    q('UPDATE events SET deleted_at=?, showcase=0 WHERE id=?').run(now(), id);
  });
}

// ---------- photographers (Photographer N, by name) ----------
export const listPhotographers = (eventId) => q('SELECT * FROM photographers WHERE event_id=? ORDER BY slot').all(eventId);
export function slotForName(eventId, name) {
  const key = name.trim().toLowerCase();
  const found = listPhotographers(eventId).find((p) => p.name.trim().toLowerCase() === key);
  if (found) return found.slot;
  return tx(() => {
    const used = new Set(listPhotographers(eventId).map((p) => p.slot));
    let n = 1; while (used.has(n)) n++;
    q('INSERT INTO photographers(event_id,slot,name,created_at) VALUES(?,?,?,?)').run(eventId, n, name.trim(), now());
    return n;
  });
}
// Admin moves a photographer to another slot number (swap if taken). Live sessions follow.
export function moveSlot(eventId, from, to) {
  return tx(() => {
    const a = q('SELECT * FROM photographers WHERE event_id=? AND slot=?').get(eventId, from);
    if (!a) return false;
    const b = q('SELECT * FROM photographers WHERE event_id=? AND slot=?').get(eventId, to);
    q('DELETE FROM photographers WHERE event_id=? AND slot IN (?,?)').run(eventId, from, to);
    q('INSERT INTO photographers(event_id,slot,name,created_at) VALUES(?,?,?,?)').run(eventId, to, a.name, a.created_at);
    if (b) q('INSERT INTO photographers(event_id,slot,name,created_at) VALUES(?,?,?,?)').run(eventId, from, b.name, b.created_at);
    q("UPDATE sessions SET slot=CASE WHEN slot=? THEN ? WHEN slot=? THEN ? ELSE slot END WHERE event_id=? AND role='photographer'")
      .run(from, to, to, b ? from : to, eventId);
    return true;
  });
}

// ---------- sessions ----------
export function createSession({ tokenHash, role, eventId, name = '', slot = null }) {
  const r = q('INSERT INTO sessions(token_hash,role,event_id,name,slot,created_at) VALUES(?,?,?,?,?,?)')
    .run(tokenHash, role, eventId, name, slot, now());
  return getSession(Number(r.lastInsertRowid));
}
export const getSession = (id) => q('SELECT * FROM sessions WHERE id=?').get(id);
export const sessionByHash = (h) => q('SELECT * FROM sessions WHERE token_hash=? AND revoked_at IS NULL').get(h);
export const revokeSession = (h) => q('UPDATE sessions SET revoked_at=? WHERE token_hash=?').run(now(), h);
export const revokeEventSessions = (eventId, roles) =>
  q(`UPDATE sessions SET revoked_at=? WHERE event_id=? AND revoked_at IS NULL AND role IN (${roles.map(() => '?').join(',')})`).run(now(), eventId, ...roles);
export const revokeSessionId = (id) => q('UPDATE sessions SET revoked_at=? WHERE id=?').run(now(), id);
export const setAgreed = (id) => q('UPDATE sessions SET agreed=1 WHERE id=?').run(id);
export const bumpUploads = (id) => q('UPDATE sessions SET uploads=uploads+1 WHERE id=?').run(id);
export const activeDevices = (eventId) => q(`SELECT id, role, name, slot, uploads, created_at FROM sessions
  WHERE event_id=? AND revoked_at IS NULL AND role='photographer' ORDER BY slot, created_at`).all(eventId);

// ---------- photos ----------
const nextSeq = () => q('SELECT COALESCE(MAX(seq),0)+1 AS n FROM photos').get().n;

export function upsertPhoto({ id, eventId, relPath, source, slot = null, uploader = '', status: st = 'approved',
  width = 0, height = 0, bytes = 0, mtime = 0, thumbs = 0 }) {
  const existing = q('SELECT * FROM photos WHERE event_id=? AND rel_path=?').get(eventId, relPath);
  if (existing) {
    q('UPDATE photos SET width=?, height=?, bytes=?, mtime=?, thumbs=thumbs|? WHERE id=?')
      .run(width || existing.width, height || existing.height, bytes, mtime, thumbs, existing.id);
    return { photo: getPhoto(existing.id), created: false };
  }
  q(`INSERT INTO photos(id,event_id,rel_path,source,slot,uploader,status,width,height,bytes,mtime,thumbs,seq,created_at)
     VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)`).run(id, eventId, relPath, source, slot, uploader, st, width, height, bytes, mtime, thumbs, nextSeq(), now());
  return { photo: getPhoto(id), created: true };
}
export const getPhoto = (id) => q('SELECT * FROM photos WHERE id=?').get(id);
export const photosOf = (eventId) => q('SELECT * FROM photos WHERE event_id=?').all(eventId);
export const deletePhotoRow = (id) => q('DELETE FROM photos WHERE id=?').run(id);
export const setPhotoHidden = (id, hidden) => q('UPDATE photos SET hidden=? WHERE id=?').run(hidden ? 1 : 0, id);
export const addPhotoThumb = (id, bit) => q('UPDATE photos SET thumbs=thumbs|? WHERE id=?').run(bit, id);
export function setPhotoStatus(id, st, flags) {
  if (flags) q('UPDATE photos SET status=?, flags=? WHERE id=?').run(st, JSON.stringify(flags), id);
  else q('UPDATE photos SET status=? WHERE id=?').run(st, id);
  return getPhoto(id);
}
export const bumpModTries = (id) => q('UPDATE photos SET mod_tries=mod_tries+1 WHERE id=?').run(id);
export const pendingPhotos = (limit = 20) => q("SELECT * FROM photos WHERE status='pending' ORDER BY seq LIMIT ?").all(limit);
export const missingThumbs = (eventId, limit = 200) =>
  q('SELECT * FROM photos WHERE event_id=? AND thumbs<>3 ORDER BY seq DESC LIMIT ?').all(eventId, limit);

// Public view: approved and not hidden. Staff view: filter by status ('all', 'review', 'blocked', ...).
export function galleryPhotos(eventId, { before, after, limit = 120, staff = false, filter = 'all', source = 'all' } = {}) {
  const where = ['event_id=?'];
  const args = [eventId];
  if (!staff) where.push("hidden=0 AND status='approved'");
  else if (filter === 'review') where.push("status IN ('flagged','pending')");
  else if (filter === 'blocked') where.push("status='blocked'");
  else if (filter === 'hidden') where.push('hidden=1');
  else if (filter === 'visible') where.push("hidden=0 AND status='approved'");
  if (source === 'public') where.push("source='public'");
  else if (source === 'photographer') where.push("source<>'public'");
  if (after != null) { where.push('seq>?'); args.push(after); }
  if (before != null) { where.push('seq<?'); args.push(before); }
  args.push(limit);
  return q(`SELECT * FROM photos WHERE ${where.join(' AND ')} ORDER BY seq DESC LIMIT ?`).all(...args);
}
export const randomPhotos = (eventId, n) =>
  q("SELECT * FROM photos WHERE event_id=? AND hidden=0 AND status='approved' AND width>0 ORDER BY RANDOM() LIMIT ?").all(eventId, n);

// ---------- chunked uploads ----------
export function createUpload(u) {
  const t = now();
  q(`INSERT INTO uploads(id,event_id,session_id,source,slot,uploader,rel_path,size,received,thumbs,client_ip,created_at,updated_at)
     VALUES(?,?,?,?,?,?,?,?,0,0,?,?,?)`).run(u.id, u.eventId, u.sessionId ?? null, u.source, u.slot ?? null, u.uploader || '', u.relPath, u.size, u.clientIp || '', t, t);
  return getUpload(u.id);
}
export const getUpload = (id) => q('SELECT * FROM uploads WHERE id=?').get(id);
export const setUploadReceived = (id, received) => q('UPDATE uploads SET received=?, updated_at=? WHERE id=?').run(received, now(), id);
export const addUploadThumb = (id, bit) => q('UPDATE uploads SET thumbs=thumbs|?, updated_at=? WHERE id=?').run(bit, now(), id);
export const deleteUpload = (id) => q('DELETE FROM uploads WHERE id=?').run(id);
export const staleUploads = (olderThanMs) => q('SELECT * FROM uploads WHERE updated_at<?').all(now() - olderThanMs);
export const openUploadsFor = (sessionId) => q('SELECT COUNT(*) AS n FROM uploads WHERE session_id=?').get(sessionId).n;
