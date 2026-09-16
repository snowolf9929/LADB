package com.draco.ladb.fragments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.preference.*
import com.draco.ladb.R
import com.draco.ladb.utils.ADB
import com.draco.ladb.utils.AdbDevice
import com.draco.ladb.utils.PairedDeviceStore
import com.draco.ladb.utils.RemoteDevice
import com.draco.ladb.views.MainActivity
import com.google.android.material.snackbar.Snackbar
import kotlin.system.exitProcess

class HelpPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var adb: ADB

    override fun onAttach(context: Context) {
        super.onAttach(context)
        adb = ADB.getInstance(context)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.help, rootKey)

        findPreference<ListPreference>(getString(R.string.language_key))?.apply {
            value = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')

            setOnPreferenceChangeListener { _, newValue ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(newValue as String)
                )
                true
            }
        }
    }

    private fun restartApp() {
        val intent = Intent(requireContext(), MainActivity::class.java)
        startActivity(intent)
        requireActivity().finishAffinity()
        exitProcess(0)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            getString(R.string.unpair_key) -> unpair()

            getString(R.string.reset_keys_key) -> {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.reset_keys_title)
                    .setMessage(R.string.reset_keys_confirm)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        val context = requireContext()
                        PairedDeviceStore(context).clear()
                        PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
                            putBoolean(context.getString(R.string.paired_key), false)
                        }
                        /* Drop the paired keys themselves, not just the records. */
                        adb.resetKeys()
                        restartApp()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            getString(R.string.restart_key) -> restartApp()
            getString(R.string.tutorial_key) -> openURL(getString(R.string.tutorial_url))

            getString(R.string.developer_key) -> openURL(getString(R.string.developer_url))
            getString(R.string.source_key) -> openURL(getString(R.string.source_url))

            else -> {
                if (preference !is DialogPreference) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(preference.title)
                        .setMessage(preference.summary)
                        .show()
                }
            }
        }

        return super.onPreferenceTreeClick(preference)
    }

    /**
     * Reset the pairing of one device: this one, a single remote device, or
     * everything at once. Only this device and the "*all*" entry restart the
     * app, since only they change how this device itself connects.
     */
    private fun unpair() {
        val context = requireContext()
        val remotes = PairedDeviceStore(context).all()

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels.add(getString(R.string.device_local))
        actions.add { unpairLocal() }

        remotes.forEach { record ->
            labels.add(
                if (record.displayName == record.host) record.host
                else "${record.displayName} (${record.host})"
            )
            actions.add { unpairRemote(record) }
        }

        if (remotes.isNotEmpty()) {
            labels.add(getString(R.string.unpair_all))
            actions.add { unpairAll() }
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.unpair_title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun unpairLocal() {
        forgetLocalPairing()
        restartApp()
    }

    private fun unpairAll() {
        PairedDeviceStore(requireContext()).clear()
        forgetLocalPairing()
        restartApp()
    }

    private fun unpairRemote(record: RemoteDevice) {
        val context = requireContext()
        val id = AdbDevice.remoteId(record.host)

        adb.session(id)?.serial?.let { adb.disconnect(it) }
        adb.closeSession(id)
        PairedDeviceStore(context).remove(record.host)

        Snackbar.make(
            requireView(),
            getString(R.string.unpair_done, record.displayName),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun forgetLocalPairing() {
        val context = requireContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
            putBoolean(context.getString(R.string.paired_key), false)
        }
    }

    /**
     * Open a URL for the user
     */
    private fun openURL(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Snackbar.make(requireView(), getString(R.string.snackbar_intent_failed), Snackbar.LENGTH_SHORT).show()
        }
    }
}