// Who is calling:
//  - Admin: Cloudflare Access in front of /admin and /api/admin. We verify the Access JWT
//    (RS256, team certs, audience tag) and check the email against ADMIN_EMAILS.
//  - Guest / photographer / organiser (client): the 6-digit code for one event. A guest or
//    photographer session works while the event is live (8 h window, or until the admin closes it);
//    an organiser session works until 7 days after the event ends.
import crypto from 'node:crypto';
import { config } from './config.js';
import * as db from './db.js';
import { HttpError, cookie, parseCookies, randomToken, sha256 } from './http.js';

export const COOKIES = { guest: 'lp_g', photographer: 'lp_p', client: 'lp_c' };

// ---------------- Cloudflare Access ----------------
let certs = { at: 0, keys: new Map() };
async function accessKeys(force = false) {
  if (!force && Date.now() - certs.at < 3600e3 && certs.keys.size) return certs.keys;
  const url = config.accessCertsUrl || `https://${config.accessTeam}/cdn-cgi/access/certs`;
  const r = await fetch(url, { signal: AbortSignal.timeout(8000) });
  if (!r.ok) throw new Error(`certs ${r.status}`);
  const j = await r.json();
  const keys = new Map();
  for (const k of j.keys || []) keys.set(k.kid, crypto.createPublicKey({ key: k, format: 'jwk' }));
  certs = { at: Date.now(), keys };
  return keys;
}
const b64json = (s) => JSON.parse(Buffer.from(s, 'base64url').toString('utf8'));

export async function verifyAccessJwt(token) {
  const parts = String(token || '').split('.');
  if (parts.length !== 3) throw new HttpError(401, 'Admin login required');
  let head, claims;
  try { head = b64json(parts[0]); claims = b64json(parts[1]); } catch { throw new HttpError(401, 'Admin login required'); }
  if (head.alg !== 'RS256') throw new HttpError(401, 'Admin login required');
  let keys = await accessKeys().catch(() => new Map());
  if (!keys.has(head.kid)) keys = await accessKeys(true).catch(() => new Map());
  const key = keys.get(head.kid);
  if (!key) throw new HttpError(401, 'Admin login could not be verified');
  const ok = crypto.verify('RSA-SHA256', Buffer.from(`${parts[0]}.${parts[1]}`), key, Buffer.from(parts[2], 'base64url'));
  if (!ok) throw new HttpError(401, 'Admin login could not be verified');
  const aud = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
  if (!config.accessAud || !aud.includes(config.accessAud)) throw new HttpError(401, 'Admin login is for another application');
  if (claims.iss !== `https://${config.accessTeam}`) throw new HttpError(401, 'Admin login issuer mismatch');
  if (!claims.exp || claims.exp * 1000 < Date.now()) throw new HttpError(401, 'Admin login expired, reload the page');
  const email = String(claims.email || '').toLowerCase();
  if (!config.adminEmails.includes(email)) throw new HttpError(403, 'This account is not an admin');
  return { email };
}

export async function requireAdmin(req) {
  if (config.devAdmin) return { email: config.devAdmin };
  const token = req.headers['cf-access-jwt-assertion'] || parseCookies(req).CF_Authorization;
  return verifyAccessJwt(token);
}

// ---------------- 6-digit codes ----------------
// 3 wrong tries per device/IP, then locked for 30 minutes. The reply says how many tries are left.
const tries = new Map(); // ip -> {n, until}
setInterval(() => { const t = Date.now(); for (const [k, v] of tries) if (v.until && v.until < t) tries.delete(k); }, 60e3).unref();

function lockInfo(ip) {
  const v = tries.get(ip);
  if (v?.until && v.until > Date.now()) return Math.ceil((v.until - Date.now()) / 60e3);
  return 0;
}
function wrongCode(ip) {
  const v = tries.get(ip) || { n: 0, until: 0 };
  v.n++;
  if (v.n >= config.codeTries) { v.until = Date.now() + config.codeLockMin * 60e3; v.n = 0; }
  tries.set(ip, v);
  const left = v.until > Date.now() ? 0 : config.codeTries - v.n;
  const msg = left ? `Wrong code. ${left} ${left === 1 ? 'try' : 'tries'} left.` : `Too many wrong codes. Try again in ${config.codeLockMin} minutes.`;
  return new HttpError(left ? 401 : 429, msg, { triesLeft: left });
}

export function cleanName(s, max = 30) {
  return String(s || '').normalize('NFC').replace(/[\u0000-\u001f\u007f<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, max);
}

/** Exchange a code for a session. `want` limits which role the form accepts ('guest' | 'photographer' | 'client' | undefined). */
export function loginWithCode({ code, name, want, ip }) {
  const mins = lockInfo(ip);
  if (mins) throw new HttpError(429, `Too many wrong codes. Try again in ${mins} minute${mins === 1 ? '' : 's'}.`, { triesLeft: 0 });
  code = String(code || '').replace(/\D/g, '');
  if (code.length !== 6) throw new HttpError(400, 'Enter the 6-digit code');
  const ev = db.eventByCode(code);
  if (!ev) throw wrongCode(ip);
  const role = ev.code_role;
  if (want && want !== role) {
    if (want === 'guest' && role === 'photographer') throw new HttpError(400, 'This is a photographer code. Tap "Sign in as photographer".');
    if (want === 'photographer' && role === 'guest') throw new HttpError(400, 'This is the guest code. Ask the organiser for the photographer code.');
    throw wrongCode(ip);
  }
  if (role === 'client') {
    if (!db.clientWindowOpen(ev)) throw new HttpError(403, 'This organiser code has expired.');
  } else if (!db.isLive(ev)) {
    const st = db.status(ev);
    throw new HttpError(403, st === 'scheduled' ? `This event opens at ${new Date(ev.starts_at).toLocaleTimeString('en-MY', { timeZone: 'Asia/Kuala_Lumpur', hour: 'numeric', minute: '2-digit' })}.` : 'This event is not live right now.');
  }
  tries.delete(ip);
  let slot = null;
  if (role === 'photographer') {
    name = cleanName(name);
    if (!name) throw new HttpError(400, 'Type your name');
    slot = db.slotForName(ev.id, name);
  }
  const token = randomToken();
  const s = db.createSession({ tokenHash: sha256(token), role, eventId: ev.id, name: role === 'photographer' ? name : '', slot });
  return { token, role, session: s, event: db.getEvent(ev.id) };
}

export const sessionCookie = (role, token, ev) => {
  const end = role === 'client' ? (ev.closed_at || ev.ends_at || Date.now()) + 7 * 86400e3 : ev.ends_at;
  return cookie(COOKIES[role], token, { maxAge: Math.max(60, (end - Date.now()) / 1000), secure: config.cookieSecure });
};
export const clearCookie = (role) => cookie(COOKIES[role], '', { maxAge: 0, secure: config.cookieSecure });

// A valid session of `role` (optionally for one event). Bearer tokens (the app) count for any role.
export function sessionFor(req, role, eventId) {
  const h = String(req.headers.authorization || '');
  const tokens = [];
  if (h.startsWith('Bearer ')) tokens.push(h.slice(7).trim());
  const c = parseCookies(req)[COOKIES[role]];
  if (c) tokens.push(c);
  for (const t of tokens) {
    const s = db.sessionByHash(sha256(t));
    if (!s || s.role !== role || (eventId && s.event_id !== eventId)) continue;
    const ev = db.getEvent(s.event_id);
    if (!ev) continue;
    if (role === 'client' ? !db.clientWindowOpen(ev) : !db.isLive(ev)) continue;
    return { session: s, event: ev, token: t };
  }
  return null;
}
export function requireSession(req, role, eventId) {
  const s = sessionFor(req, role, eventId);
  if (!s) throw new HttpError(401, role === 'client' ? 'Organiser login required' : 'This event is not live, or your code has expired. Enter the code again.');
  return s;
}
export function logout(req, role) {
  const s = sessionFor(req, role);
  if (s) db.revokeSession(sha256(s.token));
}
