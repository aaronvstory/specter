// Offline shell for the installed PWA.
//
// NETWORK-FIRST for the page, cache-first for the icons. That order is the whole design: the page is
// generated from PAGE in specter/ipcheck.py and redeployed often, and a cache-first shell is how a PWA
// pins a visitor to a months-old build that still looks current — the exact silent-staleness failure this
// project keeps paying for elsewhere. The cache is a fallback for being offline, not the source of truth.
//
// /api/* is never cached, in either direction: a cached reputation answer would be a MEASUREMENT that is
// no longer true, rendered as if it had just been taken. That is worse than no answer at all.
const CACHE = 'specter-ip-v1';
const SHELL = ['/', '/icon.svg', '/icon-192.png', '/icon-512.png', '/apple-touch-icon.png',
               '/favicon-32.png', '/manifest.webmanifest'];

self.addEventListener('install', e => {
  // addAll rejects the whole batch if ONE entry 404s, which would leave the worker permanently
  // uninstalled and silently offline-broken. Each entry is cached on its own instead.
  e.waitUntil(caches.open(CACHE)
    .then(c => Promise.all(SHELL.map(u => c.add(u).catch(() => {}))))
    .then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;      // flag images etc. — let the network handle them
  if (url.pathname.startsWith('/api/')) return;         // never cache a measurement

  const isDoc = req.mode === 'navigate' || url.pathname === '/' || url.pathname.endsWith('.html');
  if (isDoc) {
    e.respondWith(fetch(req)
      .then(r => { const copy = r.clone(); caches.open(CACHE).then(c => c.put(req, copy)); return r; })
      .catch(() => caches.match(req).then(r => r || caches.match('/'))));
    return;
  }
  e.respondWith(caches.match(req).then(r => r || fetch(req).then(net => {
    const copy = net.clone();
    caches.open(CACHE).then(c => c.put(req, copy));
    return net;
  })));
});
