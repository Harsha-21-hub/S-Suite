/* SDM UI — talks to the local engine over REST + WebSocket */
"use strict";

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];

function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === "class") el.className = v;
    else if (k === "text") el.textContent = v;
    else if (k === "html") el.innerHTML = v;
    else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const kid of kids.flat()) {
    if (kid == null || kid === false) continue;
    el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return el;
}
function icon(name, cls = "ico") {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("class", cls);
  const use = document.createElementNS(ns, "use");
  use.setAttribute("href", "#i-" + name);
  svg.append(use);
  return svg;
}

/** Wrap a range input so the dots left of the thumb light up. */
function dslider(input) {
  const fill = h("span", { class: "fill" });
  const wrap = h("div", { class: "dslider" }, fill, input);
  const upd = () => {
    const min = +input.min || 0, max = +input.max || 100;
    wrap.style.setProperty("--p", ((+input.value - min) / (max - min)).toFixed(4));
  };
  input.addEventListener("input", upd);
  upd();
  return wrap;
}

/* ---------------- state ---------------- */
const S = {
  items: new Map(), order: [], cfg: {}, speed: 0, hist: [], stats: {},
  filter: "all", cat: null, q: "", ytdlp: false, ffmpeg: false, ext: false,
  expanded: new Set(), connected: false, quit: false, free: null, version: "",
  dialogFor: null, modals: [], cards: new Map(), platform: "", freeDrive: "",
  handled: new Set(),
  gone: new Set(),      // removed here; ignore late updates for these   // pending ids already answered in the dialog (never reopen them)
};

const STATUS = [
  ["all", "All", "all"], ["downloading", "Downloading", "down"], ["queued", "Queued", "clock"],
  ["paused", "Paused", "pause"], ["done", "Completed", "check"], ["error", "Failed", "alert"],
];
const CATS = [
  ["Video", "film"], ["Music", "music"], ["Archives", "box"], ["Programs", "app"],
  ["Documents", "doc"], ["Images", "image"], ["Torrents", "magnet"], ["Other", "dots"],
];
const CAT_ICON = Object.fromEntries(CATS);

/* ---------------- utils ---------------- */
function fmtBytes(n, dec) {
  if (n == null || isNaN(n)) return "—";
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  const d = dec != null ? dec : (i === 0 ? 0 : n < 10 ? 2 : n < 100 ? 1 : 0);
  return n.toFixed(d) + " " + u[i];
}
function splitBytes(n) {
  const s = fmtBytes(n || 0, (n || 0) < 1024 ? 0 : 1);
  const [v, u] = s.split(" ");
  return [v, u];
}
const mbitMode = () => S.cfg.speed_unit === "Mbps";
function fmtMbit(n) { const v = (n || 0) * 8 / 1e6; return v >= 100 ? v.toFixed(0) : v >= 10 ? v.toFixed(1) : v.toFixed(2); }
function fmtSpeed(n) {
  if (mbitMode()) return fmtMbit(n) + " Mbps";
  return fmtBytes(n || 0, (n || 0) >= 10 * 1048576 ? 1 : (n || 0) >= 1048576 ? 2 : 0) + "/s";
}
function speedParts(n) { return mbitMode() ? [fmtMbit(n), "MBPS"] : (([v, u]) => [v, u + "/S"])(splitBytes(n)); }
function fmtTime(s) {
  if (s == null || !isFinite(s)) return "—";
  s = Math.round(s);
  const hh = Math.floor(s / 3600), mm = Math.floor((s % 3600) / 60), ss = s % 60;
  const p = (x) => String(x).padStart(2, "0");
  return hh ? `${hh}:${p(mm)}:${p(ss)}` : `${p(mm)}:${p(ss)}`;
}
function fmtDate(t) {
  if (!t) return "—";
  const d = new Date(t * 1000);
  return d.toLocaleDateString(undefined, { day: "2-digit", month: "short" }) + " " +
    d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
}
/* 5x7 dot-matrix digits (Nothing-style rounded zero, red decimal dot), drawn as crisp SVG */
const GLYPHS = {
  "0": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
  "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
  "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
  "3": ["11110", "00001", "00001", "01110", "00001", "00001", "11110"],
  "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
  "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
  "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
  "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
  "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
  "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
  ".": ["0", "0", "0", "0", "0", "0", "1"],
  ":": ["0", "1", "0", "0", "0", "1", "0"],
  "-": ["000", "000", "000", "111", "000", "000", "000"],
};
function dnum(str) {
  const P = 10, out = [];
  let x = 0;
  for (const ch of String(str)) {
    const g = GLYPHS[ch];
    if (!g) { x += 3; continue; }
    const w = g[0].length;
    for (let r = 0; r < 7; r++) for (let c = 0; c < w; c++) {
      if (g[r][c] === "1") out.push(`<circle cx="${(x + c) * P + P / 2}" cy="${r * P + P / 2}" r="4.3"${ch === "." ? ' class="rd"' : ""}/>`);
    }
    x += w + 1;
  }
  const W = Math.max(1, x - 1) * P;
  return `<svg class="dsvg" viewBox="0 0 ${W} ${7 * P}" style="aspect-ratio:${W}/${7 * P}" fill="currentColor" aria-label="${str}">${out.join("")}</svg>`;
}
function setDots(el, value, unit) {
  const key = value + "|" + (unit || "");
  if (el.dataset.k === key) return;
  el.dataset.k = key;
  el.innerHTML = dnum(value) + (unit ? `<small>${unit}</small>` : "");
}
function Short(s, n) { s = String(s || ""); return s.length > n ? s.slice(0, n - 1) + "…" : s; }
function extOf(n) { const m = /\.([a-z0-9]{1,5})$/i.exec(n || ""); return m ? m[1].toUpperCase() : ""; }
function hostOf(u) { try { return new URL(u).host; } catch { return ""; } }
function joinPath(a, b) { if (!a) return b; const sep = a.includes("\\") ? "\\" : "/"; return a.replace(/[\\/]+$/, "") + sep + b; }
function debounce(fn, ms) { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; }
function cssVar(n) { return getComputedStyle(document.documentElement).getPropertyValue(n).trim(); }
let PAL = null;
function pal() {
  if (!PAL) PAL = { on: cssVar("--dot-on"), off: cssVar("--dot-off"), red: cssVar("--red"), dim: cssVar("--text-3") };
  return PAL;
}

/** Talk to the engine. Gives up after `timeout` ms (0 = wait, e.g. while a folder picker is open). */
async function api(path, data, timeout = 10000) {
  const opt = data === undefined ? {} : data instanceof Blob
    ? { method: "POST", headers: { "Content-Type": "application/x-bittorrent" }, body: data }   // a file, sent as-is
    : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) };
  let timer = null;
  if (timeout) {
    const ac = new AbortController();
    opt.signal = ac.signal;
    timer = setTimeout(() => ac.abort(), timeout);
  }
  let r;
  try { r = await fetch(path, opt); }
  catch (e) {
    throw new Error(e.name === "AbortError" ? "Engine not responding — close this window and open SDM.exe again" : "Engine offline — open SDM.exe");
  } finally { if (timer) clearTimeout(timer); }
  let j = {};
  try { j = await r.json(); } catch { /* empty */ }
  if (!r.ok || j.ok === false) throw new Error(j.error || "HTTP " + r.status);
  return j;
}
/* local state changes shown immediately; the engine confirms on its next update */
const OPTIMISTIC = { pause: "paused", resume: "queued", restart: "queued" };
const PRIORITY = { first: 1, normal: 0 };
async function act(id, action, extra = {}) {
  const v = S.items.get(id);
  if (v && OPTIMISTIC[action]) {
    S.items.set(id, { ...v, state: OPTIMISTIC[action], speed: 0, error: null });
    render();
  }
  if (v && action === "remove") dropLocal(id);
  if (v && action in PRIORITY) { S.items.set(id, { ...v, priority: PRIORITY[action] }); render(); }
  try { await api("/api/action", { id, action, ...extra }); }
  catch (e) { toast(e.message, 4500); }
}
function dropLocal(id) {
  S.gone.add(id);
  S.items.delete(id);
  S.order = S.order.filter((x) => x !== id);
  S.expanded.delete(id);
  const c = S.cards.get(id);
  if (c) { c.el.remove(); S.cards.delete(id); }
  render();
}

/* ---------------- toast / sound / notify ---------------- */
function toast(msg, ms = 2800) {
  const t = h("div", { class: "toast" }, h("i"), h("span", { text: msg }));
  $("#toasts").append(t);
  setTimeout(() => { t.style.transition = "opacity .3s"; t.style.opacity = "0"; setTimeout(() => t.remove(), 320); }, ms);
}
let AC = null;
function blip(ok = true) {
  if (!S.cfg.sound) return;
  try {
    AC = AC || new AudioContext();
    const t0 = AC.currentTime;
    const notes = ok ? [880, 1320] : [440, 330];
    notes.forEach((f, i) => {
      const o = AC.createOscillator(), g = AC.createGain();
      o.type = "square"; o.frequency.value = f;
      g.gain.setValueAtTime(0.0001, t0 + i * 0.09);
      g.gain.exponentialRampToValueAtTime(0.05, t0 + i * 0.09 + 0.01);
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + i * 0.09 + 0.08);
      o.connect(g).connect(AC.destination); o.start(t0 + i * 0.09); o.stop(t0 + i * 0.09 + 0.09);
    });
  } catch { /* audio unavailable */ }
}
function notify(title, body) {
  if (!("Notification" in window) || Notification.permission !== "granted" || !document.hidden) return;
  try { new Notification(title, { body, icon: "/ui/icon.png", silent: true }); } catch { /* ignore */ }
}

/* ---------------- websocket ---------------- */
let ws = null;
function connect() {
  ws = new WebSocket(`ws://${location.host}/ws`);
  ws.onopen = () => { S.connected = true; offline(false); };
  ws.onmessage = (e) => handle(JSON.parse(e.data));
  ws.onclose = () => {
    S.connected = false;
    if (S.quit) return;
    offline(true);
    setTimeout(connect, 1500);
  };
}
function offline(on) {
  let b = $("#offline");
  if (on && !b) document.body.append(h("div", { class: "offline", id: "offline", text: "Engine offline — reconnecting…" }));
  if (!on && b) b.remove();
}

function upsert(v) {
  if (S.gone.has(v.id)) return null;
  const prev = S.items.get(v.id);
  S.items.set(v.id, v);
  if (!prev && !S.order.includes(v.id)) S.order.unshift(v.id);
  return prev;
}
function handle(m) {
  if (m.t === "full") {
    S.items.clear();
    S.order = [];
    for (const v of m.items) { if (S.gone.has(v.id)) continue; S.items.set(v.id, v); S.order.push(v.id); }
    S.cfg = m.config; S.hist = m.hist || []; S.speed = m.speed; S.stats = m.stats;
    S.ytdlp = m.ytdlp; S.ffmpeg = m.ffmpeg; S.ext = m.ext; S.version = m.version; S.platform = m.platform;
    S.torrents = !!m.torrents; S.tray = !!m.tray;
    applyTheme();
    $("#ver").textContent = "v" + S.version;
    refreshFree();
  } else if (m.t === "tick") {
    S.speed = m.speed; S.stats = m.stats; S.ext = m.ext;
    S.hist.push(m.speed); if (S.hist.length > 240) S.hist.splice(0, S.hist.length - 240);
    for (const v of m.items) upsert(v);
    for (const id of m.removed || []) {
      S.items.delete(id); S.order = S.order.filter((x) => x !== id);
      const c = S.cards.get(id); if (c) { c.el.remove(); S.cards.delete(id); }
    }
  } else if (m.t === "finished") {
    if (m.state === "done") { toast("Done · " + m.filename); blip(true); notify("Download complete", m.filename); }
    else { toast("Failed · " + (m.error || m.filename), 4500); blip(false); notify("Download failed", m.error || m.filename); }
    return;
  }
  render();
}

/* ---------------- dot graphics ---------------- */
function fitCanvas(cv) {
  const dpr = window.devicePixelRatio || 1;
  const w = cv.clientWidth, hgt = cv.clientHeight;
  if (!w || !hgt) return null;
  if (cv.width !== Math.round(w * dpr) || cv.height !== Math.round(hgt * dpr)) {
    cv.width = Math.round(w * dpr); cv.height = Math.round(hgt * dpr);
  }
  const ctx = cv.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, hgt);
  return { ctx, w, h: hgt };
}
function dot(ctx, x, y, r, color) { ctx.fillStyle = color; ctx.beginPath(); ctx.arc(x, y, r, 0, 6.2832); ctx.fill(); }

function drawChart() {
  const cv = $("#chart"); const f = fitCanvas(cv); if (!f) return;
  const { ctx, w, h: H } = f; const P = pal();
  const gap = 8, r = 2.2;
  const cols = Math.floor(w / gap), rows = Math.floor(H / gap);
  const data = S.hist.slice(-cols);
  const max = Math.max(1024 * 64, ...data) * 1.12;
  const off = cols - data.length;
  for (let c = 0; c < cols; c++) {
    const v = c >= off ? data[c - off] : 0;
    const lit = v > 0 ? Math.max(1, Math.round((v / max) * rows)) : 0;
    for (let rr = 0; rr < rows; rr++) {
      const x = c * gap + gap / 2, y = H - (rr * gap + gap / 2);
      let col = P.off;
      if (rr < lit) col = (c === cols - 1 && rr === lit - 1) ? P.red : P.on;
      dot(ctx, x, y, r, col);
    }
  }
}

/** Dot bar: each dot = a slice of the file. White = downloaded, red = connection head, dim = waiting. */
function drawBar(cv, it, rows = 1) {
  const f = fitCanvas(cv); if (!f) return;
  const { ctx, w, h: H } = f; const P = pal();
  const gap = rows > 1 ? 7 : 7, r = rows > 1 ? 2.1 : 2.2;
  const cols = Math.max(1, Math.floor(w / gap));
  const N = cols * rows;
  const segs = it.segs;
  const size = it.size;
  const fracDone = size ? Math.min(1, it.done / size) : 0;
  const t = performance.now() / 120;
  const cover = new Float32Array(N), head = new Uint8Array(N);
  if (it.state === "done") cover.fill(1);
  else if (segs && segs.length) {
    for (const [a, p, e, act] of segs) {
      const i0 = Math.floor(a * N), i1 = Math.min(N - 1, Math.floor(Math.max(a, p - 1e-9) * N));
      if (p > a) for (let i = i0; i <= i1; i++) {
        const lo = i / N, hi = (i + 1) / N;
        cover[i] += Math.max(0, Math.min(hi, p) - Math.max(lo, a)) * N;
      }
      if (act && p < e) head[Math.min(N - 1, Math.floor(p * N))] = 1;
    }
  } else if (size) {
    const k = fracDone * N;
    for (let i = 0; i < N; i++) cover[i] = Math.max(0, Math.min(1, k - i));
    if (it.state === "downloading" && fracDone < 1) head[Math.min(N - 1, Math.floor(k))] = 1;
  }
  const unknown = !size && it.state === "downloading";
  for (let i = 0; i < N; i++) {
    const rr = Math.floor(i / cols), c = i % cols;
    const x = c * gap + gap / 2, y = rr * gap + gap / 2 + (H - rows * gap) / 2;
    let col = P.off;
    if (unknown) { const d = Math.abs(((c - t) % cols + cols) % cols - cols / 2); if (d < 3) col = d < 1 ? P.red : P.on; }
    else if (head[i]) col = P.red;
    else if (cover[i] >= 0.5) col = it.state === "error" ? P.dim : P.on;
    dot(ctx, x, y, r, col);
  }
}

/* ---------------- sidebar ---------------- */
function buildNav() {
  const ns = $("#nav-status");
  for (const [key, label, ic] of STATUS) {
    ns.append(h("button", { "data-f": key, onclick: () => { S.filter = key; render(); } },
      icon(ic), h("span", { text: label }), h("span", { class: "count", "data-c": key, text: "0" })));
  }
  const nc = $("#nav-cat");
  for (const [key, ic] of CATS) {
    nc.append(h("button", { "data-cat": key, onclick: () => { S.cat = S.cat === key ? null : key; render(); } },
      icon(ic), h("span", { text: key }), h("span", { class: "count", "data-cc": key, text: "0" })));
  }
}
function matchStatus(it, f) {
  if (f === "all") return true;
  if (f === "queued") return it.state === "queued" || it.state === "pending";
  return it.state === f;
}

/* ---------------- list rendering ---------------- */
function primaryAction(it) {
  switch (it.state) {
    case "downloading": case "queued": return ["pause", "Pause", () => act(it.id, "pause")];
    case "paused": return ["play", "Resume", () => act(it.id, "resume")];
    case "error": return ["retry", "Retry", () => act(it.id, "resume")];
    case "done": return ["open", "Open file", () => act(it.id, "open")];
    case "pending": return ["down", "Choose where to save", () => openSaveDialog(it.id, true)];
  }
  return ["more", "", () => {}];
}
const STATE_TXT = { downloading: "Downloading", queued: "Queued", paused: "Paused", done: "Done", error: "Failed", pending: "Waiting for you" };

function makeCard(id) {
  const el = h("div", { class: "dl" });
  const tile = h("div", { class: "tile" }, h("span", { class: "sd" }), h("span", { class: "tx" }));
  const fn = h("span", { class: "fn" });
  const st = h("span", { class: "state" });
  const pct = h("div", { class: "pct" });
  const b1 = h("button", { class: "ibtn hot" });
  const b2 = h("button", { class: "ibtn", title: "Show in folder" }, icon("folder"));
  const b3 = h("button", { class: "ibtn", title: "More" }, icon("more"));
  const meta = h("div", { class: "dl-meta" });
  const bar = h("canvas", { class: "bar" });
  const more = h("div", { class: "dl-more hide" });
  el.append(tile, h("div", { class: "dl-name" }, fn, st), h("div", { class: "dl-right" }, pct, h("div", { class: "acts" }, b1, b2, b3)), meta, bar, more);
  const card = { el, tile, fn, st, pct, b1, b2, b3, meta, bar, more, sig: "" };
  b1.addEventListener("click", (e) => { e.stopPropagation(); card.primary && card.primary(); });
  b2.addEventListener("click", (e) => { e.stopPropagation(); act(id, "folder"); });
  b3.addEventListener("click", (e) => { e.stopPropagation(); openMenu(b3, S.items.get(id)); });
  el.addEventListener("click", (e) => {
    if (e.target.closest("button, input, a, .dl-more")) return;
    if (S.expanded.has(id)) S.expanded.delete(id); else S.expanded.add(id);
    render();
  });
  el.addEventListener("dblclick", (e) => {
    if (e.target.closest("button, .dl-more")) return;
    const it = S.items.get(id); if (it && it.state === "done") act(id, "open");
  });
  return card;
}

function updateCard(card, it) {
  card.el.dataset.state = it.state;
  const ext = extOf(it.filename);
  card.tile.className = "tile" + (it.kind === "media" || it.kind === "torrent" ? " media" : "");
  const tx = card.tile.querySelector(".tx");
  const txv = it.kind === "torrent" ? "BT" : it.kind === "media" && !ext ? "MEDIA" : (ext || (it.category || "FILE").slice(0, 3).toUpperCase());
  if (tx.textContent !== txv) tx.textContent = txv;
  const name = it.filename || hostOf(it.url) || it.url;
  if (card.fn.textContent !== name) { card.fn.textContent = name; card.fn.title = name; }
  const qn = it.state === "queued" && S.qpos ? S.qpos.get(it.id) : 0;
  card.st.textContent = (it.seeding ? "Seeding" : STATE_TXT[it.state] || it.state) + (qn ? ` · #${qn}` : "") + (it.priority > 0 && it.state !== "done" ? " · first" : "");

  const p = it.state === "done" ? 100 : it.size ? Math.min(100, (it.done / it.size) * 100) : null;
  const pctHtml = p == null ? "" : `${dnum(p >= 99.95 || p === 0 ? p.toFixed(0) : p.toFixed(1))}<small>%</small>`;
  if (card.pctHtml !== pctHtml) { card.pct.innerHTML = pctHtml; card.pctHtml = pctHtml; }

  const [ic, title, fn] = primaryAction(it);
  card.primary = fn;
  if (card.b1.dataset.ic !== ic) { card.b1.replaceChildren(icon(ic)); card.b1.dataset.ic = ic; }
  card.b1.title = title;

  // meta line
  const parts = [];
  const sizeTxt = it.size ? `${it.state === "done" ? "" : fmtBytes(it.done) + " / "}${fmtBytes(it.size)}` : (it.done ? fmtBytes(it.done) : "Size unknown");
  parts.push(h("span", {}, h("b", { text: sizeTxt })));
  if (it.state === "downloading") {
    parts.push(h("span", { text: fmtSpeed(it.speed) }));
    if (it.eta != null) parts.push(h("span", { text: fmtTime(it.eta) + " left" }));
    parts.push(h("span", { text: "elapsed " + fmtTime(it.elapsed) }));
    parts.push(h("span", { text: it.kind === "torrent" ? `${it.conns} peers` : `${it.conns} conn` }));
  } else if (it.state === "done") {
    const avg = it.elapsed > 0 ? it.size / it.elapsed : 0;
    parts.push(h("span", { text: fmtDate(it.finished) }));
    if (it.elapsed > 0) parts.push(h("span", { text: "took " + fmtTime(it.elapsed) }));
    if (avg) parts.push(h("span", { text: "avg " + fmtSpeed(avg) }));
    if (it.note) parts.push(h("span", { class: "note", text: it.note }));
  } else if (it.state === "pending") {
    parts.push(h("span", { class: "note", text: "Choose where to save" }));
  } else {
    if (qn) parts.push(h("span", { class: "note", text: qn === 1 ? "next in queue" : `#${qn} in queue` }));
    parts.push(h("span", { text: it.kind === "torrent" ? "torrent" : hostOf(it.url) }));
    if (it.elapsed > 0) parts.push(h("span", { text: "elapsed " + fmtTime(it.elapsed) }));
    if (it.state === "paused" && !it.ranges && it.done) parts.push(h("span", { class: "note", text: "server can't resume — restarts" }));
  }
  if (it.state === "error" && it.error) parts.push(h("span", { class: "err", text: it.error, title: it.error }));
  else if (it.note && it.state === "downloading") parts.push(h("span", { class: "note", text: it.note }));
  const sig = parts.map((p) => p.textContent).join("|");
  if (sig !== card.sig) { card.meta.replaceChildren(...parts); card.sig = sig; }

  drawBar(card.bar, it, 1);

  // expanded details
  const open = S.expanded.has(it.id);
  card.el.classList.toggle("sel", open);
  card.more.classList.toggle("hide", !open);
  if (open) renderMore(card, it);
}

function kv(label, value, mono = true) {
  return h("div", { class: "kv" }, h("span", { class: "label", text: label }), h("span", { class: mono ? "v" : "v", text: value }));
}
function renderMore(card, it) {
  const key = [it.state, it.dir, it.filename, it.url, it.max_conns].join("|");
  if (card.moreKey !== key) {
    card.moreKey = key;
    const conn = h("div", { class: "kv" }, h("span", { class: "label", text: "Connections" }),
      h("div", { class: "conns", style: { gap: "10px" } },
        h("button", { class: "ibtn", title: "Fewer", onclick: () => act(it.id, "connections", { value: Math.max(1, it.max_conns - (it.max_conns > 128 ? 16 : 4)) }) }, h("span", { class: "mono", text: "−" })),
        h("span", { class: "cval", style: { minWidth: "40px", justifyContent: "center" }, html: dnum(String(it.max_conns)) }),
        h("button", { class: "ibtn", title: "More", onclick: () => act(it.id, "connections", { value: Math.min(256, it.max_conns + (it.max_conns >= 128 ? 16 : 4)) }) }, h("span", { class: "mono", text: "+" }))));
    card.more.replaceChildren(
      kv("Saved to", joinPath(it.dir, it.filename)),
      kv("Source", isWeb(it) ? Short(it.url, 180) : ".torrent file from this computer"),
      kv("Elapsed", fmtTime(it.elapsed) + (it.state === "done" ? " (total)" : "")),
      kv("Added", fmtDate(it.created) + (it.source === "browser" ? " · from browser" : "")),
      kv("Resume", it.ranges ? "Supported" : it.size ? "Not supported by server" : "Unknown"),
      conn,
      h("div", { class: "kv connmap" }, h("span", { class: "label", text: "Connection map — each dot is a slice of the file" }), card.map = h("canvas")),
      h("div", { class: "row" },
        isWeb(it) ? h("button", { class: "btn sm", onclick: () => copy(it.url) }, icon("copy"), "Copy link") : null,
        it.kind !== "torrent" ? h("button", { class: "btn sm", onclick: () => refreshLink(it) }, icon("link"), "Refresh link") : null,
        h("button", { class: "btn sm", onclick: () => act(it.id, "restart") }, icon("retry"), "Restart"),
        /^(https?|ftps?):/i.test(it.url) ? h("button", { class: "btn sm", onclick: () => act(it.id, "browser") }, icon("globe"), "Open in browser") : null,
        h("button", { class: "btn sm", onclick: () => confirmRemove(it) }, icon("trash"), "Remove")));
  }
  if (card.map) drawBar(card.map, it, 6);
}

function render() {
  const items = S.order.map((id) => S.items.get(id)).filter(Boolean);
  // queue order = the engine's start order: "download first" items, then oldest first
  const qd = items.filter((i) => i.state === "queued").sort((a, b) => (b.priority || 0) - (a.priority || 0) || (a.created || 0) - (b.created || 0));
  S.qpos = new Map(qd.map((i, k) => [i.id, k + 1]));
  // counts
  const counts = Object.fromEntries(STATUS.map(([k]) => [k, 0]));
  const cc = Object.fromEntries(CATS.map(([k]) => [k, 0]));
  for (const it of items) {
    for (const [k] of STATUS) if (matchStatus(it, k)) counts[k]++;
    cc[it.category || "Other"] = (cc[it.category || "Other"] || 0) + 1;
  }
  $$("#nav-status [data-c]").forEach((e) => (e.textContent = counts[e.dataset.c]));
  $$("#nav-cat [data-cc]").forEach((e) => (e.textContent = cc[e.dataset.cc] || 0));
  $$("#nav-status button").forEach((b) => b.classList.toggle("on", b.dataset.f === S.filter));
  $$("#nav-cat button").forEach((b) => b.classList.toggle("on", b.dataset.cat === S.cat));
  $("#listtitle").textContent = (STATUS.find((s) => s[0] === S.filter) || [0, "All"])[1].toUpperCase() + (S.cat ? " · " + S.cat.toUpperCase() : "");
  // toolbar follows the view: nothing to pause among finished items, failed items get "Retry all"
  $("#btn-pauseall").classList.toggle("hide", S.filter === "done" || S.filter === "error");
  $("#btn-resumeall").classList.toggle("hide", S.filter === "done");
  $("#btn-resumeall .lbl").textContent = S.filter === "error" ? "Retry all" : "Resume all";
  $("#btn-resumeall use").setAttribute("href", S.filter === "error" ? "#i-retry" : "#i-play");

  // widgets
  const [sv, su] = speedParts(S.speed);
  setDots($("#speed"), sv);
  $("#speedunit").textContent = su;
  const active = counts.downloading;
  $("#live").classList.toggle("on", active > 0);
  $("#livetxt").textContent = active ? "Live" : "Idle";
  setDots($("#st-active"), String(active));
  setDots($("#st-queued"), String(counts.queued));
  const [tv, tu] = splitBytes(S.stats.today || 0);
  setDots($("#st-today"), tv, tu);
  renderDrives();
  drawChart();
  document.title = active ? `↓ ${fmtSpeed(S.speed)} · SDM` : "SDM - S Download Manager";

  const lp = $("#linkpill");
  lp.classList.toggle("on", !!S.ext);
  $("#linksub").textContent = !S.cfg.intercept ? "Catching is off" : S.ext ? "Catching downloads" : "Extension not seen";

  // list
  const q = S.q.toLowerCase();
  const vis = items.filter((it) => matchStatus(it, S.filter) && (!S.cat || (it.category || "Other") === S.cat) &&
    (!q || (it.filename || "").toLowerCase().includes(q) || (it.url || "").toLowerCase().includes(q)));
  const list = $("#list");
  const visIds = new Set(vis.map((i) => i.id));
  for (const [id, c] of S.cards) if (!visIds.has(id) && c.el.parentNode) c.el.remove();
  let prev = null;
  for (const it of vis) {
    let c = S.cards.get(it.id);
    if (!c) { c = makeCard(it.id); S.cards.set(it.id, c); }
    const want = prev ? prev.nextSibling : list.firstChild;
    if (c.el !== want) list.insertBefore(c.el, want);
    updateCard(c, it);
    prev = c.el;
  }
  const empty = $("#empty");
  if (!vis.length && !empty) list.append(emptyState());
  if (vis.length && empty) empty.remove();

  maybeShowPending();
}

function renderDrives() {
  const box = $("#drives");
  if (!box) return;
  let drives = (S.drives || []).filter((d) => d.total > 0 || d.free > 0);
  if (!drives.length && S.free != null) drives = [{ drive: S.freeDrive || "", free: S.free }];
  const key = drives.map((d) => d.drive + ":" + Math.round(d.free / 1e8)).join("|") + "|" + S.freeDrive;
  if (box.dataset.k === key) return;
  box.dataset.k = key;
  box.replaceChildren(...drives.map((d) => {
    const named = /^[A-Za-z]:$/.test(d.drive);
    const label = h("span", { class: "label", text: named ? "Free on " + d.drive : "Free space" });
    if (named && d.drive.toUpperCase() === String(S.freeDrive).toUpperCase() && drives.length > 1) {
      label.append(h("i", { class: "dl-mark", title: "Your download folder is on this drive" }));
    }
    const b = h("b");
    const [v, u] = splitBytes(d.free);
    setDots(b, v, u);
    return h("div", { class: "stat", title: (d.root || d.drive) + (d.total ? " - " + fmtBytes(d.free) + " free of " + fmtBytes(d.total) : "") }, label, b);
  }));
}

function emptyState() {
  const cv = h("canvas");
  const box = h("div", { class: "empty", id: "empty" }, cv,
    h("h3", { text: items0() ? "NOTHING HERE" : "NOTHING YET" }),
    h("p", { text: items0() ? "No downloads match this view." : "Paste a link above, drop one on this window, or just download anything in your browser — SDM catches it at full speed." }));
  requestAnimationFrame(() => drawGlyph(cv));
  return box;
}
function items0() { return S.items.size > 0; }
function drawGlyph(cv) {
  const f = fitCanvas(cv); if (!f) return;
  const { ctx, w, h: H } = f; const P = pal();
  const art = [
    "....#....", "....#....", "....#....", "....#....", "#...#...#", ".#..#..#.", "..#.#.#..", "...###...", "....R....",
  ];
  const g = 10, ox = (w - 9 * g) / 2, oy = (H - 9 * g) / 2;
  for (let y = 0; y < 9; y++) for (let x = 0; x < 9; x++) {
    const ch = art[y][x];
    dot(ctx, ox + x * g + g / 2, oy + y * g + g / 2, 2.6, ch === "#" ? P.on : ch === "R" ? P.red : P.off);
  }
}

/* ---------------- menus ---------------- */
const isWeb = (it) => /^(https?|ftps?|magnet):/i.test(it.url || "");
function closeMenu() { const m = $(".menu"); if (m) m.remove(); }
function openMenu(anchor, it) {
  closeMenu();
  if (!it) return;
  const mk = (ic, label, fn, cls) => h("button", { class: cls || "", onclick: () => { closeMenu(); fn(); } }, icon(ic), label);
  const m = h("div", { class: "menu" },
    it.state === "done" ? mk("open", "Open file", () => act(it.id, "open")) : null,
    mk("folder", "Show in folder", () => act(it.id, "folder")),
    isWeb(it) ? mk("copy", it.kind === "torrent" && /^magnet:/i.test(it.url) ? "Copy magnet link" : "Copy link", () => copy(it.url)) : null,
    !["done", "pending"].includes(it.state) && it.priority <= 0 ? mk("bolt", "Download first", () => act(it.id, "first")) : null,
    !["done", "pending"].includes(it.state) && it.priority > 0 ? mk("clock", "Normal priority", () => act(it.id, "normal")) : null,
    it.state !== "done" && it.state !== "pending" && it.kind !== "torrent" ? mk("link", "Refresh link…", () => refreshLink(it)) : null,
    it.state !== "pending" ? mk("retry", "Restart from zero", () => act(it.id, "restart")) : null,
    /^(https?|ftps?):/i.test(it.url) ? mk("globe", "Open link in browser", () => act(it.id, "browser")) : null,
    it.seeding ? mk("pause", "Stop seeding (keep in list)", () => { act(it.id, "stop_seeding"); toast("Seeding stopped · files kept"); }) : null,
    h("hr"),
    mk("x", it.state === "done" ? (it.seeding ? "Remove from list · stop seeding" : "Remove from list") : "Cancel & remove", () => confirmRemove(it)),
    it.state === "done" ? mk("trash", "Delete file from disk", () => confirmRemove(it, true), "danger") : null);
  placeMenu(m, anchor);
}
function placeMenu(m, anchor) {
  document.body.append(m);
  const r = anchor.getBoundingClientRect();
  const mw = m.offsetWidth, mh = m.offsetHeight;
  m.style.left = Math.max(8, Math.min(window.innerWidth - mw - 8, r.right - mw)) + "px";
  m.style.top = (r.bottom + mh + 8 > window.innerHeight ? r.top - mh - 6 : r.bottom + 6) + "px";
}
document.addEventListener("click", (e) => { if (!e.target.closest(".menu")) closeMenu(); });

async function copy(text) {
  try { await navigator.clipboard.writeText(text); toast("Link copied"); }
  catch { toast("Could not copy"); }
}

/* ---------------- modals ---------------- */
function modal(content, { wide = false, onClose } = {}) {
  const box = h("div", { class: "modal" + (wide ? " wide" : "") }, ...content);
  const scrim = h("div", { class: "scrim" }, box);
  const m = { scrim, box, close: () => { scrim.remove(); S.modals = S.modals.filter((x) => x !== m); onClose && onClose(); setTimeout(maybeShowPending, 50); } };
  scrim.addEventListener("mousedown", (e) => { if (e.target === scrim) m.dismiss ? m.dismiss() : m.close(); });
  $("#layer").append(scrim);
  S.modals.push(m);
  return m;
}
function topModal() { return S.modals[S.modals.length - 1]; }
document.addEventListener("keydown", (e) => {
  const m = topModal();
  if (e.key === "Escape") { closeMenu(); if (m) { e.preventDefault(); m.dismiss ? m.dismiss() : m.close(); } }
  if (e.key === "Enter" && m && m.enter && !e.target.matches("textarea")) { e.preventDefault(); m.enter(); }
});

function confirmBox(title, text, okLabel, danger) {
  return new Promise((res) => {
    const m = modal([
      h("div", { class: "mhead" }, h("h3", { text: title })),
      h("p", { class: "help", style: { fontSize: "14px", margin: 0 }, text }),
      h("div", { class: "mfoot" }, h("span", { class: "spacer" }),
        h("button", { class: "btn ghost", onclick: () => { m.close(); res(false); } }, "Cancel"),
        h("button", { class: "btn " + (danger ? "red" : "solid"), onclick: () => { m.close(); res(true); } }, okLabel)),
    ]);
    m.enter = () => { m.close(); res(true); };
    m.dismiss = () => { m.close(); res(false); };
  });
}
async function confirmRemove(it, deleteFile) {
  if (deleteFile) {
    if (await confirmBox("DELETE FILE?", `“${it.filename}” will be deleted from your disk.`, "Delete", true)) act(it.id, "remove", { delete_file: true });
  } else if (it.state === "done" || await confirmBox("REMOVE?", "Cancel this download and remove it from the list? The partial file is deleted.", "Remove", true)) {
    act(it.id, "remove");
  }
}
function refreshLink(it) {
  const inp = h("input", { class: "inp mono", placeholder: "https://… (fresh link to the same file)", spellcheck: "false" });
  const go = async () => {
    try { await api("/api/action", { id: it.id, action: "refresh", url: inp.value.trim() }); m.close(); toast("Link refreshed — resuming"); }
    catch (e) { toast(e.message); }
  };
  const m = modal([
    h("div", { class: "mhead" }, h("h3", { text: "REFRESH LINK" })),
    h("p", { class: "help", style: { margin: 0 }, text: "Links from many sites expire. Get a new link to the same file and paste it here — SDM keeps everything already downloaded." }),
    inp,
    h("div", { class: "mfoot" }, h("span", { class: "spacer" }),
      h("button", { class: "btn ghost", onclick: () => m.close() }, "Cancel"),
      h("button", { class: "btn red", onclick: go }, icon("retry"), "Resume with new link")),
  ]);
  m.enter = go;
  setTimeout(() => inp.focus(), 30);
}

/* ---------------- save-location dialog ---------------- */
let pendingTimer = null;
function maybeShowPending() {
  if (S.modals.length || S.dialogFor) return;
  const pend = S.order.map((id) => S.items.get(id)).filter((i) => i && i.state === "pending" && !S.handled.has(i.id)).reverse();
  if (!pend.length) return;
  const it = pend[0];
  if (!it.probed && Date.now() / 1000 - it.created < 3) {
    clearTimeout(pendingTimer);
    pendingTimer = setTimeout(maybeShowPending, 300);
    return;
  }
  openSaveDialog(it.id);
}

async function refreshFree() {
  try { const r = await api("/api/diskfree", { path: S.cfg.download_dir }); S.free = r.free; S.freeDrive = r.drive || ""; } catch { /* ignore */ }
  try { const d = await api("/api/drives"); S.drives = d.drives || []; } catch { /* ignore */ }
  render();
}
setInterval(refreshFree, 20000);

function openSaveDialog(id, manual) {
  const it0 = S.items.get(id);
  if (!it0 || it0.state !== "pending" || S.handled.has(id)) return;
  if (S.dialogFor) return;
  S.dialogFor = id;
  const touched = { name: false, dir: false };
  const isWaiting = (x) => (S.items.get(x) || {}).state === "pending" && !S.handled.has(x);
  const pendingCount = () => S.order.filter(isWaiting).length;
  /** mark answered + update local copy at once, so the dialog can't reappear before the server's next tick */
  const settle = (x, state) => {
    S.handled.add(x);
    const v = S.items.get(x);
    if (!v) return;
    if (state === null) {
      S.gone.add(x);
      S.items.delete(x); S.order = S.order.filter((y) => y !== x);
      const c = S.cards.get(x); if (c) { c.el.remove(); S.cards.delete(x); }
    } else S.items.set(x, { ...v, state });
  };

  const tile = h("div", { class: "tile" }, h("span", { class: "tx" }));
  const nameIn = h("input", { class: "inp", spellcheck: "false", value: it0.filename || "", oninput: () => { touched.name = true; } });
  const nameLbl = h("span", { class: "label", text: "File name" });
  const badges = h("div", { class: "badges" });
  const note = h("div", { class: "help", style: { marginTop: "6px" } });
  const dirIn = h("input", { class: "inp mono", spellcheck: "false", value: it0.dir || S.cfg.download_dir || "", oninput: () => { touched.dir = true; checkFree(); fillChips(); } });
  const free = h("div", { class: "free" });
  const chips = h("div", { class: "chips" });
  const connVal = h("span", { class: "cval" });
  const conn = h("input", { type: "range", min: "1", max: "256", step: "1", value: String(it0.max_conns || S.cfg.connections || 16), oninput: () => setDots(connVal, conn.value) });
  setDots(connVal, conn.value);
  const connField = h("div", { class: "field" },
    h("div", { class: "srow" }, h("span", { class: "label", text: "Connections" }), h("span", { class: "free", text: "MORE = FASTER ON MOST SERVERS" })),
    h("div", { class: "conns" }, dslider(conn), connVal),
    h("div", { class: "chips" }, ...[["Balanced", 16], ["Turbo", 32], ["Max", 64], ["Insane", 128], ["Ultra", 256]].map(([l, v]) => [`${l} ${v}`, v]).map(([l, v]) =>
      h("button", { class: "chip", onclick: () => { conn.value = v; conn.dispatchEvent(new Event("input")); } }, l))));
  const remember = h("input", { type: "checkbox" });
  const dontask = h("input", { type: "checkbox" });
  const counter = h("span", { class: "counter" });
  const allBtn = h("button", { class: "btn hide", title: "Save every waiting download to this folder" }, "Start all");

  const setDir = (p) => { dirIn.value = p; touched.dir = true; checkFree(); };
  let freeSeq = 0;
  const checkFree = debounce(async () => {
    const seq = ++freeSeq;
    const path = dirIn.value.trim();
    if (!path) { free.textContent = ""; return; }
    try {
      const r = await api("/api/diskfree", { path });
      if (seq !== freeSeq || dirIn.value.trim() !== path) return;   // a newer check is on its way
      const it = S.items.get(id) || it0;
      if (r.free == null) { free.textContent = ""; return; }
      const on = r.drive ? ` ON ${r.drive}` : "";
      const short = it.size && r.free < it.size;
      free.className = "free" + (short ? " bad" : "");
      free.textContent = short ? `NOT ENOUGH SPACE${on} · ${fmtBytes(r.free)} FREE` : `${fmtBytes(r.free)} FREE${on}`;
    } catch { if (seq === freeSeq) free.textContent = ""; }
  }, 200);
  let drives = [];
  api("/api/drives").then((r) => { drives = r.drives || []; fillChips(); }).catch(() => {});

  function fillChips() {
    const it = S.items.get(id) || it0;
    const base = S.cfg.download_dir;
    const opts = [];
    const add = (label, path) => { if (path && !opts.some((o) => o[1] === path)) opts.push([label, path]); };
    if (S.cfg.categorize && it.category && it.category !== "Other") add(it.category, joinPath(base, it.category));
    add("Default", base);
    (S.cfg.recent_dirs || []).forEach((p) => add(p.split(/[\\/]/).filter(Boolean).pop() || p, p));
    if (S.platform.startsWith("win")) {
      drives.forEach((d) => add(`${d.drive} · ${fmtBytes(d.free, 0)} free`, joinPath(d.root, "SDM")));
    }
    const cur = dirIn.value.trim();
    const clear = (S.cfg.recent_dirs || []).length ? [h("button", { class: "chip", title: "Forget recently used folders", onclick: async () => { await setCfg({ recent_dirs: [] }); fillChips(); toast("Recent folders cleared"); } }, icon("x", "ico"), h("span", { text: "Clear recent" }))] : [];
    chips.replaceChildren(...clear.concat(opts.map(([l, p]) => {
      const dr = drives.find((d) => p.toUpperCase().startsWith(d.root.toUpperCase()));
      const tight = it.size && dr && dr.free < it.size;
      return h("button", { class: "chip" + (cur === p ? " on" : ""), style: tight ? { opacity: ".45" } : null,
        title: p + (tight ? " — not enough space" : ""), onclick: () => { setDir(p); fillChips(); } }, icon(/^[A-Z]: ·/.test(l) ? "drive" : "folder", "ico"), h("span", { text: l }));
    })));
    $$("svg", chips).forEach((s) => { s.style.width = "13px"; s.style.height = "13px"; });
  }
  function sync() {
    const it = S.items.get(id);
    if (!it || it.state !== "pending") { m.close(); return; }   // answered elsewhere / already started
    const ext = extOf(it.filename);
    const bt = it.kind === "torrent";
    tile.className = "tile" + (it.kind === "media" || bt ? " media" : "");
    tile.firstChild.textContent = bt ? "BT" : it.kind === "media" && !ext ? "MEDIA" : ext || "FILE";
    nameLbl.textContent = bt ? "Torrent name" : "File name";
    nameIn.readOnly = bt;                       // a torrent names its own files and folders
    nameIn.title = bt ? "Torrents keep the names chosen by their creator" : "";
    if (bt && !it.filename) nameIn.placeholder = "Name arrives with the torrent info…";
    if (!touched.name && document.activeElement !== nameIn) nameIn.value = it.filename || "";
    if (!touched.dir && document.activeElement !== dirIn && it.dir && dirIn.value !== it.dir) {
      dirIn.value = it.dir; checkFree(); fillChips();
    }
    const b = [];
    b.push(h("span", { class: "badge solid", text: it.size ? fmtBytes(it.size) : it.kind === "torrent" && it.probed ? "Size after metadata" : it.probed ? "Size unknown" : "Checking…" }));
    connField.classList.toggle("hide", it.kind === "torrent");
    if (it.kind === "torrent") b.push(h("span", { class: "badge red", text: /^magnet:/i.test(it.url) ? "Magnet · BitTorrent" : "BitTorrent" }));
    else if (it.kind === "media") b.push(h("span", { class: "badge red", text: S.ytdlp ? "Media grab" : "Needs yt-dlp" }));
    else if (it.probed) b.push(h("span", { class: "badge" + (it.ranges ? "" : " red"), text: it.ranges ? "Resumable · multi-connection" : "Single stream" }));
    if (it.mime) b.push(h("span", { class: "badge", text: it.mime.split("/").pop().slice(0, 18) }));
    const host = hostOf(it.url);
    if (host) b.push(h("span", { class: "badge", text: host }));
    if (it.source === "browser") b.push(h("span", { class: "badge", text: "From browser" }));
    badges.replaceChildren(...b);
    note.textContent = it.note || "";
    const n = pendingCount();
    counter.textContent = n > 1 ? `1 OF ${n} WAITING` : "";
    allBtn.classList.toggle("hide", n < 2);
    allBtn.textContent = `Start all ${n}`;
  }

  const start = async (later = false) => {
    const name = nameIn.value.trim();
    const dir = dirIn.value.trim();
    if (!dir) { toast("Choose a folder"); return; }
    try {
      await api("/api/confirm", {
        id, filename: touched.name && !nameIn.readOnly ? name : undefined, dir, connections: +conn.value,
        remember_dir: remember.checked, dont_ask: dontask.checked, later,
      });
      settle(id, later ? "paused" : "queued");
      if (remember.checked) { S.cfg.download_dir = dir; S.cfg.categorize = false; refreshFree(); }
      if (dontask.checked) S.cfg.ask_location = false;
      done();
      toast((later ? "Saved for later · " : "Downloading · ") + (name || "file"));
    } catch (e) { toast(e.message); }
  };
  const startAll = async () => {
    const dir = dirIn.value.trim();
    const ids = S.order.filter(isWaiting);
    for (const x of ids) {
      try {
        await api("/api/confirm", { id: x, dir, connections: +conn.value, filename: x === id && touched.name ? nameIn.value.trim() : undefined, remember_dir: remember.checked, dont_ask: dontask.checked });
        settle(x, "queued");
      } catch (e) { toast(e.message); }
    }
    done();
    toast(`Started ${ids.length} downloads`);
  };
  const cancel = async () => {
    try { await api("/api/action", { id, action: "cancel_pending" }); } catch { /* already gone */ }
    settle(id, null);
    done();
    toast("Cancelled");
  };
  allBtn.addEventListener("click", startAll);

  const m = modal([
    h("div", { class: "mhead" }, h("h3", { text: "NEW DOWNLOAD" }), h("span", { class: "spacer" }), counter,
      h("button", { class: "ibtn", title: "Cancel download", onclick: cancel }, icon("x"))),
    h("div", { class: "filecard" }, tile, h("div", { style: { minWidth: 0 } },
      nameLbl, h("div", { style: { height: "8px" } }), nameIn, badges, note)),
    h("div", { class: "field" },
      h("div", { class: "srow" }, h("span", { class: "label", text: "Save to" }), free),
      h("div", { class: "pathrow" }, dirIn,
        h("button", { class: "btn", onclick: () => folderBrowser(dirIn.value, (p) => { setDir(p); fillChips(); }) }, icon("folder"), "Browse"),
        h("button", { class: "btn ghost", title: "Use the Windows / system folder picker", onclick: async () => {
          try { const r = await api("/api/pickfolder", { initial: dirIn.value }, 0); if (r.path) { setDir(r.path); fillChips(); } }
          catch { toast("System picker not available — use Browse"); }
        } }, "System…")),
      chips),
    connField,
    h("div", { class: "checks" },
      h("label", { class: "toggle" }, remember, h("span", { class: "sw" }), "Make this my default download folder"),
      h("label", { class: "toggle" }, dontask, h("span", { class: "sw" }), "Don’t ask again — save straight to the default folder")),
    h("div", { class: "mfoot" },
      h("button", { class: "btn ghost", onclick: cancel }, "Cancel"),
      h("button", { class: "btn", title: "Add paused, start later", onclick: () => start(true) }, "Later"),
      h("span", { class: "spacer" }), allBtn,
      h("button", { class: "btn red", onclick: () => start(false) }, icon("bolt"), "Download")),
  ], { onClose: () => { S.dialogFor = null; clearInterval(syncT); } });
  m.enter = () => start(false);
  m.dismiss = () => start(true);   // Esc / outside click keeps it (paused) rather than losing it
  function done() { m.close(); }
  const syncT = setInterval(sync, 400);
  sync(); fillChips(); checkFree();
  setTimeout(() => {
    if (nameIn.readOnly) return;               // torrent: nothing to rename, keep focus on the actions
    const n = nameIn.value; nameIn.focus(); const dotI = n.lastIndexOf("."); nameIn.setSelectionRange(0, dotI > 0 ? dotI : n.length);
  }, 60);
  if (manual) S.expanded.delete(id);
}

/* ---------------- folder browser ---------------- */
function folderBrowser(start, onPick) {
  const pathIn = h("input", { class: "inp mono", spellcheck: "false" });
  const list = h("div", { class: "fb-list" });
  const places = h("div", { class: "fb-places" });
  let cur = start || "";
  let parent = null;
  async function go(p) {
    try {
      const r = await api("/api/listdir", { path: p });
      cur = r.path; parent = r.parent; pathIn.value = cur;
      places.replaceChildren(...r.places.map((pl) => h("button", { onclick: () => go(pl.path) }, icon(/^[A-Z]:/.test(pl.name) ? "drive" : "folder"), pl.name)));
      const rows = r.dirs.map((d) => h("button", { ondblclick: () => go(cur ? joinPath(cur, d) : d), onclick: () => go(cur ? joinPath(cur, d) : d) }, icon(cur ? "folder" : "drive"), h("span", { text: d })));
      list.replaceChildren(...(rows.length ? rows : [h("div", { class: "none", text: "No sub-folders here" })]));
    } catch (e) {
      // folder may not exist yet: walk up
      if (p && p.length > 3) { const up = p.replace(/[\\/][^\\/]*[\\/]?$/, ""); if (up && up !== p) return go(up); }
      toast(e.message);
    }
  }
  pathIn.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); e.stopPropagation(); go(pathIn.value.trim()); } });
  const newFolder = async () => {
    if (!cur) return toast("Open a drive or folder first");
    const name = prompt("New folder name");
    if (!name) return;
    try { const r = await api("/api/mkdir", { path: cur, name }); go(r.path); } catch (e) { toast(e.message); }
  };
  const m = modal([
    h("div", { class: "mhead" }, h("h3", { text: "CHOOSE FOLDER" }), h("span", { class: "spacer" }),
      h("button", { class: "ibtn", onclick: () => m.close() }, icon("x"))),
    h("div", { class: "fb" }, places, h("div", { class: "fb-main" },
      h("div", { class: "fb-path" },
        h("button", { class: "ibtn", title: "Up", onclick: () => { if (parent !== null && parent !== undefined) go(parent); } }, icon("up")),
        pathIn,
        h("button", { class: "ibtn", title: "New folder", onclick: newFolder }, icon("newfolder"))),
      list)),
    h("div", { class: "mfoot" }, h("span", { class: "help", text: "Click a folder to open it." }), h("span", { class: "spacer" }),
      h("button", { class: "btn ghost", onclick: () => m.close() }, "Cancel"),
      h("button", { class: "btn solid", onclick: () => { if (!cur) return toast("Pick a folder"); onPick(cur); m.close(); } }, icon("check"), "Use this folder")),
  ], { wide: true });
  m.enter = () => { if (cur) { onPick(cur); m.close(); } };
  go(start);
}

/* ---------------- settings ---------------- */
async function setCfg(patch) {
  Object.assign(S.cfg, patch);          // apply at once (theme, units...), engine saves it
  applyTheme(); render();
  try { const r = await api("/api/config", patch); S.cfg = r.config; applyTheme(); render(); if ("download_dir" in patch) refreshFree(); }
  catch (e) { toast(e.message, 4500); }
}
function tog(key, label) {
  const i = h("input", { type: "checkbox", onchange: () => setCfg({ [key]: i.checked }) });
  i.checked = !!S.cfg[key];
  return h("label", { class: "toggle" }, i, h("span", { class: "sw" }), label);
}
function slider(key, min, max, fmt = (v) => v) {
  const val = h("span", { class: "cval" });
  setDots(val, String(fmt(S.cfg[key])));
  const save = debounce((v) => setCfg({ [key]: v }), 250);
  const r = h("input", { type: "range", min, max, value: S.cfg[key], oninput: () => { setDots(val, String(fmt(+r.value))); save(+r.value); } });
  return h("div", { class: "conns" }, dslider(r), val);
}
function openSettings(tab) {
  const dirIn = h("input", { class: "inp mono", value: S.cfg.download_dir, spellcheck: "false" });
  const saveDir = debounce(() => setCfg({ download_dir: dirIn.value.trim() }), 600);
  dirIn.addEventListener("input", saveDir);
  const limIn = h("input", { class: "inp mono", type: "number", min: "0", value: S.cfg.speed_limit_kb || 0 });
  const limText = (kb) => !kb ? "Unlimited - full speed" : `${fmtBytes(kb * 1024)}/s  ≈ ${(kb * 1024 * 8 / 1e6).toFixed(kb * 1024 * 8 / 1e6 >= 10 ? 0 : 1)} Mbps`;
  const limNow = h("span", { class: "limnow", text: limText(S.cfg.speed_limit_kb || 0) });
  limIn.addEventListener("change", () => { setCfg({ speed_limit_kb: +limIn.value || 0 }); limNow.textContent = limText(+limIn.value || 0); });
  let connSlider;
  const mbps = (m) => Math.round(m * 125000 / 1024);   // Mbps -> KB/s
  const limChips = h("div", { class: "chips" }, ...[["Unlimited", 0], ["10 Mbps", mbps(10)], ["50 Mbps", mbps(50)], ["100 Mbps", mbps(100)], ["300 Mbps", mbps(300)], ["1 MB/s", 1024], ["10 MB/s", 10240]].map(([l, v]) =>
    h("button", { class: "chip", onclick: () => { limIn.value = v; setCfg({ speed_limit_kb: v }); limNow.textContent = limText(v); } }, l)));
  const minIn = h("input", { class: "inp mono", type: "number", min: "0", value: S.cfg.min_size_kb || 0 });
  minIn.addEventListener("change", () => setCfg({ min_size_kb: +minIn.value || 0 }));
  const skipIn = h("input", { class: "inp mono", style: { width: "100%" }, value: S.cfg.skip_sites || "", placeholder: "example.com, drive.google.com", spellcheck: "false" });
  skipIn.addEventListener("change", () => setCfg({ skip_sites: skipIn.value }));
  const theme = h("div", { class: "seg" }, ...["dark", "light"].map((t) =>
    h("button", { class: S.cfg.theme === t ? "on" : "", onclick: (e) => { $$("button", theme).forEach((b) => b.classList.remove("on")); e.currentTarget.classList.add("on"); setCfg({ theme: t }); } }, t)));
  const unit = h("div", { class: "seg" }, ...[["MBps", "MB/s"], ["Mbps", "Mbps"]].map(([k, l]) =>
    h("button", { class: (S.cfg.speed_unit || "MBps") === k ? "on" : "", onclick: (e) => { $$("button", unit).forEach((b) => b.classList.remove("on")); e.currentTarget.classList.add("on"); setCfg({ speed_unit: k }); } }, l)));
  const dns = h("div", { class: "seg" }, ...[["system", "System"], ["auto", "Auto"], ["doh", "Secure"]].map(([k, l]) =>
    h("button", { class: (S.cfg.dns_mode || "auto") === k ? "on" : "", onclick: (e) => { $$("button", dns).forEach((b) => b.classList.remove("on")); e.currentTarget.classList.add("on"); setCfg({ dns_mode: k }); } }, l)));
  const proxyIn = h("input", { class: "inp mono", style: { width: "100%" }, value: S.cfg.proxy || "", placeholder: "Empty = system proxy · e.g. http://127.0.0.1:8080", spellcheck: "false" });
  proxyIn.addEventListener("change", () => setCfg({ proxy: proxyIn.value.trim() }));
  const st = (ok, txt) => h("div", { class: "statusline " + (ok ? "ok" : "bad") }, h("i"), txt);

  const box = (cls, ...kids) => h("div", { class: "sbox" + (cls ? " " + cls : "") }, ...kids);
  const isWin = S.platform.startsWith("win");
  const TABS = [
    ["general", "General", "folder", [
      box("full",
        h("span", { class: "label", text: "Saving" }),
        h("div", { class: "pathrow" }, dirIn,
          h("button", { class: "btn", onclick: () => folderBrowser(dirIn.value, (p) => { dirIn.value = p; setCfg({ download_dir: p }); }) }, icon("folder"), "Browse")),
        tog("ask_location", "Ask where to save every download"),
        tog("categorize", "Sort into Video / Music / Archives … sub-folders"),
        h("div", { class: "srow" }, h("span", { class: "help", text: "Recent folders: " + ((S.cfg.recent_dirs || []).length || "none") }),
          h("button", { class: "btn sm", onclick: async (e) => { const lbl = e.currentTarget.previousSibling; await setCfg({ recent_dirs: [] }); if (lbl) lbl.textContent = "Recent folders: none"; toast("Recent folders cleared"); } }, icon("x"), "Clear recent"))),
      box("full",
        h("span", { class: "label", text: "Startup & background" }),
        tog("start_with_windows", "Start SDM automatically when I sign in to Windows"),
        tog("tray", "Keep running in the system tray when I close the window (quit from the tray icon)"),
        tog("wake_on_catch", "Let the browser extension start SDM when it is closed"),
        tog("keep_awake", "Keep the PC awake while downloads are running (screen can still turn off)"),
        st(S.tray, S.tray ? "Tray icon active — right-click it for Open / Pause all / Quit" : "Tray icon not active (Windows only; it installs itself on first start)"))]],
    ["speed", "Speed", "bolt", [
      box("",
        h("span", { class: "label", text: "Connections per file" }), connSlider = slider("connections", 1, 256),
        h("div", { class: "chips" }, ...[["Balanced", 16], ["Turbo", 32], ["Max", 64], ["Insane", 128], ["Ultra", 256]].map(([l, v]) => [`${l} ${v}`, v]).map(([l, v]) =>
          h("button", { class: "chip", onclick: () => { const r = $("input", connSlider); r.value = v; r.dispatchEvent(new Event("input")); } }, l))),
        h("span", { class: "help", text: "More connections = more of your line used, up to 256. Past ~64 most servers gain little and some refuse — SDM backs off automatically, then tries again." })),
      box("",
        h("span", { class: "label", text: "Downloads at once" }), slider("max_active", 1, 10),
        h("span", { class: "help", text: "Others wait in the queue and start automatically. ⋯ > Download first jumps the queue." })),
      box("full",
        h("div", { class: "srow" }, h("span", { class: "label", text: "Speed limit (KB/s)" }), limIn), limNow, limChips)]],
    ["network", "Network", "globe", [
      box("full",
        h("span", { class: "label", text: "DNS" }),
        h("div", { class: "srow" }, h("span", { class: "help", text: "Auto falls back to secure DNS (Cloudflare / Google) when yours fails. Secure always bypasses your ISP's DNS." }), dns)),
      box("full",
        h("span", { class: "label", text: "Proxy & certificates" }),
        proxyIn,
        tog("allow_insecure", "Allow servers with invalid / self-signed certificates (less safe)"))]],
    ["browser", "Browser", "link", [
      box("full",
        h("span", { class: "label", text: "Browser extension" }),
        st(S.ext, S.ext ? "Extension connected" : "Extension not seen yet — chrome://extensions > Developer mode > Load unpacked > the “extension” folder"),
        tog("intercept", "Catch browser downloads and send them to SDM"),
        tog("open_window_on_catch", "Open this window when a download is caught"),
        h("div", { class: "srow" }, h("span", { class: "help", text: "Leave files smaller than (KB) to the browser" }), minIn),
        h("span", { class: "help", text: "Never catch downloads from these sites:" }), skipIn)]],
    ["torrents", "Torrents", "magnet", [
      box("full",
        h("span", { class: "label", text: "Torrents & magnet links" }),
        st(S.torrents || isWin, S.torrents ? "Torrent engine ready — magnet links and .torrent files" : isWin ? "Torrent engine installs itself (5 MB, verified) the first time you add a torrent" : "Torrent engine missing — install aria2 (apt / brew install aria2)"),
        tog("seed_after", "Keep sharing (seeding) finished torrents until I upload as much as I downloaded"),
        isWin ? tog("handle_magnets", "Open magnet links and .torrent files with SDM (double-click / browser)") : null,
        h("span", { class: "help", text: "Add a .torrent file with the .TORRENT button next to the link bar, by dragging it onto this window, or by pasting a copied file (Ctrl+V)." }),
        h("span", { class: "help", text: "Speed comes from DHT, peer exchange, local discovery and a public tracker list refreshed daily (up to 200 peers per torrent). Popular torrents with many seeds download fastest." }))]],
    ["about", "Look & about", "sun", [
      box("",
        h("span", { class: "label", text: "Look" }),
        h("div", { class: "srow" }, h("span", { class: "help", text: "Theme" }), theme),
        h("div", { class: "srow" }, h("span", { class: "help", text: "Speed unit" }), unit),
        tog("sound", "Completion sound")),
      box("",
        h("span", { class: "label", text: "Engine" }),
        st(true, "SDM - S Download Manager " + S.version),
        st(S.ytdlp, S.ytdlp ? "yt-dlp ready — video pages & streams" : "yt-dlp missing — installs itself in the background"),
        st(S.ffmpeg, S.ffmpeg ? "ffmpeg ready — best quality merges" : "ffmpeg missing — media limited to single-file formats"),
        st(S.torrents || isWin, "Torrents: " + (S.torrents ? "ready" : "installs on first use")))]],
  ];
  const cur = TABS.some((t) => t[0] === tab) ? tab : (S.settingsTab || "general");
  const panel = h("div", { class: "spanel" });
  const nav = h("div", { class: "snav" });
  const show = (id) => {
    S.settingsTab = id;
    const t = TABS.find((x) => x[0] === id);
    panel.replaceChildren(h("div", { class: "sgrid" }, ...t[3]));
    panel.scrollTop = 0;
    $$("button", nav).forEach((b) => b.classList.toggle("on", b.dataset.tab === id));
  };
  TABS.forEach(([id, label, ic]) => nav.append(h("button", { "data-tab": id, onclick: () => show(id) }, icon(ic), label)));
  const m = modal([
    h("div", { class: "mhead" }, h("h3", { text: "SETTINGS" }), h("span", { class: "spacer" }),
      h("button", { class: "ibtn", title: "Close", onclick: () => m.close() }, icon("x"))),
    h("div", { class: "stabs" }, nav, panel),
  ], { wide: true });
  m.box.classList.add("settings");
  show(cur);
}

/* ---------------- adding ---------------- */
const URL_RE = /(?:\b(?:https?|ftps?):\/\/|\bmagnet:\?)[^\s"'<>`]+/gi;
async function addText(text) {
  const urls = [...new Set((text.match(URL_RE) || []).map((u) => u.replace(/[),.;\]]+$/, "")))];
  if (!urls.length) { toast("No link found"); return false; }
  try {
    const r = await api("/api/add", { urls, source: "ui" });
    toast(r.duplicate ? "Already downloading that link" : r.ids.length > 1 ? `Added ${r.ids.length} links` : "Link added");
    if (S.filter !== "all") { S.filter = "all"; render(); }
    return true;
  } catch (e) { toast(e.message); return false; }
}

/** .torrent files from this computer: the Open button, drag & drop, or Ctrl+V of a copied file */
const isTorrentFile = (f) => /\.torrent$/i.test(f.name) || f.type === "application/x-bittorrent";
async function addTorrentFiles(files) {
  const all = [...files];
  const list = all.filter(isTorrentFile);
  if (!list.length) { toast(all.length ? "Only .torrent files can be added here — for other files paste their link" : "No file chosen"); return false; }
  let added = 0;
  for (const f of list) {
    if (f.size > 64 * 1024 * 1024) { toast(`${f.name} is too large to be a .torrent file`, 4500); continue; }
    try {
      const r = await api("/api/add_torrent?name=" + encodeURIComponent(f.name), f, 30000);
      added++;
      toast(r.duplicate ? `Already in the list · ${r.name}` : `Torrent added · ${r.name}`);
    } catch (e) { toast(e.message, 4500); }
  }
  if (added && S.filter !== "all") { S.filter = "all"; render(); }
  return added > 0;
}

/* ---------------- theme ---------------- */
function applyTheme() {
  const t = S.cfg.theme === "light" ? "light" : "dark";
  if (document.documentElement.dataset.theme !== t) {
    document.documentElement.dataset.theme = t;
    PAL = null;
    for (const c of S.cards.values()) c.sig = "";
    const e = $("#empty"); if (e) e.remove();
  }
  $("#btn-theme").replaceChildren(icon(t === "dark" ? "sun" : "moon"));
}

/* ---------------- wiring ---------------- */
function init() {
  buildNav();
  $("#addform").addEventListener("submit", async (e) => {
    e.preventDefault();
    const inp = $("#addurl");
    if (!inp.value.trim()) { inp.focus(); return; }
    if (await addText(inp.value)) inp.value = "";
  });
  $("#q").addEventListener("input", (e) => { S.q = e.target.value; render(); });
  const tfile = $("#tfile");
  $("#btn-tfile").addEventListener("click", () => tfile.click());
  tfile.addEventListener("change", async () => { if (tfile.files.length) await addTorrentFiles(tfile.files); tfile.value = ""; });
  $("#btn-settings").addEventListener("click", () => openSettings());
  $("#linkpill").addEventListener("click", () => openSettings("browser"));
  $("#btn-settings2").addEventListener("click", () => openSettings());
  $("#btn-theme").addEventListener("click", () => setCfg({ theme: S.cfg.theme === "light" ? "dark" : "light" }));
  const bulk = (action, from, to) => {
    for (const [id, v] of S.items) {
      if (!from.includes(v.state)) continue;
      if (to) S.items.set(id, { ...v, state: to, speed: 0 }); else dropLocal(id);
    }
    render();
    api("/api/action", { action }).catch((e) => toast(e.message, 4500));
  };
  $("#btn-pauseall").addEventListener("click", () => bulk("pause_all", ["downloading", "queued"], "paused"));
  $("#btn-resumeall").addEventListener("click", () => S.filter === "error"
    ? bulk("retry_failed", ["error"], "queued") : bulk("resume_all", ["paused", "error"], "queued"));
  $("#btn-clear").addEventListener("click", (e) => {
    e.stopPropagation();
    if ($(".menu.clearmenu")) { closeMenu(); return; }
    closeMenu();
    const n = (st) => [...S.items.values()].filter((v) => st.includes(v.state)).length;
    const opt = (ic, label, action, states) => {
      const c = n(states);
      return h("button", { disabled: !c, onclick: () => { closeMenu(); bulk(action, states, null); toast(`Cleared ${c} from the list · files kept`); } },
        icon(ic), h("span", { class: "mlabel", text: label }), h("span", { class: "mcount", text: String(c) }));
    };
    const m = h("div", { class: "menu clearmenu" },
      opt("check", "Clear done", "clear_done", ["done"]),
      opt("alert", "Clear failed", "clear_failed", ["error"]),
      opt("sweep", "Clear done + failed", "clear_finished", ["done", "error"]),
      h("hr"),
      h("div", { class: "mnote", text: "Removes them from this list only. Downloaded files stay on your disk; finished torrents stop seeding." }));
    placeMenu(m, e.currentTarget);
  });
  $("#btn-quit").addEventListener("click", async () => {
    if (!await confirmBox("QUIT SDM?", "The engine stops. Running downloads pause and continue automatically next time you open SDM.", "Quit", true)) return;
    S.quit = true;
    try { await api("/api/shutdown", {}); } catch { /* engine exits */ }
    document.body.replaceChildren(h("div", { class: "empty", style: { height: "100vh", justifyContent: "center" } },
      h("h3", { text: "ENGINE STOPPED" }), h("p", { text: "You can close this window. Open SDM again any time." })));
  });

  document.addEventListener("paste", (e) => {
    const cd = e.clipboardData || window.clipboardData;
    if (cd && cd.files && [...cd.files].some(isTorrentFile)) { e.preventDefault(); addTorrentFiles(cd.files); return; }
    if (e.target.closest("input, textarea")) return;
    const text = cd.getData("text");
    if (text) { e.preventDefault(); addText(text); }
  });
  document.addEventListener("keydown", (e) => {
    if (e.target.closest("input, textarea") || S.modals.length) return;
    if (e.key === "/" || (e.ctrlKey && e.key.toLowerCase() === "f")) { e.preventDefault(); $(e.key === "/" ? "#addurl" : "#q").focus(); }
  });
  let dragDepth = 0;
  const showDrop = (on) => {
    let d = $("#drop");
    if (on && !d) document.body.append(h("div", { class: "drop", id: "drop", text: "DROP LINK OR .TORRENT FILE" }));
    if (!on && d) d.remove();
  };
  document.addEventListener("dragenter", (e) => { e.preventDefault(); dragDepth++; showDrop(true); });
  document.addEventListener("dragleave", () => { if (--dragDepth <= 0) { dragDepth = 0; showDrop(false); } });
  document.addEventListener("dragover", (e) => e.preventDefault());
  document.addEventListener("drop", (e) => {
    e.preventDefault(); dragDepth = 0; showDrop(false);
    if (e.dataTransfer.files && e.dataTransfer.files.length) { addTorrentFiles(e.dataTransfer.files); return; }
    const t = e.dataTransfer.getData("text/uri-list") || e.dataTransfer.getData("text/plain");
    if (t) addText(t);
  });
  document.addEventListener("pointerdown", () => {
    if ("Notification" in window && Notification.permission === "default") Notification.requestPermission().catch(() => {});
  }, { once: true });
  window.addEventListener("resize", debounce(render, 80));
  // smooth animation for unknown-size bars
  setInterval(() => {
    for (const [id, c] of S.cards) {
      const it = S.items.get(id);
      if (it && it.state === "downloading" && !it.size && c.el.isConnected) drawBar(c.bar, it, 1);
    }
  }, 120);
  connect();
}
init();
