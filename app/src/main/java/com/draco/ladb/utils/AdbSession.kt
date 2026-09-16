package com.draco.ladb.utils

import java.io.File
import java.io.PrintStream

/**
 * One interactive shell session, bound to exactly one device.
 *
 * Every connected device owns its own session and its own output buffer, so
 * switching between devices in the UI never disturbs the other shells.
 */
class AdbSession internal constructor(
    /** The [AdbDevice.id] this session belongs to. */
    val deviceId: String,
    /** The ADB serial the shell was opened on. */
    val serial: String,
    /** Where this session's output is written. */
    val outputBufferFile: File,
    private val process: Process
) {
    /**
     * Set when the session is torn down on purpose, so the death watchers know
     * the death is not a failure that needs recovering from.
     */
    @Volatile
    var deathExpected = false
        private set

    /**
     * Send a command to the shell.
     */
    fun send(msg: String) {
        try {
            PrintStream(process.outputStream).apply {
                println(msg)
                flush()
            }
        } catch (e: Exception) {
            /* The shell died between the liveness check and the write. */
            e.printStackTrace()
        }
    }

    internal fun expectDeath() {
        deathExpected = true
    }

    internal fun waitFor(): Int = process.waitFor()

    /**
     * Tear the process down without marking the death as expected, so the
     * watcher treats it as a connection that should be brought back.
     */
    internal fun killProcess() {
        try {
            process.destroyForcibly().waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    internal fun destroy() {
        expectDeath()
        killProcess()
    }
}
