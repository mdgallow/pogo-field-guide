const CACHE_NAME = 'pogo-companion-v56';
const ASSETS_TO_CACHE = [
  './',
  './index.html',
  './manifest.json',
  './icon.svg',
  './icon-192.png',
  './icon-512.png',
  './apple-touch-icon.png',
  './pogo_qr_code.png'
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS_TO_CACHE))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))
      );
    })
  );
  self.clients.claim();
});

const isNavigation = (req) => req.mode === 'navigate' || (req.destination === 'document');
const sameOrigin = (url) => new URL(url).origin === self.location.origin;

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;

  // The page itself: network first (so a redeploy reaches existing visitors), cache as fallback.
  if (isNavigation(e.request) || e.request.url.endsWith('/index.html')) {
    e.respondWith(
      fetch(e.request).then((response) => {
        if (response && response.status === 200) {
          const clone = response.clone();
          caches.open(CACHE_NAME).then((cache) => { cache.put(e.request, clone); cache.put('./index.html', response.clone()); });
        }
        return response;
      }).catch(() => caches.match(e.request).then((hit) => hit || caches.match('./index.html')))
    );
    return;
  }

  // Everything else: cache first, then network; same-origin files and image sprites are cached
  // on the fly so a Force Reload never leaves the offline cache empty.
  e.respondWith(
    caches.match(e.request).then((cachedResponse) => {
      if (cachedResponse) return cachedResponse;
      return fetch(e.request).then((response) => {
        if (response && response.status === 200 && (
          sameOrigin(e.request.url) ||
          e.request.url.includes('raw.githubusercontent.com') ||
          e.request.url.includes('.png')
        )) {
          const responseClone = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(e.request, responseClone));
        }
        return response;
      }).catch(() => cachedResponse || Response.error());
    })
  );
});
