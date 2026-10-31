// Turn a phone photo into WebP in the browser, keeping its full resolution (Rafi #25),
// plus 480 px and 1600 px thumbnails for the gallery.
// Chrome/Android: native canvas WebP. iPhone Safari cannot encode WebP with <canvas> and
// limits canvas size to ~16.7 MP, so there we read the pixels in strips and use libwebp (WASM).

let webpCanvasOk;
export function canvasWebpSupported() {
  if (webpCanvasOk === undefined) {
    const c = document.createElement('canvas'); c.width = c.height = 2;
    webpCanvasOk = c.toDataURL('image/webp').startsWith('data:image/webp');
  }
  return webpCanvasOk;
}
const isIOS = () => /iP(hone|ad|od)/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const CANVAS_SAFE_PIXELS = 16_000_000;

export async function decode(blob) {
  try { return await createImageBitmap(blob, { imageOrientation: 'from-image' }); }
  catch {
    // Older Safari: fall back to <img>
    const url = URL.createObjectURL(blob);
    try {
      const img = new Image(); img.decoding = 'async'; img.src = url;
      await img.decode();
      return img;
    } catch { throw new Error('This photo format cannot be opened in this browser'); }
    finally { setTimeout(() => URL.revokeObjectURL(url), 1000); }
  }
}
const dims = (src) => ({ w: src.width || src.naturalWidth, h: src.height || src.naturalHeight });

function toBlob(canvas, type, q) {
  return new Promise((res, rej) => canvas.toBlob((b) => (b ? res(b) : rej(new Error('Encoding failed'))), type, q));
}

// Thumbnail: WebP where supported, JPEG otherwise (the server accepts both).
export async function thumbnail(src, maxSide) {
  const { w, h } = dims(src);
  const s = Math.min(1, maxSide / Math.max(w, h));
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(w * s)); c.height = Math.max(1, Math.round(h * s));
  const ctx = c.getContext('2d');
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(src, 0, 0, c.width, c.height);
  const blob = canvasWebpSupported() ? await toBlob(c, 'image/webp', 0.72) : await toBlob(c, 'image/jpeg', 0.82);
  return { blob, canvas: c };
}

let wasmModule;
async function wasmEncoder() {
  if (!wasmModule) wasmModule = import('/vendor/webp/webp_enc.js').then((m) => m.default());
  return wasmModule;
}
const WASM_OPTS = {
  quality: 50, target_size: 0, target_PSNR: 0, method: 4, sns_strength: 50, filter_strength: 60, filter_sharpness: 0,
  filter_type: 1, partitions: 0, segments: 4, pass: 1, show_compressed: 0, preprocessing: 0, autofilter: 0,
  partition_limit: 0, alpha_compression: 1, alpha_filtering: 1, alpha_quality: 100, lossless: 0, exact: 0,
  image_hint: 0, emulate_jpeg_size: 0, thread_level: 0, low_memory: 1, near_lossless: 100, use_delta_palette: 0, use_sharp_yuv: 0,
};

async function encodeWasm(src, quality) {
  const { w, h } = dims(src);
  const rgba = new Uint8ClampedArray(w * h * 4);
  const stripH = Math.max(1, Math.floor(4_000_000 / w));
  const c = document.createElement('canvas');
  c.width = w; c.height = Math.min(stripH, h);
  const ctx = c.getContext('2d', { willReadFrequently: true });
  for (let y = 0; y < h; y += stripH) {
    const sh = Math.min(stripH, h - y);
    ctx.clearRect(0, 0, w, c.height);
    ctx.drawImage(src, 0, y, w, sh, 0, 0, w, sh);
    rgba.set(ctx.getImageData(0, 0, w, sh).data, y * w * 4);
    await new Promise((r) => setTimeout(r)); // keep the page responsive
  }
  c.width = c.height = 1;
  const enc = await wasmEncoder();
  const out = enc.encode(rgba, w, h, { ...WASM_OPTS, quality: Math.round(quality * 100) });
  if (!out) throw new Error('WebP encoding failed');
  return new Blob([out], { type: 'image/webp' });
}

async function encodeCanvas(src, quality) {
  const { w, h } = dims(src);
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  c.getContext('2d').drawImage(src, 0, 0);
  const blob = await toBlob(c, 'image/webp', quality);
  c.width = c.height = 1;
  if (blob.type !== 'image/webp') throw new Error('no-webp');
  return blob;
}

// Full-resolution WebP + thumbnails.
export async function preparePhoto(file, quality = 0.5) {
  const src = await decode(file);
  const { w, h } = dims(src);
  try {
    const t1600 = await thumbnail(src, 1600);
    const t480 = await thumbnail(t1600.canvas, 480);
    let blob;
    const useCanvas = canvasWebpSupported() && !(isIOS() && w * h > CANVAS_SAFE_PIXELS);
    if (useCanvas) {
      try { blob = await encodeCanvas(src, quality); } catch { blob = null; }
    }
    if (!blob) blob = await encodeWasm(src, quality);
    return { blob, width: w, height: h, thumbs: { 480: t480.blob, 1600: t1600.blob } };
  } finally {
    if (src.close) src.close();
  }
}
