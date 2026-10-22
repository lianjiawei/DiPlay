package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.ExistingWifiNetwork
import com.shilapi.xcertplay.network.ExistingWifiNetworks
import com.shilapi.xcertplay.network.SystemWifiConnection

/** Location is optional for manual setup; request it only when the driver asks for the system list. */
internal class SystemWifiPicker(
    private val activity: ComponentActivity,
    private val dialogBuilder: () -> AlertDialog.Builder,
    private val openWifiSettings: () -> Unit,
) {
    private var pending: (() -> Unit)? = null
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.also { pending = null }?.invoke()
    }

    fun show(isActive: () -> Boolean, onSelected: (ExistingWifiNetwork) -> Unit) {
        val display = {
            if (isActive() && !activity.isFinishing && !activity.isDestroyed) {
                val networks = ExistingWifiNetworks.read(activity)
                val builder = dialogBuilder().setTitle(R.string.settings_existing_wifi_select)
                    .setNegativeButton(R.string.cancel, null)
                if (networks.isEmpty()) Toast.makeText(activity, R.string.settings_existing_wifi_unavailable, Toast.LENGTH_LONG).show()
                val labels = networks.map { network ->
                    activity.getString(when {
                        network.connected -> R.string.settings_existing_wifi_connected
                        network.saved -> R.string.settings_existing_wifi_saved
                        else -> R.string.settings_existing_wifi_available
                    }, network.ssid)
                } + listOf(activity.getString(R.string.settings_existing_wifi_manual), activity.getString(R.string.open_car_wi_fi_settings))
                builder.setItems(labels.toTypedArray()) { _, index ->
                    if (isActive()) {
                        when (index) {
                            networks.size -> connect(ExistingWifiNetwork(""), isActive, onSelected, manual = true)
                            networks.size + 1 -> openWifiSettings()
                            else -> {
                                val network = networks[index]
                                if (network.connected) onSelected(network)
                                else connect(network, isActive, onSelected)
                            }
                        }
                    }
                }
                builder.show()
            }
        }
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            display()
        } else {
            pending = display
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun connect(network: ExistingWifiNetwork, isActive: () -> Boolean,
        onSelected: (ExistingWifiNetwork) -> Unit, manual: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 29 || network.security == ExistingWifiNetwork.Security.UNSUPPORTED) {
            onSelected(network)
            openWifiSettings()
            return
        }
        val context = activity.appDialogContext()
        val fields = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (GlassChrome.CONTROL_GAP_DP * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
        }
        fields.addView(TextView(context).apply { setText(R.string.settings_existing_wifi_switch_note) })
        val name = EditText(context).apply {
            hint = activity.getString(R.string.existing_wifi_ssid)
            setText(network.ssid)
            setSingleLine()
        }
        if (manual) fields.addView(name)
        val password = EditText(context).apply {
            hint = activity.getString(R.string.existing_wifi_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            visibility = if (network.security == ExistingWifiNetwork.Security.OPEN) android.view.View.GONE else android.view.View.VISIBLE
        }
        fields.addView(password)
        val open = android.widget.CheckBox(context).apply {
            setText(R.string.open)
            setOnCheckedChangeListener { _, checked ->
                password.visibility = if (checked) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
        if (manual) fields.addView(open)
        val error = TextView(context).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        fields.addView(error)
        val dialog = dialogBuilder().setTitle(if (manual) activity.getString(R.string.settings_existing_wifi_manual) else network.ssid)
            .setView(android.widget.ScrollView(context).apply { addView(fields) })
            .setPositiveButton(R.string.settings_existing_wifi_connect, null).setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!isActive()) { dialog.dismiss(); return@setOnClickListener }
                val selected = if (manual) network.copy(ssid = name.text.toString(),
                    security = if (open.isChecked) ExistingWifiNetwork.Security.OPEN else ExistingWifiNetwork.Security.WPA2_PERSONAL) else network
                val secret = if (selected.security == ExistingWifiNetwork.Security.OPEN) "" else password.text.toString()
                val validName = com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(selected.ssid, "") == null
                if (validName && SystemWifiConnection.connect(activity, selected, secret)) {
                    password.text.clear()
                    dialog.dismiss()
                    onSelected(selected)
                    Toast.makeText(activity, R.string.settings_existing_wifi_connecting, Toast.LENGTH_LONG).show()
                } else error.setText(R.string.settings_existing_wifi_connect_failed)
            }
        }
        dialog.setOnDismissListener { password.text.clear() }
        dialog.show()
    }
}
