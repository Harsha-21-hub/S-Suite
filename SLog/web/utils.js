// Shared helpers (times, dates, CSV). Mirrors TimeUtils.kt / CsvImporter.kt on Android.

const TIME24 = /^(\d{1,2}):(\d{2})$/;
const TIME12 = /^(\d{1,2})(?::(\d{2}))?\s*([AaPp])\.?[Mm]\.?$/;

export const pad = (n) => String(n).padStart(2, "0");

/** "7:00" | "19:30" | "7 PM" | "7:30pm" -> "HH:mm", or null. */
export function parseTime(raw) {
  const s = String(raw).trim();
  let m = s.match(TIME24);
  if (m) {
    const h = +m[1], min = +m[2];
    return h <= 23 && min <= 59 ? `${pad(h)}:${pad(min)}` : null;
  }
  m = s.match(TIME12);
  if (m) {
    let h = +m[1];
    const min = m[2] ? +m[2] : 0;
    const pm = m[3].toLowerCase() === "p";
    if (h < 1 || h > 12 || min > 59) return null;
    if (h === 12) h = 0;
    if (pm) h += 12;
    return `${pad(h)}:${pad(min)}`;
  }
  return null;
}

export const toMinutes = (t) => {
  const [h, m] = t.split(":").map(Number);
  return h * 60 + m;
};

/** Valid, unique, earliest first. */
export function normalizeTimes(list) {
  const out = [...new Set(list.map(parseTime).filter(Boolean))];
  return out.sort((a, b) => toMinutes(a) - toMinutes(b));
}

export function parseTimeList(text) {
  const parts = String(text).split(/[,;|\n]/).map((s) => s.trim()).filter(Boolean);
  return { times: normalizeTimes(parts), bad: parts.filter((p) => !parseTime(p)) };
}

export function to12h(t) {
  let [h, m] = t.split(":").map(Number);
  const ap = h >= 12 ? "PM" : "AM";
  if (h === 0) h = 12;
  if (h > 12) h -= 12;
  return `${h}:${pad(m)} ${ap}`;
}

// ---------------------------------------------------------------- dates
export const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
export const today = () => {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d;
};
export const addDays = (d, n) => {
  const x = new Date(d);
  x.setDate(x.getDate() + n);
  return x;
};
export const sameDay = (a, b) => ymd(a) === ymd(b);
export const daysInMonth = (y, m) => new Date(y, m + 1, 0).getDate(); // m: 0-11

// ------------------------------------------------------------------ CSV
function splitCsvLine(line) {
  const out = [];
  let cur = "", q = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (q && c === '"' && line[i + 1] === '"') { cur += '"'; i++; }
    else if (c === '"') q = !q;
    else if (c === "," && !q) { out.push(cur); cur = ""; }
    else cur += c;
  }
  out.push(cur);
  return out;
}

function buildRow(number, name, raw, history, seen, startDate = "", timesLog = {}) {
  const times = normalizeTimes(raw);
  const bad = raw.filter((r) => !parseTime(r));
  const problems = [];
  if (!name) problems.push("Missing name");
  if (bad.length) problems.push("Skipped invalid time: " + bad.join(", "));
  if (name && !times.length) problems.push("No times");
  const key = name.toLowerCase();
  const duplicate = !!name && seen.has(key);
  if (duplicate) problems.push("A log with this name already exists");
  if (name) seen.add(key);
  // keep only ticks for times this log has (or had, for v2 files with time changes)
  const known = new Set([...times, ...Object.values(timesLog || {}).flat()]);
  const cleanHistory = {};
  for (const [date, ticks] of Object.entries(history || {})) {
    const t = ticks.filter((x) => known.has(x));
    if (t.length) cleanHistory[date] = t;
  }
  return { line: number, name, times, problems, duplicate, valid: !!name, history: cleanHistory, startDate, timesLog };
}

/**
 * .csv:  name,times   e.g.  Gym,"07:00;19:00"   (header optional, extra columns = more times)
 * Returns [{line, name, times, problems[], duplicate, valid, history}]
 */
export function parseCsv(text, existingNames) {
  const seen = new Set(existingNames.map((n) => n.trim().toLowerCase()));
  const rows = [];
  text.replace(/^\uFEFF/, "").split(/\r?\n/).forEach((line, i) => {
    if (!line.trim()) return;
    const cells = splitCsvLine(line);
    const name = (cells[0] || "").trim();
    if (i === 0 && name.toLowerCase() === "name") return;
    const raw = cells.slice(1).flatMap((c) => c.split(/[;|]/)).map((s) => s.trim()).filter(Boolean);
    rows.push(buildRow(i + 1, name, raw, {}, seen));
  });
  return rows;
}

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

/** {"2026-10-01": ["07:00", ...]} -> {date: valid times} (dates and times checked). */
function dateMap(obj, clean) {
  const out = {};
  for (const date of Object.keys(obj || {}).sort()) {
    if (!DATE_RE.test(date) || !Array.isArray(obj[date])) continue;
    const times = clean(obj[date].map(parseTime).filter(Boolean));
    if (times.length) out[date] = times;
  }
  return out;
}

/** .slog: S Log's own JSON file (names, times, tick history). Throws if it isn't one. */
export function parseSlog(text, existingNames) {
  const root = JSON.parse(text);
  if (!root || root.format !== "slog") throw new Error("This isn't an S Log (.slog) file.");
  const seen = new Set(existingNames.map((n) => n.trim().toLowerCase()));
  return (Array.isArray(root.logs) ? root.logs : []).map((o, i) => {
    const name = String((o && o.name) || "").trim();
    const raw = (Array.isArray(o && o.times) ? o.times : []).map((t) => String(t).trim()).filter(Boolean);
    const history = dateMap(o && o.history, (t) => [...new Set(t)]);
    // v2 files: first day + earlier times, so imported streaks match the sender's
    const timesLog = dateMap(o && o.timesLog, normalizeTimes);
    const startDate = DATE_RE.test(String((o && o.startDate) || "")) ? o.startDate : "";
    return buildRow(i + 1, name, raw, history, seen, startDate, timesLog);
  });
}

/** Picks the right reader from the file content. */
export function parseAny(text, existingNames) {
  const t = text.replace(/^\uFEFF/, "");
  return t.trimStart().startsWith("{") ? parseSlog(t, existingNames) : parseCsv(t, existingNames);
}

// ---------------------------------------------------------------- export
const q = (s) => '"' + String(s).replace(/"/g, '""') + '"';

/** CSV: name,times -> "Gym","07:00;19:00" */
export function toCsv(logs) {
  return "name,times\n" + logs.map((l) => `${q(l.name)},${q(l.times.join(";"))}`).join("\n") + "\n";
}

/**
 * .slog JSON (format "slog", version 2): my tick history + the log's first day and time changes,
 * so the receiver sees the same streaks. Same format as the Android app.
 */
export function toSlog(logs, records, user, isActive) {
  return JSON.stringify({
    format: "slog",
    version: 2,
    app: "S Log",
    exportedAt: new Date().toISOString(),
    exportedBy: user.name,
    logs: logs.map((l) => {
      const plan = planFor(l, user.uid, records[l.id]);
      const history = {};
      (records[l.id] || [])
        .filter((r) => r.uid === user.uid)
        .sort((a, b) => (a.date < b.date ? -1 : 1))
        .forEach((r) => {
          const due = plan.timesOn(r.date); // the times the log had that day
          const ticks = r.checked.filter((t) => due.includes(t));
          if (ticks.length) history[r.date] = ticks;
        });
      const timesLog = {};
      Object.keys(l.timesLog || {}).sort().forEach((k) => (timesLog[k] = l.timesLog[k]));
      return { name: l.name, times: l.times, active: isActive(l), startDate: plan.start, timesLog, history };
    })
  }, null, 2);
}

export const safeFileName = (name) =>
  (String(name).replace(/[^A-Za-z0-9 _-]/g, "").trim().replace(/ /g, "_") || "log").slice(0, 40);

export const escapeHtml = (s) =>
  String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

// ---------------------------------------------------------------- streaks
// A day counts for a log when at least one of its times was ticked (partly done counts).
const nextDay = (key) => {
  const [y, m, d] = key.split("-").map(Number);
  return ymd(new Date(y, m - 1, d + 1));
};

/** Longest run of consecutive dates ("YYYY-MM-DD"). */
export function longestRun(keys) {
  const sorted = [...new Set(keys)].sort();
  let best = 0, run = 0, prev = null;
  for (const k of sorted) {
    run = prev && nextDay(prev) === k ? run + 1 : 1;
    best = Math.max(best, run);
    prev = k;
  }
  return best;
}

/** Run of complete days up to today (today counts once it's complete). */
export function currentRun(set) {
  let d = today();
  if (!set.has(ymd(d))) d = addDays(d, -1);
  let run = 0;
  while (set.has(ymd(d))) { run++; d = addDays(d, -1); }
  return run;
}

/** {month, best, current}: month = best run inside "YYYY-MM", best = best ever (incl. saved). */
export function streakStats(set, monthPrefix, storedBest) {
  return {
    month: longestRun([...set].filter((k) => k.startsWith(monthPrefix))),
    best: Math.max(storedBest || 0, longestRun([...set])),
    current: currentRun(set)
  };
}

// ------------------------------------------------------------- history
// Every day is judged with the logs and times that existed ON THAT DAY, so adding a log or a
// time today never changes the ticks, marks or streaks of earlier days. Same as Streaks.kt.
//   log.startDate  first day the log counts
//   log.timesLog   {date: times} - times in effect from that date
//   userState[uid].activeLog {date: on/off} - the switch, same idea

/** First day of a log: saved start date, else the day it was created, else today. */
export function startKey(log) {
  if (log.startDate && DATE_RE.test(log.startDate)) return log.startDate;
  if (log.createdAt > 0) return ymd(new Date(log.createdAt));
  return ymd(today());
}

const steps = (map) => Object.keys(map || {}).filter((k) => DATE_RE.test(k)).sort().map((k) => [k, map[k]]);
const lastStep = (list, key) => {
  let found = null;
  for (const st of list) { if (st[0] <= key) found = st; else break; }
  return found || list[0];
};

/** What a log asked for on any day ("YYYY-MM-DD" keys). */
export function planFor(log, uid, recs) {
  let start = startKey(log);
  if (!log.startDate) {
    // logs from before this update: also any earlier day that has ticks (imported history)
    for (const r of recs || []) if (r.checked && r.checked.length && r.date < start) start = r.date;
  }
  const timesSteps = steps(log.timesLog);
  const activeSteps = steps(log.userState?.[uid]?.activeLog);
  const currentActive = log.userState?.[uid]?.active ?? true;
  const exists = (key) => key >= start;
  const timesOn = (key) => {
    if (!exists(key)) return [];
    if (!timesSteps.length) return log.times;
    return lastStep(timesSteps, key)[1];
  };
  const activeOn = (key) => (activeSteps.length ? lastStep(activeSteps, key)[1] : currentActive);
  return { log, start, exists, timesOn, activeOn, requiredOn: (key) => (activeOn(key) ? timesOn(key) : []) };
}

/**
 * timesLog after changing the times from day fromKey on (the day being viewed, usually today).
 * Days before keep their times; any later change is replaced.
 */
export function timesLogAfterChange(log, newTimes, fromKey = ymd(today())) {
  const start = startKey(log);
  const day = fromKey < start ? start : fromKey;
  const old = log.timesLog || {};
  const map = {};
  for (const k of Object.keys(old)) if (k < day) map[k] = old[k];
  if (!Object.keys(old).length && start < day) map[start] = log.times;
  map[day] = normalizeTimes(newTimes);
  return map;
}

/** activeLog after switching a log on/off from day fromKey on (same idea). */
export function activeLogAfterToggle(log, uid, active, fromKey = ymd(today())) {
  const start = startKey(log);
  const day = fromKey < start ? start : fromKey;
  const old = log.userState?.[uid]?.activeLog || {};
  const map = {};
  for (const k of Object.keys(old)) if (k < day) map[k] = old[k];
  if (!Object.keys(old).length && start < day) map[start] = log.userState?.[uid]?.active ?? true;
  map[day] = active;
  return map;
}

/** My records of one log: {date: record} */
export function myRecords(recs, uid) {
  const out = {};
  for (const r of recs || []) if (r.uid === uid) out[r.date] = r;
  return out;
}

/** Dates on which uid ticked at least one of the times the log had THAT day (partly done counts). */
export function completeDays(plan, recs, uid) {
  const out = new Set();
  for (const r of recs || []) {
    if (r.uid !== uid) continue;
    const need = plan.timesOn(r.date);
    if (need.some((t) => r.checked.includes(t))) out.add(r.date);
  }
  return out;
}

/**
 * One day over every log that was on and existed that day:
 * {done, total} ticks, full = every time ticked, counts = at least one tick that day (partly done counts).
 */
export function dayProgress(plans, mine, key) {
  let done = 0, total = 0, started = 0, due = 0;
  for (const p of plans) {
    const need = p.requiredOn(key);
    if (!need.length) continue;
    total += need.length;
    due++;
    const r = mine[p.log.id]?.[key];
    const n = r ? need.filter((t) => r.checked.includes(t)).length : 0;
    done += n;
    if (n > 0) started++;
  }
  return { done, total, full: total > 0 && done >= total, counts: total > 0 && done > 0 };
}

/** Days from fromKey to toKey with at least one tick (partly done days count). */
export function completeWholeDays(plans, records, uid, fromKey, toKey = ymd(today())) {
  const out = new Set();
  if (!plans.length) return out;
  const mine = {};
  for (const p of plans) mine[p.log.id] = myRecords(records[p.log.id], uid);
  let key = plans.reduce((m, p) => (p.start < m ? p.start : m), plans[0].start);
  if (key < fromKey) key = fromKey;
  while (key <= toKey) {
    if (dayProgress(plans, mine, key).counts) out.add(key);
    key = nextDay(key);
  }
  return out;
}

// ---------------------------------------------------------- milestones
/** Max-streak milestones that get a celebration: 3, 10, 50, 100, 150 ... every 50, plus every 365. */
export const isMilestone = (n) => n === 3 || n === 10 || (n >= 50 && n % 50 === 0) || (n > 0 && n % 365 === 0);
export function milestoneFloor(n) {
  let m = n;
  while (m > 0 && !isMilestone(m)) m--;
  return m;
}
export function celebrationLine(days) {
  if (days >= 365 && days % 365 === 0) return `${days / 365} YEAR${days >= 730 ? "S" : ""} UNBROKEN. LEGEND.`;
  if (days >= 300) return "UNSTOPPABLE.";
  if (days >= 200) return "THIS IS WHO YOU ARE NOW.";
  if (days >= 100) return "TRIPLE DIGITS. RESPECT.";
  if (days >= 50) return "HALF A HUNDRED. KEEP GOING.";
  if (days >= 10) return "DOUBLE DIGITS. HABIT UNLOCKED.";
  return "GREAT START. KEEP THE CHAIN.";
}

// ------------------------------------------------------- dot-matrix art
// Nothing-style glyphs: '#' main dot, 'o' accent dot, '.' faint "off" dot.
export const GLYPHS = {
  // outline glyphs: crisp at small sizes
  flame: ["....#....", "...##....", "...#.#...", "..#...#..", ".#..o..#.", ".#.ooo.#.", "#..ooo..#", ".#..o..#.", "..#####.."],
  crown: ["o...o...o", "##.#.#.##", "#.#...#.#", "#.......#", "#.o.o.o.#", "#########"],
  month: [".#.....#.", "#########", "#.......#", "#.o.o.o.#", "#.......#", "#.o.o.o.#", "#.......#", "#########"]
};
export const DIGITS = {
  0: [".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."],
  1: ["..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."],
  2: [".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"],
  3: ["####.", "....#", "....#", ".###.", "....#", "....#", "####."],
  4: ["...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."],
  5: ["#####", "#....", "####.", "....#", "....#", "#...#", ".###."],
  6: ["..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."],
  7: ["#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."],
  8: [".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."],
  9: [".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."]
};

/**
 * SVG for a glyph. opts: {size (px height), main, accent, off, cls, sweep (animate dots lighting up)}
 */
export function dotSvg(pattern, opts = {}) {
  const { size = 16, main = "#fff", accent = "#ea1537", off = "rgba(255,255,255,.08)", cls = "", sweep = false } = opts;
  const rows = pattern.length, cols = Math.max(...pattern.map((r) => r.length));
  const w = (size * cols) / rows;
  let dots = "", i = 0;
  for (let y = 0; y < rows; y++) for (let x = 0; x < cols; x++) {
    const ch = pattern[y][x] || ".";
    const fill = ch === "#" ? main : ch === "o" ? accent : off;
    const on = ch !== ".";
    const delay = sweep && on ? ` style="animation-delay:${Math.round(((y * cols + x) / (rows * cols)) * 900)}ms"` : "";
    if (fill === "none" || fill === "transparent") { i++; continue; }
    dots += `<circle cx="${x + 0.5}" cy="${y + 0.5}" r=".4" fill="${fill}"${on && sweep ? ` class="lit"${delay}` : ""}/>`;
    i++;
  }
  return `<svg class="dots ${cls}" width="${w}" height="${size}" viewBox="0 0 ${cols} ${rows}" aria-hidden="true">${dots}</svg>`;
}

/** What's still left for uid on date: [{log, time}] */
export function leftFor(activeLogs, records, uid, date) {
  return activeLogs.flatMap((log) => {
    const mine = (records[log.id] || []).find((r) => r.uid === uid && r.date === date);
    return log.times.filter((t) => !(mine && mine.checked.includes(t))).map((time) => ({ log, time }));
  });
}

/** "Sorra Sri Harsha" -> "SSH", "Ravi Teja" -> "RT", "Teja" -> "T" (max 3 letters). */
export function initialsOf(name) {
  const parts = String(name || "").trim().split(/\s+/).filter(Boolean).slice(0, 3);
  return parts.map((p) => p[0].toUpperCase()).join("") || "?";
}
