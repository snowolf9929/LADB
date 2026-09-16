package com.draco.ladb.views

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.draco.ladb.BuildConfig
import com.draco.ladb.R
import com.draco.ladb.databinding.ActivityMainBinding
import com.draco.ladb.utils.AdbDevice
import com.draco.ladb.utils.DiscoveredService
import com.draco.ladb.utils.DnsDiscover
import com.draco.ladb.viewmodels.ConnectResult
import com.draco.ladb.viewmodels.MainActivityViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {
    private val viewModel: MainActivityViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding

    private lateinit var pairDialog: AlertDialog.Builder

    private var lastCommand = ""

    /**
     * Lets the open device dialog follow changes while it is on screen.
     */
    private var deviceDialogRebuild: (() -> Unit)? = null

    private var bookmarkGetResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val text = it.data?.getStringExtra(Intent.EXTRA_TEXT) ?: return@registerForActivityResult
        binding.command.setText(text)
    }

    private val localNetworkRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            viewModel.adb.debug(getString(R.string.debug_local_network_denied))
        }

        pairAndStart()
    }

    private fun setupUI() {
        /* Fix stupid Google edge-to-edge bullshit */
        ViewCompat.setOnApplyWindowInsetsListener(binding.content) { v, windowInsets ->
            val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val statusBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())

            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = systemBarsInsets.left
                bottomMargin = systemBarsInsets.bottom
                rightMargin = systemBarsInsets.right
                topMargin = systemBarsInsets.top
            }
            binding.statusBarBackground.updateLayoutParams {
                height = statusBarInsets.top
            }

            WindowInsetsCompat.CONSUMED
        }
        supportActionBar!!.elevation = 0f
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        pairDialog = AlertDialog.Builder(this)
            .setTitle(R.string.pair_title)
            .setCancelable(false)
            .setView(R.layout.dialog_pair)
            .setPositiveButton(R.string.pair, null)
            .setNegativeButton(R.string.help, null)
            .setNeutralButton(R.string.skip, null)

        binding.deviceSelector.setOnClickListener { showDeviceDialog() }
        binding.addDevice.setOnClickListener { showAddDeviceDialog() }

        binding.command.setOnKeyListener { _, keyCode, keyEvent ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && keyEvent.action == KeyEvent.ACTION_DOWN) {
                sendCommandToADB()
                return@setOnKeyListener true
            } else {
                return@setOnKeyListener false
            }
        }

        binding.command.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommandToADB()
                return@setOnEditorActionListener true
            } else {
                return@setOnEditorActionListener false
            }
        }
    }

    private fun sendCommandToADB() {
        val text = binding.command.text.toString()
        lastCommand = text
        binding.command.text = null
        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.adb.sendToActiveSession(text)
        }
    }

    private fun setReadyForInput(ready: Boolean) {
        binding.command.isEnabled = ready
        binding.commandContainer.hint =
            if (ready) getString(R.string.command_hint) else getString(R.string.command_hint_waiting)
        binding.progress.visibility = if (ready) View.INVISIBLE else View.VISIBLE
    }

    private fun setupDataListeners() {
        /* Update the output text */
        viewModel.outputText.observe(this) { newText ->
            binding.output.text = newText
            binding.outputScrollview.post {
                binding.outputScrollview.fullScroll(ScrollView.FOCUS_DOWN)
                binding.command.requestFocus()
                WindowCompat.getInsetsController(window, binding.command)
                    .show(WindowInsetsCompat.Type.ime())
            }
        }

        /* Keep the device bar and the input state in step with the devices */
        viewModel.devices.observe(this) { devices ->
            val active = devices.firstOrNull { it.id == viewModel.adb.activeDeviceId }
            binding.deviceSelector.text =
                active?.let { deviceLabel(it) } ?: getString(R.string.device_local)

            setReadyForInput(active?.state == AdbDevice.State.CONNECTED)

            applyKeepScreenOn(devices)

            deviceDialogRebuild?.invoke()
        }
    }

    /**
     * While another device is being debugged, keep this screen awake: Android
     * freezes a backgrounded app and the ADB server it started.
     */
    private fun applyKeepScreenOn(devices: List<AdbDevice>) {
        val wantsIt = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(getString(R.string.keep_awake_key), true)
        val remoteConnected = devices.any { !it.isLocal && it.state == AdbDevice.State.CONNECTED }

        if (wantsIt && remoteConnected)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Android 17 blocks the local network until granted, which mDNS port discovery needs
     */
    private fun requestLocalNetworkAccess() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                PackageManager.PERMISSION_GRANTED

        if (needsPermission) {
            localNetworkRequest.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            pairAndStart()
        }
    }

    private fun pairAndStart() {
        if (viewModel.needsToPair()) {
            viewModel.adb.debug(getString(R.string.debug_pairing_requesting))
            askToPair { thisPairSuccess ->
                if (thisPairSuccess) {
                    viewModel.setPairedBefore(true)
                    viewModel.startADBServer()
                } else {
                    /* Failed; try again! */
                    viewModel.adb.debug(getString(R.string.debug_pairing_failed))
                    runOnUiThread { pairAndStart() }
                }
            }
        } else {
            viewModel.startADBServer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupDataListeners()

        /* Ensure we are not running this a second time around */
        if (viewModel.viewModelHasStartedADB.value != true) {
            if (viewModel.isPairing.value != true)
                requestLocalNetworkAccess()
        }
    }

    override fun onResume() {
        super.onResume()
        /* Devices may have been unpaired in the settings. */
        viewModel.refreshDevices()
    }

    override fun onDestroy() {
        deviceDialogRebuild = null
        super.onDestroy()
    }

    /**
     * Ask the user to pair
     */
    private fun askToPair(callback: ((Boolean) -> (Unit))? = null) {
        pairDialog
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val port = findViewById<TextInputEditText>(R.id.port)!!.text.toString()
                        val code = findViewById<TextInputEditText>(R.id.code)!!.text.toString()
                        dismiss()

                        lifecycleScope.launch(Dispatchers.IO) {
                            viewModel.adb.debug(getString(R.string.debug_pairing))
                            val success = viewModel.adb.pair("localhost", port, code)
                            callback?.invoke(success)
                        }
                    }

                    getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        val intent = Intent(Intent.ACTION_VIEW, getString(R.string.tutorial_url).toUri())
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Snackbar.make(
                                binding.output,
                                getString(R.string.snackbar_intent_failed),
                                Snackbar.LENGTH_SHORT
                            )
                                .show()
                        }
                    }

                    getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        PreferenceManager.getDefaultSharedPreferences(context).edit(true) {
                            putBoolean(getString(R.string.auto_shell_key), false)
                        }
                        dismiss()
                        callback?.invoke(true)
                    }
                }
            }
            .show()
    }

    /* ------------------------------------------------------------------ */
    /* Devices                                                             */
    /* ------------------------------------------------------------------ */

    private fun deviceLabel(device: AdbDevice): String =
        "${device.alias} · ${stateLabel(device.state)}"

    private fun stateLabel(state: AdbDevice.State): String = getString(
        when (state) {
            AdbDevice.State.CONNECTED -> R.string.device_state_connected
            AdbDevice.State.CONNECTING -> R.string.device_state_connecting
            AdbDevice.State.UNAUTHORIZED -> R.string.device_state_unauthorized
            AdbDevice.State.FAILED -> R.string.device_state_failed
            AdbDevice.State.OFFLINE -> R.string.device_state_offline
        }
    )

    /**
     * The device picker: this device, the paired remote devices, and anything
     * else announcing wireless debugging on this network.
     */
    private fun showDeviceDialog() {
        val container = layoutInflater.inflate(R.layout.dialog_devices, null)
        val list = container.findViewById<LinearLayout>(R.id.device_list)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.devices)
            .setView(container)
            .setPositiveButton(R.string.add_remote_device, null)
            .setNegativeButton(R.string.dismiss, null)
            .create()

        deviceDialogRebuild = {
            list.removeAllViews()

            val devices = viewModel.devices.value.orEmpty()
            devices.forEach { device ->
                list.addView(buildDeviceRow(list, device, dialog))
            }

            val discovered = viewModel.discoveredDevices()
                .distinctBy { it.host }
                .filter { service -> devices.none { it.host == service.host } }

            if (discovered.isNotEmpty()) {
                val header = layoutInflater.inflate(R.layout.item_device_header, list, false)
                header.findViewById<TextView>(R.id.device_header).setText(R.string.device_discovered)
                list.addView(header)

                discovered.forEach { service ->
                    list.addView(buildDiscoveredRow(list, service))
                }
            }
        }

        /* The positive button always reaches the add dialog, even when the list scrolls. */
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                showAddDeviceDialog()
            }
        }

        dialog.setOnDismissListener { deviceDialogRebuild = null }
        dialog.show()
        deviceDialogRebuild?.invoke()
    }

    private fun buildDeviceRow(parent: ViewGroup, device: AdbDevice, dialog: AlertDialog): View {
        val row = layoutInflater.inflate(R.layout.item_device, parent, false)
        val isActive = device.id == viewModel.adb.activeDeviceId

        row.findViewById<TextView>(R.id.device_name).text = device.alias
        row.findViewById<TextView>(R.id.device_detail).text = getString(
            R.string.device_detail,
            device.serial ?: device.endpoint,
            stateLabel(device.state)
        )

        val action = row.findViewById<MaterialButton>(R.id.device_action)
        action.setText(
            when {
                isActive && device.state == AdbDevice.State.CONNECTED -> R.string.device_showing
                device.state == AdbDevice.State.CONNECTED -> R.string.device_show
                device.state == AdbDevice.State.CONNECTING -> R.string.device_connecting
                else -> R.string.device_connect
            }
        )
        action.isEnabled = device.state != AdbDevice.State.CONNECTING
        action.setOnClickListener { onDeviceSelected(device, dialog) }
        row.setOnClickListener { onDeviceSelected(device, dialog) }

        val more = row.findViewById<MaterialButton>(R.id.device_more)
        more.visibility = if (device.isLocal) View.GONE else View.VISIBLE
        more.setOnClickListener { showDeviceOptions(device) }

        return row
    }

    private fun buildDiscoveredRow(parent: ViewGroup, service: DiscoveredService): View {
        val row = layoutInflater.inflate(R.layout.item_device, parent, false)

        row.findViewById<TextView>(R.id.device_name).text = service.host
        row.findViewById<TextView>(R.id.device_detail).text =
            getString(R.string.device_discovered_detail, service.port)

        val action = row.findViewById<MaterialButton>(R.id.device_action)
        action.setText(R.string.device_add_short)
        action.setOnClickListener { showAddDeviceDialog(service.host) }
        row.setOnClickListener { showAddDeviceDialog(service.host) }

        row.findViewById<MaterialButton>(R.id.device_more).visibility = View.GONE

        return row
    }

    private fun onDeviceSelected(device: AdbDevice, dialog: AlertDialog) {
        dialog.dismiss()
        viewModel.selectDevice(device.id)

        if (device.state == AdbDevice.State.CONNECTED)
            return

        if (device.isLocal) {
            pairAndStart()
            return
        }

        /* A device that was never paired needs the pairing code from its screen. */
        if (!device.paired) {
            showAddDeviceDialog(device.host)
            return
        }

        viewModel.connectDevice(device.id) { result ->
            runOnUiThread { reportConnectResult(device.alias, device.host, result) }
        }
    }

    private fun showDeviceOptions(device: AdbDevice) {
        val options = arrayOf(
            getString(R.string.device_rename),
            getString(R.string.device_set_port),
            getString(R.string.device_repair),
            getString(R.string.device_forget)
        )

        AlertDialog.Builder(this)
            .setTitle(device.alias)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(device)
                    1 -> showPortDialog(device)
                    2 -> showAddDeviceDialog(device.host)
                    else -> confirmForgetDevice(device)
                }
            }
            .show()
    }

    private fun showRenameDialog(device: AdbDevice) {
        val container = layoutInflater.inflate(R.layout.dialog_text_input, null)
        val input = container.findViewById<TextInputEditText>(android.R.id.edit)
        input.setText(device.alias)
        input.setSelection(input.text?.length ?: 0)

        AlertDialog.Builder(this)
            .setTitle(R.string.device_rename)
            .setView(container)
            .setPositiveButton(R.string.done) { _, _ ->
                viewModel.renameDevice(device.id, input.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Wireless debugging picks a new connect port every time it is switched on,
     * so let the user copy the one shown on the other device.
     */
    private fun showPortDialog(device: AdbDevice) {
        val container = layoutInflater.inflate(R.layout.dialog_text_input, null)
        val input = container.findViewById<TextInputEditText>(android.R.id.edit)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        if (device.port > 0)
            input.setText(device.port.toString())

        AlertDialog.Builder(this)
            .setTitle(R.string.device_set_port)
            .setMessage(R.string.device_set_port_message)
            .setView(container)
            .setPositiveButton(R.string.done) { _, _ ->
                input.text?.toString()?.trim()?.toIntOrNull()?.let { port ->
                    if (port > 0) viewModel.setDevicePort(device.id, port)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmForgetDevice(device: AdbDevice) {
        AlertDialog.Builder(this)
            .setTitle(R.string.device_forget)
            .setMessage(getString(R.string.device_forget_confirm, device.alias))
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.forgetDevice(device.id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Add a device by pairing with it. The pairing code comes from the wireless
     * debugging page of the other device.
     */
    private fun showAddDeviceDialog(prefillHost: String? = null) {
        val container = layoutInflater.inflate(R.layout.dialog_add_device, null)
        val aliasInput = container.findViewById<TextInputEditText>(R.id.device_alias)
        val hostInput = container.findViewById<TextInputEditText>(R.id.device_host)
        val pairPortInput = container.findViewById<TextInputEditText>(R.id.device_pair_port)
        val codeInput = container.findViewById<TextInputEditText>(R.id.device_code)
        val connectPortInput = container.findViewById<TextInputEditText>(R.id.device_connect_port)

        if (!prefillHost.isNullOrBlank()) {
            hostInput.setText(prefillHost)
            DnsDiscover.portForHost(prefillHost)?.let { port ->
                connectPortInput.setText(port.toString())
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.add_remote_device)
            .setView(container)
            .setPositiveButton(R.string.pair_and_connect, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = hostInput.text.toString().trim()
                val pairPort = pairPortInput.text.toString().trim()
                val code = codeInput.text.toString().trim()

                if (host.isBlank() || pairPort.isBlank() || code.isBlank()) {
                    Snackbar.make(
                        binding.output,
                        getString(R.string.error_pair_fields_required),
                        Snackbar.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                dialog.dismiss()

                val alias = aliasInput.text.toString().trim()
                val connectPort = connectPortInput.text.toString().trim().ifBlank { null }

                Snackbar.make(
                    binding.output,
                    getString(R.string.pairing_in_progress, host),
                    Snackbar.LENGTH_SHORT
                ).show()

                viewModel.addRemoteDevice(alias, host, pairPort, code, connectPort) { result ->
                    runOnUiThread {
                        if (result == ConnectResult.CONNECTED) {
                            viewModel.selectDevice(AdbDevice.remoteId(host))
                        }

                        reportConnectResult(alias.ifBlank { host }, host, result)
                    }
                }
            }
        }

        dialog.show()
    }

    private fun reportConnectResult(alias: String, host: String, result: ConnectResult) {
        val message = when (result) {
            ConnectResult.CONNECTED -> getString(R.string.connect_result_connected, alias)
            ConnectResult.PAIR_FAILED -> getString(R.string.connect_result_pair_failed)
            ConnectResult.PORT_UNKNOWN -> getString(R.string.connect_result_port_unknown, host)
            ConnectResult.CONNECT_FAILED -> getString(R.string.connect_result_connect_failed, alias)
            ConnectResult.UNAUTHORIZED -> getString(R.string.connect_result_unauthorized, alias)
        }

        Snackbar.make(binding.output, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.bookmarks -> {
                val intent = Intent(this, BookmarksActivity::class.java)
                    .putExtra(Intent.EXTRA_TEXT, binding.command.text.toString())
                bookmarkGetResult.launch(intent)
                true
            }

            R.id.last_command -> {
                binding.command.setText(lastCommand)
                binding.command.setSelection(lastCommand.length)
                true
            }

            R.id.more -> {
                val intent = Intent(this, HelpActivity::class.java)
                startActivity(intent)
                true
            }

            R.id.share -> {
                try {
                    val uri = FileProvider.getUriForFile(
                        this,
                        BuildConfig.APPLICATION_ID + ".provider",
                        viewModel.adb.activeOutputFile
                    )
                    val intent = Intent(Intent.ACTION_SEND)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .setType("file/*")
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Snackbar.make(binding.output, getString(R.string.snackbar_intent_failed), Snackbar.LENGTH_SHORT)
                        .setAction(getString(R.string.dismiss)) {}
                        .show()
                }
                true
            }

            R.id.clear -> {
                viewModel.clearOutputText()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }
}
