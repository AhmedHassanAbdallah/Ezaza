/*
 * حساباتي - Service Worker
 * تصميم وتطوير: أحمد حسن عبدالله © 2026 - جميع الحقوق محفوظة
 *
 * كل إصدار من التطبيق يُحفظ على الهاتف في مخزن خاص به.
 * التطبيق يفتح دائماً من الإصدار الذي اختاره المستخدم،
 * ولا يتحول للإصدار الجديد إلا لما المستخدم يضغط "تحديث الآن".
 *
 * عند إضافة ملف جديد للتطبيق: أضفه في APP_FILES وزوّد SW_LOGIC.
 */
const SW_LOGIC = 2;
const PREFIX = 'hesabaty-';
const CONTENT_PREFIX = PREFIX + 'content-';
const META_CACHE = PREFIX + 'meta';
const FONT_CACHE = PREFIX + 'fonts';

const APP_FILES = [
  './',
  './index.html',
  './version.json',
  './manifest.json',
  './privacy.html',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-192.png',
  './icons/icon-maskable-512.png'
];

const scopeURL = path => new URL(path, self.registration.scope).href;
let selectedVersion = null;
let repairing = null;

async function getSelected() {
  if (selectedVersion) return selectedVersion;
  const meta = await caches.open(META_CACHE);
  const res = await meta.match('selected-version');
  selectedVersion = res ? await res.text() : null;
  return selectedVersion;
}

async function setSelected(version) {
  const meta = await caches.open(META_CACHE);
  await meta.put('selected-version', new Response(version));
  selectedVersion = version;
}

// تنزيل أحدث إصدار منشور بالكامل قبل التحويل إليه (لو فشل أي ملف لا يتغير شيء)
async function downloadLatest() {
  const infoRes = await fetch(scopeURL('./version.json') + '?latest=' + Date.now(), { cache: 'no-store' });
  if (!infoRes.ok) throw new Error('version.json ' + infoRes.status);
  const info = await infoRes.json();
  const cacheName = CONTENT_PREFIX + info.version;
  const cache = await caches.open(cacheName);
  try {
    for (const file of APP_FILES) {
      const res = await fetch(scopeURL(file) + '?v=' + encodeURIComponent(info.version), { cache: 'reload' });
      if (!res.ok) throw new Error(file + ' ' + res.status);
      await cache.put(scopeURL(file), res);
    }
  } catch (err) {
    if (info.version !== selectedVersion) await caches.delete(cacheName);
    throw err;
  }
  return info;
}

async function cleanup() {
  const keep = new Set([META_CACHE, FONT_CACHE, CONTENT_PREFIX + (await getSelected())]);
  const keys = await caches.keys();
  await Promise.all(keys
    .filter(k => k.startsWith('hesabaty') && !keep.has(k))
    .map(k => caches.delete(k)));
}

self.addEventListener('install', event => {
  event.waitUntil((async () => {
    const current = await getSelected();
    // لو المستخدم عنده إصدار محفوظ نحافظ عليه كما هو، وإلا ننزّل أحدث إصدار
    if (!current || !(await caches.has(CONTENT_PREFIX + current))) {
      const info = await downloadLatest();
      await setSelected(info.version);
    }
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', event => {
  event.waitUntil(cleanup().then(() => self.clients.claim()));
});

self.addEventListener('message', event => {
  const data = event.data || {};
  const port = event.ports && event.ports[0];
  const reply = msg => port && port.postMessage(msg);

  if (data.type === 'GET_STATUS') {
    event.waitUntil(getSelected().then(v => reply({ version: v, logic: SW_LOGIC })));
  }
  if (data.type === 'INSTALL_LATEST') {
    event.waitUntil((async () => {
      try {
        const info = await downloadLatest();
        await setSelected(info.version);
        await cleanup();
        reply({ ok: true, version: info.version });
      } catch (err) {
        reply({ ok: false, error: String(err) });
      }
    })());
  }
});

self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);

  // خطوط جوجل: من الهاتف فوراً مع تحديثها في الخلفية (تعمل بدون إنترنت)
  if (url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com') {
    event.respondWith((async () => {
      const cache = await caches.open(FONT_CACHE);
      const hit = await cache.match(req);
      const net = fetch(req).then(res => {
        if (res.ok || res.type === 'opaque') cache.put(req, res.clone());
        return res;
      }).catch(() => hit);
      return hit || net;
    })());
    return;
  }

  if (url.origin !== self.location.origin) return;

  // فحص وجود إصدار أحدث: دائماً من الإنترنت
  if (url.pathname.endsWith('/version.json') && url.searchParams.has('latest')) {
    event.respondWith(fetch(req, { cache: 'no-store' }));
    return;
  }

  event.respondWith((async () => {
    let version = await getSelected();
    // لو ملفات الإصدار اتمسحت من الهاتف (مثلاً المساحة امتلأت) ننزّلها من جديد
    if (version && !(await caches.has(CONTENT_PREFIX + version))) {
      version = null;
      if (!repairing) {
        repairing = downloadLatest()
          .then(info => setSelected(info.version))
          .then(cleanup)
          .catch(() => {})
          .finally(() => { repairing = null; });
      }
      event.waitUntil(repairing);
    }
    if (version) {
      const cache = await caches.open(CONTENT_PREFIX + version);
      const hit = await cache.match(req, { ignoreSearch: true });
      if (hit) return hit;
      if (req.mode === 'navigate') {
        try { return await fetch(req); }
        catch (e) {
          const index = await cache.match(scopeURL('./index.html'));
          if (index) return index;
        }
      }
    }
    return fetch(req);
  })());
});
