package com.shilapi.xcertplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.PreconnectedBluetoothSocket
import com.shilapi.xcertplay.transport.BluetoothLinkCheck
import java.util.UUID

/** Proactively checks the selected iPhone without starting a second CarPlay controller. */
class BackgroundWirelessProbeService : Service() {
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    private var socket: BluetoothSocket? = null
    private var controlSocket: BluetoothSocket? = null
    private var worker: Thread? = null
    private var aclPending = false
    private var handoffOffered = false
    @Volatile private var waitToken: String? = null
    private var latestStartId = 0

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = BackgroundWirelessWake.token(this)
        if (token == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val notification = notification(false)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Background wireless service could not enter foreground", failure)
            BackgroundWirelessDiagnostics.record(this, "foreground_failed")
            notifyNeedsSetup(this)
            stopSelf()
            return START_NOT_STICKY
        }
        synchronized(lock) {
            latestStartId = startId
            if (worker == null || stopped || waitToken != token) {
                stopNow()
                stopped = false
                waitToken = token
                aclPending = false
                handoffOffered = false
                active = this
                BackgroundWirelessDiagnostics.record(this, "waiting")
                worker = Thread({ probeLoop(token) }, "diplay-background-wireless").apply { isDaemon = true; start() }
            }
        }
        return START_STICKY
    }

    private fun probeLoop(token: String) {
        try {
        while (isCurrent(token)) {
            val address = DiPlayPreferences.phoneAddress(this) ?: break
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            val allowed = Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (allowed && adapter != null && runCatching { adapter.isEnabled }.getOrDefault(false)) {
                val started = SystemClock.elapsedRealtime()
                val device = runCatching {
                    val device = adapter.bondedDevices.firstOrNull { it.address.equals(address, true) }
                        ?: return@runCatching null
                    device
                }.getOrNull()
                val attempt = runCatching { device?.createRfcommSocketToServiceRecord(UUID.fromString(IAP2_UUID)) }.getOrNull()
                if (attempt != null && installAttempt(attempt, token)) {
                    BackgroundWirelessDiagnostics.record(this, "connecting")
                    // close() aborts a blocking Android BluetoothSocket.connect().
                    handler.postDelayed({ closeAttempt(attempt) }, CONNECT_TIMEOUT_MS)
                    val connected = runCatching { attempt.connect() }.isSuccess
                    val connectMillis = SystemClock.elapsedRealtime() - started
                    val unreal = connected && device != null &&
                        BluetoothLinkCheck.looksUnreal(connectMillis, 0) && controlProvesUnreal(device, token)
                    val handedOff = synchronized(lock) {
                        if (connected && !unreal && socket === attempt && isCurrent(token) &&
                            DiPlayPreferences.phoneAddress(this).equals(address, true) &&
                            !CarPlayBackgroundSession.hasSession()) {
                            socket = null
                            aclPending = false
                            handoffOffered = true
                            val offered = PreconnectedBluetoothSocket(attempt, connectMillis)
                            BackgroundSocketHandoff.offer(address, offered, token)
                            handler.postDelayed({ BackgroundSocketHandoff.clearIf(offered) }, HANDOFF_TIMEOUT_MS)
                            offered
                        } else null
                    }
                    if (handedOff != null) {
                        BackgroundWirelessDiagnostics.record(this, "socket_connected")
                        Log.i(TAG, "selected phone RFCOMM connected; handing off")
                        notifyReadyAndOpen(token)
                        if (!pause(HANDOFF_TIMEOUT_MS, token)) break
                        // The app was not opened/allowed in time; the next attempt starts cleanly.
                        BackgroundSocketHandoff.clearIf(handedOff)
                        synchronized(lock) { if (waitToken == token) handoffOffered = false }
                    } else {
                        closeAttempt(attempt)
                        if (isCurrent(token)) BackgroundWirelessDiagnostics.record(this, "connect_failed")
                        val wake = synchronized(lock) {
                            if (waitToken != token) false else (!unreal && aclPending).also { aclPending = false }
                        }
                        if (wake) notifyReadyAndOpen(token)
                    }
                } else if (attempt != null) runCatching { attempt.close() }
            }
            if (!pause(RETRY_MS, token)) break
        }
        } finally {
            if (CarPlayBackgroundSession.hasSession()) BackgroundWirelessWake.cancelIfCurrent(this, token)
            synchronized(lock) {
                if (waitToken == token && !stopped) stopSelf(latestStartId)
            }
        }
    }

    private fun isCurrent(token: String): Boolean = !stopped && waitToken == token &&
        !CarPlayBackgroundSession.hasSession() && BackgroundWirelessWake.matches(this, token)

    private fun installAttempt(value: BluetoothSocket, token: String): Boolean = synchronized(lock) {
        if (!isCurrent(token)) false else { socket = value; true }
    }

    private fun controlProvesUnreal(device: BluetoothDevice, token: String): Boolean {
        val control = runCatching { device.createRfcommSocketToServiceRecord(UUID.randomUUID()) }.getOrNull()
            ?: return false
        val installed = synchronized(lock) {
            if (!isCurrent(token)) false else { controlSocket = control; true }
        }
        if (!installed) { runCatching { control.close() }; return false }
        handler.postDelayed({ closeControl(control) }, CONTROL_TIMEOUT_MS)
        val started = SystemClock.elapsedRealtime()
        val connected = runCatching { control.connect() }.isSuccess
        val elapsed = SystemClock.elapsedRealtime() - started
        closeControl(control)
        return BluetoothLinkCheck.controlProvesUnreal(connected, elapsed)
    }

    private fun closeControl(value: BluetoothSocket) {
        val close = synchronized(lock) {
            if (controlSocket === value) { controlSocket = null; true } else false
        }
        if (close) runCatching { value.close() }
    }

    private fun recordAcl(address: String): Boolean {
        var openNow = false
        var token: String? = null
        val accepted = synchronized(lock) {
            if (stopped || waitToken == null || !isCurrent(waitToken!!) ||
                !DiPlayPreferences.phoneAddress(this).equals(address, true)) false
            else if (handoffOffered) true
            else {
                aclPending = true
                if (socket == null) { aclPending = false; openNow = true; token = waitToken }
                true
            }
        }
        if (openNow) token?.let { captured -> handler.post { notifyReadyAndOpen(captured) } }
        return accepted
    }

    private fun closeAttempt(value: BluetoothSocket) {
        val close = synchronized(lock) {
            if (socket === value) { socket = null; true } else false
        }
        if (close) runCatching { value.close() }
    }

    private fun pause(millis: Long, token: String): Boolean = try {
        Thread.sleep(millis)
        isCurrent(token)
    } catch (_: InterruptedException) { false }

    private fun wakeIntent(token: String) = BackgroundWirelessWake.launchIntent(this, token)

    private fun notifyReadyAndOpen(token: String) {
        synchronized(lock) {
        if (!isCurrent(token)) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(true, token)) }
            .onFailure { Log.w(TAG, "Ready notification unavailable", it) }
        BackgroundWirelessDiagnostics.record(this, "activity_requested")
        runCatching { startActivity(wakeIntent(token)) }
            .onFailure { Log.w(TAG, "Background activity launch failed; notification remains available", it) }
        }
    }

    private fun notification(ready: Boolean, token: String? = null): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.background_wireless_channel), NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL)
        } else @Suppress("DEPRECATION") Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        val open = PendingIntent.getActivity(this, BackgroundWirelessWake.PENDING_INTENT_REQUEST, if (ready) wakeIntent(requireNotNull(token)) else Intent(this, DiPlayActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return builder.setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle(getString(R.string.background_wireless_channel))
            .setContentText(getString(if (ready) R.string.background_wireless_ready else R.string.background_wireless_waiting))
            .setContentIntent(open).setOngoing(true).build()
    }

    private fun stopNow() {
        val current = synchronized(lock) {
            stopped = true
            val old = listOfNotNull(socket, controlSocket)
            socket = null
            controlSocket = null
            handoffOffered = false
            worker?.interrupt()
            worker = null
            old
        }
        current.forEach { runCatching { it.close() } }
    }

    private fun stopForToken(token: String) {
        synchronized(lock) {
            if (waitToken != token) return
            val startId = latestStartId
            stopNow()
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        stopNow()
        if (active === this) active = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DiPlayBootWireless"
        private const val CHANNEL = "diplay_background_wireless"
        private const val NOTIFICATION_ID = 31
        private const val IAP2_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val CONTROL_TIMEOUT_MS = 5_000L
        private const val RETRY_MS = 30_000L
        private const val HANDOFF_TIMEOUT_MS = 60_000L
        @Volatile private var active: BackgroundWirelessProbeService? = null

        fun isRunning(): Boolean = active != null
        fun noteAcl(address: String): Boolean = active?.recordAcl(address) ?: false
        fun startWaiting(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, BackgroundWirelessProbeService::class.java)) }
                .onFailure {
                    Log.w(TAG, "Could not start background wireless wait", it)
                    BackgroundWirelessDiagnostics.record(context, "service_start_failed")
                    notifyNeedsSetup(context)
                }
        }
        fun notifyNeedsSetup(context: Context) {
            BackgroundWirelessDiagnostics.record(context, "setup_needed")
            runCatching {
                val localized = AppLocale.wrap(context)
                val manager = context.getSystemService(NotificationManager::class.java)
                val builder = if (Build.VERSION.SDK_INT >= 26) {
                    manager.createNotificationChannel(NotificationChannel(CHANNEL,
                        localized.getString(R.string.background_wireless_channel), NotificationManager.IMPORTANCE_LOW))
                    Notification.Builder(context, CHANNEL)
                } else @Suppress("DEPRECATION") Notification.Builder(context).setPriority(Notification.PRIORITY_LOW)
                val open = PendingIntent.getActivity(context, 0, Intent(context, DiPlayActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(NOTIFICATION_ID, builder.setSmallIcon(R.drawable.ic_diplay_notification)
                    .setContentTitle(localized.getString(R.string.background_wireless_channel))
                    .setContentText(localized.getString(R.string.background_wireless_needs_setup))
                    .setContentIntent(open).setAutoCancel(true).build())
            }.onFailure { Log.w(TAG, "Background wireless setup notification unavailable", it) }
        }
        fun stopWaiting(context: Context, clearHandoff: Boolean, expectedToken: String? = null) {
            if (expectedToken != null) {
                active?.stopForToken(expectedToken)
                if (clearHandoff) BackgroundSocketHandoff.clearOwner(expectedToken)
            } else {
                active?.stopNow()
                if (clearHandoff) BackgroundSocketHandoff.clear()
                runCatching { context.stopService(Intent(context, BackgroundWirelessProbeService::class.java)) }
            }
        }
    }
}
