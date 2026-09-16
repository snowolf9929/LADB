package com.draco.ladb.viewmodels

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.os.Build
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.draco.ladb.R
import com.draco.ladb.utils.ADB
import com.draco.ladb.utils.AdbDevice
import com.draco.ladb.utils.DnsDiscover
import com.draco.ladb.utils.PairedDeviceStore
import com.draco.ladb.utils.RemoteDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * How an attempt to reach a remote device ended.
 */
enum class ConnectResult {
    CONNECTED,
    PAIR_FAILED,
    PORT_UNKNOWN,
    CONNECT_FAILED,
    UNAUTHORIZED
}

class MainActivityViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        /** How many times a dropped remote device is brought back before giving up. */
        const val MAX_RECONNECT_ATTEMPTS = 3

        /** How long to wait between those attempts. */
        const val RECONNECT_DELAY_MS = 4_000L

        /** A connection that held this long starts over with a clean counter. */
        const val RECONNECT_RESET_MS = 2 * 60 * 1000L

        /**
         * How often a connected remote device is nudged awake. It has to be
         * well under the shortest screen timeout Android ships with.
         */
        const val WAKE_INTERVAL_MS = 15_000L

        const val WAKE_KEY = "KEYCODE_WAKEUP"
    }

    private val _outputText = MutableLiveData<String>()
    val outputText: LiveData<String> = _outputText

    val isPairing = MutableLiveData<Boolean>()

    private val sharedPreferences = PreferenceManager
        .getDefaultSharedPreferences(application.applicationContext)

    val adb = ADB.getInstance(getApplication<Application>().applicationContext)
    val dnsDiscover =
        DnsDiscover.getInstance(
            application.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        )

    private val deviceStore = PairedDeviceStore(application.applicationContext)

    private val _viewModelHasStartedADB = MutableLiveData(false)
    val viewModelHasStartedADB: LiveData<Boolean> = _viewModelHasStartedADB

    /**
     * Every device LADB knows about: this device first, then the paired ones.
     */
    private val _devices = MutableLiveData<List<AdbDevice>>(emptyList())
    val devices: LiveData<List<AdbDevice>> = _devices

    /**
     * The device whose shell the screen is showing.
     */
    private val _activeDevice = MutableLiveData<AdbDevice?>()
    val activeDevice: LiveData<AdbDevice?> = _activeDevice

    private val remoteStates = ConcurrentHashMap<String, AdbDevice.State>()

    /** When a remote shell was opened, so a flapping link can be told from a long one. */
    private val sessionStartedAt = ConcurrentHashMap<String, Long>()

    /** Consecutive automatic reconnects, so a dead link cannot loop forever. */
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()

    @Volatile
    private var localConnecting = false

    @Volatile
    private var localFailed = false

    init {
        startOutputThread()
        dnsDiscover.scanAdbPorts()
        refreshDevices()

        /* Keep the device list in step with the local ADB server. */
        viewModelScope.launch {
            adb.running.asFlow().collect { refreshDevices() }
        }

        /* Keep the other devices from falling asleep on us. */
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(WAKE_INTERVAL_MS)
                keepRemoteDevicesAwake()
            }
        }
    }

    /**
     * Nudge the screen of every connected remote device.
     *
     * Android switches wireless debugging off once that screen turns off, which
     * drops the session, so a wake key is sent well inside its screen timeout.
     * A device that is already awake ignores it.
     */
    private fun keepRemoteDevicesAwake() {
        val context = getApplication<Application>()

        val wanted = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.keep_remote_awake_key), true)

        if (!wanted) return

        adb.remoteSerials().forEach { serial ->
            adb.sendKey(serial, WAKE_KEY)
        }
    }

    fun startADBServer(callback: ((Boolean) -> (Unit))? = null) {
        // Don't start if it's already started, or while an attempt is running.
        if (adb.running.value == true || localConnecting) return

        localConnecting = true
        localFailed = false
        refreshDevices()

        viewModelScope.launch(Dispatchers.IO) {
            val success = adb.initServer()
            localConnecting = false
            localFailed = !success
            if (success) {
                startShellDeathThread()
                _viewModelHasStartedADB.postValue(true)
            }
            refreshDevices()
            callback?.invoke(success)
        }
    }

    /**
     * Continuously update shell output
     */
    private fun startOutputThread() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val out = readOutputFile(adb.activeOutputFile)
                val currentText = _outputText.value
                if (out != currentText)
                    _outputText.postValue(out)
                Thread.sleep(ADB.OUTPUT_BUFFER_DELAY_MS)
            }
        }
    }

    /**
     * Start a death listener to restart the shell once it dies
     */
    private fun startShellDeathThread() {
        viewModelScope.launch(Dispatchers.IO) {
            adb.waitForDeathAndReset()
        }
    }

    /**
     * Erase all shell text of the active device
     */
    fun clearOutputText() {
        adb.activeOutputFile.writeText("")
        _outputText.postValue("")
    }

    /* ------------------------------------------------------------------ */
    /* Devices                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Rebuild the device list from the stored pairing records and live sessions.
     */
    fun refreshDevices() {
        val context = getApplication<Application>()
        val devices = mutableListOf<AdbDevice>()
        val records = deviceStore.all()

        adb.remoteDevicesConfigured = records.isNotEmpty()

        devices.add(
            AdbDevice(
                id = AdbDevice.LOCAL_ID,
                host = "localhost",
                alias = context.getString(R.string.device_local),
                isLocal = true,
                paired = true,
                port = adb.localPort ?: 0,
                state = localState(),
                serial = adb.session(AdbDevice.LOCAL_ID)?.serial
            )
        )

        records.forEach { record ->
            val id = AdbDevice.remoteId(record.host)

            devices.add(
                AdbDevice(
                    id = id,
                    host = record.host,
                    alias = record.displayName,
                    isLocal = false,
                    paired = record.paired,
                    port = record.lastPort,
                    state = remoteStates[id]
                        ?: if (adb.session(id) != null) AdbDevice.State.CONNECTED else AdbDevice.State.OFFLINE,
                    serial = adb.session(id)?.serial
                )
            )
        }

        _devices.postValue(devices)
        _activeDevice.postValue(devices.firstOrNull { it.id == adb.activeDeviceId })
    }

    private fun localState(): AdbDevice.State = when {
        adb.running.value == true -> AdbDevice.State.CONNECTED
        localConnecting -> AdbDevice.State.CONNECTING
        localFailed -> AdbDevice.State.FAILED
        else -> AdbDevice.State.OFFLINE
    }

    /**
     * Show another device's shell.
     */
    fun selectDevice(id: String) {
        adb.activeDeviceId = id
        refreshDevices()
    }

    /**
     * Connect to a device that has been paired before. No pairing code needed.
     */
    fun connectDevice(id: String, onResult: ((ConnectResult) -> Unit)? = null) {
        val host = AdbDevice.hostOf(id)
        if (host == null) {
            onResult?.invoke(ConnectResult.CONNECT_FAILED)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            /* The user asked for this one, so it gets a full set of attempts. */
            reconnectAttempts[id] = 0

            setState(id, AdbDevice.State.CONNECTING)
            val result = establish(host, manualPort = null, allowServerRestart = false)
            refreshDevices()
            onResult?.invoke(result)
        }
    }

    /**
     * Pair a new remote device, remember it, and connect right away.
     */
    fun addRemoteDevice(
        alias: String,
        host: String,
        pairPort: String,
        pairingCode: String,
        connectPort: String?,
        onResult: (ConnectResult) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val id = AdbDevice.remoteId(host)

            /*
             * Store and show the device before pairing: the pairing takes a
             * while, and its progress belongs in that device's own output.
             */
            val record = deviceStore.find(host) ?: RemoteDevice(host)
            if (alias.isNotBlank()) {
                record.alias = alias
                record.named = true
            }
            val port = connectPort?.toIntOrNull()?.takeIf { it > 0 }
            if (port != null) record.lastPort = port
            deviceStore.save(record)
            selectDevice(id)

            setState(id, AdbDevice.State.CONNECTING)
            adb.debug(context.getString(R.string.debug_pairing_remote, "$host:$pairPort"), id)

            /*
             * The pairing itself is done by the client, so the running server is
             * left alone here; it is restarted later only if the fresh device
             * turns out to be unreachable with the keys it already holds.
             */
            val paired = adb.pair(host, pairPort, pairingCode, killServer = false)
            if (!paired) {
                adb.debug(context.getString(R.string.debug_pairing_remote_failed, host), id)
                setState(id, AdbDevice.State.FAILED)
                refreshDevices()
                onResult(ConnectResult.PAIR_FAILED)
                return@launch
            }

            adb.debug(context.getString(R.string.debug_paired_remote), id)

            deviceStore.update(host) { it.paired = true }

            onResult(establish(host, manualPort = port, allowServerRestart = true))
        }
    }

    /**
     * Attach a remote device and give it a shell session.
     */
    private fun establish(host: String, manualPort: Int?, allowServerRestart: Boolean): ConnectResult {
        val context = getApplication<Application>()
        val id = AdbDevice.remoteId(host)

        val storedPort = deviceStore.find(host)?.lastPort?.takeIf { it > 0 }

        /*
         * Remote devices are given their port by hand. Wireless debugging hands
         * out a new one every time it is switched on, and watching another
         * device's announcement over mDNS is not dependable, so the port the
         * user entered wins and the one that worked last is the fallback.
         */
        val ports = LinkedHashSet<Int>()
        manualPort?.takeIf { it > 0 }?.let { ports.add(it) }
        storedPort?.let { ports.add(it) }

        if (ports.isEmpty()) {
            adb.debug(context.getString(R.string.debug_port_unknown, host), id)
            setState(id, AdbDevice.State.FAILED)
            return ConnectResult.PORT_UNKNOWN
        }

        fun tryAllPorts(): Int? {
            for (port in ports) {
                adb.debug(context.getString(R.string.debug_connect_remote, "$host:$port"), id)
                if (adb.connect(host, port)) return port
            }
            return null
        }

        var port = tryAllPorts()

        if (port == null && allowServerRestart) {
            /*
             * A device paired while the server was running is only reachable
             * once the server has re-read the pairing keys.
             */
            adb.restartServerAndReconnectLocal()
            port = tryAllPorts()
        }

        if (port == null) {
            val unauthorized = ports.any { adb.deviceState("$host:$it") == ADB.STATE_UNAUTHORIZED }

            adb.debug(
                context.getString(
                    if (unauthorized) R.string.debug_remote_unauthorized
                    else R.string.debug_remote_connect_failed,
                    ports.joinToString(", ") { "$host:$it" }
                ),
                id
            )
            setState(id, if (unauthorized) AdbDevice.State.UNAUTHORIZED else AdbDevice.State.FAILED)
            return if (unauthorized) ConnectResult.UNAUTHORIZED else ConnectResult.CONNECT_FAILED
        }

        deviceStore.update(host) {
            it.lastPort = port
            it.lastConnectedAt = System.currentTimeMillis()
        }

        openRemoteSession(host, port)
        adb.debug(context.getString(R.string.debug_remote_connected, "$host:$port"), id)

        /* Give the device its own name, unless the user already gave it one. */
        viewModelScope.launch(Dispatchers.IO) {
            autoNameRemote(host, "$host:$port")
        }

        return ConnectResult.CONNECTED
    }

    /**
     * Name a device after itself, as "brand:model", when the user did not name
     * it. A device that could not be asked keeps its previous name.
     */
    private fun autoNameRemote(host: String, serial: String) {
        val record = deviceStore.find(host) ?: return
        if (record.named) return

        val name = adb.getDeviceName(serial) ?: return
        if (name == record.alias) {
            refreshDevices()
            return
        }

        deviceStore.update(host) { it.alias = name }
        adb.debug(
            getApplication<Application>().getString(R.string.debug_auto_named, name),
            AdbDevice.remoteId(host)
        )
        refreshDevices()
    }

    /**
     * Open the shell of a remote device and watch it for death.
     */
    private fun openRemoteSession(host: String, port: Int) {
        val context = getApplication<Application>()
        val id = AdbDevice.remoteId(host)
        val alias = deviceStore.find(host)?.displayName ?: host

        val session = adb.openSession(
            deviceId = id,
            serial = "$host:$port",
            autoShell = true,
            banner = context.getString(R.string.shell_entered_remote, alias)
        ) ?: return

        remoteStates[id] = AdbDevice.State.CONNECTED
        sessionStartedAt[id] = System.currentTimeMillis()
        refreshDevices()

        viewModelScope.launch(Dispatchers.IO) {
            session.waitFor()

            /* A newer session took its place; that one is watched instead. */
            val replacement = adb.session(id)
            if (replacement != null && replacement !== session) return@launch

            adb.forgetSession(id, session)

            /* The user closed it, or another device took its place. */
            if (session.deathExpected) return@launch

            /*
             * A wireless link drops for all sorts of reasons, most of them
             * temporary, so give it a few chances before calling it gone.
             */
            if (reconnectRemote(host, id, alias)) return@launch

            remoteStates[id] = AdbDevice.State.OFFLINE
            adb.debug(disconnectReason(host, session.serial, alias), id)

            /*
             * A device that went away on its own usually had its wireless
             * debugging switched off, which Android does after a screen off.
             */
            adb.debug(context.getString(R.string.debug_remote_lost_hint), id)

            refreshDevices()
        }
    }

    /**
     * Try to bring a dropped remote device back. Returns true when it is back
     * on its own session.
     */
    private fun reconnectRemote(host: String, id: String, alias: String): Boolean {
        val context = getApplication<Application>()
        val startedAt = sessionStartedAt[id] ?: 0L

        /* A connection that held for a while starts over with a clean slate. */
        if (System.currentTimeMillis() - startedAt > RECONNECT_RESET_MS)
            reconnectAttempts[id] = 0

        var attempt = reconnectAttempts[id] ?: 0

        while (attempt < MAX_RECONNECT_ATTEMPTS) {
            attempt++
            reconnectAttempts[id] = attempt

            remoteStates[id] = AdbDevice.State.CONNECTING
            refreshDevices()
            adb.debug(context.getString(R.string.debug_remote_reconnecting, alias, attempt), id)

            Thread.sleep(RECONNECT_DELAY_MS)

            if (establish(host, manualPort = null, allowServerRestart = false) == ConnectResult.CONNECTED) {
                reconnectAttempts[id] = 0
                adb.debug(context.getString(R.string.debug_remote_reconnected, alias), id)
                return true
            }
        }

        return false
    }

    /**
     * Why a device counts as gone: what adb still says about it, and whether it
     * is still announcing wireless debugging on this network.
     */
    private fun disconnectReason(host: String, serial: String, alias: String): String {
        val context = getApplication<Application>()
        val state = adb.deviceState(serial)

        return context.getString(
            R.string.debug_remote_lost,
            alias,
            context.getString(
                if (state == null) R.string.reason_not_listed else R.string.reason_state,
                state ?: ""
            )
        )
    }

    /**
     * Drop a remote device's shell, and its connection. The pairing is kept,
     * so it can be brought back with one tap.
     */
    fun disconnectDevice(id: String) {
        val host = AdbDevice.hostOf(id) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val serial = adb.session(id)?.serial
            adb.closeSession(id)
            if (serial != null) adb.disconnect(serial)

            remoteStates[id] = AdbDevice.State.OFFLINE
            reconnectAttempts.remove(id)

            /* Nothing left to type into, so fall back to this device. */
            if (adb.activeDeviceId == id) selectDevice(AdbDevice.LOCAL_ID)

            refreshDevices()
        }
    }

    /**
     * Name a device by hand. Clearing the name hands it back to [autoNameRemote].
     */
    fun renameDevice(id: String, alias: String) {
        val host = AdbDevice.hostOf(id) ?: return
        val name = alias.trim()

        deviceStore.update(host) {
            it.alias = name
            it.named = name.isNotBlank()
        }
        refreshDevices()
    }

    /**
     * Remember the connect port of a device by hand; wireless debugging hands
     * out a new one every time it is switched on.
     */
    fun setDevicePort(id: String, port: Int) {
        val host = AdbDevice.hostOf(id) ?: return
        deviceStore.update(host) { it.lastPort = port }
        refreshDevices()
    }

    /**
     * Forget a device entirely; the next connection needs pairing again.
     */
    fun forgetDevice(id: String) {
        val host = AdbDevice.hostOf(id) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val serial = adb.session(id)?.serial
            adb.closeSession(id)
            if (serial != null) adb.disconnect(serial)

            deviceStore.remove(host)
            remoteStates.remove(id)

            if (adb.activeDeviceId == id) selectDevice(AdbDevice.LOCAL_ID)

            refreshDevices()
        }
    }

    /**
     * The port a device was last reached on, so the add dialog can offer it.
     */
    fun savedPortFor(host: String): Int? = deviceStore.find(host)?.lastPort?.takeIf { it > 0 }

    private fun setState(id: String, state: AdbDevice.State) {
        if (id == AdbDevice.LOCAL_ID) return
        remoteStates[id] = state
        refreshDevices()
    }

    /* ------------------------------------------------------------------ */
    /* The local device                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Check if the user should be prompted to pair
     */
    fun needsToPair(): Boolean {
        val context = getApplication<Application>().applicationContext

        return !sharedPreferences.getBoolean(context.getString(R.string.paired_key), false) &&
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
    }

    fun setPairedBefore(value: Boolean) {
        val context = getApplication<Application>().applicationContext
        sharedPreferences.edit {
            putBoolean(context.getString(R.string.paired_key), value)
        }
    }

    /**
     * Read the content of the ADB output file
     */
    private fun readOutputFile(file: File): String {
        val out = ByteArray(adb.getOutputBufferSize())

        synchronized(file) {
            if (!file.exists())
                return ""

            file.inputStream().use {
                val size = it.channel.size()

                if (size <= out.size)
                    return String(it.readBytes())

                val newPos = (it.channel.size() - out.size)
                it.channel.position(newPos)
                it.read(out)
            }
        }

        return String(out)
    }
}
