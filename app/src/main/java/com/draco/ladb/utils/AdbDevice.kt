package com.draco.ladb.utils

/**
 * A device LADB can talk to.
 *
 * The local device (this very phone, reached through its own wireless debugging
 * port on localhost) is always present. Remote devices are other phones on the
 * same network that have been paired with this one.
 */
data class AdbDevice(
    /** Stable identifier; survives a change of port. */
    val id: String,
    /** "localhost" for this device, otherwise the IP address of the remote one. */
    val host: String,
    val alias: String,
    val isLocal: Boolean,
    /** Has this device ever been paired? A paired device connects without a pairing code. */
    val paired: Boolean,
    /** Last known wireless debugging connect port, if any. */
    val port: Int,
    val state: State,
    /** The ADB serial currently in use, e.g. "localhost:5555" or "192.168.1.5:37001". */
    val serial: String? = null
) {
    enum class State {
        /** Not connected. */
        OFFLINE,

        /** A connection attempt is running. */
        CONNECTING,

        /** Ready to take shell commands. */
        CONNECTED,

        /** The other device has not accepted this device's ADB key yet. */
        UNAUTHORIZED,

        /** The last connection attempt failed. */
        FAILED
    }

    /** "192.168.1.5:37001", or the host alone when no port is known yet. */
    val endpoint: String
        get() = if (port > 0) "$host:$port" else host

    companion object {
        const val LOCAL_ID = "local"
        private const val REMOTE_PREFIX = "remote:"

        fun remoteId(host: String): String = "$REMOTE_PREFIX$host"

        fun hostOf(id: String): String? =
            if (id.startsWith(REMOTE_PREFIX)) id.removePrefix(REMOTE_PREFIX) else null
    }
}
