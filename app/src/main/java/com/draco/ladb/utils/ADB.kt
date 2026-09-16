package com.draco.ladb.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.draco.ladb.BuildConfig
import com.draco.ladb.R
import java.io.BufferedReader
import java.io.File
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class ADB(private val context: Context) {
    companion object {
        const val MAX_OUTPUT_BUFFER_SIZE = 1024 * 16
        const val OUTPUT_BUFFER_DELAY_MS = 100L
        const val CONNECT_ATTEMPTS = 3
        const val ADB_KEY_NAME = "LADB"

        const val STATE_DEVICE = "device"
        const val STATE_UNAUTHORIZED = "unauthorized"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ADB? = null
        fun getInstance(context: Context): ADB = instance ?: synchronized(this) {
            instance ?: ADB(context).also { instance = it }
        }
    }

    private val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)

    val adbPath = "${context.applicationInfo.nativeLibraryDir}/libadb.so"

    /**
     * Is the local ADB server up and is this device's own shell running?
     */
    private val _running = MutableLiveData(false)
    val running: LiveData<Boolean> = _running

    private var tryingToPair = false

    /**
     * The wireless debugging port of this device, once discovered.
     */
    @Volatile
    var localPort: Int? = null
        private set

    /**
     * Which device the UI is currently looking at.
     */
    @Volatile
    var activeDeviceId: String = AdbDevice.LOCAL_ID

    /**
     * One output buffer per device; debug messages and shell output share it.
     */
    private val outputFiles = ConcurrentHashMap<String, File>()

    /**
     * One shell session per connected device.
     */
    private val sessions = ConcurrentHashMap<String, AdbSession>()

    val activeOutputFile: File
        get() = outputFile(activeDeviceId)

    /**
     * Returns the user buffer size if valid, else the default
     */
    fun getOutputBufferSize(): Int {
        val userValue = sharedPrefs.getString(context.getString(R.string.buffer_size_key), "16384")!!
        return try {
            Integer.parseInt(userValue)
        } catch (_: NumberFormatException) {
            MAX_OUTPUT_BUFFER_SIZE
        }
    }

    /**
     * Where this device's output is stored. The file is emptied the first time
     * it is handed out, so a fresh process never shows stale output.
     */
    fun outputFile(deviceId: String): File = outputFiles.computeIfAbsent(deviceId) {
        val name = deviceId.replace(Regex("[^A-Za-z0-9]"), "_")
        File(context.cacheDir, "ladb-output-$name.txt").also { file ->
            file.writeText("")
        }
    }

    /**
     * The session of a device, or null when it is not connected.
     */
    fun session(deviceId: String): AdbSession? = sessions[deviceId]

    /**
     * Directory holding the ADB key pair.
     *
     * This is what makes a second connection work without a pairing code: the
     * key that was paired is kept here, inside the app's private storage, and
     * reused for every later connection.
     */
    fun getAdbKeyDirectory(): File = File(context.filesDir, ".android")

    /**
     * Throw away the ADB keys and the pairing records. Every device, including
     * this one, has to be paired again afterwards.
     */
    fun resetKeys() {
        killServer()
        getAdbKeyDirectory().deleteRecursively()
    }

    /**
     * Get a list of connected devices, ready to take commands.
     */
    fun getDevices(): List<String> = deviceEntries().filter { it.second == STATE_DEVICE }.map { it.first }

    /**
     * The state adb reports for a serial: "device", "offline", "unauthorized"…
     */
    fun deviceState(serial: String): String? =
        deviceEntries().firstOrNull { it.first == serial }?.second

    fun isDeviceConnected(serial: String): Boolean = deviceState(serial) == STATE_DEVICE

    private fun deviceEntries(): List<Pair<String, String>> {
        val devicesProcess = adb(false, listOf("devices"))
        devicesProcess.waitFor(1, TimeUnit.MINUTES)

        /* Get result of the command. */
        val linesRaw = BufferedReader(devicesProcess.inputStream.reader()).readLines()

        /* Split each line into the name and the state; headers and empty lines have neither. */
        return linesRaw.map { line ->
            line.split("\t")
        }.filter { parts ->
            parts.size == 2
        }.map { parts ->
            parts.first().trim() to parts.last().trim()
        }.filter { entry ->
            /* Offline and unauthorized devices take no commands, but we still report them. */
            entry.first.isNotEmpty() && entry.second.isNotEmpty()
        }
    }

    /**
     * Start the ADB server and connect this device to itself.
     */
    fun initServer(): Boolean {
        if (_running.value == true || tryingToPair)
            return true

        tryingToPair = true

        try {
            val autoShell = sharedPrefs.getBoolean(context.getString(R.string.auto_shell_key), true)

            val secureSettingsGranted =
                context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

            if (autoShell) {
                /* Only do wireless debugging steps on compatible versions */
                if (secureSettingsGranted) {
                    disableMobileDataAlwaysOn()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        cycleWirelessDebugging()
                    } else if (!isUSBDebuggingEnabled()) {
                        debug(context.getString(R.string.debug_usb_debugging_on))
                        Settings.Global.putInt(
                            context.contentResolver,
                            Settings.Global.ADB_ENABLED,
                            1
                        )

                        Thread.sleep(5_000)
                    }
                }

                /* Check again... */
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!isWirelessDebuggingEnabled()) {
                        debug(context.getString(R.string.debug_wireless_debugging_off_hint))
                        debug(context.getString(R.string.debug_wireless_debugging_path))
                        debug(context.getString(R.string.debug_wireless_debugging_await))

                        while (!isWirelessDebuggingEnabled()) {
                            Thread.sleep(1_000)
                        }
                    }
                } else {
                    if (!isUSBDebuggingEnabled()) {
                        debug(context.getString(R.string.debug_usb_debugging_off_hint))
                        debug(context.getString(R.string.debug_usb_debugging_path))
                        debug(context.getString(R.string.debug_usb_debugging_await))

                        while (!isUSBDebuggingEnabled()) {
                            Thread.sleep(1_000)
                        }
                    }
                }

                val nowTime = System.currentTimeMillis()
                val maxTimeoutTime = nowTime + 10.seconds.inWholeMilliseconds
                val minDnsScanTime = (DnsDiscover.aliveTime ?: nowTime) + 3.seconds.inWholeMilliseconds
                while (true) {
                    val nowTime = System.currentTimeMillis()
                    val pendingResolves = DnsDiscover.pendingResolves.get()

                    // Wait for a port, for pending DNS resolves, and for the minimum scan time...
                    if (nowTime >= minDnsScanTime && !pendingResolves && DnsDiscover.adbPort != null) {
                        debug(context.getString(R.string.debug_dns_done))
                        break
                    }

                    // Or if 10 seconds pass...
                    if (nowTime >= maxTimeoutTime) {
                        debug(context.getString(R.string.debug_dns_timeout))
                        break
                    }

                    debug(context.getString(R.string.debug_dns_await))

                    Thread.sleep(1_000)
                }

                val adbPort = DnsDiscover.adbPort
                if (adbPort != null)
                    debug(context.getString(R.string.debug_port_found, adbPort))
                else
                    debug(context.getString(R.string.debug_port_missing))

                debug(context.getString(R.string.debug_server_starting))
                adb(false, listOf("start-server")).waitFor(1, TimeUnit.MINUTES)

                var waitProcess = false

                if (adbPort != null) {
                    waitProcess = connect("localhost", adbPort)

                    /* Only remember a port that actually accepted the connection. */
                    if (waitProcess)
                        localPort = adbPort
                } else {
                    waitProcess = adb(false, listOf("wait-for-device")).waitFor(1, TimeUnit.MINUTES)
                }

                if (!waitProcess) {
                    debug(context.getString(R.string.debug_connect_failed))
                    debug(context.getString(R.string.debug_connect_failed_hint))

                    if (isMobileDataAlwaysOnEnabled()) {
                        debug(context.getString(R.string.debug_mobile_data_hint))
                        Thread.sleep(5_000)
                    }

                    return false
                }
            }

            val deviceList = getDevices()
            Log.d("DEVICES", "Devices: $deviceList")

            val serial = if (autoShell) selectLocalSerial(deviceList) else null

            if (autoShell && serial == null) {
                debug(context.getString(R.string.debug_connect_failed))
                debug(context.getString(R.string.debug_connect_failed_hint))
                return false
            }

            openSession(
                deviceId = AdbDevice.LOCAL_ID,
                serial = serial,
                autoShell = autoShell,
                banner = context.getString(
                    if (autoShell) R.string.shell_entered_adb else R.string.shell_entered_non_adb
                )
            )

            _running.postValue(true)

            return true
        } finally {
            tryingToPair = false
        }
    }

    /**
     * Pick the serial that belongs to this device out of everything adb knows.
     */
    private fun selectLocalSerial(deviceList: List<String>): String? {
        if (deviceList.isEmpty())
            return null

        if (deviceList.size == 1)
            return deviceList.first()

        Log.w("DEVICES", "Multiple devices detected...")

        /* Choose the first local device (hopefully the only). */
        deviceList.firstOrNull { it.contains("localhost") }?.let { serialId ->
            Log.w("DEVICES", "Choosing first local device: $serialId")
            return serialId
        }

        /*
         * If no local devices to use, try to filter out
         * any emulator devices and choose the first remaining result.
         */
        deviceList.firstOrNull { !it.contains("emulator") }?.let { serialId ->
            Log.w("DEVICES", "Choosing first non-emulator device: $serialId")
            return serialId
        }

        /* Otherwise, we're screwed, just choose the first device. */
        val serialId = deviceList.first()
        Log.w("DEVICES", "Choosing first unrecognized device: $serialId")
        return serialId
    }

    private fun isWirelessDebuggingEnabled() =
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1

    private fun isUSBDebuggingEnabled() =
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    private fun isMobileDataAlwaysOnEnabled() =
        Settings.Global.getInt(context.contentResolver, "mobile_data_always_on", 0) == 1

    /**
     * Settings.Global.MOBILE_DATA_ALWAYS_ON creates a bug
     * with the DNS resolver.
     */
    fun disableMobileDataAlwaysOn() {
        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (secureSettingsGranted) {
            // Only turn it off if it's already on.
            if (isMobileDataAlwaysOnEnabled()) {
                debug(context.getString(R.string.debug_mobile_data_disabling))
                Settings.Global.putInt(
                    context.contentResolver,
                    "mobile_data_always_on",
                    0
                )
                Thread.sleep(3_000)
            }
        }
    }

    /**
     * Cycles wireless debugging to get a new port to scan.
     *
     * For whatever reason, Wireless Debugging needs to be
     * cycled twice to broadcast a valid port.
     */
    fun cycleWirelessDebugging() {
        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (secureSettingsGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                debug(context.getString(R.string.debug_cycling_wireless_debugging))
                // Only turn it off if it's already on.
                if (isWirelessDebuggingEnabled()) {
                    debug(context.getString(R.string.debug_wireless_debugging_turning_off))
                    Settings.Global.putInt(
                        context.contentResolver,
                        "adb_wifi_enabled",
                        0
                    )
                    Thread.sleep(3_000)
                }

                debug(context.getString(R.string.debug_wireless_debugging_turning_on))
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    1
                )
                Thread.sleep(3_000)

                debug(context.getString(R.string.debug_wireless_debugging_turning_off))
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    0
                )
                Thread.sleep(3_000)

                debug(context.getString(R.string.debug_wireless_debugging_turning_on))
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    1
                )
                Thread.sleep(3_000)
            }
        }
    }

    /**
     * Wait restart the shell once it dies
     */
    fun waitForDeathAndReset() {
        var watched: AdbSession? = null

        while (true) {
            val session = sessions[AdbDevice.LOCAL_ID]

            /* Wait for a session to show up, or for a new one to replace the old. */
            if (session == null || session !== watched) {
                watched = session
                Thread.sleep(1_000)
                continue
            }

            /* Do not falsely claim the shell is dead if we haven't even initialized it yet */
            if (tryingToPair) {
                Thread.sleep(1_000)
                continue
            }

            session.waitFor()
            watched = null

            /* The session was torn down on purpose; a replacement is on its way. */
            if (session.deathExpected)
                continue

            _running.postValue(false)
            debug(context.getString(R.string.debug_shell_dead))
            adb(false, listOf("kill-server")).waitFor(30, TimeUnit.SECONDS)

            Thread.sleep(3_000)
            initServer()
        }
    }

    /**
     * Ask a device to pair on Android 11+ devices.
     *
     * [host] is "localhost" for this device, or the IP address of another one.
     */
    fun pair(host: String, port: String, pairingCode: String, killServer: Boolean = true): Boolean {
        val pairShell = adb(false, listOf("pair", "$host:$port"))

        /* Sleep to allow shell to catch up */
        Thread.sleep(5000)

        /* Pipe pairing code */
        PrintStream(pairShell.outputStream).apply {
            println(pairingCode)
            flush()
        }

        /* Continue once finished pairing (or 30s elapses) */
        val paired = pairShell.waitFor(30, TimeUnit.SECONDS) && pairShell.exitValue() == 0
        pairShell.destroyForcibly().waitFor()

        if (killServer) {
            /*
             * The server caches the wireless debugging host keys, so it has to
             * be restarted before the freshly paired device can connect.
             */
            val killShell = adb(false, listOf("kill-server"))
            killShell.waitFor(3, TimeUnit.SECONDS)
            killShell.destroyForcibly()
        }

        return paired
    }

    /**
     * Attach a device over the network. Returns true once it takes commands.
     */
    fun connect(host: String, port: Int): Boolean {
        val serial = "$host:$port"

        // Connect exits successfully even when it attaches nothing.
        for (attempt in 1..CONNECT_ATTEMPTS) {
            adb(false, listOf("connect", serial)).waitFor(1, TimeUnit.MINUTES)

            if (isDeviceConnected(serial))
                return true

            /* An unauthorized device stays unauthorized until someone taps "Allow". */
            if (deviceState(serial) == STATE_UNAUTHORIZED)
                return false

            if (attempt < CONNECT_ATTEMPTS) {
                debug(context.getString(R.string.debug_connect_retry))
                Thread.sleep(2_000)
            }
        }

        return false
    }

    /**
     * Detach a device from the ADB server.
     */
    fun disconnect(serial: String) {
        adb(false, listOf("disconnect", serial)).waitFor(30, TimeUnit.SECONDS)
    }

    fun killServer() {
        adb(false, listOf("kill-server")).waitFor(30, TimeUnit.SECONDS)
    }

    /**
     * Kill the ADB server, then bring this device's own connection back up.
     *
     * Pairing a remote device while the server is running leaves the server
     * with stale host keys, so it is restarted and the local device is
     * re-attached without going through the whole wireless debugging dance.
     */
    fun restartServerAndReconnectLocal() {
        val wasRunning = _running.value == true
        val autoShell = sharedPrefs.getBoolean(context.getString(R.string.auto_shell_key), true)
        val port = localPort

        debug(context.getString(R.string.debug_server_restarting))

        sessions.values.forEach { it.destroy() }
        sessions.clear()

        killServer()
        adb(false, listOf("start-server")).waitFor(1, TimeUnit.MINUTES)
        Thread.sleep(1_000)

        _running.postValue(false)

        if (!wasRunning)
            return

        if (!autoShell) {
            openSession(
                deviceId = AdbDevice.LOCAL_ID,
                serial = null,
                autoShell = false,
                banner = context.getString(R.string.shell_entered_non_adb)
            )
            _running.postValue(true)
            return
        }

        if (port != null && connect("localhost", port)) {
            openSession(
                deviceId = AdbDevice.LOCAL_ID,
                serial = "localhost:$port",
                autoShell = true,
                banner = context.getString(R.string.shell_entered_adb)
            )
            _running.postValue(true)
        } else {
            initServer()
        }
    }

    /**
     * Open a shell session for a device, replacing any session it already had.
     *
     * [serial] is null for the plain, non-adb shell.
     */
    fun openSession(
        deviceId: String,
        serial: String?,
        autoShell: Boolean,
        banner: String? = null
    ): AdbSession? {
        closeSession(deviceId)

        val outputFile = outputFile(deviceId)

        val command = when {
            autoShell && serial != null -> listOf("-s", serial, "shell")
            autoShell -> listOf("shell")
            else -> listOf("sh", "-l")
        }

        val process = if (autoShell)
            adb(true, command, outputFile)
        else
            shell(true, command, outputFile)

        val session = AdbSession(
            deviceId = deviceId,
            serial = serial ?: if (deviceId == AdbDevice.LOCAL_ID) "localhost" else deviceId,
            outputBufferFile = outputFile,
            process = process
        )
        sessions[deviceId] = session

        if (autoShell) {
            /*
             * The adb helper and the secure settings grant only make sense on
             * this device; a remote shell runs on the other phone.
             */
            if (deviceId == AdbDevice.LOCAL_ID) {
                session.send("alias adb=\"$adbPath\"")

                val secureSettingsGranted =
                    context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

                if (!secureSettingsGranted)
                    session.send("pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS &> /dev/null")
            }

            session.send("echo '${banner ?: context.getString(R.string.shell_entered_adb)}'")
        } else {
            session.send("echo '${context.getString(R.string.shell_entered_non_adb)}'")
        }

        if (deviceId == AdbDevice.LOCAL_ID) {
            val startupCommand = sharedPrefs.getString(
                context.getString(R.string.startup_command_key),
                context.getString(R.string.startup_command_default)
            )!!
            if (startupCommand.isNotEmpty())
                session.send(startupCommand)
        }

        return session
    }

    /**
     * Tear down a device's session on purpose.
     */
    fun closeSession(deviceId: String) {
        sessions.remove(deviceId)?.destroy()
    }

    /**
     * Drop a session that died on its own, unless it has already been replaced.
     */
    fun forgetSession(deviceId: String, session: AdbSession) {
        sessions.remove(deviceId, session)
    }

    /**
     * Send commands directly to the shell of the active device
     */
    fun sendToActiveSession(msg: String) {
        sessions[activeDeviceId]?.send(msg)
    }

    /**
     * Send a raw ADB command
     */
    private fun adb(redirect: Boolean, command: List<String>, outputFile: File? = null): Process {
        val commandList = command.toMutableList().also {
            it.add(0, adbPath)
        }
        return shell(redirect, commandList, outputFile)
    }

    /**
     * Send a raw shell command
     */
    private fun shell(redirect: Boolean, command: List<String>, outputFile: File? = null): Process {
        val processBuilder = ProcessBuilder(command)
            .directory(context.filesDir)
            .apply {
                if (redirect) {
                    redirectErrorStream(true)
                    redirectOutput(outputFile ?: activeOutputFile)
                }

                environment().apply {
                    put("HOME", context.filesDir.path)
                    put("TMPDIR", context.cacheDir.path)
                    put("LOGNAME", ADB_KEY_NAME)
                    put("HOSTNAME", ADB_KEY_NAME)
                }
            }

        return processBuilder.start()!!
    }

    /**
     * Write a debug message to the user.
     *
     * Messages without an explicit device belong to this device's own
     * connection, so they land in the local output buffer.
     */
    fun debug(msg: String, deviceId: String = AdbDevice.LOCAL_ID) {
        val file = outputFile(deviceId)

        synchronized(file) {
            Log.d("DEBUG", msg)
            if (file.exists())
                file.appendText("* $msg" + System.lineSeparator())
        }
    }
}
