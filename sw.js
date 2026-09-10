const CACHE_NAME = 'pogo-companion-v11';
const ASSETS_TO_CACHE = [
  './',
  './index.html',
  './manifest.json',
  './icon.svg',
  './icon-192.png',
  './icon-512.png',
  './apple-touch-icon.png',
  './pogo_qr_code.png',
  'https://cdn.jsdelivr.net/npm/tesseract.js@5/dist/tesseract.min.js'
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

self.addEventListener('fetch', (e) => {
  // Cache-first strategy
  e.respondWith(
    caches.match(e.request).then((cachedResponse) => {
      if (cachedResponse) {
        return cachedResponse;
      }
      return fetch(e.request).then((response) => {
        // Cache external image sprites and OCR dependencies on the fly
        if (response && response.status === 200 && (
          e.request.url.includes('raw.githubusercontent.com') ||
          e.request.url.includes('cdn.jsdelivr.net') ||
          e.request.url.includes('tesseract') ||
          e.request.url.includes('.png') ||
          e.request.url.includes('.wasm') ||
          e.request.url.includes('.traineddata')
        )) {
          const responseClone = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(e.request, responseClone));
        }
        return response;
      }).catch(() => {
        // Fallback if offline
        return cachedResponse;
      });
    })
  );
});
