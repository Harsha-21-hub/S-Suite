// S Log - web version (laptops). Uses the same Firebase account and data as the Android app.
import * as api from "./db.js";
import {
  parseTimeList, to12h, ymd, today, addDays, sameDay, daysInMonth, parseAny, escapeHtml, pad,
  toCsv, toSlog, safeFileName, streakStats, completeDays, completeWholeDays, leftFor, initialsOf,
  planFor, myRecords, dayProgress, milestoneFloor, celebrationLine, GLYPHS, DIGITS, dotSvg
} from "./utils.js";

const $app = document.getElementById("app");
const $modal = document.getElementById("modal-root");
const $toast = document.getElementById("toast");
const $csv = document.getElementById("csv-input");

const MONTHS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];

const S = {
  user: null,                // {uid, email, name}
  logs: [],
  records: {},               // logId -> [record]
  month: { y: new Date().getFullYear(), m: new Date().getMonth() },
  selected: today(),
  collapsed: true,           // calendar shows only the current week by default
  windowStart: "",
  unsubLogs: null,
  unsubRecs: new Map(),
  signingUp: false,          // ignore auth changes while sign-up registers the PIN
  storedDayBest: null,       // best whole-day streak saved on my user entry (null = not loaded yet)
  celebratedDay: null,       // highest whole-day milestone celebrated (null = never saved)
  unsubProfile: null,
  // ---- animation bookkeeping (the page is re-rendered on every change)
  celebratedLocal: {},       // milestones celebrated this session (before the database echoes them)
  celebrations: [],          // queue of {days, labels}
  removing: new Set(),       // logs animating out before the delete is sent
  known: new Set(),          // logs already shown (only brand-new ones animate in)
  knownReady: false,
  dateDir: 0,                // -1 / 1: list slides in after picking another day
  monthDir: 0,               // -1 / 1: calendar slides after changing month
  collapseFrom: null,        // previous collapsed state while the weeks animate
  prevFull: null,            // {monthKey, set of full days} -> pop the new ✓ marks
  lastNums: {},              // streak numbers last shown -> roll when they change
  justTicked: null,          // "logId|time" -> pop that tick
  fabShown: false,
  showing: null              // celebration on screen
};

// ------------------------------------------------------------------ helpers
const h = escapeHtml;
let toastTimer;
function toast(msg) {
  $toast.textContent = msg;
  $toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => ($toast.hidden = true), 3500);
}
const isActive = (log) => (log.userState?.[S.user.uid]?.active ?? true);
const orderOf = (log) => (log.userState?.[S.user.uid]?.order ?? Number.MAX_SAFE_INTEGER);
const isOwner = (log) => log.ownerUid === S.user.uid;
const isShared = (log) => log.memberEmails.length > 1;
const isEditableDate = (d) => !(d < addDays(today(), -2) || d > today());

function friendlyError(e) {
  const code = e && e.code ? e.code : "";
  if (code.includes("wrong-password") || code.includes("invalid-credential")) return "Wrong email or password.";
  if (code.includes("user-not-found")) return "No account found for this email.";
  if (code.includes("email-already-in-use")) return "An account with this email already exists. Log in instead.";
  if (code.includes("weak-password")) return "Password is too weak (min 6 characters).";
  if (code.includes("invalid-email")) return "That email address looks wrong.";
  if (code.includes("too-many-requests")) return "Too many attempts. Wait a minute and try again.";
  if (code === "wrong-pin") return "Wrong registration PIN.";
  if (code.includes("permission-denied")) return "Not allowed. Try signing out and in again.";
  return (e && e.message) || "Something went wrong. Check your connection.";
}

// Password / PIN fields: SHOW / HIDE button inside the field (works for every .pw on any screen)
const PW_TOGGLE = `<button type="button" class="pw-toggle" tabindex="-1">SHOW</button>`;
document.addEventListener("click", (e) => {
  const btn = e.target.closest(".pw-toggle");
  if (!btn) return;
  const input = btn.parentElement.querySelector("input");
  const show = input.type === "password";
  input.type = show ? "text" : "password";
  btn.textContent = show ? "HIDE" : "SHOW";
  input.focus();
});

// ------------------------------------------------------------------- modal
function openModal(html, onMount) {
  $modal.innerHTML = `<div class="overlay"><div class="modal">${html}</div></div>`;
  const overlay = $modal.firstElementChild;
  overlay.addEventListener("mousedown", (e) => { if (e.target === overlay) closeModal(); });
  onMount && onMount($modal.querySelector(".modal"));
}
function closeModal() { $modal.innerHTML = ""; }
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeModal(); });

function confirmBox(title, message) {
  return new Promise((resolve) => {
    openModal(
      `<h2>${h(title)}</h2><p>${h(message)}</p>
       <div class="actions"><button class="link" data-no>NO</button><button class="btn" data-yes>YES</button></div>`,
      (m) => {
        m.querySelector("[data-yes]").onclick = () => { closeModal(); resolve(true); };
        m.querySelector("[data-no]").onclick = () => { closeModal(); resolve(false); };
      }
    );
  });
}

// --------------------------------------------------------------- auth UI
function renderSetupNeeded() {
  $app.innerHTML = `
    <div class="auth"><div class="auth-box setup">
      <p class="brand" style="text-align:center">S&nbsp;&nbsp;LOG</p>
      <h2 style="text-align:center">SETUP NEEDED</h2>
      <p>Paste your Firebase web config into <code>web/firebase-config.js</code>, then reload.
      SETUP.md (in the project folder) walks you through it.</p>
    </div></div>`;
}

function renderAuth(mode = "login", msg = "") {
  const signup = mode === "signup";
  $app.innerHTML = `
    <div class="auth"><form class="auth-box" autocomplete="on">
      <p class="brand">S&nbsp;&nbsp;LOG</p>
      <h2>${signup ? "CREATE ACCOUNT" : "LOG IN"}</h2>
      ${signup ? `<input class="field" name="name" placeholder="Name" autocomplete="name" />` : ""}
      <input class="field" name="email" type="email" placeholder="Email" autocomplete="email" />
      <div class="pw"><input class="field" name="password" type="password" placeholder="Password"
             autocomplete="${signup ? "new-password" : "current-password"}" />${PW_TOGGLE}</div>
      ${signup ? `<div class="pw"><input class="field" name="pin" type="password" inputmode="numeric" placeholder="Registration PIN" autocomplete="off" />${PW_TOGGLE}</div>
      <div class="hint" style="margin:-6px 0 10px">Ask the owner of this S Log for the PIN.</div>` : ""}
      <div class="msg">${h(msg)}</div>
      <button class="btn block" type="submit">${signup ? "SIGN UP" : "LOG IN"}</button>
      ${signup ? "" : `<button class="link" type="button" data-forgot>Forgot password?</button><br/>`}
      <button class="link white" type="button" data-switch>
        ${signup ? "Already have an account? Log in" : "New here? Create an account"}</button>
    </form></div>`;
  const form = $app.querySelector("form");
  const $msg = form.querySelector(".msg");
  const btn = form.querySelector("[type=submit]");
  form.querySelector("[data-switch]").onclick = () => renderAuth(signup ? "login" : "signup");
  const forgot = form.querySelector("[data-forgot]");
  if (forgot) forgot.onclick = async () => {
    const email = form.email.value.trim();
    if (!email) { $msg.textContent = "Type your email above first, then tap Forgot password."; return; }
    try { await api.resetPassword(email); $msg.textContent = `Password reset link sent to ${email}.`; }
    catch (e) { $msg.textContent = friendlyError(e); }
  };
  form.onsubmit = async (e) => {
    e.preventDefault();
    const name = signup ? form.name.value.trim() : "";
    const email = form.email.value.trim();
    const pw = form.password.value;
    if (signup && !name) { $msg.textContent = "Enter your name."; return; }
    if (!email || !pw) { $msg.textContent = "Enter your email and password."; return; }
    if (signup && pw.length < 6) { $msg.textContent = "Password must be at least 6 characters."; return; }
    const pin = signup ? form.pin.value.trim() : "";
    if (signup && !pin) { $msg.textContent = "Enter the registration PIN."; return; }
    btn.disabled = true;
    $msg.textContent = "";
    try {
      if (signup) {
        S.signingUp = true;
        try {
          await api.signUp(name, email, pw, pin);
        } finally {
          S.signingUp = false;
        }
        const u = api.currentAuth();
        if (u) startMain(u); // registered with the PIN -> straight in
      } else {
        await api.signIn(email, pw);
      }
    } catch (err) {
      $msg.textContent = friendlyError(err);
    } finally {
      btn.disabled = false;
    }
  };
}

// ------------------------------------------------------------ data wiring
function computeWindowStart() {
  const recent = addDays(today(), -180);
  const monthStart = new Date(S.month.y, S.month.m, 1);
  return ymd(monthStart < recent ? monthStart : recent);
}

function stopListeners() {
  S.unsubLogs && S.unsubLogs();
  S.unsubLogs = null;
  S.unsubProfile && S.unsubProfile();
  S.unsubProfile = null;
  S.storedDayBest = null;
  S.celebratedDay = null;
  S.celebratedLocal = {};
  S.celebrations = [];
  S.known = new Set();
  S.knownReady = false;
  S.prevFull = null;
  S.lastNums = {};
  hideCelebration(true);
  S.unsubRecs.forEach((u) => u());
  S.unsubRecs.clear();
  recordRetries.clear();
  S.logs = [];
  S.records = {};
}

function syncRecordListeners() {
  const ids = new Set(S.logs.map((l) => l.id));
  for (const [id, unsub] of S.unsubRecs) {
    if (!ids.has(id)) { unsub(); S.unsubRecs.delete(id); delete S.records[id]; }
  }
  for (const id of ids) if (!S.unsubRecs.has(id)) attachRecords(id);
}

// A brand-new log isn't on the server for a moment, so the server refuses to show its ticks and
// Firestore stops that listener. Listen again shortly (the ticks themselves are saved fine).
const recordRetries = new Map();
function attachRecords(id) {
  const unsub = api.listenRecords(id, S.windowStart, (recs) => {
    S.records[id] = recs;
    renderMain();
  }, () => {
    if (S.unsubRecs.get(id) === unsub) S.unsubRecs.delete(id);
    const n = (recordRetries.get(id) || 0) + 1;
    recordRetries.set(id, n);
    if (n > 8) return;
    setTimeout(() => {
      if (S.user && !S.unsubRecs.has(id) && S.logs.some((l) => l.id === id)) attachRecords(id);
    }, Math.min(800 * n, 6000));
  });
  S.unsubRecs.set(id, unsub);
}

function startMain(user) {
  if (S.user && S.user.uid === user.uid && S.unsubLogs) return;
  stopListeners();
  S.user = user;
  S.windowStart = computeWindowStart();
  renderMain();
  S.unsubProfile = api.listenProfile(user.uid, (best, celebrated) => {
    S.celebratedDay = celebrated;
    S.storedDayBest = best;
    renderMain();
  });
  S.unsubLogs = api.listenLogs(user.email, (logs) => {
    S.logs = logs.sort((a, b) => orderOf(a) - orderOf(b) || a.createdAt - b.createdAt);
    syncRecordListeners();
    renderMain();
  });
}

function setMonth(y, m) {
  const d = new Date(y, m, 1);
  const before = S.month.y * 12 + S.month.m;
  S.monthDir = Math.sign(d.getFullYear() * 12 + d.getMonth() - before);
  S.month = { y: d.getFullYear(), m: d.getMonth() };
  const needed = computeWindowStart();
  if (needed < S.windowStart) {
    S.windowStart = needed;
    S.unsubRecs.forEach((u) => u());
    S.unsubRecs.clear();
    syncRecordListeners();
  }
  renderMain();
}

// ------------------------------------------------------------------ stats
function computeStats() {
  // Every day is judged with the logs and times that existed THAT day (planFor), so adding a
  // log or a time never changes earlier days. Half-done days don't count.
  const uid = S.user.uid;
  const todayKey = ymd(today());
  const prefix = `${S.month.y}-${pad(S.month.m + 1)}`;
  const plans = {};
  for (const log of S.logs) plans[log.id] = planFor(log, uid, S.records[log.id]);

  const reached = []; // [milestone, label]
  const logStats = {};
  for (const log of S.logs) {
    const saved = log.userState?.[uid]?.best || 0;
    const st = streakStats(completeDays(plans[log.id], S.records[log.id], uid), prefix, saved);
    logStats[log.id] = st;
    if (st.best > saved) api.saveLogBest(log.id, uid, st.best); // remember new best (only goes up)
    const stored = log.userState?.[uid]?.celebrated;
    const base = Math.max(S.celebratedLocal[log.id] || 0, typeof stored === "number" ? stored : milestoneFloor(saved));
    const m = milestoneFloor(st.best);
    if (m > base) {
      S.celebratedLocal[log.id] = m;
      api.saveLogCelebrated(log.id, uid, m);
      reached.push([m, log.name.toUpperCase()]);
    }
  }
  const planList = S.logs.map((l) => plans[l.id]);
  const storedBest = S.storedDayBest; // read before saving a new best (the save can echo back at once)
  const day = streakStats(completeWholeDays(planList, S.records, uid, S.windowStart, todayKey), prefix, storedBest || 0);
  if (storedBest !== null) {
    const base = Math.max(S.celebratedLocal._day || 0, S.celebratedDay ?? milestoneFloor(storedBest));
    if (day.best > storedBest) api.saveDayBest(day.best);
    const m = milestoneFloor(day.best);
    if (m > base) {
      S.celebratedLocal._day = m;
      api.saveDayCelebrated(m);
      reached.unshift([m, "ALL LOGS"]);
    }
  }
  // one popup per milestone listing everything that reached it
  const byDays = {};
  reached.forEach(([m, label]) => (byDays[m] = [...(byDays[m] || []), label]));
  Object.keys(byDays).map(Number).sort((a, b) => a - b).forEach((m) => queueCelebration(m, byDays[m]));

  // calendar marks for the month on screen
  const mine = {};
  for (const l of S.logs) mine[l.id] = myRecords(S.records[l.id], uid);
  const marks = {};
  const n = daysInMonth(S.month.y, S.month.m);
  for (let i = 1; i <= n; i++) {
    const key = `${prefix}-${pad(i)}`;
    if (key > todayKey) break;
    marks[key] = dayProgress(planList, mine, key);
  }
  return { logStats, day, plans, marks };
}

// -------------------------------------------------------------- main view
const NUM_KEYS = ["best", "month", "current"];
/** Settings button: Nothing-style dotted gear (ring of dots, 8 teeth, red centre). */
const GEAR_SVG = (() => {
  let d = "";
  for (let i = 0; i < 16; i++) {
    const a = (2 * Math.PI * i) / 16;
    d += `<circle cx="${(52 + 28 * Math.cos(a)).toFixed(1)}" cy="${(52 + 28 * Math.sin(a)).toFixed(1)}" r="5.2" fill="#fff"/>`;
  }
  for (let t = 0; t < 8; t++) {
    const a = (2 * Math.PI * t) / 8 - Math.PI / 2;
    d += `<circle cx="${(52 + 44 * Math.cos(a)).toFixed(1)}" cy="${(52 + 44 * Math.sin(a)).toFixed(1)}" r="6.2" fill="#fff"/>`;
  }
  return `<svg width="30" height="30" viewBox="0 0 104 104" aria-hidden="true">${d}<circle cx="52" cy="52" r="7.5" fill="var(--accent)"/></svg>`;
})();
/** Two-column (landscape / laptop) layout: whole month, no drag control. Matches style.css. */
const isWide = () => window.matchMedia("(min-width: 821px)").matches;
let resizeTimer;
window.addEventListener("resize", () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => S.user && renderMain(), 150);
});
// hollow block arrows for the calendar drag control (dot-matrix)
const ARROW_UP = ["..#..", ".#.#.", "#...#", "##.##", ".#.#.", ".#.#.", ".###."];
const ARROW_DOWN = [...ARROW_UP].reverse();
const ICON = { month: GLYPHS.month, best: GLYPHS.crown, current: GLYPHS.flame };

/** Nothing-style avatar: black disc, ring of white dots, dot-font initials, red dot. */
function avatarHtml(name, big = false) {
  const n = 28;
  let ring = "";
  for (let i = 0; i < n; i++) {
    const a = (2 * Math.PI * i) / n - Math.PI / 2;
    ring += `<circle cx="${(50 + 45 * Math.cos(a)).toFixed(2)}" cy="${(50 + 45 * Math.sin(a)).toFixed(2)}" r="2.6" fill="#fff" opacity="${i % 7 === 0 ? 0.95 : 0.55}"/>`;
  }
  return `<svg viewBox="0 0 100 100" class="ring" aria-hidden="true"><circle cx="50" cy="50" r="50" fill="#000"/>${ring}
    <circle cx="85" cy="15" r="6.5" fill="var(--accent)"/></svg><span>${h(initialsOf(name))}</span>${big ? "" : ""}`;
}

/** Streak number with its dot icon; [key] is used to roll the number when it changes. */
function statHtml(kind, value, key, small) {
  return `${dotSvg(ICON[kind], { size: small ? 10 : 18, off: "none", main: kind === "current" ? "var(--accent)" : "#fff", accent: kind === "current" ? "#fff" : "var(--accent)" })}<span class="num" data-num="${key}">${value}</span>`;
}

// Renders never nest: a change that arrives while rendering just triggers one more render after.
let rendering = false, renderAgain = false;
function renderMain() {
  if (rendering) { renderAgain = true; return; }
  rendering = true;
  try { renderMainNow(); } finally { rendering = false; }
  if (renderAgain) { renderAgain = false; renderMain(); }
}

function renderMainNow() {
  if (!S.user) return;
  const logsColOld = $app.querySelector(".col.logs");
  const scrollTop = logsColOld?.scrollTop ?? 0;
  // FLIP: remember where every log row was, so moves animate after the re-render
  const before = new Map();
  $app.querySelectorAll("[data-log]").forEach((el) => before.set(el.dataset.log, el.getBoundingClientRect().top));
  let stats;
  try {
    stats = computeStats();
  } catch (e) {
    // never let one bad calculation stop the live updates
    console.error("computeStats", e);
    stats = { logStats: {}, day: { month: 0, best: 0, current: 0 }, plans: {}, marks: {} };
  }
  const { logStats, day, plans, marks } = stats;
  const mm = `${pad(S.month.m + 1)}/${S.month.y}`;
  const showFab = isEditableDate(S.selected);

  $app.innerHTML = `
    <div class="main">
      <section class="col cal-col">
        <div class="top">
          <button class="avatar nothing" data-profile title="${h(S.user.name)}">${avatarHtml(S.user.name)}</button>
          <button class="icon-btn gear" data-settings title="Settings">${GEAR_SVG}</button>
        </div>
        <div class="stats-row">
          <div class="stats">
            <div class="stat" title="Best run ever"><small>Max</small><b>${statHtml("best", day.best, "d-best")}</b></div>
            <div class="stat" title="Best run this month (every log at least partly done)"><small>Monthly</small><b>${statHtml("month", day.month, "d-month")}</b></div>
            <div class="stat" title="Current run"><small>Current</small><b>${statHtml("current", day.current, "d-current")}</b></div>
          </div>
          <div class="month-nav">
            <button class="icon-btn" data-prev title="Previous month">‹</button>
            <button class="month-label" data-month>
              ${mm[0]}<span class="a">${mm[1]}</span>${mm.slice(2, 5)}<span class="a">${mm.slice(5, 7)}</span>
            </button>
            <button class="icon-btn" data-next title="Next month">›</button>
          </div>
        </div>
        ${renderCalendar(marks)}
        ${isWide() ? "" : `<div class="handle ${S.collapsed ? "collapsed" : "expanded"}" data-handle title="${S.collapsed ? "Drag down for the whole month" : "Drag up for this week"}">
          <span class="arr up">${dotSvg(ARROW_UP, { size: 16, main: "var(--accent)", off: "transparent" })}</span>
          <span class="pill">DRAG</span>
          <span class="arr down">${dotSvg(ARROW_DOWN, { size: 16, main: "var(--accent)", off: "transparent" })}</span>
        </div>`}
      </section>
      <section class="col logs">
        ${renderLogs(logStats, plans)}
      </section>
    </div>
    ${showFab ? `<button class="fab" data-add title="Add log">+</button>` : ""}`;
  const fabAppears = showFab && !S.fabShown;
  S.fabShown = showFab;

  const logsCol = $app.querySelector(".col.logs");
  if (logsCol) logsCol.scrollTop = scrollTop;
  bindMain();
  animateAfterRender(before);
  if (fabAppears) playAnim("[data-add]", "pop-in", 360);
  showNextCelebration();
}

// Animations that are still running when the page re-renders (data arrives every few ms) are
// re-applied to the new elements with a negative delay, so they continue instead of restarting.
const liveAnims = new Map(); // key -> {selector, cls, start, dur}
function playAnim(selector, cls, dur) {
  const el = $app.querySelector(selector);
  if (!el) return;
  liveAnims.set(selector + "|" + cls, { selector, cls, start: performance.now(), dur });
  el.style.animationDelay = "";
  el.classList.add(cls);
}
function resumeAnims() {
  const now = performance.now();
  for (const [key, a] of liveAnims) {
    const t = now - a.start;
    if (t >= a.dur) { liveAnims.delete(key); continue; }
    const el = $app.querySelector(a.selector);
    if (el && !el.classList.contains(a.cls)) {
      el.style.animationDelay = `-${Math.round(t)}ms`;
      el.classList.add(a.cls);
    }
  }
}
const isAnimating = (selector, cls) => liveAnims.has(selector + "|" + cls);

/** All the "fluid" bits, applied after each re-render without replaying old animations. */
function animateAfterRender(before) {
  resumeAnims();
  const rows = [...$app.querySelectorAll("[data-log]")];
  if (S.dateDir) {
    // another day picked: the list slides in from that side
    playAnim(".logs-list", S.dateDir > 0 ? "in-right" : "in-left", 300);
    S.dateDir = 0;
  } else {
    rows.forEach((row) => {
      const id = row.dataset.log;
      const sel = `[data-log="${id}"]`;
      if (isAnimating(sel, "enter")) return;
      if (before.has(id)) {
        // FLIP: start from where it was on screen, glide to the new place
        const dy = before.get(id) - row.getBoundingClientRect().top;
        if (Math.abs(dy) > 1) {
          row.style.transition = "none";
          row.style.transform = `translateY(${dy}px)`;
          requestAnimationFrame(() => {
            row.style.transition = "transform .3s cubic-bezier(.2,.8,.2,1)";
            row.style.transform = "";
          });
        }
      } else if (S.knownReady && !S.known.has(id)) {
        playAnim(sel, "enter", 380); // brand-new log
      }
    });
  }
  S.logs.forEach((l) => S.known.add(l.id));
  if (S.logs.length) S.knownReady = true;

  if (S.monthDir) {
    playAnim(".weeks", S.monthDir > 0 ? "in-right" : "in-left", 320);
    playAnim(".month-label", S.monthDir > 0 ? "in-right" : "in-left", 280);
    S.monthDir = 0;
  }

  // collapse / expand: start each week from where it was, then let CSS transition it
  if (S.collapseFrom !== null) {
    const weeks = [...$app.querySelectorAll(".week")];
    const target = weeks.map((w) => w.classList.contains("hidden"));
    weeks.forEach((w) => { w.style.transition = "none"; w.classList.toggle("hidden", w.dataset.wasHidden === "1"); });
    void $app.offsetHeight;
    weeks.forEach((w, i) => { w.style.transition = ""; w.classList.toggle("hidden", target[i]); });
    S.collapseFrom = null;
  }

  // pop the ✓ of a day that just became complete
  const monthKey = `${S.month.y}-${S.month.m}`;
  const full = new Set([...$app.querySelectorAll(".day.full")].map((d) => d.dataset.day));
  if (S.prevFull && S.prevFull.monthKey === monthKey) {
    full.forEach((k) => { if (!S.prevFull.set.has(k)) playAnim(`[data-day="${k}"] .mark`, "pop", 460); });
  }
  S.prevFull = { monthKey, set: full };

  // roll streak numbers that changed
  $app.querySelectorAll("[data-num]").forEach((el) => {
    const k = el.dataset.num;
    const v = +el.textContent;
    const old = S.lastNums[k];
    if (old !== undefined && old !== v) playAnim(`[data-num="${k}"]`, v > old ? "roll-up" : "roll-down", 360);
    S.lastNums[k] = v;
  });

  if (S.justTicked) {
    playAnim(`[data-tick="${CSS.escape(S.justTicked)}"]`, "pop", 360);
    S.justTicked = null;
  }
}

function renderCalendar(marks) {
  const { y, m } = S.month;
  const n = daysInMonth(y, m);
  const offset = new Date(y, m, 1).getDay(); // Sunday = 0
  const weeks = Math.ceil((offset + n) / 7);
  const t = today();

  // Which week stays visible when collapsed: selected day, else today, else first week
  const inMonth = (d) => d.getFullYear() === y && d.getMonth() === m;
  const focusDay = inMonth(S.selected) ? S.selected.getDate() : inMonth(t) ? t.getDate() : 1;
  const focusWeek = Math.floor((offset + focusDay - 1) / 7);
  const wasCollapsed = S.collapseFrom;

  let html = `<div class="cal" data-cal><div class="cal-row cal-head">${"SMTWTFS".split("").map((c) => `<div>${c}</div>`).join("")}</div><div class="weeks">`;
  for (let w = 0; w < weeks; w++) {
    // wide (landscape / laptop) layout: always the whole month
    const hidden = !isWide() && S.collapsed && w !== focusWeek;
    const wasHidden = wasCollapsed !== null ? (wasCollapsed && w !== focusWeek) : hidden;
    html += `<div class="week ${hidden ? "hidden" : ""}" data-was-hidden="${wasHidden ? 1 : 0}"><div class="cal-row">`;
    for (let i = 0; i < 7; i++) {
      const dayNum = w * 7 + i - offset + 1;
      if (dayNum < 1 || dayNum > n) { html += `<div></div>`; continue; }
      const d = new Date(y, m, dayNum);
      const key = ymd(d);
      // judged with the logs and times that existed ON that day
      const p = marks[key] || { done: 0, total: 0, full: false, counts: false };
      const future = d > t;
      const past = d < t;
      const cls = ["day"];
      if (sameDay(d, S.selected)) cls.push("sel");
      if (p.full || p.counts) cls.push("full");
      else if (past) cls.push("past");
      if (d < addDays(t, -2) || future) cls.push("faded");
      let mark = "";
      if (p.full) mark = `<span class="mark full">✓</span>`;                    // everything due was ticked
      else if (p.counts) mark = `<span class="mark part">✓</span>`;             // partly done: counts for the streak
      else if (past && p.total > 0) mark = `<span class="mark miss">✕</span>`;  // nothing ticked at all (days before your logs: no mark)
      else if (p.done > 0) mark = `<span class="part-dot"></span>`;             // today, part done
      html += `<button class="${cls.join(" ")}" data-day="${key}" ${future ? "disabled" : ""}>${dayNum}${mark}</button>`;
    }
    html += `</div></div>`;
  }
  return html + `</div></div>`;
}

function renderLogs(logStats, plans) {
  const sel = ymd(S.selected);
  const editable = isEditableDate(S.selected);
  const isPastDay = sel < ymd(today());
  const isToday = sel === ymd(today());
  const title = S.selected.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" }).toUpperCase();
  const uid = S.user.uid;

  let html = `<div class="logs-head"><h3>LOGS FOR ${h(title)}</h3>
    <button class="text-btn" data-import>IMPORT</button>
    ${S.logs.length && editable ? `<button class="text-btn" data-delete-all>DELETE ALL</button>` : ""}</div>`;

  // Daily summary: what's still left today
  if (isToday) {
    const active = S.logs.filter(isActive);
    if (active.some((l) => l.times.length)) {
      const left = leftFor(active, S.records, uid, sel);
      html += left.length
        ? `<div class="left-today">LEFT TODAY (${left.length}): ${left.map((x) => h(x.log.name.toUpperCase()) + " " + to12h(x.time)).join(" · ")}</div>`
        : `<div class="left-today done">ALL DONE TODAY ✓</div>`;
    }
  }

  if (!S.logs.length) {
    return html + `<p class="empty">No logs yet. Click + to add one, or IMPORT a CSV file.</p>`;
  }
  // Logs that existed on the selected day (a log added later isn't part of earlier days)
  const dayLogs = S.logs.filter((l) => !plans[l.id] || plans[l.id].exists(sel));
  if (!dayLogs.length) {
    return html + `<p class="empty">No logs on this day yet. Your logs start later.</p>`;
  }

  html += `<div class="logs-list">`;
  for (const log of dayLogs) {
    const plan = plans[log.id];
    // the times / switch this log had on that day (later changes don't rewrite the past)
    const dayTimes = isPastDay && plan ? plan.timesOn(sel) : log.times;
    const activeThatDay = isPastDay && plan ? plan.activeOn(sel) : isActive(log);
    const activeNow = isActive(log);
    const recs = (S.records[log.id] || []).filter((r) => r.date === sel);
    const mine = recs.find((r) => r.uid === uid);
    const friends = recs.filter((r) => r.uid !== uid);
    const can = activeThatDay && editable;
    const st = logStats[log.id] || { month: 0, best: 0, current: 0 };
    const leaving = S.removing.has(log.id);

    html += `<div class="log ${editable && activeThatDay ? "" : "off"} ${leaving ? "leave gone" : ""}" data-log="${log.id}">
      <div class="log-side">
        <span class="grip" data-grip title="Drag to reorder">☰</span>
        <span class="mini" title="Best run ever">${statHtml("best", st.best, "l-" + log.id + "-b", true)}</span>
        <span class="mini" title="Best run this month">${statHtml("month", st.month, "l-" + log.id + "-m", true)}</span>
        <span class="mini" title="Current run">${statHtml("current", st.current, "l-" + log.id + "-c", true)}</span>
      </div>
      <div class="log-main">
        <div class="log-title">
          <span class="name" title="${h(log.name)}">${h(log.name.toUpperCase())}</span>
          <label class="switch" title="On / off for you (from today)"><input type="checkbox" data-active ${activeNow ? "checked" : ""} ${editable ? "" : "disabled"}/><span></span></label>
          <button class="small-btn" data-share title="Share as file">⇪</button>
          <button class="small-btn" data-edit title="Edit" ${editable ? "" : "disabled"}>✎</button>
          <button class="small-btn" data-del title="${isOwner(log) ? "Delete" : "Leave"}" ${editable ? "" : "disabled"}>🗑</button>
        </div>
        ${isShared(log) ? `<div class="shared-tag">${isOwner(log) ? `SHARED WITH ${log.memberEmails.length - 1}` : `SHARED BY ${h(log.ownerName.toUpperCase())}`}</div>` : ""}
        <div class="times">
          ${dayTimes.map((t) => `
            <label class="time" data-tick="${log.id}|${t}"><input type="checkbox" data-time="${t}" ${mine && mine.checked.includes(t) ? "checked" : ""} ${can ? "" : "disabled"}/>${to12h(t)}</label>`).join("")}
          <button class="add-time" data-add-time title="Add time" ${can ? "" : "disabled"}>+</button>
        </div>
        ${isShared(log) && friends.length ? `<div class="friends">${friends.map((r) => {
          const d = dayTimes.filter((t) => r.checked.includes(t)).length;
          const full = dayTimes.length && d >= dayTimes.length;
          return `<span class="chip ${full ? "full" : ""}">${h((r.userName || "").toUpperCase())} ${d}/${dayTimes.length}</span>`;
        }).join("")}</div>` : ""}
      </div>
    </div>`;
  }
  return html + `</div>`;
}

// ------------------------------------------------------------ celebration
function queueCelebration(days, labels) {
  // same milestone as the popup on screen or one waiting -> one popup listing everything
  const merge = (c) => (c.labels = [...new Set([...c.labels, ...labels])]);
  if (S.showing && S.showing.days === days) {
    merge(S.showing);
    const el = document.querySelector("#celebrate .clabels");
    if (el) el.textContent = S.showing.labels.join("  ·  ");
    return;
  }
  const same = S.celebrations.find((c) => c.days === days);
  if (same) return merge(same);
  S.celebrations.push({ days, labels: [...labels] });
  S.celebrations.sort((a, b) => a.days - b.days);
}

function showNextCelebration() {
  if (document.getElementById("celebrate") || !S.celebrations.length) return;
  const c = S.celebrations.shift();
  S.showing = c;
  const ring = Array.from({ length: 48 }, (_, i) => {
    const a = (2 * Math.PI * i) / 48;
    const lit = i % 4 === 0;
    return `<circle cx="${(150 + 138 * Math.cos(a)).toFixed(1)}" cy="${(150 + 138 * Math.sin(a)).toFixed(1)}" r="${lit ? 3.4 : 2}" fill="${lit ? "var(--accent)" : "rgba(255,255,255,.35)"}" ${lit ? 'class="pulse"' : ""}/>`;
  }).join("");
  let seed = c.days * 9301 + 49297;
  const rnd = () => ((seed = (seed * 9301 + 49297) % 233280) / 233280);
  const sparks = Array.from({ length: 56 }, (_, i) => {
    const a = rnd() * 360, dist = 90 + rnd() * 110;
    const col = ["var(--accent)", "#fff", "#ffb300"][i % 3];
    return `<i style="--a:${a.toFixed(0)}deg;--d:${dist.toFixed(0)}px;background:${col}"></i>`;
  }).join("");
  const digits = String(c.days).split("").map((ch) => dotSvg(DIGITS[ch], { size: 64, off: "rgba(255,255,255,.06)", sweep: true })).join("");
  const el = document.createElement("div");
  el.id = "celebrate";
  el.className = "celebrate";
  el.innerHTML = `
    <div class="burst"><svg class="cring" viewBox="0 0 300 300">${ring}</svg><div class="sparks">${sparks}</div></div>
    <div class="ccontent">
      ${dotSvg(GLYPHS.crown, { size: 34, sweep: true })}
      <div class="cdigits">${digits}</div>
      <div class="ctitle">DAY STREAK</div>
      <div class="clabels">${c.labels.map(h).join("  ·  ")}</div>
      <div class="cline">${h(celebrationLine(c.days))}</div>
      <div class="ctap">TAP TO CONTINUE</div>
    </div>`;
  el.onclick = () => hideCelebration(false);
  document.body.appendChild(el);
}

function hideCelebration(now) {
  const el = document.getElementById("celebrate");
  S.showing = null;
  if (!el) return;
  if (now) { el.remove(); return; }
  el.classList.add("out");
  setTimeout(() => { el.remove(); showNextCelebration(); }, 300);
}

// ----------------------------------------------------------------- events
function bindMain() {
  const q = (sel) => $app.querySelector(sel);
  q("[data-settings]").onclick = openSettings;
  q("[data-profile]").onclick = openProfile;
  q("[data-prev]").onclick = () => setMonth(S.month.y, S.month.m - 1);
  q("[data-next]").onclick = () => setMonth(S.month.y, S.month.m + 1);
  q("[data-month]").onclick = openMonthPicker;
  q("[data-import]").onclick = () => $csv.click();
  const add = q("[data-add]");
  if (add) add.onclick = openAddLog;
  const delAll = q("[data-delete-all]");
  if (delAll) delAll.onclick = async () => {
    const ok = await confirmBox(
      "DELETE ALL LOGS?",
      "This deletes every log you created, including all ticks, on all your devices. Logs shared with you are only removed from your list. This can't be undone."
    );
    if (!ok) return;
    const all = [...S.logs];
    all.forEach((l) => S.removing.add(l.id));
    $app.querySelectorAll("[data-log]").forEach((row) => row.classList.add("leave"));
    await new Promise((r) => setTimeout(r, 280)); // let the rows shrink away first
    let failed = 0;
    for (const log of all) {
      try { await api.deleteOrLeave(log, S.user); } catch { failed++; }
    }
    setTimeout(() => { all.forEach((l) => S.removing.delete(l.id)); renderMain(); }, 1500);
    if (failed) toast(`${failed} log(s) couldn't be deleted. Try again when online.`);
  };

  $app.querySelectorAll("[data-day]").forEach((b) => {
    b.onclick = () => {
      const [yy, mo, dd] = b.dataset.day.split("-").map(Number);
      const next = new Date(yy, mo - 1, dd);
      if (sameDay(next, S.selected)) return;
      S.dateDir = next > S.selected ? 1 : -1;
      S.selected = next;
      renderMain();
    };
  });

  // Collapse / expand: drag down = full month, drag up = this week. Click the handle to toggle.
  const attachDrag = (el, clickToggles) => {
    let startY = null, moved = false;
    el.addEventListener("pointerdown", (e) => {
      startY = e.clientY;
      moved = false;
      // keep receiving moves even when the pointer leaves the small handle
      if (clickToggles) el.setPointerCapture(e.pointerId);
    });
    el.addEventListener("pointermove", (e) => {
      if (startY === null) return;
      const dy = e.clientY - startY;
      if (Math.abs(dy) > 20) {
        moved = true;
        const next = dy < 0;
        startY = null;
        if (S.collapsed !== next) { S.collapseFrom = S.collapsed; S.collapsed = next; renderMain(); }
      }
    });
    const end = () => {
      if (startY !== null && clickToggles && !moved) { S.collapseFrom = S.collapsed; S.collapsed = !S.collapsed; renderMain(); }
      startY = null;
    };
    el.addEventListener("pointerup", end);
    el.addEventListener("pointercancel", () => (startY = null));
    el.addEventListener("pointerleave", () => (startY = null));
  };
  if (q("[data-handle]")) {          // narrow (portrait) layout only
    attachDrag(q("[data-handle]"), true);
    attachDrag(q("[data-cal]"), false);
  }

  // Per-log controls
  $app.querySelectorAll("[data-log]").forEach((row) => {
    const log = S.logs.find((l) => l.id === row.dataset.log);
    if (!log) return;
    row.querySelector("[data-active]").onchange = (e) =>
      api.setActive(log, S.user.uid, e.target.checked, ymd(S.selected)).catch((x) => toast(friendlyError(x)));
    row.querySelector("[data-share]").onclick = () => openExport([log]);
    row.querySelector("[data-edit]").onclick = () => openEdit(log);
    row.querySelector("[data-del]").onclick = async () => {
      const owner = isOwner(log);
      const msg = owner && isShared(log)
        ? `'${log.name}' and all its ticks will be deleted for you and everyone it's shared with.`
        : owner ? `'${log.name}' and all its ticks will be deleted on all your devices.`
        : `'${log.name}' will be removed from your list. The owner keeps it.`;
      if (await confirmBox(owner ? "DELETE LOG?" : "LEAVE LOG?", msg)) {
        S.removing.add(log.id);
        $app.querySelector(`[data-log="${log.id}"]`)?.classList.add("leave");
        await new Promise((r) => setTimeout(r, 280)); // shrink away, then delete
        try { await api.deleteOrLeave(log, S.user); } catch (e) { toast("Couldn't delete: " + friendlyError(e)); }
        setTimeout(() => { S.removing.delete(log.id); renderMain(); }, 1500); // still there = failed -> show again
      }
    };
    row.querySelectorAll("[data-time]").forEach((cb) => {
      cb.onchange = () => {
        if (cb.checked) S.justTicked = `${log.id}|${cb.dataset.time}`;
        api.setChecked(log.id, S.user, ymd(S.selected), cb.dataset.time, cb.checked)
          .catch((e) => toast(friendlyError(e)));
      };
    });
    row.querySelector("[data-add-time]").onclick = () => openAddTime(log);

    // Drag to reorder (only from the grip)
    const grip = row.querySelector("[data-grip]");
    grip.addEventListener("mousedown", () => (row.draggable = true));
    grip.addEventListener("touchstart", () => (row.draggable = true), { passive: true });
    row.addEventListener("dragstart", (e) => {
      e.dataTransfer.setData("text/plain", log.id);
      e.dataTransfer.effectAllowed = "move";
      requestAnimationFrame(() => row.classList.add("dragging"));
    });
    row.addEventListener("dragend", () => { row.draggable = false; row.classList.remove("dragging"); });
    row.addEventListener("dragover", (e) => { e.preventDefault(); row.classList.add("drop-target"); });
    row.addEventListener("dragleave", () => row.classList.remove("drop-target"));
    row.addEventListener("drop", (e) => {
      e.preventDefault();
      row.classList.remove("drop-target");
      const fromId = e.dataTransfer.getData("text/plain");
      if (!fromId || fromId === log.id) return;
      const ids = S.logs.map((l) => l.id);
      ids.splice(ids.indexOf(fromId), 1);
      ids.splice(ids.indexOf(log.id), 0, fromId);
      S.logs = ids.map((id) => S.logs.find((l) => l.id === id));
      renderMain();
      api.saveOrder(S.user.uid, ids).catch((err) => toast(friendlyError(err)));
    });
  });
}

// ---------------------------------------------------------------- dialogs
function openSettings() {
  openModal(
    `<h2>SETTINGS</h2>
     <div class="stack">
       <button class="btn gray block" data-imp>IMPORT LOGS (.CSV / .SLOG)</button>
       ${S.logs.length ? `<button class="btn gray block" data-exp>SHARE ALL LOGS</button>` : ""}
       <p class="hint">Reminders and notifications run on the Android app. This web version is for viewing and ticking logs on your laptop.</p>
     </div>
     <div class="actions"><button class="link" data-close style="color:var(--accent)">CLOSE</button></div>`,
    (m) => {
      m.querySelector("[data-close]").onclick = closeModal;
      m.querySelector("[data-imp]").onclick = () => { closeModal(); $csv.click(); };
      const exp = m.querySelector("[data-exp]");
      if (exp) exp.onclick = () => openExport([...S.logs]);
    }
  );
}

// Avatar -> profile: sign out / delete account
function openProfile() {
  openModal(
    `<div class="profile">
       <div class="avatar nothing big">${avatarHtml(S.user.name, true)}</div>
       <div class="dot" style="font-size:18px">${h(S.user.name.toUpperCase())}</div>
       <div class="hint">${h(S.user.email)}</div>
     </div>
     <div class="stack" style="margin-top:16px">
       <button class="btn gray block" data-out>SIGN OUT</button>
       <button class="btn danger block" data-del-account>DELETE ACCOUNT</button>
     </div>
     <div class="actions"><button class="link" data-close style="color:var(--accent)">CLOSE</button></div>`,
    (m) => {
      m.querySelector("[data-close]").onclick = closeModal;
      m.querySelector("[data-out]").onclick = () => { closeModal(); api.signOut(); };
      m.querySelector("[data-del-account]").onclick = openDeleteAccount;
    }
  );
}

function openDeleteAccount() {
  openModal(
    `<h2>DELETE ACCOUNT?</h2>
     <p>This permanently deletes your account, all logs you created and all their ticks, on every device.
     It can't be undone. Export your logs first if you want to keep them.</p>
     <form class="stack">
       <div class="pw"><input class="field" name="pw" type="password" placeholder="Your password" autocomplete="current-password" />${PW_TOGGLE}</div>
       <div class="err"></div>
       <div class="actions"><button type="button" class="link" data-cancel>CANCEL</button><button class="btn" type="submit">DELETE</button></div>
     </form>`,
    (m) => {
      const f = m.querySelector("form");
      const err = m.querySelector(".err");
      const btn = f.querySelector("[type=submit]");
      f.pw.focus();
      m.querySelector("[data-cancel]").onclick = closeModal;
      f.onsubmit = async (e) => {
        e.preventDefault();
        if (!f.pw.value) { err.textContent = "Enter your password."; return; }
        btn.disabled = true;
        err.textContent = "Deleting...";
        try {
          await api.deleteAccount(f.pw.value);
          closeModal(); // auth change takes you back to the login screen
        } catch (x) {
          err.textContent = friendlyError(x);
          btn.disabled = false;
        }
      };
    }
  );
}

function openMonthPicker() {
  let year = S.month.y;
  const draw = (m) => {
    m.innerHTML = `
      <div class="year-nav"><button class="icon-btn" data-py>‹</button><b>${year}</b><button class="icon-btn" data-ny>›</button></div>
      <div class="month-grid">${MONTHS.map((name, i) =>
        `<button data-m="${i}" class="${year === S.month.y && i === S.month.m ? "sel" : ""}">${name}</button>`).join("")}</div>`;
    m.querySelector("[data-py]").onclick = () => { year--; draw(m); };
    m.querySelector("[data-ny]").onclick = () => { year++; draw(m); };
    m.querySelectorAll("[data-m]").forEach((b) => (b.onclick = () => { closeModal(); setMonth(year, +b.dataset.m); }));
  };
  openModal("", draw);
}

function openAddLog() {
  openModal(
    `<h2>NEW LOG</h2>
     <form class="stack">
       <input class="field" name="name" placeholder="Name" autofocus />
       <input class="field" name="times" placeholder="Times, e.g. 07:00, 19:00" />
       <div class="err"></div>
       <div class="actions"><button type="button" class="link" data-cancel>CANCEL</button><button class="btn" type="submit">ADD</button></div>
     </form>`,
    (m) => {
      const f = m.querySelector("form");
      f.name.focus();
      m.querySelector("[data-cancel]").onclick = closeModal;
      f.onsubmit = (e) => {
        e.preventDefault();
        const name = f.name.value.trim();
        const { times, bad } = parseTimeList(f.times.value);
        const err = m.querySelector(".err");
        if (!name) return (err.textContent = "Enter a name.");
        if (bad.length) return (err.textContent = "Invalid time: " + bad.join(", "));
        if (!times.length) return (err.textContent = "Add at least one time.");
        // starts on the day being viewed, so it shows up right there
        api.addLog(S.user, name, times, S.logs.length, ymd(S.selected) < ymd(today()) ? ymd(S.selected) : ymd(today()));
        closeModal();
      };
    }
  );
}

function openAddTime(log) {
  openModal(
    `<h2>ADD TIME</h2>
     <form class="stack">
       <p>${h(log.name.toUpperCase())}</p>
       <input class="field" type="time" name="t" required />
       <div class="actions"><button type="button" class="link" data-cancel>CANCEL</button><button class="btn" type="submit">SAVE</button></div>
     </form>`,
    (m) => {
      const f = m.querySelector("form");
      f.t.focus();
      m.querySelector("[data-cancel]").onclick = closeModal;
      f.onsubmit = (e) => {
        e.preventDefault();
        if (!f.t.value) return;
        // a new time applies from today on; earlier days keep their times
        api.updateLog(log, log.name, [...log.times, f.t.value], ymd(S.selected)).catch((err) => toast(friendlyError(err)));
        closeModal();
      };
    }
  );
}

function openEdit(log) {
  openModal(
    `<h2>EDIT LOG</h2>
     <form class="stack">
       <input class="field" name="name" value="${h(log.name)}" placeholder="Name" />
       <input class="field" name="times" value="${h(log.times.join(", "))}" placeholder="Times" />
       <div class="err"></div>
       <div class="hint">Format: 07:00, 12:30, 19:00 (saved earliest first). Changes apply from the day you're viewing; earlier days stay as they were.</div>
       <div class="actions"><button type="button" class="link" data-cancel>CANCEL</button><button class="btn" type="submit">SAVE</button></div>
     </form>`,
    (m) => {
      const f = m.querySelector("form");
      m.querySelector("[data-cancel]").onclick = closeModal;
      f.onsubmit = (e) => {
        e.preventDefault();
        const name = f.name.value.trim();
        const { times, bad } = parseTimeList(f.times.value);
        const err = m.querySelector(".err");
        if (!name) return (err.textContent = "Name can't be empty.");
        if (bad.length) return (err.textContent = "Invalid time: " + bad.join(", "));
        api.updateLog(log, name, times, ymd(S.selected)).catch((x) => toast(friendlyError(x)));
        closeModal();
      };
    }
  );
}

// Share logs as files: .csv, .slog or both
function openExport(logs) {
  const title = logs.length === 1 ? `SHARE "${h(logs[0].name.toUpperCase())}"` : `SHARE ${logs.length} LOGS`;
  openModal(
    `<h2>${title}</h2>
     <p class="hint">Send as a file. Your friend opens S Log, clicks IMPORT and picks the file.</p>
     <div class="stack" style="margin-top:12px">
       <button class="export-opt" data-f="csv"><b>CSV</b><span>Name + times. Also opens in Excel / Google Sheets.</span></button>
       <button class="export-opt" data-f="slog"><b>.SLOG</b><span>S Log file: names, times and your tick history.</span></button>
       <button class="export-opt" data-f="both"><b>BOTH</b><span>The .csv and the .slog file together.</span></button>
     </div>
     <div class="actions"><button class="link" data-cancel>CANCEL</button></div>`,
    (m) => {
      m.querySelector("[data-cancel]").onclick = closeModal;
      m.querySelectorAll("[data-f]").forEach((b) => (b.onclick = () => {
        closeModal();
        shareFiles(logs, b.dataset.f);
      }));
    }
  );
}

async function shareFiles(logs, format) {
  const base = logs.length === 1 ? safeFileName(logs[0].name) : `slog_logs_${ymd(today())}`;
  const files = [];
  if (format === "csv" || format === "both") {
    files.push(new File([toCsv(logs)], `${base}.csv`, { type: "text/csv" }));
  }
  if (format === "slog" || format === "both") {
    files.push(new File([toSlog(logs, S.records, S.user, isActive)], `${base}.slog`, { type: "application/octet-stream" }));
  }
  // Phones / browsers that can share files get the share sheet; laptops download the files.
  try {
    if (navigator.canShare && navigator.canShare({ files })) {
      await navigator.share({ files, title: "S Log", text: "Import this in S Log (IMPORT button)." });
      return;
    }
  } catch (e) {
    if (e && e.name === "AbortError") return;
  }
  files.forEach((f, i) => setTimeout(() => {
    const url = URL.createObjectURL(f);
    const a = document.createElement("a");
    a.href = url;
    a.download = f.name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 2000);
  }, i * 400));
  toast(files.length === 1 ? `Downloaded ${files[0].name}` : `Downloaded ${files.map((f) => f.name).join(" and ")}`);
}

// ---------------------------------------------------- registration PIN
function renderChecking() {
  $app.innerHTML = `<div class="auth"><div class="auth-box"><p class="brand">S&nbsp;&nbsp;LOG</p><p class="muted">Loading...</p></div></div>`;
}

async function checkRegistration(user) {
  if (S.user && S.user.uid === user.uid && S.unsubLogs) return; // already in
  renderChecking();
  const ok = await api.isRegistered(user.email);
  if (ok) startMain(user);
  else { stopListeners(); S.user = null; renderPin(user); }
}

function renderPin(user, msg = "") {
  $app.innerHTML = `
    <div class="auth"><form class="auth-box">
      <p class="brand">S&nbsp;&nbsp;LOG</p>
      <h2>REGISTRATION PIN</h2>
      <p class="muted" style="font-size:13px">${h(user.email)} needs the registration PIN once before it can use S Log.</p>
      <div class="pw"><input class="field" name="pin" type="password" inputmode="numeric" placeholder="Registration PIN" autocomplete="off" />${PW_TOGGLE}</div>
      <div class="msg">${h(msg)}</div>
      <button class="btn block" type="submit">CONTINUE</button>
      <button class="link white" type="button" data-out>Use a different account</button>
    </form></div>`;
  const form = $app.querySelector("form");
  const btn = form.querySelector("[type=submit]");
  form.pin.focus();
  form.querySelector("[data-out]").onclick = () => api.signOut();
  form.onsubmit = async (e) => {
    e.preventDefault();
    const pin = form.pin.value.trim();
    if (!pin) { form.querySelector(".msg").textContent = "Enter the registration PIN."; return; }
    btn.disabled = true;
    try {
      await api.completeRegistration(pin);
      startMain(user);
    } catch (err) {
      renderPin(user, friendlyError(err));
    }
  };
}

// --------------------------------------------------------------- CSV import
$csv.addEventListener("change", async () => {
  const file = $csv.files[0];
  $csv.value = "";
  if (!file) return;
  let rows;
  try {
    rows = parseAny(await file.text(), S.logs.map((l) => l.name));
  } catch (e) {
    return toast("Couldn't read file: " + (e.message || e));
  }
  if (!rows.length) return toast("No logs found in that file.");
  openCsvPreview(rows);
});

function openCsvPreview(rows) {
  const selected = new Set(rows.filter((r) => r.valid && !r.duplicate).map((r) => r.line));
  const fileHasHistory = rows.some((r) => Object.keys(r.history || {}).length);
  let includeHistory = false;
  const draw = (m) => {
    const n = selected.size;
    const days = rows.filter((r) => selected.has(r.line)).reduce((s, r) => s + Object.keys(r.history || {}).length, 0);
    m.innerHTML = `
      <h2>IMPORT PREVIEW</h2>
      <p class="hint">${rows.length} log(s) found. Untick anything you don't want.</p>
      ${fileHasHistory ? `<label class="hist-opt"><input type="checkbox" data-hist ${includeHistory ? "checked" : ""}/>
        Also import tick history (${days} days) as my ticks</label>` : ""}
      ${rows.map((r) => {
        const hd = Object.keys(r.history || {}).length;
        return `
        <label class="csv-row">
          <input type="checkbox" data-line="${r.line}" ${selected.has(r.line) ? "checked" : ""} ${r.valid ? "" : "disabled"} />
          <div>
            <div class="n">${h((r.name || `(no name) - #${r.line}`).toUpperCase())}</div>
            <div class="t">${r.times.length ? r.times.map(to12h).join("  ") : "No times"}</div>
            ${hd ? `<div class="t muted">${hd} days of history</div>` : ""}
            ${r.problems.map((p) => `<div class="p">${h(p)}</div>`).join("")}
          </div>
        </label>`;
      }).join("")}
      <p class="hint">Accepts .csv (name,times &nbsp;e.g.&nbsp; Gym,"07:00;19:00") and .slog files.</p>
      <div class="actions">
        <button class="link" data-cancel>CANCEL</button>
        <button class="btn" data-go ${n ? "" : "disabled"}>ADD ${n} LOG${n === 1 ? "" : "S"}</button>
      </div>`;
    m.querySelectorAll("[data-line]").forEach((cb) => (cb.onchange = () => {
      cb.checked ? selected.add(+cb.dataset.line) : selected.delete(+cb.dataset.line);
      draw(m);
    }));
    const hist = m.querySelector("[data-hist]");
    if (hist) hist.onchange = () => { includeHistory = hist.checked; draw(m); };
    m.querySelector("[data-cancel]").onclick = closeModal;
    m.querySelector("[data-go]").onclick = () => {
      let order = S.logs.length;
      let historyDays = 0;
      const chosen = rows.filter((r) => selected.has(r.line));
      const todayKey = ymd(today());
      chosen.forEach((r) => {
        // with history: the log starts on its first day in the file, so old days count as before
        let start = todayKey;
        if (includeHistory) {
          const first = [r.startDate, ...Object.keys(r.history || {})].filter(Boolean).sort()[0];
          if (first && first < todayKey) start = first;
        }
        const id = api.addLog(S.user, r.name, r.times, order++, start, includeHistory ? r.timesLog : {});
        const hd = Object.keys(r.history || {}).length;
        if (includeHistory && hd) {
          api.importHistory(id, S.user, r.history);
          historyDays += hd;
        }
      });
      closeModal();
      toast(`Imported ${chosen.length} log(s)` + (historyDays ? ` with ${historyDays} days of history.` : "."));
    };
  };
  openModal("", draw);
}

// ------------------------------------------------------------------- boot
if (!api.configured) {
  renderSetupNeeded();
} else {
  api.onAuth((a) => {
    if (a.state === "signedOut") {
      if (S.signingUp) return; // wrong PIN during sign-up: keep the form + error on screen
      stopListeners(); S.user = null; closeModal(); renderAuth();
    }
    else if (!S.signingUp) checkRegistration({ uid: a.uid, email: a.email, name: a.name });
  });
}
