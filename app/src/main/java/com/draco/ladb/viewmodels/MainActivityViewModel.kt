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
import com.draco.ladb.utils.DiscoveredService
import com.draco.ladb.utils.DnsDiscover
import com.draco.ladb.utils.PairedDeviceStore
import com.draco.ladb.utils.RemoteDevice
import kotlinx.coroutines.Dispatchers
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

        deviceStore.all().forEach { record ->
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
                deviceStore.save(
                    (deviceStore.find(host) ?: RemoteDevice(host)).also { record ->
                        if (alias.isNotBlank()) {
                            record.alias = alias
                            record.named = true
                        }
                    }
                )
                refreshDevices()
                onResult(ConnectResult.PAIR_FAILED)
                return@launch
            }

            adb.debug(context.getString(R.string.debug_paired_remote), id)

            val port = connectPort?.toIntOrNull()
            val record = deviceStore.find(host) ?: RemoteDevice(host)
            if (alias.isNotBlank()) {
                record.alias = alias
                record.named = true
            }
            record.paired = true
            if (port != null && port > 0) record.lastPort = port
            deviceStore.save(record)

            onResult(establish(host, manualPort = port, allowServerRestart = true))
        }
    }

    /**
     * Attach a remote device and give it a shell session.
     */
    private fun establish(host: String, manualPort: Int?, allowServerRestart: Boolean): ConnectResult {
        val context = getApplication<Application>()
        val id = AdbDevice.remoteId(host)

        val port = manualPort?.takeIf { it > 0 }
            ?: awaitDiscoveredPort(host)
            ?: deviceStore.find(host)?.lastPort?.takeIf { it > 0 }
            ?: run {
                adb.debug(context.getString(R.string.debug_port_unknown, host), id)
                setState(id, AdbDevice.State.FAILED)
                return ConnectResult.PORT_UNKNOWN
            }

        adb.debug(context.getString(R.string.debug_connect_remote, "$host:$port"), id)

        var connected = adb.connect(host, port)

        if (!connected && allowServerRestart) {
            /*
             * A device paired while the server was running is only reachable
             * once the server has re-read the pairing keys.
             */
            adb.restartServerAndReconnectLocal()
            connected = adb.connect(host, port)
        }

        if (!connected) {
            val unauthorized = adb.deviceState("$host:$port") == ADB.STATE_UNAUTHORIZED
            adb.debug(
                context.getString(
                    if (unauthorized) R.string.debug_remote_unauthorized
                    else R.string.debug_remote_connect_failed,
                    "$host:$port"
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
        refreshDevices()

        viewModelScope.launch(Dispatchers.IO) {
            session.waitFor()

            /* A newer session took its place; that one is watched instead. */
            val replacement = adb.session(id)
            if (replacement != null && replacement !== session) return@launch

            adb.forgetSession(id, session)
            remoteStates[id] = AdbDevice.State.OFFLINE
            adb.debug(context.getString(R.string.debug_remote_disconnected, alias), id)
            refreshDevices()
        }
    }

    /**
     * Drop a remote device's shell, and its connection.
     */
    fun disconnectDevice(id: String) {
        val host = AdbDevice.hostOf(id) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val serial = adb.session(id)?.serial
            adb.closeSession(id)
            if (serial != null) adb.disconnect(serial)
            remoteStates[id] = AdbDevice.State.OFFLINE
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
     * Devices with wireless debugging on that are visible on this network.
     */
    fun discoveredDevices(): List<DiscoveredService> =
        DnsDiscover.discoveredServices()

    private fun setState(id: String, state: AdbDevice.State) {
        if (id == AdbDevice.LOCAL_ID) return
        remoteStates[id] = state
        refreshDevices()
    }

    /**
     * Wait a moment for mDNS to announce the connect port of a device.
     */
    private fun awaitDiscoveredPort(host: String, timeoutMs: Long = 5_000): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (true) {
            DnsDiscover.portForHost(host)?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(250)
        }
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
