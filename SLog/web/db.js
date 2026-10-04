// All Firebase access for the web app. Same database layout as the Android app:
//   logs/{logId}                       name, times[], ownerUid, ownerEmail, ownerName,
//                                      memberEmails[], createdAt,
//                                      startDate, timesLog{date: times[]}  (history, see utils.js)
//                                      userState{uid:{active, activeLog{date: bool}, order, best, celebrated}}
//   logs/{logId}/records/{date}_{uid}  uid, userName, date, logId, checked[]
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import {
  getAuth, onAuthStateChanged, signInWithEmailAndPassword, createUserWithEmailAndPassword,
  updateProfile, sendPasswordResetEmail, signOut as fbSignOut, deleteUser,
  EmailAuthProvider, reauthenticateWithCredential
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import {
  initializeFirestore, persistentLocalCache, persistentMultipleTabManager,
  collection, doc, query, where, limit, onSnapshot, setDoc, updateDoc, deleteDoc, getDocs, getDocsFromServer,
  getDocsFromCache, waitForPendingWrites,
  writeBatch, serverTimestamp, arrayUnion, arrayRemove, deleteField
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js";
import { firebaseConfig } from "./firebase-config.js";
import { normalizeTimes, ymd, today, timesLogAfterChange, activeLogAfterToggle, startKey } from "./utils.js";

export const configured = !String(firebaseConfig.apiKey).startsWith("PASTE");

const app = configured ? initializeApp(firebaseConfig) : null;
const auth = configured ? getAuth(app) : null;
const db = configured
  ? initializeFirestore(app, { localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() }) })
  : null;

const emailOf = (u) => (u.email || "").toLowerCase();
const nameOf = (u) => (u.displayName && u.displayName.trim()) || emailOf(u).split("@")[0];

// ---------------------------------------------------------------- auth
export function onAuth(cb) {
  onAuthStateChanged(auth, (u) => {
    if (!u) return cb({ state: "signedOut" });
    cb({ state: "signedIn", uid: u.uid, email: emailOf(u), name: nameOf(u) });
  });
}
// Registers the account with the registration PIN: users/{uid} = uid, email, name, pin, dates.
// The database rules only accept it when the PIN matches config/registration -> pin (stored in
// Firebase, never in this code). Wrong PIN -> throws an error with code "wrong-pin".
async function register(u, name, pin, isNew) {
  const data = {
    uid: u.uid, email: emailOf(u), name: (name && name.trim()) || nameOf(u), pin: String(pin).trim(),
    registeredAt: serverTimestamp(), lastLoginAt: serverTimestamp()
  };
  if (isNew) data.createdAt = serverTimestamp();
  try {
    await setDoc(doc(db, "users", u.uid), data, { merge: true });
  } catch (e) {
    if (e && e.code === "permission-denied") {
      const err = new Error("Wrong registration PIN.");
      err.code = "wrong-pin";
      throw err;
    }
    throw e;
  }
}
// Updates name + last login on a registered account (never blocks login)
function touchLogin(u) {
  setDoc(doc(db, "users", u.uid), { uid: u.uid, email: emailOf(u), name: nameOf(u), lastLoginAt: serverTimestamp() }, { merge: true })
    .catch(() => {});
}
export async function signIn(email, password) {
  const cred = await signInWithEmailAndPassword(auth, email.trim(), password);
  touchLogin(cred.user);
}
/** Creates the account and registers it with the PIN. Wrong PIN -> the new account is removed. */
export async function signUp(name, email, password, pin) {
  const cred = await createUserWithEmailAndPassword(auth, email.trim(), password);
  await updateProfile(cred.user, { displayName: name.trim() });
  try {
    await register(cred.user, name, pin, true);
  } catch (e) {
    if (e.code === "wrong-pin") {
      try { await deleteUser(cred.user); } catch { await fbSignOut(auth); }
    }
    throw e;
  }
}
/** For an account that hasn't entered the PIN yet (or after the PIN was changed). */
export async function completeRegistration(pin) {
  if (auth.currentUser) await register(auth.currentUser, null, pin, false);
}
/** True when this account has entered the current PIN (a server read only registered accounts may do). */
export async function isRegistered(email) {
  try {
    await getDocsFromServer(query(collection(db, "logs"), where("memberEmails", "array-contains", email), limit(1)));
    return true;
  } catch (e) {
    return !(e && e.code === "permission-denied"); // offline etc. -> let them in; rules still protect data
  }
}
export const resetPassword = (email) => sendPasswordResetEmail(auth, email.trim());
export const signOut = () => fbSignOut(auth);
export const currentUserName = () => (auth.currentUser ? nameOf(auth.currentUser) : "");
export const currentAuth = () => {
  const u = auth.currentUser;
  return u ? { uid: u.uid, email: emailOf(u), name: nameOf(u) } : null;
};

// ----------------------------------------------------------- listeners
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const cleanTimesLog = (m) => {
  const out = {};
  for (const [k, v] of Object.entries(m || {})) if (DATE_RE.test(k) && Array.isArray(v)) out[k] = normalizeTimes(v);
  return out;
};
const fromLogDoc = (d) => {
  // estimate: a new log still waiting for the server gets the device time
  const x = d.data({ serverTimestamps: "estimate" });
  return {
    id: d.id,
    name: x.name || "",
    times: normalizeTimes(x.times || []),
    ownerUid: x.ownerUid || "",
    ownerEmail: x.ownerEmail || "",
    ownerName: x.ownerName || "",
    memberEmails: x.memberEmails || [],
    userState: x.userState || {},
    createdAt: x.createdAt && x.createdAt.toMillis ? x.createdAt.toMillis() : 0,
    startDate: DATE_RE.test(x.startDate || "") ? x.startDate : "",
    timesLog: cleanTimesLog(x.timesLog)
  };
};

export function listenLogs(email, cb) {
  return onSnapshot(
    query(collection(db, "logs"), where("memberEmails", "array-contains", email)),
    (snap) => cb(snap.docs.map(fromLogDoc)),
    (err) => console.error("logs listener", err)
  );
}

/**
 * Every member's ticks for one log from fromDate on. onError: the listener has stopped (Firestore
 * ends a listener after an error) - e.g. a log created a moment ago isn't on the server yet and gets
 * refused once. The caller listens again.
 */
export function listenRecords(logId, fromDate, cb, onError) {
  return onSnapshot(
    query(collection(db, "logs", logId, "records"), where("date", ">=", fromDate)),
    (snap) => cb(snap.docs.map((d) => ({ id: d.id, logId, ...d.data(), checked: d.data().checked || [] }))),
    (err) => {
      console.warn("records listener stopped, retrying", logId, err && err.code);
      onError && onError(err);
    }
  );
}

// ---------------------------------------------------------------- logs
/**
 * Creates a log. startDate = first day it counts (today, or the first history day for imports);
 * timesLog = earlier time changes from a .slog file (default: these times from the start).
 */
export function addLog(user, name, times, order, startDate = ymd(today()), timesLog = {}) {
  const ref = doc(collection(db, "logs"));
  const clean = normalizeTimes(times);
  const history = {};
  for (const [k, v] of Object.entries(timesLog || {})) if (k >= startDate) history[k] = v;
  if (!Object.keys(history).some((k) => k <= startDate)) {
    const before = Object.keys(timesLog || {}).filter((k) => k < startDate).sort().pop();
    const first = Object.keys(history).sort()[0];
    history[startDate] = before ? timesLog[before] : first ? history[first] : clean;
  }
  setDoc(ref, {
    name: name.trim(),
    times: clean,
    ownerUid: user.uid,
    ownerEmail: user.email,
    ownerName: user.name,
    memberEmails: [user.email],
    userState: { [user.uid]: { active: true, order } },
    startDate,
    timesLog: history,
    createdAt: serverTimestamp()
  }).catch((e) => console.error(e));
  return ref.id;
}
/** Rename / change times. Time changes apply from fromKey (day being viewed) on; earlier days keep theirs. */
export function updateLog(log, name, times, fromKey = ymd(today())) {
  const clean = normalizeTimes(times);
  const data = { name: name.trim() };
  if (clean.join() !== log.times.join()) {
    data.times = clean;
    data.timesLog = timesLogAfterChange(log, clean, fromKey);
    if (!log.startDate) data.startDate = startKey(log);
  }
  return updateDoc(doc(db, "logs", log.id), data);
}
/** Switch on/off from fromKey on (earlier days keep the state they had). */
export const setActive = (log, uid, active, fromKey = ymd(today())) =>
  updateDoc(doc(db, "logs", log.id), {
    [`userState.${uid}.active`]: active,
    [`userState.${uid}.activeLog`]: activeLogAfterToggle(log, uid, active, fromKey)
  });
/** Highest streak milestone already celebrated on a log / on the whole day. */
export const saveLogCelebrated = (logId, uid, milestone) =>
  updateDoc(doc(db, "logs", logId), { [`userState.${uid}.celebrated`]: milestone }).catch(() => {});
export function saveDayCelebrated(milestone) {
  const u = auth.currentUser;
  if (!u) return;
  setDoc(doc(db, "users", u.uid), { uid: u.uid, email: emailOf(u), name: nameOf(u), celebratedDay: milestone }, { merge: true })
    .catch(() => {});
}

export function saveOrder(uid, ids) {
  const b = writeBatch(db);
  ids.forEach((id, i) => b.update(doc(db, "logs", id), { [`userState.${uid}.order`]: i }));
  return b.commit();
}

// Owner: deletes the log + all its ticks. Member: leaves. Works offline: it disappears here at once
// and the delete is uploaded automatically when the network is back (queued writes are kept).
export async function deleteOrLeave(log, user) {
  if (log.ownerUid === user.uid) {
    const ref = collection(db, "logs", log.id, "records");
    let docs = [];
    try {
      docs = navigator.onLine ? (await getDocs(ref)).docs : (await getDocsFromCache(ref)).docs;
    } catch {
      try { docs = (await getDocsFromCache(ref)).docs; } catch { docs = []; }
    }
    for (let i = 0; i < docs.length; i += 400) {
      const b = writeBatch(db);
      docs.slice(i, i + 400).forEach((d) => b.delete(d.ref));
      b.commit().catch((e) => console.error("delete ticks", e));
    }
    deleteDoc(doc(db, "logs", log.id)).catch((e) => console.error("delete log", e));
  } else {
    updateDoc(doc(db, "logs", log.id), {
      memberEmails: arrayRemove(user.email),
      [`userState.${user.uid}`]: deleteField()
    }).catch((e) => console.error("leave log", e));
  }
}

/** Remembers a new best streak for a log (only ever goes up). */
export const saveLogBest = (logId, uid, best) =>
  updateDoc(doc(db, "logs", logId), { [`userState.${uid}.best`]: best }).catch(() => {});

/** Remembers a new best whole-day streak on my user entry. */
export function saveDayBest(best) {
  const u = auth.currentUser;
  if (!u) return;
  setDoc(doc(db, "users", u.uid), { uid: u.uid, email: emailOf(u), name: nameOf(u), bestDayStreak: best }, { merge: true })
    .catch(() => {});
}

/** My user entry -> saved best whole-day streak + highest celebrated milestone (null = never). */
export function listenProfile(uid, cb) {
  return onSnapshot(doc(db, "users", uid), (snap) => {
    const x = snap.exists() ? snap.data() : {};
    cb(x.bestDayStreak || 0, typeof x.celebratedDay === "number" ? x.celebratedDay : null);
  }, () => {});
}

/** Deletes the account for good: my logs + ticks, leaves shared logs, my user entry, the login. */
export async function deleteAccount(password) {
  const u = auth.currentUser;
  if (!u) return;
  await reauthenticateWithCredential(u, EmailAuthProvider.credential(u.email, password));
  const snap = await getDocs(query(collection(db, "logs"), where("memberEmails", "array-contains", emailOf(u))));
  const me = { uid: u.uid, email: emailOf(u), name: nameOf(u) };
  for (const d of snap.docs) await deleteOrLeave({ id: d.id, ...d.data() }, me);
  await waitForPendingWrites(db);
  await deleteDoc(doc(db, "users", u.uid));
  await deleteUser(u);
}

export const addMember = (logId, email) =>
  updateDoc(doc(db, "logs", logId), { memberEmails: arrayUnion(email.trim().toLowerCase()) });
export const removeMember = (logId, email) =>
  updateDoc(doc(db, "logs", logId), { memberEmails: arrayRemove(email.trim().toLowerCase()) });

/** Writes imported tick history (from a .slog file) as my ticks on a newly imported log. */
export function importHistory(logId, user, history) {
  const entries = Object.entries(history);
  for (let i = 0; i < entries.length; i += 200) {
    const b = writeBatch(db);
    entries.slice(i, i + 200).forEach(([date, ticks]) => {
      b.set(doc(db, "logs", logId, "records", `${date}_${user.uid}`), {
        uid: user.uid, userName: user.name, date, logId, checked: ticks, updatedAt: serverTimestamp()
      });
    });
    b.commit().catch((e) => console.error("history import", e));
  }
}

// --------------------------------------------------------------- ticks
export function setChecked(logId, user, date, time, checked) {
  return setDoc(
    doc(db, "logs", logId, "records", `${date}_${user.uid}`),
    {
      uid: user.uid,
      userName: user.name,
      date,
      logId,
      checked: checked ? arrayUnion(time) : arrayRemove(time),
      updatedAt: serverTimestamp()
    },
    { merge: true }
  );
}
