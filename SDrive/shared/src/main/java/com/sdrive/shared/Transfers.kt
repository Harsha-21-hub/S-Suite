package com.sdrive.shared

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class TState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class Transfer(
    val id: Long,
    val name: String,
    val incoming: Boolean,
    val via: String,
    val total: Long,
    val done: Long = 0,
    val state: TState = TState.RUNNING,
    val bps: Long = 0,
    val error: String? = null,
    val note: String? = null,
    val savedPath: String? = null,
    val startedAt: Long = SystemClock.elapsedRealtime(),
)

/** Live list of transfers (both directions, all transports). UI updates are throttled to save CPU. */
object Transfers {
    private val _list = MutableStateFlow<List<Transfer>>(emptyList())
    val list: StateFlow<List<Transfer>> = _list.asStateFlow()
    private val ids = AtomicLong()
    private val handles = ConcurrentHashMap<Long, Handle>()

    @Volatile var lastActivity: Long = SystemClock.elapsedRealtime()
        private set

    fun touch() { lastActivity = SystemClock.elapsedRealtime() }

    val active: Boolean get() = _list.value.any { it.state == TState.RUNNING || it.state == TState.QUEUED }

    fun begin(name: String, incoming: Boolean, via: String, total: Long, queued: Boolean = false): Handle {
        val t = Transfer(ids.incrementAndGet(), name, incoming, via, total, state = if (queued) TState.QUEUED else TState.RUNNING)
        _list.update { (listOf(t) + it).take(80) }
        val h = Handle(t.id)
        handles[t.id] = h
        touch()
        return h
    }

    internal fun edit(id: Long, f: (Transfer) -> Transfer) =
        _list.update { l -> l.map { if (it.id == id) f(it) else it } }

    fun cancel(id: Long) {
        handles[id]?.cancelled = true
        edit(id) { if (it.state == TState.QUEUED) it.copy(state = TState.CANCELLED) else it }
    }

    fun cancelAll() = handles.keys.forEach { cancel(it) }

    fun clearFinished() = _list.update { l -> l.filter { it.state == TState.RUNNING || it.state == TState.QUEUED } }

    class CancelledIo : IOException("Cancelled")

    class Handle internal constructor(val id: Long) {
        @Volatile var cancelled = false
        private var lastT = SystemClock.elapsedRealtime()
        private var lastB = 0L
        private var bps = 0.0

        fun start() {
            if (cancelled) throw CancelledIo()
            lastT = SystemClock.elapsedRealtime(); lastB = 0
            edit(id) { it.copy(state = TState.RUNNING, startedAt = lastT) }
        }

        fun note(text: String?) = edit(id) { it.copy(note = text) }

        fun retarget(name: String, total: Long) = edit(id) { it.copy(name = name, total = total, done = 0, note = null) }

        fun progress(done: Long) {
            if (cancelled) throw CancelledIo()
            val now = SystemClock.elapsedRealtime()
            val dt = now - lastT
            if (dt < 350) return
            val inst = (done - lastB) * 1000.0 / dt
            bps = if (bps == 0.0) inst else bps * 0.6 + inst * 0.4
            lastT = now; lastB = done
            touch()
            edit(id) { it.copy(done = done, bps = bps.toLong()) }
        }

        fun finish(saved: String? = null) {
            handles.remove(id)
            edit(id) { it.copy(done = maxOf(it.total, it.done), state = TState.DONE, bps = 0, note = null, savedPath = saved) }
            touch()
        }

        fun fail(e: Throwable) {
            handles.remove(id)
            val c = cancelled || e is CancelledIo
            edit(id) {
                it.copy(state = if (c) TState.CANCELLED else TState.FAILED, bps = 0, note = null,
                    error = if (c) null else (e.message ?: e.javaClass.simpleName))
            }
            touch()
        }
    }
}

/** Files the watch/phone has offered to the PC browser. The web page polls and downloads them. */
object Outbox {
    data class Item(val id: Long, val file: File)
    private val ids = AtomicLong()
    val items = MutableStateFlow<List<Item>>(emptyList())

    fun add(files: List<File>) {
        val flat = files.flatMap { f -> if (f.isDirectory) f.walkTopDown().filter { it.isFile }.toList() else listOf(f) }
        items.update { it + flat.map { f -> Item(ids.incrementAndGet(), f) } }
    }
    fun get(id: Long) = items.value.firstOrNull { it.id == id }
    fun remove(id: Long) = items.update { l -> l.filterNot { it.id == id } }
    fun clear() { items.value = emptyList() }
}