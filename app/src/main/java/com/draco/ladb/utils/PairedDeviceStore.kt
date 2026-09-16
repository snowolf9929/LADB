package com.draco.ladb.utils

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.draco.ladb.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * A remote device the user added to LADB.
 *
 * The pairing secret itself is the ADB key pair kept in the app's private
 * storage (see [ADB.getAdbKeyDirectory]), which the system preserves across
 * restarts. This record only remembers *which* device has been paired and how
 * to reach it, so every later connection can skip the pairing code.
 */
data class RemoteDevice(
    val host: String,
    var alias: String = "",
    /** True when the user typed the name, false when LADB named it after the device. */
    var named: Boolean = false,
    var lastPort: Int = 0,
    var paired: Boolean = false,
    var lastConnectedAt: Long = 0L
) {
    val displayName: String
        get() = alias.ifBlank { host }
}

/**
 * Persists the pairing records as a JSON array inside the default shared
 * preferences.
 */
class PairedDeviceStore(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val key = context.applicationContext.getString(R.string.remote_devices_key)

    @Synchronized
    fun all(): List<RemoteDevice> {
        val raw = prefs.getString(key, null) ?: return emptyList()

        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val host = item.optString(JSON_HOST)
                if (host.isBlank()) return@mapNotNull null

                val alias = item.optString(JSON_ALIAS)

                RemoteDevice(
                    host = host,
                    alias = alias,
                    /*
                     * Records written before auto-naming existed can only hold
                     * a name the user typed.
                     */
                    named = item.optBoolean(JSON_NAMED, alias.isNotBlank()),
                    lastPort = item.optInt(JSON_PORT),
                    paired = item.optBoolean(JSON_PAIRED),
                    lastConnectedAt = item.optLong(JSON_CONNECTED)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    @Synchronized
    fun find(host: String): RemoteDevice? = all().firstOrNull { it.host == host }

    @Synchronized
    fun save(device: RemoteDevice) {
        val devices = all().toMutableList()
        val index = devices.indexOfFirst { it.host == device.host }

        if (index >= 0) devices[index] = device else devices.add(device)

        write(devices)
    }

    /**
     * Change a stored record in place. Does nothing when the host is unknown.
     */
    @Synchronized
    fun update(host: String, transform: (RemoteDevice) -> Unit) {
        val devices = all().toMutableList()
        val device = devices.firstOrNull { it.host == host } ?: return

        transform(device)
        write(devices)
    }

    @Synchronized
    fun remove(host: String) {
        write(all().filterNot { it.host == host })
    }

    @Synchronized
    fun clear() {
        write(emptyList())
    }

    private fun write(devices: List<RemoteDevice>) {
        val array = JSONArray()

        devices.forEach { device ->
            array.put(JSONObject().apply {
                put(JSON_HOST, device.host)
                put(JSON_ALIAS, device.alias)
                put(JSON_NAMED, device.named)
                put(JSON_PORT, device.lastPort)
                put(JSON_PAIRED, device.paired)
                put(JSON_CONNECTED, device.lastConnectedAt)
            })
        }

        prefs.edit { putString(key, array.toString()) }
    }

    private companion object {
        const val JSON_HOST = "host"
        const val JSON_ALIAS = "alias"
        const val JSON_NAMED = "named"
        const val JSON_PORT = "port"
        const val JSON_PAIRED = "paired"
        const val JSON_CONNECTED = "last_connected"
    }
}
