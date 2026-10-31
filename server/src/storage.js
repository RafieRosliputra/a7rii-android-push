// Event folders on disk (the source of truth) + thumbnails cache + periodic scanner.
//   PHOTOS_DIR/EVENTS/<YYYYMMDD Event name>/{.livephoto.json, Photographer N/, public upload/}
//   DATA_DIR/thumbs/<event id>/<photo id>-480.webp|-1600.webp
import fs from 'node:fs';
import path from 'node:path';
import { config, EVENTS_DIR, META_FILE, PUBLIC_UPLOAD, STORAGE_MARKER, slotFolder } from './config.js';
import * as db from './db.js';
import { imageSizeOfFile } from './imageinfo.js';
import { shortToken, uuid } from './http.js';
import { publish } from './live.js';

export const IMAGE_RE = /\.(webp|jpe?g)$/i;
export const THUMB_SIZES = { 480: 1, 1600: 2 };

// ---------- storage health ----------
export function storageOnline() {
  try {
    if (!fs.statSync(EVENTS_DIR()).isDirectory()) return false;
    if (config.requireStorageMarker) return fs.existsSync(path.join(config.photosDir, STORAGE_MARKER));
    return true;
  } catch { return false; }
}
export function ensureStorage() {
  if (config.requireStorageMarker) return storageOnline();
  fs.mkdirSync(EVENTS_DIR(), { recursive: true });
  return true;
}

// ---------- names ----------
export const cleanTitle = (t) => String(t || '')
  .normalize('NFC').replace(/[\u0000-\u001f\u007f/\\:*?"<>|]+/g, ' ').replace(/\s+/g, ' ').trim().replace(/^\.+/, '').slice(0, 80);
export const isDate8 = (d) => /^\d{8}$/.test(d) && !Number.isNaN(Date.parse(`${d.slice(0, 4)}-${d.slice(4, 6)}-${d.slice(6)}`));
export function parseFolderName(name) {
  const m = /^(\d{8})\s+(.+)$/.exec(name);
  return m && isDate8(m[1]) ? { date: m[1], title: m[2] } : { date: '', title: name };
}
export function cleanFileName(name, fallbackExt = '.webp') {
  let n = String(name || '').normalize('NFC').replace(/[\u0000-\u001f\u007f/\\:*?"<>|]+/g, '_').replace(/^\.+/, '').trim().slice(0, 120);
  if (!n) n = `photo-${Date.now()}`;
  if (!IMAGE_RE.test(n)) n = n.replace(/\.[^.]*$/, '') + fallbackExt;
  return n;
}

// ---------- paths ----------
function inside(base, p) {
  const r = path.resolve(base, p);
  if (r !== base && !r.startsWith(base + path.sep)) throw Object.assign(new Error('Bad path'), { status: 400 });
  return r;
}
export const eventDir = (ev) => inside(EVENTS_DIR(), ev.folder_name);
export const photoFile = (ev, photo) => inside(eventDir(ev), photo.rel_path);
export const thumbDir = (eventId) => path.join(config.dataDir, 'thumbs', eventId);
export const thumbFile = (eventId, photoId, size) => path.join(thumbDir(eventId), `${photoId}-${size}.img`);
export const sourceFolder = (source, slot) => (source === 'public' ? PUBLIC_UPLOAD : slotFolder(slot));

// Choose a free file name inside the event's slot folder, e.g. DSC01234.webp, DSC01234-2.webp
export function uniqueRelPath(ev, folder, name) {
  const dir = path.join(eventDir(ev), folder);
  fs.mkdirSync(dir, { recursive: true });
  const ext = path.extname(name), base = name.slice(0, name.length - ext.length);
  let rel = path.posix.join(folder, name), i = 2;
  const taken = (r) => fs.existsSync(path.join(eventDir(ev), r)) || db.photosOf(ev.id).some((p) => p.rel_path === r)
    || fs.existsSync(partFile(ev, r));
  while (taken(rel)) rel = path.posix.join(folder, `${base}-${i++}${ext}`);
  return rel;
}
// Uploads in progress live next to their final name as a hidden ".name.part" file (ignored by the scanner).
export function partFile(ev, rel) {
  const dir = path.posix.dirname(rel), base = path.posix.basename(rel);
  return inside(eventDir(ev), path.posix.join(dir, `.${base}.part`));
}

// ---------- .livephoto.json (metadata travels with the folder) ----------
export function readMeta(dir) {
  try { return JSON.parse(fs.readFileSync(path.join(dir, META_FILE), 'utf8')); } catch { return null; }
}
export function writeMeta(ev) {
  const owner = ev.owner_id ? db.getUser(ev.owner_id) : null;
  const meta = {
    id: ev.id, owner: owner?.email || null, token: ev.public_token,
    public_upload: !!ev.public_upload_on, created: new Date(ev.created_at).toISOString(),
    members: db.listMembers(ev.id).map((m) => ({ email: m.email, slot: m.slot })),
  };
  const file = path.join(eventDir(ev), META_FILE);
  fs.writeFileSync(file + '.tmp', JSON.stringify(meta, null, 2));
  fs.renameSync(file + '.tmp', file);
}

// ---------- events ----------
export function createEvent({ date, title, owner }) {
  if (!storageOnline()) throw Object.assign(new Error('Storage offline, try again shortly'), { status: 503 });
  title = cleanTitle(title);
  if (!isDate8(date)) throw Object.assign(new Error('Date must be YYYYMMDD'), { status: 400 });
  if (!title) throw Object.assign(new Error('Event name is required'), { status: 400 });
  let folder = `${date} ${title}`, n = 2;
  while (fs.existsSync(path.join(EVENTS_DIR(), folder))) folder = `${date} ${title} (${n++})`;
  fs.mkdirSync(path.join(EVENTS_DIR(), folder, PUBLIC_UPLOAD), { recursive: true });
  const ev = db.insertEvent({ id: uuid(), folderName: folder, date, title: folder.slice(9), ownerId: owner.id, token: shortToken() });
  db.setMember(ev.id, owner.id, 1);
  writeMeta(ev);
  return db.getEvent(ev.id);
}

// Deleting an event deletes the folder for good (no recycle bin, Rafi's choice #50).
export function deleteEvent(ev) {
  if (!storageOnline()) throw Object.assign(new Error('Storage offline, try again shortly'), { status: 503 });
  fs.rmSync(eventDir(ev), { recursive: true, force: true });
  fs.rmSync(thumbDir(ev.id), { recursive: true, force: true });
  db.markEventDeleted(ev.id);
  publish(ev.id, 'gone', {});
}

export function deletePhoto(ev, photo) {
  fs.rmSync(photoFile(ev, photo), { force: true });
  for (const s of Object.keys(THUMB_SIZES)) fs.rmSync(thumbFile(ev.id, photo.id, s), { force: true });
  db.deletePhotoRow(photo.id);
  publish(ev.id, 'remove', { id: photo.id });
}

function classify(rel) {
  const top = rel.split('/')[0];
  const m = /^Photographer (\d+)$/i.exec(top);
  if (m) return { source: 'photographer', slot: Number(m[1]) };
  if (top.toLowerCase() === PUBLIC_UPLOAD) return { source: 'public', slot: null };
  return { source: 'other', slot: null };
}

function listImages(dir) {
  const out = [];
  const walk = (d, rel, depth) => {
    let ents; try { ents = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
    for (const e of ents) {
      if (e.name.startsWith('.') || e.name.startsWith('@')) continue; // .part, .livephoto.json, @eaDir
      const r = rel ? `${rel}/${e.name}` : e.name;
      if (e.isDirectory() && depth < 2) walk(path.join(d, e.name), r, depth + 1);
      else if (e.isFile() && IMAGE_RE.test(e.name)) out.push(r);
    }
  };
  walk(dir, '', 0);
  return out;
}

// Bring one event's photo rows in line with its folder. Returns photos that are new.
export function scanEventPhotos(ev) {
  const dir = eventDir(ev);
  const known = new Map(db.photosOf(ev.id).map((p) => [p.rel_path, p]));
  const added = [];
  for (const rel of listImages(dir)) {
    const file = path.join(dir, rel);
    let st; try { st = fs.statSync(file); } catch { continue; }
    const old = known.get(rel);
    known.delete(rel);
    if (old && old.bytes === st.size && old.mtime === Math.floor(st.mtimeMs)) continue;
    if (!old && Date.now() - st.mtimeMs < 5000) continue; // still being copied over SMB; next scan
    let size = { width: 0, height: 0 };
    try { size = imageSizeOfFile(file); } catch { /* unreadable: keep 0x0 */ }
    const { photo, created } = db.upsertPhoto({ id: uuid(), eventId: ev.id, relPath: rel, ...classify(rel),
      width: size.width, height: size.height, bytes: st.size, mtime: Math.floor(st.mtimeMs) });
    if (created) added.push(photo);
  }
  for (const gone of known.values()) {
    for (const s of Object.keys(THUMB_SIZES)) fs.rmSync(thumbFile(ev.id, gone.id, s), { force: true });
    db.deletePhotoRow(gone.id);
    publish(ev.id, 'remove', { id: gone.id });
  }
  for (const p of added) publish(ev.id, 'photo', p);
  return added;
}

// Full reconcile: folders <-> events, then photos. Safe to run any time.
let scanning = false;
export function scanAll() {
  if (scanning || !storageOnline()) return { skipped: true };
  scanning = true;
  const stats = { events: 0, created: 0, removed: 0, photos: 0 };
  try {
    const seen = new Set();
    let folders = [];
    try { folders = fs.readdirSync(EVENTS_DIR(), { withFileTypes: true }).filter((d) => d.isDirectory() && !d.name.startsWith('.') && !d.name.startsWith('@')).map((d) => d.name); } catch { /* none */ }
    for (const folder of folders) {
      const dir = path.join(EVENTS_DIR(), folder);
      const meta = readMeta(dir);
      const { date, title } = parseFolderName(folder);
      let ev = meta?.id ? db.getEventAny(meta.id) : db.getEventByFolder(folder);
      if (ev && seen.has(ev.id)) ev = null; // folder copied over SMB with the same .livephoto.json
      if (ev) {
        if (ev.folder_name !== folder || ev.deleted_at) {
          db.insertEvent({ id: ev.id, folderName: folder, date, title, ownerId: ev.owner_id, token: ev.public_token, createdAt: ev.created_at });
        }
        ev = db.getEvent(ev.id);
      } else {
        // New folder (made over SMB, or the DB was rebuilt): adopt it, using .livephoto.json when present.
        const owner = (meta?.owner && db.getUserByEmail(meta.owner)) || db.getUserByEmail(config.adminEmail);
        const id = meta?.id && !seen.has(meta.id) ? meta.id : uuid();
        let token = meta?.token && !db.getEventByToken(meta.token) ? meta.token : shortToken();
        ev = db.insertEvent({ id, folderName: folder, date, title, ownerId: owner?.id, token,
          publicUploadOn: meta ? meta.public_upload !== false : true,
          createdAt: meta?.created ? Date.parse(meta.created) || Date.now() : Date.now() });
        for (const m of meta?.members || []) {
          const u = db.getUserByEmail(m.email);
          if (u) try { db.setMember(id, u.id, m.slot); } catch { /* slot clash */ }
        }
        try { writeMeta(ev); } catch { /* read-only share: fine */ }
        stats.created++;
      }
      seen.add(ev.id);
      stats.events++;
      stats.photos += scanEventPhotos(ev).length;
    }
    for (const ev of db.liveEvents()) {
      if (!seen.has(ev.id)) { db.markEventDeleted(ev.id); fs.rmSync(thumbDir(ev.id), { recursive: true, force: true }); publish(ev.id, 'gone', {}); stats.removed++; }
    }
    cleanupStaleUploads();
  } finally { scanning = false; }
  return stats;
}

function cleanupStaleUploads() {
  for (const u of db.staleUploads(48 * 3600e3)) {
    const ev = db.getEventAny(u.event_id);
    if (ev && !ev.deleted_at) fs.rmSync(partFile(ev, u.rel_path), { force: true });
    fs.rmSync(path.join(config.dataDir, 'uploads', u.id), { recursive: true, force: true });
    db.deleteUpload(u.id);
  }
}

let timer;
export function startScanner() {
  ensureStorage();
  const run = () => { try { scanAll(); } catch (e) { console.error('[scan]', e.message); } };
  run();
  timer = setInterval(run, config.scanIntervalSec * 1000);
  timer.unref();
}
export const stopScanner = () => clearInterval(timer);
