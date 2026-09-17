// Minimal service worker -- exists so Chrome considers this app installable (manifest +
// registered SW + HTTPS/localhost are the three requirements). Deliberately no caching: every
// real interaction here needs a live call to Groq, and caching those responses would be actively
// wrong (stale tutor replies, stale transcriptions). Add real offline caching only if this ever
// needs to work without a network, which isn't the case for a live voice-tutor demo.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));
self.addEventListener('fetch', () => {}); // presence alone satisfies installability; network passthrough
