// All settings come from environment variables (set in the TrueNAS app / .env).
import path from 'node:path';

const env = process.env;

export const config = {
  port: Number(env.PORT || 8095),
  host: env.HOST || '0.0.0.0',
  publicUrl: (env.PUBLIC_URL || 'https://live.johorstudio.com').replace(/\/$/, ''),
  // m10: /mnt/ssd/livephoto/data  -> database, thumbnails
  dataDir: path.resolve(env.DATA_DIR || './data'),
  // tnas (NFS): /mnt/ssd/client-1month/livephoto  -> EVENTS/<folder>/...
  photosDir: path.resolve(env.PHOTOS_DIR || './photos'),
  // When 1, a missing marker file (.livephoto-storage) means "storage offline" (NFS not mounted).
  requireStorageMarker: env.REQUIRE_STORAGE_MARKER === '1',
  adminEmail: (env.ADMIN_EMAIL || 'rafierosliputra@gmail.com').toLowerCase(),
  googleClientId: env.GOOGLE_CLIENT_ID || '',
  // JohorStudio Mail (repo johorstudio-mail): POST {mailApi}/api/v1/send, template login_code
  mailApi: (env.MAIL_API || 'http://192.168.0.100:8140').replace(/\/$/, ''),
  mailKey: env.MAIL_API_KEY || '',
  mailFrom: env.MAIL_FROM || 'noreply@johorstudio.com',
  mailFromName: env.MAIL_FROM_NAME || 'JohorStudio.com Live',
  cookieSecure: env.COOKIE_SECURE !== '0',
  sessionDays: 30,
  codeMinutes: 10,
  scanIntervalSec: Number(env.SCAN_INTERVAL || 60),
  chunkBytes: 4 * 1024 * 1024,
  maxUploadBytes: Number(env.MAX_UPLOAD_MB || 80) * 1024 * 1024,
  maxGuestBytes: Number(env.MAX_GUEST_MB || 40) * 1024 * 1024,
  guestUploadsPerHour: Number(env.GUEST_UPLOADS_PER_HOUR || 300),
  whatsapp: env.WHATSAPP || '60127983745',
  contactUrl: env.CONTACT_URL || 'https://johorstudio.com/#contact',
  // Dev only: print login codes to the log instead of emailing them.
  devLogCodes: env.DEV_LOG_CODES === '1',
};

export const EVENTS_DIR = () => path.join(config.photosDir, 'EVENTS');
export const PUBLIC_UPLOAD = 'public upload';
export const META_FILE = '.livephoto.json';
export const STORAGE_MARKER = '.livephoto-storage';
export const slotFolder = (n) => `Photographer ${n}`;
