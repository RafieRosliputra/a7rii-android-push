// JS Live Photo server: API + website + live updates. Node 24, no npm packages.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { config } from './config.js';
import * as db from './db.js';
import { router } from './api.js';
import { HttpError, sendJson } from './http.js';
import { startScanner, stopScanner } from './storage.js';
import { startModeration, stopModeration } from './moderation.js';

const PUBLIC_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../public');
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.webp': 'image/webp', '.jpg': 'image/jpeg', '.wasm': 'application/wasm', '.json': 'application/json', '.txt': 'text/plain',
};
// Clean URLs for the pages.
const PAGES = [
  [/^\/$/, 'index.html'],
  [/^\/c\/\d{6}\/?$/, 'index.html'],        // QR on the big screen: code filled in
  [/^\/admin\/?$/, 'admin.html'],
  [/^\/panel\/?$/, 'admin.html'],              // organiser panel (client code), same page, fewer powers
  [/^\/shoot\/?$/, 'shoot.html'],
  [/^\/show\/[0-9a-f-]{36}\/?$/, 'show.html'],
  [/^\/e\/[a-z0-9]{6,16}\/?$/, 'gallery.html'],
];

function serveStatic(req, res, pathname) {
  let rel = null;
  for (const [re, file] of PAGES) if (re.test(pathname)) { rel = file; break; }
  if (!rel) rel = pathname.replace(/^\/+/, '');
  const file = path.resolve(PUBLIC_DIR, rel);
  if (!file.startsWith(PUBLIC_DIR + path.sep)) return false;
  let st; try { st = fs.statSync(file); } catch { return false; }
  if (!st.isFile()) return false;
  const ext = path.extname(file);
  res.writeHead(200, {
    'content-type': TYPES[ext] || 'application/octet-stream',
    'content-length': st.size,
    // HTML always fresh; versioned assets (vendor/wasm) can be cached long.
    'cache-control': ext === '.html' ? 'no-cache' : rel.startsWith('vendor/') ? 'public, max-age=604800' : 'public, max-age=300',
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'strict-origin-when-cross-origin',
  });
  if (req.method === 'HEAD') res.end();
  else fs.createReadStream(file).pipe(res);
  return true;
}

export function handler(req, res) {
  const url = new URL(req.url, 'http://x');
  const done = (e) => {
    if (res.headersSent) { res.destroy(); return; }
    const status = e instanceof HttpError ? e.status : e.status || 500;
    if (status >= 500 && !(e instanceof HttpError)) console.error('[error]', req.method, url.pathname, e);
    sendJson(res, status, { error: status >= 500 && !(e instanceof HttpError) ? 'Server error' : e.message, ...(e.extra || {}) });
  };
  try {
    const m = router.match(req.method, url.pathname);
    if (m) {
      const out = m.handler(req, res, { params: m.params, query: url.searchParams });
      if (out && typeof out.catch === 'function') out.catch(done);
      return;
    }
    if ((req.method === 'GET' || req.method === 'HEAD') && serveStatic(req, res, url.pathname)) return;
    if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/auth/')) return sendJson(res, 404, { error: 'Not found' });
    if (serveStatic(req, res, '/404.html')) return;
    res.writeHead(404).end('Not found');
  } catch (e) { done(e); }
}

export function start() {
  db.openDb();
  startScanner();
  startModeration();
  const server = http.createServer(handler);
  server.requestTimeout = 0;           // long SSE streams and slow 4G chunk uploads
  server.headersTimeout = 60000;
  server.keepAliveTimeout = 65000;
  server.listen(config.port, config.host, () => console.log(`JS Live Photo on http://${config.host}:${config.port} (photos: ${config.photosDir})`));
  const stop = () => { stopScanner(); stopModeration(); server.close(); db.closeDb(); process.exit(0); };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
  return server;
}

if (import.meta.url === `file://${process.argv[1]}`) start();
