package com.hesi.slog

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * All access to Firebase (login + Firestore database).
 *
 * Firestore layout:
 *   logs/{logId}                         name, times[], ownerUid, ownerEmail, ownerName,
 *                                        memberEmails[], createdAt,
 *                                        startDate, timesLog{date: times[]}   (history, see LogItem)
 *                                        userState{uid:{active, activeLog{date: bool}, order,
 *                                                       best, celebrated}}
 *   logs/{logId}/records/{date}_{uid}    uid, userName, date, logId, checked[]
 *
 * Firestore keeps a local cache, so the app keeps working offline and syncs later.
 */
object FirebaseRepo {

    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun logs() = db.collection("logs")
    private fun records(logId: String) = logs().document(logId).collection("records")

    /** True when the build includes app/google-services.json (Firebase started). */
    val isReady: Boolean
        get() = try {
            FirebaseApp.getInstance()
            true
        } catch (e: IllegalStateException) {
            false
        }

    private fun users() = db.collection("users")

    /**
     * Registers the account in the database with the registration PIN:
     * users/{uid} = uid, email, name, pin, dates. The database rules accept it only when the PIN
     * matches config/registration -> pin. Throws [WrongPinException] when it doesn't.
     */
    private suspend fun register(user: FirebaseUser, name: String?, pin: String, isNew: Boolean) {
        val data = mutableMapOf<String, Any>(
            "uid" to user.uid,
            "email" to emailOf(user),
            "name" to (name?.trim()?.takeIf { it.isNotEmpty() } ?: nameOf(user)),
            "pin" to pin.trim(),
            "registeredAt" to FieldValue.serverTimestamp(),
            "lastLoginAt" to FieldValue.serverTimestamp()
        )
        if (isNew) data["createdAt"] = FieldValue.serverTimestamp()
        try {
            users().document(user.uid).set(data, SetOptions.merge()).await()
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) throw WrongPinException()
            throw e
        }
    }

    /** Updates name + last login on an already registered account (never blocks login). */
    private fun touchLogin(user: FirebaseUser) {
        users().document(user.uid).set(
            mapOf(
                "uid" to user.uid,
                "email" to emailOf(user),
                "name" to nameOf(user),
                "lastLoginAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        )
    }

    /**
     * True when this account has entered the current registration PIN.
     * Asks the server (a read that only registered accounts may do). Offline -> true, so the
     * app still opens with cached data; the database rules still protect everything.
     */
    suspend fun isRegistered(user: FirebaseUser): Boolean = try {
        logs().whereArrayContains("memberEmails", emailOf(user)).limit(1).get(Source.SERVER).await()
        true
    } catch (e: FirebaseFirestoreException) {
        e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED
    } catch (e: Exception) {
        true
    }

    /** For an existing account that hasn't entered the PIN yet (or after the PIN was changed). */
    suspend fun completeRegistration(pin: String) {
        val user = currentUser ?: return
        register(user, null, pin, isNew = false)
    }

    /** Null when signed out or when Firebase isn't set up yet (never throws). */
    val currentUser: FirebaseUser?
        get() = try {
            auth.currentUser
        } catch (e: IllegalStateException) {
            null
        }
    fun emailOf(user: FirebaseUser): String = (user.email ?: "").lowercase(Locale.ROOT)
    fun nameOf(user: FirebaseUser): String =
        user.displayName?.takeIf { it.isNotBlank() } ?: emailOf(user).substringBefore("@")

    // ------------------------------------------------------------------ Auth

    /** Creates the account, then registers it with the PIN. Wrong PIN -> the new account is removed. */
    suspend fun signUp(name: String, email: String, password: String, pin: String) {
        val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        val user = result.user ?: return
        user.updateProfile(
            UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()
        ).await()
        try {
            register(user, name, pin, isNew = true)
        } catch (e: WrongPinException) {
            try {
                user.delete().await()
            } catch (_: Exception) {
                auth.signOut()
            }
            throw e
        }
    }

    suspend fun signIn(email: String, password: String) {
        val user = auth.signInWithEmailAndPassword(email.trim(), password).await().user ?: return
        touchLogin(user)
    }

    suspend fun sendPasswordReset(email: String) {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    fun signOut() = auth.signOut()

    /**
     * Deletes the account for good: my logs (and their ticks), leaves logs shared with me,
     * removes my users/{uid} entry and the login itself. Needs the password again (Firebase rule).
     */
    suspend fun deleteAccount(password: String) {
        val user = currentUser ?: return
        val email = user.email ?: return
        user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
        fetchLogs(emailOf(user)).forEach { deleteOrLeave(it, user) }
        db.waitForPendingWrites().await() // make sure the deletes reach the server before the login goes
        users().document(user.uid).delete().await()
        user.delete().await()
    }

    // ------------------------------------------------------------- Listeners

    fun listenLogs(email: String, onChange: (List<LogItem>) -> Unit): ListenerRegistration =
        logs().whereArrayContains("memberEmails", email)
            .addSnapshotListener { snap, error ->
                if (error != null) android.util.Log.e("SLog", "logs listener", error)
                if (error != null || snap == null) return@addSnapshotListener
                onChange(snap.documents.mapNotNull { runCatching { LogItem.from(it) }.getOrNull() })
            }

    /**
     * Every member's ticks for one log from [fromDate] (yyyy-MM-dd) onwards.
     * [onError]: the listener has stopped (Firestore ends a listener after an error) - e.g. a log
     * that was just created and isn't on the server yet gets refused once. The caller re-listens.
     */
    fun listenRecords(
        logId: String,
        fromDate: String,
        onError: (Exception) -> Unit = {},
        onChange: (List<DayRecord>) -> Unit
    ): ListenerRegistration =
        records(logId).whereGreaterThanOrEqualTo("date", fromDate)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    android.util.Log.w("SLog", "records listener $logId stopped, retrying", error)
                    onError(error)
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                onChange(snap.documents.mapNotNull { runCatching { DayRecord.from(logId, it) }.getOrNull() })
            }

    // ----------------------------------------------------------------- Logs

    /**
     * Creates a log and returns its id. Works offline (the write is queued).
     * [startDate]: first day it counts (today for a new log, the first history day for imports).
     * [timesLog]: earlier time changes (from a .slog file); default = these times from the start.
     */
    fun addLog(
        user: FirebaseUser,
        name: String,
        times: List<String>,
        order: Int,
        startDate: String = DateUtils.getCurrentDateString(),
        timesLog: Map<String, List<String>> = emptyMap()
    ): String {
        val ref = logs().document()
        val email = emailOf(user)
        val clean = TimeUtils.normalize(times)
        val history = timesLog.filterKeys { it >= startDate }.toMutableMap()
        if (history.keys.none { it <= startDate }) {
            history[startDate] = timesLog.entries.filter { it.key < startDate }.maxByOrNull { it.key }?.value
                ?: history.entries.minByOrNull { it.key }?.value
                ?: clean
        }
        ref.set(
            mapOf(
                "name" to name.trim(),
                "times" to clean,
                "ownerUid" to user.uid,
                "ownerEmail" to email,
                "ownerName" to nameOf(user),
                "memberEmails" to listOf(email),
                "userState" to mapOf(user.uid to mapOf("active" to true, "order" to order)),
                "startDate" to startDate,
                "timesLog" to history,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        return ref.id
    }

    /**
     * Renames / changes times. A time change is recorded in timesLog from [fromDate] on (the day
     * being viewed, today by default), so the ticks and streaks of earlier days stay as they were.
     */
    fun updateLog(log: LogItem, name: String, times: List<String>, fromDate: String = DateUtils.getCurrentDateString()) {
        val clean = TimeUtils.normalize(times)
        val data = mutableMapOf<String, Any>("name" to name.trim())
        if (clean != log.times) {
            data["times"] = clean
            data["timesLog"] = log.timesLogAfterChange(clean, fromDate)
            if (log.startDate.isBlank()) data["startDate"] = log.startKey()
        }
        logs().document(log.id).update(data)
    }

    /** Switch on/off from [fromDate] on (earlier days keep the state they had). */
    fun setActive(log: LogItem, uid: String, active: Boolean, fromDate: String = DateUtils.getCurrentDateString()) {
        logs().document(log.id).update(
            mapOf(
                "userState.$uid.active" to active,
                "userState.$uid.activeLog" to log.activeLogAfterToggle(uid, active, fromDate)
            )
        )
    }

    /** Remembers the highest streak milestone already celebrated on this log. */
    fun saveLogCelebrated(logId: String, uid: String, milestone: Int) {
        logs().document(logId).update("userState.$uid.celebrated", milestone)
    }

    /** Same for the whole-day streak (all logs). */
    fun saveDayCelebrated(user: FirebaseUser, milestone: Int) {
        users().document(user.uid).set(
            mapOf(
                "uid" to user.uid,
                "email" to emailOf(user),
                "name" to nameOf(user),
                "celebratedDay" to milestone
            ),
            SetOptions.merge()
        )
    }

    /** Remembers a new best streak for this log (only ever goes up). */
    fun saveLogBest(logId: String, uid: String, best: Int) {
        logs().document(logId).update("userState.$uid.best", best)
    }

    /** Remembers a new best whole-day streak (all logs done) on the user's entry. */
    fun saveDayBest(user: FirebaseUser, best: Int) {
        users().document(user.uid).set(
            mapOf(
                "uid" to user.uid,
                "email" to emailOf(user),
                "name" to nameOf(user),
                "bestDayStreak" to best
            ),
            SetOptions.merge()
        )
    }

    /** The user's own entry (users/{uid}): saved best whole-day streak + celebrated milestone. */
    fun listenProfile(uid: String, onChange: (bestDayStreak: Int, celebratedDay: Int?) -> Unit): ListenerRegistration =
        users().document(uid).addSnapshotListener { snap, error ->
            if (error != null || snap == null) return@addSnapshotListener
            onChange(
                (snap.get("bestDayStreak") as? Number)?.toInt() ?: 0,
                (snap.get("celebratedDay") as? Number)?.toInt()
            )
        }

    fun saveOrder(uid: String, orderedIds: List<String>) {
        val batch = db.batch()
        orderedIds.forEachIndexed { index, id ->
            batch.update(logs().document(id), "userState.$uid.order", index)
        }
        batch.commit()
    }

    /**
     * Owner: deletes the log and all its ticks from the database. Member: leaves the shared log.
     * Works offline too: it disappears on this device straight away and the delete is uploaded
     * automatically when the network is back (pending writes survive app restarts).
     */
    suspend fun deleteOrLeave(log: LogItem, user: FirebaseUser) {
        if (log.isOwner(user.uid)) {
            // server when online, local cache when offline
            val recs = try {
                records(log.id).get().await().documents
            } catch (e: Exception) {
                emptyList()
            }
            // ticks first (Firestore batches hold max 500 writes), then the log itself;
            // writes are queued in this order and not awaited, so nothing waits for the network
            recs.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit()
            }
            logs().document(log.id).delete()
        } else {
            leave(log.id, user)
        }
    }

    fun leave(logId: String, user: FirebaseUser) {
        logs().document(logId).update(
            mapOf(
                "memberEmails" to FieldValue.arrayRemove(emailOf(user)),
                "userState.${user.uid}" to FieldValue.delete()
            )
        )
    }

    // -------------------------------------------------------------- Sharing

    suspend fun addMember(logId: String, email: String) {
        logs().document(logId)
            .update("memberEmails", FieldValue.arrayUnion(email.trim().lowercase(Locale.ROOT)))
            .await()
    }

    suspend fun removeMember(logId: String, email: String) {
        logs().document(logId)
            .update("memberEmails", FieldValue.arrayRemove(email.trim().lowercase(Locale.ROOT)))
            .await()
    }

    // ---------------------------------------------------------------- Ticks

    fun setChecked(logId: String, user: FirebaseUser, date: String, time: String, checked: Boolean) {
        records(logId).document(DayRecord.docId(date, user.uid)).set(
            mapOf(
                "uid" to user.uid,
                "userName" to nameOf(user),
                "date" to date,
                "logId" to logId,
                "checked" to if (checked) FieldValue.arrayUnion(time) else FieldValue.arrayRemove(time),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        )
    }

    /** Writes imported tick history (from a .slog file) as my ticks on a newly imported log. */
    fun importHistory(logId: String, user: FirebaseUser, history: Map<String, List<String>>) {
        history.entries.chunked(200).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { (date, ticks) ->
                batch.set(
                    records(logId).document(DayRecord.docId(date, user.uid)),
                    mapOf(
                        "uid" to user.uid,
                        "userName" to nameOf(user),
                        "date" to date,
                        "logId" to logId,
                        "checked" to ticks,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit()
        }
    }

    // ------------------------------------------------- One-shot reads (background)

    suspend fun fetchLogs(email: String): List<LogItem> =
        logs().whereArrayContains("memberEmails", email).get().await()
            .documents.mapNotNull { LogItem.from(it) }

    suspend fun fetchLog(logId: String): LogItem? =
        logs().document(logId).get().await().let { if (it.exists()) LogItem.from(it) else null }

    suspend fun fetchMyRecord(logId: String, uid: String, date: String): DayRecord? =
        records(logId).document(DayRecord.docId(date, uid)).get().await()
            .let { if (it.exists()) DayRecord.from(logId, it) else null }
}

class WrongPinException : Exception("Wrong registration PIN.")
