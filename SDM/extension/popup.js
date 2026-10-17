"use strict";
const API = "http://127.0.0.1:6880";
const $ = (s) => document.querySelector(s);
let tab = null;
let online = false;

function fmt(n) {
  if (!n) return "";
  const u = ["B", "KB", "MB", "GB"]; let i = 0;
  while (n >= 1024 && i < 3) { n /= 1024; i++; }
  return n.toFixed(i ? 1 : 0) + " " + u[i];
}
function flash(t) {
  const d = document.createElement("div");
  d.className = "flash"; d.textContent = t;
  document.body.append(d);
  setTimeout(() => d.remove(), 1800);
}
const bg = (msg) => new Promise((res) => chrome.runtime.sendMessage(msg, res));

async function ping() {
  try {
    const r = await fetch(API + "/api/ping?src=ext", { signal: AbortSignal.timeout(1200), cache: "no-store" });
    return r.ok ? r.json() : null;
  } catch { return null; }
}

function setStatus(app) {
  online = !!app;
  const s = $("#status");
  s.className = "status " + (online ? "on" : "off");
  s.querySelector("span").textContent = online ? "Online" : "Offline";
  $("#offline").classList.toggle("hidden", online);
  if (app) $("#enabled").checked = !!app.intercept;
}

async function refresh() {
  const st = await chrome.storage.local.get({ enabled: true, pauseUntil: 0, caught: 0 });
  $("#enabled").checked = st.enabled;
  $("#caught").textContent = `${st.caught} caught`;
  const left = Math.ceil((st.pauseUntil - Date.now()) / 1000);
  $("#catchsub").textContent = left > 0 ? `Paused — browser handles downloads for ${left}s` :
    st.enabled ? "Every browser download goes to SDM" : "Off — the browser downloads normally";
  setStatus(await ping());
}

async function renderMedia() {
  const list = $("#list");
  if (!tab) return;
  const k = "media_" + tab.id;
  const items = (await chrome.storage.session.get(k))[k] || [];
  if (!items.length) return;
  list.replaceChildren(...items.map((m) => {
    const row = document.createElement("div");
    row.className = "m";
    const t = document.createElement("span");
    t.className = "t " + m.type.toLowerCase(); t.textContent = m.type;
    const n = document.createElement("div");
    n.className = "n"; n.title = m.url; n.textContent = m.name || m.url;
    const s = document.createElement("span"); s.className = "s";
    s.textContent = (m.size ? fmt(m.size) + " · " : "") + new URL(m.url).hostname;
    n.append(s);
    const b = document.createElement("button");
    b.className = "btn sm"; b.textContent = "Grab";
    b.onclick = async () => {
      b.disabled = true;
      const kind = m.type === "HLS" || m.type === "DASH" ? "media" : undefined;
      const r = await bg({ cmd: "grab", url: m.url, kind, page: tab.url, incognito: tab.incognito });
      flash(r && r.ok ? "Sent to SDM" : (r && r.error) || "Failed");
      b.disabled = false;
    };
    row.append(t, n, b);
    return row;
  }));
}

$("#enabled").addEventListener("change", async (e) => {
  const on = e.target.checked;
  await chrome.storage.local.set({ enabled: on, pauseUntil: 0 });
  try {
    await fetch(API + "/api/config", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ intercept: on }) });
  } catch { /* offline: stored locally */ }
  refresh();
});
$("#skip").addEventListener("click", async () => {
  await chrome.storage.local.set({ pauseUntil: Date.now() + 60000 });
  flash("Browser handles the next 60s");
  refresh();
});
$("#open").addEventListener("click", async () => {
  const r = await bg({ cmd: "open" });
  if (r && r.ok) window.close(); else flash((r && r.error) || "Could not open");
});
$("#start").addEventListener("click", async () => {
  $("#start").textContent = "Starting…";
  const app = await bg({ cmd: "ensure" });
  $("#start").textContent = "Start SDM";
  if (!app) flash("Run install.bat once, or open SDM from the desktop");
  refresh();
});
$("#grabpage").addEventListener("click", async () => {
  if (!tab || !/^https?:/.test(tab.url || "")) return flash("Not a web page");
  const r = await bg({ cmd: "grab", url: tab.url, kind: "media", page: tab.url, incognito: tab.incognito });
  flash(r && r.ok ? "Grabbing video…" : (r && r.error) || "Failed");
});
$("#addform").addEventListener("submit", async (e) => {
  e.preventDefault();
  const v = $("#addurl").value.trim();
  if (!/^((https?|ftps?):\/\/|magnet:\?)/i.test(v)) return flash("Paste an http(s), ftp or magnet link");
  const r = await bg({ cmd: "grab", url: v, page: tab && tab.url, incognito: tab && tab.incognito });
  if (r && r.ok) { $("#addurl").value = ""; flash("Sent to SDM"); } else flash((r && r.error) || "Failed");
});

(async () => {
  [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  refresh();
  renderMedia();
  setInterval(refresh, 2000);
  setInterval(renderMedia, 1500);
})();
