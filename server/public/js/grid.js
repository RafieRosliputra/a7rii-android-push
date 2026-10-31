// Justified-rows photo grid (Google Photos style): portrait and landscape side by side,
// rows scaled to fill the width exactly. Sizes come from the server, so nothing jumps.
import { esc, icon } from './common.js';

export class PhotoGrid {
  constructor(el, { urlFor, filter = () => true, onTile, gap = 4 } = {}) {
    this.el = el; this.urlFor = urlFor; this.filter = filter; this.onTile = onTile; this.gap = gap;
    this.photos = []; this.fresh = new Set(); this.pending = []; this.flushTimer = null;
    this.io = new IntersectionObserver((ents) => {
      for (const e of ents) if (e.isIntersecting) { const img = e.target; img.src = img.dataset.src; this.io.unobserve(img); }
    }, { rootMargin: '800px 0px' });
    let w = el.clientWidth;
    new ResizeObserver(() => { if (Math.abs(el.clientWidth - w) > 2) { w = el.clientWidth; this.render(); } }).observe(el);
    el.addEventListener('click', (e) => {
      const t = e.target.closest('.jtile');
      if (t) this.onTile?.(this.visible().find((p) => p.id === t.dataset.id), this.visible());
    });
  }
  targetHeight() { const w = this.el.clientWidth; return w < 500 ? 130 : w < 900 ? 180 : 240; }
  visible() { return this.photos.filter(this.filter); }

  set(photos) { this.photos = photos.slice(); this.render(); }
  append(photos) { const ids = new Set(this.photos.map((p) => p.id)); this.photos.push(...photos.filter((p) => !ids.has(p.id))); this.render(); }
  // New photos are collected and shown together once a second, so a burst doesn't shake the layout.
  addLive(p) {
    if (this.photos.some((x) => x.id === p.id) || this.pending.some((x) => x.id === p.id)) return;
    this.pending.push(p);
    if (!this.flushTimer) this.flushTimer = setTimeout(() => {
      this.flushTimer = null;
      const add = this.pending.splice(0).sort((a, b) => b.seq - a.seq);
      add.forEach((x) => this.fresh.add(x.id));
      this.photos = [...add, ...this.photos].sort((a, b) => b.seq - a.seq);
      this.render();
      setTimeout(() => { add.forEach((x) => this.fresh.delete(x.id)); this.el.querySelectorAll('.tag').forEach((t) => { if (!this.fresh.has(t.parentElement.dataset.id)) t.remove(); }); }, 30000);
    }, 1000);
  }
  remove(id) { const n = this.photos.length; this.photos = this.photos.filter((p) => p.id !== id); if (n !== this.photos.length) this.render(); }
  update(p) { const i = this.photos.findIndex((x) => x.id === p.id); if (i >= 0) { this.photos[i] = { ...this.photos[i], ...p }; this.render(); } }
  maxSeq() { return this.photos.reduce((m, p) => Math.max(m, p.seq), 0); }
  minSeq() { return this.photos.reduce((m, p) => Math.min(m, p.seq), Infinity); }

  render() {
    const W = this.el.clientWidth;
    if (!W) return;
    const H = this.targetHeight(), g = this.gap;
    const list = this.visible();
    const rows = []; let row = [], sum = 0;
    for (const p of list) {
      const ar = p.w && p.h ? Math.min(4, Math.max(0.25, p.w / p.h)) : 1.5;
      row.push([p, ar]); sum += ar;
      if (sum * H + g * (row.length - 1) >= W) { rows.push([row, (W - g * (row.length - 1)) / sum]); row = []; sum = 0; }
    }
    if (row.length) rows.push([row, Math.min(H, (W - g * (row.length - 1)) / sum)]);
    const html = rows.map(([r, h]) => `<div class="jrow" style="height:${h.toFixed(1)}px">${r.map(([p, ar]) =>
      `<div class="jtile${p.hidden ? ' is-hidden' : ''}" data-id="${p.id}" style="width:${(ar * h).toFixed(1)}px">` +
      `<img alt="" data-src="${esc(this.urlFor(p, ar * h > 600 ? 1600 : 480))}">` +
      (this.fresh.has(p.id) ? '<span class="tag">NEW</span>' : '') + (p.hidden ? '<span class="hid">Hidden</span>' : '') + '</div>').join('')}</div>`).join('');
    this.el.innerHTML = html;
    this.el.querySelectorAll('img').forEach((img) => {
      img.addEventListener('load', () => img.classList.add('loaded'), { once: true });
      this.io.observe(img);
    });
  }
}

// Full-screen viewer with swipe, arrows and download.
export function openLightbox(list, start, { urlFor, downloadUrl, actions = () => '' }) {
  let i = Math.max(0, list.findIndex((p) => p.id === start.id));
  const el = document.createElement('div');
  el.className = 'lightbox';
  el.innerHTML = `<div class="lb-img"></div>
    <button class="lb-nav lb-prev" aria-label="Previous">‹</button><button class="lb-nav lb-next" aria-label="Next">›</button>
    <div class="lb-bar"><span class="lb-count"></span><span class="lb-actions" style="display:flex;gap:8px;flex-wrap:wrap"></span></div>`;
  const show = () => {
    const p = list[i];
    el.querySelector('.lb-img').innerHTML = `<img alt="" src="${esc(urlFor(p, 1600))}">`;
    el.querySelector('.lb-count').textContent = `${i + 1} / ${list.length}`;
    el.querySelector('.lb-actions').innerHTML = actions(p) +
      `<a class="btn small light" href="${esc(downloadUrl(p))}">${icon.dl}Download</a><button class="btn small light" data-x>Close</button>`;
    const n = list[i + 1]; if (n) new Image().src = urlFor(n, 1600);
  };
  const go = (d) => { i = (i + d + list.length) % list.length; show(); };
  const close = () => { el.remove(); document.removeEventListener('keydown', key); };
  const key = (e) => { if (e.key === 'Escape') close(); if (e.key === 'ArrowRight') go(1); if (e.key === 'ArrowLeft') go(-1); };
  el.querySelector('.lb-prev').onclick = () => go(-1);
  el.querySelector('.lb-next').onclick = () => go(1);
  el.addEventListener('click', (e) => { if (e.target.closest('[data-x]') || e.target.classList.contains('lb-img')) close(); });
  let x0 = null;
  el.addEventListener('touchstart', (e) => { x0 = e.touches[0].clientX; }, { passive: true });
  el.addEventListener('touchend', (e) => { if (x0 == null) return; const dx = e.changedTouches[0].clientX - x0; if (Math.abs(dx) > 50) go(dx < 0 ? 1 : -1); x0 = null; });
  document.addEventListener('keydown', key);
  document.body.appendChild(el);
  show();
  return { close, refresh: show, current: () => list[i] };
}

// Live updates: SSE first, polling every 5 s as a fallback (30 s while the tab is hidden).
// getCursor() returns the newest photo seq the page already has.
export function liveFeed({ streamUrl, pollUrl, getCursor, onPhoto, onRemove, onGone, onStatus, onClosed, onSettings }) {
  let es = null, pollTimer, alive = true;
  const status = (s) => onStatus?.(s);
  const catchUp = async () => {
    const r = await fetch(`${pollUrl}?after=${getCursor()}`);
    if (r.status === 404) { onGone?.(); stop(); return; }
    const d = await r.json();
    d.photos.forEach(onPhoto);
  };
  const poll = async () => {
    try { await catchUp(); status('poll'); } catch { status('offline'); }
    if (alive && !es) pollTimer = setTimeout(poll, document.hidden ? 30000 : 5000);
  };
  const connect = () => {
    if (!window.EventSource) return poll();
    es = new EventSource(streamUrl);
    es.onopen = () => { status('live'); clearTimeout(pollTimer); catchUp().catch(() => {}); };
    es.addEventListener('photo', (e) => { onPhoto(JSON.parse(e.data)); status('live'); });
    es.addEventListener('remove', (e) => onRemove?.(JSON.parse(e.data).id));
    es.addEventListener('gone', () => { onGone?.(); stop(); });
    es.addEventListener('closed', () => onClosed?.());
    es.addEventListener('settings', (e) => onSettings?.(JSON.parse(e.data)));
    es.onerror = () => {
      status('reconnecting');
      if (es && es.readyState === EventSource.CLOSED) { es = null; clearTimeout(pollTimer); pollTimer = setTimeout(() => { poll(); setTimeout(() => alive && !es && connect(), 30000); }, 5000); }
    };
  };
  const stop = () => { alive = false; es?.close(); es = null; clearTimeout(pollTimer); };
  document.addEventListener('visibilitychange', () => { if (!document.hidden && alive) catchUp().catch(() => {}); });
  connect();
  return { stop };
}
