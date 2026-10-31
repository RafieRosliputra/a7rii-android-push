// Chunked, resumable upload (same protocol the Android app uses):
//   POST start -> {id, chunk}; PUT /api/uploads/:id?offset=N; PUT thumbs; POST complete.
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function req(method, url, body, headers = {}) {
  const r = await fetch(url, { method, body, headers, credentials: 'same-origin' });
  let data = {};
  try { data = await r.json(); } catch { /* empty */ }
  return { status: r.status, ok: r.ok, data };
}

// Retry network errors / 5xx with backoff. 4xx errors (except 409/429) are final.
async function withRetry(fn, tries = 8) {
  let wait = 1000;
  for (let i = 0; ; i++) {
    let res;
    try { res = await fn(); } catch (e) { res = { status: 0, data: { error: 'Network error' } }; }
    if (res.ok || res.status === 409) return res;
    const retryable = res.status === 0 || res.status >= 500 || res.status === 429;
    if (!retryable || i >= tries) {
      const e = new Error(res.data.error || `Upload failed (${res.status || 'network'})`);
      e.status = res.status; throw e;
    }
    await sleep(wait); wait = Math.min(wait * 2, 15000);
  }
}

export async function uploadPhoto({ startUrl, startBody, blob, thumbs = {}, onProgress = () => {} }) {
  const start = await withRetry(() => req('POST', startUrl, JSON.stringify({ ...startBody, size: blob.size }), { 'content-type': 'application/json' }));
  const { id, chunk } = start.data;
  let offset = 0;
  while (offset < blob.size) {
    const part = blob.slice(offset, Math.min(blob.size, offset + chunk));
    const r = await withRetry(() => req('PUT', `/api/uploads/${id}?offset=${offset}`, part));
    offset = r.data.received;
    onProgress(offset / blob.size);
  }
  for (const [size, t] of Object.entries(thumbs)) {
    if (t) await withRetry(() => req('PUT', `/api/uploads/${id}/thumb/${size}`, t));
  }
  const done = await withRetry(() => req('POST', `/api/uploads/${id}/complete`));
  return done.data.photo;
}
