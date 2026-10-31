// Server-Sent Events hub: one channel per event. Heartbeat keeps Cloudflare Tunnel from
// closing idle streams (~100 s limit).
const channels = new Map(); // eventId -> Set<res>
let photoToJson = (p) => p;
export const setPhotoSerializer = (fn) => { photoToJson = fn; };

export function subscribe(eventId, req, res) {
  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-store, no-transform',
    connection: 'keep-alive',
    'x-accel-buffering': 'no',
  });
  res.write('retry: 3000\n\n');
  let set = channels.get(eventId);
  if (!set) channels.set(eventId, (set = new Set()));
  set.add(res);
  const hb = setInterval(() => res.write(': ping\n\n'), 20000);
  const close = () => { clearInterval(hb); set.delete(res); if (!set.size) channels.delete(eventId); };
  req.on('close', close);
  res.on('error', close);
}

export function publish(eventId, type, data) {
  const set = channels.get(eventId);
  if (!set) return;
  const payload = type === 'photo' ? photoToJson(data) : data;
  const msg = `event: ${type}\ndata: ${JSON.stringify(payload)}\n\n`;
  for (const res of set) res.write(msg);
}

export const viewerCount = (eventId) => channels.get(eventId)?.size || 0;
