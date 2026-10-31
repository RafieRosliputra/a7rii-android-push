// Login: Google ID token or a 6-digit email code (sent by JohorStudio Mail).
// Only emails the admin has added (allowlist, status active) may log in. Sessions last 30 days:
// HttpOnly cookie for the web, bearer token for the Android app.
import crypto from 'node:crypto';
import { config } from './config.js';
import * as db from './db.js';
import { HttpError, cookie, parseCookies, randomToken, sha256, rateLimiter } from './http.js';

export const COOKIE = 'lp_s';
const codeIpLimit = rateLimiter(20, 15 * 60e3);
const codeEmailLimit = rateLimiter(5, 15 * 60e3);
const verifyIpLimit = rateLimiter(40, 15 * 60e3);

// ---------- sessions ----------
export function startSession(user, kind, label = '') {
  const token = randomToken();
  db.createSession({ userId: user.id, tokenHash: sha256(token), kind, label });
  return token;
}
export const sessionCookie = (token) => cookie(COOKIE, token, { maxAge: config.sessionDays * 86400, secure: config.cookieSecure });
export const clearCookie = () => cookie(COOKIE, '', { maxAge: 0, secure: config.cookieSecure });

function tokenOf(req) {
  const h = String(req.headers.authorization || '');
  if (h.startsWith('Bearer ')) return h.slice(7).trim();
  return parseCookies(req)[COOKIE] || null;
}
export function currentUser(req) {
  const t = tokenOf(req);
  return t ? db.sessionUser(sha256(t)) || null : null;
}
export function requireUser(req) {
  const u = currentUser(req);
  if (!u) throw new HttpError(401, 'Please log in');
  return u;
}
export function requireAdmin(req) {
  const u = requireUser(req);
  if (u.role !== 'admin') throw new HttpError(403, 'Admins only');
  return u;
}
export function logout(req) {
  const t = tokenOf(req);
  if (t) db.deleteSession(sha256(t));
}

function allowed(email) {
  const u = db.getUserByEmail(email);
  if (!u || u.status !== 'active') throw new HttpError(403, 'This email is not registered for JohorStudio.com Live. Ask the admin to add you.');
  return u;
}

// ---------- Google ----------
// Verify with Google's tokeninfo endpoint (checks signature + expiry), then check audience.
export async function verifyGoogle(credential, fetchImpl = fetch) {
  if (!config.googleClientId) throw new HttpError(503, 'Google login is not set up yet. Use the email code.');
  if (!credential || typeof credential !== 'string') throw new HttpError(400, 'Missing Google credential');
  let info;
  try {
    const r = await fetchImpl(`https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(credential)}`);
    if (!r.ok) throw new Error(`tokeninfo ${r.status}`);
    info = await r.json();
  } catch (e) {
    throw new HttpError(401, 'Google sign-in could not be verified');
  }
  if (info.aud !== config.googleClientId) throw new HttpError(401, 'Google sign-in is for another app');
  if (!['accounts.google.com', 'https://accounts.google.com'].includes(info.iss)) throw new HttpError(401, 'Invalid Google issuer');
  if (String(info.email_verified) !== 'true') throw new HttpError(401, 'Google email is not verified');
  const user = allowed(info.email);
  db.noteLogin(user.id, { name: info.name || null, googleSub: info.sub });
  return db.getUser(user.id);
}

// ---------- 6-digit email code ----------
const codeHash = (email, code) => sha256(`${db.normEmail(email)}:${code}`);

export async function requestCode(email, ip, sendImpl = sendLoginCode) {
  email = db.normEmail(email);
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new HttpError(400, 'Enter a valid email');
  if (!codeIpLimit(ip) || !codeEmailLimit(email)) throw new HttpError(429, 'Too many codes requested. Wait a few minutes.');
  const user = db.getUserByEmail(email);
  // Same answer whether or not the email is registered, so the list of users can't be probed.
  if (!user || user.status !== 'active') return { sent: true };
  const prev = db.getCode(email);
  if (prev && Date.now() - prev.sent_at < 30e3) throw new HttpError(429, 'A code was just sent. Wait 30 seconds before asking again.');
  const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
  db.putCode(email, codeHash(email, code));
  await sendImpl(user, code);
  return { sent: true };
}

export function verifyCode(email, code, ip) {
  email = db.normEmail(email);
  if (!verifyIpLimit(ip)) throw new HttpError(429, 'Too many attempts. Wait a few minutes.');
  const row = db.getCode(email);
  const bad = new HttpError(401, 'Wrong or expired code');
  if (!row || row.expires_at < Date.now()) throw bad;
  if (row.attempts >= 5) { db.deleteCode(email); throw new HttpError(401, 'Too many wrong tries. Ask for a new code.'); }
  const ok = crypto.timingSafeEqual(Buffer.from(row.code_hash), Buffer.from(codeHash(email, String(code || '').replace(/\D/g, ''))));
  if (!ok) { db.bumpCodeAttempts(email); throw bad; }
  db.deleteCode(email);
  const user = allowed(email);
  db.noteLogin(user.id);
  return db.getUser(user.id);
}

// JohorStudio Mail API (repo johorstudio-mail): template login_code {code, minutes, name}
export async function sendLoginCode(user, code) {
  if (config.devLogCodes) { console.log(`[dev] login code for ${user.email}: ${code}`); return; }
  if (!config.mailKey) throw new HttpError(503, 'Email is not set up yet (MAIL_API_KEY)');
  let r;
  try {
    r = await fetch(`${config.mailApi}/api/v1/send`, {
      method: 'POST',
      headers: { authorization: `Bearer ${config.mailKey}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        to: user.email, template: 'login_code', from: config.mailFrom, from_name: config.mailFromName,
        data: { code, minutes: config.codeMinutes, name: user.name || user.email.split('@')[0] },
      }),
      signal: AbortSignal.timeout(10000),
    });
  } catch (e) {
    console.error('[mail]', e.message);
    throw new HttpError(502, 'Could not send the email right now. Try again.');
  }
  if (!r.ok) {
    console.error('[mail]', r.status, await r.text().catch(() => ''));
    throw new HttpError(502, 'Could not send the email right now. Try again.');
  }
}
