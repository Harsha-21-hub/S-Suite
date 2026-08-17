package com.hesi.scharge.core

import java.io.File

/**
 * Reads /sys/class/power_supply nodes.
 * Tries a plain (non-root) file read first — most battery nodes are
 * world-readable — and falls back to root `cat` only if needed.
 * The resolved path (or a "missing" marker) is cached per name set,
 * so after the first tick every read is a single cheap file read
 * with no shell calls at all.
 */
object Nodes {

    private val BATTERY_DIRS = listOf(
        "/sys/class/power_supply/battery",
        "/sys/class/power_supply/bms",
        "/sys/class/power_supply/bat"
    )

    private val INPUT_DIRS = listOf(
        "/sys/class/power_supply/usb",
        "/sys/class/power_supply/ac",
        "/sys/class/power_supply/main",
        "/sys/class/power_supply/pc_port"
    )

    private val cache = HashMap<String, String>() // key -> resolved path, "" = not present

    /** Drops all resolved paths so the next read re-detects every node. */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    fun readBattery(vararg names: String): String = readFrom("bat", BATTERY_DIRS, names)

    fun readInput(vararg names: String): String = readFrom("inp", INPUT_DIRS, names)

    /**
     * Returns the first value across all battery dirs/names that parses to a
     * POSITIVE Int, or -1. Unlike [readBattery] this skips nodes that read "0"
     * (some kernels expose a battery/cycle_count that is always 0 while the real
     * count lives in bms/). The winning path is cached.
     */
    fun readBatteryInt(vararg names: String): Int {
        val key = "batint:" + names.joinToString(",")
        val cached: String?
        synchronized(cache) { cached = cache[key] }
        if (cached != null) {
            if (cached.isEmpty()) return -1
            readPath(cached)?.trim()?.toIntOrNull()?.let { if (it > 0) return it }
            // Cached path stopped giving a positive value; re-resolve.
        }
        for (dir in BATTERY_DIRS) {
            for (name in names) {
                val v = readPath("$dir/$name")?.trim()?.toIntOrNull()
                if (v != null && v > 0) {
                    synchronized(cache) { cache[key] = "$dir/$name" }
                    return v
                }
            }
        }
        synchronized(cache) { cache[key] = "" }
        return -1
    }

    /**
     * Root-only, last-resort discovery for a positive-valued node whose filename
     * matches [pattern] (e.g. "*cycle*") but lives outside the standard supply
     * dirs (common on custom kernels: /sys/devices/platform/..., virtual/...).
     * Runs the expensive `find` at most once, then caches the winning path so
     * every later read is a single cheap file read.
     */
    fun findPositiveIntByName(cacheKey: String, pattern: String): Int {
        val key = "find:$cacheKey"
        val cached: String?
        synchronized(cache) { cached = cache[key] }
        if (cached != null) {
            if (cached.isEmpty()) return -1
            readPath(cached)?.trim()?.toIntOrNull()?.let { if (it > 0) return it }
            return -1
        }
        if (!RootShell.isRootAvailable()) return -1
        val listing = RootShell.exec(
            "find /sys -type f -iname \"$pattern\" 2>/dev/null"
        )
        for (path in listing.lineSequence()) {
            val p = path.trim()
            if (p.isEmpty()) continue
            val v = readPath(p)?.trim()?.toIntOrNull()
            if (v != null && v > 0) {
                synchronized(cache) { cache[key] = p }
                return v
            }
        }
        synchronized(cache) { cache[key] = "" } // don't scan /sys again
        return -1
    }

    private fun readFrom(tag: String, dirs: List<String>, names: Array<out String>): String {
        val key = tag + ":" + names.joinToString(",")
        val cached: String?
        synchronized(cache) { cached = cache[key] }
        if (cached != null) {
            if (cached.isEmpty()) return ""
            readPath(cached)?.let { return it }
            // Path stopped working; fall through and re-resolve.
        }
        for (dir in dirs) {
            for (name in names) {
                val path = "$dir/$name"
                val value = readPath(path)
                if (value != null) {
                    synchronized(cache) { cache[key] = path }
                    return value
                }
            }
        }
        synchronized(cache) { cache[key] = "" }
        return ""
    }

    private fun readPath(path: String): String? {
        try {
            val f = File(path)
            if (f.canRead()) {
                val v = f.readText().trim()
                if (v.isNotEmpty()) return v
            } else if (!f.exists() && !RootShell.isRootAvailable()) {
                return null
            }
        } catch (_: Throwable) {
        }
        if (RootShell.isRootAvailable()) {
            val v = RootShell.exec("cat $path 2>/dev/null")
            if (v.isNotEmpty()) return v
        }
        return null
    }
}
