// Service worker: aplikacja otwiera się także bez internetu (nagrania czekają w kolejce).
const CACHE = 'lt-shell-v4';
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
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
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
