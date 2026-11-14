// Service worker: aplikacja otwiera się także bez internetu (nagrania czekają w kolejce).
const CACHE = 'lt-shell-v5';
// Plik udostępniony z innej aplikacji czeka tu, aż odbierze go strona (nigdy nie trafia na serwer).
const SHARE_CACHE = 'lt-share';
const SHELL = [
  '/', '/index.html', '/styles.css', '/app.js', '/core.js', '/worklet.js', '/manifest.webmanifest',
  '/icons/icon-192.png', '/icons/icon-512.png', '/icons/maskable-512.png', '/icons/apple-touch-icon.png',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE && k !== SHARE_CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  const url = new URL(req.url);
  // Dyktafon → Udostępnij → LiveTranscribe (share_target z manifestu)
  if (req.method === 'POST' && url.origin === self.location.origin && url.pathname === '/share-target') {
    e.respondWith(receiveShare(req));
    return;
  }
  if (req.method !== 'GET') return;
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname === '/apk') return;

  // Najpierw sieć (świeża wersja), a bez internetu – kopia z pamięci telefonu.
  e.respondWith(
    fetch(req)
      .then((res) => {
        if (res.ok && res.type === 'basic') {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req.mode === 'navigate' ? '/index.html' : req, copy));
        }
        return res;
      })
      .catch(async () => {
        const hit = await caches.match(req.mode === 'navigate' ? '/index.html' : req, { ignoreSearch: true });
        return hit || Response.error();
      }),
  );
});

async function receiveShare(req) {
  try {
    const form = await req.formData();
    const file = form.getAll('audio').find((f) => f && typeof f === 'object' && f.size > 0);
    if (file) {
      const cache = await caches.open(SHARE_CACHE);
      await cache.put('/shared-audio', new Response(file, {
        headers: { 'Content-Type': file.type || 'application/octet-stream', 'X-File-Name': encodeURIComponent(file.name || 'nagranie') },
      }));
      return Response.redirect('/#/?shared=1', 303);
    }
  } catch (_) { /* niżej: informacja w aplikacji */ }
  return Response.redirect('/#/?shared=0', 303);
}
