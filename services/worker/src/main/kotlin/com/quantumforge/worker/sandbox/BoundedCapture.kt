package com.quantumforge.worker.sandbox

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

data class CapturedOutput(val file: Path, val totalBytes: Long, val storedBytes: Long) {
    val truncated get() = totalBytes > storedBytes
}

/**
 * Drains a process stream on its own thread, keeping at most [limit] bytes on
 * disk. Draining continues past the limit so a chatty process never blocks on a
 * full pipe.
 */
class BoundedCapture(private val input: InputStream, private val file: Path, private val limit: Long) {
    @Volatile private var total = 0L
    @Volatile private var stored = 0L
    private val thread = Thread(::pump, "capture-${file.fileName}").apply { isDaemon = true }

    fun start(): BoundedCapture = apply { thread.start() }

    fun finish(timeoutMillis: Long = 5000): CapturedOutput {
        thread.join(timeoutMillis)
        return CapturedOutput(file, total, stored)
    }

    private fun pump() {
        Files.newOutputStream(file).use { out ->
            val buffer = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    val room = (limit - stored).coerceAtLeast(0).coerceAtMost(n.toLong()).toInt()
                    if (room > 0) {
                        out.write(buffer, 0, room)
                        stored += room
                    }
                    total += n
                }
            } catch (_: java.io.IOException) {
                // Stream closed because the process was killed; keep what we have.
            }
        }
    }
}
