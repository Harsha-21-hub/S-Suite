package com.hesi.scharge.core

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Single persistent `su` shell for commands, plus a lightweight one-shot
 * `su -c id` for the root check.
 *
 * This is the ORIGINAL, known-good detection method. Do not replace the
 * `su -c id` probe with an interactive shell — some superuser managers answer
 * `su -c id` reliably but behave differently for a bare interactive `su`,
 * which is what caused root to read as "unavailable".
 *
 * If your device is rooted but this still returns false, the superuser manager
 * has a saved "Deny" for this app: open it (e.g. Magisk -> Superuser -> S Charge)
 * and set the app to "Grant" (or delete the entry so it can prompt again), then
 * press the refresh button in the app.
 */
object RootShell {

    private const val MARKER = "---SCHARGE-EOC---"

    @Volatile
    private var cachedRoot: Boolean? = null

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var stdout: BufferedReader? = null
    private val lock = Any()

    /** True if the device is rooted AND the user granted su to this app. Cached. */
    fun isRootAvailable(): Boolean {
        cachedRoot?.let { return it }
        synchronized(lock) {
            cachedRoot?.let { return it }
            val result = probe()
            cachedRoot = result
            return result
        }
    }

    /**
     * Forgets the cached result and asks again -- used by the refresh button so a
     * missed first prompt, or a policy the user just switched to "Grant", takes
     * effect without restarting the app.
     */
    fun recheck(): Boolean {
        synchronized(lock) {
            cachedRoot = null
            closeShell()
            val result = probe()
            cachedRoot = result
            return result
        }
    }

    private fun probe(): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val finished = p.waitFor(6, TimeUnit.SECONDS)
            val output = if (finished) {
                p.inputStream.bufferedReader().use { it.readText() }
            } else {
                p.destroyForcibly()
                ""
            }
            finished && output.contains("uid=0")
        } catch (t: Throwable) {
            false
        }
    }

    /** Runs a shell command as root and returns trimmed stdout ("" on failure). */
    fun exec(command: String): String {
        if (!isRootAvailable()) return ""
        synchronized(lock) {
            return try {
                ensureShell()
                val writer = stdin ?: return ""
                val reader = stdout ?: return ""
                writer.write(command)
                writer.newLine()
                writer.write("echo $MARKER")
                writer.newLine()
                writer.flush()
                val sb = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.contains(MARKER)) break
                    sb.append(line).append('\n')
                }
                sb.toString().trim()
            } catch (t: Throwable) {
                closeShell()
                ""
            }
        }
    }

    private fun ensureShell() {
        val p = process
        if (p != null && p.isAlive) return
        closeShell()
        val np = ProcessBuilder("su").redirectErrorStream(true).start()
        process = np
        stdin = BufferedWriter(OutputStreamWriter(np.outputStream))
        stdout = BufferedReader(InputStreamReader(np.inputStream))
    }

    private fun closeShell() {
        try {
            process?.destroyForcibly()
        } catch (_: Throwable) {
        }
        process = null
        stdin = null
        stdout = null
    }
}
