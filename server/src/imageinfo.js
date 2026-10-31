// Read image type and size from the file bytes (no image library needed),
// and strip EXIF/XMP (GPS etc.) from guest WebP files.
import fs from 'node:fs';

export function sniff(buf) {
  if (buf.length >= 12 && buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WEBP') return 'webp';
  if (buf.length >= 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return 'jpeg';
  return null;
}

export function webpSize(buf) {
  let off = 12;
  while (off + 8 <= buf.length) {
    const id = buf.toString('ascii', off, off + 4);
    const len = buf.readUInt32LE(off + 4);
    const d = off + 8;
    if (id === 'VP8X' && d + 10 <= buf.length) {
      return { width: 1 + buf.readUIntLE(d + 4, 3), height: 1 + buf.readUIntLE(d + 7, 3) };
    }
    if (id === 'VP8 ' && d + 10 <= buf.length) {
      return { width: buf.readUInt16LE(d + 6) & 0x3fff, height: buf.readUInt16LE(d + 8) & 0x3fff };
    }
    if (id === 'VP8L' && d + 5 <= buf.length && buf[d] === 0x2f) {
      const b = buf.readUInt32LE(d + 1);
      return { width: (b & 0x3fff) + 1, height: ((b >> 14) & 0x3fff) + 1 };
    }
    off = d + len + (len & 1);
  }
  return null;
}

// JPEG: walk markers to the first SOFn. Also reads EXIF orientation so portrait shots
// report portrait dimensions (cameras store 6000x4000 + "rotate 90").
export function jpegSize(buf) {
  let off = 2, orientation = 1;
  while (off + 4 <= buf.length) {
    if (buf[off] !== 0xff) { off++; continue; }
    const marker = buf[off + 1];
    if (marker === 0xd8 || (marker >= 0xd0 && marker <= 0xd7) || marker === 0x01 || marker === 0xff) { off += 2; continue; }
    const len = buf.readUInt16BE(off + 2);
    if (marker === 0xe1 && buf.toString('ascii', off + 4, off + 8) === 'Exif') orientation = exifOrientation(buf, off + 10) || 1;
    if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker) && off + 9 <= buf.length) {
      const height = buf.readUInt16BE(off + 5), width = buf.readUInt16BE(off + 7);
      return orientation >= 5 ? { width: height, height: width } : { width, height };
    }
    off += 2 + len;
  }
  return null;
}

function exifOrientation(buf, tiff) {
  try {
    const le = buf.toString('ascii', tiff, tiff + 2) === 'II';
    const u16 = (o) => (le ? buf.readUInt16LE(o) : buf.readUInt16BE(o));
    const u32 = (o) => (le ? buf.readUInt32LE(o) : buf.readUInt32BE(o));
    const ifd = tiff + u32(tiff + 4);
    const n = u16(ifd);
    for (let i = 0; i < n; i++) {
      const e = ifd + 2 + i * 12;
      if (u16(e) === 0x0112) return u16(e + 8);
    }
  } catch { /* ignore broken EXIF */ }
  return null;
}

export function imageSize(buf) {
  const type = sniff(buf);
  const size = type === 'webp' ? webpSize(buf) : type === 'jpeg' ? jpegSize(buf) : null;
  return size ? { type, ...size } : { type, width: 0, height: 0 };
}

// Read just enough of a file to know its type/size (JPEG EXIF can be ~64 KB).
export function imageSizeOfFile(file) {
  const fd = fs.openSync(file, 'r');
  try {
    const buf = Buffer.alloc(256 * 1024);
    const n = fs.readSync(fd, buf, 0, buf.length, 0);
    return imageSize(buf.subarray(0, n));
  } finally { fs.closeSync(fd); }
}

// Remove EXIF and XMP chunks from a WebP file (in place) and clear their VP8X flags.
export function stripWebpMetadata(file) {
  const buf = fs.readFileSync(file);
  if (sniff(buf) !== 'webp') return false;
  const parts = [buf.subarray(0, 12)];
  let off = 12, changed = false, vp8xIndex = -1;
  while (off + 8 <= buf.length) {
    const id = buf.toString('ascii', off, off + 4);
    const len = buf.readUInt32LE(off + 4);
    const end = Math.min(buf.length, off + 8 + len + (len & 1));
    if (id === 'EXIF' || id === 'XMP ') { changed = true; off = end; continue; }
    if (id === 'VP8X') vp8xIndex = parts.length;
    parts.push(Buffer.from(buf.subarray(off, end)));
    off = end;
  }
  if (!changed) return false;
  if (vp8xIndex > 0) parts[vp8xIndex][8] &= ~(0x08 | 0x04); // EXIF + XMP flags
  const out = Buffer.concat(parts);
  out.writeUInt32LE(out.length - 8, 4);
  fs.writeFileSync(file, out);
  return true;
}
