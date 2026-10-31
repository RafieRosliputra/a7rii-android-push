// SQLite metadata store (node:sqlite, no npm packages).
// The folders on disk are the source of truth for events and photos; this DB holds
// who owns what, Photographer slots, gallery tokens, sessions and photo dimensions.
import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';

const SCHEMA_VERSION = 1;

const SCHEMA = `
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);

CREATE TABLE IF NOT EXISTS users (
  id            INTEGER PRIMARY KEY,
  email         TEXT NOT NULL UNIQUE,
  name          TEXT NOT NULL DEFAULT '',
  role          TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('admin','user')),
  status        TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','removed')),
  google_sub    TEXT,
  created_at    INTEGER NOT NULL,
  last_login_at INTEGER
);

CREATE TABLE IF NOT EXISTS sessions (
  id          INTEGER PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash  TEXT NOT NULL UNIQUE,
  kind        TEXT NOT NULL CHECK (kind IN ('web','app')),
  label       TEXT NOT NULL DEFAULT '',
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);

CREATE TABLE IF NOT EXISTS login_codes (
  email      TEXT PRIMARY KEY,
  code_hash  TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  attempts   INTEGER NOT NULL DEFAULT 0,
  sent_at    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS events (
  id               TEXT PRIMARY KEY,
  folder_name      TEXT NOT NULL,
  date             TEXT NOT NULL DEFAULT '',
  title            TEXT NOT NULL DEFAULT '',
  owner_id         INTEGER REFERENCES users(id),
  public_token     TEXT NOT NULL UNIQUE,
  public_upload_on INTEGER NOT NULL DEFAULT 1,
  showcase         INTEGER NOT NULL DEFAULT 0,
  created_at       INTEGER NOT NULL,
  deleted_at       INTEGER
);
CREATE INDEX IF NOT EXISTS events_owner ON events(owner_id);

CREATE TABLE IF NOT EXISTS event_members (
  event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  user_id  INTEGER NOT NULL REFERENCES users(id),
  slot     INTEGER NOT NULL CHECK (slot >= 1),
  PRIMARY KEY (event_id, user_id),
  UNIQUE (event_id, slot)
);

CREATE TABLE IF NOT EXISTS photos (
  id         TEXT PRIMARY KEY,
  event_id   TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  rel_path   TEXT NOT NULL,
  source     TEXT NOT NULL CHECK (source IN ('photographer','public','other')),
  slot       INTEGER,
  width      INTEGER NOT NULL DEFAULT 0,
  height     INTEGER NOT NULL DEFAULT 0,
  bytes      INTEGER NOT NULL DEFAULT 0,
  mtime      INTEGER NOT NULL DEFAULT 0,
  hidden     INTEGER NOT NULL DEFAULT 0,
  thumbs     INTEGER NOT NULL DEFAULT 0,  -- bit 1 = 480px, bit 2 = 1600px
  seq        INTEGER NOT NULL,            -- monotonically increasing, used as the live cursor
  created_at INTEGER NOT NULL,
  UNIQUE (event_id, rel_path)
);
CREATE INDEX IF NOT EXISTS photos_event_seq ON photos(event_id, seq);

CREATE TABLE IF NOT EXISTS uploads (
  id         TEXT PRIMARY KEY,
  event_id   TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  user_id    INTEGER,
  source     TEXT NOT NULL,
  slot       INTEGER,
  rel_path   TEXT NOT NULL,
  size       INTEGER NOT NULL,
  received   INTEGER NOT NULL DEFAULT 0,
  thumbs     INTEGER NOT NULL DEFAULT 0,
  client_ip  TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
`;

let db;
const now = () => Date.now();

export function openDb(file = path.join(config.dataDir, 'livephoto.db')) {
  if (db) db.close();
  if (file !== ':memory:') fs.mkdirSync(path.dirname(file), { recursive: true });
  db = new DatabaseSync(file);
  db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;');
  db.exec(SCHEMA);
  const v = db.prepare("SELECT value FROM meta WHERE key='schema'").get();
  if (!v) db.prepare("INSERT INTO meta(key,value) VALUES('schema',?)").run(String(SCHEMA_VERSION));
  ensureAdmin(config.adminEmail);
  return db;
}

export function closeDb() { if (db) { db.close(); db = undefined; } }

const q = (sql) => db.prepare(sql);

export function tx(fn) {
  db.exec('BEGIN IMMEDIATE');
  try { const r = fn(); db.exec('COMMIT'); return r; }
  catch (e) { db.exec('ROLLBACK'); throw e; }
}

// ---------- users ----------
export const normEmail = (e) => String(e || '').trim().toLowerCase();

function ensureAdmin(email) {
  if (!email) return;
  const u = getUserByEmail(email);
  if (!u) q('INSERT INTO users(email,role,status,created_at) VALUES(?,?,?,?)').run(email, 'admin', 'active', now());
  else if (u.role !== 'admin' || u.status !== 'active') q("UPDATE users SET role='admin', status='active' WHERE id=?").run(u.id);
}

export const getUser = (id) => q('SELECT * FROM users WHERE id=?').get(id);
export const getUserByEmail = (email) => q('SELECT * FROM users WHERE email=?').get(normEmail(email));
export const listUsers = () => q(`
  SELECT u.*, (SELECT COUNT(*) FROM events e WHERE e.owner_id=u.id AND e.deleted_at IS NULL) AS event_count
  FROM users u ORDER BY u.status, u.role, u.email`).all();

// Add (or re-activate) a user. Re-adding a removed email brings back the same account,
// so the events it created are visible again.
export function upsertUser({ email, name = '', role = 'user' }) {
  email = normEmail(email);
  const u = getUserByEmail(email);
  if (u) {
    q("UPDATE users SET status='active', role=?, name=CASE WHEN ?<>'' THEN ? ELSE name END WHERE id=?")
      .run(role, name, name, u.id);
    return getUser(u.id);
  }
  const r = q('INSERT INTO users(email,name,role,status,created_at) VALUES(?,?,?,?,?)').run(email, name, role, 'active', now());
  return getUser(Number(r.lastInsertRowid));
}

export function updateUser(id, { status, role, name }) {
  const u = getUser(id);
  if (!u) return null;
  if (u.email === config.adminEmail && (status === 'removed' || role === 'user')) throw Object.assign(new Error('The main admin cannot be removed'), { status: 400 });
  if (status) q('UPDATE users SET status=? WHERE id=?').run(status, id);
  if (role) q('UPDATE users SET role=? WHERE id=?').run(role, id);
  if (name !== undefined) q('UPDATE users SET name=? WHERE id=?').run(String(name), id);
  if (status === 'removed') q('DELETE FROM sessions WHERE user_id=?').run(id);
  return getUser(id);
}

export function noteLogin(id, { name, googleSub } = {}) {
  q('UPDATE users SET last_login_at=?, name=CASE WHEN name=? AND ? IS NOT NULL THEN ? ELSE name END, google_sub=COALESCE(?,google_sub) WHERE id=?')
    .run(now(), '', name ?? null, name ?? null, googleSub ?? null, id);
}

// ---------- sessions ----------
export function createSession({ userId, tokenHash, kind, label = '' }) {
  const t = now();
  q('INSERT INTO sessions(user_id,token_hash,kind,label,created_at,expires_at) VALUES(?,?,?,?,?,?)')
    .run(userId, tokenHash, kind, label, t, t + config.sessionDays * 86400e3);
}
export const sessionUser = (tokenHash) => q(`
  SELECT u.*, s.id AS session_id, s.kind AS session_kind FROM sessions s JOIN users u ON u.id=s.user_id
  WHERE s.token_hash=? AND s.expires_at>? AND u.status='active'`).get(tokenHash, now());
export const deleteSession = (tokenHash) => q('DELETE FROM sessions WHERE token_hash=?').run(tokenHash);
export const pruneSessions = () => q('DELETE FROM sessions WHERE expires_at<?').run(now());

// ---------- login codes ----------
export const getCode = (email) => q('SELECT * FROM login_codes WHERE email=?').get(normEmail(email));
export function putCode(email, codeHash) {
  const t = now();
  q(`INSERT INTO login_codes(email,code_hash,expires_at,attempts,sent_at) VALUES(?,?,?,0,?)
     ON CONFLICT(email) DO UPDATE SET code_hash=excluded.code_hash, expires_at=excluded.expires_at, attempts=0, sent_at=excluded.sent_at`)
    .run(normEmail(email), codeHash, t + config.codeMinutes * 60e3, t);
}
export const bumpCodeAttempts = (email) => q('UPDATE login_codes SET attempts=attempts+1 WHERE email=?').run(normEmail(email));
export const deleteCode = (email) => q('DELETE FROM login_codes WHERE email=?').run(normEmail(email));

// ---------- events ----------
export const getEvent = (id) => q('SELECT * FROM events WHERE id=? AND deleted_at IS NULL').get(id);
export const getEventAny = (id) => q('SELECT * FROM events WHERE id=?').get(id);
export const getEventByToken = (token) => q('SELECT * FROM events WHERE public_token=? AND deleted_at IS NULL').get(token);
export const getEventByFolder = (folder) => q('SELECT * FROM events WHERE folder_name=? AND deleted_at IS NULL').get(folder);
export const liveEvents = () => q('SELECT * FROM events WHERE deleted_at IS NULL').all();
export const showcaseEvent = () => q('SELECT * FROM events WHERE showcase=1 AND deleted_at IS NULL ORDER BY date DESC LIMIT 1').get();

const EVENT_LIST = `
  SELECT e.*, o.email AS owner_email, o.name AS owner_name,
    (SELECT COUNT(*) FROM photos p WHERE p.event_id=e.id) AS photo_count,
    (SELECT COUNT(*) FROM photos p WHERE p.event_id=e.id AND p.source='public') AS guest_count
  FROM events e LEFT JOIN users o ON o.id=e.owner_id
  WHERE e.deleted_at IS NULL`;

export function listEventsFor(user) {
  if (user.role === 'admin') return q(`${EVENT_LIST} ORDER BY e.date DESC, e.created_at DESC`).all();
  return q(`${EVENT_LIST} AND (e.owner_id=? OR e.id IN (SELECT event_id FROM event_members WHERE user_id=?))
            ORDER BY e.date DESC, e.created_at DESC`).all(user.id, user.id);
}

export function insertEvent({ id, folderName, date, title, ownerId, token, publicUploadOn = 1, createdAt = now() }) {
  q(`INSERT INTO events(id,folder_name,date,title,owner_id,public_token,public_upload_on,created_at) VALUES(?,?,?,?,?,?,?,?)
     ON CONFLICT(id) DO UPDATE SET folder_name=excluded.folder_name, date=excluded.date, title=excluded.title, deleted_at=NULL`)
    .run(id, folderName, date, title, ownerId ?? null, token, publicUploadOn ? 1 : 0, createdAt);
  return getEvent(id);
}

export function updateEvent(id, fields) {
  const allowed = ['folder_name', 'date', 'title', 'owner_id', 'public_token', 'public_upload_on', 'showcase'];
  for (const [k, v] of Object.entries(fields)) {
    if (!allowed.includes(k)) continue;
    q(`UPDATE events SET ${k}=? WHERE id=?`).run(typeof v === 'boolean' ? (v ? 1 : 0) : v, id);
  }
  if (fields.showcase) q('UPDATE events SET showcase=0 WHERE id<>?').run(id); // only one sample event
  return getEvent(id);
}

// Folder gone from disk (deleted in the app or over SMB): the event and its photo rows go.
export function markEventDeleted(id) {
  tx(() => {
    q('DELETE FROM photos WHERE event_id=?').run(id);
    q('DELETE FROM uploads WHERE event_id=?').run(id);
    q('UPDATE events SET deleted_at=?, showcase=0 WHERE id=?').run(now(), id);
  });
}

// ---------- members (Photographer N) ----------
export const listMembers = (eventId) => q(`
  SELECT m.slot, u.id AS user_id, u.email, u.name, u.status FROM event_members m JOIN users u ON u.id=m.user_id
  WHERE m.event_id=? ORDER BY m.slot`).all(eventId);
export const memberSlot = (eventId, userId) => q('SELECT slot FROM event_members WHERE event_id=? AND user_id=?').get(eventId, userId)?.slot ?? null;
export const nextFreeSlot = (eventId) => {
  const used = new Set(q('SELECT slot FROM event_members WHERE event_id=?').all(eventId).map((r) => r.slot));
  let n = 1; while (used.has(n)) n++; return n;
};

// Put a user in a slot (null = keep current or take the next free one).
// If another user holds that slot, the two swap (or the other moves to the next free slot).
export function setMember(eventId, userId, slot) {
  return tx(() => {
    const current = memberSlot(eventId, userId);
    if (!slot) slot = current || nextFreeSlot(eventId);
    if (slot === current) return slot;
    const holder = q('SELECT user_id FROM event_members WHERE event_id=? AND slot=?').get(eventId, slot);
    q('DELETE FROM event_members WHERE event_id=? AND user_id=?').run(eventId, userId);
    if (holder) q('DELETE FROM event_members WHERE event_id=? AND user_id=?').run(eventId, holder.user_id);
    q('INSERT INTO event_members(event_id,user_id,slot) VALUES(?,?,?)').run(eventId, userId, slot);
    if (holder) q('INSERT INTO event_members(event_id,user_id,slot) VALUES(?,?,?)').run(eventId, holder.user_id, current || nextFreeSlot(eventId));
    return slot;
  });
}
export const removeMember = (eventId, userId) => q('DELETE FROM event_members WHERE event_id=? AND user_id=?').run(eventId, userId);

// ---------- photos ----------
const nextSeq = () => (q('SELECT COALESCE(MAX(seq),0)+1 AS n FROM photos').get().n);

export function upsertPhoto({ id, eventId, relPath, source, slot = null, width = 0, height = 0, bytes = 0, mtime = 0, thumbs = 0 }) {
  const existing = q('SELECT * FROM photos WHERE event_id=? AND rel_path=?').get(eventId, relPath);
  if (existing) {
    q('UPDATE photos SET width=?, height=?, bytes=?, mtime=?, thumbs=thumbs|? WHERE id=?')
      .run(width || existing.width, height || existing.height, bytes, mtime, thumbs, existing.id);
    return { photo: getPhoto(existing.id), created: false };
  }
  q(`INSERT INTO photos(id,event_id,rel_path,source,slot,width,height,bytes,mtime,thumbs,seq,created_at)
     VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`).run(id, eventId, relPath, source, slot, width, height, bytes, mtime, thumbs, nextSeq(), now());
  return { photo: getPhoto(id), created: true };
}
export const getPhoto = (id) => q('SELECT * FROM photos WHERE id=?').get(id);
export const photosOf = (eventId) => q('SELECT * FROM photos WHERE event_id=?').all(eventId);
export const deletePhotoRow = (id) => q('DELETE FROM photos WHERE id=?').run(id);
export const setPhotoHidden = (id, hidden) => q('UPDATE photos SET hidden=? WHERE id=?').run(hidden ? 1 : 0, id);
export const addPhotoThumb = (id, bit) => q('UPDATE photos SET thumbs=thumbs|? WHERE id=?').run(bit, id);
export const missingThumbs = (eventId, limit = 200) =>
  q('SELECT * FROM photos WHERE event_id=? AND thumbs<>3 ORDER BY seq DESC LIMIT ?').all(eventId, limit);

// Newest first. `before` pages backwards; `after` returns photos added since a live cursor.
export function galleryPhotos(eventId, { before, after, limit = 120, includeHidden = false } = {}) {
  const hid = includeHidden ? '' : 'AND hidden=0';
  if (after != null) return q(`SELECT * FROM photos WHERE event_id=? AND seq>? ${hid} ORDER BY seq DESC LIMIT ?`).all(eventId, after, limit);
  if (before != null) return q(`SELECT * FROM photos WHERE event_id=? AND seq<? ${hid} ORDER BY seq DESC LIMIT ?`).all(eventId, before, limit);
  return q(`SELECT * FROM photos WHERE event_id=? ${hid} ORDER BY seq DESC LIMIT ?`).all(eventId, limit);
}
export const randomPhotos = (eventId, n) =>
  q('SELECT * FROM photos WHERE event_id=? AND hidden=0 AND width>0 ORDER BY RANDOM() LIMIT ?').all(eventId, n);

// ---------- chunked uploads ----------
export function createUpload(u) {
  const t = now();
  q(`INSERT INTO uploads(id,event_id,user_id,source,slot,rel_path,size,received,thumbs,client_ip,created_at,updated_at)
     VALUES(?,?,?,?,?,?,?,0,0,?,?,?)`).run(u.id, u.eventId, u.userId ?? null, u.source, u.slot ?? null, u.relPath, u.size, u.clientIp || '', t, t);
  return getUpload(u.id);
}
export const getUpload = (id) => q('SELECT * FROM uploads WHERE id=?').get(id);
export const setUploadReceived = (id, received) => q('UPDATE uploads SET received=?, updated_at=? WHERE id=?').run(received, now(), id);
export const addUploadThumb = (id, bit) => q('UPDATE uploads SET thumbs=thumbs|?, updated_at=? WHERE id=?').run(bit, now(), id);
export const deleteUpload = (id) => q('DELETE FROM uploads WHERE id=?').run(id);
export const staleUploads = (olderThanMs) => q('SELECT * FROM uploads WHERE updated_at<?').all(now() - olderThanMs);
