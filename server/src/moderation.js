// Guest photo moderation. Every guest upload starts as "pending" and is invisible until checked:
//   ok    -> approved (appears in the gallery and on the big screen)
//   flag  -> flagged  (waits for the admin / organiser: Allow or Remove)
//   block -> blocked  (clear NSFW: hidden, kept for the admin)
// The AI runs in a separate app on m10 (moderator/, Python). If it does not answer after a few
// tries the photo is flagged "moderator offline" so a person decides; nothing is published unchecked.
import path from 'node:path';
import { config } from './config.js';
import * as db from './db.js';
import { publishPhoto } from './live.js';
import { eventDir } from './storage.js';

// Names guests type (max 20 chars). Rude words (BM + EN) and party names are flagged, not rejected.
const WORDS = [
  // English
  'fuck', 'fck', 'fuk', 'shit', 'bitch', 'bastard', 'dick', 'pussy', 'cunt', 'asshole', 'slut', 'whore', 'porn', 'sex', 'nude',
  // Bahasa Melayu
  'babi', 'bodoh', 'bodo', 'sial', 'celaka', 'pukimak', 'puki', 'pantat', 'butoh', 'konek', 'lancau', 'pundek', 'jubur', 'haram jadah',
  'anjing', 'bangsat', 'sundal', 'lahanat', 'kimak', 'keparat', 'setan', 'bengap', 'bahalol', 'barua',
  // politics
  'umno', 'pas', 'pkr', 'dap', 'bersatu', 'amanah', 'muda', 'perikatan', 'pakatan', 'barisan', 'gerakan', 'mca', 'mic', 'reformasi',
];
const WORD_RE = new RegExp(`(^|[^a-z])(${WORDS.map((w) => w.replace(/ /g, '\\s*')).join('|')})([^a-z]|$)`, 'i');
export const nameIsSuspicious = (name) => !!name && WORD_RE.test(name.toLowerCase().replace(/[0@]/g, 'o').replace(/[1!]/g, 'i').replace(/3/g, 'e').replace(/\$/g, 's'));

async function askModerator(photo, ev) {
  const rel = path.relative(config.photosDir, path.join(eventDir(ev), photo.rel_path));
  const r = await fetch(`${config.moderatorUrl}/check`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ path: rel, id: photo.id }),
    signal: AbortSignal.timeout(60000),
  });
  if (!r.ok) throw new Error(`moderator ${r.status}`);
  return r.json(); // {verdict: 'ok'|'flag'|'block', labels: [...], scores: {...}}
}

const running = new Set();
export async function checkPhoto(photo) {
  if (running.has(photo.id)) return;
  running.add(photo.id);
  try {
    const ev = db.getEvent(photo.event_id);
    if (!ev) return;
    const nameFlag = nameIsSuspicious(photo.uploader) ? ['name'] : [];
    let verdict = 'ok', labels = [];
    if (config.moderatorUrl) {
      try {
        const res = await askModerator(photo, ev);
        verdict = ['ok', 'flag', 'block'].includes(res.verdict) ? res.verdict : 'flag';
        labels = Array.isArray(res.labels) ? res.labels.slice(0, 8).map(String) : [];
      } catch (e) {
        db.bumpModTries(photo.id);
        const p = db.getPhoto(photo.id);
        if (p && p.mod_tries >= 3) publishPhoto(db.setPhotoStatus(p.id, 'flagged', ['moderator offline', ...nameFlag]));
        else console.error('[moderator]', e.message);
        return;
      }
    }
    const fresh = db.getPhoto(photo.id);
    if (!fresh || fresh.status !== 'pending') return; // a person decided meanwhile
    const flags = [...labels, ...nameFlag];
    const st = verdict === 'block' ? 'blocked' : verdict === 'flag' || nameFlag.length ? 'flagged' : 'approved';
    publishPhoto(db.setPhotoStatus(photo.id, st, flags));
  } finally {
    running.delete(photo.id);
  }
}

// Retry loop for photos still pending (moderator was down, server restarted...).
let timer;
export function startModeration() {
  const tick = () => { for (const p of db.pendingPhotos(10)) checkPhoto(p); };
  timer = setInterval(tick, 15000);
  timer.unref();
  setTimeout(tick, 2000).unref();
}
export const stopModeration = () => clearInterval(timer);
