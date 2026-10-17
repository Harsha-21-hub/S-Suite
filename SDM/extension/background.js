/* SDM Catcher — hands browser downloads to the SDM engine on this PC. */
"use strict";

const API = "http://127.0.0.1:6880";
const NATIVE_HOST = "com.fastdl.host";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ---------------- app link ---------------- */
async function local() {
  return chrome.storage.local.get({ enabled: true, pauseUntil: 0, minSizeKB: 0, skipSites: "", caught: 0 });
}

async function ping(ms = 1200) {
  try {
    const r = await fetch(API + "/api/ping?src=ext", { signal: AbortSignal.timeout(ms), cache: "no-store" });
    if (!r.ok) return null;
    const j = await r.json();
    if (j.app !== "SDM" && j.app !== "FastDL") return null;     // FastDL = SDM's old name
    await chrome.storage.local.set({ enabled: !!j.intercept, minSizeKB: j.min_size_kb || 0, skipSites: j.skip_sites || "" });
    return j;
  } catch {
    return null;
  }
}

/** Make sure the engine is running; start it through the native helper if needed. */
/** manual = the user clicked something (menu, popup): always allowed to start SDM.
 *  automatic download catches only start it if "Browser can start SDM" is on in its Settings. */
async function ensureApp(manual = false) {
  let j = await ping();
  if (j) return j;
  try {
    const r = await chrome.runtime.sendNativeMessage(NATIVE_HOST, { cmd: manual ? "launch" : "start" });
    if (!r || r.ok === false) return null;
  } catch {
    return null; // helper not installed — browser keeps the download
  }
  for (let i = 0; i < 30; i++) {
    await sleep(300);
    j = await ping(800);
    if (j) return j;
  }
  return null;
}

async function cookiesFor(urls, incognito) {
  const out = new Map();
  let storeId;
  if (incognito) {
    try {
      const stores = await chrome.cookies.getAllCookieStores();
      const inc = stores.find((s) => s.id !== "0");
      storeId = inc && inc.id;
    } catch { /* ignore */ }
  }
  for (const url of urls) {
    if (!url || !/^https?:/i.test(url)) continue;
    try {
      const list = await chrome.cookies.getAll(storeId ? { url, storeId } : { url });
      for (const c of list) {
        out.set(`${c.domain}|${c.path}|${c.name}`, {
          name: c.name, value: c.value, domain: c.domain, path: c.path, secure: c.secure,
          hostOnly: c.hostOnly, httpOnly: c.httpOnly, expirationDate: c.expirationDate || 0,
        });
      }
    } catch { /* ignore */ }
  }
  return [...out.values()];
}

async function send(payload) {
  try {
    const r = await fetch(API + "/api/add", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ user_agent: navigator.userAgent, ...payload }),
    });
    return r.ok;
  } catch {
    return false;
  }
}

function flash(text = "↓") {
  chrome.action.setBadgeBackgroundColor({ color: "#D71921" });
  chrome.action.setBadgeText({ text });
  setTimeout(() => refreshBadge(), 2500);
}

function hostSkipped(url, list) {
  if (!url || !list) return false;
  let host = "";
  try { host = new URL(url).hostname.toLowerCase(); } catch { return false; }
  return list.split(/[\s,;]+/).map((s) => s.trim().toLowerCase().replace(/^\*?\./, "")).filter(Boolean)
    .some((s) => host === s || host.endsWith("." + s));
}

/* ---------------- download interception ---------------- */
async function shouldCatch(item) {
  const url = item.finalUrl || item.url;
  if (!/^https?:/i.test(url)) return false;          // blob:, data:, file: stay in the browser
  if (item.byExtensionId) return false;              // downloads started by extensions
  const st = await local();
  if (!st.enabled || Date.now() < st.pauseUntil) return false;
  const size = item.fileSize > 0 ? item.fileSize : item.totalBytes > 0 ? item.totalBytes : 0;
  if (st.minSizeKB > 0 && size > 0 && size < st.minSizeKB * 1024) return false;
  if (hostSkipped(url, st.skipSites) || hostSkipped(item.referrer, st.skipSites)) return false;
  return true;
}

async function catchDownload(item) {
  if (!(await shouldCatch(item))) return false;
  const app = await ensureApp();
  if (!app || !app.intercept) return false;
  const name = (item.filename || "").split(/[\\/]/).pop();
  const ok = await send({
    url: item.url,
    final_url: item.finalUrl && item.finalUrl !== item.url ? item.finalUrl : undefined,
    filename: name || undefined,
    referrer: item.referrer || "",
    size: item.fileSize > 0 ? item.fileSize : item.totalBytes > 0 ? item.totalBytes : undefined,
    mime: item.mime || "",
    cookies: await cookiesFor([item.url, item.finalUrl, item.referrer], item.incognito),
    source: "browser",
  });
  if (!ok) return false;
  try {
    await chrome.downloads.cancel(item.id);
  } catch {
    return "uncancelled";
  }
  try { await chrome.downloads.erase({ id: item.id }); } catch { /* ignore */ }
  const st = await local();
  chrome.storage.local.set({ caught: (st.caught || 0) + 1 });
  flash();
  return true;
}

if (chrome.downloads.onDeterminingFilename) {
  // Fires before the browser picks a file name / shows "Save as", so nothing reaches the disk.
  chrome.downloads.onDeterminingFilename.addListener((item, suggest) => {
    catchDownload(item)
      .then((res) => { if (res !== true) suggest(); })
      .catch(() => suggest());
    return true;
  });
} else {
  chrome.downloads.onCreated.addListener((item) => { catchDownload(item); });
}

/* ---------------- context menu ---------------- */
function buildMenus() {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({ id: "fdl-link", title: "Download with SDM", contexts: ["link"] });
    chrome.contextMenus.create({ id: "fdl-src", title: "Download this with SDM", contexts: ["image", "video", "audio"] });
    chrome.contextMenus.create({ id: "fdl-page", title: "SDM: grab video / audio from this page", contexts: ["page", "video", "frame"] });
  });
}

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const app = await ensureApp(true);
  if (!app) {
    chrome.action.setBadgeBackgroundColor({ color: "#D71921" });
    chrome.action.setBadgeText({ text: "OFF" });
    setTimeout(refreshBadge, 3000);
    return;
  }
  const page = info.pageUrl || (tab && tab.url) || "";
  let url = "", kind;
  if (info.menuItemId === "fdl-link") url = info.linkUrl;            // http, ftp and magnet links
  if (info.menuItemId === "fdl-src") {
    url = info.srcUrl;
    if (!/^https?:/i.test(url || "")) { url = info.frameUrl || page; kind = "media"; }
  }
  if (info.menuItemId === "fdl-page") { url = info.frameUrl || page; kind = "media"; }
  if (!/^(https?|ftps?|magnet):/i.test(url || "")) return;
  const ok = await send({
    url, kind, referrer: page, cookies: await cookiesFor([url, page], tab && tab.incognito),
    source: "link", page_url: page,
  });
  if (ok) flash();
});

/* ---------------- media sniffer ---------------- */
const SEGMENT_RE = /\.(ts|m4s|m4f|cmfv|cmfa|aac|vtt|webvtt|key)$/i;
function header(list, name) {
  const h = (list || []).find((x) => x.name.toLowerCase() === name);
  return h ? h.value || "" : "";
}
const mediaKey = (tabId) => "media_" + tabId;

chrome.webRequest.onHeadersReceived.addListener(
  (d) => {
    if (d.tabId < 0 || d.statusCode >= 400) return;
    let u;
    try { u = new URL(d.url); } catch { return; }
    const path = u.pathname.toLowerCase();
    const ct = header(d.responseHeaders, "content-type").split(";")[0].trim().toLowerCase();
    let type = null;
    if (path.endsWith(".m3u8") || ct.includes("mpegurl")) type = "HLS";
    else if (path.endsWith(".mpd") || ct === "application/dash+xml") type = "DASH";
    else if (/^(video|audio)\//.test(ct) && ct !== "video/mp2t" && !SEGMENT_RE.test(path)) type = ct.split("/")[0] === "audio" ? "AUDIO" : "VIDEO";
    if (!type) return;
    if (/googlevideo\.com$/.test(u.hostname) || u.searchParams.has("range") && /videoplayback/.test(path)) return;
    const cr = header(d.responseHeaders, "content-range");
    const total = cr && /\/(\d+)/.test(cr) ? +cr.match(/\/(\d+)/)[1] : +header(d.responseHeaders, "content-length") || 0;
    if ((type === "VIDEO" || type === "AUDIO") && total && total < 300 * 1024) return;
    remember(d.tabId, { url: d.url, type, size: total, name: decodeURIComponent(path.split("/").pop() || u.hostname) });
  },
  { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other", "object"] },
  ["responseHeaders"]
);

let saveChain = Promise.resolve();
function remember(tabId, m) {
  saveChain = saveChain.then(async () => {
    const k = mediaKey(tabId);
    const cur = (await chrome.storage.session.get(k))[k] || [];
    if (cur.some((x) => x.url === m.url)) return;
    cur.unshift({ ...m, t: Date.now() });
    await chrome.storage.session.set({ [k]: cur.slice(0, 40) });
    refreshBadge(tabId);
  }).catch(() => {});
}

async function refreshBadge(tabId) {
  try {
    if (tabId == null) {
      const [t] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
      if (!t) return;
      tabId = t.id;
    }
    const k = mediaKey(tabId);
    const n = ((await chrome.storage.session.get(k))[k] || []).length;
    chrome.action.setBadgeBackgroundColor({ color: "#FFFFFF", tabId });
    if (chrome.action.setBadgeTextColor) chrome.action.setBadgeTextColor({ color: "#000000", tabId });
    chrome.action.setBadgeText({ text: n ? String(n) : "", tabId });
  } catch { /* tab gone */ }
}

chrome.tabs.onUpdated.addListener((tabId, info) => {
  if (info.status === "loading" && info.url) {
    chrome.storage.session.remove(mediaKey(tabId));
    chrome.action.setBadgeText({ text: "", tabId });
  }
});
chrome.tabs.onRemoved.addListener((tabId) => chrome.storage.session.remove(mediaKey(tabId)));
chrome.tabs.onActivated.addListener(({ tabId }) => refreshBadge(tabId));

/* ---------------- lifecycle ---------------- */
chrome.runtime.onInstalled.addListener(() => {
  buildMenus();
  chrome.alarms.create("ping", { periodInMinutes: 1 });
  ping();
});
chrome.runtime.onStartup.addListener(() => {
  buildMenus();
  chrome.alarms.create("ping", { periodInMinutes: 1 });
  ping();
});
chrome.alarms.onAlarm.addListener((a) => { if (a.name === "ping") ping(); });

// popup helpers
chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  (async () => {
    if (msg.cmd === "ensure") reply(await ensureApp(true));
    else if (msg.cmd === "grab") {
      const app = await ensureApp(true);
      if (!app) return reply({ ok: false, error: "SDM is not running" });
      const ok = await send({
        url: msg.url, kind: msg.kind, referrer: msg.page || "", page_url: msg.page || "",
        cookies: await cookiesFor([msg.url, msg.page], msg.incognito), source: "link",
      });
      if (ok) flash();
      reply({ ok });
    } else if (msg.cmd === "open") {
      const app = await ping();
      if (app) {
        try { await fetch(API + "/api/open_window", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" }); } catch { /* ignore */ }
        return reply({ ok: true });
      }
      try { await chrome.runtime.sendNativeMessage(NATIVE_HOST, { cmd: "open" }); reply({ ok: true }); }
      catch (e) { reply({ ok: false, error: "Start SDM from the desktop shortcut (helper not installed)" }); }
    }
  })();
  return true;
});
