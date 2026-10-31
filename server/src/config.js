// All settings come from environment variables (set in the TrueNAS app / .env).
import path from 'node:path';

const env = process.env;

export const config = {
  port: Number(env.PORT || 8095),
  publicUrl: (env.PUBLIC_URL || 'https://live.johorstudio.com').replace(/\/$/, ''),
  // m10: /mnt/ssd/livephoto/data  -> database, thumbnails, temporary uploads
  dataDir: path.resolve(env.DATA_DIR || './data'),
  // tnas (NFS): /mnt/ssd/client-1month/livephoto  -> EVENTS/<folder>/...
  photosDir: path.resolve(env.PHOTOS_DIR || './photos'),
  adminEmail: (env.ADMIN_EMAIL || 'rafierosliputra@gmail.com').toLowerCase(),
  googleClientId: env.GOOGLE_CLIENT_ID || '',
  mailjetKey: env.MAILJET_API_KEY || '',
  mailjetSecret: env.MAILJET_SECRET_KEY || '',
  mailFrom: env.MAIL_FROM || 'noreply@johorstudio.com',
  mailFromName: env.MAIL_FROM_NAME || 'Johor Studio Live',
  cookieSecure: env.COOKIE_SECURE !== '0',
  sessionDays: 30,
  scanIntervalSec: Number(env.SCAN_INTERVAL || 60),
  maxUploadBytes: Number(env.MAX_UPLOAD_MB || 80) * 1024 * 1024,
  maxGuestBytes: Number(env.MAX_GUEST_MB || 40) * 1024 * 1024,
  guestUploadsPerHour: Number(env.GUEST_UPLOADS_PER_HOUR || 300),
  whatsapp: env.WHATSAPP || '60127983745',
  // Dev only: print login codes to the log instead of emailing them.
  devLogCodes: env.DEV_LOG_CODES === '1',
};

export const EVENTS_DIR = () => path.join(config.photosDir, 'EVENTS');
export const PUBLIC_UPLOAD = 'public upload';
export const slotFolder = (n) => `Photographer ${n}`;
