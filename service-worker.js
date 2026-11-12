/*
 * حساباتي - Service Worker
 * © 2026 أحمد حسن عبدالله - جميع الحقوق محفوظة.
 *
 * نظام الإصدارات:
 *  - كل إصدار من التطبيق يُحفظ في ذاكرة مستقلة (hesabaty-app-<الإصدار>).
 *  - الـ Service Worker يعرض دائماً الإصدار الذي اختاره المستخدم (active)،
 *    حتى لو تم نشر إصدار أحدث على الخادم.
 *  - عند نزول إصدار جديد يتم تنزيله في الخلفية، ويسأل التطبيقُ المستخدمَ:
 *    "تحديث الآن" أو "البقاء على الإصدار الحالي".
 *  - يتم الاحتفاظ بالإصدار السابق لإمكانية الرجوع إليه.
 *
 * عند إصدار تحديث جديد: غيّر SW_VERSION هنا + APP_VERSION في index.html + version.json
 * (أو شغّل: node tools/bump-version.js 2.1.0 "ملاحظة 1" "ملاحظة 2").
 */
const SW_VERSION = "2.3.0";

const APP_PREFIX = "hesabaty-app-";
const META_CACHE = "hesabaty-meta";
const RUNTIME_CACHE = "hesabaty-runtime";
const LEGACY_CACHES = ["hesabaty-v1"];
const META_KEY = new URL("./__version-meta__", self.location).href;
const SCOPE_PATH = new URL("./", self.location).pathname;
const INDEX_URL = new URL("./index.html", self.location).href;

const APP_FILES = [
  "./",
  "./index.html",
  "./manifest.json",
  "./privacy.html",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
  "./icons/icon-maskable-192.png",
  "./icons/icon-maskable-512.png",
  "./icons/developer.jpg"
];

/* ---------- أدوات مساعدة ---------- */
function cmpVer(a, b) {
  const pa = String(a || "0").split(".").map(n => parseInt(n, 10) || 0);
  const pb = String(b || "0").split(".").map(n => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d !== 0) return d > 0 ? 1 : -1;
  }
  return 0;
}

async function readMeta() {
  const cache = await caches.open(META_CACHE);
  const res = await cache.match(META_KEY);
  if (!res) return {};
  try { return await res.json(); } catch (e) { return {}; }
}

async function writeMeta(meta) {
  const cache = await caches.open(META_CACHE);
  await cache.put(META_KEY, new Response(JSON.stringify(meta), {
    headers: { "Content-Type": "application/json" }
  }));
}

async function cachedVersions() {
  const keys = await caches.keys();
  return keys.filter(k => k.startsWith(APP_PREFIX)).map(k => k.slice(APP_PREFIX.length));
}

async function cleanupCaches(meta) {
  const keep = new Set([meta.active, meta.previous, SW_VERSION].filter(Boolean).map(v => APP_PREFIX + v));
  const keys = await caches.keys();
  await Promise.all(keys.map(k => {
    if (LEGACY_CACHES.includes(k)) return caches.delete(k);
    if (k.startsWith(APP_PREFIX) && !keep.has(k)) return caches.delete(k);
    return null;
  }));
}

/* يُرجع الإصدار الذي يجب عرضه حالياً */
async function resolveActiveVersion() {
  const meta = await readMeta();
  const versions = await cachedVersions();
  if (meta.active && versions.includes(meta.active)) return meta.active;
  return SW_VERSION;
}

async function versionInfo() {
  const meta = await readMeta();
  const versions = await cachedVersions();
  const active = (meta.active && versions.includes(meta.active)) ? meta.active : SW_VERSION;
  const previous = (meta.previous && meta.previous !== active && versions.includes(meta.previous)) ? meta.previous : null;
  return {
    type: "VERSION_INFO",
    active,
    latest: SW_VERSION,
    previous,
    cached: versions.sort(cmpVer)
  };
}

async function setVersion(version) {
  const versions = await cachedVersions();
  if (!versions.includes(version)) return { ok: false, reason: "not-cached" };
  const meta = await readMeta();
  if (meta.active !== version) {
    meta.previous = meta.active || null;
    meta.active = version;
  }
  await writeMeta(meta);
  await cleanupCaches(meta);
  return Object.assign({ ok: true }, await versionInfo());
}

/* ---------- دورة حياة الـ Service Worker ---------- */
self.addEventListener("install", event => {
  event.waitUntil((async () => {
    const cache = await caches.open(APP_PREFIX + SW_VERSION);
    await cache.addAll(APP_FILES.map(u => new Request(u, { cache: "reload" })));
    await self.skipWaiting();
  })());
});

self.addEventListener("activate", event => {
  event.waitUntil((async () => {
    const meta = await readMeta();
    const versions = await cachedVersions();
    // أول تثبيت (أو ضاع الإصدار المختار): ابدأ بالإصدار الحالي
    if (!meta.active || !versions.includes(meta.active)) meta.active = SW_VERSION;
    meta.latest = SW_VERSION;
    await writeMeta(meta);
    await cleanupCaches(meta);
    await self.clients.claim();
    const info = await versionInfo();
    const clients = await self.clients.matchAll({ type: "window" });
    clients.forEach(c => c.postMessage(Object.assign({}, info, { type: "VERSION_AVAILABLE" })));
  })());
});

self.addEventListener("message", event => {
  const data = event.data || {};
  const port = event.ports && event.ports[0];
  const reply = payload => {
    if (port) port.postMessage(payload);
    else if (event.source) event.source.postMessage(payload);
  };
  if (data.type === "GET_VERSION_INFO") {
    event.waitUntil(versionInfo().then(reply));
  } else if (data.type === "SET_VERSION") {
    event.waitUntil(setVersion(String(data.version)).then(reply));
  }
});

/* ---------- تقديم الملفات ---------- */
async function serveApp(request) {
  const version = await resolveActiveVersion();
  const cache = await caches.open(APP_PREFIX + version);
  const url = new URL(request.url);
  // الصفحة الرئيسية فقط (وليس أي مسار فرعي مثل /beta/)
  const isIndex = request.mode === "navigate" && (url.pathname === SCOPE_PATH || url.pathname === SCOPE_PATH + "index.html");
  const hit = await cache.match(isIndex ? INDEX_URL : request, { ignoreSearch: true });
  if (hit) return hit;
  try {
    return await fetch(request);
  } catch (e) {
    if (request.mode === "navigate") {
      const fallback = await cache.match(INDEX_URL);
      if (fallback) return fallback;
    }
    throw e;
  }
}

async function serveFont(request) {
  const cache = await caches.open(RUNTIME_CACHE);
  const hit = await cache.match(request);
  const network = fetch(request).then(res => {
    if (res && (res.ok || res.type === "opaque")) cache.put(request, res.clone());
    return res;
  }).catch(() => hit || Response.error());
  return hit || network;
}

self.addEventListener("fetch", event => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);

  if (url.origin === self.location.origin) {
    if (!url.pathname.startsWith(SCOPE_PATH)) return;
    // ملفات يجب أن تأتي دائماً من الشبكة لاكتشاف التحديثات
    if (url.pathname.endsWith("/version.json") || url.pathname.endsWith("/service-worker.js")) return;
    event.respondWith(serveApp(request));
    return;
  }

  if (url.hostname === "fonts.googleapis.com" || url.hostname === "fonts.gstatic.com") {
    event.respondWith(serveFont(request));
  }
});
