// Server-Sent Events hub. Two channels per event:
//   public  -> galleries and the big screen: only approved, visible photos
//   staff   -> admin / organiser panels: every photo with its moderation status
// Heartbeat keeps Cloudflare Tunnel from closing idle streams (~100 s limit).
const channels = new Map(); // key -> Set<res>
let photoToJson = (p) => p;
export const setPhotoSerializer = (fn) => { photoToJson = fn; };

export function subscribe(eventId, req, res, staff = false) {
  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-store, no-transform',
    connection: 'keep-alive',
    'x-accel-buffering': 'no',
  });
  res.write('retry: 3000\n\n');
  const key = (staff ? 'staff:' : 'pub:') + eventId;
  let set = channels.get(key);
  if (!set) channels.set(key, (set = new Set()));
  set.add(res);
  const hb = setInterval(() => res.write(': ping\n\n'), 20000);
  const close = () => { clearInterval(hb); set.delete(res); if (!set.size) channels.delete(key); };
  req.on('close', close);
  res.on('error', close);
}

function send(key, type, data) {
  const set = channels.get(key);
  if (!set) return;
  const msg = `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of set) res.write(msg);
}

/** Non-photo events (remove, gone, event settings) go to both channels. */
export function publish(eventId, type, data) {
  send('pub:' + eventId, type, data);
  send('staff:' + eventId, type, data);
}

/** A photo was added or changed: staff always see it; the public only when it is approved and visible. */
export function publishPhoto(p) {
  const j = photoToJson(p);
  send('staff:' + p.event_id, 'photo', j);
  if (p.status === 'approved' && !p.hidden) send('pub:' + p.event_id, 'photo', j);
  else send('pub:' + p.event_id, 'remove', { id: p.id });
}

export const viewerCount = (eventId) => channels.get('pub:' + eventId)?.size || 0;
