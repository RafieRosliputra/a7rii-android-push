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
  // Admins log in through Cloudflare Access (paths /admin and /api/admin on live.johorstudio.com).
  adminEmails: (env.ADMIN_EMAILS || 'rafierosliputra@gmail.com,johorstudio@gmail.com').toLowerCase().split(',').map((s) => s.trim()).filter(Boolean),
  accessTeam: (env.ACCESS_TEAM_DOMAIN || 'johorstudio.cloudflareaccess.com').replace(/^https?:\/\//, ''),
  accessAud: env.ACCESS_AUD || '',
  accessCertsUrl: env.ACCESS_CERTS_URL || '',          // tests only; default https://<team>/cdn-cgi/access/certs
  devAdmin: env.DEV_ADMIN || '',                         // dev only: treat every /api/admin request as this email
  // AI moderator for guest photos (separate Python app on m10). Empty = approve without AI.
  moderatorUrl: (env.MODERATOR_URL || '').replace(/\/$/, ''),
  liveHours: Number(env.LIVE_HOURS || 8),
  maxLiveEvents: 3,
  guestUploadsPerEvent: Number(env.GUEST_UPLOADS_PER_EVENT || 30),
  codeTries: 3,
  codeLockMin: 30,
  siteOrigins: (env.SITE_ORIGINS || 'https://johorstudio.com,https://www.johorstudio.com').split(','),
  cookieSecure: env.COOKIE_SECURE !== '0',
  scanIntervalSec: Number(env.SCAN_INTERVAL || 60),
  chunkBytes: 4 * 1024 * 1024,
  maxUploadBytes: Number(env.MAX_UPLOAD_MB || 80) * 1024 * 1024,
  maxGuestBytes: Number(env.MAX_GUEST_MB || 40) * 1024 * 1024,
  guestUploadsPerHour: Number(env.GUEST_UPLOADS_PER_HOUR || 300),
  whatsapp: env.WHATSAPP || '60127983745',
  contactUrl: env.CONTACT_URL || 'https://johorstudio.com/#contact',
};

export const EVENTS_DIR = () => path.join(config.photosDir, 'EVENTS');
export const PUBLIC_UPLOAD = 'public upload';
export const META_FILE = '.livephoto.json';
export const STORAGE_MARKER = '.livephoto-storage';
export const slotFolder = (n) => `Photographer ${n}`;
