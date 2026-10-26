// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.view.Window
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.widget.FrameLayout
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaFormat
import android.net.Uri
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.TextViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.ClusterSpeedometerOverlay
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.backup.SettingsBackupCodec
import com.shilapi.xcertplay.backup.SettingsBackupEndpoint
import com.shilapi.xcertplay.backup.SettingsBackupServer
import com.shilapi.xcertplay.backup.SettingsQrCode
import com.shilapi.xcertplay.compat.closeCompat
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydFieldSource
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.BydVehicleCapabilities
import com.shilapi.xcertplay.telecom.PhoneAudioRoute
import com.shilapi.xcertplay.hud.BydVehicleField
import com.shilapi.xcertplay.hud.BydVehicleFieldStore
import com.shilapi.xcertplay.hud.BydVehicleProbeOutcome
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AmbientColorMode
import com.shilapi.xcertplay.media.AmbientColorSpeed
import com.shilapi.xcertplay.media.AmbientIdleBehavior
import com.shilapi.xcertplay.media.AmbientMusicController
import com.shilapi.xcertplay.media.AmbientColorSource
import com.shilapi.xcertplay.media.AmbientMusicSettings
import com.shilapi.xcertplay.media.MtkDecoderTuning
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.ExistingWifiNetworks
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.setup.DiLinkGeneration
import com.shilapi.xcertplay.setup.SetupGuide
import com.shilapi.xcertplay.transport.EvChargingConnectors
import com.shilapi.xcertplay.update.BackgroundUpdateScheduler
import com.shilapi.xcertplay.update.UpdateStorage
import com.shilapi.xcertplay.update.UpdateApkExport
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateCatalog
import com.shilapi.xcertplay.update.UpdateChecksums
import com.shilapi.xcertplay.update.UpdateClient
import com.shilapi.xcertplay.update.InternetNetworkSelector
import com.shilapi.xcertplay.update.UpdateRelease
import com.shilapi.xcertplay.update.UpdateReleaseLookup
import com.shilapi.xcertplay.update.UpdateNetworkStatus
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal enum class SettingsCategory {
    OVERVIEW, CONNECTION, DISPLAY, AUDIO, NAVIGATION, VEHICLE, LANGUAGE, DIAGNOSTICS, ADVANCED, ABOUT,
}

/** A settings card. Every entry needs one category in [SettingsInformationArchitecture]; see AGENTS.md. */
internal enum class SettingsSection {
    VEHICLE_APPEARANCE,
    CARPLAY_CONTROLS,
    WHEEL_KEYS,
    CONNECTION_SETUP,
    DIAGNOSTICS,
    AUTOMATIC_CONNECTION,
    USB_CONNECTION,
    BYD_ADB,
    DISPLAY_AND_PERFORMANCE,
    EXPERIMENTAL_DISPLAY,
    ADVANCED_MEDIA,
    AMBIENT_LIGHTING,
    CAR_BUTTON,
    AUDIO_ROUTING,
    NAVIGATION_WHEEL,
    LOCATION,
    CLUSTER_MAP,
    BYD_NAVIGATION,
    LINCOLN_CLUSTER,
    PERMISSIONS_AND_HELP,
    LANGUAGE,
}

internal object SettingsInformationArchitecture {
    val sectionsByCategory: Map<SettingsCategory, Set<SettingsSection>> = mapOf(
        SettingsCategory.OVERVIEW to emptySet(),
        SettingsCategory.LANGUAGE to setOf(SettingsSection.LANGUAGE),
        SettingsCategory.ABOUT to emptySet(),
        SettingsCategory.CONNECTION to setOf(
            SettingsSection.CONNECTION_SETUP,
            SettingsSection.AUTOMATIC_CONNECTION,
            SettingsSection.USB_CONNECTION,
            SettingsSection.BYD_ADB,
            SettingsSection.PERMISSIONS_AND_HELP,
        ),
        SettingsCategory.DISPLAY to setOf(SettingsSection.DISPLAY_AND_PERFORMANCE),
        SettingsCategory.AUDIO to setOf(SettingsSection.AUDIO_ROUTING),
        SettingsCategory.NAVIGATION to setOf(
            SettingsSection.LOCATION,
            SettingsSection.BYD_NAVIGATION,
            SettingsSection.LINCOLN_CLUSTER,
        ),
        SettingsCategory.VEHICLE to setOf(
            SettingsSection.VEHICLE_APPEARANCE,
            SettingsSection.CARPLAY_CONTROLS,
            SettingsSection.WHEEL_KEYS,
            SettingsSection.CAR_BUTTON,
        ),
        SettingsCategory.DIAGNOSTICS to setOf(SettingsSection.DIAGNOSTICS),
        SettingsCategory.ADVANCED to setOf(
            SettingsSection.CLUSTER_MAP,
            SettingsSection.EXPERIMENTAL_DISPLAY,
            SettingsSection.ADVANCED_MEDIA,
            SettingsSection.NAVIGATION_WHEEL,
            SettingsSection.AMBIENT_LIGHTING,
        ),
    )
}

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : CarPlayHostActivity() {
    override val unifiedHome: Boolean get() = true
    override fun applyHomeWindowAppearance() = applyFullscreenMode()
    private var openProjectionWhenReady = false
    private var homeActionWidth = 0
    // Startup routing tests replace only this media-runtime boundary, never authentication resources.
    internal var projectionStarter: () -> Boolean = { requestUnifiedProjection() }

    private val mainProjectionReady: Boolean get() = CarPlayBackgroundSession.active &&
        CarPlayBackgroundSession.mainPictureReady && !projectionNeedsRetry && !projectionNeedsWifiReset

    override fun onProjectionStateChanged() {
        if (projectionIsVisible && !mainProjectionReady) {
            // Reuse the visible home when video disappears, including during automatic recovery.
            openProjectionWhenReady = true
            revealUnifiedHome(afterConnectionLoss = true)
            applyHomeWindowAppearance()
        }
        refreshStatus()
        if (openProjectionWhenReady && page == "home" && mainProjectionReady &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
            window.decorView.hasWindowFocus()) {
            if (showUnifiedProjection()) openProjectionWhenReady = false
        }
    }

    override fun showDiPlayHome(page: String) {
        openProjectionWhenReady = false
        returnToCarPlay = projectionIsVisible && page == "settings"
        if (returnToCarPlay) prepareSettingsOverview()
        this.page = if (page == "wireless-recovery") "home" else page
        render()
        if (page == "wireless-recovery") confirmWirelessReset()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val appNight = true
    private val palette = DiPlayPalette.DARK
    private var windowLearning: WindowKeyLearning? = null
    private val windowLearningPresses = WheelKeyPresses()
    private val endWindowLearning = Runnable { cancelKeyLearning() }
    private var page = "home"
    private var returnToCarPlay = false
    internal var ambientSupportCheck: (Context) -> java.util.concurrent.CompletableFuture<Boolean> = AmbientMusicController::checkSupport
    private var settingsCategory = SettingsCategory.OVERVIEW
    private var connectionSettingsReturnCategory: SettingsCategory? = null
    private var setupStep = SetupGuide.STEP_CAR
    private var setupFromSettings = false
    private var settingsSectionFilter: Set<SettingsSection>? = null
    private var clusterSafeAreaDialog: Dialog? = null
    private var clusterContentRequestVersion = 0L
    private var pendingCarHotspotSetup = false
    private var hotspotJoinControls: HotspotJoinControls? = null
    private var setupError: String? = null
    private var status: TextView? = null
    private var homeHeadline: TextView?=null
    private var homeConnectionFooter: Button?=null
    private var homeConnectionRing: ConnectionRingView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var pendingWirelessReturnHome = false
    private var bluetoothSettingsVisited = false
    private var initialLaunch = true
    private var launchAutoConnectPending = false
    private var notificationTransport = true
    private var notificationReturnHome = false
    private var exportInProgress = false
    private var usbPermissionOperation: UsbPermissionSetup.Operation? = null
    private var usbPermissionDialog: AlertDialog? = null
    internal var usbPermissionOperationFactory: (Context) -> UsbPermissionSetup.Operation = {
        UsbPermissionSetup.Operation(it.applicationContext)
    }
    internal var selectedMtkDecoderName: (String) -> String? = MtkDecoderTuning::selectedDecoderName
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var rootScroll: ScrollView? = null
    private var settingsRailScroll: ScrollView? = null
    private var reconnectBar: View? = null
    private var searchIndexSink: MutableList<String>? = null
    private var settingsSearchBox: EditText? = null
    private var settingsSearchPopup: PopupWindow? = null
    private var settingsSearchIndex: List<SettingsSearchResult>? = null
    private var settingsSearchQuery = ""
    private var settingsSearchExpanded = false
    private var settingsSearchIndexing = false
    private var settingsSearchRestoreFocus = false
    private var settingsSearchOutsideTouch: EditText? = null
    private var renderedPage: String? = null
    private var renderedSettingsCategory: SettingsCategory? = null
    private var pendingScrollY: Int? = null
    private var pendingRailScrollY: Int? = null
    private var pendingRailFocus = false
    private var bydVehicleAdvancedExpanded = false
    private var adbAccessState: BydAdbAccess.State? = null
    private var adbCheckInProgress = false
    private var adbCheckMayAsk = false
    private var adbCheckFailed = false
    private var vehicleProbeAuthorizationInProgress = false
    private var vehicleProbeInProgress = false
    private var vehicleProbeOutcome: BydVehicleProbeOutcome? = null
    private var adbCheckGeneration = 0
    private var adbStatus: TextView? = null
    private var availableUpdate: UpdateRelease? = null
    private var updateHistoryDialog: AlertDialog? = null
    private val installedUpdateVersion by lazy { version() }
    private var updatePromptResumed = false
    private var updatePrompt: UpdatePromptDialog? = null
    private var updatePromptForDownload = false
    private val automaticUpdatePrompt = Runnable { showUpdatePrompt(automatic = true) }
    private var upToDateFeedbackGeneration = 0
    private val clearUpToDateFeedback = Runnable {
        if (updateStage == UpdateStage.UP_TO_DATE &&
            upToDateFeedbackGeneration == updateGeneration && !isFinishing && !isDestroyed) {
            updateStage = UpdateStage.IDLE
            refreshUpdateRow()
        }
    }
    private var updateStorageDialog: AlertDialog? = null
    internal var updateStorageWorker: (Runnable) -> Unit = UpdateStorage::execute
    private var updateSavedPath: String? = null
    private var updateSavedUri: Uri? = null
    /** Runs the check of the public update copy; tests run it inline. */
    internal var updateInstallWorker: (Runnable) -> Unit = { Thread(it, "diplay-update-install").start() }
    internal var updateInstallerUri: (File) -> Uri = {
        FileProvider.getUriForFile(this, "$packageName.update-apks", it)
    }
    internal var updateInstallerLauncher: (Intent) -> Unit = ::startActivity
    internal var updateCheckWorker: (Runnable) -> Unit = { Thread(it, "diplay-update-check").start() }
    internal var updateDownloadWorker: (Runnable) -> Unit = { Thread(it, "diplay-update-download").start() }
    internal var updateConnection: (URL) -> URLConnection = ::openUpdateConnection
    internal var updateLookup: () -> UpdateRelease? = {
        UpdateReleaseLookup.latest(version(), userAgent(), updateConnection)
    }
    @Volatile private var updateStage = UpdateStage.IDLE
    @Volatile private var backupServer: SettingsBackupServer? = null
    @Volatile private var backupEndpoint: SettingsBackupEndpoint? = null
    /** The Settings category that opened the phone page, and the one to go back to. */
    private var backupOpenedFrom = SettingsCategory.ABOUT
    @Volatile private var backupSummary: String? = null
    @Volatile private var updateGeneration = 0
    @Volatile private var updateProgress: Int? = null
    private var updateRelease: UpdateRelease? = null
    private var updateFile: File? = null
    private var updateMessage: String? = null
    private var renderedUpdateStage: UpdateStage? = null
    private var renderedHistoryStage: HistoryStage? = null
    private var updateRowContainer: LinearLayout? = null
    private var updateStatusLabel: TextView? = null
    private var updateCheckButton: TextView? = null
    private var updateProgressButton: TextView? = null
    private var historyProgressLabel: TextView? = null
    private var historyActionButton: TextView? = null
    @Volatile private var historyStage = HistoryStage.IDLE
    @Volatile private var historyProgress: Int? = null
    @Volatile private var historyGeneration = 0
    private var historyMessage: String? = null
    private var historyInstallerFile: File? = null
    private var carButtonCard: LinearLayout? = null
    private var bydAdbControls: LinearLayout? = null
    private var adbSwitchChangePending = false
    private var pausedForAdbSwitchChange = false
    private var updatingAdbSwitches = false
    private val adbSwitches = mutableMapOf<Int, Pair<Switch, () -> Boolean>>()
    private var hotspotStartupResult: CarHotspotTethering.Result? = null
    @Volatile private var startupHotspotCancelled = false
    @Volatile private var vehicleProbeGeneration = 0
    @Volatile private var vehicleValidationGeneration = 0
    private val vehicleOperationLock = Any()
    private var automaticVehicleValidationStarted = false
    private var automaticVehicleValidationInProgress = false
    private var automaticVehicleValidationPending = false
    private var pendingVehicleReplacement: BydVehicleCapabilities? = null
    // The saved snapshot [pendingVehicleReplacement] was compared with.
    private var pendingVehicleReplacementExpected: BydVehicleCapabilities? = null
    private var pendingVehicleLostFields: Set<BydVehicleField> = emptySet()
    private var defaultVehicleStatus: BydAdbAccess.Status? = null
    private var vehicleDataReconnectPending = false
    private val automaticVehicleValidation = Runnable {
        automaticVehicleValidationPending = false
        validateSavedVehicleConfigurationAutomatically()
    }
    private data class VehicleProbeAttempt(
        val outcome: BydVehicleProbeOutcome,
        val heldCandidate: BydVehicleCapabilities? = null,
        val lostFields: Set<BydVehicleField> = emptySet(),
        val snapshotChanged: Boolean = false,
        val allowedOnlyOnce: Boolean = false,
    )

    private data class VehicleValidationAttempt(
        val status: BydAdbAccess.Status,
        val outcome: BydVehicleProbeOutcome? = null,
        val heldCandidate: BydVehicleCapabilities? = null,
        val lostFields: Set<BydVehicleField> = emptySet(),
        val snapshotChanged: Boolean = false,
        val savedFieldsReadable: Boolean = false,
    )
    private val systemWifiPicker = SystemWifiPicker(this, { appDialogBuilder() }, ::openCarClientWifiSettings)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val returnHome = notificationReturnHome
        notificationReturnHome = false
        connect(notificationTransport, returnHome)
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!projectionIsVisible) {
                refreshStatus()
                refreshUpdateAvailability()
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(brandString(R.string.nearby_devices), brandString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    private val bluetoothAutoConnectPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (DiPlayPreferences.phoneAddress(this) == null) choosePhone()
        } else {
            DiPlayPreferences.saveConnectOnPhoneBluetooth(this, false)
            render()
            permissionHelp(brandString(R.string.nearby_devices), brandString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
        }
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) {
            applyLocationReporting(true)
        } else {
            render()
            permissionHelp(brandString(R.string.location), brandString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
        }
    }
    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent(), ::cropIcon)
    private val wallpaperImport by lazy { androidx.lifecycle.ViewModelProvider(this)[WallpaperImportModel::class.java] }
    private var wallpaperProgress: AlertDialog? = null
    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.GetContent(), ::importWallpaper)
    private val wallpaperDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument(), ::importWallpaper)
    private fun importWallpaper(uri: Uri?) {
        if (uri != null) wallpaperImport.import(applicationContext, uri)
    }

    private fun chooseWallpaperImage() {
        launchCarButtonImagePicker(
            openDocument = { wallpaperDocumentPicker.launch(arrayOf("image/*")) },
            getContent = { wallpaperPicker.launch("image/*") },
            documentPickerIsSystem = documentPickerIsSystem(),
        ).onFailure {
            Toast.makeText(this, R.string.this_head_unit_has_no_image_picker, Toast.LENGTH_LONG).show()
        }
    }

    private fun reflectWallpaperImport(state: WallpaperImportModel.State) {
        if (state == WallpaperImportModel.State.LOADING) {
            if (wallpaperProgress == null) wallpaperProgress = appDialogBuilder()
                .setTitle(R.string.settings_vehicle_custom)
                .setMessage(R.string.loading_image)
                .setNegativeButton(R.string.cancel) { _, _ -> wallpaperImport.cancel() }
                .setOnCancelListener { wallpaperImport.cancel() }.show()
            return
        }
        wallpaperProgress?.dismiss()
        wallpaperProgress = null
        if (state == WallpaperImportModel.State.SAVED) render()
        if (state == WallpaperImportModel.State.FAILED) {
            Toast.makeText(this, R.string.settings_wallpaper_import_failed, Toast.LENGTH_LONG).show()
        }
        wallpaperImport.acknowledge()
    }

    private val iconDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument(), ::cropIcon)
    private fun cropIcon(uri: Uri?) {
        if (uri != null) iconCrop.launch(Intent(this, ImageCropActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    private val iconCrop = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        refreshCarButton()
        carButtonSaved()
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }
    private var storagePermissionAction: ((Boolean) -> Unit)? = null
    // Android 9 and older need storage access for visible Downloads. The caller decides whether
    // denial means app-only storage or no download at all.
    private val reportStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = storagePermissionAction
        storagePermissionAction = null
        if (action != null) action(granted) else exportDiagnostics()
    }

    private var languagePreferenceAtCreate = AppLocale.SIMPLIFIED_CHINESE

    private var interfaceOverride: Configuration? = null
    private var interfaceSystemDensityDpi = 0
    private var interfaceRecreateRequested = false

    private fun enforceInterfaceSize(): Boolean {
        val language = AppLocale.enforce(this)
        if (language) android.util.Log.i("DiPlayUi", "app language re-applied")
        val override = interfaceOverride ?: return language
        val found = resources.displayMetrics.densityDpi
        if (!InterfaceSize.enforce(resources, override)) return language
        android.util.Log.i("DiPlayUi", "interface size re-applied: $found -> ${override.densityDpi} dpi")
        return true
    }

    override fun attachBaseContext(newBase: Context) {
        val base = AppLocale.wrap(newBase)
        super.attachBaseContext(base)
        interfaceSystemDensityDpi = base.resources.configuration.densityDpi
        interfaceOverride = InterfaceSize.attach(this, base)
    }

    private fun brandString(resId: Int): String =
        VehicleBranding.text(this, resId)

    private fun brandString(resId: Int, vararg formatArgs: Any): String =
        VehicleBranding.text(this, resId, *formatArgs)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleBackgroundUpdateChecks()
        availableUpdate = UpdateAvailability.available(this, version())
        enforceInterfaceSize()
        rememberLaunchAppearance()
        languagePreferenceAtCreate = AppLocale.preference(this)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        PhoneAudioRoute.attach(applicationContext)
        // Attach unconditionally; the output checks its own switches on every snapshot.
        com.shilapi.xcertplay.lincoln.LincolnClusterOutput.attach(applicationContext)
        WheelKeyService.restoreIfNeeded(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        applyWindowAppearance()
        setupError = runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.exceptionOrNull()?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            brandString(R.string.setup_error_auth)
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        bydVehicleAdvancedExpanded = savedInstanceState?.getBoolean("byd_vehicle_advanced") ?: false
        settingsCategory = savedInstanceState?.getString("settings_category")
            ?.let { runCatching { SettingsCategory.valueOf(it) }.getOrNull() }
            ?: SettingsCategory.OVERVIEW
        connectionSettingsReturnCategory = savedInstanceState?.getString("connection_settings_return_category")
            ?.let { runCatching { SettingsCategory.valueOf(it) }.getOrNull() }
        setupStep = savedInstanceState?.getInt("setup_step") ?: SetupGuide.STEP_CAR
        setupFromSettings = savedInstanceState?.getBoolean("setup_from_settings") ?: false
        pendingWireless = savedInstanceState?.getBoolean("pending_wireless") ?: false
        pendingWirelessReturnHome = savedInstanceState?.getBoolean("pending_wireless_return_home") ?: false
        notificationTransport = savedInstanceState?.getBoolean("notification_transport") ?: true
        notificationReturnHome = savedInstanceState?.getBoolean("notification_return_home") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page")
            ?: "home"
        returnToCarPlay = savedInstanceState?.getBoolean(EXTRA_RETURN_TO_CARPLAY)
            ?: intent.getBooleanExtra(EXTRA_RETURN_TO_CARPLAY, false)
        UpdateStorage.start(applicationContext)
        render()
        scheduleAutomaticVehicleValidation()
        handleWirelessRecovery()
        if (consumeBluetoothAutoConnectIntent(intent)) {
            if (initialLaunch) {
                initialLaunch = false
                startCarHotspotOnLaunch()
            }
            handler.post { connect(true) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (projectionIsVisible) {
                    openProjectionWhenReady = false
                    revealUnifiedHome()
                    if (!projectionIsVisible) { refreshStatus(); applyFullscreenMode() }
                } else if (page != "home") navigateBack()
                else showCarLauncher()
            }
        })
        val openRequested = intent.getBooleanExtra(EXTRA_OPEN_PROJECTION, false) || intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED ||
            (savedInstanceState == null && isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession())
        val restoreConnection = savedInstanceState?.getBoolean("unified_connection_requested") == true
        if (openRequested || restoreConnection) {
            intent.removeExtra(EXTRA_OPEN_PROJECTION)
            handler.post {
                if (!isFinishing && !isDestroyed) {
                    openProjectionWhenReady = openRequested || savedInstanceState?.getBoolean("unified_projection_visible") == true
                    if (!projectionStarter()) openProjectionWhenReady = false
                    onProjectionStateChanged()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        connectionSettingsReturnCategory = null
        returnToCarPlay = intent.getBooleanExtra(EXTRA_RETURN_TO_CARPLAY, false)
        val openRequested = intent.getBooleanExtra(EXTRA_OPEN_PROJECTION, false) || intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED ||
            (isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession())
        intent.removeExtra(EXTRA_OPEN_PROJECTION)
        if (openRequested) {
            if (page != "home") { page = "home"; render() }
            openProjection()
            return
        }
        val destination = intent.getStringExtra("page") ?: "home"
        if (destination == "settings" && returnToCarPlay) prepareSettingsOverview()
        if (page != destination || destination != "home") {
            page = destination
            render()
        } else refreshStatus()
        automaticVehicleValidationStarted = false
        scheduleAutomaticVehicleValidation()
        handleWirelessRecovery()
        if (consumeBluetoothAutoConnectIntent(intent)) {
            if (initialLaunch) {
                initialLaunch = false
                startCarHotspotOnLaunch()
            }
            handler.post { connect(true) }
        }
    }

    /** Explicit Settings entry points always land predictably; recreation keeps its saved category. */
    private fun prepareSettingsOverview() {
        setSearchKeyboard(settingsSearchBox, false)
        clearSettingsSearchState()
        settingsSectionFilter = null
        connectionSettingsReturnCategory = null
        settingsCategory = SettingsCategory.OVERVIEW
    }

    private fun consumeBluetoothAutoConnectIntent(intent: Intent): Boolean {
        val requested = intent.getBooleanExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, false)
        intent.removeExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT)
        return requested && DiPlayPreferences.connectOnPhoneBluetooth(this) &&
            DiPlayPreferences.phoneAddress(this) != null && setupError == null &&
            !CarPlayBackgroundSession.hasSession()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(EXTRA_RETURN_TO_CARPLAY, returnToCarPlay)
        outState.putString("page", page)
        outState.putString("settings_category", settingsCategory.name)
        connectionSettingsReturnCategory?.let { outState.putString("connection_settings_return_category", it.name) }
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        outState.putBoolean("byd_vehicle_advanced", bydVehicleAdvancedExpanded)
        outState.putInt("setup_step", setupStep)
        outState.putBoolean("setup_from_settings", setupFromSettings)
        outState.putBoolean("pending_wireless", pendingWireless)
        outState.putBoolean("pending_wireless_return_home", pendingWirelessReturnHome)
        outState.putBoolean("notification_transport", notificationTransport)
        outState.putBoolean("notification_return_home", notificationReturnHome)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (updateInterfaceSize(newConfig)) return
        render()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            settingsSearchOutsideTouch = settingsSearchBox?.takeIf { box ->
                page == "settings" && settingsSearchExpanded &&
                    !box.containsScreenPoint(event.rawX, event.rawY) &&
                    settingsSearchPopup?.takeIf { it.isShowing }?.contentView
                        ?.containsScreenPoint(event.rawX, event.rawY) != true
            }
        }
        val outside = settingsSearchOutsideTouch.takeIf { event.actionMasked == MotionEvent.ACTION_UP }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            settingsSearchOutsideTouch = null
        }
        // Complete the tapped control's click before collapsing search. Replacing the page on DOWN
        // would discard its UP event; rebuilding it afterwards could cancel a newly opened action.
        val handled = super.dispatchTouchEvent(event)
        if (outside != null && settingsSearchBox === outside && settingsSearchExpanded) {
            closeSettingsSearch(restoreFocus = false)
        }
        return handled
    }

    private fun View.containsScreenPoint(x: Float, y: Float): Boolean {
        if (!isShown) return false
        val location = IntArray(2)
        getLocationOnScreen(location)
        return x >= location[0] && x < location[0] + width &&
            y >= location[1] && y < location[1] + height
    }

    /** True while a density change is recreating this activity. */
    private fun updateInterfaceSize(configuration: Configuration): Boolean {
        if (interfaceRecreateRequested) return true
        // The activity handles real system density changes too. Its callback contains our installed
        // density, while application resources retain the current unscaled system density.
        applicationContext.resources.configuration.densityDpi.takeIf { it > 0 }?.let {
            interfaceSystemDensityDpi = it
        }
        val next = InterfaceSize.configurationChange(configuration, interfaceSystemDensityDpi,
            InterfaceSize.preference(this))
        if (InterfaceSize.needsRecreate(interfaceOverride, next)) {
            interfaceRecreateRequested = true
            recreate()
            return true
        }
        interfaceOverride = next
        return false
    }
    private fun openOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        if (runCatching { startActivity(intent) }.isFailure) {
            android.widget.Toast.makeText(this, R.string.center_map_no_permission_screen, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        // Results from the image picker and crop screens arrive before onResume.
        enforceInterfaceSize()
        CenterMapOverlay.onDiPlayScreenShown()
    }

    override fun onStop() {
        cancelUsbPermissionSetup()
        clusterSafeAreaDialog?.dismiss()
        startupHotspotCancelled = true
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    override fun onResume() {
        super.onResume()
        updatePromptResumed = true
        WheelKeyService.restoreIfNeeded(this)
        applyFullscreenMode()
        // Returning from another activity can bring the head unit's own density back.
        if (enforceInterfaceSize()) render()
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        // Back with the hotspot access now allowed: the start-up attempt that lacked it runs again.
        if (hotspotStartupResult == CarHotspotTethering.Result.PERMISSION_REQUIRED && CarHotspotTethering.permitted(this)) {
            hotspotStartupResult = null
            startupHotspotCancelled = false
            startCarHotspotOnLaunch()
        }
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && !adbSwitchChangePending && !pausedForAdbSwitchChange &&
            (page == "settings" || page == "connection" || page == "setup")) render()
        else refreshStatus()
        pausedForAdbSwitchChange = false
        if (initialLaunch) {
            initialLaunch = false
            startCarHotspotOnLaunch()
            connectAutomaticallyOnLaunch()
        }
        if (updatePromptForDownload) handler.post { refreshUpdatePrompt() }
        wallpaperImport.observer = ::reflectWallpaperImport
        reflectWallpaperImport(wallpaperImport.state)
    }

    private fun canAutoConnectOnLaunch(): Boolean =
        !isFinishing && !isDestroyed && setupError == null &&
            !CarPlayBackgroundSession.hasSession() && !projectionIsStarting && DiPlayPreferences.autoConnect(this) &&
            !intent.hasExtra("page") && page == "home"

    private fun connectAutomaticallyOnLaunch() {
        if (launchAutoConnectPending || !canAutoConnectOnLaunch()) return
        launchAutoConnectPending = true
        handler.post {
            launchAutoConnectPending = false
            // Permission/setup or another startup entry may have advanced while this was queued.
            // Never stop an existing session just to prefer the cable.
            if (!canAutoConnectOnLaunch()) return@post
            connect(wireless = !PreattachedIphone.isAttached(this) && DiPlayPreferences.autoConnectWireless(this))
        }
    }

    override fun onPause() {
        wallpaperImport.observer = null
        wallpaperProgress?.dismiss()
        wallpaperProgress = null
        updatePromptResumed = false
        handler.removeCallbacks(automaticUpdatePrompt)
        updatePrompt?.dismiss()
        updatePrompt = null
        updateHistoryDialog?.dismiss()
        updateHistoryDialog = null
        cancelKeyLearning()
        pausedForAdbSwitchChange = adbSwitchChangePending
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        updateStorageDialog?.dismiss()
        updateFile?.parentFile?.let { UpdateStorage.release(applicationContext, it) }
        releaseHistoryInstaller()
        handler.removeCallbacks(automaticUpdatePrompt)
        handler.removeCallbacks(clearUpToDateFeedback)
        updatePrompt?.dismiss()
        updatePrompt = null
        settingsSearchPopup?.dismiss()
        hotspotJoinControls?.close()
        cancelUsbPermissionSetup()
        cancelKeyLearning()
        stopBackupServer()
        handler.removeCallbacks(automaticVehicleValidation)
        adbCheckGeneration++
        synchronized(vehicleOperationLock) {
            vehicleProbeGeneration++
            vehicleValidationGeneration++
        }
        adbCheckInProgress = false
        vehicleProbeAuthorizationInProgress = false
        vehicleProbeInProgress = false
        automaticVehicleValidationInProgress = false
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyFullscreenMode()
        if (updateInterfaceSize(newConfig)) return
        render()
    }

    private val isCompactLayout: Boolean
        // Window size only: a multi-window task can fill the whole screen, and in multi-window the
        // configuration already reports the window's own size.
        get() = resources.configuration.screenWidthDp < 550 ||
            resources.configuration.screenHeightDp < 450

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { applyFullscreenMode(); onProjectionStateChanged() }
        // Some head units put their own density back without any other callback.
        if (hasFocus && enforceInterfaceSize()) render()
    }

    private fun applyFullscreenMode() {
        if (projectionIsVisible) { applyProjectionWindowAppearance(); return }
        val multiWindow = Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode
        // Projection may have enabled decor fitting for visible bars. Home owns its own inset
        // padding and full-bleed background, so always restore its edge-to-edge window policy.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.systemBarIconsAreDark
            isAppearanceLightNavigationBars = palette.systemBarIconsAreDark
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (AirPlayPersistence.loadHideTopBar(this@DiPlayActivity) && !multiWindow) {
                hide(WindowInsetsCompat.Type.statusBars())
            } else {
                show(WindowInsetsCompat.Type.statusBars())
            }
            if (AirPlayPersistence.loadHideBottomBar(this@DiPlayActivity) && !multiWindow) {
                hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                show(WindowInsetsCompat.Type.navigationBars())
            }
        }
        ViewCompat.requestApplyInsets(window.decorView)
    }

    private val isNaturallyExpandedSettingsLayout: Boolean
        get() = resources.configuration.let {
            SettingsLayoutPolicy.isExpanded(it.screenWidthDp, it.screenHeightDp, it.fontScale)
        }

    private val isExpandedSettingsLayout: Boolean
        get() = SettingsLayoutPreferences.isActive(this) || isNaturallyExpandedSettingsLayout

    private fun render() {
        val keepProjectionVisible = projectionIsVisible && page == "home"
        if (page !in setOf("home", "settings", "connection", "setup", "backup")) page = "home"
        if (!isUpdatePage()) {
            handler.removeCallbacks(automaticUpdatePrompt)
            updatePromptForDownload = false
            updatePrompt?.dialog?.dismiss()
            updatePrompt = null
            updateHistoryDialog?.dismiss()
            updateHistoryDialog = null
        }
        enforceInterfaceSize()
        applyWindowAppearance()
        // A pending assignment belongs to the widgets being replaced, never to another page.
        cancelKeyLearning()
        // The backup server lives only while its page is shown, however the driver left it.
        if (page != "backup" && backupServer != null) stopBackupServer()
        // A restore still waiting for layout keeps its target: the old page was never laid out.
        val sameDestination = renderedPage == page &&
            (page != "settings" || renderedSettingsCategory == settingsCategory)
        val previousScrollY = (pendingScrollY ?: rootScroll?.scrollY)?.takeIf { sameDestination }
        // The rail is one destination even when its selected category changes.
        val keepRailPosition = renderedPage == "settings" && page == "settings"
        val previousRailScrollY = (pendingRailScrollY ?: settingsRailScroll?.scrollY)
            ?.takeIf { keepRailPosition }
        val restoreRailFocus = keepRailPosition &&
            (pendingRailFocus || settingsRailScroll?.hasFocus() == true)
        settingsRailScroll = null
        // The results hang off the old header; the new header reopens them if the search continues.
        settingsSearchPopup?.dismiss()
        settingsSearchPopup = null
        settingsSearchRestoreFocus = settingsSearchRestoreFocus ||
            (settingsSearchExpanded && settingsSearchBox?.hasFocus() == true)
        settingsSearchBox = null
        if (page != "settings") clearSettingsSearchState()
        status = null; connectButton = null; disconnectButton = null; lastRunning = null; carButtonCard = null
        homeConnectionRing = null
        homeHeadline=null;homeConnectionFooter=null;homeActionWidth=0
        reconnectBar = null
        exportButton = null
        bydAdbControls = null
        adbSwitches.clear()
        adbStatus = null
        renderedUpdateStage = null
        renderedHistoryStage = null
        updateRowContainer = null
        updateStatusLabel = null
        updateCheckButton = null
        updateProgressButton = null
        historyProgressLabel = null
        historyActionButton = null
        val compact = isCompactLayout && when (page) {
            "settings" -> !SettingsLayoutPreferences.isActive(this)
            else -> true
        }
        val scroll = (if (page == "home") HomeViewportScrollView(this) else ScrollView(this)).apply {
            background = SceneBackdrop(this@DiPlayActivity, appNight,
                quiet = page != "home" || resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT,
                readingShare = if (page == "home") ConnectionScreenMetrics.CONTENT_SHARE else .5f,
                strongReadingVeil = page == "home")
            isFillViewport = true; clipToPadding = false
        }
        rootScroll = scroll
        val content = column()
        scroll.addView(content)
        val header = if (page == "settings") settingsHeader(compact) else standardHeader(compact)
        val root: View = if (page == "settings" && isExpandedSettingsLayout) {
            content.setPadding(dp(FidelitySettingsMetrics.CONTENT_PAD_DP),dp(FidelitySettingsMetrics.CONTENT_TOP_DP),dp(FidelitySettingsMetrics.CONTENT_PAD_DP),dp(FidelitySettingsMetrics.CONTENT_BOTTOM_DP))
            settingsCategoryContent(content)
            expandedSettingsShell(header, scroll)
        } else {
            if (page == "home") {
                content.setPadding(dp(ReferenceHomeMetrics.SIDE_DP),dp(ReferenceHomeMetrics.TOP_DP),
                    dp(ReferenceHomeMetrics.SIDE_DP),dp(ReferenceHomeMetrics.BOTTOM_DP))
            } else if (compact) content.setPadding(dp(12), dp(10), dp(12), dp(12))
            else content.setPadding(dp(32), dp(24), dp(32), dp(32))
            content.addView(header)
            content.addView(space(when {
                page == "home" -> ReferenceHomeMetrics.HEADER_GAP_DP
                compact && page == "settings" -> SETTINGS_BLOCK_GAP_DP
                compact -> 8
                else -> 24
            }))
            if (page == "settings") content.addView(reconnectBar(), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(SETTINGS_BLOCK_GAP_DP)
            })
            when (page) {
                "connection" -> connectionSetup(content)
                "setup" -> setupGuide(content)
                "settings" -> settingsCategoryContent(content)
                "backup" -> backup(content)
                else -> home(content)
            }
            scroll
        }
        // Keep the background full screen, but all controls inside the camera and
        // visible system/keyboard bounds. Insets are physical pixels, not scaled dp.
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        val searchEditor = settingsSearchBox as? SettingsSearchEditText
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(baseLeft + safe.left, baseTop + safe.top,
                baseRight + safe.right, baseBottom + safe.bottom)
            if (settingsSearchBox === searchEditor) {
                searchEditor?.onKeyboardVisibilityChanged(insets.isVisible(WindowInsetsCompat.Type.ime()))
            }
            insets
        }
        installUnifiedHomeContent(root)
        if (keepProjectionVisible && !showUnifiedProjection()) openProjectionWhenReady = true
        ViewCompat.requestApplyInsets(root)
        renderedPage = page
        renderedSettingsCategory = settingsCategory.takeIf { page == "settings" }
        refreshStatus()
        pendingScrollY = previousScrollY
        // A stopped window still dispatches pre-draw but skips layout, so wait for a real layout;
        // the listener stays on this view and goes away with it.
        previousScrollY?.let { y ->
            scroll.doOnLayout {
                if (rootScroll === scroll) {
                    scroll.scrollTo(0, y)
                    pendingScrollY = null
                }
            }
        }
        val rail = settingsRailScroll
        pendingRailScrollY = previousRailScrollY.takeIf { rail != null }
        pendingRailFocus = restoreRailFocus && rail != null
        rail?.doOnLayout {
            if (settingsRailScroll !== rail) return@doOnLayout
            previousRailScrollY?.let { rail.scrollTo(0, it) }
            val destinations = (rail.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
            val selected = (0 until destinations.childCount)
                .map(destinations::getChildAt).firstOrNull { it.isSelected }
            if (selected != null) {
                if (restoreRailFocus) selected.requestFocus()
                selected.requestRectangleOnScreen(android.graphics.Rect(0, 0, selected.width, selected.height), true)
            }
            pendingRailScrollY = null
            pendingRailFocus = false
        }
    }

    private fun standardHeader(compact: Boolean): LinearLayout = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        val home = page == "home"
        val control = if (home) ReferenceHomeMetrics.HEADER_DP else if (compact) 44 else 52
        if (!home) {
            // Settings-derived pages use a conventional leading Back action. Branding belongs to
            // the home screen only; keeping it here pushed Back to the opposite edge and made
            // Connection setup, Setup guide and report/backup pages feel like different flows.
            addView(headerButton(brandString(R.string.back), R.drawable.ic_dp_back, compact, ::navigateBack).apply {
                contentDescription = brandString(R.string.back)
            }, LinearLayout.LayoutParams(-2, dp(control)))
            addView(Space(this@DiPlayActivity), LinearLayout.LayoutParams(0, dp(control), 1f))
            return@apply
        }

        val logoSize=dp(if (AirPlayPersistence.loadHomeVehicleBrand(this@DiPlayActivity) == HomeVehicleBrand.FORD)
            ReferenceHomeMetrics.FORD_MARK_WIDTH_DP else ReferenceHomeMetrics.LINCOLN_MARK_WIDTH_DP)
        addView(BrandMarkView(this@DiPlayActivity,appNight, homeBrand = true),LinearLayout.LayoutParams(
            logoSize, dp(ReferenceHomeMetrics.BRAND_MARK_HEIGHT_DP)))
        addView(Space(this@DiPlayActivity), LinearLayout.LayoutParams(0, dp(control), 1f))
        addView(headerButton(brandString(R.string.car_home), R.drawable.ic_dp_car, true) {
            showCarLauncher()
        }.apply {
            // Keep the home logo and the car-home action at the same fixed edges.
            val available=dp(resources.configuration.screenWidthDp-ReferenceHomeMetrics.SIDE_DP*2)
            maxWidth=minOf(available/2,available-logoSize)
                .coerceAtLeast(dp(control))
            val label=text.toString()
            contentDescription=label
            val textRoom=(maxWidth-compoundPaddingLeft-compoundPaddingRight-dp(2)).coerceAtLeast(1)
            val desired=paint.measureText(label)
            if(desired>textRoom) {
                val fitted=textSize*textRoom/desired
                val minimum=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,12f,resources.displayMetrics)
                if(fitted>=minimum) setTextSize(TypedValue.COMPLEX_UNIT_PX,fitted)
                else {
                    // Long translations use the familiar car/back icon and retain the spoken label.
                    text="";minWidth=dp(control);minimumWidth=dp(control)
                    setPadding(dp(12),0,dp(12),0)
                }
            }
        }, LinearLayout.LayoutParams(-2, dp(control)))
    }

    private fun showCarLauncher() {
        openProjectionWhenReady = false
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        moveTaskToBack(true)
    }

    private fun navigateBack() {
        // Back closes an open search first, like a system search bar.
        if (page == "settings" && settingsSearchExpanded) {
            closeSettingsSearch()
            return
        }
        val returnCategory = connectionSettingsReturnCategory
        when {
            page == "backup" -> {
                stopBackupServer()
                page = "settings"
                settingsCategory = backupOpenedFrom
            }
            page == "setup" && setupStep > SetupGuide.STEP_CAR -> setupStep--
            page == "setup" -> {
                closeSetupGuide()
                return
            }
            page == "connection" && returnCategory != null -> {
                page = "settings"
                settingsCategory = returnCategory
                connectionSettingsReturnCategory = null
            }
            page == "settings" && !isExpandedSettingsLayout && settingsCategory != SettingsCategory.OVERVIEW -> {
                settingsCategory = SettingsCategory.OVERVIEW
            }
            page == "settings" && returnToCarPlay && CarPlayBackgroundSession.hasSession() -> {
                returnToCarPlay = false
                page = "home"
                render()
                openProjection()
                return
            }
            else -> {
                returnToCarPlay = false
                page = "home"
                connectionSettingsReturnCategory = null
            }
        }
        render()
    }

    private fun openConnectionSetupFromSettings() {
        connectionSettingsReturnCategory = settingsCategory
        page = "connection"
        render()
    }

    private fun openHomeConnectionSettings() {
        openProjectionWhenReady = false
        connectionSettingsReturnCategory = null
        page = "settings"
        settingsCategory = SettingsCategory.CONNECTION
        render()
    }

    private fun homeConnectsWirelessly() = DiPlayPreferences.autoConnectWireless(this)

    private fun homeConnectionModeLabel(compact: Boolean = false) = brandString(when {
        homeConnectsWirelessly() && compact -> R.string.home_connection_mode_wireless_short
        homeConnectsWirelessly() -> R.string.home_connection_mode_wireless
        compact -> R.string.home_connection_mode_usb_short
        else -> R.string.home_connection_mode_usb
    })

    private fun settingsHeader(compact: Boolean): LinearLayout = row().apply {
        val controlHeight=dp(if (isExpandedSettingsLayout) (FidelitySettingsMetrics.HEADER_DP * resources.configuration.fontScale.coerceAtLeast(1f)).roundToInt() else if(compact)GlassChrome.MIN_TARGET_DP else FidelitySettingsMetrics.HEADER_DP)
        // Search is a secondary header action. Keep it icon-only at every window size so the
        // settings title and back action retain a stable position on car and phone displays.
        val iconOnlySearch = true
        gravity = Gravity.CENTER_VERTICAL
        addView(headerButton(brandString(if(returnToCarPlay) R.string.back else if(isExpandedSettingsLayout) R.string.ui_glass_back_home else R.string.settings),R.drawable.ic_dp_back,compact,::navigateBack).apply {
            contentDescription=brandString(if(isExpandedSettingsLayout && !returnToCarPlay)R.string.ui_glass_back_home else R.string.back)
            textSize=UiTypography.ACTION_SP.toFloat()
        },LinearLayout.LayoutParams(-2,controlHeight))
        val settingsTitle = label(if(isExpandedSettingsLayout)brandString(R.string.settings)else "", if (compact) 20 else 26, TEXT, true,
            centreGlyphs = true).apply {
            setPadding(dp(12), 0, dp(12), 0)
            setSingleLine(true)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 12,
                if (compact) 20 else 26, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        // Reserve the action buttons first; only the title may shrink in a narrow header.
        val titleBlock: View=if(isExpandedSettingsLayout && !settingsSearchExpanded) column().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(FidelitySettingsMetrics.CONTENT_PAD_DP),0,0,0)
            addView(label(brandString(R.string.settings),UiTypography.PAGE_SP,TEXT,true).apply { includeFontPadding=false })
        } else if(!isExpandedSettingsLayout && !settingsSearchExpanded)View(this@DiPlayActivity)else settingsTitle
        addView(titleBlock, LinearLayout.LayoutParams(
            if (settingsSearchExpanded) -2 else 0, controlHeight,
            if (settingsSearchExpanded) 0f else 1f,
        ))
        val searchGap = dp(if (compact) SETTINGS_HEADER_COMPACT_GAP_DP else SETTINGS_HEADER_GAP_DP)
        if (settingsSearchExpanded) {
            val searchField = settingsSearchField(compact)
            addView(searchField, LinearLayout.LayoutParams(0, controlHeight, 1f))
            searchField.doOnLayout {
                // Give a squeezed editor the title's space, using the current locale and font scale.
                val hintWidth = searchField.paint.measureText(searchField.hint.toString())
                val requiredWidth = hintWidth + searchField.compoundPaddingLeft + searchField.compoundPaddingRight
                if (searchField.width < requiredWidth) {
                    settingsTitle.visibility = View.GONE
                    searchField.layoutParams = (searchField.layoutParams as LinearLayout.LayoutParams).apply {
                        marginStart = searchGap
                    }
                }
            }
        } else {
            val openSearch = {
                // Indexing rebuilds the settings widgets. Do it before focusing an editor,
                // so the first keystroke never replaces the IME's input connection.
                settingsSearchIndex = buildSettingsSearchIndex()
                settingsSearchExpanded = true
                settingsSearchRestoreFocus = true
                render()
            }
            val searchButton = if (iconOnlySearch) {
                iconButton(R.drawable.ic_dp_search, brandString(R.string.settings_search), openSearch).apply {
                    imageTintList = ColorStateList.valueOf(TEXT)
                }
            } else {
                headerButton(brandString(R.string.settings_search_hint), R.drawable.ic_dp_search, compact, openSearch).apply {
                    textSize=UiTypography.ACTION_SP.toFloat()
                }
            }.apply {
                tag = SETTINGS_SEARCH_BUTTON_TAG
                contentDescription = brandString(R.string.settings_search)
            }
            addView(searchButton, LinearLayout.LayoutParams(if (iconOnlySearch) controlHeight else -2, controlHeight))
        }
    }

    private fun home(content: LinearLayout) {
        val body = column()
        val headline = StableConnectionTextView(this, ConnectionScreenCopy.headlines).apply {
            text=brandString(R.string.ui_glass_home_title);textSize=UiTypography.HERO_SP.toFloat()
            homeHeadline=this
            gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
            SceneText.apply(this, appNight)
        }
        homeConnectionRing = ConnectionRingView(this).apply { appearance(appNight) }
        val phone = actionButton(if (DiPlayPreferences.phoneAddress(this) == null) brandString(R.string.choose_iphone)
            else DiPlayPreferences.phoneName(this), false) { choosePhone() }.apply {
            SceneText.apply(this, appNight); textSize=UiTypography.ROW_SP.toFloat(); includeFontPadding=false
            background=RippleDrawable(ColorStateList.valueOf(RIPPLE), ColorDrawable(Color.TRANSPARENT),null)
            setPadding(dp(8),0,dp(8),0)
            val arrow=android.graphics.drawable.RotateDrawable().apply {
                drawable=getDrawable(R.drawable.ic_dp_chevron)?.mutate()?.apply { setTint(TEXT);setBounds(0,0,dp(18),dp(18)) }
                fromDegrees=90f;toDegrees=90f;level=10000
            }
            arrow.setBounds(0,0,dp(18),dp(18))
            setCompoundDrawablesRelative(null,null,arrow,null)
            doOnLayout { background=RippleDrawable(ColorStateList.valueOf(RIPPLE),ColorDrawable(Color.TRANSPARENT),null) }
        }
        phone.minHeight=dp(40)
        phone.maxLines=1;phone.ellipsize=android.text.TextUtils.TruncateAt.END
        status=StableConnectionTextView(this, ConnectionScreenCopy.allStages).apply {
            text=brandString(R.string.ready_when_you_are);textSize=UiTypography.BODY_SP.toFloat()
            gravity=Gravity.CENTER;includeFontPadding=false;SceneText.apply(this, appNight, secondary=true)
        }
        connectButton=button(brandString(R.string.ui_glass_connect_carplay),true) {
            when {
                projectionNeedsWifiReset -> confirmWirelessReset()
                projectionNeedsRetry -> { openProjectionWhenReady = true; retryUnifiedProjection() }
                CarPlayBackgroundSession.hasSession() || projectionIsStarting -> openProjection()
                else -> connect(homeConnectsWirelessly())
            }
        }.apply { textSize=UiTypography.ACTION_SP.toFloat();includeFontPadding=false;minHeight=dp(ReferenceHomeMetrics.ACTION_HEIGHT_DP);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
        disconnectButton=button(brandString(R.string.disconnect),false) {
            disconnectButton?.isEnabled=false
            openProjectionWhenReady = false
            stopUnifiedProjection { runOnUiThread { refreshStatus() } }
        }.apply {
            visibility=View.GONE;textSize=UiTypography.ACTION_SP.toFloat();includeFontPadding=false;minHeight=dp(ReferenceHomeMetrics.ACTION_HEIGHT_DP);centeredIconGroup=true
            persistentStartIcon=getDrawable(R.drawable.ic_glass_connection)?.mutate()?.apply { setTint(TEXT);setBounds(0,0,dp(20),dp(20)) }
            setCompoundDrawablesRelative(persistentStartIcon,null,null,null);compoundDrawablePadding=dp(8)
        }
        val primarySlot = FrameLayout(this).apply {
            addView(connectButton, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        val hero = ConnectionContentLayout(this, headline, homeConnectionRing!!, phone, status!!, primarySlot)
        val connectionMode=headerButton(homeConnectionModeLabel(compact = true),R.drawable.ic_glass_connection,true,::openHomeConnectionSettings).apply {
            minHeight=dp(ReferenceHomeMetrics.ACTION_HEIGHT_DP);setSingleLine(false);maxLines=1;centeredIconGroup=true
            tag=ConnectionPageLayout.CONNECTION_MODE_ACTION_TAG
            contentDescription=homeConnectionModeLabel()
        }
        homeConnectionFooter=connectionMode
        val footerStart=android.widget.FrameLayout(this).apply {
            addView(connectionMode,android.widget.FrameLayout.LayoutParams(-1,-1))
            addView(disconnectButton,android.widget.FrameLayout.LayoutParams(-1,-1))
        }
        val settings=headerButton(brandString(R.string.settings),R.drawable.ic_glass_settings,true) { openProjectionWhenReady = false; page="settings";render() }.apply {
            textSize=UiTypography.ACTION_SP.toFloat();minHeight=dp(ReferenceHomeMetrics.ACTION_HEIGHT_DP);setSingleLine(false);centeredIconGroup=true
        }
        val brand=HomeBrandView(this).apply {
            contentDescription=brandString(R.string.home_public_preview,AirPlayPersistence.loadHomeVehicleBrand(this@DiPlayActivity).displayName(this@DiPlayActivity),version())
        }
        val reserved=dp(ReferenceHomeMetrics.TOP_DP+ReferenceHomeMetrics.BOTTOM_DP+ReferenceHomeMetrics.HEADER_DP+ReferenceHomeMetrics.HEADER_GAP_DP)
        val viewport = rootScroll as HomeViewportScrollView
        body.addView(ReferenceHomeScene(this,hero,phone,connectButton!!,footerStart,settings,brand,
            { viewport.availableHeight }, reserved, ::refreshHomeActionLabels))
        content.addView(body)
    }

    // Only the category scrolls, so the rail keeps showing where the driver is.
    private fun expandedSettingsShell(header: View, categoryScroll: ScrollView): LinearLayout = column().apply {
        background = SceneBackdrop(this@DiPlayActivity, appNight, quiet = true)
        setPadding(dp(SettingsLayoutPolicy.SHELL_SIDE_PADDING_DP), dp(24),
            dp(SettingsLayoutPolicy.SHELL_SIDE_PADDING_DP), 0)
        addView(header)
        addView(space(24))
        addView(reconnectBar(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        val split = row().apply {
            gravity=Gravity.TOP; background=rounded(SURFACE,BORDER)
            clipToOutline=true
        }
        categoryScroll.background=ColorDrawable(Color.TRANSPARENT)
        // The rail scrolls on its own only when the window is too short for every destination.
        split.addView(ScrollView(this@DiPlayActivity).apply {
            addView(column().apply {
                setPadding(0, 0, 0, dp(12))
                addView(settingsRail())
            })
            settingsRailScroll = this
        },
            LinearLayout.LayoutParams(dp(SettingsLayoutPolicy.railWidthDp(resources.configuration.fontScale)), -2))
        split.addView(View(this@DiPlayActivity).apply { setBackgroundColor(Color.argb(100,190,220,255)) },
            LinearLayout.LayoutParams(dp(1),-1))
        split.addView(categoryScroll, LinearLayout.LayoutParams(0, -1, 1f))
        addView(split, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun settingsRail(): LinearLayout = column().apply {
        background = ColorDrawable(Color.TRANSPARENT)
        setPadding(dp(FidelitySettingsMetrics.RAIL_SIDE_DP),dp(FidelitySettingsMetrics.RAIL_INSET_DP),dp(FidelitySettingsMetrics.RAIL_SIDE_DP),dp(FidelitySettingsMetrics.RAIL_INSET_DP))
        SettingsCategory.entries.forEach { category ->
            addView(settingsRailDestination(category), matchButton(0, FidelitySettingsMetrics.RAIL_ROW_DP).apply {
                bottomMargin = if (category == SettingsCategory.entries.last()) 0 else dp(8)
            })
        }
    }

    private fun settingsRailDestination(category: SettingsCategory): View {
        val selected = settingsCategory == category
        val title = settingsCategoryTitle(category)
        return row().apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            isSelected = selected
            foreground = focusRing(12)
            contentDescription = brandString(R.string.settings_open_category, title)
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(RIPPLE),
                if (selected) GlassChrome.surface(RAIL_SELECTED, RAIL_SELECTED_BORDER,
                    resources.displayMetrics.density, dp(GlassChrome.CONTROL_GAP_DP).toFloat())
                else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT),
                null,
            )
            setPadding(dp(8), 0, dp(12), 0)
            addView(View(this@DiPlayActivity).apply {
                background = GradientDrawable().apply {
                    setColor(if (selected) ACCENT else Color.TRANSPARENT)
                    cornerRadius = dp(2).toFloat()
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(4), dp(34)).apply { marginEnd = dp(10) })
            addView(ImageView(this@DiPlayActivity).apply {
                setImageResource(settingsCategoryIcon(category))
                imageTintList = ColorStateList.valueOf(if (selected) ACCENT else TEXT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(FidelitySettingsMetrics.RAIL_ICON_DP),dp(FidelitySettingsMetrics.RAIL_ICON_DP)).apply { marginEnd=dp(FidelitySettingsMetrics.ROW_GAP_DP) })
            addView(label(title, UiTypography.ROW_SP, if (selected) ACCENT else TEXT, selected,
                centreGlyphs = usesChineseGlyphAlignment(resources.configuration.locales[0].language, title)),
                LinearLayout.LayoutParams(0, -1, 1f))
            setOnClickListener {
                openSettingsCategory(category)
            }
        }
    }

    private fun settingsCategoryIcon(category: SettingsCategory): Int = when (category) {
        SettingsCategory.OVERVIEW -> R.drawable.ic_dp_overview
        SettingsCategory.CONNECTION -> R.drawable.ic_glass_connection
        SettingsCategory.DISPLAY -> R.drawable.ic_glass_display
        SettingsCategory.AUDIO -> R.drawable.ic_glass_audio
        SettingsCategory.NAVIGATION -> R.drawable.ic_glass_navigation
        SettingsCategory.VEHICLE -> R.drawable.ic_glass_vehicle
        SettingsCategory.DIAGNOSTICS -> R.drawable.ic_dp_diagnostics
        SettingsCategory.ADVANCED -> R.drawable.ic_dp_advanced
        SettingsCategory.LANGUAGE -> R.drawable.ic_dp_language
        SettingsCategory.ABOUT -> R.drawable.ic_dp_about
    }

    private fun settingsCategoryTitle(category: SettingsCategory): String = brandString(when (category) {
        SettingsCategory.OVERVIEW -> R.string.settings_overview
        SettingsCategory.CONNECTION -> R.string.connection
        SettingsCategory.DISPLAY -> R.string.settings_display
        SettingsCategory.AUDIO -> R.string.audio
        SettingsCategory.NAVIGATION -> R.string.settings_navigation
        SettingsCategory.VEHICLE -> R.string.settings_vehicle
        SettingsCategory.DIAGNOSTICS -> R.string.diagnostics
        SettingsCategory.ADVANCED -> R.string.settings_advanced
        SettingsCategory.LANGUAGE -> R.string.language_section_title
        SettingsCategory.ABOUT -> R.string.about
    })

    private fun settingsCategoryContent(content: LinearLayout) {
        when (settingsCategory) {
            SettingsCategory.OVERVIEW -> settingsOverview(content)
            SettingsCategory.CONNECTION -> connectionSettings(content)
            SettingsCategory.DISPLAY -> displaySettings(content)
            SettingsCategory.AUDIO -> audioSettings(content)
            SettingsCategory.NAVIGATION -> navigationSettings(content)
            SettingsCategory.VEHICLE -> vehicleSettings(content)
            SettingsCategory.DIAGNOSTICS -> diagnosticsSettings(content)
            SettingsCategory.ADVANCED -> advancedSettings(content)
            SettingsCategory.LANGUAGE -> {
                settingsPageTitle(content, brandString(R.string.language_section_title))
                renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.LANGUAGE))
            }
            SettingsCategory.ABOUT -> about(content)
        }
    }

    private fun settingsOverview(content: LinearLayout) {
        content.addView(settingsSectionHeading(R.string.settings_quick_settings))
        content.addView(quickSettingsCard(),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(SETTINGS_BLOCK_GAP_DP) })
        val destinations = listOf(
            SettingsCategory.CONNECTION to R.string.settings_connection_summary,
            SettingsCategory.DISPLAY to R.string.settings_display_summary,
            SettingsCategory.AUDIO to R.string.settings_audio_summary,
            SettingsCategory.NAVIGATION to R.string.settings_navigation_summary,
            SettingsCategory.VEHICLE to R.string.settings_vehicle_summary,
            SettingsCategory.LANGUAGE to R.string.settings_language_summary,
            SettingsCategory.ABOUT to R.string.about_diplay,
            SettingsCategory.DIAGNOSTICS to R.string.settings_diagnostics_summary,
            SettingsCategory.ADVANCED to R.string.settings_advanced_subtitle,
        )
        content.addView(settingsSectionHeading(R.string.settings_your_setup))
        destinations.forEach { item ->
            content.addView(settingsSummaryCard(item.first,brandString(item.second)),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(SETTINGS_BLOCK_GAP_DP) })
        }

        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.OVERVIEW))
    }

    private fun visualDescendants(view: View): List<View> = listOf(view) +
        if(view is ViewGroup)(0 until view.childCount).flatMap { visualDescendants(view.getChildAt(it)) }else emptyList()

    private fun quickSettingsCard(): LinearLayout = card().apply {
        buildQuickSettings(this);normalizeSpacing(this);polishSettingsRows(this)
        if(isExpandedSettingsLayout && SettingsLayoutPolicy.overviewHasTwoColumns(resources.configuration.screenWidthDp,resources.configuration.fontScale)) {
            val controls=(0 until childCount).map(::getChildAt).filter { it.visibility!=View.GONE }
            if(controls.size==4) {
                removeAllViews()
                controls.chunked(2).forEachIndexed { index,pair ->
                    addView(GlassEqualHeightRow(this@DiPlayActivity).apply {
                        pair.forEachIndexed { column,view -> addView(view,LinearLayout.LayoutParams(0,-2,1f).apply { if(column==1)marginStart=dp(FidelitySettingsMetrics.GRID_GAP_DP) }) }
                    },LinearLayout.LayoutParams(-1,-2).apply { if(index>0)topMargin=dp(FidelitySettingsMetrics.GRID_GAP_DP) })
                }
            }
        }
    }

    private fun buildQuickSettings(card: LinearLayout): Unit = card.run {
        toggle(this, brandString(R.string.connect_when_diplay_opens),
            brandString(R.string.default_connection_description),
            DiPlayPreferences.autoConnect(this@DiPlayActivity)) { DiPlayPreferences.saveAutoConnect(this@DiPlayActivity, it) }
        appearanceControl(this)
        toggle(this, brandString(R.string.full_screen), brandString(R.string.settings_full_screen_description),
            AirPlayPersistence.loadHideTopBar(this@DiPlayActivity) && AirPlayPersistence.loadHideBottomBar(this@DiPlayActivity)) { enabled ->
            AirPlayPersistence.saveHideTopBar(this@DiPlayActivity, enabled)
            AirPlayPersistence.saveHideBottomBar(this@DiPlayActivity, enabled)
            applyFullscreenMode()
        }
        toggle(this, brandString(R.string.report_location_to_iphone),
            "${brandString(R.string.location_reporting_reconnects)} ${brandString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th)}",
            AirPlayPersistence.loadLocationReportingEnabled(this@DiPlayActivity), confirmReconnect = true, save = ::onLocationReportingChanged)
        Unit
    }

    private fun openSettingsCategory(category: SettingsCategory) {
        setSearchKeyboard(settingsSearchBox, false)
        clearSettingsSearchState()
        settingsCategory = category
        render()
    }

    private fun settingsSectionHeading(title: Int) = label(brandString(title), UiTypography.SECTION_SP, TEXT, true).apply {
        setPadding(0, 0, 0, dp(FidelitySettingsMetrics.SECTION_GAP_DP))
    }

    private fun settingsSummaryCard(category: SettingsCategory, summary: String): LinearLayout = card().apply {
        val warning = category == SettingsCategory.ADVANCED
        setPadding(dp(FidelitySettingsMetrics.CARD_PAD_DP), dp(FidelitySettingsMetrics.CATEGORY_VERTICAL_DP),
            dp(FidelitySettingsMetrics.CARD_PAD_DP), dp(FidelitySettingsMetrics.CATEGORY_VERTICAL_DP))
        isClickable = true
        isFocusable = true
        foreground = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null),
            focusRing(FidelitySettingsMetrics.CARD_RADIUS_DP)))
        contentDescription = brandString(R.string.settings_open_category, settingsCategoryTitle(category))
        addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@DiPlayActivity).apply {
                setImageResource(settingsCategoryIcon(category))
                imageTintList = ColorStateList.valueOf(if (warning) WARNING else ACCENT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(FidelitySettingsMetrics.CATEGORY_ICON_DP), dp(FidelitySettingsMetrics.CATEGORY_ICON_DP)).apply { marginEnd = dp(FidelitySettingsMetrics.CATEGORY_ICON_GAP_DP) })
            addView(column().apply {
                addView(label(settingsCategoryTitle(category), UiTypography.ROW_SP, if (warning) WARNING else TEXT, true))
                addView(label(summary, UiTypography.CAPTION_SP, MUTED).apply { setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        setOnClickListener { openSettingsCategory(category) }
    }

    private fun settingsPageTitle(content: LinearLayout, title: String) {
        content.addView(label(title, if (isExpandedSettingsLayout) UiTypography.PAGE_SP else UiTypography.COMPACT_PAGE_SP, TEXT, true).apply {
            includeFontPadding=false;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setPadding(0,0,0,dp(FidelitySettingsMetrics.ROW_GAP_DP))
        })
    }

    private fun connectionSettings(content: LinearLayout) {
        settingsPageTitle(content, brandString(R.string.connection))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.CONNECTION))
    }

    private fun displaySettings(content: LinearLayout) {
        settingsPageTitle(content, brandString(R.string.settings_display))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.DISPLAY))
    }

    private fun nightModeLabels() = CarPlayNightMode.entries.map { mode ->
        brandString(when (mode) {
            CarPlayNightMode.SYSTEM -> R.string.carplay_night_system
            CarPlayNightMode.AMBIENT -> R.string.carplay_night_ambient
            CarPlayNightMode.DAY -> R.string.carplay_night_day
            CarPlayNightMode.NIGHT -> R.string.carplay_night_night
            CarPlayNightMode.SCHEDULE -> R.string.carplay_night_schedule
        })
    }

    private fun appearanceControl(parent: LinearLayout) {
        val modes = CarPlayNightMode.entries
        choice(parent, brandString(R.string.carplay_night_mode), nightModeLabels(),
            modes.indexOf(AirPlayPersistence.loadCarPlayNightMode(this)), reconnects = false) { index ->
            AirPlayPersistence.saveCarPlayNightMode(this, modes[index])
        }
    }

    private fun audioSettings(content: LinearLayout) {
        settingsPageTitle(content, brandString(R.string.audio))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.AUDIO))
    }

    private fun navigationSettings(content: LinearLayout) {
        settingsPageTitle(content, brandString(R.string.settings_navigation))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.NAVIGATION))
    }

    private fun vehicleSettings(content: LinearLayout) {
        settingsPageTitle(content, brandString(R.string.settings_vehicle))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.VEHICLE))
        section(content, brandString(R.string.settings_advanced), R.drawable.ic_dp_advanced) { card ->
            card.addView(settingsNote(brandString(R.string.settings_vehicle_description), 16, MUTED))
            card.addView(button(brandString(R.string.settings_open_advanced), false) {
                settingsCategory = SettingsCategory.ADVANCED
                render()
            }, matchButton(14, 56))
        }
    }

    private fun diagnosticsSettings(content: LinearLayout) {
        settingsPageTitle(content, brandString(R.string.diagnostics))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.DIAGNOSTICS))
    }

    internal data class SettingsSearchResult(val title: String, val category: SettingsCategory)

    // Renders every category into a detached view and records what the builders label.
    // render() afterwards replaces every widget reference those detached builds assigned.
    // ponytail: indexes the default state only; controls behind an expander (vehicle data) are not found.
    private fun buildSettingsSearchIndex(): List<SettingsSearchResult> {
        val selected = settingsCategory
        // Category names first, so a card that only links to a category cannot claim its name.
        val index = SettingsCategory.entries.associateByTo(linkedMapOf(), ::settingsCategoryTitle)
        try {
            for (category in SettingsCategory.entries.filter { it != SettingsCategory.OVERVIEW } + SettingsCategory.OVERVIEW) {
                settingsCategory = category
                val titles = mutableListOf(settingsCategoryTitle(category))
                searchIndexSink = titles
                settingsCategoryContent(column())
                titles.forEach { index.putIfAbsent(it, category) }
            }
        } finally {
            searchIndexSink = null
            settingsCategory = selected
        }
        render()
        return index.map { SettingsSearchResult(it.key, it.value) }
    }

    private fun searchSettings(index: List<SettingsSearchResult>, query: String): List<SettingsSearchResult> {
        val words = query.trim().lowercase(Locale.getDefault()).split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return emptyList()
        return index.filter { result ->
            val haystack = "${result.title} ${settingsCategoryTitle(result.category)}".lowercase(Locale.getDefault())
            words.all(haystack::contains)
        }
    }

    private fun openSearchResult(result: SettingsSearchResult) {
        setSearchKeyboard(settingsSearchBox, false)
        clearSettingsSearchState()
        openSettingsCategory(result.category)
        val scroll = rootScroll ?: return
        val match = descendants(scroll).filterIsInstance<TextView>().firstOrNull {
            it.text.toString() == result.title || it.text.startsWith(result.title + VALUE_SEPARATOR)
        } ?: return
        val target = generateSequence<View>(match) { it.parent as? View }.firstOrNull { it.isClickable } ?: match
        scroll.doOnLayout {
            var top = 0
            var view: View? = target
            while (view != null && view !== scroll) { top += view.top; view = view.parent as? View }
            scroll.smoothScrollTo(0, (top - dp(24)).coerceAtLeast(0))
            target.requestFocus()
            target.isPressed = true
            handler.postDelayed({ target.isPressed = false }, SEARCH_HIGHLIGHT_MILLIS)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    /**
     * The settings header search box; matches drop down under it as the driver types.
     * The index is built before expanding the field, so typing keeps the same editor
     * and keyboard input connection.
     */
    private fun settingsSearchField(compact: Boolean): EditText = SettingsSearchEditText(this, compact).apply {
        settingsSearchBox = this
        onDismissSearch = {
            if (settingsSearchBox === this && settingsSearchExpanded) closeSettingsSearch()
        }
        setSingleLine()
        hint = brandString(R.string.settings_search_hint)
        contentDescription = brandString(R.string.settings_search)
        textSize = if (compact) 16f else 18f
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        // The editor scrolls long text sideways and its drawing canvas scrolls with it, but Android
        // draws the background unscrolled. The icon and focus ring live there, so they stay put
        // without compensating for scroll or for the text's vertical centring.
        val iconSize = headerIconSize(compact)
        val icon = getDrawable(R.drawable.ic_dp_search)?.mutate()?.apply { setTint(MUTED) }
        background = android.graphics.drawable.LayerDrawable(
            listOfNotNull(buttonShape(BUTTON, BORDER), icon, buttonFocusRing()).toTypedArray(),
        ).apply {
            if (icon != null) {
                setLayerGravity(1, Gravity.START or Gravity.CENTER_VERTICAL)
                setLayerSize(1, iconSize, iconSize)
                setLayerInsetRelative(1, dp(16), 0, 0, 0)
            }
        }
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        includeFontPadding = false
        setPaddingRelative(dp(16) + if (icon != null) iconSize + dp(10) else 0, 0, dp(16), 0)
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        // Landscape head units would otherwise cover the results with a full-screen editor.
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        // Touch focus waits for a tap, so opening settings never focuses the box or raises the keyboard.
        isFocusableInTouchMode = settingsSearchRestoreFocus
        setText(settingsSearchQuery)
        setSelection(text.length)
        setOnClickListener {
            if (!isFocusableInTouchMode) {
                isFocusableInTouchMode = true
                requestFocus()
                setSearchKeyboard(this, true)
            }
            showSettingsSearchResults()
        }
        setOnFocusChangeListener { _, focused -> if (focused) showSettingsSearchResults() }
        setOnEditorActionListener { _, actionId, event ->
            val search = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (!search) return@setOnEditorActionListener false
            if (event == null || event.action == KeyEvent.ACTION_DOWN) settingsSearchResults().firstOrNull()?.let(::openSearchResult)
            true
        }
        doAfterTextChanged { onSettingsSearchChanged(this) }
        // The keyboard resizes the window, not this field, so follow window layouts. A listener added
        // here would join the window's observer and outlive every rebuilt field; register it only
        // while this field is attached.
        val followWindow = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (settingsSearchBox === this && settingsSearchPopup?.isShowing == true) {
                positionSettingsSearchResults(this, settingsSearchPopup!!)
            }
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = view.viewTreeObserver.addOnGlobalLayoutListener(followWindow)
            override fun onViewDetachedFromWindow(view: View) = view.viewTreeObserver.removeOnGlobalLayoutListener(followWindow)
        })
        if (settingsSearchRestoreFocus) {
            settingsSearchRestoreFocus = false
            post {
                if (settingsSearchBox !== this) return@post
                requestFocus()
                setSearchKeyboard(this, true)
                showSettingsSearchResults()
            }
        }
    }

    private fun onSettingsSearchChanged(box: EditText) {
        if (box !== settingsSearchBox) return
        settingsSearchQuery = box.text.toString()
        if (settingsSearchQuery.isEmpty()) {
            // Keep this search's index when the query is cleared. Closing search invalidates it.
            settingsSearchPopup?.dismiss()
            return
        }
        if (settingsSearchIndex != null) {
            showSettingsSearchResults()
            return
        }
        if (settingsSearchIndexing) return
        settingsSearchIndexing = true
        // Not from inside the text watcher: indexing replaces this box.
        handler.post {
            settingsSearchIndexing = false
            if (page != "settings" || settingsSearchQuery.isEmpty() || settingsSearchIndex != null) return@post
            settingsSearchRestoreFocus = true
            settingsSearchIndex = buildSettingsSearchIndex()
        }
    }

    private fun settingsSearchResults(): List<SettingsSearchResult> =
        settingsSearchIndex?.let { searchSettings(it, settingsSearchQuery) }.orEmpty()

    private fun showSettingsSearchResults() {
        val box = settingsSearchBox ?: return
        if (settingsSearchIndex == null || settingsSearchQuery.isBlank() || !box.isAttachedToWindow) {
            settingsSearchPopup?.dismiss()
            return
        }
        val results = settingsSearchResults()
        val list = column().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        if (results.isEmpty()) {
            list.addView(label(brandString(R.string.settings_search_empty), 15, MUTED).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
        }
        results.forEach { list.addView(settingsSearchRow(it)) }
        val existing = settingsSearchPopup?.takeIf { it.isShowing }
        val scroll = (existing?.contentView as? ScrollView) ?: ScrollView(this)
        scroll.removeAllViews()
        scroll.addView(list)
        if (existing != null) {
            positionSettingsSearchResults(box, existing)
            return
        }
        settingsSearchPopup = PopupWindow(scroll, box.width, 0, false).apply {
            setBackgroundDrawable(rounded(SURFACE, BORDER))
            elevation = dp(8).toFloat()
            // Outside touches also reach the activity, which collapses search after the gesture.
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            // The list is placed in screen coordinates. Android 10 made this call public; before it the
            // same switch is setLayoutInScreenEnabled, and calling the new name stops the app.
            if (Build.VERSION.SDK_INT >= 29) setIsLaidOutInScreen(true)
            else runCatching {
                PopupWindow::class.java.getMethod("setLayoutInScreenEnabled", Boolean::class.javaPrimitiveType)
                    .invoke(this, true)
            }
            positionSettingsSearchResults(box, this)
        }
    }

    private fun positionSettingsSearchResults(box: EditText, popup: PopupWindow) {
        val visible = android.graphics.Rect()
        box.getWindowVisibleDisplayFrame(visible)
        val location = IntArray(2)
        box.getLocationOnScreen(location)
        val top = location[1] + box.height + dp(SETTINGS_SEARCH_RESULTS_GAP_DP)
        val width = minOf(box.width, visible.width())
        val maxHeight = minOf(
            visible.bottom - top,
            dp(if (resources.configuration.screenHeightDp < 600)
                SETTINGS_SEARCH_RESULTS_SHORT_HEIGHT_DP else SETTINGS_SEARCH_RESULTS_HEIGHT_DP),
        )
        // Never let Android flip the list above its anchor and cover the field.
        if (width <= 0 || maxHeight <= 0) {
            popup.dismiss()
            return
        }
        val left = location[0].coerceIn(visible.left, visible.right - width)
        val content = popup.contentView
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        val height = content.measuredHeight.coerceIn(1, maxHeight)
        if (popup.isShowing) {
            popup.update(left, top, width, height)
        } else {
            popup.width = width
            popup.height = height
            popup.showAtLocation(box.rootView, Gravity.TOP or Gravity.LEFT, left, top)
        }
    }

    private fun settingsSearchRow(result: SettingsSearchResult): View = column().apply {
        val category = settingsCategoryTitle(result.category)
        isClickable = true
        isFocusable = true
        contentDescription = "${result.title}, $category"
        foreground = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null), focusRing(12)))
        minimumHeight = dp(56)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(label(result.title, 17, TEXT, true))
        addView(label(category, 13, MUTED))
        setOnClickListener { openSearchResult(result) }
    }

    private fun closeSettingsSearch(restoreFocus: Boolean = true) {
        if (!settingsSearchExpanded) return
        val box = settingsSearchBox as? SettingsSearchEditText
        val header = box?.parent as? LinearLayout
        val parent = header?.parent as? ViewGroup
        clearSettingsSearchState()
        settingsSearchBox = null
        box?.clearFocus()
        setSearchKeyboard(box, false)
        if (box != null && header != null && parent != null) {
            val index = parent.indexOfChild(header)
            val params = header.layoutParams
            parent.removeViewAt(index)
            parent.addView(settingsHeader(box.compact), index, params)
        } else render()
        if (restoreFocus) {
            descendants(window.decorView).firstOrNull { it.tag == SETTINGS_SEARCH_BUTTON_TAG }?.requestFocus()
        }
    }

    private fun clearSettingsSearchState() {
        settingsSearchQuery = ""
        settingsSearchExpanded = false
        settingsSearchIndex = null
        settingsSearchRestoreFocus = false
        settingsSearchOutsideTouch = null
        settingsSearchPopup?.dismiss()
    }

    private fun setSearchKeyboard(view: View?, show: Boolean) {
        view ?: return
        val keyboard = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        if (show) keyboard.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        else keyboard.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun advancedSettings(content: LinearLayout) {
        settingsPageTitle(content, brandString(R.string.settings_advanced))
        val caution = card().apply {
            addView(label(brandString(R.string.settings_advanced_caution_title), UiTypography.ROW_SP, WARNING, true))
            addView(label(brandString(R.string.settings_advanced_caution_description), 14, MUTED).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        content.addView(caution, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.ADVANCED))
        advancedVehicleDataSettings(content)
    }

    private fun advancedVehicleDataSettings(content: LinearLayout) {
        // The whole card reads BYD vehicle fields over ADB (battery, range, gear, speed). On a
        // Lincoln/Ford head unit there is no BYD receiver for any of it, so the card is not shown.
        if (com.shilapi.xcertplay.lincoln.LincolnVehicle.eligible(this)) return
        section(content, brandString(R.string.settings_vehicle), R.drawable.ic_dp_dashboard) { card ->
            card.addView(button(brandString(if (bydVehicleAdvancedExpanded)
                R.string.hide_advanced_vehicle_data else R.string.advanced_vehicle_data), false) {
                bydVehicleAdvancedExpanded = !bydVehicleAdvancedExpanded
                render()
            }, matchButton(0, 56))
            if (bydVehicleAdvancedExpanded) {
                advancedVehicleData(card)
                if (!BydOutputSettings.available(this)) clusterSongSwitch(card)
            }
        }
    }

    private fun renderSections(
        content: LinearLayout,
        sections: Set<SettingsSection>,
    ) {
        val previous = settingsSectionFilter
        settingsSectionFilter = sections
        try {
            allSettingsSections(content)
        } finally {
            settingsSectionFilter = previous
        }
    }

    private fun allSettingsSections(content: LinearLayout) {
        filteredSection(content, SettingsSection.VEHICLE_APPEARANCE,
            brandString(R.string.settings_vehicle_appearance), R.drawable.ic_dp_car,
            showHeading = false) { card ->
            homeVehicleBrandControl(card)
            vehicleWallpaperControl(card)
        }
        filteredSection(content, SettingsSection.CARPLAY_CONTROLS,
            brandString(R.string.carplay_controls), R.drawable.ic_dp_controls) { card ->
            val gestureFingers = listOf(0, 2, 3, 4)
            choice(card, brandString(R.string.settings_gesture_fingers_label),
                gestureFingers.map { if (it == 0) brandString(R.string.settings_gesture_disabled) else brandString(R.string.settings_gesture_fingers_option, it) },
                gestureFingers.indexOf(AirPlayPersistence.loadSettingsGestureFingers(this)).coerceAtLeast(0),
                reconnects = false) {
                AirPlayPersistence.saveSettingsGestureFingers(this, gestureFingers[it])
            }
            card.addView(label(brandString(R.string.settings_gesture_fingers_hint), 14, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
            toggle(card, brandString(R.string.right_hand_drive), brandString(R.string.place_carplay_s_controls_closer_to_the_driver), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it); markReconnectNeeded() }
        }
        filteredSection(content, SettingsSection.WHEEL_KEYS,
            brandString(R.string.settings_wheel_keys), R.drawable.ic_dp_controls, build = ::wheelKeysSettings)
        filteredSection(content, SettingsSection.CONNECTION_SETUP,
            brandString(R.string.connection_setup), R.drawable.ic_dp_connection) { card ->
            card.addView(settingsNote(brandString(R.string.choose_how_to_connect_follow_the_setup_steps_and_save_your), 16, MUTED))
            card.addView(button(brandString(R.string.open_connection_setup), false, ::openConnectionSetupFromSettings), matchButton(12, 60))
            card.addView(space(12))
            // Also here, not only in the CarPlay menu: with the CH341 removed CarPlay cannot connect,
            // so that menu could not be reached to switch back (#696).
            val mfiTargets = listOf(MfiTarget.LOCAL, MfiTarget.USB_CH341)
            val storedMfiTarget = AirPlayPersistence.loadMfiTarget(this)
            if (storedMfiTarget in mfiTargets) {
                choice(card, brandString(R.string.mfi_certificate_signing_target), listOf(
                    brandString(R.string.local_offline),
                    brandString(R.string.usb_ch341),
                ), mfiTargets.indexOf(storedMfiTarget)) { index ->
                    val target = mfiTargets[index]
                    AirPlayPersistence.saveMfiTarget(this, target)
                    setupError = runCatching { DiPlayBootstrap.ensure(this, target) }.exceptionOrNull()?.let {
                        android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
                        brandString(R.string.setup_error_auth)
                    }
                }
                card.addView(label(brandString(R.string.settings_mfi_target_description), 14, MUTED))
            }
        }
        filteredSection(content, SettingsSection.DIAGNOSTICS,
            brandString(R.string.diagnostics), R.drawable.ic_dp_diagnostics) { card ->
            val reportDetails = brandString(R.string.reports_save_to_downloads_diplay) +
                brandString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden)
            exportButton = destinationRow(if (exportInProgress) brandString(R.string.saving_report) else brandString(R.string.save_diagnostic_report),
                reportDetails, R.drawable.ic_dp_diagnostics) { saveReportToDownloads() }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(0, FidelitySettingsMetrics.SETTING_ROW_DP))
            card.addView(destinationRow(brandString(R.string.choose_save_location), "", R.drawable.ic_dp_storage) {
                chooseReportDestination()
            }, matchButton(0, FidelitySettingsMetrics.SETTING_ROW_DP))
            card.addView(destinationRow(brandString(R.string.settings_report_on_phone), "", R.drawable.ic_dp_connection) {
                openBackup(SettingsCategory.DIAGNOSTICS)
            }, matchButton(0, FidelitySettingsMetrics.SETTING_ROW_DP))
            toggle(card, brandString(R.string.settings_fps_counter), brandString(R.string.settings_fps_counter_description),
                AirPlayPersistence.loadFpsCounter(this)) { AirPlayPersistence.saveFpsCounter(this, it) }
        }
        filteredSection(content, SettingsSection.AUTOMATIC_CONNECTION,
            brandString(R.string.automatic_connection), R.drawable.ic_dp_automation) { card ->
            toggle(card, brandString(R.string.connect_when_diplay_opens), brandString(R.string.default_connection_description), DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            toggle(card, brandString(R.string.connect_when_iphone_bluetooth_connects),
                brandString(R.string.connect_when_iphone_bluetooth_connects_description),
                DiPlayPreferences.connectOnPhoneBluetooth(this)) { enabled ->
                DiPlayPreferences.saveConnectOnPhoneBluetooth(this, enabled)
                if (enabled && Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    bluetoothAutoConnectPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else if (enabled && DiPlayPreferences.phoneAddress(this) == null) choosePhone()
            }
            val connectionModes = DefaultConnectionMode.entries
            choice(card, brandString(R.string.default_connection_mode), listOf(
                brandString(R.string.default_connection_last_used),
                brandString(R.string.default_connection_wireless),
                brandString(R.string.default_connection_usb)
            ), connectionModes.indexOf(DiPlayPreferences.defaultConnectionMode(this)), reconnects = false) {
                DiPlayPreferences.saveDefaultConnectionMode(this, connectionModes[it])
            }
            adbToggle(card, R.string.open_after_the_car_starts,
                R.string.availability_depends_on_your_head_unit_s_startup_settings,
                read = { AirPlayPersistence.loadAutoStartOnBoot(this) },
                needsAdb = { CarHotspotSettings.enabled(this) &&
                    AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL },
                permissions = { listOf(CarHotspotSetup.Permission.BOOT_LAUNCH) }) {
                AirPlayPersistence.saveAutoStartOnBoot(this, it)
            }
            val autoConfirmActive = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)
            toggle(
                card,
                brandString(R.string.usb_auto_confirm_title),
                brandString(R.string.usb_auto_confirm_subtitle),
                autoConfirmActive,
            ) { enabled ->
                if (enabled) promptEnableUsbAutoConfirm()
                else {
                    if (!UsbAutoConfirmService.openSettings(this)) toast(brandString(R.string.wheel_keys_no_settings))
                    render()
                }
            }
            if (!autoConfirmActive) {
                card.addView(
                    button(
                        brandString(R.string.btn_auto_apply_permissions),
                        true,
                    ) {
                        autoApplyPermissions()
                    },
                    matchButton(8, 54),
                )
            } else {
                card.addView(label(brandString(R.string.usb_auto_confirm_active_hint), 14, READY).apply {
                    setPadding(0, dp(4), 0, dp(8))
                })
            }
            card.addView(destinationRow(brandString(R.string.boot_start_repair), brandString(R.string.boot_start_repair_desc),
                R.drawable.ic_dp_automation) { repairBootStart() }, matchButton(0, FidelitySettingsMetrics.SETTING_ROW_DP))
            card.addView(button(chooseIphoneTitle(), false) { choosePhone() }, matchButton(12, 60))
        }
        filteredSection(content, SettingsSection.USB_CONNECTION,
            brandString(R.string.settings_usb_connection), R.drawable.ic_dp_connection) { card ->
            val currents = UsbChargingCurrent.entries
            choice(card, brandString(R.string.settings_usb_charging), listOf(
                brandString(R.string.settings_usb_charging_normal),
                brandString(R.string.settings_usb_charging_reduced),
                brandString(R.string.settings_usb_charging_low),
            ), currents.indexOf(AirPlayPersistence.loadUsbChargingCurrent(this)), reconnects = false) {
                AirPlayPersistence.saveUsbChargingCurrent(this, currents[it])
                if (!AirPlayPersistence.loadWirelessEnabled(this)) markReconnectNeeded()
            }
            card.addView(label(brandString(R.string.settings_usb_charging_description), 14, MUTED))
        }
        if (settingsSectionFilter?.contains(SettingsSection.BYD_ADB) != false) bydAdbSettings(content)
        filteredSection(content, SettingsSection.DISPLAY_AND_PERFORMANCE,
            brandString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
            val nightModes = CarPlayNightMode.entries
            val nightMode = AirPlayPersistence.loadCarPlayNightMode(this)
            val ambientControls = column().apply {
                visibility = if (nightMode == CarPlayNightMode.AMBIENT) View.VISIBLE else View.GONE
            }
            val scheduleControls = column().apply {
                visibility = if (nightMode == CarPlayNightMode.SCHEDULE) View.VISIBLE else View.GONE
            }
            choice(
                card,
                brandString(R.string.carplay_night_mode),
                nightModeLabels(),
                nightModes.indexOf(nightMode),
                reconnects = false,
            ) { index ->
                AirPlayPersistence.saveCarPlayNightMode(this, nightModes[index])
                ambientControls.visibility = if (nightModes[index] == CarPlayNightMode.AMBIENT) View.VISIBLE else View.GONE
                scheduleControls.visibility = if (nightModes[index] == CarPlayNightMode.SCHEDULE) View.VISIBLE else View.GONE
                if (page == "settings") polishSettingsRows(card)
            }
            val sentenceSeparator = if (resources.configuration.locales[0].language == "zh") "" else " "
            card.addView(label(brandString(R.string.carplay_night_hint) + sentenceSeparator +
                brandString(R.string.carplay_night_time_note), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            ambientControls.addView(settingsNote(brandString(R.string.carplay_night_ambient_hint), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            ambientLightThresholdControl(ambientControls)
            nightDelaySettingControl(ambientControls, R.string.ambient_delay_title, R.string.ambient_delay_hint,
                0..60, CarPlayNightMode.DEFAULT_AMBIENT_DELAY_SECONDS, R.string.ambient_delay_summary,
                { AirPlayPersistence.loadAmbientDelaySeconds(this) },
                save = { AirPlayPersistence.saveAmbientDelaySeconds(this, it) })
            scheduleControls.addView(settingsNote(brandString(R.string.carplay_night_schedule_hint), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            scheduleTimeControl(scheduleControls, start = true)
            scheduleTimeControl(scheduleControls, start = false)
            card.addView(button(brandString(R.string.picture_adjustments), false) {
                startActivity(CarPlayEntry.intent(this)
                    .putExtra("picture_controls", true).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }.apply {
                rowPresentation=true; rowTitle=brandString(R.string.picture_adjustments)
                rowIconResource=R.drawable.ic_dp_display
                textSize=UiTypography.ROW_SP.toFloat()
            }, matchButton(0, FidelitySettingsMetrics.SETTING_ROW_DP))
            carPlaySizeControl(card)
            resolutionSettingControl(
                card, R.string.resolution, R.string.custom_resolution_hint,
                CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT, 100,
                R.string.custom_resolution_summary,
                { AirPlayPersistence.loadDisplayScalePercent(this) }, reconnects = true,
                save = { AirPlayPersistence.saveDisplayScalePercent(this, it) },
            )
            choice(card, brandString(R.string.frame_rate), listOf(brandString(R.string.s_30_fps_lighter_load), brandString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            card.addView(overlaySliderRow(
                brandString(R.string.settings_carplay_corner_radius),
                (0..AirPlayPersistence.MAX_CARPLAY_CORNER_RADIUS_DP
                    step AirPlayPersistence.CARPLAY_CORNER_RADIUS_STEP_DP).toList(),
                AirPlayPersistence.loadCarPlayCornerRadiusDp(this),
            ) { value ->
                if (value == 0) brandString(R.string.settings_carplay_corner_radius_off)
                else brandString(R.string.settings_carplay_corner_radius_value, value)
            }.also { row ->
                row.onSave = { value ->
                    AirPlayPersistence.saveCarPlayCornerRadiusDp(this, value)
                }
            })
            card.addView(label(brandString(R.string.settings_carplay_corner_radius_description), 14, MUTED))
            card.addView(ambientControls)
            card.addView(scheduleControls)
            carPlayDockControl(card)
            addSystemBarControls(
                hideTopBar = AirPlayPersistence.loadHideTopBar(this),
                hideBottomBar = AirPlayPersistence.loadHideBottomBar(this),
                onHideTopBarChanged = { AirPlayPersistence.saveHideTopBar(this, it); applyFullscreenMode() },
                onHideBottomBarChanged = { AirPlayPersistence.saveHideBottomBar(this, it); applyFullscreenMode() },
            ) { label, checked, onChanged ->
                toggle(card, brandString(label), brandString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), checked, save = onChanged)
            }
            toggle(card, brandString(R.string.adapt_pip_resolution), brandString(R.string.adapt_pip_resolution_description), AirPlayPersistence.loadAdaptPipResolution(this)) {
                AirPlayPersistence.saveAdaptPipResolution(this, it)
            }
            card.addView(button("${brandString(R.string.settings_interface_size)} · ${InterfaceSize.displayName(this, InterfaceSize.preference(this))}", false) {
                InterfaceSize.showPicker(this)
            }, matchButton(12, 60))
            card.addView(label(brandString(R.string.settings_interface_size_hint), 14, MUTED))
            if (!isNaturallyExpandedSettingsLayout) toggle(card, brandString(R.string.settings_force_full_settings),
                brandString(R.string.settings_force_full_settings_hint), SettingsLayoutPreferences.forceFull(this)) {
                SettingsLayoutPreferences.saveForceFull(this, it)
                render()
            }
        }
        filteredSection(content, SettingsSection.EXPERIMENTAL_DISPLAY,
            brandString(R.string.settings_experimental_display), R.drawable.ic_dp_display) { card ->
            toggle(card, brandString(R.string.settings_home_card_media),
                brandString(R.string.settings_home_card_media_description),
                CarPlayHomeCardSettings.enabled(this)) {
                CarPlayHomeCardSettings.setEnabled(this, it)
                CarPlayHomeCard.refresh(this)
            }
            toggle(card, brandString(R.string.settings_harman_fullscreen_experimental),
                brandString(R.string.settings_harman_fullscreen_experimental_description),
                AirPlayPersistence.loadHarmanFullscreen(this)) { enabled ->
                AirPlayPersistence.saveHarmanFullscreen(this, enabled)
            }
            toggle(card, brandString(R.string.split_screen_areas), brandString(R.string.split_screen_areas_description),
                SplitScreenSettings.enabled(this)) {
                SplitScreenSettings.setEnabled(this, it)
                markReconnectNeeded()
            }
            toggle(card, brandString(R.string.carplay_rotation), brandString(R.string.carplay_rotation_description),
                CarPlayRotation.enabled(this)) {
                CarPlayRotation.setEnabled(this, it)
                render()
                markReconnectNeeded()
            }
            toggle(card, brandString(R.string.side_panel), brandString(R.string.side_panel_description), SidePanelSettings.enabled(this)) {
                SidePanelSettings.setEnabled(this, it)
                markReconnectNeeded()
            }
            if (CarPlayRotation.enabled(this)) {
                val pictures = CarPlayRotation.Picture.entries
                choice(card, brandString(R.string.carplay_rotation_picture), listOf(
                    brandString(R.string.carplay_rotation_smoother),
                    brandString(R.string.carplay_rotation_sharper),
                ), pictures.indexOf(CarPlayRotation.picture(this)), reconnects = false) {
                    CarPlayRotation.setPicture(this, pictures[it])
                    markReconnectNeeded()
                }
            }
        }
        // Opt-in controls that can cost sound or video on some head units.
        filteredSection(content, SettingsSection.ADVANCED_MEDIA,
            brandString(R.string.settings_advanced_media), R.drawable.ic_dp_advanced) { card ->
            if (Build.VERSION.SDK_INT == 32) {
                toggle(card, brandString(R.string.settings_hide_byd_call_popup),
                    brandString(R.string.settings_hide_byd_call_popup_description),
                    AirPlayPersistence.loadHideBydCallPopup(this)) {
                    AirPlayPersistence.saveHideBydCallPopup(this, it)
                }
            }
            toggle(card, brandString(R.string.efficient_video), brandString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility), AirPlayPersistence.loadHevcEnabled(this)) {
                AirPlayPersistence.saveHevcEnabled(this, it)
                markReconnectNeeded()
                render()
            }
            val hevc = AirPlayPersistence.loadHevcEnabled(this)
            val selectedMime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val mtkDecoder = if (hevc && AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)) null
                else selectedMtkDecoderName(selectedMime)
            if (mtkDecoder != null) {
                toggle(card, brandString(R.string.settings_mtk_decoder_tuning),
                    brandString(R.string.settings_mtk_decoder_tuning_description, mtkDecoder),
                    AirPlayPersistence.loadMtkDecoderTuning(this)) {
                    AirPlayPersistence.saveMtkDecoderTuning(this, it)
                    markReconnectNeeded()
                }
            }
            toggle(card, brandString(R.string.smooth_video), brandString(R.string.smooth_video_description),
                AirPlayPersistence.loadSmoothVideo(this), confirmReconnect = true) {
                AirPlayPersistence.saveSmoothVideo(this, it)
                reconnectIfRunning()
            }
            toggle(card, brandString(R.string.settings_direct_video_output),
                brandString(R.string.settings_direct_video_output_description),
                AirPlayPersistence.loadDirectVideoOutput(this), confirmReconnect = true) {
                AirPlayPersistence.saveDirectVideoOutput(this, it)
                reconnectIfRunning()
            }
            toggle(card, brandString(R.string.settings_low_latency_decoder),
                brandString(R.string.settings_low_latency_decoder_description),
                AirPlayPersistence.loadLowLatencyDecoder(this)) {
                AirPlayPersistence.saveLowLatencyDecoder(this, it)
                markReconnectNeeded()
            }
            if (com.shilapi.xcertplay.lincoln.LincolnVehicleVideo.eligible()) {
                toggle(card, brandString(R.string.settings_lincoln_fast_video),
                    brandString(R.string.settings_lincoln_fast_video_description),
                    com.shilapi.xcertplay.lincoln.LincolnVehicleVideo.independentOutput(this)) {
                    com.shilapi.xcertplay.lincoln.LincolnVehicleVideo.setIndependentOutput(this, it)
                    markReconnectNeeded()
                }
            }
            toggle(card, brandString(R.string.call_echo_cancellation), brandString(R.string.call_echo_cancellation_description),
                AirPlayPersistence.loadCallEchoCancellation(this)) {
                AirPlayPersistence.saveCallEchoCancellation(this, it)
                markReconnectNeeded()
            }
            toggle(card, brandString(R.string.call_voice_filter), brandString(R.string.call_voice_filter_description),
                AirPlayPersistence.loadCallVoiceFilter(this)) {
                AirPlayPersistence.saveCallVoiceFilter(this, it)
                markReconnectNeeded()
            }
            toggle(card, brandString(R.string.phone_audio_route), brandString(R.string.phone_audio_route_description),
                BydOutputSettings.phoneAudioRoute(this)) {
                BydOutputSettings.setPhoneAudioRoute(this, it)
                PhoneAudioRoute.settingsChanged(this)
            }
            toggle(card, brandString(R.string.phone_audio_route_apps), brandString(R.string.phone_audio_route_apps_description),
                BydOutputSettings.phoneAudioRouteApps(this)) {
                BydOutputSettings.setPhoneAudioRouteApps(this, it)
                PhoneAudioRoute.settingsChanged(this)
                // The watcher for other apps' calls needs DiPlay running outside a CarPlay session too, which
                // the accessibility service provides.
                if (it && !WheelKeyService.connected()) {
                    Thread({ WheelKeyService.enableOverAdb(this) }, "diplay-phone-audio-enable").start()
                }
            }
            audioFocusControls(card)
            if (resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                toggle(card, brandString(R.string.advanced_audio_channel_mapping),
                    brandString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                    AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                    markReconnectNeeded()
                }
            }
            toggle(card, brandString(R.string.main_buffered_audio), brandString(R.string.main_buffered_audio_description),
                AirPlayPersistence.loadMainBufferedAudio(this), confirmReconnect = true) {
                AirPlayPersistence.saveMainBufferedAudio(this, it)
                reconnectIfRunning()
            }
            toggle(card, brandString(R.string.settings_speech_without_low_rates), brandString(R.string.settings_speech_without_low_rates_description),
                AirPlayPersistence.loadSpeechWithout8And16Khz(this), confirmReconnect = true) {
                AirPlayPersistence.saveSpeechWithout8And16Khz(this, it)
                reconnectIfRunning()
            }
            toggle(card, brandString(R.string.settings_microphone_without_48khz),
                brandString(R.string.settings_microphone_without_48khz_description),
                AirPlayPersistence.loadMicrophoneWithout48Khz(this), confirmReconnect = true) {
                AirPlayPersistence.saveMicrophoneWithout48Khz(this, it)
                reconnectIfRunning()
            }
            toggle(card, brandString(R.string.settings_music_clock_trim), brandString(R.string.settings_music_clock_trim_description),
                AirPlayPersistence.loadMusicClockTrim(this), confirmReconnect = true) {
                AirPlayPersistence.saveMusicClockTrim(this, it)
                reconnectIfRunning()
            }
            toggle(card, brandString(R.string.settings_car_bluetooth_audio), brandString(R.string.settings_car_bluetooth_audio_description),
                AirPlayPersistence.loadCarBluetoothAudio(this), confirmReconnect = true) {
                AirPlayPersistence.saveCarBluetoothAudio(this, it)
                reconnectIfRunning()
            }
        }
        filteredSection(content, SettingsSection.CAR_BUTTON,
            brandString(R.string.car_button_in_carplay), R.drawable.ic_dp_car) { card -> carButtonCard = card; carButtonControls(card) }
        filteredSection(content, SettingsSection.AUDIO_ROUTING,
            brandString(R.string.audio_routing), R.drawable.ic_dp_audio) { card ->
            mediaChannelControl(card)
            navigationChannelControl(card)
            val gainSlider = overlaySliderRow(
                brandString(R.string.settings_navigation_attenuation),
                (com.shilapi.xcertplay.media.NavigationAudioGain.MIN_DB..
                    com.shilapi.xcertplay.media.NavigationAudioGain.MAX_DB).toList(),
                AirPlayPersistence.loadNavigationAudioAttenuationDb(this),
            ) { db -> brandString(R.string.settings_navigation_attenuation_value, db) }
            val gainTitle = brandString(R.string.settings_navigation_attenuation)
            gainSlider.slider.contentDescription = gainTitle + ": " +
                brandString(R.string.settings_navigation_attenuation_value,
                    AirPlayPersistence.loadNavigationAudioAttenuationDb(this))
            gainSlider.onSave = { db ->
                AirPlayPersistence.saveNavigationAudioAttenuationDb(this, db)
                CarPlayBackgroundSession.snapshot()?.sink?.setNavigationAttenuationDb(db)
                gainSlider.slider.contentDescription = gainTitle + ": " +
                    brandString(R.string.settings_navigation_attenuation_value, db)
            }
            card.addView(gainSlider)
            card.addView(label(brandString(R.string.settings_navigation_attenuation_description), 14, MUTED).apply {
                setPadding(0, dp(SETTINGS_BLOCK_GAP_DP), 0, 0)
            })
            toggle(card, brandString(R.string.settings_navigation_ducking),
                brandString(R.string.settings_navigation_ducking_description),
                AirPlayPersistence.loadNavigationDuckingMedia(this)) {
                AirPlayPersistence.saveNavigationDuckingMedia(this, it)
                CarPlayBackgroundSession.snapshot()?.sink?.setNavigationDuckingEnabled(it)
                // The tuning rows stay visible either way, so a driver can set them before enabling.
                render()
            }
            val dbSlider = overlaySliderRow(
                brandString(R.string.settings_navigation_duck_amount),
                (com.shilapi.xcertplay.media.NavigationDuckSettings.MAX_DB downTo
                    com.shilapi.xcertplay.media.NavigationDuckSettings.MIN_DB).toList(),
                AirPlayPersistence.loadNavigationDuckDb(this),
            ) { db -> brandString(R.string.settings_navigation_duck_amount_value, -db) }
            dbSlider.slider.contentDescription = brandString(
                R.string.settings_navigation_duck_amount,
            ) + ": " + brandString(
                R.string.settings_navigation_duck_amount_value, -AirPlayPersistence.loadNavigationDuckDb(this),
            )
            dbSlider.onSave = { db ->
                AirPlayPersistence.saveNavigationDuckDb(this, db)
                applyNavigationDuckTuning()
                dbSlider.slider.contentDescription = brandString(
                    R.string.settings_navigation_duck_amount_value, -db)
            }
            card.addView(dbSlider)

            val duckSlider = overlaySliderRow(
                brandString(R.string.settings_navigation_duck_fade_out),
                com.shilapi.xcertplay.media.NavigationDuckSettings.RAMP_PRESETS,
                AirPlayPersistence.loadNavigationDuckDownMs(this),
            ) { ms -> brandString(R.string.settings_navigation_duck_ms_value, ms) }
            duckSlider.onSave = { ms ->
                AirPlayPersistence.saveNavigationDuckDownMs(this, ms)
                applyNavigationDuckTuning()
                duckSlider.slider.contentDescription =
                    brandString(R.string.settings_navigation_duck_ms_value, ms)
            }
            card.addView(duckSlider)

            val restoreSlider = overlaySliderRow(
                brandString(R.string.settings_navigation_duck_fade_in),
                com.shilapi.xcertplay.media.NavigationDuckSettings.RAMP_PRESETS,
                AirPlayPersistence.loadNavigationDuckUpMs(this),
            ) { ms -> brandString(R.string.settings_navigation_duck_ms_value, ms) }
            restoreSlider.onSave = { ms ->
                AirPlayPersistence.saveNavigationDuckUpMs(this, ms)
                applyNavigationDuckTuning()
                restoreSlider.slider.contentDescription =
                    brandString(R.string.settings_navigation_duck_ms_value, ms)
            }
            card.addView(restoreSlider)

            val holdSlider = overlaySliderRow(
                brandString(R.string.settings_navigation_duck_hold),
                com.shilapi.xcertplay.media.NavigationDuckSettings.HOLD_PRESETS,
                AirPlayPersistence.loadNavigationDuckHoldMs(this),
            ) { ms -> brandString(R.string.settings_navigation_duck_ms_value, ms) }
            holdSlider.onSave = { ms ->
                AirPlayPersistence.saveNavigationDuckHoldMs(this, ms)
                applyNavigationDuckTuning()
                holdSlider.slider.contentDescription =
                    brandString(R.string.settings_navigation_duck_ms_value, ms)
            }
            card.addView(holdSlider)
            card.addView(label(brandString(R.string.settings_navigation_duck_tuning_description), 14, MUTED).apply {
                setPadding(0, dp(SETTINGS_BLOCK_GAP_DP), 0, 0)
            })
            musicBufferChoice(card, wireless = true)
            musicBufferChoice(card, wireless = false)
            toggle(card, brandString(R.string.settings_call_speakerphone), brandString(R.string.settings_call_speakerphone_description),
                AirPlayPersistence.loadCallSpeakerphone(this)) { AirPlayPersistence.saveCallSpeakerphone(this, it) }
        }
        filteredSection(content, SettingsSection.NAVIGATION_WHEEL,
            brandString(R.string.settings_navigation_wheel_volume), R.drawable.ic_dp_audio) { card ->
            navigationWheelControls(card)
        }
        // Ambient lighting drives BYD's lamp interface only, so it is hidden on a Lincoln/Ford unit.
        if (!com.shilapi.xcertplay.lincoln.LincolnVehicle.eligible(this)) {
            filteredSection(content, SettingsSection.AMBIENT_LIGHTING, brandString(R.string.settings_ambient_title)) { card ->
                card.addView(button(ambientConfigurationLabel(AmbientMusicSettings.load(this)), false) {
                    showAmbientConfiguration()
                }, matchButton(0, 60))
            }
        }
        filteredSection(content, SettingsSection.LOCATION,
            brandString(R.string.location), R.drawable.ic_dp_navigation) { card ->
            toggle(card, brandString(R.string.report_location_to_iphone),
                "${brandString(R.string.location_reporting_reconnects)} ${brandString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th)}",
                AirPlayPersistence.loadLocationReportingEnabled(this), confirmReconnect = true, save = ::onLocationReportingChanged)
        }
        // Cluster video does not require a BYD navigation broadcast receiver.
        filteredSection(content, SettingsSection.CLUSTER_MAP,
            brandString(R.string.carplay_map_on_instrument_cluster_experimental), R.drawable.ic_dp_dashboard) { card ->
            // DiLink 3 makes its own dashboard display; either DiLink 4 route stops that, so offer them only to
            // turn off, as the setup guide does (#722).
            val diLink3 = DiLinkGeneration.current(this) == DiLinkGeneration.DILINK_3
            if (diLink3) {
                if (AirPlayPersistence.loadDiLink4ClusterRouteEnabled(this)) clusterRouteConflict(card)
            } else {
                reconnectingToggle(card, brandString(R.string.adb_cluster_activity_mode),
                    brandString(R.string.adb_cluster_activity_description), AirPlayPersistence.loadAdbClusterEnabled(this)) {
                    AirPlayPersistence.saveAdbClusterEnabled(this, it)
                    ClusterActivityOutput.stopForSettings()
                    render()
                }
                reconnectingToggle(card, brandString(R.string.platform21_cluster_mode),
                    brandString(R.string.platform21_cluster_description), AirPlayPersistence.loadLegacyClusterEnabled(this)) {
                    AirPlayPersistence.saveLegacyClusterEnabled(this, it)
                    ClusterActivityOutput.stopForSettings()
                    render()
                }
            }
            val adbCluster = AdbClusterRouter.enabled(this)
            if (adbCluster) {
                card.addView(button(brandString(R.string.adb_cluster_authorize), false) { authorizeClusterRouting() }, matchButton(10, 56))
                card.addView(button(brandString(R.string.adb_cluster_open), false) { ClusterActivityOutput.retry(force = true) }, matchButton(10, 56))
            }
            if (adbCluster && !AirPlayPersistence.loadLegacyClusterEnabled(this) && com.shilapi.xcertplay.hud.BydOemClusterNavi.applicable(this)) {
                val holds = com.shilapi.xcertplay.hud.BydOemClusterHold.entries
                card.addView(settingsNote(brandString(R.string.oem_cluster_map_description), 14, MUTED))
                choice(card, brandString(R.string.oem_cluster_map), holds.map { it.localizedLabel(this) },
                    holds.indexOf(BydOutputSettings.oemClusterHold(this))) { index ->
                    BydOutputSettings.setOemClusterHold(this, holds[index])
                    ClusterActivityOutput.stopForSettings()
                    markReconnectNeeded()
                }
            }
            val clusterDisplay = ClusterMapPresentation.findDisplay(this)
            val clusterSize = clusterDisplay?.let { ClusterMapPresentation.sizeOf(it) }
            val diLink4 = adbCluster || (clusterDisplay != null && clusterSize != null &&
                DiLink4ClusterDisplay.matches(clusterDisplay.name, clusterSize.x, clusterSize.y))
            val clusterMapEnabled = AirPlayPersistence.loadClusterMapEnabled(this)
            reconnectingToggle(card, brandString(R.string.carplay_map_on_instrument_cluster_experimental),
                if (clusterDisplay != null || adbCluster) brandString(R.string.shows_the_iphone_s_cluster_map_on_the_instrument_cluster_c)
                else brandString(R.string.shows_the_iphone_s_cluster_map_virtual_stream_description),
                clusterMapEnabled) {
                AirPlayPersistence.saveClusterMapEnabled(this, it)
                render()
            }
            if (clusterMapEnabled) {
                clusterSpeedometerControls(card)
                clusterMusicControls(card)
                toggle(card, brandString(R.string.center_map_card),
                    if (clusterDisplay != null || adbCluster) brandString(R.string.center_map_card_description)
                    else brandString(R.string.center_map_card_virtual_description),
                    AirPlayPersistence.loadCenterMapOverlay(this)) {
                    AirPlayPersistence.saveCenterMapOverlay(this, it)
                    if (it && !CenterMapOverlay.permitted(this)) openOverlayPermission()
                    render()
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    toggle(card, brandString(R.string.center_map_follows_dashboard), brandString(R.string.center_map_follows_dashboard_description),
                        AirPlayPersistence.loadCenterMapFollowsDashboard(this)) {
                        AirPlayPersistence.saveCenterMapFollowsDashboard(this, it)
                    }
                    toggle(card, brandString(R.string.center_map_auto_hide), brandString(R.string.center_map_auto_hide_description),
                        AirPlayPersistence.loadCenterMapAutoHide(this)) {
                        AirPlayPersistence.saveCenterMapAutoHide(this, it)
                    }
                }
                toggle(card, brandString(R.string.launcher_map_sharing), brandString(R.string.launcher_map_sharing_description),
                    AirPlayPersistence.loadLauncherMapSharing(this)) {
                    AirPlayPersistence.saveLauncherMapSharing(this, it)
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    val overlay = CenterMapOverlay.permitted(this)
                    card.addView(label(if (overlay) brandString(R.string.center_map_overlay_allowed)
                        else brandString(R.string.center_map_overlay_missing, packageName), 14, if (overlay) MUTED else WARNING))
                    val usage = HomeScreenMonitor.hasAccess(this)
                    card.addView(label(if (usage) brandString(R.string.center_map_auto_hide_active)
                        else brandString(R.string.center_map_auto_hide_needed), 14, if (usage) MUTED else WARNING))
                    if (!usage) {
                        card.addView(button(brandString(R.string.btn_auto_apply_permissions), false) { autoApplyPermissions() }, matchButton(8, 54))
                    }
                }
                if (clusterDisplay != null || adbCluster) {
                    if (DiLink51ClusterLayout.supported() && !adbCluster) {
                        val automatic = DiLink51ClusterLayout.automatic(this)
                        toggle(card, brandString(R.string.follow_instrument_theme_and_map_card),
                            brandString(R.string.show_the_side_map_only_when_its_card_is_open_and_switch_to), automatic) {
                            DiLink51ClusterLayout.saveAutomatic(this, it)
                            render()
                            markReconnectNeeded()
                        }
                        val allowed = DiLink51ClusterMonitor.hasAccess(this)
                        card.addView(label(if (allowed) brandString(R.string.usage_access_enabled)
                            else brandString(R.string.usage_access_setup_needed_for_automatic_mode), 14, if (allowed) MUTED else WARNING))
                        card.addView(actionButton(brandString(R.string.automatic_map_setup_adb), false) { showClusterAccessSetup() }, matchButton(10, 56))
                        if (!automatic) {
                            val themes = DiLink51ClusterLayout.Theme.entries
                            choice(card, brandString(R.string.instrument_theme), themes.map { it.localizedLabel(this) }, themes.indexOf(DiLink51ClusterLayout.theme(this))) {
                                DiLink51ClusterLayout.saveTheme(this, themes[it])
                                markReconnectNeeded()
                            }
                            card.addView(label(brandString(R.string.manual_mode_match_the_cluster_theme_here_the_map_cannot_fo), 14, MUTED))
                        }
                        val contrasts = DiLink51ClusterLayout.Contrast.entries
                        choice(card, brandString(R.string.instrument_contrast), contrasts.map { it.localizedLabel(this) }, contrasts.indexOf(DiLink51ClusterLayout.contrast(this))) {
                            DiLink51ClusterLayout.saveContrast(this, contrasts[it])
                            markReconnectNeeded()
                        }
                    } else {
                        if (diLink4) clusterSafeAreaControls(card)
                        val sizes = CarPlayClusterDisplay.scalePresets
                        val contents = CarPlayClusterDisplay.Content.entries
                        val content = AirPlayPersistence.loadClusterContent(this)
                        val customCard = CarPlayClusterDisplay.usesCustomTurnCard(content)
                        val officialCardOnly = content == CarPlayClusterDisplay.Content.TURN_CARD
                        choice(card, brandString(R.string.dashboard_shows), listOf(
                            brandString(R.string.dashboard_content_map),
                            brandString(R.string.dashboard_content_turn_card),
                            brandString(R.string.dashboard_content_map_with_turn_card),
                            brandString(R.string.dashboard_content_map_with_custom_turn_card),
                        ), contents.indexOf(content).coerceAtLeast(0), reconnects = false) {
                            val next = contents[it]
                            val request = ++clusterContentRequestVersion
                            AirPlayPersistence.saveClusterContent(this, next)
                            render()
                            if (content.url != next.url) {
                                // The iPhone's own contents switch live. DiPlay's card over the map, and the
                                // DiLink 5.1 layout (always the map), are set up at connection, so they reconnect.
                                val controller = CarPlayBackgroundSession.snapshot()?.controller
                                if (customCard || CarPlayClusterDisplay.usesCustomTurnCard(next) ||
                                    DiLink51ClusterLayout.supported() || controller == null) {
                                    markReconnectNeeded()
                                } else controller.showDashboardContent(next.url) { applied ->
                                    runOnUiThread {
                                        if (!applied && request == clusterContentRequestVersion &&
                                            !isFinishing && !isDestroyed &&
                                            AirPlayPersistence.loadClusterContent(this) == next &&
                                            CarPlayBackgroundSession.snapshot()?.controller === controller) {
                                            markReconnectNeeded()
                                        }
                                    }
                                }
                            }
                        }
                        if (customCard) {
                            card.addView(overlaySliderRow(
                                brandString(R.string.turn_card_overlay_size),
                                ClusterTurnCardOverlay.sizePercents,
                                AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(this),
                            ) { it -> brandString(R.string.turn_card_overlay_size_option, it) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOverlaySizePercent(this, v) } })
                            card.addView(overlaySliderRow(
                                brandString(R.string.turn_card_overlay_opacity),
                                ClusterTurnCardOverlay.opacityPercents,
                                AirPlayPersistence.loadClusterTurnCardOpacityPercent(this),
                            ) { it -> brandString(R.string.turn_card_overlay_opacity_option, it) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOpacityPercent(this, v) } })
                            card.addView(overlaySliderRow(
                                brandString(R.string.turn_card_overlay_horizontal),
                                ClusterTurnCardOverlay.xPercents,
                                AirPlayPersistence.loadClusterTurnCardOverlayXPercent(this),
                            ) { it -> overlayOffsetLabel(it, brandString(R.string.marker_left), brandString(R.string.marker_right), 50) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOverlayXPercent(this, v) } })
                            card.addView(overlaySliderRow(
                                brandString(R.string.turn_card_overlay_vertical),
                                ClusterTurnCardOverlay.yPercents,
                                AirPlayPersistence.loadClusterTurnCardOverlayYPercent(this),
                            ) { it -> overlayOffsetLabel(it, brandString(R.string.marker_up), brandString(R.string.marker_down), 40) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOverlayYPercent(this, v) } })
                            card.addView(button(brandString(R.string.reset_turn_card_overlay), false) {
                                AirPlayPersistence.saveClusterTurnCardOverlayXPercent(this, ClusterTurnCardOverlay.DEFAULT_X_PERCENT)
                                AirPlayPersistence.saveClusterTurnCardOverlayYPercent(this, ClusterTurnCardOverlay.DEFAULT_Y_PERCENT)
                                render()
                            }, matchButton(10, 56))
                            card.addView(label(brandString(R.string.turn_card_overlay_note), 14, MUTED))
                        }
                        val turnCard = officialCardOnly
                        if (!diLink4) {
                            choice(card, brandString(if (turnCard) R.string.turn_card_size else R.string.cluster_map_size),
                                listOf(brandString(R.string.cluster_size_standard), brandString(R.string.cluster_size_larger), brandString(R.string.cluster_size_largest), brandString(R.string.cluster_size_smallest)),
                                sizes.indexOf(AirPlayPersistence.loadClusterMapScalePercent(this)).coerceAtLeast(0)) {
                                AirPlayPersistence.saveClusterMapScalePercent(this, sizes[it])
                            }
                        }
                        if (!diLink4 || AirPlayPersistence.loadClusterSafeAreaRect(this) == null) {
                            card.addView(overlaySliderRow(
                                brandString(if (turnCard) R.string.turn_card_horizontal else R.string.car_marker_horizontal),
                                CarPlayClusterDisplay.markerXPercents,
                                AirPlayPersistence.loadClusterMarkerXPercent(this),
                            ) { it -> overlayOffsetLabel(it, brandString(R.string.marker_left), brandString(R.string.marker_right), 50) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterMarkerXPercent(this, v) } }
                                .also { it.onCommit = { markReconnectNeeded() } })
                            card.addView(overlaySliderRow(
                                brandString(if (turnCard) R.string.turn_card_vertical else R.string.car_marker_vertical),
                                CarPlayClusterDisplay.markerYPercents,
                                AirPlayPersistence.loadClusterMarkerYPercent(this),
                            ) { it -> overlayOffsetLabel(it, brandString(R.string.marker_up), brandString(R.string.marker_down), 45) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterMarkerYPercent(this, v) } }
                                .also { it.onCommit = { markReconnectNeeded() } })
                            card.addView(button(brandString(if (turnCard) R.string.reset_turn_card_to_centre else R.string.reset_car_marker_to_centre), false) {
                                AirPlayPersistence.saveClusterMarkerXPercent(this, 50)
                                AirPlayPersistence.saveClusterMarkerYPercent(this, 45)
                                render()
                                markReconnectNeeded()
                            }, matchButton(10, 56))
                            choice(card, brandString(R.string.cluster_small_window_marker), listOf(
                                brandString(R.string.cluster_small_window_off),
                                brandString(R.string.cluster_small_window_on),
                                brandString(R.string.cluster_small_window_auto),
                            ), AirPlayPersistence.loadClusterSmallWindowMode(this)) {
                                AirPlayPersistence.saveClusterSmallWindowMode(this, it)
                                render()
                                markReconnectNeeded()
                            }
                            card.addView(label(brandString(R.string.cluster_small_window_marker_description), 14, MUTED).apply {
                                setPadding(0, dp(8), 0, dp(6))
                            })
                            if (AirPlayPersistence.loadClusterSmallWindowMode(this) == 2) {
                                val adbMode = BydNavigationOutputs.clusterNaviMode()
                                if (adbMode != null) {
                                    card.addView(label(brandString(R.string.cluster_small_window_adb_ok, adbMode.label), 14, MUTED)
                                        .apply { setPadding(0, dp(4), 0, dp(4)) })
                                } else if (!DiLink51ClusterMonitor.hasAccess(this)) {
                                    card.addView(label(brandString(R.string.cluster_small_window_access_missing), 14, WARNING)
                                        .apply { setPadding(0, dp(4), 0, dp(4)) })
                                    card.addView(button(brandString(R.string.cluster_small_window_grant_access), false) {
                                        runCatching {
                                            openSystem(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                        }
                                    }, matchButton(6, 56))
                                } else {
                                    card.addView(label(brandString(R.string.cluster_small_window_access_ok), 14, MUTED)
                                        .apply { setPadding(0, dp(4), 0, dp(4)) })
                                }
                            }
                            if (AirPlayPersistence.loadClusterSmallWindowMode(this) != 0) {
                                card.addView(overlaySliderRow(
                                    brandString(R.string.cluster_small_window_horizontal),
                                    CarPlayClusterDisplay.markerXPercents,
                                    AirPlayPersistence.loadClusterSmallWindowMarkerXPercent(this),
                                ) { it -> overlayOffsetLabel(it, brandString(R.string.marker_left), brandString(R.string.marker_right), 50) }
                                    .also { it.onSave = { v -> AirPlayPersistence.saveClusterSmallWindowMarkerXPercent(this, v) } }
                                    .also { it.onCommit = { markReconnectNeeded() } })
                                card.addView(overlaySliderRow(
                                    brandString(R.string.cluster_small_window_vertical),
                                    CarPlayClusterDisplay.markerYPercents,
                                    AirPlayPersistence.loadClusterSmallWindowMarkerYPercent(this),
                                ) { it -> overlayOffsetLabel(it, brandString(R.string.marker_up), brandString(R.string.marker_down), 45) }
                                    .also { it.onSave = { v -> AirPlayPersistence.saveClusterSmallWindowMarkerYPercent(this, v) } }
                                    .also { it.onCommit = { markReconnectNeeded() } })
                                if (AirPlayPersistence.loadClusterContent(this) ==
                                    com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD) {
                                    choice(card, brandString(R.string.cluster_small_window_card_theme), listOf(
                                        brandString(R.string.cluster_small_window_card_theme_follow),
                                        brandString(R.string.turn_card_theme_day),
                                        brandString(R.string.turn_card_theme_night),
                                    ), AirPlayPersistence.loadClusterSmallWindowCardTheme(this), reconnects = false) {
                                        AirPlayPersistence.saveClusterSmallWindowCardTheme(this, it)
                                    }
                                    val smallWindowPlacementPreview = ClusterCardPlacementPreview(this, ClusterCardPlacementPreview.Mode.SMALL).also {
                                        card.addView(it, LinearLayout.LayoutParams(-1, dp(240)))
                                    }
                                    card.addView(overlaySliderRow(
                                        brandString(R.string.cluster_small_window_card_size),
                                        ClusterTurnCardOverlay.sizePercents,
                                        AirPlayPersistence.loadClusterSmallWindowCardSizePercent(this),
                                    ) { it -> brandString(R.string.turn_card_overlay_size_option, it) }
                                        .also { it2 -> it2.onSave = { v -> AirPlayPersistence.saveClusterSmallWindowCardSizePercent(this, v); smallWindowPlacementPreview.invalidate() } })
                                    card.addView(overlaySliderRow(
                                        brandString(R.string.cluster_small_window_card_horizontal),
                                        ClusterTurnCardOverlay.smallWindowXPercents,
                                        AirPlayPersistence.loadClusterSmallWindowCardXPercent(this),
                                    ) { it -> overlayOffsetLabel(it, brandString(R.string.marker_left), brandString(R.string.marker_right), 80) }
                                        .also { it2 -> it2.onSave = { v -> AirPlayPersistence.saveClusterSmallWindowCardXPercent(this, v); smallWindowPlacementPreview.invalidate() } })
                                    card.addView(overlaySliderRow(
                                        brandString(R.string.cluster_small_window_card_vertical),
                                        ClusterTurnCardOverlay.smallWindowYPercents,
                                        AirPlayPersistence.loadClusterSmallWindowCardYPercent(this),
                                    ) { it -> overlayOffsetLabel(it, brandString(R.string.marker_up), brandString(R.string.marker_down), 25) }
                                        .also { it2 -> it2.onSave = { v -> AirPlayPersistence.saveClusterSmallWindowCardYPercent(this, v); smallWindowPlacementPreview.invalidate() } })
                                    card.addView(overlaySliderRow(
                                        brandString(R.string.cluster_small_window_card_opacity),
                                        ClusterTurnCardOverlay.opacityPercents,
                                        AirPlayPersistence.loadClusterSmallWindowCardOpacityPercent(this),
                                    ) { it -> brandString(R.string.turn_card_overlay_opacity_option, it) }
                                        .also { it2 -> it2.onSave = { v -> AirPlayPersistence.saveClusterSmallWindowCardOpacityPercent(this, v) } })
                                }
                                card.addView(label(brandString(R.string.cluster_small_window_hint), 14, MUTED).apply { setPadding(0, dp(10), 0, 0) })
                            }
                        }
                        if (!diLink4) {
                            toggle(card, brandString(R.string.dashboard_map_only_in_small_and_full_navi),
                                brandString(R.string.dashboard_map_only_in_small_and_full_navi_description),
                                BydOutputSettings.clusterStreamPause(this)) {
                                BydOutputSettings.setClusterStreamPause(this, it)
                                if (it) checkAdbState(mayAsk = true)
                            }
                        }
                    }
                }
            }
        }
        // FCE head units show this card for their cluster's navigation view, which has its own opt-in.
        val bydNavigation = BydOutputSettings.available(this)
        if (bydNavigation || BydOutputSettings.fceClusterAvailable()) filteredSection(content, SettingsSection.BYD_NAVIGATION,
            brandString(R.string.byd_navigation), R.drawable.ic_dp_navigation) { card ->
            toggle(card, brandString(R.string.navigation_on_hud_and_instrument_cluster),
                brandString(R.string.show_phone_navigation_arrows_distance_and_street_names_on),
                com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
            if (BydOutputSettings.fceClusterAvailable()) {
                toggle(card, brandString(R.string.settings_fce_cluster_guidance),
                    brandString(R.string.settings_fce_cluster_guidance_description),
                    BydOutputSettings.fceCluster(this)) { BydOutputSettings.setFceCluster(this, it) }
            }
            if (BydOutputSettings.standaloneHudAvailable(this)) {
                toggle(card, brandString(R.string.song_on_hud), brandString(R.string.song_on_hud_description),
                    BydOutputSettings.hudSong(this)) { BydOutputSettings.setHudSong(this, it) }
            }
            if (bydNavigation) clusterSongSwitch(card)
        }
        // This fork targets Lincoln/Ford, and the same settings must remain reachable on a phone
        // used to configure and verify it. Hardware/package detection only describes the current
        // Android device; using it as a visibility gate hid the whole card on the Huawei test phone.
        filteredSection(content, SettingsSection.LINCOLN_CLUSTER,
            brandString(R.string.settings_lincoln_cluster), R.drawable.ic_dp_navigation, build = ::lincolnClusterSection)
        filteredSection(content, SettingsSection.PERMISSIONS_AND_HELP,
            brandString(R.string.permissions_and_connection_help), R.drawable.ic_dp_permissions) { card ->
            card.addView(label(brandString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
            card.addView(button(brandString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button(brandString(R.string.bluetooth_settings), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10, 60))
            card.addView(button(brandString(R.string.wireless_connection_help), false) { wirelessHelp() }, matchButton(10, 60))
        }
        languageSettings(content)
    }

    private fun about(content: LinearLayout) {
        val explanation=brandString(R.string.an_independent_carplay_receiver_for_android_head_units_wir)
        val title = brandString(R.string.about_diplay)
        content.addView(column().apply {
            addView(label(title, UiTypography.PAGE_SP, TEXT, true).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            })
            addView(label(version(), UiTypography.BODY_SP, MUTED).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        val maintenance=card()
        maintenance.addView(updateRow())
        toggle(maintenance,brandString(R.string.settings_background_update_checks),
            brandString(R.string.ui_glass_background_hint),UpdateAvailability.backgroundChecksEnabled(this)) {
            UpdateAvailability.saveBackgroundChecksEnabled(this,it)
        }
        maintenance.addView(destinationRow(brandString(R.string.settings_update_storage),brandString(R.string.settings_update_storage_hint),R.drawable.ic_dp_storage) { showUpdateStorage() })
        content.addView(maintenance,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(SETTINGS_BLOCK_GAP_DP) })
        content.addView(card().apply {
            addView(destinationRow(brandString(R.string.backup_open),brandString(R.string.backup_open_description),R.drawable.ic_dp_connection) { openBackup() })
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(SETTINGS_BLOCK_GAP_DP) })
        content.addView(card().apply {
            addView(destinationRow(brandString(R.string.made_possible_by_open_source), "", R.drawable.ic_dp_about) {
                appDialogBuilder().setTitle(brandString(R.string.made_possible_by_open_source))
                    .setMessage(explanation + "\n\n" +
                        brandString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay))
                    .setPositiveButton(android.R.string.ok, null).show()
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    private fun destinationRow(title: String,summary: String,icon: Int,click: () -> Unit): SettingButton = button(title,false,click).apply {
        rowPresentation=true;rowTitle=title
        rowIconResource=icon
        if (summary.isNotBlank()) {
            rowDetails=summary
        }
        textSize=UiTypography.ACTION_SP.toFloat()
        minHeight=dp(FidelitySettingsMetrics.SETTING_ROW_DP)
        setPadding(0,0,0,0)
    }

    private fun showUpdateStorage() {
        if (updateStorageDialog?.isShowing == true) return
        val app = applicationContext
        val dialog = appDialogBuilder().setTitle(brandString(R.string.settings_update_storage))
            .setMessage(brandString(R.string.settings_update_storage_scanning))
            .setPositiveButton(brandString(R.string.settings_update_storage_clean), null)
            .setNegativeButton(android.R.string.cancel, null).create()
        updateStorageDialog = dialog
        dialog.setOnDismissListener { if (updateStorageDialog === dialog) updateStorageDialog = null }
        dialog.show()
        val clean = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        clean.isEnabled = false
        fun showResult(usage: UpdateStorage.Usage, cleaned: UpdateStorage.Cleaned? = null) {
            val size = { bytes: Long -> android.text.format.Formatter.formatFileSize(this, bytes) }
            val summary = brandString(R.string.settings_update_storage_usage,
                usage.apkCount, size(usage.apkBytes), size(usage.cacheBytes))
            val result = cleaned?.let { brandString(R.string.settings_update_storage_result, it.files, size(it.bytes)) + "\n\n" }.orEmpty()
            dialog.setMessage(result + summary + "\n\n" + brandString(R.string.settings_update_storage_hint) +
                (if (usage.protectedCount > 0) "\n\n" + brandString(R.string.settings_update_storage_protected, usage.protectedCount) else "") +
                (if (usage.problems > 0) "\n\n" + brandString(R.string.settings_update_storage_partial) else ""))
            clean.isEnabled = usage.removableCount > 0 || usage.problems > 0
        }
        fun runCleanup() {
            clean.isEnabled = false
            dialog.setMessage(brandString(R.string.settings_update_storage_cleaning))
            updateStorageWorker(Runnable {
                val outcome = runCatching { UpdateStorage.clean(app, manual = true) }
                runOnUiThread {
                    if (isFinishing || isDestroyed || !dialog.isShowing) return@runOnUiThread
                    outcome.fold({ showResult(it.usage, it) }, {
                        dialog.setMessage(brandString(R.string.settings_update_storage_partial))
                        clean.isEnabled = true
                    })
                }
            })
        }
        clean.setOnClickListener {
            if (DiagnosticExportStore.needsStoragePermission(this)) {
                storagePermissionAction = { granted ->
                    if (dialog.isShowing && !isFinishing && !isDestroyed) {
                        if (granted) runCleanup()
                        else dialog.setMessage(brandString(R.string.settings_update_storage_partial))
                    }
                }
                runCatching { reportStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                    .onFailure { storagePermissionAction = null; runCleanup() }
            } else runCleanup()
        }
        updateStorageWorker(Runnable {
            val outcome = runCatching { UpdateStorage.usage(app) }
            runOnUiThread {
                if (isFinishing || isDestroyed || !dialog.isShowing) return@runOnUiThread
                outcome.fold({ showResult(it) }, {
                    dialog.setMessage(brandString(R.string.settings_update_storage_partial))
                })
            }
        })
    }

    private enum class UpdateStage {
        IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, VERIFYING, READY,
        PREPARING_INSTALL, INSTALL_FAILED, FAILED,
    }
    private enum class HistoryStage { IDLE, CHECKING, DOWNLOADING, VERIFYING }

    private fun openBackup(from: SettingsCategory = SettingsCategory.ABOUT) {
        backupOpenedFrom = from
        page = "backup"
        render()
    }

    private fun backup(content: LinearLayout) {
        val preferredPrefix = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
            WirelessHotspotMode.WIFI_P2P -> "p2p"
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "ap"
            WirelessHotspotMode.MANUAL, WirelessHotspotMode.EXISTING_WIFI -> "wlan"
        }
        val addresses = SettingsBackupServer.addressCandidates(preferredPrefix)
        // The same page serves both; opened from Diagnostics, it says what the driver came for.
        val forReport = backupOpenedFrom == SettingsCategory.DIAGNOSTICS
        content.addView(label(brandString(if (forReport) R.string.settings_report_on_phone else R.string.backup_title), 40, TEXT, true))
        var endpoint = backupEndpoint
        if ((backupServer == null || endpoint == null) && addresses.isEmpty()) {
            content.addView(space(SETTINGS_BLOCK_GAP_DP))
            content.addView(label(brandString(R.string.backup_no_network), 16, MUTED))
            return
        }
        if (backupServer == null || endpoint == null) {
            val created = SettingsBackupServer(
                page = backupPageHtml(),
                export = { SettingsBackupCodec.export(this, version()) },
                import = { SettingsBackupCodec.import(this, it) },
                report = { diagnosticReportText(applicationContext).toByteArray(Charsets.UTF_8) },
                codePage = localizedSettingsPage("settings-code.html"),
            )
            created.onCodeEntryClosed = {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && backupServer === created) render()
                }
            }
            created.onIdle = {
                runOnUiThread {
                    if (isFinishing || isDestroyed || backupServer !== created) return@runOnUiThread
                    stopBackupServer()
                    page = "settings"
                    settingsCategory = backupOpenedFrom
                    render()
                }
            }
            created.onImported = { outcome ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    backupSummary = if (outcome.invalid) brandString(R.string.backup_import_invalid)
                    else brandString(R.string.backup_summary, outcome.applied, outcome.skipped)
                    if (!outcome.invalid && outcome.applied > 0) markReconnectNeeded()
                    render()
                }
            }
            endpoint = created.start()
            if (endpoint == null) {
                created.stop()
                content.addView(space(SETTINGS_BLOCK_GAP_DP))
                content.addView(label(brandString(R.string.backup_no_network), 16, MUTED))
                return
            }
            backupServer = created
            backupEndpoint = endpoint
            backupSummary = null
        }
        val active = endpoint
        backupServer?.landing = if (forReport) "#report" else ""
        val qr = ImageView(this).apply {
            val side = dp(240)
            setImageBitmap(SettingsQrCode.encode(active.url(addresses.first()) + if (forReport) "#report" else "", side))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        content.addView(space(SETTINGS_BLOCK_GAP_DP))
        content.addView(card().apply {
            addView(label(brandString(if (forReport) R.string.settings_report_on_phone_hint else R.string.backup_page_hint), 16, MUTED))
            addView(qr, LinearLayout.LayoutParams(dp(240) + dp(24), dp(240) + dp(24)).apply {
                topMargin = dp(SETTINGS_BLOCK_GAP_DP); gravity = Gravity.CENTER_HORIZONTAL
            })
            backupSummary?.let {
                addView(label(it, 15, TEXT).apply { setPadding(0, dp(16), 0, 0) })
            }
            // For a computer, or a phone whose camera will not read the code.
            addView(label(
                if (backupServer?.codeEntryOpen == true) {
                    brandString(R.string.backup_type_address, active.typedAddress(addresses.first()),
                        active.code.chunked(3).joinToString(" "))
                } else brandString(R.string.backup_code_entry_closed),
                16,
                TEXT,
            ).apply { setPadding(0, dp(16), 0, 0) })
            if (addresses.size > 1) {
                addView(label(
                    addresses.drop(1).joinToString("\n") { brandString(R.string.backup_other_addresses, it) },
                    14,
                    MUTED,
                ).apply { setPadding(0, dp(16), 0, 0) })
            }
        })
    }

    private fun stopBackupServer() {
        backupServer?.stop()
        backupServer = null
        backupEndpoint = null
        backupSummary = null
    }

    private fun backupPageHtml(): ByteArray = localizedSettingsPage("settings-backup.html")

    private fun localizedSettingsPage(asset: String): ByteArray {
        val language = if (AppLocale.preference(this) == AppLocale.ENGLISH) "en" else "zh"
        return assets.open(asset).use { it.readBytes() }
            .toString(Charsets.UTF_8)
            .replace("{{DIPLAY_LANGUAGE}}", language)
            .toByteArray(Charsets.UTF_8)
    }

    private fun updateRow(): View {
        renderedUpdateStage = updateStage
        renderedHistoryStage = historyStage
        val container = column().apply { setPadding(0, dp(12), 0, 0) }
        updateRowContainer = container
        updateProgressButton = null
        historyProgressLabel = null
        historyActionButton = null
        updateCheckButton = null
        container.addView(label("", 14, MUTED).apply {
            setPadding(0, 0, 0, dp(SETTINGS_UPDATE_GAP_DP))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also { updateStatusLabel = it })
        when (updateStage) {
            UpdateStage.IDLE, UpdateStage.FAILED, UpdateStage.CHECKING, UpdateStage.UP_TO_DATE ->
                container.addView(button(brandString(R.string.update_check), false) { checkForUpdates() }
                    .also { updateCheckButton = it }, matchButton(0, 60))
            UpdateStage.AVAILABLE -> container.addView(
                button(brandString(R.string.update_download), true) { downloadUpdate() }.apply {
                    isEnabled = historyStage == HistoryStage.IDLE
                },
                matchButton(10, 60))
            UpdateStage.DOWNLOADING -> container.addView(
                button(updateProgress?.let { brandString(R.string.update_downloading, it) }
                    ?: brandString(R.string.update_downloading_unknown), false) { openUpdateProgress() }
                    .also { updateProgressButton = it },
                matchButton(10, 60))
            UpdateStage.VERIFYING -> container.addView(
                button(brandString(R.string.update_verifying), false) { openUpdateProgress() }, matchButton(10, 60))
            UpdateStage.READY -> {
                container.addView(
                    button(brandString(R.string.update_install, updateRelease?.tagName.orEmpty()), true) { installUpdate() },
                    matchButton(10, 60))
                updateSavedPath?.let { container.addView(label(brandString(R.string.update_saved_to, it), 13, MUTED).apply { setPadding(0, dp(10), 0, 0) }) }
            }
            UpdateStage.PREPARING_INSTALL -> container.addView(
                button(brandString(R.string.update_preparing_install), false) { openUpdateProgress() }.apply {
                    isEnabled = false
                }, matchButton(10, 60))
            UpdateStage.INSTALL_FAILED -> container.addView(
                button(brandString(R.string.update_retry_install), true) { installUpdate() }, matchButton(10, 60))
        }
        historyMessage?.let {
            container.addView(label(it, 14, MUTED).apply { setPadding(0, dp(10), 0, 0) })
        }
        when (historyStage) {
            HistoryStage.IDLE -> container.addView(
                button(brandString(R.string.update_history_download), false) { loadHistoryReleases() }.apply {
                    isEnabled = updateStage != UpdateStage.CHECKING && updateStage != UpdateStage.DOWNLOADING &&
                        updateStage != UpdateStage.VERIFYING && updateStage != UpdateStage.PREPARING_INSTALL
                }.also { historyActionButton = it },
                matchButton(10, 56),
            )
            HistoryStage.CHECKING -> container.addView(
                label(brandString(R.string.update_history_checking), 14, MUTED).apply {
                    setPadding(0, dp(10), 0, 0)
                },
            )
            HistoryStage.DOWNLOADING -> container.addView(
                label(brandString(R.string.update_history_downloading, historyProgress ?: 0), 14, MUTED).apply {
                    setPadding(0, dp(10), 0, 0)
                }.also { historyProgressLabel = it },
            )
            HistoryStage.VERIFYING -> container.addView(
                label(brandString(R.string.update_history_verifying), 14, MUTED).apply {
                    setPadding(0, dp(10), 0, 0)
                },
            )
        }
        refreshUpdateLabels()
        presentUpdateActions(container)
        return container
    }

    private fun presentUpdateActions(container: LinearLayout) {
        val actions=(0 until container.childCount).map(container::getChildAt).filterIsInstance<Button>()
        if(!isExpandedSettingsLayout || actions.size!=2)return
        actions.forEach { container.removeView(it) }
        val actionRow=GlassEqualHeightRow(this).apply {
            actions.forEachIndexed { index,action ->
                action.textSize=16f;action.minHeight=dp(GlassChrome.MIN_TARGET_DP)
                if(action===updateCheckButton)action.doOnLayout {
                    action.setTextColor(palette.onAccent)
                    action.background=RippleDrawable(ColorStateList.valueOf(RIPPLE),GlassChrome.surface(ACCENT,ACCENT,resources.displayMetrics.density,action.height/2f,primary=true),null)
                }
                addView(action,LinearLayout.LayoutParams(0,-2,1f).apply { if(index>0)marginStart=dp(FidelitySettingsMetrics.ROW_GAP_DP) })
            }
        }
        val statusIndex = updateStatusLabel?.let(container::indexOfChild)?.takeIf { it >= 0 } ?: -1
        container.addView(actionRow, (statusIndex + 1).coerceAtMost(container.childCount),
            LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(FidelitySettingsMetrics.CONTENT_PAD_DP) })
    }

    private fun checkForUpdates() {
        if (updateStage == UpdateStage.CHECKING || updateStage == UpdateStage.UP_TO_DATE || updateStage == UpdateStage.DOWNLOADING ||
            updateStage == UpdateStage.VERIFYING || updateStage == UpdateStage.PREPARING_INSTALL ||
            historyStage != HistoryStage.IDLE) return
        handler.removeCallbacks(clearUpToDateFeedback)
        if (updateNetworkUnavailable()) {
            updateMessage = brandString(R.string.update_no_network)
            updateStage = UpdateStage.FAILED
            refreshUpdateRow()
            return
        }
        updateStage = UpdateStage.CHECKING
        updateMessage = null
        val generation = ++updateGeneration
        refreshUpdateRow()
        updateCheckWorker(Runnable {
            val outcome = runCatching { latestRelease() }
            runOnUiThread {
                if (generation != updateGeneration || isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    { release ->
                        UpdateAvailability.recordAttempt(this, System.currentTimeMillis())
                        availableUpdate = release
                        if (release == null) {
                            UpdateAvailability.clear(this)
                            updateMessage = null
                            updateStage = UpdateStage.UP_TO_DATE
                            upToDateFeedbackGeneration = generation
                            handler.postDelayed(clearUpToDateFeedback, UPDATE_SUCCESS_FEEDBACK_MILLIS)
                        } else {
                            UpdateAvailability.save(this, release)
                            updateRelease = release
                            updateStage = UpdateStage.AVAILABLE
                        }
                        refreshUpdateRow()
                        if (release != null && page == "settings" && settingsCategory == SettingsCategory.ABOUT) {
                            showUpdatePrompt(release, automatic = false)
                        }
                    },
                    { failure ->
                        Log.w("DiPlay-Update", "update check failed", failure)
                        updateMessage = updateFailureMessage(failure)
                        updateStage = UpdateStage.FAILED
                        refreshUpdateRow()
                    },
                )
            }
        })
    }

    /** Bind only update requests, leaving the CarPlay process network unchanged. */
    private fun openUpdateConnection(url: URL): URLConnection {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.let {
            runCatching { InternetNetworkSelector.select(it) }.getOrNull()
        }
        return network?.openConnection(url) ?: url.openConnection()
    }

    private fun latestRelease(): UpdateRelease? {
        return updateLookup()
    }

    private fun loadHistoryReleases() {
        if (historyStage != HistoryStage.IDLE) return
        if (updateStage == UpdateStage.CHECKING || updateStage == UpdateStage.DOWNLOADING ||
            updateStage == UpdateStage.VERIFYING || updateStage == UpdateStage.PREPARING_INSTALL) return
        if (updateNetworkUnavailable()) {
            historyMessage = brandString(R.string.update_no_network)
            refreshUpdateRow(rebuild = true)
            return
        }
        historyStage = HistoryStage.CHECKING
        historyMessage = null
        refreshUpdateRow(rebuild = true)
        Thread({
            val outcome = runCatching { UpdateReleaseLookup.history(userAgent(), updateConnection) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                historyStage = HistoryStage.IDLE
                outcome.fold(
                    { releases ->
                        if (releases.isEmpty()) {
                            historyMessage = brandString(R.string.update_history_empty)
                            refreshUpdateRow(rebuild = true)
                        } else {
                            refreshUpdateRow(rebuild = true)
                            showHistoryReleasePicker(releases)
                        }
                    },
                    { failure ->
                        Log.w("DiPlay-Update", "update history check failed", failure)
                        historyMessage = updateFailureMessage(failure)
                        refreshUpdateRow(rebuild = true)
                    },
                )
            }
        }, "diplay-history-check").start()
    }

    private fun showHistoryReleasePicker(releases: List<UpdateRelease>) {
        if (!isUpdatePage()) return
        updateHistoryDialog?.dismiss()
        val downloadable = releases.filter { it.tagName.isNotBlank() }
        if (downloadable.isEmpty()) {
            historyMessage = brandString(R.string.update_history_empty)
            refreshUpdateRow(rebuild = true)
            return
        }
        val labels = downloadable.map { release ->
            if (release.apkName.isBlank()) release.tagName else "${release.tagName}  ·  ${release.apkName}"
        }.toTypedArray()
        updateHistoryDialog = appDialogBuilder()
            .setTitle(brandString(R.string.update_history_title))
            .setItems(labels) { _, index -> confirmHistoryDownload(downloadable[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmHistoryDownload(release: UpdateRelease) {
        if (!isUpdatePage()) return
        updateHistoryDialog?.dismiss()
        val prompt = HistoryReleaseDialog(this, release) { requestHistoryDownload(release) }
        updateHistoryDialog = prompt.dialog
        prompt.show()
    }

    private fun requestHistoryDownload(release: UpdateRelease) {
        if (DiagnosticExportStore.needsStoragePermission(this)) {
            storagePermissionAction = { granted ->
                if (granted) {
                    downloadHistoryRelease(release)
                } else {
                    historyMessage = brandString(R.string.update_history_permission_denied)
                    if (isUpdatePage()) render()
                }
            }
            runCatching { reportStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                .onFailure {
                    storagePermissionAction = null
                    historyMessage = brandString(R.string.update_history_permission_denied)
                    if (isUpdatePage()) render()
                }
        } else {
            downloadHistoryRelease(release)
        }
    }

    private fun downloadHistoryRelease(release: UpdateRelease) {
        if (updateStage == UpdateStage.DOWNLOADING || updateStage == UpdateStage.VERIFYING ||
            updateStage == UpdateStage.PREPARING_INSTALL) return
        releaseHistoryInstaller()
        historyStage = HistoryStage.DOWNLOADING
        historyProgress = null
        historyMessage = null
        val generation = ++historyGeneration
        if (isUpdatePage()) render()
        Thread({
            var attempt: File? = null
            val outcome = runCatching {
                val directory = UpdateStorage.createAttempt(applicationContext, history = true)
                attempt = directory
                val checksumsFile = File(directory, UpdateCatalog.CHECKSUMS_FILE)
                UpdateClient.download(release.checksumsUrl, checksumsFile, { _, _ -> }, updateConnection)
                val apkFile = File(directory, release.apkName)
                UpdateClient.download(release.apkUrl, apkFile, onProgress = { written, total ->
                    val percent = total?.takeIf { it > 0 }?.let { (written * 100 / it).toInt() } ?: return@download
                    if (percent != historyProgress) {
                        historyProgress = percent
                        refreshHistoryUi(generation)
                    }
                }, openConnection = updateConnection)
                historyStage = HistoryStage.VERIFYING
                refreshHistoryUi(generation)
                val expected = UpdateChecksums.parse(checksumsFile.readText())
                val actual = UpdateChecksums.sha256Hex(apkFile)
                if (!UpdateChecksums.matches(expected, release.apkName, actual)) {
                    throw IOException("Checksum mismatch for ${release.apkName}")
                }
                apkFile to UpdateApkExport.copyToSystemDownloads(applicationContext, apkFile)
            }.onFailure { attempt?.let { UpdateStorage.discard(applicationContext, it) } }
            runOnUiThread {
                if (generation != historyGeneration || isFinishing || isDestroyed) {
                    outcome.getOrNull()?.first?.parentFile?.let { UpdateStorage.release(applicationContext, it) }
                    return@runOnUiThread
                }
                historyStage = HistoryStage.IDLE
                outcome.fold(
                    { (file, saved) ->
                        historyMessage = brandString(R.string.update_saved_to, saved.path)
                        if (isUpdatePage()) {
                            historyInstallerFile = file
                            render()
                            showHistoryDownloadComplete(file, saved.path)
                        } else {
                            file.parentFile?.let { UpdateStorage.release(applicationContext, it) }
                        }
                    },
                    { failure ->
                        historyMessage = failure.message ?: brandString(R.string.update_failed)
                        if (isUpdatePage()) render()
                    },
                )
            }
        }, "diplay-history-download").start()
    }

    private fun showHistoryDownloadComplete(file: File, savedPath: String, installFailure: Boolean = false) {
        if (!isUpdatePage() || historyInstallerFile !== file) return
        updateHistoryDialog?.dismiss()
        var openingInstaller = false
        val message = buildString {
            append(brandString(R.string.update_saved_to, savedPath))
            append("\n\n")
            append(brandString(R.string.update_history_downgrade_warning))
            if (installFailure) {
                append("\n\n")
                append(brandString(R.string.update_history_install_failed))
            }
        }
        val dialog = appDialogBuilder()
            .setTitle(brandString(R.string.update_history_saved_title))
            .setMessage(message)
            .setNegativeButton(brandString(R.string.update_history_keep_file), null)
            .setPositiveButton(brandString(R.string.update_history_open_installer)) { _, _ ->
                openingInstaller = true
                installHistoryRelease(file, savedPath)
            }
            .create()
        dialog.setOnDismissListener {
            if (updateHistoryDialog === dialog) updateHistoryDialog = null
            if (!openingInstaller && historyInstallerFile === file) releaseHistoryInstaller()
        }
        updateHistoryDialog = dialog
        dialog.show()
    }

    private fun installHistoryRelease(file: File, savedPath: String) {
        updateInstallWorker(Runnable {
            val protection = runCatching { UpdateStorage.protectInstaller(applicationContext, file) }
            runOnUiThread {
                if (historyInstallerFile !== file || isFinishing || isDestroyed) return@runOnUiThread
                protection.fold(
                    { startHistoryInstaller(file, savedPath) },
                    {
                        Log.w("DiPlay-Update", "history installation protection failed", it)
                        showHistoryDownloadComplete(file, savedPath, installFailure = true)
                    },
                )
            }
        })
    }

    private fun startHistoryInstaller(file: File, savedPath: String) {
        try {
            val uri = updateInstallerUri(file)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val installers = packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY).size
            val launch = if (installers > 1) Intent.createChooser(view, null) else view
            updateInstallerLauncher(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i("DiPlay-Update", "history install started: installers=$installers")
            historyInstallerFile = null
            file.parentFile?.let { UpdateStorage.release(applicationContext, it) }
        } catch (failure: RuntimeException) {
            Log.w("DiPlay-Update", "history install failed to start", failure)
            showHistoryDownloadComplete(file, savedPath, installFailure = true)
        }
    }

    private fun releaseHistoryInstaller() {
        val file = historyInstallerFile ?: return
        historyInstallerFile = null
        file.parentFile?.let { UpdateStorage.release(applicationContext, it) }
    }

    private fun openAvailableUpdate(release: UpdateRelease) {
        if (updateStage == UpdateStage.CHECKING || updateStage == UpdateStage.DOWNLOADING || updateStage == UpdateStage.VERIFYING ||
            updateStage == UpdateStage.READY || updateStage == UpdateStage.PREPARING_INSTALL ||
            updateStage == UpdateStage.INSTALL_FAILED) {
            page = "settings"
            settingsCategory = SettingsCategory.ABOUT
            render()
            if (updateStage != UpdateStage.CHECKING) openUpdateProgress()
            return
        }
        updateRelease = release
        updateMessage = null
        updateStage = UpdateStage.AVAILABLE
        page = "settings"
        settingsCategory = SettingsCategory.ABOUT
        render()
        showUpdatePrompt(release, automatic = false)
    }

    /** The existing foreground tick also picks up results saved by WorkManager. No network I/O. */
    private fun isUpdatePage() = page == "settings" && settingsCategory == SettingsCategory.ABOUT && !projectionIsVisible

    private fun refreshUpdateAvailability() {
        UpdateStorage.start(applicationContext)
        val release = UpdateAvailability.available(this, installedUpdateVersion)
        if (availableUpdate != release) {
            availableUpdate = release
        }
        if (release != null && (updateStage == UpdateStage.IDLE || updateStage == UpdateStage.AVAILABLE)) {
            updateRelease = release
            updateStage = UpdateStage.AVAILABLE
            if (isUpdatePage()) refreshUpdateRow()
        }
        // Offers belong to the update page; background checks never interrupt the home flow.
        if (release != null && isUpdatePage()) {
            handler.removeCallbacks(automaticUpdatePrompt)
            handler.post(automaticUpdatePrompt)
        }
    }

    private fun showUpdatePrompt(release: UpdateRelease? = availableUpdate, automatic: Boolean) {
        if (release == null || !isUpdatePage() || !updatePromptResumed || isFinishing || isDestroyed ||
            updatePrompt?.isShowing == true || historyStage != HistoryStage.IDLE ||
            updateStage == UpdateStage.CHECKING || updateStage == UpdateStage.DOWNLOADING ||
            updateStage == UpdateStage.VERIFYING || updateStage == UpdateStage.READY ||
            updateStage == UpdateStage.PREPARING_INSTALL || updateStage == UpdateStage.INSTALL_FAILED) return
        if (automatic && (!window.decorView.hasWindowFocus() ||
                projectionIsStarting || CarPlayBackgroundSession.hasSession() || !UpdateAvailability.shouldPrompt(this, release))) return
        val prompt = createUpdatePrompt()
        updatePrompt = prompt
        prompt.show(release, UpdatePromptStage.OFFER, null, null)
        UpdateAvailability.recordPrompt(this, release)
    }

    private fun createUpdatePrompt(): UpdatePromptDialog {
        val prompt = UpdatePromptDialog(this,
            onDownload = { displayedRelease ->
                updateRelease = displayedRelease
                updateMessage = null
                downloadUpdate()
            },
            onInstall = { if (updateStage == UpdateStage.READY || updateStage == UpdateStage.INSTALL_FAILED) installUpdate() },
            onHide = { updatePromptForDownload = false },
        )
        prompt.dialog.setOnDismissListener { if (updatePrompt === prompt) updatePrompt = null }
        return prompt
    }

    private fun refreshUpdatePrompt() {
        if (!isUpdatePage() || !updatePromptResumed || isFinishing || isDestroyed) return
        val release = updateRelease ?: return
        val state = when (updateStage) {
            UpdateStage.DOWNLOADING -> UpdatePromptStage.DOWNLOADING
            UpdateStage.VERIFYING -> UpdatePromptStage.VERIFYING
            UpdateStage.READY -> UpdatePromptStage.READY
            UpdateStage.PREPARING_INSTALL -> UpdatePromptStage.PREPARING_INSTALL
            UpdateStage.INSTALL_FAILED -> UpdatePromptStage.INSTALL_FAILED
            UpdateStage.FAILED -> UpdatePromptStage.FAILED
            else -> return
        }
        val prompt = updatePrompt
        if (prompt != null) prompt.update(release, state, updateProgress, updateMessage)
        else if (updatePromptForDownload) {
            createUpdatePrompt().also {
                updatePrompt = it
                it.show(release, state, updateProgress, updateMessage)
            }
        }
    }

    private fun openUpdateProgress() {
        updatePromptForDownload = true
        refreshUpdatePrompt()
    }

    private fun scheduleBackgroundUpdateChecks() {
        runCatching { BackgroundUpdateScheduler.schedule(applicationContext) }
            .onFailure { Log.i("DiPlay-Update", "background update scheduling unavailable", it) }
    }

    private fun downloadUpdate() {
        val requestedRelease = updateRelease ?: return
        if (updateStage == UpdateStage.DOWNLOADING || updateStage == UpdateStage.VERIFYING ||
            updateStage == UpdateStage.PREPARING_INSTALL) return
        if (historyStage == HistoryStage.DOWNLOADING || historyStage == HistoryStage.VERIFYING) return
        lateinit var release: UpdateRelease
        updateStage = UpdateStage.DOWNLOADING
        updateSavedPath = null
        updateSavedUri = null
        updateFile?.parentFile?.let { UpdateStorage.release(applicationContext, it) }
        updateFile = null
        updateProgress = null
        updateMessage = null
        updatePromptForDownload = true
        UpdateAvailability.recordPrompt(this, requestedRelease)
        val generation = ++updateGeneration
        if (isUpdatePage()) render()
        refreshUpdatePrompt()
        updateDownloadWorker(Runnable {
            // Each attempt owns its files, including across activity recreation.
            var attempt: File? = null
            val outcome = runCatching {
                val directory = UpdateStorage.createAttempt(applicationContext, history = false)
                attempt = directory
                // The cached release can be a day old. Download the newest one.
                release = latestRelease() ?: throw IOException(brandString(R.string.update_up_to_date))
                refreshUpdateUi(generation, release)
                val checksumsFile = File(directory, UpdateCatalog.CHECKSUMS_FILE)
                UpdateClient.download(release.checksumsUrl, checksumsFile, { _, _ -> }, updateConnection)
                val apkFile = File(directory, release.apkName)
                UpdateClient.download(release.apkUrl, apkFile, onProgress = { written, total ->
                    val percent = total?.takeIf { it > 0 }?.let { (written * 100 / it).toInt() } ?: return@download
                    if (percent != updateProgress) {
                        updateProgress = percent
                        refreshUpdateUi(generation)
                    }
                }, openConnection = updateConnection)
                updateStage = UpdateStage.VERIFYING
                refreshUpdateUi(generation)
                val expected = UpdateChecksums.parse(checksumsFile.readText())
                val actual = UpdateChecksums.sha256Hex(apkFile)
                if (!UpdateChecksums.matches(expected, release.apkName, actual)) {
                    throw IOException("Checksum mismatch for ${release.apkName}")
                }
                apkFile
            }.onFailure { attempt?.let { UpdateStorage.discard(applicationContext, it) } }
            val saved = outcome.getOrNull()?.let { runCatching { UpdateApkExport.copy(applicationContext, it) }.getOrNull() }
            runOnUiThread {
                if (generation != updateGeneration || isFinishing || isDestroyed) {
                    attempt?.let { UpdateStorage.release(applicationContext, it) }
                    return@runOnUiThread
                }
                outcome.fold(
                    { file ->
                        updateRelease = release
                        updateFile = file
                        updateSavedPath = saved?.path
                        updateSavedUri = saved?.uri
                        updateStage = UpdateStage.READY
                        if (isUpdatePage()) render()
                        refreshUpdatePrompt()
                    },
                    { failure ->
                        Log.w("DiPlay-Update", "update download failed", failure)
                        updateMessage = failure.message ?: brandString(R.string.update_failed)
                        updateStage = UpdateStage.FAILED
                        if (isUpdatePage()) render()
                        refreshUpdatePrompt()
                    },
                )
            }
        })
    }

    private fun installUpdate() {
        // No unknown-sources pre-check: some head units answer its settings page with "Not supported yet".
        // The chosen installer asks for that permission itself.
        if (updateStage != UpdateStage.READY && updateStage != UpdateStage.INSTALL_FAILED) return
        val file = updateFile ?: return
        updateStage = UpdateStage.PREPARING_INSTALL
        updateMessage = null
        updatePromptForDownload = true
        refreshUpdateRow()
        refreshUpdatePrompt()
        installApk(file)
    }

    private fun installApk(file: File) {
        // The public copy lets file managers such as MT Manager open the APK. The private provider URI is the fallback,
        // including when the driver deleted the public copy.
        // The public copy is outside the app's control, so it must still match the verified file. Hashing two
        // APKs takes seconds on a slow head unit, so it runs off the main thread.
        val savedUri = updateSavedUri
        val generation = updateGeneration
        updateInstallWorker(Runnable {
            val publicUri = savedUri?.takeIf { sameContent(it, file) }
            val protection = runCatching { UpdateStorage.protectInstaller(applicationContext, file) }
            runOnUiThread {
                if (generation != updateGeneration || isFinishing || isDestroyed) return@runOnUiThread
                protection.fold({ startInstaller(file, publicUri) }, { failure ->
                    Log.w("DiPlay-Update", "installation protection failed", failure)
                    updateMessage = brandString(R.string.update_install_failed)
                    updateStage = UpdateStage.INSTALL_FAILED
                    refreshUpdateRow()
                    refreshUpdatePrompt()
                })
            }
        })
    }

    private fun startInstaller(file: File, publicUri: Uri?) {
        val uri = publicUri ?: updateInstallerUri(file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Ask only when several installers exist. A lone system installer opens directly, as before.
        val installers = packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY).size
        val launch = if (installers > 1) Intent.createChooser(view, null) else view
        val outcome = "${if (installers > 1) "chooser" else "direct"} uri=${if (publicUri != null) "public" else "provider"} installers=$installers"
        try {
            updateInstallerLauncher(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            updateStage = UpdateStage.READY
            updateMessage = null
            refreshUpdateRow()
            refreshUpdatePrompt()
            Log.i("DiPlay-Update", "install started: $outcome")
            UpdateAvailability.recordInstall(applicationContext, System.currentTimeMillis(), outcome)
        } catch (failure: RuntimeException) {
            Log.w("DiPlay-Update", "install failed to start: $outcome", failure)
            UpdateAvailability.recordInstall(applicationContext, System.currentTimeMillis(), "$outcome failed=${failure.javaClass.simpleName}")
            updateMessage = brandString(R.string.update_install_failed)
            updateStage = UpdateStage.INSTALL_FAILED
            refreshUpdateRow()
            refreshUpdatePrompt()
        }
    }

    private fun sameContent(uri: Uri, verified: File): Boolean = runCatching {
        val copy = contentResolver.openInputStream(uri)?.use { UpdateChecksums.sha256Hex(it) }
        copy != null && copy == UpdateChecksums.sha256Hex(verified)
    }.getOrDefault(false)

    private fun refreshUpdateUi(generation: Int, release: UpdateRelease? = null) {
        runOnUiThread {
            if (generation != updateGeneration || isFinishing || isDestroyed) return@runOnUiThread
            if (release != null) updateRelease = release
            refreshUpdatePrompt()
            refreshUpdateRow()
        }
    }

    private fun refreshHistoryUi(generation: Int) {
        runOnUiThread {
            if (generation != historyGeneration || isFinishing || isDestroyed ||
                !(page == "settings" && settingsCategory == SettingsCategory.ABOUT)) {
                return@runOnUiThread
            }
            refreshUpdateRow()
        }
    }

    /** Checking and percentages keep their controls; other actions replace only this block. */
    private fun refreshUpdateRow(rebuild: Boolean = false) {
        if (page != "settings" || settingsCategory != SettingsCategory.ABOUT) return
        val row = updateRowContainer ?: return
        val sameCheckAction = isUpdateCheckStage(renderedUpdateStage) && isUpdateCheckStage(updateStage)
        if (rebuild || (renderedUpdateStage != updateStage && !sameCheckAction) ||
            renderedHistoryStage != historyStage) {
            val parent = row.parent as? ViewGroup ?: return
            val index = parent.indexOfChild(row)
            val params = row.layoutParams
            parent.removeView(row)
            parent.addView(updateRow(), index, params)
            return
        }
        renderedUpdateStage = updateStage
        refreshUpdateLabels()
    }

    private fun isUpdateCheckStage(stage: UpdateStage?): Boolean = when (stage) {
        UpdateStage.IDLE, UpdateStage.CHECKING, UpdateStage.UP_TO_DATE, UpdateStage.FAILED -> true
        else -> false
    }

    private fun refreshUpdateLabels() {
        updateStatusLabel?.apply {
            text = updateMessage.orEmpty()
            visibility=if(text.isNullOrBlank())View.GONE else View.VISIBLE
            setCompoundDrawablesRelative(null,null,null,null)
        }
        updateCheckButton?.apply {
            text = brandString(when (updateStage) {
                UpdateStage.CHECKING -> R.string.update_checking
                UpdateStage.UP_TO_DATE -> R.string.update_up_to_date
                UpdateStage.FAILED -> R.string.update_retry
                else -> R.string.update_check
            })
            isEnabled = updateStage != UpdateStage.CHECKING && updateStage != UpdateStage.UP_TO_DATE &&
                historyStage == HistoryStage.IDLE
        }
        historyActionButton?.isEnabled = updateStage != UpdateStage.CHECKING &&
            updateStage != UpdateStage.DOWNLOADING && updateStage != UpdateStage.VERIFYING &&
            updateStage != UpdateStage.PREPARING_INSTALL
        updateProgressButton?.text = updateProgress?.let { brandString(R.string.update_downloading, it) }
            ?: brandString(R.string.update_downloading_unknown)
        historyProgressLabel?.text = brandString(R.string.update_history_downloading, historyProgress ?: 0)
    }

    private fun updateNetworkUnavailable(): Boolean = runCatching {
        getSystemService(ConnectivityManager::class.java)?.let(UpdateNetworkStatus::isOffline) ?: false
    }.getOrDefault(false)

    private fun updateFailureMessage(failure: Throwable): String = brandString(
        if (updateNetworkUnavailable()) R.string.update_no_network
        else UpdateNetworkStatus.failureMessage(failure),
    )

    private fun userAgent() = "Lincoln/${version()}"

    // An opted-in connection prepares the hotspot in the controller instead of stopping at this reminder.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            CarHotspotSettings.reportedOff(this, com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this)) &&
            !(CarHotspotSettings.enabled(this) && CarHotspotTethering.permitted(this))

    /**
     * Other head units: starting the hotspot needs only Android's "Modify system settings" access
     * (WRITE_SETTINGS), which the driver allows on the system screen. No ADB is involved.
     */
    private fun systemHotspotSettings(parent: LinearLayout) {
        section(parent, brandString(R.string.settings_car_hotspot_control), R.drawable.ic_dp_permissions) { card ->
            toggle(card, brandString(R.string.auto_car_hotspot_title),
                brandString(R.string.settings_auto_car_hotspot_system_description), CarHotspotSettings.enabled(this)) { enabled ->
                CarHotspotSettings.setEnabled(this, enabled)
                if (!enabled) startupHotspotCancelled = true
                else if (!CarHotspotTethering.permitted(this)) openWriteSettingsAccess()
            }
            val permitted = CarHotspotTethering.permitted(this)
            card.addView(label(brandString(if (permitted) R.string.settings_write_settings_granted
                else R.string.settings_write_settings_missing), 14, if (permitted) READY else MUTED).apply {
                setPadding(0, dp(6), 0, dp(4))
            })
            if (!permitted) {
                card.addView(button(brandString(R.string.settings_write_settings_allow), false) { openWriteSettingsAccess() },
                    matchButton(8, 54))
            }
        }
    }

    private fun openWriteSettingsAccess() =
        openSystem(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))

    private fun bydAdbSettings(parent: LinearLayout) {
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) return
        if (!CarHotspotSetup.isBydHeadUnit(this)) {
            if (CarHotspotTethering.appStartSupported()) {
                Log.i("DiPlay-Hotspot", "BYD head unit not detected: hotspot control through system settings access")
                systemHotspotSettings(parent)
            } else {
                Log.i("DiPlay-Hotspot", "settings hidden: BYD head unit not detected; Android ${Build.VERSION.SDK_INT} does not let an app start tethering")
            }
            return
        }
        if (searchIndexSink != null) {
            // Index discoverable names without starting the asynchronous permission probe.
            searchIndexSink?.addAll(listOf(
                brandString(R.string.byd_adb_features),
                brandString(R.string.auto_car_hotspot_title),
                brandString(R.string.btn_auto_apply_permissions),
            ))
            return
        }
        val controls = column().apply { visibility = View.GONE }
        bydAdbControls = controls
        parent.addView(controls)
        Thread({
            val access = runCatching { CarHotspotSetup.check(applicationContext) }
                .onFailure { Log.w("DiPlay-Hotspot", "settings ADB check failed", it) }
                .getOrDefault(LocalAdb.Access.UNREACHABLE)
            Log.i("DiPlay-Hotspot", "settings eligibility: byd=true adb=$access visible=${CarHotspotSettings.visible(true, access)}")
            runOnUiThread {
                if (bydAdbControls !== controls || isFinishing || isDestroyed) return@runOnUiThread
                if (CarHotspotSettings.visible(true, access)) {
                    controls.visibility = View.VISIBLE
                    renderBydAdbControls(controls, access)
                }
            }
        }, "diplay-hotspot-adb-check").start()
    }

    private fun renderBydAdbControls(controls: LinearLayout, access: LocalAdb.Access) {
        controls.removeAllViews()
        adbSwitches.keys.retainAll(setOf(R.string.open_after_the_car_starts))
        adbStatus = null
        controls.visibility = if (CarHotspotSettings.visible(true, access)) View.VISIBLE else View.GONE
        if (controls.visibility == View.GONE) return
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) {
            controls.visibility = View.GONE
            return
        }
        section(controls, brandString(R.string.byd_adb_features), R.drawable.ic_dp_permissions) { card ->
            if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL) {
                adbToggle(card, R.string.auto_car_hotspot_title, R.string.auto_car_hotspot_description,
                    read = { CarHotspotSettings.enabled(this) },
                    permissions = {
                        buildList {
                            add(CarHotspotSetup.Permission.HOTSPOT)
                            if (AirPlayPersistence.loadAutoStartOnBoot(this@DiPlayActivity)) {
                                add(CarHotspotSetup.Permission.BOOT_LAUNCH)
                            }
                        }
                    }) {
                    CarHotspotSettings.setEnabled(this, it)
                    if (!it) startupHotspotCancelled = true
                }
            }
            adbStatus = label(brandString(if (access == LocalAdb.Access.READY)
                R.string.adb_access_ready else R.string.adb_not_approved), 14, MUTED).also(card::addView)
            val allReady = UsbPermissionSetup.snapshot(this).values.all { it }
            if (!allReady) {
                card.addView(button(brandString(R.string.btn_auto_apply_permissions), false) { autoApplyPermissions() }, matchButton(8, 54))
            } else {
                card.addView(label(brandString(R.string.btn_permissions_ready), 14, READY).apply {
                    setPadding(0, dp(6), 0, dp(4))
                })
            }
        }
    }

    private fun adbToggle(parent: LinearLayout, title: Int, description: Int, read: () -> Boolean,
        needsAdb: () -> Boolean = { true },
        permissions: () -> List<CarHotspotSetup.Permission> = { emptyList() }, save: (Boolean) -> Unit) {
        val control = toggle(parent, brandString(title), brandString(description), read(),
            enabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()) { enabled ->
            if (updatingAdbSwitches || adbSwitchChangePending) return@toggle
            if (enabled && needsAdb()) requestAdbSwitchChange(permissions()) { save(true) }
            else save(enabled)
        }
        adbSwitches[title] = control to read
    }

    private fun requestAdbSwitchChange(permissions: List<CarHotspotSetup.Permission>, save: () -> Unit) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) {
            updateAdbSwitches()
            return
        }
        cancelAutomaticVehicleValidationForUserOperation(
            resumeAfter = automaticVehicleValidationInProgress || automaticVehicleValidationPending,
        )
        val app = applicationContext
        adbSwitchChangePending = true
        updateAdbSwitches()
        adbStatus?.setText(R.string.adb_checking_may_ask)
        if (page == "settings" && bydVehicleAdvancedExpanded) render()
        Thread({
            var access = LocalAdb.Access.UNREACHABLE
            val ready = runCatching {
                access = CarHotspotSetup.grant(app, permissions)
                access == LocalAdb.Access.READY && permissions.all { it.granted(app) }
            }.getOrDefault(false)
            runOnUiThread {
                adbSwitchChangePending = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = if (ready) {
                    if (permissions.isEmpty()) R.string.adb_access_ready else R.string.hotspot_permission_granted
                } else when (access) {
                    LocalAdb.Access.NOT_APPROVED -> R.string.adb_not_approved
                    LocalAdb.Access.UNREACHABLE -> R.string.adb_off
                    LocalAdb.Access.UNSUPPORTED -> R.string.adb_pairing_only
                    else -> R.string.hotspot_permission_failed
                }
                adbStatus?.setText(if (ready) R.string.adb_access_ready else message)
                if (ready) save()
                updateAdbSwitches()
                toast(brandString(message))
                runPendingAutomaticVehicleValidation()
                // Re-enable the vehicle controls disabled while this authorization was outstanding.
                if (page == "settings" && bydVehicleAdvancedExpanded) render()
            }
        }, "diplay-adb-switch").start()
    }

    private fun updateAdbSwitches() {
        updatingAdbSwitches = true
        for ((control, read) in adbSwitches.values) {
            control.isChecked = read()
            control.isEnabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()
        }
        updatingAdbSwitches = false
    }

    private fun vehicleAdbWorkInProgress(): Boolean =
        adbCheckInProgress || vehicleProbeAuthorizationInProgress || vehicleProbeInProgress

    private fun startCarHotspotOnLaunch() {
        if (!startupHotspotEligible()) return
        val app = applicationContext
        Thread({
            val result = CarHotspotTethering.enable(app,
                isCancelled = { startupHotspotCancelled || !startupHotspotEligible() },
                log = { Log.i("DiPlay-Hotspot", it) },
            )
            runOnUiThread {
                if (isFinishing || isDestroyed || result == CarHotspotTethering.Result.CANCELLED) return@runOnUiThread
                hotspotStartupResult = result
                if (page == "home") refreshStatus()
            }
        }, "diplay-hotspot-startup").start()
    }

    private fun startupHotspotEligible(): Boolean =
        CarHotspotSetup.shouldStartOnLaunch(applicationContext, CarPlayBackgroundSession.hasSession())

    // Keep the no-argument entry for the existing dialog regression test and any reflective
    // diagnostics; manual Settings connects use the overload to carry their navigation intent.
    private fun carHotspotOffDialog() = carHotspotOffDialog(returnHomeOnStart = false)

    private fun carHotspotOffDialog(returnHomeOnStart: Boolean) {
        appDialogBuilder().setTitle(brandString(R.string.car_hotspot_is_off))
            .setMessage(brandString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(brandString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(brandString(R.string.connect)) { _, _ ->
                // The driver sees the hotspot on: trust that over Android's report, or this dialog would
                // come back on every tap (#604).
                CarHotspotSettings.setOffReportIgnored(this, true)
                connect(true, returnHomeOnStart)
            }
            .setNegativeButton(brandString(R.string.cancel), null).show()
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun repairBootStart() {
        val dialog = appDialogBuilder()
            .setTitle(brandString(R.string.boot_start_repair))
            .setMessage(brandString(R.string.boot_start_repair_running))
            .setNegativeButton(brandString(R.string.common_cancel), null)
            .show()
        Thread({
            val result = runCatching {
                com.shilapi.xcertplay.hud.BydBootStartRepair.apply(applicationContext, packageName)
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                dialog.dismiss()
                val text = result?.lines?.joinToString("\n")?.let { brandString(R.string.boot_start_repair_result, it) }
                    ?: brandString(R.string.adb_check_failed)
                appDialogBuilder()
                    .setTitle(brandString(R.string.boot_start_repair))
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }, "diplay-boot-repair").start()
    }

    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        if (packageManager.resolveActivity(wifi, 0)?.activityInfo?.packageName == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        openSystem(wifi)
    }

    private fun openSetupGuide(fromSettings: Boolean) {
        setupFromSettings = fromSettings
        setupStep = SetupGuide.STEP_CAR
        page = "setup"
        render()
    }

    private fun closeSetupGuide(connectNow: Boolean = false) {
        SetupGuide.markSeen(this)
        page = if (setupFromSettings) "settings" else "home"
        setupFromSettings = false
        setupStep = SetupGuide.STEP_CAR
        render()
        if (connectNow) connect(AirPlayPersistence.loadWirelessEnabled(this), returnHomeOnStart = true)
    }

    private fun dilinkLabel(generation: DiLinkGeneration) = brandString(when (generation) {
        DiLinkGeneration.DILINK_3 -> R.string.dilink_3
        DiLinkGeneration.DILINK_4 -> R.string.dilink_4
        DiLinkGeneration.DILINK_5 -> R.string.dilink_5
        DiLinkGeneration.UNKNOWN -> R.string.dilink_not_sure
    })

    private fun setupGuide(content: LinearLayout) {
        val step = setupStep.coerceIn(SetupGuide.STEP_CAR, SetupGuide.STEP_DONE)
        content.addView(label(brandString(R.string.setup_step_of, step + 1, SetupGuide.STEP_COUNT), 13, ACCENT, true)
            .apply { letterSpacing = .12f })
        val progress = row().apply { setPadding(0, dp(10), 0, dp(20)) }
        repeat(SetupGuide.STEP_COUNT) { index ->
            progress.addView(View(this).apply {
                background = GradientDrawable().apply {
                    setColor(if (index <= step) ACCENT else BUTTON); cornerRadius = dp(3).toFloat()
                }
            }, LinearLayout.LayoutParams(0, dp(6), 1f).apply { if (index > 0) marginStart = dp(8) })
        }
        content.addView(progress)
        when (step) {
            SetupGuide.STEP_CAR -> setupCarStep(content)
            SetupGuide.STEP_CONNECTION -> setupConnectionStep(content)
            SetupGuide.STEP_IPHONE -> setupIphoneStep(content)
            SetupGuide.STEP_FEATURES -> setupFeaturesStep(content)
            else -> setupDoneStep(content)
        }
        if (step == SetupGuide.STEP_DONE) return
        val footer = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        footer.addView(button(brandString(R.string.setup_skip), false) { closeSetupGuide() },
            LinearLayout.LayoutParams(0, dp(60), 1f))
        footer.addView(space(16), LinearLayout.LayoutParams(dp(16), 1))
        footer.addView(button(brandString(R.string.setup_next), true) {
            if (step == SetupGuide.STEP_CAR && SetupGuide.applyFirstSetupDefaults(this,
                    DiLinkGeneration.confirmed(this) ?: DiLinkGeneration.detect().generation,
                    phoneChosen = DiPlayPreferences.phoneAddress(this) != null)) markReconnectNeeded()
            setupStep = step + 1; render()
        },
            LinearLayout.LayoutParams(0, dp(60), 1f))
        content.addView(footer)
    }

    private fun setupTitle(content: LinearLayout, title: Int, description: String) {
        content.addView(label(brandString(title), 34, TEXT, true))
        content.addView(label(description, 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
    }

    private fun setupCarStep(content: LinearLayout) {
        val detection = DiLinkGeneration.detect()
        val selected = DiLinkGeneration.confirmed(this) ?: detection.generation
        setupTitle(content, R.string.setup_car_title, when (detection.source) {
            DiLinkGeneration.Source.SYSTEM_VERSION -> brandString(R.string.setup_car_detected, dilinkLabel(detection.generation))
            DiLinkGeneration.Source.LIKELY -> brandString(R.string.setup_car_likely, dilinkLabel(detection.generation))
            DiLinkGeneration.Source.NONE -> brandString(R.string.setup_car_unknown)
        })
        val card = card()
        card.addView(label(brandString(R.string.setup_car_choose), 22, TEXT, true))
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row() else column()
        DiLinkGeneration.entries.forEachIndexed { index, generation ->
            val chosen = generation == selected
            choices.addView(button("${if (chosen) "✓  " else ""}${dilinkLabel(generation)}", chosen) {
                DiLinkGeneration.save(this, generation)
                render()
            }, if (wide) LinearLayout.LayoutParams(0, dp(60), 1f).apply {
                topMargin = dp(12); if (index > 0) marginStart = dp(12)
            } else matchButton(12, 60))
        }
        card.addView(choices)
        detection.evidence?.let {
            card.addView(label(brandString(R.string.setup_car_evidence, it), 13, MUTED).apply { setPadding(0, dp(16), 0, 0) })
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    private fun setupConnectionStep(content: LinearLayout) {
        setupTitle(content, R.string.setup_connection_title, brandString(R.string.setup_connection_description))
        val card = card()
        wirelessLinkControls(card)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    /** "Choose iPhone · <name>" once an iPhone is chosen. Before that there is no name to show, only "Choose iPhone". */
    private fun chooseIphoneTitle(): String =
        if (DiPlayPreferences.phoneAddress(this) == null) brandString(R.string.choose_iphone)
        else "${brandString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}"

    private fun setupIphoneStep(content: LinearLayout) {
        setupTitle(content, R.string.setup_iphone_title, brandString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car))
        val card = card()
        val chosen = DiPlayPreferences.phoneAddress(this) != null
        card.addView(label(if (chosen) DiPlayPreferences.phoneName(this) else brandString(R.string.setup_iphone_none), 22,
            if (chosen) READY else TEXT, true))
        card.addView(button(chooseIphoneTitle(), !chosen) {
            choosePhone()
        }, matchButton(16, 60))
        card.addView(button(brandString(R.string.review_app_permissions), false) {
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, matchButton(12, 60))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    /** The warning and the button that turn both DiLink 4 cluster routes off (saving the ADB route clears both). */
    private fun clusterRouteConflict(parent: LinearLayout) {
        parent.addView(label(brandString(R.string.setup_cluster_route_conflict), 16, WARNING))
        parent.addView(button(brandString(R.string.setup_cluster_route_turn_off), true) {
            AirPlayPersistence.saveAdbClusterEnabled(this, false)
            ClusterActivityOutput.stopForSettings()
            markReconnectNeeded()
            render()
        }, matchButton(12, 56))
    }

    private fun setupFeaturesStep(content: LinearLayout) {
        val generation = DiLinkGeneration.current(this)
        setupTitle(content, R.string.setup_features_title, brandString(R.string.setup_features_description,
            dilinkLabel(generation)))
        if (SetupGuide.hasConflictingClusterRoute(generation, AirPlayPersistence.loadDiLink4ClusterRouteEnabled(this))) {
            val warning = card()
            clusterRouteConflict(warning)
            content.addView(warning, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        }
        val card = card()
        SetupGuide.features(generation).forEach { entry ->
            val before = card.childCount
            when (entry.feature) {
                SetupGuide.Feature.AUTO_CONNECT -> toggle(card, brandString(R.string.connect_when_diplay_opens),
                    brandString(R.string.default_connection_description), DiPlayPreferences.autoConnect(this)) {
                    DiPlayPreferences.saveAutoConnect(this, it)
                }
                SetupGuide.Feature.LOCATION -> toggle(card, brandString(R.string.report_location_to_iphone),
                    brandString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                    AirPlayPersistence.loadLocationReportingEnabled(this), confirmReconnect = true, save = ::onLocationReportingChanged)
                SetupGuide.Feature.CLUSTER_MAP -> reconnectingToggle(card,
                    brandString(R.string.carplay_map_on_instrument_cluster_experimental),
                    brandString(R.string.shows_the_iphone_s_cluster_map_on_the_instrument_cluster_c),
                    AirPlayPersistence.loadClusterMapEnabled(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, it)
                    render()
                }
                SetupGuide.Feature.DILINK4_ADB_CLUSTER -> reconnectingToggle(card,
                    brandString(R.string.adb_cluster_activity_mode), brandString(R.string.adb_cluster_activity_description),
                    AirPlayPersistence.loadAdbClusterEnabled(this)) {
                    AirPlayPersistence.saveAdbClusterEnabled(this, it)
                    ClusterActivityOutput.stopForSettings()
                    render()
                }
                SetupGuide.Feature.BYD_NAVIGATION -> if (BydOutputSettings.available(this)) {
                    toggle(card, brandString(R.string.navigation_on_hud_and_instrument_cluster),
                        brandString(R.string.show_phone_navigation_arrows_distance_and_street_names_on),
                        BydOutputSettings.enabled(this)) { BydOutputSettings.setEnabled(this, it) }
                }
                SetupGuide.Feature.CALLS_ON_DASHBOARD -> toggle(card, brandString(R.string.carplay_calls_on_dashboard),
                    brandString(R.string.carplay_calls_on_dashboard_description),
                    BydOutputSettings.carPlayCalls(this), enabled = !adbSwitchChangePending) { applyCarPlayCalls(it) }
                SetupGuide.Feature.CALL_KEYS -> toggle(card, brandString(R.string.carplay_call_controls_experimental),
                    brandString(R.string.carplay_call_controls_experimental_description),
                    BydOutputSettings.carPlayCallControls(this)) { applyCallKeys(it) }
            }
            if (card.childCount > before) card.addView(setupBadges(entry))
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    private fun setupBadges(entry: SetupGuide.Entry): LinearLayout = row().apply {
        setPadding(0, dp(2), 0, dp(14))
        fun badge(text: String, color: Int) = label(text, 12, color, true).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT); cornerRadius = dp(10).toFloat(); setStroke(dp(1), color)
            }
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        val tested = entry.status == SetupGuide.Status.TESTED
        addView(badge(brandString(if (tested) R.string.setup_badge_tested else R.string.setup_badge_experimental),
            if (tested) READY else WARNING), LinearLayout.LayoutParams(-2, -2))
        if (entry.needsAdb) addView(badge(brandString(R.string.setup_badge_adb), ACCENT),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    }

    private fun setupDoneStep(content: LinearLayout) {
        setupTitle(content, R.string.setup_done_title, brandString(R.string.setup_done_description))
        val card = card()
        card.addView(button(brandString(R.string.connect_phone), true) { closeSetupGuide(connectNow = true) }, matchButton(0, 60))
        card.addView(button(brandString(R.string.settings), false) {
            SetupGuide.markSeen(this)
            setupFromSettings = false
            setupStep = SetupGuide.STEP_CAR
            page = "settings"
            render()
        }, matchButton(12, 60))
        card.addView(button(brandString(R.string.done), false) { closeSetupGuide() }, matchButton(12, 60))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    private fun connectionSetup(content: LinearLayout) {
        content.addView(label(brandString(R.string.connection_setup), 34, TEXT, true))
        content.addView(label(brandString(R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, brandString(R.string.s_1_choose_your_connection)) { card -> wirelessLinkControls(card) }
        section(content, brandString(R.string.s_2_pair_your_iphone)) { card ->
            card.addView(label(brandString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car), 16, MUTED))
            card.addView(button(chooseIphoneTitle(), false) { choosePhone() }, matchButton(12, 60))
            card.addView(button(brandString(R.string.review_app_permissions), false) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, matchButton(12, 60))
        }
        section(content, brandString(R.string.s_3_connect)) { card ->
            card.addView(label(brandString(R.string.return_from_car_settings_to_diplay_then_connect_accept_the), 16, MUTED))
            card.addView(button(brandString(R.string.connect_phone), true) {
                connect(true, returnHomeOnStart = true)
            }, matchButton(12, 60))
        }
        section(content, brandString(R.string.prefer_a_cable)) { card ->
            card.addView(label(brandString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo), 16, MUTED))
            card.addView(button(brandString(R.string.connect_with_usb), false) {
                connect(false, returnHomeOnStart = true)
            }, matchButton(12, 60))
        }
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val mode = if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)
        val modes = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.EXISTING_WIFI)
        val titles = listOf(brandString(R.string.settings_built_in_car_hotspot_recommended), brandString(R.string.wifi_direct), brandString(R.string.existing_wifi_title))
        val descriptions = listOf(
            brandString(R.string.hotspot_mode_manual_desc),
            brandString(R.string.hotspot_mode_p2p_desc),
            brandString(R.string.existing_wifi_description)
        )
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        modes.forEachIndexed { index, candidate ->
            val option = column()
            choices.addView(option, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(16)
            } else LinearLayout.LayoutParams(-1, -2))
            option.addView(button("${if (mode == candidate) "✓  " else ""}${titles[index]}", mode == candidate) {
                if (candidate == WirelessHotspotMode.MANUAL) {
                    pendingCarHotspotSetup = true
                    render()
                } else if (candidate == WirelessHotspotMode.EXISTING_WIFI) {
                    pendingCarHotspotSetup = false
                    applyWirelessLink(candidate)
                } else {
                    pendingCarHotspotSetup = false
                    applyWirelessLink(candidate)
                }
            }, matchButton(12, 60))
            option.addView(label(descriptions[index], 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
        }
        if (mode == WirelessHotspotMode.MANUAL) {
            parent.addView(label(brandString(R.string.hotspot_setup), 22, TEXT, true))
            parent.addView(label(brandString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) })
            parent.addView(button(brandString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(0, 60))
            // Before anything is saved there is no hotspot to edit and no name to show after the dot.
            val detailsMissing = carHotspotDetailsMissing()
            parent.addView(button(if (detailsMissing) brandString(R.string.save_hotspot_details_and_use_this_mode) else "${brandString(R.string.edit_saved_hotspot_prefix)}${storedSsid()}", false) {
                askHotspotCredentials { ssid, password, security ->
                    saveHotspotCredentials(ssid, password, security)
                    pendingCarHotspotSetup = false
                    applyWirelessLink(WirelessHotspotMode.MANUAL)
                }
            }, matchButton(12, 60))
            parent.addView(label(if (detailsMissing) brandString(R.string.finish_setup_save_your_hotspot_details_to_use_this_mode) else if (carHotspotOff()) brandString(R.string.hotspot_details_off) else brandString(R.string.hotspot_details_saved), 15, if (carHotspotOff()) WARNING else MUTED).apply { setPadding(0, dp(12), 0, 0) })
            val join = hotspotJoinControls ?: HotspotJoinControls(this,
                { CarPlayBackgroundSession.hasSession() }, beforeAction = { startupHotspotCancelled = true },
                labelFactory = { label(it, 15, MUTED) }, buttonFactory = { title, click -> button(title, false, click) })
                .also { hotspotJoinControls = it }
            parent.addView(join.build())
        } else if (mode == WirelessHotspotMode.EXISTING_WIFI) {
            parent.addView(label(brandString(R.string.existing_wifi_instructions), 16, MUTED))
            parent.addView(button(brandString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
            val name = ExistingWifiNetworks.read(this).firstOrNull { it.connected }?.ssid
                ?: AirPlayPersistence.loadExistingWifiSsid(this).ifEmpty { brandString(R.string.auto) }
            parent.addView(button("${brandString(R.string.settings_existing_wifi_select)} · $name", false) {
                askExistingWifiNetwork()
            }, matchButton(SETTINGS_BLOCK_GAP_DP, 60))
        } else {
            parent.addView(label(brandString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            wifiDirectChannelControl(parent)
            // #755: the pause also stops the car joining a known network, such as the phone's hotspot, by itself.
            toggle(parent, brandString(R.string.settings_wifi_direct_scan_pause),
                brandString(R.string.settings_wifi_direct_scan_pause_description),
                AirPlayPersistence.loadWifiDirectScanPause(this)) {
                AirPlayPersistence.saveWifiDirectScanPause(this, it)
                markReconnectNeeded()
            }
            parent.addView(button(brandString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
    }

    private fun wifiDirectChannelLabel(channel: Int): String = if (channel == WifiP2pChannels.AUTO) {
        brandString(R.string.auto)
    } else if (channel == WifiP2pChannels.AUTO_5_GHZ || channel == WifiP2pChannels.AUTO_2_4_GHZ) {
        brandString(R.string.settings_wifi_direct_auto_band,
            brandString(if (channel == WifiP2pChannels.AUTO_5_GHZ) R.string.s_5_ghz else R.string.s_2_4_ghz))
    } else {
        brandString(R.string.wifi_direct_channel_choice, channel,
            brandString(if (channel < 36) R.string.s_2_4_ghz else R.string.s_5_ghz))
    }

    private fun wifiDirectChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            brandString(R.string.wifi_direct_channel_summary, wifiDirectChannelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadWifiP2pPreferredChannel(this)), false) {}
        control.setOnClickListener {
            val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.bandChoices + WifiP2pChannels.channels
            val current = AirPlayPersistence.loadWifiP2pPreferredChannel(this)
            var selection = current
            appDialogBuilder().setTitle(R.string.wifi_direct_channel_title)
                .setSingleChoiceItems(choices.map(::wifiDirectChannelLabel).toTypedArray(),
                    choices.indexOf(current)) { _, which -> selection = choices[which] }
                .setPositiveButton(R.string.save) { _, _ ->
                    if (selection != current) {
                        AirPlayPersistence.saveWifiP2pPreferredChannel(this, selection)
                        control.text = summary(selection)
                        toast(brandString(R.string.saved_for_your_next_connection))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        parent.addView(control, matchButton(12, 60))
        parent.addView(label(brandString(R.string.wifi_direct_channel_description), 15, MUTED).apply {
            setPadding(0, dp(6), 0, dp(6))
        })
        parent.addView(label(brandString(R.string.settings_wifi_direct_band_note), 15, MUTED).apply {
            setPadding(0, 0, 0, dp(12))
        })
    }

    private fun mediaChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            brandString(R.string.contrib_audio_home_choice_summary, brandString(R.string.contrib_audio_home_media_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadMediaAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadMediaAudioChannel(this)
            showChannelDialog(
                title = brandString(R.string.contrib_audio_home_media_channel_label),
                current = current,
                navigation = false,
                onApply = { value -> applyMediaChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(12, 60))
    }

    private fun navigationChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            brandString(R.string.contrib_audio_home_choice_summary, brandString(R.string.contrib_audio_home_nav_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadNavigationAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadNavigationAudioChannel(this)
            showChannelDialog(
                title = brandString(R.string.contrib_audio_home_nav_channel_label),
                current = current,
                navigation = true,
                onApply = { value -> applyNavigationChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(10, 60))
        parent.addView(label(brandString(R.string.contrib_audio_home_nav_channel_note), 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    private fun showChannelDialog(title: String, current: Int, navigation: Boolean, onApply: (Int) -> Unit) {
        val preview = AudioChannelPreview { channel ->
            toast(brandString(R.string.contrib_audio_home_channel_preview_unavailable, channel))
        }
        val channels = AirPlayPersistence.AUDIO_CHANNELS
        val labels = channels.map(Int::toString).toTypedArray()
        var selection = current.coerceIn(channels.first, channels.last)
        appDialogBuilder().setTitle(title)
            .setSingleChoiceItems(labels, selection) { _, which ->
                selection = which
                preview.play(which, navigation)
            }
            .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) brandString(R.string.apply_and_reconnect) else brandString(R.string.save)) { _, _ ->
                onApply(selection)
            }
            .setNegativeButton(brandString(R.string.cancel), null)
            .setOnDismissListener { preview.close() }
            .show()
    }

    private fun applyMediaChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveMediaAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyNavigationChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveNavigationAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun channelLabel(value: Int): String = value.toString()

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { brandString(it.messageResource()) }

    /** True until a usable car hotspot name and password are saved, as on a first run. */
    private fun carHotspotDetailsMissing() = pendingCarHotspotSetup || hotspotError(storedSsid(), storedPassword()) != null

    private fun saveHotspotCredentials(ssid: String, password: String, security: ManualHotspotSecurity) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this, security)
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
        markReconnectNeeded()
    }

    private fun askExistingWifiNetwork() {
        var active = true
        val fields = column().apply { setPadding(dp(FidelitySettingsMetrics.CONTENT_PAD_DP), dp(FidelitySettingsMetrics.ROW_GAP_DP), dp(FidelitySettingsMetrics.CONTENT_PAD_DP), dp(FidelitySettingsMetrics.ROW_GAP_DP)) }
        val instructions = label(brandString(R.string.settings_existing_wifi_credentials_description), UiTypography.BODY_SP, MUTED)
        val current = ExistingWifiNetworks.read(this).firstOrNull { it.connected }
        val savedName = AirPlayPersistence.loadExistingWifiSsid(this)
        val name = EditText(appDialogContext()).apply {
            hint = brandString(R.string.existing_wifi_ssid)
            setText(current?.ssid ?: savedName)
            setSingleLine()
            textSize = UiTypography.ROW_SP.toFloat()
            minHeight = dp(GlassChrome.MIN_TARGET_DP)
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val password = EditText(appDialogContext()).apply {
            hint = brandString(R.string.existing_wifi_password)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = UiTypography.ROW_SP.toFloat()
            minHeight = dp(GlassChrome.MIN_TARGET_DP)
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            if (name.text.toString() == savedName)
                setText(AirPlayPersistence.loadExistingWifiPassphrase(this@DiPlayActivity))
        }
        val error = label("", 14, WARNING).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        fields.addView(instructions)
        lateinit var choose: ImageButton
        fun updateChoice() {
            choose.contentDescription = "${brandString(R.string.settings_existing_wifi_select)} · " +
                name.text.toString().ifEmpty { brandString(R.string.auto) }
        }
        choose = iconButton(R.drawable.ic_dp_chevron, brandString(R.string.settings_existing_wifi_select)) {
            systemWifiPicker.show({ active }) { network ->
                name.setText(network.ssid)
            }
        }
        updateChoice()
        var passwordNetwork = name.text.toString()
        name.doAfterTextChanged {
            val selectedName = name.text.toString()
            if (selectedName != passwordNetwork) password.text.clear()
            passwordNetwork = selectedName
            error.visibility = View.GONE
            updateChoice()
        }
        password.doAfterTextChanged { error.visibility = View.GONE }
        val networkInput = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            addView(choose, LinearLayout.LayoutParams(dp(GlassChrome.MIN_TARGET_DP), dp(GlassChrome.MIN_TARGET_DP)).apply {
                marginStart = dp(FidelitySettingsMetrics.CONTROL_GAP_DP)
            })
        }
        val wide = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            resources.configuration.screenWidthDp >= FidelitySettingsMetrics.DIALOG_FORM_MAX_WIDTH_DP
        fields.addView((if (wide) row() else column()).apply {
            addView(networkInput, LinearLayout.LayoutParams(if (wide) 0 else -1, -2, if (wide) 1f else 0f))
            addView(password, LinearLayout.LayoutParams(if (wide) 0 else -1, -2, if (wide) 1f else 0f).apply {
                if (wide) marginStart = dp(FidelitySettingsMetrics.CONTROL_GAP_DP)
            })
        })
        fields.addView(error)
        val dialog = appDialogBuilder().setTitle(R.string.existing_wifi_details)
            .setView(ScrollView(appDialogContext()).apply { addView(fields) })
            .setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { active = false }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ssid = name.text.toString() // SSID whitespace is meaningful.
                val passphrase = password.text.toString()
                val problem = when {
                    passphrase.length !in 8..63 -> brandString(R.string.settings_existing_wifi_password_required)
                    else -> hotspotError(ssid, passphrase)
                }
                if (problem != null) {
                    error.text = problem
                    error.visibility = View.VISIBLE
                } else {
                    AirPlayPersistence.saveExistingWifiCredentials(this, ssid, passphrase)
                    markReconnectNeeded()
                    dialog.dismiss()
                    render()
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout(dp(minOf(FidelitySettingsMetrics.DIALOG_FORM_MAX_WIDTH_DP,
            resources.configuration.screenWidthDp - 2 * FidelitySettingsMetrics.CONTENT_PAD_DP)
            .coerceAtLeast(FidelitySettingsMetrics.DIALOG_ACTION_MIN_WIDTH_DP)), -2)
    }

    private fun askHotspotCredentials(done: (String, String, ManualHotspotSecurity) -> Unit) {
        val dialogContext = appDialogContext()
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(brandString(R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        val ssid = EditText(dialogContext).apply {
            hint = brandString(R.string.hotspot_name)
            setText(storedSsid())
            setSingleLine()
        }
        val password = EditText(dialogContext).apply {
            hint = brandString(R.string.hotspot_password)
            setText(storedPassword())
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        val nameWarning = label(brandString(R.string.settings_hotspot_name_non_english), 14, WARNING)
        fun updateNameWarning() {
            nameWarning.visibility = if (
                com.shilapi.xcertplay.orchestration.ManualHotspotValidation.nameMayNotReachIphone(ssid.text.toString())
            ) View.VISIBLE else View.GONE
        }
        updateNameWarning()
        ssid.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = updateNameWarning()
        })
        fields.addView(ssid); fields.addView(nameWarning); fields.addView(password)
        fields.addView(CheckBox(dialogContext).apply {
            text = brandString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val securityModes = listOf(ManualHotspotSecurity.WPA2, ManualHotspotSecurity.WPA3_TRANSITION, ManualHotspotSecurity.WPA3)
        val securityTitles = listOf(brandString(R.string.wpa2), brandString(R.string.wpa3_transition), brandString(R.string.wpa3))
        var protectedSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
            .takeIf { it in securityModes } ?: ManualHotspotSecurity.WPA2
        run {
            lateinit var securityControl: Button
            fun updateSecurityControl() {
                val hasPassword = password.text.isNotEmpty()
                val title = if (hasPassword) securityTitles[securityModes.indexOf(protectedSecurity)] else brandString(R.string.open)
                securityControl.text = "${brandString(R.string.security)} · $title"
                securityControl.isEnabled = hasPassword
            }
            securityControl = button("", false) {
                var pendingSecurity = protectedSecurity
                appDialogBuilder().setTitle(brandString(R.string.security))
                    .setSingleChoiceItems(securityTitles.toTypedArray(), securityModes.indexOf(protectedSecurity)) { _, index ->
                        pendingSecurity = securityModes[index]
                    }
                    .setPositiveButton(brandString(R.string.save)) { _, _ ->
                        protectedSecurity = pendingSecurity
                        updateSecurityControl()
                    }.setNegativeButton(brandString(R.string.cancel), null).show()
            }
            updateSecurityControl()
            password.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = updateSecurityControl()
            })
            fields.addView(securityControl, matchButton(12, 60))
            fields.addView(label(brandString(R.string.saved_for_your_next_connection), 15, MUTED))
        }
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = appDialogBuilder().setTitle(brandString(R.string.car_hotspot_details))
            .setView(ScrollView(dialogContext).apply { addView(fields) })
            .setPositiveButton(brandString(R.string.save_details), null).setNegativeButton(brandString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(brandString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ssid.text.toString().trim()
                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else {
                    val security = if (secret.isEmpty()) ManualHotspotSecurity.OPEN else protectedSecurity
                    hideKeyboard(); dialog.dismiss(); done(name, secret, security)
                }
            }
        }
        dialog.show()
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.
    private fun markerStepLabel(step: Int, negative: String, positive: String): String = when {
        step == 0 -> brandString(R.string.marker_centre_default)
        step < 0 -> "$negative ${-step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
        else -> "$positive ${step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
    }

    private fun clusterMusicControls(card: LinearLayout) {
        toggle(card, brandString(R.string.settings_cluster_music), brandString(R.string.settings_cluster_music_description),
            ClusterMusicSettings.enabled(this)) {
            ClusterMusicSettings.saveEnabled(this, it)
            render()
        }
        if (!ClusterMusicSettings.enabled(this)) return
        choice(card, brandString(R.string.settings_cluster_music_mode), listOf(
            brandString(R.string.settings_cluster_music_temporary), brandString(R.string.settings_cluster_music_permanent)),
            if (ClusterMusicSettings.permanent(this)) 1 else 0, reconnects = false) {
            ClusterMusicSettings.savePermanent(this, it == 1)
        }
        listOf(
            Triple("x", R.string.settings_cluster_music_horizontal, ClusterMusicSettings.x(this)),
            Triple("y", R.string.settings_cluster_music_vertical, ClusterMusicSettings.y(this)),
            Triple("size", R.string.settings_cluster_music_size, ClusterMusicSettings.size(this)),
        ).forEach { (key, title, value) ->
            card.addView(overlaySliderRow(brandString(title),
                (if (key == "size") 50..150 step 5 else 5..95 step 5).toList(), value) { "$it%" }.also {
                it.onSave = { percent -> ClusterMusicSettings.save(this, key, percent) }
                val head = it.getChildAt(0) as LinearLayout
                val value = head.getChildAt(head.childCount - 1)
                value.layoutParams = (value.layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = dp(SETTINGS_BLOCK_GAP_DP)
                }
            })
        }
    }

    private fun clusterSpeedometerControls(card: LinearLayout) {
        toggle(card, brandString(R.string.settings_cluster_speedometer),
            brandString(R.string.settings_cluster_speedometer_description), AirPlayPersistence.loadClusterSpeedometerEnabled(this)) {
            AirPlayPersistence.saveClusterSpeedometerEnabled(this, it)
            render()
        }
        if (AirPlayPersistence.loadClusterSpeedometerEnabled(this)) {
            card.addView(overlaySliderRow(
                brandString(R.string.settings_cluster_speedometer_horizontal), ClusterSpeedometerOverlay.xPercents,
                AirPlayPersistence.loadClusterSpeedometerX(this),
            ) { value -> "$value %" }.also { row ->
                row.getChildAt(0).layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(resources.configuration.locales[0])
                row.onSave = { value -> AirPlayPersistence.saveClusterSpeedometerX(this, value) }
            })
            card.addView(overlaySliderRow(
                brandString(R.string.settings_cluster_speedometer_vertical), ClusterSpeedometerOverlay.yPercents,
                AirPlayPersistence.loadClusterSpeedometerY(this),
            ) { value -> "$value %" }.also { row ->
                row.getChildAt(0).layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(resources.configuration.locales[0])
                row.onSave = { value -> AirPlayPersistence.saveClusterSpeedometerY(this, value) }
            })
            card.addView(overlaySliderRow(
                brandString(R.string.settings_cluster_speedometer_size), ClusterSpeedometerOverlay.sizePercents,
                AirPlayPersistence.loadClusterSpeedometerSize(this),
            ) { value -> "$value %" }.also { row ->
                row.getChildAt(0).layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(resources.configuration.locales[0])
                row.onSave = { value -> AirPlayPersistence.saveClusterSpeedometerSize(this, value) }
            })
            card.addView(overlaySliderRow(
                brandString(R.string.settings_cluster_speedometer_opacity), ClusterSpeedometerOverlay.opacityPercents,
                AirPlayPersistence.loadClusterSpeedometerOpacity(this),
            ) { value -> "$value %" }.also { row ->
                row.getChildAt(0).layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(resources.configuration.locales[0])
                row.onSave = { value -> AirPlayPersistence.saveClusterSpeedometerOpacity(this, value) }
            })
        }
    }

    /** A 1%-step slider row for overlay placement; every step saves, so the card moves live. */
    private fun overlaySliderRow(title: String, values: List<Int>, current: Int, describe: (Int) -> String): OverlaySliderRow =
        OverlaySliderRow(this, title, values, current, describe)

    private inner class OverlaySliderRow(
        context: android.content.Context,
        private val title: String,
        private val steps: List<Int>,
        current: Int,
        private val describe: (Int) -> String,
    ) : LinearLayout(context) {
        var onSave: (Int) -> Unit = {}
        /** Fired once when the finger leaves the slider, for saves that need a reconnect. */
        var onCommit: () -> Unit = {}
        val slider: SeekBar
        var compactControl: SettingButton? = null
            private set
        private var dialogValue: TextView? = null

        init {
            orientation = VERTICAL
            val valueView = label(describe(current), 16, ACCENT, true)
            val head = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
            head.addView(label(title, 16, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(valueView)
            addView(head)
            slider = SeekBar(context).apply {
                max = steps.lastIndex
                progress = steps.indexOf(current).coerceIn(steps.indices)
                // ProgressBar.setMinHeight needs API 29; the view minimum keeps the touch target before that.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) minHeight = dp(44) else minimumHeight = dp(44)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = steps[progress.coerceIn(steps.indices)]
                        valueView.text = describe(value)
                        compactControl?.text = "$title · ${describe(value)}"
                        dialogValue?.text = describe(value)
                        if (fromUser) onSave(value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) { onCommit() }
                })
            }
            addView(slider, LinearLayout.LayoutParams(-1, dp(44)))
        }

        /** Only the main settings cards collapse; sliders inside adjustment dialogs stay inline. */
        fun makeCompact(): SettingButton {
            compactControl?.let { return it }
            for (index in 0 until childCount) getChildAt(index).visibility = View.GONE
            return button("$title · ${describe(steps[slider.progress])}", false) {
                val body = column().apply {
                    setPadding(dp(FidelitySettingsMetrics.CONTENT_PAD_DP), dp(FidelitySettingsMetrics.ROW_VERTICAL_DP),
                        dp(FidelitySettingsMetrics.CONTENT_PAD_DP), dp(FidelitySettingsMetrics.ROW_VERTICAL_DP))
                }
                val value = label(describe(steps[slider.progress]), UiTypography.SECTION_SP, ACCENT, true).apply {
                    gravity = Gravity.CENTER
                }
                dialogValue = value
                body.addView(value)
                (slider.parent as? ViewGroup)?.removeView(slider)
                slider.visibility = View.VISIBLE
                body.addView(slider, LinearLayout.LayoutParams(-1, dp(GlassChrome.MIN_TARGET_DP)))
                // Keep the original listener and live-save/reconnect semantics. The slider is the
                // same view, with the same complete step list, every time this dialog opens.
                appDialogBuilder().setTitle(title).setView(body)
                    .setPositiveButton(android.R.string.ok, null)
                    .setOnDismissListener {
                        dialogValue = null
                        body.removeView(slider)
                        slider.visibility = View.GONE
                        addView(slider, LinearLayout.LayoutParams(-1, dp(GlassChrome.MIN_TARGET_DP)))
                    }.show()
            }.also {
                compactControl = it
                addView(it, matchButton(0, FidelitySettingsMetrics.SETTING_ROW_DP))
            }
        }
    }

    private fun overlayOffsetLabel(percent: Int, negative: String, positive: String, centre: Int): String {
        val delta = percent - centre
        return when {
            delta == 0 -> brandString(R.string.marker_centre_default)
            delta < 0 -> "$negative ${-delta} %"
            else -> "$positive $delta %"
        }
    }

    /** Push the Audio page's ducking tuning into the running sink, if any. */
    private fun applyNavigationDuckTuning() {
        CarPlayBackgroundSession.snapshot()?.sink?.setNavigationDuckTuning(
            AirPlayPersistence.loadNavigationDuckDb(this),
            AirPlayPersistence.loadNavigationDuckDownMs(this),
            AirPlayPersistence.loadNavigationDuckUpMs(this),
            AirPlayPersistence.loadNavigationDuckHoldMs(this),
        )
    }

    private fun showClusterAccessSetup() {
        val command = "adb shell appops set $packageName GET_USAGE_STATS allow"
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(brandString(R.string.one_time_setup_on_this_car), 20, TEXT, true))
        body.addView(label(brandString(R.string.usage_access_lets_diplay_follow_the_instrument_theme_and_m), 15, MUTED))
        body.addView(label(brandString(R.string.s_1_connect_a_computer_with_adb_installed_to_the_car_using), 16, TEXT))
        body.addView(label(command, 16, TEXT).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(16))
        })
        body.addView(button(brandString(R.string.copy_command), false) {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText(brandString(R.string.clipboard_usage_access), command))
            toast(brandString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(0, 56))
        body.addView(label(brandString(R.string.cluster_adb_multi_device, packageName), 14, MUTED))
        body.addView(label(brandString(R.string.s_3_tap_check_and_enable_below_this_enables_the_cluster_ma), 16, TEXT))
        val status = label(if (DiLink51ClusterMonitor.hasAccess(this)) brandString(R.string.permission_enabled_ready) else brandString(R.string.permission_not_enabled), 16, TEXT)
        body.addView(status)
        val dialog = appDialogBuilder().setTitle(brandString(R.string.automatic_cluster_map_setup))
            .setView(ScrollView(appDialogContext()).apply { addView(body) })
            .setNegativeButton(brandString(R.string.close), null)
            .setPositiveButton(brandString(R.string.check_and_enable), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (DiLink51ClusterMonitor.hasAccess(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, true)
                    DiLink51ClusterLayout.saveAutomatic(this, true)
                    dialog.dismiss()
                    render()
                    toast(brandString(R.string.automatic_map_enabled_open_the_cluster_map_card_or_select))
                    reconnectIfRunning()
                } else {
                    status.text = brandString(R.string.still_waiting_for_usage_access_check_that_the_command_ran)
                }
            }
        }
        dialog.show()
    }

    private fun promptEnableUsbAutoConfirm() {
        if (UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)) {
            toast(brandString(R.string.usb_auto_confirm_status_on))
            return
        }
        appDialogBuilder()
            .setTitle(brandString(R.string.usb_auto_confirm_title))
            .setMessage(brandString(R.string.usb_auto_confirm_dialog_msg))
            .setPositiveButton(brandString(R.string.dialog_auto_permissions)) { _, _ ->
                autoApplyPermissions()
            }
            .setNeutralButton(brandString(R.string.dialog_open_accessibility)) { _, _ ->
                if (!UsbAutoConfirmService.openSettings(this)) {
                    toast(brandString(R.string.wheel_keys_no_settings))
                }
            }
            .setNegativeButton(brandString(R.string.cancel)) { _, _ ->
                render()
            }
            .setOnCancelListener {
                render()
            }
            .show()
    }

    private fun cancelUsbPermissionSetup() {
        val operation = usbPermissionOperation
        usbPermissionOperation = null
        operation?.cancel()
        val dialog = usbPermissionDialog
        usbPermissionDialog = null
        dialog?.dismiss()
    }

    private fun autoApplyPermissions() {
        if (usbPermissionOperation != null || isFinishing || isDestroyed) return
        val operation = usbPermissionOperationFactory(applicationContext)
        usbPermissionOperation = operation
        val progress = appDialogBuilder()
            .setTitle(R.string.auto_grant_title)
            .setMessage(R.string.auto_grant_msg)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelUsbPermissionSetup() }
            .create()
        usbPermissionDialog = progress
        progress.setOnCancelListener { cancelUsbPermissionSetup() }
        progress.setOnDismissListener {
            if (usbPermissionDialog === progress) cancelUsbPermissionSetup()
        }
        progress.show()
        Thread({
            val result = operation.run()
            handler.post {
                if (usbPermissionOperation !== operation || operation.isCancelled || isFinishing || isDestroyed) {
                    return@post
                }
                usbPermissionOperation = null
                usbPermissionDialog = null
                progress.dismiss()
                render()
                if (result.complete) {
                    appDialogBuilder()
                        .setTitle(R.string.auto_grant_success_title)
                        .setMessage(R.string.auto_grant_success_msg)
                        .setPositiveButton(R.string.close, null)
                        .show()
                } else {
                    val reason = when (result.access) {
                        LocalAdb.Access.NOT_APPROVED -> brandString(R.string.auto_grant_confirm_msg)
                        LocalAdb.Access.UNSUPPORTED -> brandString(R.string.adb_pairing_only)
                        else -> brandString(R.string.auto_grant_incomplete)
                    }
                    showManualPermissionDialog(reason)
                }
            }
        }, "diplay-auto-permission").start()
    }

    private fun showManualPermissionDialog(reason: String) {
        val body = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }
        body.addView(label(reason, 15, MUTED).apply { setPadding(0, 0, 0, dp(12)) })

        val autoConfirmOn = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)
        body.addView(button(if (autoConfirmOn) brandString(R.string.usb_auto_confirm_status_on) else brandString(R.string.btn_open_accessibility_setting), false) {
            if (!UsbAutoConfirmService.openSettings(this)) toast(brandString(R.string.wheel_keys_no_settings))
        }, matchButton(0, 56))

        val overlayOn = CenterMapOverlay.permitted(this)
        body.addView(button(if (overlayOn) brandString(R.string.center_map_overlay_allowed) else brandString(R.string.btn_open_overlay_setting), false) {
            openOverlayPermission()
        }, matchButton(10, 56))

        UsbPermissionSetup.snapshot(this).forEach { (permission, granted) ->
            val title = brandString(when (permission) {
                UsbPermissionSetup.Permission.ACCESSIBILITY -> R.string.usb_auto_confirm_title
                UsbPermissionSetup.Permission.USAGE -> R.string.center_map_auto_hide
                UsbPermissionSetup.Permission.OVERLAY -> R.string.btn_open_overlay_setting
            })
            body.addView(label("$title: ${brandString(if (granted) R.string.permission_enabled_ready else R.string.permission_not_enabled)}", 14, if (granted) MUTED else WARNING))
        }
        val adbCmd = UsbPermissionSetup.manualCommand(packageName)
        body.addView(label(brandString(R.string.manual_grant_cmd_hint), 14, MUTED).apply { setPadding(0, dp(12), 0, dp(6)) })
        body.addView(label(adbCmd, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(BUTTON)
        })
        body.addView(button(brandString(R.string.copy_command), false) {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText("Lincoln ADB Command", adbCmd)
            )
            toast(brandString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(8, 50))

        appDialogBuilder()
            .setTitle(brandString(R.string.permissions_and_connection_help))
            .setView(ScrollView(appDialogContext()).apply { addView(body) })
            .setPositiveButton(brandString(R.string.close)) { _, _ -> render() }
            .show()
    }

    /** Optional guidance-volume routing through the existing wheel service. */
    private fun navigationWheelControls(card: LinearLayout) {
        toggle(card, brandString(R.string.settings_navigation_wheel_volume), brandString(R.string.settings_navigation_wheel_volume_description),
            NavigationWheelSettings.enabled(this)) {
            NavigationWheelSettings.setEnabled(this, it)
            render()
        }
        if (!NavigationWheelSettings.enabled(this) || WheelKeyService.connected()) return
        card.addView(label(brandString(R.string.settings_navigation_wheel_service_required), 14, WARNING))
        card.addView(button(brandString(R.string.wheel_keys_open_settings), false) {
            if (!UsbAutoConfirmService.openSettings(this)) toast(brandString(R.string.wheel_keys_no_settings))
        }, matchButton(10, 56))
        card.addView(button(brandString(R.string.wheel_keys_enable_adb), false) {
            Thread({
                val access = WheelKeyService.enableOverAdb(this)
                runOnUiThread {
                    if (access != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) {
                        toast(brandString(R.string.wheel_keys_adb_failed, access.name))
                    }
                    render()
                }
            }, "diplay-navigation-wheel-enable").start()
        }, matchButton(10, 56))
    }

    /** Steering-wheel keys for the dashboard map zoom and the CarPlay joystick: the switches, the key service and the keys. */
    // One place for the key service setup, above every feature that needs it.
    private fun wheelKeysSettings(card: LinearLayout) {
        // The joystick and map zoom use BYD's media and custom keys.
        val byd = CarHotspotSetup.isBydHeadUnit(this)
        val zoomAvailable = wheelMapZoomAvailable()
        val mediaKeysOn = WheelMediaKeySettings.enabled(this)
        val vehicleKeysOn = WheelZoomSettings.joystick(this) ||
            (zoomAvailable && WheelZoomSettings.enabled(this)) || mediaKeysOn
        if (WheelZoomSettings.siriKey(this) || vehicleKeysOn) wheelKeyServiceControls(card)
        toggle(
            card,
            brandString(R.string.settings_carplay_media_keys),
            brandString(R.string.settings_carplay_media_keys_description),
            mediaKeysOn,
        ) {
            WheelMediaKeySettings.setEnabled(this, it)
            render()
        }
        siriKeyControls(card)
        // It acts on BYD's short voice keys (327, 304) only, which other head units never send. Once on it
        // stays reachable, like the controls below, in case the package check misses the car.
        if (byd || WheelZoomSettings.siriLongPressOnly(this)) {
            toggle(card, brandString(R.string.wheel_siri_long_press_only), brandString(R.string.wheel_siri_long_press_only_description),
                WheelZoomSettings.siriLongPressOnly(this)) { WheelZoomSettings.setSiriLongPressOnly(this, it) }
        }
        // Any head unit: a paired keyboard, remote or rotary controller works alongside touch.
        toggle(card, brandString(R.string.settings_external_controller), brandString(R.string.settings_external_controller_description),
            AirPlayPersistence.loadExternalController(this)) { AirPlayPersistence.saveExternalController(this, it) }
        // Keep previously configured controls reachable even if package detection misses the car.
        if (byd || zoomAvailable || WheelZoomSettings.joystick(this)) wheelKeyControls(card)
    }

    // Map zoom only works where the dashboard map card used to show these controls.
    private fun wheelMapZoomAvailable(): Boolean {
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) return false
        val adbCluster = AdbClusterRouter.enabled(this)
        if (DiLink51ClusterLayout.supported() && !adbCluster) return false
        return adbCluster || ClusterMapPresentation.findDisplay(this) != null
    }

    private fun wheelKeyControls(card: LinearLayout) {
        val zoomAvailable = wheelMapZoomAvailable()
        if (zoomAvailable) {
            toggle(card, brandString(R.string.wheel_map_zoom), brandString(R.string.wheel_map_zoom_description),
                WheelZoomSettings.enabled(this)) {
                WheelZoomSettings.setEnabled(this, it)
                render()
            }
        }
        toggle(card, brandString(R.string.wheel_joystick), brandString(R.string.wheel_joystick_description),
            WheelZoomSettings.joystick(this)) {
            WheelZoomSettings.setJoystick(this, it)
            render()
        }
        val zoom = zoomAvailable && WheelZoomSettings.enabled(this)
        val joystick = WheelZoomSettings.joystick(this)
        if (!zoom && !joystick) return
        if (zoom) {
            val behaviours = WheelZoomSettings.Behaviour.entries
            choice(card, brandString(R.string.wheel_zoom_behaviour),
                listOf(brandString(R.string.wheel_zoom_behaviour_toggle), brandString(R.string.wheel_zoom_behaviour_timed)),
                behaviours.indexOf(WheelZoomSettings.behaviour(this)), reconnects = false) {
                WheelZoomSettings.setBehaviour(this, behaviours[it])
            }
        }
        if (joystick) {
            toggle(card, brandString(R.string.wheel_joystick_auto_off), brandString(R.string.wheel_joystick_auto_off_description),
                WheelZoomSettings.joystickAutoOff(this)) { WheelZoomSettings.setJoystickAutoOff(this, it) }
        }
        for (role in WheelZoomSettings.Role.entries) {
            // A key can serve both: the mode key goes back in the joystick, the zoom keys move it.
            val zoomName = when (role) {
                WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_mode
                WheelZoomSettings.Role.ZOOM_IN -> R.string.wheel_key_role_zoom_in
                WheelZoomSettings.Role.ZOOM_OUT -> R.string.wheel_key_role_zoom_out
                else -> null
            }.takeIf { zoom }
            val joystickName = when (role) {
                WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_back
                WheelZoomSettings.Role.ZOOM_IN, WheelZoomSettings.Role.PREVIOUS -> R.string.wheel_key_role_previous
                WheelZoomSettings.Role.ZOOM_OUT, WheelZoomSettings.Role.NEXT -> R.string.wheel_key_role_next
                WheelZoomSettings.Role.JOYSTICK -> R.string.wheel_key_role_joystick
                WheelZoomSettings.Role.SELECT -> R.string.wheel_key_role_select
                WheelZoomSettings.Role.SIRI -> null
            }.takeIf { joystick }
            val names = listOfNotNull(zoomName, joystickName)
            if (names.isEmpty()) continue
            wheelKeyAssignButton(card, role, names.joinToString(" · ") { brandString(it) })
        }
    }

    /** A wheel key that opens Siri on any car whose key reaches the key service. */
    private fun siriKeyControls(card: LinearLayout) {
        toggle(card, brandString(R.string.wheel_siri_key), brandString(R.string.wheel_siri_key_description),
            WheelZoomSettings.siriKey(this)) {
            WheelZoomSettings.setSiriKey(this, it)
            render()
        }
        if (!WheelZoomSettings.siriKey(this)) return
        wheelKeyAssignButton(card, WheelZoomSettings.Role.SIRI, brandString(R.string.wheel_key_role_siri))
    }

    private fun wheelKeyServiceControls(card: LinearLayout) {
        val connected = WheelKeyService.connected()
        card.addView(label(brandString(when {
            connected -> R.string.wheel_keys_service_on
            WheelKeyService.enabledInSettings(this) -> R.string.wheel_keys_service_starting
            else -> R.string.wheel_keys_service_off
        }), 14, if (connected) MUTED else WARNING))
        if (!connected) {
            card.addView(actionButton(brandString(R.string.wheel_keys_enable_adb), false) {
                Thread({
                    val access = WheelKeyService.enableOverAdb(this)
                    runOnUiThread {
                        if (access != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) {
                            toast(brandString(R.string.wheel_keys_adb_failed, access.name))
                        }
                        render()
                    }
                }, "diplay-wheel-keys-enable").start()
            }, matchButton(10, 56))
            card.addView(button(brandString(R.string.wheel_keys_open_settings), false) {
                if (!UsbAutoConfirmService.openSettings(this)) toast(brandString(R.string.wheel_keys_no_settings))
            }, matchButton(10, 56))
        }
    }

    private fun wheelKeyAssignButton(card: LinearLayout, role: WheelZoomSettings.Role, name: String) {
        fun current(key: WheelKey? = WheelZoomSettings.key(this, role)) =
            brandString(R.string.wheel_key_assign, name, key?.toString() ?: brandString(R.string.wheel_key_none))
        lateinit var assign: android.widget.Button
        assign = button(current(), false) {
            val cancelled = { runOnUiThread {
                assign.text = current()
            } }
            val refused = { taken: WheelZoomSettings.Role ->
                runOnUiThread {
                    assign.text = current()
                    toast(brandString(R.string.wheel_key_in_use, brandString(wheelKeyRoleName(taken))))
                }
            }
            val started = WheelKeyService.learn(role, cancelled, refused) { _, key -> runOnUiThread {
                assign.text = current(key)
            } } ||
                // Without the service the Siri key is learnt from this window, so only keys that reach apps.
                (role == WheelZoomSettings.Role.SIRI && learnInWindow(role, cancelled, refused) {
                    assign.text = current(it)
                })
            if (started) assign.text = brandString(R.string.wheel_key_press, name)
            else toast(brandString(R.string.wheel_keys_service_off))
        }
        card.addView(assign, matchButton(10, 56))
    }

    private fun wheelKeyRoleName(role: WheelZoomSettings.Role): Int = when (role) {
        WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_mode
        WheelZoomSettings.Role.ZOOM_IN -> R.string.wheel_key_role_zoom_in
        WheelZoomSettings.Role.ZOOM_OUT -> R.string.wheel_key_role_zoom_out
        WheelZoomSettings.Role.JOYSTICK -> R.string.wheel_key_role_joystick
        WheelZoomSettings.Role.PREVIOUS -> R.string.wheel_key_role_previous
        WheelZoomSettings.Role.NEXT -> R.string.wheel_key_role_next
        WheelZoomSettings.Role.SELECT -> R.string.wheel_key_role_select
        WheelZoomSettings.Role.SIRI -> R.string.wheel_key_role_siri
    }

    private class WindowKeyLearning(
        val role: WheelZoomSettings.Role,
        val cancelled: () -> Unit,
        val refused: (WheelZoomSettings.Role) -> Unit,
        val done: (WheelKey) -> Unit,
    )

    private fun learnInWindow(role: WheelZoomSettings.Role, cancelled: () -> Unit,
        refused: (WheelZoomSettings.Role) -> Unit, done: (WheelKey) -> Unit): Boolean {
        cancelKeyLearning()
        windowLearning = WindowKeyLearning(role, cancelled, refused, done)
        handler.postDelayed(endWindowLearning, WheelKeyService.LEARNING_TIMEOUT_MILLIS)
        return true
    }

    private fun cancelKeyLearning() {
        WheelKeyService.cancelLearning()
        handler.removeCallbacks(endWindowLearning)
        val cancelled = windowLearning?.cancelled
        windowLearning = null
        cancelled?.invoke()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
        val action = windowLearningPresses.filter(
            Triple(event.deviceId, event.keyCode, event.scanCode),
            event.action == KeyEvent.ACTION_DOWN, event.repeatCount == 0,
        ) {
            val learning = windowLearning
            if (learning == null || event.keyCode == KeyEvent.KEYCODE_BACK) return@filter WheelZoomKeys.Action.PASS
            if (inCall(this) || !WheelZoomSettings.siriKey(this)) {
                cancelKeyLearning()
                return@filter WheelZoomKeys.Action.PASS
            }
            handler.removeCallbacks(endWindowLearning)
            windowLearning = null
            val key = WheelKey.of(event)
            val taken = WheelZoomSettings.conflict(this, learning.role, key)
            if (taken != null) {
                Log.i(WheelKeyService.TAG, "${learning.role} key $key refused: it is the $taken key (learnt without the service)")
                learning.refused(taken)
            } else {
                WheelZoomSettings.assign(this, learning.role, key)
                Log.i(WheelKeyService.TAG, "${learning.role} key is now $key (learnt without the service)")
                learning.done(key)
            }
            WheelZoomKeys.Action.CONSUME
        }
        return action != WheelZoomKeys.Action.PASS || super.dispatchKeyEvent(event)
    }

    /**
     * Lincoln/Ford cluster forwarding. Shown only on a head unit that has the cluster app (or the
     * tested firmware), and off by default; it never competes with the BYD card on a BYD car.
     */
    private fun lincolnClusterSection(card: LinearLayout) {
        toggle(card, brandString(R.string.settings_lincoln_cluster_navigation),
            "${brandString(R.string.settings_lincoln_cluster_description)}\n\n${brandString(R.string.settings_lincoln_cluster_navigation_description)}",
            com.shilapi.xcertplay.lincoln.LincolnClusterSettings.navigation(this)) {
            com.shilapi.xcertplay.lincoln.LincolnClusterSettings.setNavigation(this, it)
            if (it) com.shilapi.xcertplay.lincoln.LincolnClusterOutput.attach(applicationContext)
        }
        toggle(card, brandString(R.string.settings_lincoln_cluster_enhanced_navigation),
            brandString(R.string.settings_lincoln_cluster_enhanced_navigation_description),
            com.shilapi.xcertplay.lincoln.LincolnClusterSettings.enhancedNavigation(this)) {
            com.shilapi.xcertplay.lincoln.LincolnClusterSettings.setEnhancedNavigation(this, it)
        }
        toggle(card, brandString(R.string.settings_lincoln_cluster_music),
            brandString(R.string.settings_lincoln_cluster_music_description),
            com.shilapi.xcertplay.lincoln.LincolnClusterSettings.music(this)) {
            com.shilapi.xcertplay.lincoln.LincolnClusterSettings.setMusic(this, it)
            if (it) com.shilapi.xcertplay.lincoln.LincolnClusterOutput.attach(applicationContext)
        }
        toggle(card, brandString(R.string.settings_lincoln_cluster_remaining),
            brandString(R.string.settings_lincoln_cluster_remaining_description),
            com.shilapi.xcertplay.lincoln.LincolnClusterSettings.remaining(this)) {
            com.shilapi.xcertplay.lincoln.LincolnClusterSettings.setRemaining(this, it)
        }
        card.addView(label(brandString(R.string.settings_lincoln_cluster_status,
            com.shilapi.xcertplay.lincoln.LincolnClusterSettings.clusterPackage(this),
            com.shilapi.xcertplay.lincoln.LincolnClusterOutput.statusLine()), 14, MUTED))
    }

    /** The 0.2.9 Dashboard song setting, shown once: in the BYD navigation card, or under Advanced vehicle data. */
    private fun clusterSongSwitch(card: LinearLayout) {
        toggle(card, brandString(R.string.cluster_song),
            brandString(R.string.cluster_song_description),
            BydOutputSettings.clusterSong(this), enabled = !adbSwitchChangePending) {
            BydOutputSettings.setClusterSong(this, it)
            if (it) checkAdbState(mayAsk = true)
            BydNavigationOutputs.clusterSongChanged(it)
        }
        toggle(card, brandString(R.string.cluster_song_on_change), brandString(R.string.cluster_song_on_change_description),
            BydOutputSettings.clusterSongOnChange(this), enabled = !adbSwitchChangePending) {
            BydOutputSettings.setClusterSongOnChange(this, it)
            BydNavigationOutputs.clusterSongOnChangeChanged()
        }
        toggle(card, brandString(R.string.carplay_call_controls_experimental),
            brandString(R.string.carplay_call_controls_experimental_description),
            BydOutputSettings.carPlayCallControls(this)) { applyCallKeys(it) }
        toggle(card, brandString(R.string.carplay_calls_on_dashboard),
            brandString(R.string.carplay_calls_on_dashboard_description),
            BydOutputSettings.carPlayCalls(this), enabled = !adbSwitchChangePending) { applyCarPlayCalls(it) }
    }

    private fun applyCallKeys(enabled: Boolean) {
        BydOutputSettings.setCarPlayCallControls(this, enabled)
        if (enabled && !WheelKeyService.connected()) {
            Thread({
                val access = WheelKeyService.enableOverAdb(this)
                if (access != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) {
                    runOnUiThread { toast(brandString(R.string.wheel_keys_adb_failed, access.name)) }
                }
            }, "diplay-call-keys-enable").start()
        }
    }

    private fun applyCarPlayCalls(enabled: Boolean) {
        BydOutputSettings.setCarPlayCalls(this, enabled)
        if (enabled) checkAdbState(mayAsk = true)
        BydNavigationOutputs.carPlayCallsChanged(enabled)
    }

    private fun advancedVehicleData(card: LinearLayout) {
        val legacyMode = BydOutputSettings.legacyVehicleProbe(this)
        card.addView(label(brandString(R.string.advanced_vehicle_data_description), 14, MUTED)
            .apply { setPadding(0, dp(12), 0, 0) })
        choice(
            card,
            brandString(R.string.vehicle_data_mode),
            listOf(
                brandString(R.string.vehicle_data_mode_default),
                brandString(R.string.vehicle_data_mode_legacy),
            ),
            if (legacyMode) 1 else 0,
            reconnects = false,
            announcesReconnect = vehicleDataSwitchesOn(),
            // A switch during ADB work would be dropped by the running step.
            enabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress(),
        ) { selectVehicleDataMode(it == 1) }
        if (!legacyMode) {
            defaultVehicleData(card)
            return
        }
        val capabilities = displayedVehicleCapabilities()
        val adbText = when {
            vehicleProbeAuthorizationInProgress -> brandString(R.string.adb_checking_may_ask)
            adbCheckInProgress -> brandString(if (adbCheckMayAsk)
                R.string.adb_checking_may_ask else R.string.adb_checking)
            vehicleProbeInProgress || automaticVehicleValidationInProgress ->
                brandString(R.string.probing_vehicle_data)
            adbCheckFailed -> brandString(R.string.adb_check_failed)
            adbAccessState == null && capabilities != null -> brandString(R.string.vehicle_probe_saved_automatic)
            adbAccessState == null -> brandString(R.string.adb_not_checked)
            else -> adbLinkStatusText(requireNotNull(adbAccessState))
        }
        val healthy = capabilities != null && !adbCheckFailed && vehicleProbeOutcome?.error == null &&
            pendingVehicleLostFields.isEmpty() &&
            (adbAccessState == null || adbAccessState == BydAdbAccess.State.READY)
        card.addView(label(adbText, 14, if (healthy) MUTED else WARNING)
            .apply { setPadding(0, dp(12), 0, 0) })
        if (capabilities != null) {
            showVehicleProbeResults(card, capabilities)
            showVehicleDataSettings(card, capabilities)
        }
        if (vehicleProbeAuthorizationInProgress || adbCheckInProgress ||
            vehicleProbeInProgress || automaticVehicleValidationInProgress) return
        vehicleProbeOutcome?.error?.let {
            card.addView(label(brandString(R.string.vehicle_probe_failed, it), 14, WARNING)
                .apply { setPadding(0, dp(10), 0, 0) })
        }
        if (pendingVehicleLostFields.isNotEmpty()) {
            card.addView(label(
                brandString(
                    R.string.vehicle_probe_lost_saved_fields,
                    localizedVehicleFields(pendingVehicleLostFields),
                ),
                14,
                WARNING,
            ).apply { setPadding(0, dp(10), 0, 0) })
            card.addView(button(brandString(R.string.replace_saved_vehicle_data_anyway), false) {
                replaceSavedVehicleDataAnyway()
            }, matchButton(10, 56))
        }
        val needsUserAction = capabilities == null || adbCheckFailed || vehicleProbeOutcome?.error != null ||
            pendingVehicleLostFields.isNotEmpty() ||
            adbAccessState in setOf(BydAdbAccess.State.NOT_APPROVED, BydAdbAccess.State.ADB_OFF, BydAdbAccess.State.PAIRING_ONLY)
        if (needsUserAction) {
            val title = if (adbAccessState == BydAdbAccess.State.NOT_APPROVED) {
                R.string.request_adb_authorization
            } else if (capabilities == null) {
                R.string.probe_adb_and_vehicle_data
            } else {
                R.string.probe_vehicle_data_again
            }
            card.addView(button(brandString(title), false) {
                probeVehicleData(mayAsk = true)
            }.apply { isEnabled = !adbSwitchChangePending && !adbCheckInProgress }, matchButton(10, 56))
        }
    }

    private fun defaultVehicleData(card: LinearLayout) {
        val adbText = when {
            vehicleProbeAuthorizationInProgress -> brandString(R.string.adb_checking_may_ask)
            adbCheckInProgress -> brandString(if (adbCheckMayAsk)
                R.string.adb_checking_may_ask else R.string.adb_checking)
            vehicleProbeInProgress -> brandString(R.string.probing_vehicle_data)
            adbCheckFailed -> brandString(R.string.adb_check_failed)
            adbAccessState == null -> brandString(R.string.adb_not_checked)
            else -> adbLinkStatusText(requireNotNull(adbAccessState))
        }
        val healthy = !adbCheckFailed && vehicleProbeOutcome?.error == null &&
            (adbAccessState == null || adbAccessState == BydAdbAccess.State.READY)
        card.addView(label(adbText, 14, if (healthy) MUTED else WARNING)
            .apply { setPadding(0, dp(12), 0, 0) })
        val busy = vehicleProbeAuthorizationInProgress || adbCheckInProgress || vehicleProbeInProgress
        if (!busy && adbAccessState == BydAdbAccess.State.READY) {
            defaultVehicleStatus?.let { showDefaultVehicleReadings(card, it) }
        }
        vehicleProbeOutcome?.error?.let {
            card.addView(label(brandString(R.string.vehicle_probe_failed, it), 14, WARNING)
                .apply { setPadding(0, dp(10), 0, 0) })
        }
        showVehicleDataSettings(card, capabilities = null)
        if (!busy) {
            card.addView(button(brandString(R.string.check_adb_access), false) {
                checkAdbState(mayAsk = true)
            }.apply { isEnabled = !adbSwitchChangePending }, matchButton(10, 56))
        }
    }

    /** The last default-mode reads; a value an enabled switch needs is a warning when missing. */
    private fun showDefaultVehicleReadings(card: LinearLayout, status: BydAdbAccess.Status) {
        fun reading(value: String?, unreadable: Int, needed: Boolean) {
            val text = value ?: brandString(unreadable).takeIf { needed } ?: return
            card.addView(label(text, 14, if (value != null) MUTED else WARNING))
        }
        val battery = status.batteryPercent?.let { percent ->
            status.rangeKm?.let { brandString(R.string.adb_battery_reading, percent.roundToInt(), it) }
        }
        reading(battery, R.string.adb_battery_unreadable, BydOutputSettings.batteryToIphone(this))
        reading(status.speedKmh?.let { brandString(R.string.adb_vehicle_speed_reading, it.roundToInt()) },
            R.string.adb_vehicle_speed_unreadable, BydOutputSettings.wheelSpeedToIphone(this))
        reading(status.gear?.let { brandString(R.string.adb_vehicle_gear_reading, it.letter.toString()) },
            R.string.adb_vehicle_gear_unreadable,
            BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this))
    }

    private fun selectVehicleDataMode(legacyMode: Boolean) {
        if (adbSwitchChangePending) return
        // A stale mode dialog must also invalidate a probe when the Boolean stays the same.
        synchronized(vehicleOperationLock) { vehicleProbeGeneration++ }
        vehicleProbeAuthorizationInProgress = false
        vehicleProbeInProgress = false
        if (!legacyMode) {
            cancelAutomaticVehicleValidationForUserOperation(resumeAfter = false)
            synchronized(vehicleOperationLock) {
                BydOutputSettings.setLegacyVehicleProbe(this, false)
            }
            vehicleProbeOutcome = null
            pendingVehicleReplacement = null
            pendingVehicleLostFields = emptySet()
            defaultVehicleStatus = null
            render()
            // The session was built from the legacy probe; apply once the default reads succeed.
            vehicleDataReconnectPending = vehicleDataSwitchesOn()
            if (vehicleDataReconnectPending) checkAdbState(mayAsk = true)
            return
        }
        if (BydVehicleFieldStore.load(this) == null) {
            probeVehicleData(mayAsk = true, activateLegacyModeOnSuccess = true)
            return
        }
        synchronized(vehicleOperationLock) {
            BydOutputSettings.setLegacyVehicleProbe(this, true)
        }
        vehicleProbeOutcome = null
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        scheduleAutomaticVehicleValidation()
        render()
        // With every vehicle-data switch off, the mode changes nothing CarPlay was told.
        if (vehicleDataSwitchesOn()) reconnectForVehicleSetting()
    }

    // Probe results are saved before they are shown, so the saved snapshot is the newest without
    // trusting the clock.
    private fun displayedVehicleCapabilities(): BydVehicleCapabilities? =
        BydVehicleFieldStore.load(this) ?: vehicleProbeOutcome?.capabilities

    private fun localizedVehicleFields(fields: Set<BydVehicleField>): String = fields.map { field ->
        brandString(when (field) {
            BydVehicleField.SPEED -> R.string.vehicle_field_speed
            BydVehicleField.GEAR -> R.string.vehicle_field_gear
            BydVehicleField.SOC, BydVehicleField.RANGE, BydVehicleField.REMAINING_KWH ->
                R.string.vehicle_field_battery
            BydVehicleField.BMS_STATE -> R.string.vehicle_field_charging
        })
    }.distinct().joinToString()

    private fun replaceSavedVehicleDataAnyway() {
        val candidate = pendingVehicleReplacement ?: return
        // Never over a snapshot saved since the comparison; a failed write is shown, not thrown.
        val error = runCatching {
            BydVehicleFieldStore.replaceAnyway(applicationContext, pendingVehicleReplacementExpected, candidate)
        }.fold(
            onSuccess = { replaced -> if (replaced) null else brandString(R.string.vehicle_probe_snapshot_changed) },
            onFailure = { it.message ?: it.javaClass.simpleName },
        )
        Log.i(BYD_VEHICLE_TAG, "user replaced saved vehicle data error=${error ?: "none"}")
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        vehicleProbeOutcome = error?.let { BydVehicleProbeOutcome(BydAdbAccess.State.READY, error = it) }
        render()
    }

    private fun showVehicleProbeResults(card: LinearLayout, capabilities: BydVehicleCapabilities) {
        val catalogFields = capabilities.fields.values.count { it.address?.source == BydFieldSource.FIRMWARE }
        card.addView(label(brandString(if (capabilities.catalogAvailable)
            R.string.vehicle_probe_catalog_ready else R.string.vehicle_probe_catalog_fallback, catalogFields),
            14, if (capabilities.catalogAvailable) MUTED else WARNING).apply { setPadding(0, dp(14), 0, 0) })

        val speed = capabilities.result(BydVehicleField.SPEED)
        val speedValue = speed.value
        val speedText = when {
            speedValue != null -> brandString(R.string.adb_vehicle_speed_reading, speedValue.roundToInt())
            speed.supported -> brandString(R.string.vehicle_probe_cached_supported, brandString(R.string.vehicle_field_speed))
            else -> brandString(R.string.adb_vehicle_speed_unreadable)
        }
        card.addView(label(speedText, 14, if (speed.supported) MUTED else WARNING))
        val gear = capabilities.result(BydVehicleField.GEAR)
        val gearValue = gear.value
        val gearText = when {
            gearValue != null -> brandString(R.string.adb_vehicle_gear_reading, gearLetter(gearValue.toInt()))
            gear.supported -> brandString(R.string.vehicle_probe_cached_supported, brandString(R.string.vehicle_field_gear))
            else -> brandString(R.string.adb_vehicle_gear_unreadable)
        }
        card.addView(label(gearText, 14, if (gear.supported) MUTED else WARNING))

        if (capabilities.batterySupported) {
            val percentValue = capabilities.result(BydVehicleField.SOC).value
            val rangeValue = capabilities.result(BydVehicleField.RANGE).value
            val energy = capabilities.result(BydVehicleField.REMAINING_KWH).value
            card.addView(label(if (percentValue == null || rangeValue == null) {
                brandString(R.string.vehicle_probe_cached_supported, brandString(R.string.vehicle_field_battery))
            } else if (energy != null) {
                brandString(R.string.vehicle_probe_battery_reading,
                    percentValue.roundToInt(), rangeValue.roundToInt(), energy)
            } else {
                brandString(R.string.vehicle_probe_battery_without_energy,
                    percentValue.roundToInt(), rangeValue.roundToInt())
            }, 14, if (energy != null || percentValue == null) MUTED else WARNING))
        } else {
            card.addView(label(brandString(R.string.adb_battery_unreadable), 14, WARNING))
        }
        val charging = capabilities.result(BydVehicleField.BMS_STATE)
        val chargingValue = charging.value
        val chargingText = when {
            chargingValue != null -> brandString(R.string.vehicle_probe_charging_state, chargingValue.toInt())
            charging.supported -> brandString(R.string.vehicle_probe_cached_supported, brandString(R.string.vehicle_field_charging))
            else -> brandString(R.string.vehicle_probe_charging_unreadable)
        }
        card.addView(label(chargingText, 14, if (charging.supported) MUTED else WARNING))
    }

    private fun showVehicleDataSettings(card: LinearLayout, capabilities: BydVehicleCapabilities?) {
        toggle(card, brandString(R.string.bt_suspend_during_carplay), brandString(R.string.bt_suspend_during_carplay_description),
            AirPlayPersistence.loadBtSuspendDuringCarplay(this), enabled = !adbSwitchChangePending) {
            AirPlayPersistence.saveBtSuspendDuringCarplay(this, it)
            markReconnectNeeded()
            if (it) checkAdbState(mayAsk = true)
        }
        if (AirPlayPersistence.loadBtSuspendDuringCarplay(this)) {
            val delays = listOf(5, 10, 15, 30)
            choice(card, brandString(R.string.bt_suspend_delay), delays.map { brandString(R.string.bt_suspend_delay_option, it) },
                delays.indexOf(AirPlayPersistence.loadBtSuspendDelaySeconds(this)).coerceAtLeast(0), reconnects = false) {
                AirPlayPersistence.saveBtSuspendDelaySeconds(this, delays[it])
                markReconnectNeeded()
            }
        }
        if (capabilities == null || capabilities.batterySupported) {
            toggle(card, brandString(R.string.car_battery_for_the_iphone),
                brandString(R.string.car_battery_for_the_iphone_description),
                BydOutputSettings.batteryToIphone(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setBatteryToIphone(this, it)
                onVehicleDataSettingChanged(it)
            }
            val connectors = EvChargingConnectors.entries
            choice(card, brandString(R.string.charging_connectors), connectors.map { it.localizedLabel(this) },
                connectors.indexOf(BydOutputSettings.chargingConnectors(this))) {
                BydOutputSettings.setChargingConnectors(this, connectors[it])
            }
            val lowCharge = BydOutputSettings.lowChargePresets
            choice(card, brandString(R.string.low_charge_warning), lowCharge.map {
                    brandString(if (it == BydOutputSettings.DEFAULT_LOW_CHARGE_PERCENT)
                        R.string.percent_default else R.string.percent_value, it)
                }, lowCharge.indexOf(BydOutputSettings.lowChargePercent(this)).coerceAtLeast(0), reconnects = false) {
                BydOutputSettings.setLowChargePercent(this, lowCharge[it])
            }
        }
        if (capabilities == null || capabilities.motionSupported) {
            toggle(card, brandString(R.string.wheel_speed_for_tunnels),
                brandString(R.string.wheel_speed_for_tunnels_description),
                BydOutputSettings.wheelSpeedToIphone(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setWheelSpeedToIphone(this, it)
                onVehicleDataSettingChanged(it)
            }
        }
        if (capabilities == null || capabilities.gearSupported) {
            toggle(card, brandString(R.string.video_while_parked),
                brandString(R.string.video_while_parked_description),
                BydOutputSettings.videoWhileParked(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setVideoWhileParked(this, it)
                onVehicleDataSettingChanged(it)
            }
        }
    }

    private fun onVehicleDataSettingChanged(enabled: Boolean) {
        if (!enabled) {
            reconnectForVehicleSetting()
        } else if (BydOutputSettings.legacyVehicleProbe(this)) {
            scheduleAutomaticVehicleValidation()
            reconnectForVehicleSetting()
        } else {
            // Default mode applies the switch once a check reads what the enabled switches need.
            vehicleDataReconnectPending = true
            checkAdbState(mayAsk = true)
        }
    }

    // The approval dialog can open only after an explicit user action.
    private fun checkAdbState(mayAsk: Boolean) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) return
        val legacy = BydOutputSettings.legacyVehicleProbe(this)
        // Only a validation this check interrupts runs again; a Dashboard switch needs no vehicle check.
        cancelAutomaticVehicleValidationForUserOperation(
            resumeAfter = automaticVehicleValidationInProgress || automaticVehicleValidationPending,
        )
        val generation = ++adbCheckGeneration
        adbCheckInProgress = true
        adbCheckMayAsk = mayAsk
        adbCheckFailed = false
        render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-adb-state") {
            val result = runCatching {
                if (legacy) {
                    BydAdbAccess.Status(backend.checkState(applicationContext, mayAsk))
                } else {
                    // The default battery path must publish its first sample before CarPlay reconnects.
                    backend.check(applicationContext, mayAsk)
                }
            }
            runOnUiThread {
                val current = generation == adbCheckGeneration
                if (current) {
                    adbCheckInProgress = false
                    adbCheckMayAsk = false
                }
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                adbCheckFailed = result.isFailure
                val status = result.getOrNull()
                adbAccessState = status?.state
                if (!legacy) defaultVehicleStatus = status
                if (!legacy && adbAccessState == BydAdbAccess.State.READY) {
                    vehicleProbeOutcome = null
                }
                render()
                // A check the driver asked for also says its result where they look: the status line
                // above the button changed so quietly that the button seemed to do nothing (#724).
                if (mayAsk) toast(status?.state?.let(::adbLinkStatusText) ?: brandString(R.string.adb_check_failed))
                // Unreadable data keeps the current connection; the page shows what is missing.
                if (!legacy && vehicleDataReconnectPending && status?.state == BydAdbAccess.State.READY &&
                    enabledVehicleDataReadable(null, status)) {
                    vehicleDataReconnectPending = false
                    reconnectForVehicleSetting()
                }
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    /** First probe or user retry: one click handles ADB authorization, probing and persistence. */
    private fun probeVehicleData(mayAsk: Boolean) =
        probeVehicleData(mayAsk, activateLegacyModeOnSuccess = false)

    private fun probeVehicleData(mayAsk: Boolean, activateLegacyModeOnSuccess: Boolean) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) return
        val expectedLegacyMode = BydOutputSettings.legacyVehicleProbe(this)
        val expectedSnapshot = cancelAutomaticVehicleValidationForUserOperation(resumeAfter = false)
        val generation = ++vehicleProbeGeneration
        vehicleProbeAuthorizationInProgress = mayAsk
        vehicleProbeInProgress = !mayAsk
        adbCheckFailed = false
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        Log.i(BYD_VEHICLE_TAG, "user vehicle probe starting mayAsk=$mayAsk")
        render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-byd13-probe") {
            val app = applicationContext
            val attempt = runCatching {
                val access = backend.checkState(app, mayAsk)
                if (access != BydAdbAccess.State.READY) {
                    VehicleProbeAttempt(BydVehicleProbeOutcome(access))
                } else {
                    runOnUiThread {
                        if (generation == vehicleProbeGeneration && !isFinishing && !isDestroyed) {
                            vehicleProbeAuthorizationInProgress = false
                            vehicleProbeInProgress = true
                            render()
                        }
                    }
                    var candidate = backend.probe(app, persist = false)
                    if (candidate.access == BydAdbAccess.State.NOT_APPROVED) {
                        // adbd confirms "Always allow" before it saves the key, so this new connection can be early.
                        Log.i(BYD_VEHICLE_TAG, "probe connection not approved yet; retrying once")
                        Thread.sleep(ADB_KEY_SAVE_WAIT_MILLIS)
                        candidate = backend.probe(app, persist = false)
                    }
                    val candidateCapabilities = candidate.capabilities
                    when {
                        candidate.access == BydAdbAccess.State.NOT_APPROVED -> VehicleProbeAttempt(
                            outcome = candidate,
                            allowedOnlyOnce = true,
                        )
                        candidateCapabilities == null -> VehicleProbeAttempt(candidate)
                        else -> {
                            // Invalidation must guard persistence/publication, not only the UI callback.
                            val replacement = synchronized(vehicleOperationLock) {
                                if (generation != vehicleProbeGeneration ||
                                    BydOutputSettings.legacyVehicleProbe(app) != expectedLegacyMode) null
                                else BydVehicleFieldStore.replaceAutomatically(
                                    app,
                                    expectedSnapshot,
                                    candidateCapabilities,
                                )
                            } ?: return@runCatching VehicleProbeAttempt(
                                outcome = BydVehicleProbeOutcome(candidate.access),
                                snapshotChanged = true,
                            )
                            when {
                                replacement.saved -> VehicleProbeAttempt(candidate)
                                replacement.snapshotChanged -> VehicleProbeAttempt(
                                    outcome = candidate,
                                    snapshotChanged = true,
                                )
                                else -> VehicleProbeAttempt(
                                    outcome = BydVehicleProbeOutcome(candidate.access),
                                    heldCandidate = candidateCapabilities,
                                    lostFields = replacement.lostFields,
                                )
                            }
                        }
                    }
                }
            }.getOrElse { error ->
                VehicleProbeAttempt(
                    BydVehicleProbeOutcome(
                        BydAdbAccess.State.READY,
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
            runOnUiThread {
                val current = generation == vehicleProbeGeneration
                if (current) {
                    vehicleProbeAuthorizationInProgress = false
                    vehicleProbeInProgress = false
                }
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                pendingVehicleReplacement = attempt.heldCandidate
                pendingVehicleReplacementExpected = expectedSnapshot
                pendingVehicleLostFields = attempt.lostFields
                vehicleProbeOutcome = when {
                    attempt.allowedOnlyOnce -> attempt.outcome.copy(
                        error = brandString(R.string.vehicle_probe_allowed_once),
                    )
                    attempt.snapshotChanged -> attempt.outcome.copy(
                        capabilities = null,
                        error = brandString(R.string.vehicle_probe_snapshot_changed),
                    )
                    else -> attempt.outcome
                }
                adbAccessState = attempt.outcome.access
                val activatedLegacyMode = activateLegacyModeOnSuccess &&
                    vehicleProbeOutcome?.capabilities != null
                if (activatedLegacyMode) {
                    BydOutputSettings.setLegacyVehicleProbe(this, true)
                }
                if (vehicleProbeOutcome?.capabilities != null) automaticVehicleValidationStarted = true
                Log.i(
                    BYD_VEHICLE_TAG,
                    "user vehicle probe access=${attempt.outcome.access} " +
                        "saved=${vehicleProbeOutcome?.capabilities != null} " +
                        "lost=${attempt.lostFields.joinToString()} " +
                        "error=${vehicleProbeOutcome?.error ?: "none"}",
                )
                render()
                if (activatedLegacyMode && vehicleDataSwitchesOn()) reconnectForVehicleSetting()
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    /**
     * After the first saved probe, validation is automatic and never asks for authorization. ADB
     * transport failure keeps the saved snapshot; only two complete READY-but-unreadable checks
     * trigger one automatic field re-probe.
     */
    private fun validateSavedVehicleConfigurationAutomatically() {
        if (!BydOutputSettings.legacyVehicleProbe(this)) return
        val saved = BydVehicleFieldStore.load(applicationContext) ?: return
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) {
            automaticVehicleValidationPending = true
            return
        }
        if (automaticVehicleValidationInProgress) {
            // A request during a run, such as a switch just turned on, runs once this one ends.
            automaticVehicleValidationPending = true
            return
        }
        if (automaticVehicleValidationStarted) return
        automaticVehicleValidationStarted = true
        automaticVehicleValidationInProgress = true
        val generation = ++vehicleValidationGeneration
        Log.i(BYD_VEHICLE_TAG, "automatic vehicle validation starting savedFirmware=${saved.firmwareKey}")
        if (page == "settings" && bydVehicleAdvancedExpanded) render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-byd13-auto-validate") {
            val app = applicationContext
            val validation = runCatching {
                var status = backend.check(app, mayAsk = false)
                var outcome: BydVehicleProbeOutcome? = null
                var heldCandidate: BydVehicleCapabilities? = null
                var lostFields: Set<BydVehicleField> = emptySet()
                var snapshotChanged = false
                var readable = status.state == BydAdbAccess.State.READY && enabledVehicleDataReadable(saved, status)
                if (status.state == BydAdbAccess.State.READY && !enabledVehicleDataReadable(saved, status)) {
                    Log.w(BYD_VEHICLE_TAG, "saved vehicle fields unreadable; validating once more")
                    try {
                        Thread.sleep(VEHICLE_VALIDATION_RETRY_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    // A cancelled validation reads nothing more alongside the operation that replaced it.
                    if (generation != vehicleValidationGeneration) {
                        return@runCatching VehicleValidationAttempt(
                            status = status,
                            snapshotChanged = true,
                        )
                    }
                    status = backend.check(app, mayAsk = false)
                    readable = status.state == BydAdbAccess.State.READY && enabledVehicleDataReadable(saved, status)
                    if (status.state == BydAdbAccess.State.READY && !readable) {
                        if (generation != vehicleValidationGeneration) {
                            return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                        }
                        Log.w(BYD_VEHICLE_TAG, "saved vehicle fields still unreadable; automatic re-probe starting")
                        val candidate = backend.probe(app, persist = false)
                        if (generation != vehicleValidationGeneration) {
                            return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                        }
                        outcome = candidate
                        candidate.capabilities?.let { next ->
                            val replacement = synchronized(vehicleOperationLock) {
                                if (generation != vehicleValidationGeneration) null
                                else BydVehicleFieldStore.replaceAutomatically(app, saved, next)
                            } ?: return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                            when {
                                replacement.saved -> Unit
                                replacement.snapshotChanged -> {
                                    snapshotChanged = true
                                    outcome = null
                                }
                                else -> {
                                    heldCandidate = next
                                    lostFields = replacement.lostFields
                                    outcome = BydVehicleProbeOutcome(candidate.access)
                                }
                            }
                        }
                    }
                }
                VehicleValidationAttempt(
                    status = status,
                    outcome = outcome,
                    heldCandidate = heldCandidate,
                    lostFields = lostFields,
                    snapshotChanged = snapshotChanged,
                    savedFieldsReadable = readable,
                )
            }
            runOnUiThread {
                val current = generation == vehicleValidationGeneration
                if (current) automaticVehicleValidationInProgress = false
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                adbCheckFailed = validation.isFailure
                validation.exceptionOrNull()?.let { Log.w(BYD_VEHICLE_TAG, "automatic vehicle validation failed", it) }
                validation.getOrNull()?.let { result ->
                    adbAccessState = result.outcome?.access ?: result.status.state
                    when {
                        result.snapshotChanged -> Unit
                        result.savedFieldsReadable && result.outcome == null -> {
                            // An open offer to replace stays: this check may not have read its lost fields.
                            vehicleProbeOutcome = null
                        }
                        result.lostFields.isNotEmpty() -> {
                            vehicleProbeOutcome = result.outcome
                            pendingVehicleReplacement = result.heldCandidate
                            pendingVehicleReplacementExpected = saved
                            pendingVehicleLostFields = result.lostFields
                        }
                        result.outcome != null -> {
                            vehicleProbeOutcome = result.outcome
                            pendingVehicleReplacement = null
                            pendingVehicleLostFields = emptySet()
                        }
                    }
                    Log.i(
                        BYD_VEHICLE_TAG,
                        "automatic vehicle validation access=${adbAccessState} " +
                            "reprobed=${result.outcome != null} " +
                            "saved=${result.outcome?.capabilities != null && result.lostFields.isEmpty()} " +
                            "lost=${result.lostFields.joinToString()} " +
                            "snapshotChanged=${result.snapshotChanged} " +
                            "error=${result.outcome?.error ?: "none"}",
                    )
                }
                if (page == "settings" && bydVehicleAdvancedExpanded) {
                    render()
                }
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    private fun scheduleAutomaticVehicleValidation() {
        if (!BydOutputSettings.legacyVehicleProbe(this)) {
            automaticVehicleValidationPending = false
            handler.removeCallbacks(automaticVehicleValidation)
            return
        }
        automaticVehicleValidationStarted = false
        automaticVehicleValidationPending = true
        handler.removeCallbacks(automaticVehicleValidation)
        handler.post(automaticVehicleValidation)
    }

    private fun cancelAutomaticVehicleValidationForUserOperation(
        resumeAfter: Boolean,
    ): BydVehicleCapabilities? = synchronized(vehicleOperationLock) {
        handler.removeCallbacks(automaticVehicleValidation)
        automaticVehicleValidationPending = resumeAfter
        if (resumeAfter) automaticVehicleValidationStarted = false
        vehicleValidationGeneration++
        automaticVehicleValidationInProgress = false
        BydVehicleFieldStore.load(applicationContext)
    }

    private fun runPendingAutomaticVehicleValidation() {
        if (!automaticVehicleValidationPending || adbSwitchChangePending ||
            vehicleAdbWorkInProgress() || automaticVehicleValidationInProgress) return
        handler.removeCallbacks(automaticVehicleValidation)
        handler.post(automaticVehicleValidation)
    }

    /** Whether [status] has every reading an enabled switch needs; null [capabilities] is default mode. */
    private fun enabledVehicleDataReadable(
        capabilities: BydVehicleCapabilities?,
        status: BydAdbAccess.Status,
    ): Boolean {
        if (BydOutputSettings.batteryToIphone(this) && capabilities?.batterySupported != false &&
            (status.batteryPercent == null || status.rangeKm == null)) return false
        if (BydOutputSettings.wheelSpeedToIphone(this) && capabilities?.motionSupported != false &&
            (status.speedKmh == null || status.gear == null)) return false
        if (BydOutputSettings.videoWhileParked(this) && capabilities?.gearSupported != false && status.gear == null) return false
        return true
    }

    private fun adbLinkStatusText(state: BydAdbAccess.State): String = when (state) {
        BydAdbAccess.State.READY -> brandString(R.string.adb_access_ready)
        BydAdbAccess.State.NOT_APPROVED -> brandString(R.string.adb_enabled_not_approved)
        BydAdbAccess.State.ADB_OFF -> brandString(R.string.adb_off)
        BydAdbAccess.State.PAIRING_ONLY -> brandString(R.string.adb_pairing_only)
    }

    private fun gearLetter(value: Int): String = when (value) {
        1 -> "P"
        2 -> "R"
        3 -> "N"
        else -> "D"
    }

    private fun reconnectForVehicleSetting() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun vehicleDataSwitchesOn() = BydOutputSettings.batteryToIphone(this) ||
        BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this)

    private fun hasPreciseLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // The cluster screen is described at connection time, so a running session reconnects over
    // its current link. The position choices need no call: brandString(R.string.apply_and_reconnect) already does it.
    private fun clusterSafeAreaControls(card: LinearLayout) {
        val rect = AirPlayPersistence.loadClusterSafeAreaRect(this)
            ?: DiLink4ClusterDisplay.defaultSafeAreaRect(
                markerXPercent = AirPlayPersistence.loadClusterMarkerXPercent(this),
                markerYPercent = AirPlayPersistence.loadClusterMarkerYPercent(this))
        card.addView(label(brandString(R.string.safe_area_mapping_summary,
            rect.width, rect.height, rect.left, rect.top, 1920, 720), 14, MUTED))
        card.addView(actionButton(brandString(R.string.cluster_safe_area_edit), false) {
            openClusterSafeAreaEditor()
        }, matchButton(10, 56))
        card.addView(button(brandString(R.string.cluster_safe_area_reset), false) {
            AirPlayPersistence.clearClusterSafeAreaRect(this)
            render()
            markReconnectNeeded()
        }, matchButton(10, 56))
        card.addView(label(brandString(R.string.cluster_safe_area_hint), 14, MUTED))
    }

    private fun openClusterSafeAreaEditor() {
        clusterSafeAreaDialog?.dismiss()
        val previewOwner = Any()
        val initial = AirPlayPersistence.loadClusterSafeAreaRect(this)
            ?: DiLink4ClusterDisplay.defaultSafeAreaRect(
                markerXPercent = AirPlayPersistence.loadClusterMarkerXPercent(this),
                markerYPercent = AirPlayPersistence.loadClusterMarkerYPercent(this))
        val editor = SafeAreaEditorView(this).apply {
            setBackgroundColor(SURFACE)
            setRect(initial, 1920, 720)
        }
        // A standalone dialog gives the weighted preview an exact available height.
        // AlertDialog's wrap-content custom panel can collapse it to zero.
        val dialog = appDialog().apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val panel = column().apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(SURFACE)
        }
        panel.addView(label(brandString(R.string.cluster_safe_area_edit), 18, TEXT, true))
        panel.addView(label(brandString(R.string.cluster_safe_area_live_hint), 14, MUTED))
        panel.addView(ClusterSafeAreaPreviewFrame(this, editor), LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = row()
        actions.addView(button(brandString(R.string.cancel), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(56), 1f))
        actions.addView(button(brandString(if (CarPlayBackgroundSession.hasSession())
            R.string.apply_and_reconnect else R.string.save), true) {
            editor.currentRectForSource()?.let { AirPlayPersistence.saveClusterSafeAreaRect(this, it) }
            dialog.dismiss()
            render()
            reconnectIfRunning()
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        panel.addView(actions)
        dialog.setContentView(panel, ViewGroup.LayoutParams(-1, -1))
        editor.onRectChanged = { ClusterActivityOutput.updateSafeAreaPreview(previewOwner, it) }
        clusterSafeAreaDialog = dialog
        dialog.setOnDismissListener {
            editor.onRectChanged = null
            ClusterActivityOutput.endSafeAreaPreview(previewOwner)
            if (clusterSafeAreaDialog === dialog) clusterSafeAreaDialog = null
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(),
            (resources.displayMetrics.heightPixels * 0.85f).toInt())
        ClusterActivityOutput.beginSafeAreaPreview(previewOwner, initial, this)
    }

    private fun reconnectIfRunning() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    // Saved without dropping CarPlay; the reconnect bar lets the driver choose when.
    private fun markReconnectNeeded() {
        CarPlayBackgroundSession.snapshot()?.controller?.let(PendingReconnect::mark)
        refreshReconnectBar()
    }

    private fun refreshReconnectBar() {
        reconnectBar?.visibility =
            if (PendingReconnect.isPending(CarPlayBackgroundSession.snapshot()?.controller)) View.VISIBLE else View.GONE
    }

    private fun reconnectBar(): View = row().apply {
        val wide = resources.configuration.screenWidthDp >= SETTINGS_RECONNECT_HORIZONTAL_MIN_WIDTH_DP
        orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(RAIL_SELECTED, RAIL_SELECTED_BORDER)
        setPadding(dp(SETTINGS_RECONNECT_SIDE_PADDING_DP), dp(SETTINGS_RECONNECT_VERTICAL_PADDING_DP),
            dp(SETTINGS_RECONNECT_END_PADDING_DP), dp(SETTINGS_RECONNECT_VERTICAL_PADDING_DP))
        val message = label(brandString(R.string.settings_reconnect_pending), UiTypography.BODY_SP, TEXT).apply {
            maxLines = SETTINGS_RECONNECT_MESSAGE_MAX_LINES
            setLineSpacing(dp(1).toFloat(), 1f)
        }
        addView(message, LinearLayout.LayoutParams(if (wide) 0 else -1, -2, if (wide) 1f else 0f))
        val action = button(brandString(R.string.settings_reconnect_now), true) {
            // No clear(): if connect() stops early (hotspot off, a permission prompt), the change is still pending.
            reconnectIfRunning()
        }.apply {
            textSize = UiTypography.BODY_SP.toFloat()
            minHeight = dp(SETTINGS_RECONNECT_ACTION_HEIGHT_DP)
            setPadding(dp(SETTINGS_RECONNECT_ACTION_SIDE_PADDING_DP), 0,
                dp(SETTINGS_RECONNECT_ACTION_SIDE_PADDING_DP), 0)
            setSingleLine(true)
        }
        addView(action, LinearLayout.LayoutParams(-2, dp(SETTINGS_RECONNECT_ACTION_HEIGHT_DP)).apply {
            if (wide) marginStart = dp(SETTINGS_RECONNECT_CONTENT_GAP_DP)
            else {
                topMargin = dp(SETTINGS_RECONNECT_STACK_GAP_DP)
                gravity = Gravity.END
            }
        })
        reconnectBar = this
        refreshReconnectBar()
    }

    private fun onLocationReportingChanged(enabled: Boolean) {
        if (enabled && !hasPreciseLocation()) {
            // Keep the switch off until precise location is actually granted.
            render()
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
            return
        }
        applyLocationReporting(enabled)
    }

    private fun applyLocationReporting(enabled: Boolean) {
        if (AirPlayPersistence.loadLocationReportingEnabled(this) == enabled) return
        AirPlayPersistence.saveLocationReportingEnabled(this, enabled)
        render()
        // Location support is advertised during iAP2 identification, so both enabling and
        // disabling it require a new session. The host also refreshes its location service type.
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
        else toast(brandString(R.string.saved_for_your_next_connection))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        startupHotspotCancelled = true
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast(brandString(R.string.saved_for_your_next_connection))
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(appDialogContext()).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        appDialogBuilder().setTitle(title).setView(input)
            .setPositiveButton(brandString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(brandString(R.string.cancel), null).show()
    }

    private fun resolutionSettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = brandString(titleId)
        fun summary() = brandString(R.string.contrib_audio_home_choice_summary, title, brandString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(appDialogContext()).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(brandString(hintId), 14, MUTED))
            val dialog = appDialogBuilder().setTitle(title).setView(fields)
                .setPositiveButton(brandString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(brandString(R.string.cancel), null)
                .setNeutralButton(brandString(R.string.resolution_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = brandString(R.string.resolution_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showResolutionSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showResolutionSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    private fun nightDelaySettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = brandString(titleId)
        fun summary() = brandString(R.string.contrib_audio_home_choice_summary, title, brandString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(appDialogContext()).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(brandString(hintId), 14, MUTED))
            val dialog = appDialogBuilder().setTitle(title).setView(fields)
                .setPositiveButton(brandString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(brandString(R.string.cancel), null)
                .setNeutralButton(brandString(R.string.ambient_light_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = brandString(R.string.custom_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun ambientLightThresholdControl(parent: LinearLayout) {
        val title = brandString(R.string.ambient_light_threshold_title)
        fun summary(): String = brandString(
            R.string.contrib_audio_home_choice_summary,
            title,
            brandString(R.string.ambient_light_threshold_summary, AirPlayPersistence.loadAmbientLightThreshold(this).lux),
        )

        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(appDialogContext()).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(AirPlayPersistence.loadAmbientLightThreshold(this@DiPlayActivity).lux.toString())
            }
            fields.addView(input)
            fields.addView(label(brandString(R.string.ambient_light_threshold_hint), 14, MUTED))
            val dialog = appDialogBuilder()
                .setTitle(title)
                .setView(fields)
                .setPositiveButton(brandString(R.string.save), null)
                .setNegativeButton(brandString(R.string.cancel), null)
                .setNeutralButton(brandString(R.string.ambient_light_reset_defaults), null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener saveThreshold@{
                    val lux = input.text.toString().trim().toIntOrNull()
                    if (lux == null || !AmbientLightThreshold.isValid(lux)) {
                        input.error = brandString(R.string.ambient_light_threshold_error)
                        return@saveThreshold
                    }
                    AirPlayPersistence.saveAmbientLightThreshold(this, AmbientLightThreshold(lux))
                    control.text = summary()
                    dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(AmbientLightThreshold.DEFAULT_LUX.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showNightModeSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    /** Where CarPlay puts its dock; between the two fixed edges it moves at once, otherwise on reconnect. */
    private fun carPlayDockControl(card: LinearLayout) {
        val docks = CarPlayDock.entries
        choice(card, brandString(R.string.carplay_dock), listOf(
            brandString(R.string.carplay_dock_automatic),
            brandString(R.string.carplay_dock_driver_side),
            brandString(R.string.carplay_dock_bottom),
        ), docks.indexOf(CarPlayDock.load(this)), reconnects = false) {
            val from = CarPlayDock.load(this)
            val to = docks[it]
            CarPlayDock.save(this, to)
            val session = CarPlayBackgroundSession.snapshot()
            val areas = session?.display?.viewAreas
            val target = to.edge?.let { areas?.withDock(it) }
            if (CarPlayDock.movesLive(from, to) && areas != null && target != null) {
                if (session.controller.showViewArea(target)) areas.use(target)
            } else {
                markReconnectNeeded()
            }
        }
        card.addView(label(brandString(R.string.carplay_dock_hint), 14, MUTED).apply { setPadding(0, 0, 0, dp(18)) })
    }

    private fun carButtonControls(parent: LinearLayout) {
        val preset = VehicleBranding.preset(this)
        val hasCustomIcon = AirPlayPersistence.loadCustomAirPlayIconFile(this) != null
        val preview = row().apply { gravity = Gravity.CENTER_VERTICAL }
        preview.addView(ImageView(this).apply {
            setImageDrawable(VehicleBranding.logo(this@DiPlayActivity))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(SURFACE, BORDER)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(16) })
        val text = column()
        text.addView(label(brandString(R.string.car_button_icon), 18, TEXT, true))
        val detail = when (preset) {
            VehicleBrandPreset.LINCOLN, VehicleBrandPreset.FORD -> VehicleBranding.displayName(this)
            VehicleBrandPreset.CUSTOM -> listOf(
                brandString(R.string.car_button_brand_custom),
                VehicleBranding.displayName(this),
            ).distinct().joinToString(" · ")
        }
        text.addView(label(detail, 14, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        preview.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(preview)

        val presets = VehicleBrandPreset.entries
        choice(
            parent,
            brandString(R.string.car_button_vehicle_brand),
            presets.map {
                when (it) {
                    VehicleBrandPreset.LINCOLN -> brandString(R.string.car_button_brand_lincoln)
                    VehicleBrandPreset.FORD -> brandString(R.string.car_button_brand_ford)
                    VehicleBrandPreset.CUSTOM -> brandString(R.string.car_button_brand_custom)
                }
            },
            presets.indexOf(preset),
            reconnects = false,
        ) {
            VehicleBranding.setPreset(this, presets[it])
            refreshCarButton()
            carButtonSaved()
        }

        parent.addView(button(brandString(R.string.choose_image), false) {
            launchCarButtonImagePicker(
                openDocument = { iconDocumentPicker.launch(arrayOf("image/*")) },
                getContent = { iconPicker.launch("image/*") },
                documentPickerIsSystem = documentPickerIsSystem(),
            ).onFailure { toast(brandString(R.string.this_head_unit_has_no_image_picker)) }
        }, matchButton(16, 60))
        if (preset == VehicleBrandPreset.CUSTOM && hasCustomIcon) parent.addView(button(brandString(R.string.default_icon), false) {
            AirPlayPersistence.clearCustomAirPlayIcon(this)
            VehicleBranding.setPreset(this, VehicleBrandPreset.LINCOLN)
            refreshCarButton()
            carButtonSaved()
        }, matchButton(10, 60))
        val name = AirPlayPersistence.loadOemLabel(this)
        parent.addView(button("${brandString(R.string.car_button_name)} · $name", false) {
            textInput(brandString(R.string.car_button_name), name, secret = false) {
                AirPlayPersistence.saveOemLabel(this, it)
                VehicleBranding.setPreset(this, VehicleBrandPreset.CUSTOM)
                refreshCarButton()
                carButtonSaved()
            }
        }, matchButton(10, 60))
        parent.addView(label(brandString(R.string.car_button_description), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun carButtonSaved() {
        markReconnectNeeded()
        if (CarPlayBackgroundSession.hasSession()) toast(brandString(R.string.car_button_saved_next_connection))
    }

    // Rebuilds only this card: render() would scroll the page back to the top.
    private fun refreshCarButton() {
        val card = carButtonCard ?: return
        card.removeViews(1, card.childCount - 1)
        carButtonControls(card)
        normalizeSpacing(card)
        if (page == "settings") polishSettingsRows(card)
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, brandString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(brandString(R.string.changes_the_size_of_carplay_icons_and_text_applying_a_size), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun scheduleTimeControl(parent: LinearLayout, start: Boolean) {
        val title = brandString(if (start) R.string.carplay_night_start else R.string.carplay_night_end)
        fun labelFor(minute: Int) = "$title · ${String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60)}"
        val initial = AirPlayPersistence.loadCarPlayNightSchedule(this)
        val control = button(labelFor(if (start) initial.startMinute else initial.endMinute), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadCarPlayNightSchedule(this)
            val selected = if (start) current.startMinute else current.endMinute
            TimePickerDialog(this, appDialogTheme(), { _, hour, minute ->
                val updatedMinute = hour * 60 + minute
                val other = if (start) current.endMinute else current.startMinute
                if (updatedMinute == other) {
                    toast(brandString(R.string.carplay_night_schedule_same_time))
                } else {
                    val updated = if (start) current.copy(startMinute = updatedMinute)
                        else current.copy(endMinute = updatedMinute)
                    AirPlayPersistence.saveCarPlayNightSchedule(this, updated)
                    control.text = labelFor(updatedMinute)
                }
            }, selected / 60, selected % 60, true).show()
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun connect(wireless: Boolean) = connect(wireless, returnHomeOnStart = false)

    private fun connect(wireless: Boolean, returnHomeOnStart: Boolean) {
        startupHotspotCancelled = true
        if (wireless && pendingCarHotspotSetup) { toast(brandString(R.string.save_your_hotspot_details_in_connection_setup_first)); page = "connection"; render(); return }
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            page = "connection"
            render()
            toast(brandString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.EXISTING_WIFI &&
            (AirPlayPersistence.loadExistingWifiPassphrase(this).length !in 8..63 ||
                hotspotError(AirPlayPersistence.loadExistingWifiSsid(this), AirPlayPersistence.loadExistingWifiPassphrase(this)) != null)) {
            askExistingWifiNetwork()
            return
        }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(returnHomeOnStart); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true
            pendingWirelessReturnHome = returnHomeOnStart
            choosePhone()
            return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationReturnHome = returnHomeOnStart
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            if (returnHomeOnStart && page != "home") {
                // A deliberate Connect action leaves configuration behind immediately so the
                // driver sees connection progress. The first main-screen frame then replaces this
                // same home with CarPlay; background/setting-triggered reconnects keep their page.
                returnToCarPlay = false
                connectionSettingsReturnCategory = null
                setupFromSettings = false
                setupStep = SetupGuide.STEP_CAR
                page = "home"
                render()
            }
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        openProjectionWhenReady = true
        if (!projectionStarter()) openProjectionWhenReady = false
        onProjectionStateChanged()
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            // Android here has no Bluetooth for apps at all, so there is no switch to send the driver to.
            pendingWireless = false
            pendingWirelessReturnHome = false
            appDialogBuilder().setTitle(brandString(R.string.wireless_carplay_2))
                .setMessage(brandString(R.string.bluetooth_cannot_open_connections))
                .setPositiveButton(brandString(R.string.got_it), null).show(); return
        }
        if (!adapter.isEnabled) {
            // Back from Bluetooth settings and still off: on some head units the switch will not stay on.
            val message = brandString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first) +
                if (bluetoothSettingsVisited) "\n\n" + brandString(R.string.bluetooth_will_not_stay_on_hint) else ""
            appDialogBuilder().setTitle(brandString(R.string.turn_on_bluetooth))
                .setMessage(message)
                .setPositiveButton(brandString(R.string.open_bluetooth)) { _, _ ->
                    bluetoothSettingsVisited = true
                    openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                .setNegativeButton(brandString(R.string.later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            appDialogBuilder().setTitle(brandString(R.string.pair_your_iphone))
                .setMessage(brandString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(brandString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(brandString(R.string.got_it), null).show(); return
        }
        appDialogBuilder().setTitle(brandString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: brandString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless
                val returnHome = pendingWirelessReturnHome
                pendingWireless = false
                pendingWirelessReturnHome = false
                render()
                if (start) connect(true, returnHome)
            }.setNeutralButton(brandString(R.string.pair_another)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(brandString(R.string.cancel)) { _, _ ->
                pendingWireless = false
                pendingWirelessReturnHome = false
            }.show()
    }

    private fun wirelessHelp() {
        appDialogBuilder().setTitle(brandString(R.string.wireless_connection_help))
            .setMessage(brandString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(brandString(R.string.got_it), null)
            .setNeutralButton(brandString(R.string.ui_glass_wifi_reset_short)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        appDialogBuilder().setTitle(brandString(R.string.reset_carplay_wi_fi_2))
            .setMessage(brandString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(brandString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(brandString(R.string.cancel), null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(brandString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.closeCompat(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.closeCompat(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.closeCompat(); toast(brandString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.closeCompat(); toast(brandString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            channel.closeCompat(); permissionHelp(brandString(R.string.wireless_permissions), brandString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession() || projectionIsStarting
        val connected = running && mainProjectionReady
        setHomeText(homeHeadline, brandString(when {
            projectionNeedsRetry || projectionNeedsWifiReset -> R.string.ui_glass_connection_incomplete
            connected -> R.string.carplay_connected
            running -> R.string.ui_glass_connecting_title
            else -> R.string.ui_glass_home_title
        }))
        homeConnectionFooter?.visibility=if(running)View.GONE else View.VISIBLE
        homeConnectionRing?.state(connecting = running && !connected && !projectionNeedsRetry && !projectionNeedsWifiReset,
            completed = connected)
        setHomeText(status, when {
            setupError != null -> brandString(R.string.setup_needs_attention)
            connected -> ""
            running -> projectionStatus?.takeIf { it.isNotBlank() } ?: brandString(R.string.ui_glass_connecting_hint)
            DiPlayPreferences.phoneAddress(this) != null -> brandString(R.string.ui_glass_paired_waiting)
            else -> brandString(R.string.ready_when_you_are)
        })
        status?.contentDescription = projectionStatusDetails ?: status?.text
        refreshHomeActionLabels()
        if (lastRunning != running) {
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        (if (running) disconnectButton else homeConnectionFooter)?.bringToFront()
        connectButton?.isEnabled = setupError == null && (!running || connected || projectionNeedsRetry || projectionNeedsWifiReset)
        refreshReconnectBar()
    }
    private fun refreshHomeActionLabels(width: Int = homeActionWidth) {
        homeActionWidth = width
        val compact = width > 0 && ConnectionPageLayout.compactActions(this, width)
        val running = CarPlayBackgroundSession.hasSession() || projectionIsStarting
        val connected = running && mainProjectionReady
        setHomeText(homeConnectionFooter, homeConnectionModeLabel(compact = true))
        homeConnectionFooter?.contentDescription = homeConnectionModeLabel()
        setHomeText(disconnectButton, brandString(if (connected) R.string.home_disconnect_carplay else R.string.home_cancel_connection))
        disconnectButton?.contentDescription = disconnectButton?.text
        connectButton?.contentDescription = brandString(when {
            projectionNeedsWifiReset -> R.string.reset_carplay_wi_fi
            projectionNeedsRetry -> R.string.retry_carplay_connection
            connected -> R.string.open_carplay
            running -> R.string.ui_glass_connecting_title
            else -> R.string.ui_glass_connect_carplay
        })
        setHomeText(connectButton, brandString(when {
            projectionNeedsWifiReset -> if (compact) R.string.ui_glass_wifi_reset_short else R.string.reset_carplay_wi_fi
            projectionNeedsRetry -> if (compact) R.string.ui_glass_retry_short else R.string.retry_carplay_connection
            connected -> R.string.open_carplay
            running -> R.string.ui_glass_connecting_action
            else -> R.string.ui_glass_connect_carplay
        }))
    }

    private fun setHomeText(view: TextView?, text: CharSequence) {
        if (view != null && !android.text.TextUtils.equals(view.text, text)) view.text = text
    }

    private fun authorizeClusterRouting() {
        val app = applicationContext
        Thread({
            val result = runCatching {
                com.shilapi.xcertplay.adb.LocalAdb(com.shilapi.xcertplay.adb.AdbKeys.load(app)).use {
                    it.connect(mayAsk = true)
                }
            }.getOrNull()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    toast(if (result == com.shilapi.xcertplay.adb.LocalAdb.Access.READY)
                        brandString(R.string.adb_access_ready) else brandString(R.string.adb_not_approved))
                    if (result == com.shilapi.xcertplay.adb.LocalAdb.Access.READY) ClusterActivityOutput.retry()
                }
            }
        }, "adb-cluster-authorize").start()
    }

    private fun reportFileName() = "Lincoln-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun saveReportToDownloads() {
        if (exportInProgress) return
        if (DiagnosticExportStore.needsStoragePermission(this)) {
            storagePermissionAction = { exportDiagnostics() }
            runCatching { reportStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                .onFailure {
                    storagePermissionAction = null
                    exportDiagnostics()
                }
        } else exportDiagnostics()
    }

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        if (exportInProgress) return
        runCatching { export.launch(reportFileName()) }.onFailure { exportDiagnostics() }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = brandString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = diagnosticReportText(appContext)
                val savedReport = if (uri != null) {
                    DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                    DiagnosticExportStore.SavedReport(uri)
                } else DiagnosticExportStore.saveWithoutPicker(appContext, fileName, report)
                savedReport to report
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = brandString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val (savedReport, report) = result.getOrThrow()
                    showSavedDiagnosticReport(savedReport, report, when {
                        savedReport.savedInApp -> brandString(R.string.diagnostic_report_saved_in_app)
                        savedReport.savedPath != null -> brandString(R.string.diagnostic_report_saved_to_path, savedReport.savedPath)
                        uri == null -> "Downloads/Lincoln/$fileName"
                        else -> brandString(R.string.your_report_was_saved_to_the_selected_location)
                    })
                } else {
                    appDialogBuilder().setTitle(brandString(R.string.could_not_save_the_report))
                        .setMessage(brandString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(brandString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(brandString(R.string.close), null).show()
                }
            }
        }, "diplay-export").start()
    }

    private fun showSavedDiagnosticReport(savedReport: DiagnosticExportStore.SavedReport, report: String, location: String): AlertDialog =
        appDialogBuilder().setTitle(brandString(R.string.diagnostic_report_saved))
            .setMessage(location)
            .setPositiveButton(brandString(R.string.view_diagnostic_report)) { _, _ -> showDiagnosticReport(report) }
            .setNegativeButton(brandString(R.string.done), null)
            .setNeutralButton(brandString(R.string.share)) { _, _ ->
                runCatching {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedReport.uri)
                        clipData = android.content.ClipData.newRawUri(brandString(R.string.report_clip_label), savedReport.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, brandString(R.string.share_diagnostic_report)))
                }.onFailure { showDiagnosticReport(report) }
            }.show()

    /** The text of a diagnostic report. It reads the session logs, so call it off the main thread. */
    private fun diagnosticReportText(appContext: Context): String = buildString {
        appendLine("Lincoln ${version()} · diagnostic report")
        appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
        AirPlayPersistence.loadUsbChargingCurrent(appContext).let {
            appendLine("USB charging offer: ${it.key} (${it.milliAmps} mA)")
        }
        appendLine("Authentication: local experimental beta identity; no remote fallback")
        appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
        appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
        appendLine("Media-key audio focus: ${if (AirPlayPersistence.loadDisableMediaKeyAudioFocus(appContext)) "disabled" else "enabled"}")
        appendLine("MTK decoder tuning preference: ${if (AirPlayPersistence.loadMtkDecoderTuning(appContext)) "enabled" else "disabled"}")
        appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
        appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
        appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
        appendLine(PhoneWakeDiagnostics.report(appContext))
        appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
        appendLine()
        appendLine("--- Current cluster display diagnostics (even when disabled) ---")
        appendLine(ClusterMapPresentation.diagnosticReport(appContext))
        appendLine()
        appendLine("--- ADB cluster activity routing ---")
        appendLine("adbClusterActivityEnabled=${AirPlayPersistence.loadAdbClusterEnabled(appContext)}")
        appendLine("clusterActivityMainTask=${ClusterActivityOutput.mainTaskId} surfaceValid=${ClusterActivityOutput.surface?.isValid}")
        AdbClusterRouter.report(appContext).lineSequence().forEach { line ->
            DiagnosticRedactor.redact(line)?.let { appendLine(it) }
        }
        appendLine()
        appendLine("--- Standalone HUD compatibility ---")
        appendLine(BydOutputSettings.standaloneHudDiagnosticReport(appContext))
        appendLine()
        appendLine("--- BYD vehicle-data probe ---")
        appendLine(
            "mode=${if (BydOutputSettings.legacyVehicleProbe(appContext)) "legacy-probe" else "default"} " +
                "switches location=${AirPlayPersistence.loadLocationReportingEnabled(appContext)} " +
                "battery=${BydOutputSettings.batteryToIphone(appContext)} " +
                "wheelSpeed=${BydOutputSettings.wheelSpeedToIphone(appContext)} " +
                "parkedVideo=${BydOutputSettings.videoWhileParked(appContext)}",
        )
        val bydCapabilities = BydVehicleFieldStore.load(appContext)
        if (bydCapabilities == null) {
            appendLine("no saved successful probe")
        } else {
            appendLine(
                "catalog=${bydCapabilities.catalogAvailable} detectedAt=${bydCapabilities.detectedAtMillis} " +
                    "savedFirmware=${bydCapabilities.firmwareKey} " +
                    "currentFirmware=${BydVehicleFieldStore.firmwareKey()}",
            )
            for (field in BydVehicleField.entries) {
                val probe = bydCapabilities.result(field)
                appendLine("${field.name}: supported=${probe.supported} " +
                    (probe.address?.let { "tx=${it.transaction} dev=${it.device} fid=${it.fid} source=${it.source}" }
                        ?: "address=none"))
            }
        }
        appendLine()
        appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
        appendLine(DisplayDiagnosticSnapshot.report(appContext))
        appendLine()
        appendLine("--- Last received boot and app-launch result ---")
        appendLine(StartupDiagnosticSnapshot.report(appContext))
        appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
            "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
        appendLine("Car hotspot control: autoEnable=${CarHotspotSettings.enabled(appContext)} " +
            "writeSettings=${CarHotspotTethering.permitted(appContext)} " +
            "bydHeadUnit=${runCatching { CarHotspotSetup.isBydHeadUnit(appContext) }.getOrNull()} " +
            "hotspotOn=${runCatching { com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext) }.getOrNull()}")
        appendLine(WheelKeyService.report(appContext))
        WheelKeyLog.report(WheelKeyService.connected()).forEach { appendLine(it) }
        appendLine()
        appendLine(UpdateAvailability.report(appContext, System.currentTimeMillis()))
        appendLine()
        appendLine("--- Recent own-app process exits (Android 11+) ---")
        appendLine(ProcessExitDiagnostics.report(appContext))
        appendLine()
        appendLine("--- Last crash recorded by DiPlay ---")
        appendLine(CrashRecord.report(appContext))
        appendLine()
        val logsCompleted = AsyncDiagnosticLog.flush(AsyncDiagnosticLog.FLUSH_TIMEOUT_MILLIS)
        appendLine("${AsyncDiagnosticLog.status()} pendingCompleted=$logsCompleted")
        appendLine()
        for (name in SessionLogFile.REPORT_NAMES) {
            val file = File(appContext.filesDir, "logs/$name")
            if (file.isFile) {
                appendLine("--- $name ---")
                file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
            }
        }
    }

    private fun showDiagnosticReport(report: String) {
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(brandString(R.string.diagnostic_report_copy_hint), 14, MUTED))
        body.addView(label(report, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        appDialogBuilder().setTitle(brandString(R.string.report_clip_label))
            .setView(ScrollView(appDialogContext()).apply { addView(body) })
            .setPositiveButton(brandString(R.string.close), null).show()
    }
    private fun permissionHelp(title: String, body: String) {
        appDialogBuilder().setTitle(title).setMessage(body).setPositiveButton(brandString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(brandString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(brandString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun playTestTone(streamType: Int) {
        toneStop?.let { handler.removeCallbacks(it) }
        toneStop = null
        testToneTrack?.let { runCatching { it.stop(); it.release() } }
        testToneTrack = null
        var candidate: AudioTrack? = null
        val track = try {
            val pcm = assets.open("navigation_test.pcm").use { it.readBytes() }
            AudioTrack(streamType, 44100, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM).also {
                candidate = it
                check(it.state == AudioTrack.STATE_INITIALIZED)
                check(it.write(pcm, 0, pcm.size) == pcm.size)
                it.play()
            }
        } catch (error: Exception) {
            val state = candidate?.state ?: AudioTrack.STATE_UNINITIALIZED
            candidate?.let { runCatching { it.release() } }
            Log.w("DiPlay", "playTestTone streamType=$streamType unavailable", error)
            toast(brandString(R.string.audio_stream_unavailable, streamType, state))
            return
        }
        Log.i("DiPlay", "playTestTone streamType=$streamType state=${track.state} playState=${track.playState}")
        testToneTrack = track
        val stop = Runnable {
            track.stop()
            track.release()
            if (testToneTrack === track) testToneTrack = null
            toneStop = null
        }
        toneStop = stop
        handler.postDelayed(stop, 4500)
    }

    private val channelButtons = mutableListOf<Button>()

    private fun paintChannel(index: Int, selected: Boolean) {
        val target = channelButtons.getOrNull(index) ?: return
        target.isSelected = selected
        target.setTextColor(if (selected) ON_ACCENT else TEXT)
        target.background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(RIPPLE),
            buttonShape(if (selected) ACCENT else SURFACE, if (selected) ACCENT else BORDER, target.height),
            null
        )
    }

    private fun channelSelector(): ViewGroup {
        channelButtons.clear()
        val grid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 3
            setPadding(0, dp(8), 0, dp(8))
        }
        for (i in 0..20) {
            val btn = Button(this).apply {
                text = i.toString()
                isAllCaps = false
                textSize = 16f
                minHeight = dp(48)
                stateListAnimator = null
                setOnClickListener {
                    val previous = navigationStreamType
                    navigationStreamType = i
                    if (previous != i) {
                        paintChannel(previous, false)
                        paintChannel(i, true)
                    }
                    playTestTone(i)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(btn, params)
            channelButtons.add(btn)
            paintChannel(i, i == navigationStreamType)
        }
        return grid
    }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun languageSettings(content: LinearLayout) {
        filteredSection(content, SettingsSection.LANGUAGE,
            brandString(R.string.language_section_title), R.drawable.ic_dp_language) { card ->
            card.addView(settingsNote(brandString(R.string.language_hint), 14, MUTED))
            val current = AppLocale.preference(this)
            val languageButton = button("${brandString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}", false) { }
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12, 60))
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null,
        showHeading: Boolean = true, build: (LinearLayout) -> Unit) {
        searchIndexSink?.add(title)
        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(FidelitySettingsMetrics.SECTION_GAP_DP)) }
        if (icon != null) heading.addView(ImageView(this).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(FidelitySettingsMetrics.SECTION_ICON_DP), dp(FidelitySettingsMetrics.SECTION_ICON_DP)).apply { marginEnd = dp(FidelitySettingsMetrics.ROW_GAP_DP) })
        heading.addView(label(title, UiTypography.SECTION_SP, TEXT, true,
            centreGlyphs = icon != null && usesChineseGlyphAlignment(resources.configuration.locales[0].language, title)),
            LinearLayout.LayoutParams(0, -2, 1f))
        if (showHeading) card.addView(heading)
        build(card)
        if (page == "settings") {
            normalizeSpacing(card);polishSettingsRows(card)
            if (!showHeading) card.setPadding(
                dp(FidelitySettingsMetrics.CARD_PAD_DP), dp(FidelitySettingsMetrics.ROW_VERTICAL_DP),
                dp(FidelitySettingsMetrics.CARD_PAD_DP), dp(FidelitySettingsMetrics.ROW_VERTICAL_DP))
            if(settingsCategory==SettingsCategory.DISPLAY && title==brandString(R.string.display_and_performance)) {
                heading.visibility=View.GONE
                if(!isExpandedSettingsLayout)card.setPadding(dp(FidelitySettingsMetrics.ROW_GAP_DP),dp(FidelitySettingsMetrics.ROW_GAP_DP),dp(FidelitySettingsMetrics.ROW_GAP_DP),dp(FidelitySettingsMetrics.ROW_GAP_DP))
            }
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }
    private fun audioFocusControls(parent: LinearLayout) {
        toggle(parent, brandString(R.string.settings_disable_media_key_audio_focus),
            brandString(R.string.settings_disable_media_key_audio_focus_description),
            AirPlayPersistence.loadDisableMediaKeyAudioFocus(this)) {
            AirPlayPersistence.saveDisableMediaKeyAudioFocus(this, it)
            markReconnectNeeded()
        }
        val enabled = AirPlayPersistence.loadAudioFocusEnabled(this)
        val dependent = column().apply { visibility = if (enabled) View.VISIBLE else View.GONE }
        toggle(parent, brandString(R.string.contrib_audio_home_toggle_audio_focus),
            brandString(R.string.contrib_audio_home_toggle_audio_focus_desc), enabled) {
            AirPlayPersistence.saveAudioFocusEnabled(this, it)
            markReconnectNeeded()
            dependent.visibility = if (it) View.VISIBLE else View.GONE
        }
        toggle(dependent, brandString(R.string.audio_focus_auto_yield), brandString(R.string.audio_focus_auto_yield_desc),
            AirPlayPersistence.loadAudioFocusAutoYield(this)) {
            AirPlayPersistence.saveAudioFocusAutoYield(this, it)
            markReconnectNeeded()
        }
        parent.addView(dependent)
    }


    private fun filteredSection(
        parent: LinearLayout,
        key: SettingsSection,
        title: String,
        icon: Int? = null,
        showHeading: Boolean = true,
        build: (LinearLayout) -> Unit,
    ) {
        if (settingsSectionFilter?.contains(key) == false) return
        section(parent, title, icon, showHeading, build)
    }
    // Routing changes affect the live cluster surface as soon as the host resumes.
    // Ask before saving them instead of promising to defer only part of the change.
    private fun reconnectingToggle(parent: LinearLayout, title: String, description: String,
        value: Boolean, save: (Boolean) -> Unit) {
        toggle(parent, title, description, value) { selected ->
            if (!CarPlayBackgroundSession.hasSession()) {
                save(selected)
            } else {
                appDialogBuilder().setTitle(title).setMessage(description)
                    .setPositiveButton(R.string.apply_and_reconnect) { _, _ ->
                        save(selected)
                        reconnectIfRunning()
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> render() }
                    .setOnCancelListener { render() }
                    .show()
            }
        }
    }

    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, enabled: Boolean = true, confirmReconnect: Boolean = false, save: (Boolean) -> Unit): Switch {
        searchIndexSink?.add(title)
        val result = SettingsWidgets.createSwitchRow(
            context = this,
            label = title,
            description = description,
            checked = value,
            theme = SettingsTheme.card(palette),
            contentDescription = title,
            enabled = enabled,
            onChanged = { selected ->
                if (confirmReconnect && CarPlayBackgroundSession.hasSession()) {
                    appDialogBuilder().setTitle(title).setMessage(description)
                        .setPositiveButton(R.string.apply_and_reconnect) { _, _ -> save(selected) }
                        .setNegativeButton(R.string.cancel) { _, _ -> render() }
                        .setOnCancelListener { render() }
                        .show()
                } else save(selected)
            },
        )
        if (page == "settings") {
            val row = result.rowView as LinearLayout
            row.setPadding(0, 0, 0, 0)
            val textColumn = row.getChildAt(0) as LinearLayout
            (textColumn.getChildAt(1) as TextView).visibility = View.GONE
            (textColumn.getChildAt(0) as TextView).apply {
                includeFontPadding = false
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPaddingRelative(0, dp(FidelitySettingsMetrics.ROW_VERTICAL_DP), dp(FidelitySettingsMetrics.ROW_VERTICAL_DP), dp(FidelitySettingsMetrics.ROW_VERTICAL_DP))
            }
            row.minimumHeight = dp(FidelitySettingsMetrics.SETTING_ROW_DP)
            row.contentDescription = "$title. $description"
            val icon = ImageView(this).apply {
                setImageResource(settingsCategoryIcon(settingsCategory))
                imageTintList = ColorStateList.valueOf(ACCENT)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPaddingRelative(dp(FidelitySettingsMetrics.ROW_ICON_INSET_DP), 0,
                    dp(FidelitySettingsMetrics.ROW_START_DP - FidelitySettingsMetrics.ROW_ICON_INSET_DP - FidelitySettingsMetrics.ROW_ICON_DP), 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(icon, 0, LinearLayout.LayoutParams(dp(FidelitySettingsMetrics.ROW_START_DP), dp(FidelitySettingsMetrics.ROW_ICON_DP)))
        }
        parent.addView(result.rowView)
        return result.switch
    }
    private fun ambientConfigurationLabel(value: AmbientMusicSettings.Values): String {
        val mode = if (!value.music) brandString(R.string.settings_ambient_static) else when (value.colorMode) {
            AmbientColorMode.ENERGY -> brandString(R.string.settings_ambient_energy)
            AmbientColorMode.BEAT -> brandString(R.string.settings_ambient_beat)
            AmbientColorMode.TEMPO -> brandString(R.string.settings_ambient_tempo)
            AmbientColorMode.BASS -> brandString(R.string.settings_ambient_bass)
            AmbientColorMode.SMART -> brandString(R.string.settings_ambient_smart)
        }
        val speed = when (value.speed) { AmbientColorSpeed.SLOW -> brandString(R.string.settings_ambient_slow); AmbientColorSpeed.STANDARD -> brandString(R.string.settings_ambient_standard); AmbientColorSpeed.FAST -> brandString(R.string.settings_ambient_fast) }
        return brandString(R.string.settings_ambient_configuration) + " · " + brandString(R.string.settings_ambient_summary, brandString(if (value.enabled) R.string.settings_ambient_enabled else R.string.settings_ambient_disabled), mode, speed, value.selectedColors.size, value.brightness)
    }

    private fun ambientModeDescription(mode: AmbientColorMode): String = brandString(when (mode) {
        AmbientColorMode.ENERGY -> R.string.settings_ambient_mode_energy_desc
        AmbientColorMode.BEAT -> R.string.settings_ambient_mode_beat_desc
        AmbientColorMode.TEMPO -> R.string.settings_ambient_mode_tempo_desc
        AmbientColorMode.BASS -> R.string.settings_ambient_mode_bass_desc
        AmbientColorMode.SMART -> R.string.settings_ambient_mode_smart_desc
    })

    private fun ambientBrightnessText(level: Int): String =
        if (level <= 0) brandString(R.string.settings_ambient_minimum) else brandString(R.string.settings_ambient_brightness_value, level)

    private fun showAmbientConfiguration() {
        var draft = AmbientMusicSettings.load(this)
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(brandString(R.string.settings_ambient_parked_notice), 14, MUTED))
        body.addView(space(12))
        body.addView(label(brandString(R.string.settings_ambient_behavior_title), 16, ACCENT, bold = true))
        val behavior = label("", 14, MUTED)
        body.addView(behavior)
        body.addView(space(12))
        lateinit var colorGrid: AmbientSwatchGrid
        lateinit var colorHint: TextView
        lateinit var musicOptions: LinearLayout
        fun refreshAmbient() {
            behavior.text = AmbientBehaviorText.lines(this, draft).joinToString("\n") { "• $it" }
            musicOptions.visibility = if (draft.music) View.VISIBLE else View.GONE
            val album = draft.colorSource == AmbientColorSource.ALBUM
            colorGrid.single = !draft.music
            colorGrid.setInteractive(!album)
            colorHint.text = brandString(when {
                album -> R.string.settings_ambient_colors_hint_album
                draft.music -> R.string.settings_ambient_colors_hint_follow
                else -> R.string.settings_ambient_colors_hint_fixed
            })
        }
        toggle(body, brandString(R.string.settings_ambient_enable), brandString(R.string.settings_ambient_enable_description), draft.enabled) {
            draft = draft.copy(enabled = it)
            refreshAmbient()
        }
        toggle(body, brandString(R.string.settings_ambient_music), brandString(R.string.settings_ambient_music_description), draft.music) {
            draft = draft.copy(music = it)
            if (!it) {
                // Fixed mode shows a single color, the first of the selection, as the lamps do.
                val only = draft.selectedColors.first()
                draft = draft.copy(selectedColors = listOf(only), color = only)
                colorGrid.setSelection(setOf(only))
            }
            refreshAmbient()
        }
        musicOptions = column()
        val modeNote = label(ambientModeDescription(draft.colorMode), 13, MUTED)
        choice(musicOptions, brandString(R.string.settings_ambient_color_mode), listOf(brandString(R.string.settings_ambient_energy), brandString(R.string.settings_ambient_beat), brandString(R.string.settings_ambient_tempo), brandString(R.string.settings_ambient_bass), brandString(R.string.settings_ambient_smart)), draft.colorMode.ordinal, reconnects = false) {
            draft = draft.copy(colorMode = AmbientColorMode.entries[it])
            modeNote.text = ambientModeDescription(draft.colorMode)
        }
        musicOptions.addView(modeNote)
        musicOptions.addView(space(12))
        choice(musicOptions, brandString(R.string.settings_ambient_speed), listOf(brandString(R.string.settings_ambient_slow), brandString(R.string.settings_ambient_standard), brandString(R.string.settings_ambient_fast)), draft.speed.ordinal, reconnects = false) {
            draft = draft.copy(speed = AmbientColorSpeed.entries[it])
        }
        body.addView(musicOptions)
        choice(body, brandString(R.string.settings_ambient_idle), listOf(brandString(R.string.settings_ambient_idle_steady), brandString(R.string.settings_ambient_idle_car), brandString(R.string.settings_ambient_idle_off)), draft.idle.ordinal, reconnects = false) {
            draft = draft.copy(idle = AmbientIdleBehavior.entries[it])
            refreshAmbient()
        }
        toggle(body, brandString(R.string.settings_ambient_album), brandString(R.string.settings_ambient_album_description), draft.colorSource == AmbientColorSource.ALBUM) {
            draft = draft.copy(colorSource = if (it) AmbientColorSource.ALBUM else AmbientColorSource.SELECTED)
            refreshAmbient()
        }
        body.addView(space(12))
        body.addView(label(brandString(R.string.settings_ambient_colors_title), 16, ACCENT, bold = true))
        colorHint = label("", 13, MUTED)
        body.addView(colorHint)
        body.addView(space(8))
        colorGrid = AmbientSwatchGrid(
            this, ACCENT,
            initial = if (draft.music) draft.selectedColors.toSet() else setOf(draft.selectedColors.first()),
            single = !draft.music,
            onChanged = { colors ->
                val sorted = colors.sorted()
                draft = draft.copy(selectedColors = sorted, color = sorted.first())
            },
            onLastDeselected = { toast(brandString(R.string.settings_ambient_select_one)) },
        )
        body.addView(colorGrid)
        body.addView(space(12))
        body.addView(label(brandString(R.string.settings_ambient_brightness), 16, ACCENT, bold = true))
        val brightnessValue = label(ambientBrightnessText(draft.brightness), 14, MUTED)
        body.addView(brightnessValue)
        body.addView(SeekBar(this).apply {
            max = 6
            progress = draft.brightness
            contentDescription = brandString(R.string.settings_ambient_brightness)
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, level: Int, fromUser: Boolean) {
                    draft = draft.copy(brightness = level)
                    brightnessValue.text = ambientBrightnessText(level)
                    refreshAmbient()
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        body.addView(space(12))
        choice(body, brandString(R.string.settings_ambient_area), listOf(brandString(R.string.settings_ambient_front), brandString(R.string.settings_ambient_rear), brandString(R.string.settings_ambient_all)), draft.area - 1, reconnects = false) {
            draft = draft.copy(area = it + 1)
            refreshAmbient()
        }
        refreshAmbient()
        val dialog = appDialogBuilder().setTitle(brandString(R.string.settings_ambient_configuration))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(brandString(R.string.save), null)
            .setNegativeButton(brandString(R.string.cancel), null).create()
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val selected = draft.copy(colorCycle = draft.music)
                fun commit() {
                    AmbientMusicSettings.save(this, selected)
                    dialog.dismiss()
                    render()
                }
                if (!selected.enabled) commit()
                else {
                    save.isEnabled = false
                    save.text = brandString(R.string.settings_ambient_checking)
                    ambientSupportCheck(applicationContext).whenComplete { supported, _ ->
                        runOnUiThread {
                            if (!dialog.isShowing || isFinishing || isDestroyed) return@runOnUiThread
                            save.isEnabled = true
                            save.text = brandString(R.string.save)
                            if (supported == true) commit()
                            else toast(brandString(R.string.settings_ambient_unavailable))
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /** Music buffer for one transport. A change reconnects only a running session of that transport. */
    private fun musicBufferChoice(card: LinearLayout, wireless: Boolean) {
        val buffer = com.shilapi.xcertplay.media.MediaAudioBuffer
        val presets = if (wireless) buffer.wirelessPresets else buffer.wiredPresets
        val labels = presets.map { millis ->
            when (millis) {
                buffer.DEFAULT_MILLIS -> brandString(R.string.s_300_ms_default)
                buffer.MOST_STABLE_MILLIS -> brandString(R.string.s_1000_ms_most_stable)
                else -> brandString(R.string.settings_music_buffer_ms, millis)
            }
        }
        val title = brandString(if (wireless) R.string.settings_music_buffer_wireless else R.string.settings_music_buffer_wired)
        val sessionTransport = AirPlayPersistence.loadWirelessEnabled(this) == wireless
        choice(card, title, labels,
            presets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this, wireless)).coerceAtLeast(0),
            reconnects = sessionTransport) {
            AirPlayPersistence.saveMediaBufferMillis(this, wireless, presets[it])
        }
    }

    private fun homeVehicleBrandControl(parent: LinearLayout) {
        val title = brandString(R.string.settings_home_brand)
        val current = AirPlayPersistence.loadHomeVehicleBrand(this)
        parent.addView(button("$title · ${brandString(current.label)}", false) {
            var pending = current
            val brands = HomeVehicleBrand.entries
            appDialogBuilder().setTitle(title)
                .setSingleChoiceItems(brands.map { brandString(it.label) }.toTypedArray(), brands.indexOf(current)) { _, index ->
                    pending = brands[index]
                }
                .setPositiveButton(R.string.save) { _, _ ->
                    AirPlayPersistence.saveHomeVehicleBrand(this, pending)
                    if (pending != current) {
                        render()
                    }
                }
                .setNegativeButton(R.string.cancel, null).show()
        }, matchButton(0, FidelitySettingsMetrics.SETTING_ROW_DP))
    }

    private fun vehicleWallpaperControl(parent: LinearLayout) {
        val title = brandString(R.string.settings_vehicle_model)
        val model = AirPlayPersistence.loadVehicleModel(this)
        val control = button("$title · ${brandString(model.label)}", false) {
            val custom = AirPlayPersistence.loadVehicleModel(this) == VehicleModel.CUSTOM
            appDialogBuilder().setTitle(title)
                .setPositiveButton(
                    if (custom) R.string.settings_wallpaper_replace else R.string.settings_wallpaper_choose,
                ) { _, _ -> chooseWallpaperImage() }
                .setNeutralButton(R.string.settings_wallpaper_use_default) { _, _ ->
                    wallpaperImport.cancel()
                    AirPlayPersistence.saveVehicleModel(this, VehicleModel.CORSAIR)
                    render()
                }
                .setNegativeButton(R.string.cancel, null).show()
        }
        parent.addView(control, matchButton(0, FidelitySettingsMetrics.SETTING_ROW_DP))
    }

    // [announcesReconnect] labels a choice whose [save] reconnects by itself.
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true,
        announcesReconnect: Boolean = reconnects, enabled: Boolean = true, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}.apply {
            isEnabled=enabled; rowReconnects=reconnects || announcesReconnect
        }
        button.setOnClickListener {
            var pendingSelection = selection
            appDialogBuilder().also { (it as? GlassDialogBuilder)?.reconnectHint=announcesReconnect || reconnects }.setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(brandString(if (announcesReconnect && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = "$title · ${options[selection]}"
                        if (reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(brandString(R.string.cancel), null).show()
        }
        parent.addView(button, matchButton(0, 60)); parent.addView(space(12))
    }
    private fun polishSettingsRows(container: LinearLayout) {
        val children=(0 until container.childCount).map(container::getChildAt)
        children.filterIsInstance<LinearLayout>().filter { it.orientation==LinearLayout.VERTICAL && it !is OverlaySliderRow }.forEach(::polishSettingsRows)
        val introductoryNotes = children.filterIsInstance<TextView>().filter { it.tag == SETTINGS_NOTE }
        introductoryNotes.forEach { note -> note.visibility = View.GONE }
        children.filterIsInstance<LinearLayout>().filter { row ->
            row.orientation == LinearLayout.HORIZONTAL && (0 until row.childCount).any { row.getChildAt(it) is Switch }
        }.forEach { row ->
            (row.layoutParams as? LinearLayout.LayoutParams)?.apply { topMargin = 0; bottomMargin = 0 }
        }
        children.forEachIndexed { index,control ->
            val view = if (control is OverlaySliderRow) control.makeCompact() else control
            val introduction = introductoryNotes.filter { note ->
                val noteIndex = children.indexOf(note)
                children.drop(noteIndex + 1).firstOrNull { it !is TextView || it is Button } === control
            }
            if (view is SettingButton && !view.action && introduction.isNotEmpty() && view.rowTitle == null && VALUE_SEPARATOR !in view.text) {
                view.rowTitle = view.text.toString()
                view.rowIconResource = settingsCategoryIcon(settingsCategory)
            }
            if(view !is SettingButton || view.action || (VALUE_SEPARATOR !in view.text && view.rowTitle==null))return@forEachIndexed
            view.rowPresentation=true
            view.minHeight=dp(FidelitySettingsMetrics.SETTING_ROW_DP)
            view.textSize=UiTypography.ROW_SP.toFloat()
            val title=view.rowTitle ?: view.text.toString().substringBefore(VALUE_SEPARATOR)
            if(title==brandString(R.string.resolution))view.rowReconnects=true
            val notes=children.drop(index+1).takeWhile { it is TextView && it !is Button }.filterIsInstance<TextView>()
            val detailNotes=(introduction + notes).distinct()
            val full=detailNotes.joinToString("\n\n") { it.text.toString() }
            // Preserve descriptions for accessibility without a separate help tap target.
            view.rowIconResource=when {
                view.rowTitle!=null -> view.rowIconResource
                title==brandString(R.string.carplay_night_mode) -> R.drawable.ic_glass_display
                title==brandString(R.string.carplay_size) -> R.drawable.ic_glass_size
                title==brandString(R.string.resolution) -> R.drawable.ic_glass_hd
                else -> settingsCategoryIcon(settingsCategory)
            }
            if(full.isNotBlank()) {
                notes.forEach { it.visibility=View.GONE }
                view.rowDetails=full
            }
            view.background=RippleDrawable(ColorStateList.valueOf(RIPPLE),ColorDrawable(Color.TRANSPARENT),null)
            (view.layoutParams as? LinearLayout.LayoutParams)?.apply {
                topMargin=0
                val next=children.getOrNull(index+1)
                bottomMargin=if(next is SettingButton && !next.rowPresentation && VALUE_SEPARATOR !in next.text)dp(CONTROL_GAP_DP)else 0
            }
            view.setPadding(0,0,0,0)
        }
        updateSettingsDividers(container)
    }

    /** Only adjacent settings share a separator; headings, actions and real status text break a group. */
    private fun updateSettingsDividers(container: LinearLayout) {
        fun isSetting(view: View) = (view is SettingButton && view.rowPresentation) ||
            (view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL &&
                (0 until view.childCount).any { view.getChildAt(it) is Switch })
        fun entries(view: View): List<View?> = when {
            view.visibility != View.VISIBLE -> emptyList()
            isSetting(view) -> listOf(view)
            view is LinearLayout && view.orientation == LinearLayout.VERTICAL ->
                (0 until view.childCount).flatMap { entries(view.getChildAt(it)) }
            else -> listOf(null)
        }
        val sequence = entries(container)
        val divided = sequence.zipWithNext().filter { it.first != null && it.second != null }.mapNotNull { it.first }.toMutableSet()
        // Overview can arrange quick settings in two columns. Separate the two grid rows,
        // retaining the clean edge below the final pair.
        val grids = (0 until container.childCount).map(container::getChildAt).filterIsInstance<GlassEqualHeightRow>()
        grids.dropLast(1).forEach { grid -> divided.addAll(visualDescendants(grid).filter(::isSetting)) }
        visualDescendants(container).filter(::isSetting).forEach { view ->
            val show = view in divided
            if (view is SettingButton) {
                if (view.drawRowDivider != show) { view.drawRowDivider = show; view.invalidate() }
            } else {
                val current = view.foreground
                if (show && current !is SettingsRowForeground) {
                    view.foreground = SettingsRowForeground(current ?: ColorDrawable(Color.TRANSPARENT), SettingsRowDivider(this, MUTED))
                } else if (!show && current is SettingsRowForeground) view.foreground = current.original
            }
        }
    }

    private fun card() = column().apply {
        if (page == "settings") addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSettingsDividers(this) }
        background = GlassChrome.surface(SURFACE, BORDER, resources.displayMetrics.density,
            dp(FidelitySettingsMetrics.CARD_RADIUS_DP).toFloat(), opacity = if (appNight) 184 else 224)
        setPadding(dp(FidelitySettingsMetrics.CARD_PAD_DP), dp(FidelitySettingsMetrics.CARD_VERTICAL_DP),
            dp(FidelitySettingsMetrics.CARD_PAD_DP), dp(FidelitySettingsMetrics.CARD_VERTICAL_DP))
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private class SettingsSearchEditText(context: android.content.Context, val compact: Boolean) : EditText(context) {
        var onDismissSearch: (() -> Unit)? = null
        private var keyboardWasVisible = false
        private val glyphBounds = android.graphics.Rect()
        private var measuredHint: String? = null
        private var measuredHintHeight = -1
        private var measuredHintTextSize = -1f
        private var measuredHintBaseline = 0

        fun onKeyboardVisibilityChanged(visible: Boolean) {
            val dismissed = keyboardWasVisible && !visible
            keyboardWasVisible = visible
            // The first hidden inset belongs to opening search, before the keyboard appears.
            // Defer removal of the header until the current inset traversal has completed.
            if (dismissed) post {
                if (!keyboardWasVisible && isAttachedToWindow) onDismissSearch?.invoke()
            }
        }

        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyPreIme(keyCode, event)
            // Older keyboards consume Back before the activity's back dispatcher sees it.
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onDismissSearch?.invoke()
            return true
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val value: CharSequence = if (text.isNullOrEmpty()) hint ?: "" else text
            val drawingBaseline = if (text.isNullOrEmpty() && value.isNotEmpty()) hintBaseline(value) else baseline
            val offset = if (baseline >= 0) {
                val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                contentCentre - alignedTextCentreY(this, value, glyphBounds, drawingBaseline)
            } else 0f
            // Text, selection and cursor move together.
            val checkpoint = canvas.save()
            canvas.translate(0f, offset)
            super.onDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }

        /**
         * TextView.baseline describes the editable layout, even while it draws a hint, and a hint in
         * a fallback font can sit differently. Measured once per hint, height and text size rather
         * than on every frame of the blinking cursor.
         */
        private fun hintBaseline(value: CharSequence): Int {
            if (value.toString() != measuredHint || height != measuredHintHeight || paint.textSize != measuredHintTextSize) {
                // The editor scrolls horizontally rather than wrapping: lay the hint out on one line.
                val hintWidth = maxOf(
                    (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(1),
                    kotlin.math.ceil(android.text.Layout.getDesiredWidth(value, paint).toDouble()).toInt(),
                )
                val hintLayout = android.text.StaticLayout.Builder.obtain(value, 0, value.length, paint, hintWidth)
                    .setMaxLines(1).setIncludePad(includeFontPadding).apply {
                        if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(isFallbackLineSpacing)
                    }.build()
                measuredHintBaseline = extendedPaddingTop +
                    ((height - extendedPaddingTop - extendedPaddingBottom - hintLayout.height).coerceAtLeast(0) / 2) +
                    hintLayout.getLineBaseline(0)
                measuredHint = value.toString()
                measuredHintHeight = height
                measuredHintTextSize = paint.textSize
            }
            return measuredHintBaseline
        }
    }

    private class HeaderTitleView(context: android.content.Context) : TextView(context) {
        private val glyphBounds = android.graphics.Rect()

        override fun onDraw(canvas: android.graphics.Canvas) {
            val value = text.toString()
            paint.getTextBounds(value, 0, value.length, glyphBounds)
            val checkpoint = canvas.save()
            if (layout?.lineCount == 1 && !glyphBounds.isEmpty && baseline >= 0) {
                val textCentre = alignedTextCentreY(this, value, glyphBounds)
                val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                canvas.translate(0f, contentCentre - textCentre)
            }
            super.onDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun settingsNote(value: String, size: Int, color: Int) = label(value, size, color).apply { tag = SETTINGS_NOTE }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false, centreGlyphs: Boolean = false) =
        (if (centreGlyphs) HeaderTitleView(this) else TextView(this)).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun headerIconSize(compact: Boolean) = dp(if (compact) 20 else 24)

    private fun headerButton(title: String, icon: Int, compact: Boolean, click: () -> Unit) = button(title, false, click).apply {
        alignVisibleGlyphs = true
        if (compact) textSize = 16f
        includeFontPadding = false
        setTextColor(TEXT)
        setSingleLine(true)
        gravity = Gravity.CENTER
        val iconSize = headerIconSize(compact)
        val drawable = getDrawable(icon)?.mutate()?.apply {
            setTint(TEXT)
            setBounds(0, 0, iconSize, iconSize)
        }
        persistentStartIcon=drawable
        setCompoundDrawablesRelative(drawable, null, null, null)
        compoundDrawablePadding = dp(8)
        // SettingButton centres single-line glyphs at draw time and leaves wrapped labels
        // to TextView. A first-line offset would push the second line outside the button.
    }

    private fun layoutCornerRadius(): Float {
        val compact = isCompactLayout && (page != "settings" || !SettingsLayoutPreferences.isActive(this))
        return dp(if (compact) 44 else 52) / 2f
    }

    private fun buttonShape(color: Int, stroke: Int, heightPx: Int = 0) = rounded(color, stroke).apply {
        cornerRadius = if (heightPx > 0) heightPx / 2f else layoutCornerRadius()
    }

    private fun buttonFocusRing() = focusRing(
        (layoutCornerRadius() / resources.displayMetrics.density).roundToInt(),
    )

    private fun button(title: String, primary: Boolean, click: () -> Unit) = SettingButton(this, MUTED, ACCENT).apply {
        isAllCaps = false; textSize = UiTypography.ACTION_SP.toFloat(); setTextColor(GlassChrome.textColors(if (primary) ON_ACCENT else TEXT, BUTTON))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), buttonShape(if (primary) ACCENT else BUTTON, if (primary) ACCENT else BORDER), null)
        setPadding(dp(FidelitySettingsMetrics.CARD_PAD_DP), dp(FidelitySettingsMetrics.ROW_VERTICAL_DP),
            dp(FidelitySettingsMetrics.CARD_PAD_DP), dp(FidelitySettingsMetrics.ROW_VERTICAL_DP)); minHeight = dp(FidelitySettingsMetrics.ACTION_HEIGHT_DP); stateListAnimator = null
        compoundDrawablePadding = dp(12)
        foreground = buttonFocusRing()
        text = title
        searchIndexSink?.add(title.substringBefore(VALUE_SEPARATOR))
        setOnClickListener { click() }
        doOnLayout {
            if (rowPresentation) {
                background=RippleDrawable(ColorStateList.valueOf(RIPPLE),ColorDrawable(Color.TRANSPARENT),null)
                foreground=focusRing(12)
                return@doOnLayout
            }
            val shape = buttonShape(if (primary) ACCENT else BUTTON, if (primary) ACCENT else BORDER, height)
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, null)
            foreground = focusRing((shape.cornerRadius / resources.displayMetrics.density).roundToInt())
        }
    }

    private fun iconButton(icon: Int, description: String, click: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(ACCENT)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        stateListAnimator = null
        background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(RIPPLE),
            buttonShape(BUTTON, BORDER),
            null,
        )
        foreground = buttonFocusRing()
        setOnClickListener { click() }
    }

    // For an action whose label happens to contain the separator, such as "Turn on … · ADB".
    private fun actionButton(title: String, primary: Boolean, click: () -> Unit) =
        button(title, primary, click).apply { action = true; text = title }

    /**
     * Text in the "Title · Value" form opens a choice, so it reads as a setting row:
     * start-aligned, muted value, chevron. Any other text stays a centred action button.
     * The plain text is unchanged, so callers and tests keep matching "Title · Value".
     */
    private class SettingButton(
        context: android.content.Context,
        private val muted: Int,
        private val accent: Int,
    ) : Button(context) {
        override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
            if (params is MinimumButtonLayoutParams) {
                if (params.height > 0) params.minimumButtonHeight = params.height
                minHeight = params.minimumButtonHeight
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            super.setLayoutParams(params)
        }

        var centeredIconGroup=false
        var persistentStartIcon: android.graphics.drawable.Drawable?=null
        var action = false
        var contentOffsetY = 0f
        var alignVisibleGlyphs = true
        private val glyphBounds = android.graphics.Rect()

        var rowTitle: String?=null
        var rowPresentation=false
        var drawRowDivider=false
        private val rowDivider by lazy { SettingsRowDivider(context, muted) }
        var rowReconnects=false
        var rowIconResource=R.drawable.ic_dp_display
        var rowDetails: String?=null
            set(value) { field=value; refreshRowDescription(text) }
        private fun refreshRowDescription(value: CharSequence?) {
            rowDetails?.let { contentDescription="$value. $it" }
        }
        private var titleLayout: android.text.StaticLayout?=null
        private var valueLayout: android.text.StaticLayout?=null
        private var titleX=0f
        private var valueX=0f
        private var titleWidth=0
        private var rowHeaderTop=0f
        private var rowHeaderHeight=0
        private fun px(v:Int)=(v*resources.displayMetrics.density).roundToInt()
        private fun textLayout(value:String,width:Int,size:Float,color:Int):android.text.StaticLayout {
            val p=android.text.TextPaint(paint).apply { textSize=size;this.color=color }
            return android.text.StaticLayout.Builder.obtain(value,0,value.length,p,width.coerceAtLeast(1))
                .setIncludePad(false).setTextDirection(if(layoutDirection==View.LAYOUT_DIRECTION_RTL)android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL else android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .build()
        }
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            if(!rowPresentation) return
            val split=text.indexOf(VALUE_SEPARATOR)
            if(split<=0 && rowTitle==null)return
            val rtl=layoutDirection==View.LAYOUT_DIRECTION_RTL
            val narrow=measuredWidth/resources.displayMetrics.density<420
            val startInset=FidelitySettingsMetrics.ROW_START_DP
            val available=(measuredWidth-px(startInset+FidelitySettingsMetrics.ROW_END_DP)).coerceAtLeast(1)
            val headerAvailable=available
            val titleText=rowTitle ?: text.substring(0,split)
            val valueText=if(split>0)text.substring(split+VALUE_SEPARATOR.length)else ""
            val valueSize=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, UiTypography.BODY_SP.toFloat(), resources.displayMetrics)
            val valuePaint=android.text.TextPaint(paint).apply { textSize=valueSize }
            val desiredValueWidth=kotlin.math.ceil(android.text.Layout.getDesiredWidth(valueText,valuePaint).toDouble()).toInt()+px(2)
            val gap=if(valueText.isBlank())0 else px(if(narrow)FidelitySettingsMetrics.COMPACT_ROW_VALUE_GAP_DP else FidelitySettingsMetrics.ROW_VALUE_GAP_DP)
            val desiredTitleWidth=kotlin.math.ceil(android.text.Layout.getDesiredWidth(titleText,paint).toDouble()).toInt()+px(2)
            val titleShare=minOf(desiredTitleWidth,((headerAvailable-gap)*.60f).toInt())
            val valueWidth=if(valueText.isBlank())0 else minOf(desiredValueWidth,headerAvailable-gap-titleShare).coerceAtLeast(1)
            titleWidth=(headerAvailable-valueWidth-gap).coerceAtLeast(1)
            titleX=if(rtl)(measuredWidth-px(startInset)-titleWidth).toFloat() else px(startInset).toFloat()
            valueX=if(rtl)px(FidelitySettingsMetrics.ROW_END_DP).toFloat() else (measuredWidth-px(FidelitySettingsMetrics.ROW_END_DP)-valueWidth).toFloat()
            titleLayout=textLayout(titleText,titleWidth,textSize,currentTextColor)
            valueLayout=if(valueText.isBlank())null else textLayout(valueText,valueWidth,valueSize,muted)
            rowHeaderHeight=maxOf(titleLayout?.height?:0,valueLayout?.height?:0,px(FidelitySettingsMetrics.ROW_ICON_DP))
            val groupHeight=rowHeaderHeight
            val height=groupHeight+px(FidelitySettingsMetrics.ROW_VERTICAL_DP*2)
            setMeasuredDimension(measuredWidth,resolveSize(maxOf(minHeight,height),heightMeasureSpec))
            rowHeaderTop=(measuredHeight-groupHeight)/2f
        }

        fun textAlignmentCentreY(): Float {
            return alignedTextCentreY(this, text, glyphBounds)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            if(centeredIconGroup && persistentStartIcon!=null && layout?.lineCount==1) {
                val icon=persistentStartIcon!!
                val iconWidth=icon.bounds.width();val iconHeight=icon.bounds.height()
                val content=text.toString();val textWidth=kotlin.math.ceil(paint.measureText(content).toDouble()).toInt()+1
                val groupWidth=iconWidth+compoundDrawablePadding+textWidth
                if(groupWidth<=width-paddingLeft-paddingRight) {
                    val rtl=layoutDirection==View.LAYOUT_DIRECTION_RTL
                    val start=(width-groupWidth)/2f
                    val layout=textLayout(content,textWidth,textSize,currentTextColor)
                    val iconX=if(rtl)start+textWidth+compoundDrawablePadding else start
                    val iconSave=canvas.save()
                    canvas.translate(iconX-icon.bounds.left,(height-iconHeight)/2f-icon.bounds.top)
                    icon.draw(canvas);canvas.restoreToCount(iconSave)
                    val save=canvas.save();canvas.translate(if(rtl)start else start+iconWidth+compoundDrawablePadding,(height-layout.height)/2f)
                    layout.draw(canvas);canvas.restoreToCount(save)
                    return
                }
            }
            if(rowPresentation && titleLayout!=null) {
                val title=titleLayout!!
                val centreY=rowHeaderTop+rowHeaderHeight/2f
                fun draw(layout:android.text.StaticLayout?,x:Float,top:Float) {
                    if(layout==null)return
                    val checkpoint=canvas.save();canvas.translate(x,top);layout.draw(canvas);canvas.restoreToCount(checkpoint)
                }
                draw(title,titleX,centreY-title.height/2f)
                draw(valueLayout,valueX,centreY-(valueLayout?.height?:0)/2f)
                val rtl=layoutDirection==View.LAYOUT_DIRECTION_RTL
                context.getDrawable(rowIconResource)?.mutate()?.apply {
                    setTint(accent)
                    val iconSize=px(FidelitySettingsMetrics.ROW_ICON_DP)
                    val inset=px(FidelitySettingsMetrics.ROW_ICON_INSET_DP)
                    val x=if(rtl)width-inset-iconSize else inset
                    val top=(centreY-iconSize/2f).roundToInt()
                    setBounds(x,top,x+iconSize,top+iconSize);draw(canvas)
                }
                context.getDrawable(R.drawable.ic_dp_chevron)?.mutate()?.apply {
                    setTint(currentTextColor)
                    setLayoutDirection(if(rtl)View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR)
                    val x=if(rtl)px(FidelitySettingsMetrics.ROW_CHEVRON_INSET_DP)else width-px(FidelitySettingsMetrics.ROW_END_DP)
                    val size=px(FidelitySettingsMetrics.ROW_CHEVRON_DP)
                    val top=(centreY-size/2f).roundToInt()
                    setBounds(x,top,x+size,top+size);draw(canvas)
                }
                if(drawRowDivider) {
                    rowDivider.setBounds(0, 0, width, height)
                    rowDivider.setLayoutDirection(layoutDirection)
                    rowDivider.draw(canvas)
                }
                return
            }
            var offset = contentOffsetY
            if (alignVisibleGlyphs) {
                offset = 0f
                // Wrapped labels keep Android's multiline layout. Measure single-line labels
                // on every draw so changing a setting value also updates its visual centre.
                if (layout?.lineCount == 1 && baseline >= 0) {
                    val textCentre = textAlignmentCentreY()
                    val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                    offset = contentCentre - textCentre
                }
                // The chevron is already centred by TextView; compensate for the text shift.
                compoundDrawablesRelative.filterNotNull().forEach { drawable ->
                    val bounds = drawable.bounds
                    val top = -offset.roundToInt()
                    drawable.setBounds(bounds.left, top, bounds.right, top + bounds.height())
                }
            }
            val checkpoint = canvas.save()
            canvas.translate(0f, offset)
            super.onDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }

        override fun setText(text: CharSequence?, type: BufferType?) {
            val split = if (action) -1 else text?.indexOf(VALUE_SEPARATOR) ?: -1
            // Action rows such as diagnostic export change their title while work is running.
            if (rowPresentation && rowTitle != null && split <= 0) rowTitle = text?.toString()
            if (text == null || split <= 0) {
                super.setText(text, type)
                refreshRowDescription(text)
                gravity = Gravity.CENTER
                setCompoundDrawablesRelative(persistentStartIcon, null, null, null)
                return
            }
            val styled = android.text.SpannableString(text).apply {
                setSpan(android.text.style.ForegroundColorSpan(muted), split, text.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            super.setText(styled, BufferType.SPANNABLE)
            refreshRowDescription(text)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setCompoundDrawablesRelativeWithIntrinsicBounds(persistentStartIcon, null,
                context.getDrawable(R.drawable.ic_dp_chevron)?.mutate()?.apply { setTint(accent) }, null)
        }
    }
    // Remote and D-pad users need to see where they are; touch mode never shows it.
    private fun focusRing(radiusDp: Int = 20) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(radiusDp).toFloat(); setStroke(dp(3), FOCUS_RING)
        })
    }

    private fun rounded(color: Int, stroke: Int) = GlassChrome.surface(
        color, stroke, resources.displayMetrics.density, dp(GlassChrome.PANEL_RADIUS_DP).toFloat(),
        primary = color == ACCENT,
    ).apply {
        if(color==RAIL_SELECTED && appNight)colors=intArrayOf(
            androidx.core.graphics.ColorUtils.blendARGB(color,ACCENT,.42f),color,
            androidx.core.graphics.ColorUtils.blendARGB(color,ACCENT,.16f))
    }
    /** Buttons may grow for wrapped text; non-button rows retain their requested height. */
    private class MinimumButtonLayoutParams(var minimumButtonHeight: Int) :
        LinearLayout.LayoutParams(-1, minimumButtonHeight)

    private fun matchButton(top: Int = 0, height: Int = FidelitySettingsMetrics.ACTION_HEIGHT_DP) = MinimumButtonLayoutParams(dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)); tag = SPACER }

    /**
     * One vertical rhythm for every card, whatever margins each control builder chose:
     * buttons share a minimum height and gap, wrapped text can grow, and a note closes its group with a larger gap,
     * and the last item adds nothing to the card's own padding.
     */
    private fun normalizeSpacing(container: LinearLayout, isCard: Boolean = true) {
        if (container.orientation != LinearLayout.VERTICAL) return
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i).tag == SPACER) container.removeViewAt(i)
        }
        fun isNote(view: View?) = view is TextView && view !is Button
        fun isToggleRow(view: View?) = view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL &&
            (0 until view.childCount).any { view.getChildAt(it) is Switch }
        fun isControl(view: View?) = view is Button || isToggleRow(view)
        var previous: View? = null
        var last: View? = null
        var noteFollowsButton = false
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val next = container.getChildAt(i + 1)
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            when {
                child is Button -> {
                    lp.height = dp(if (page == "settings") FidelitySettingsMetrics.ACTION_HEIGHT_DP else CONTROL_HEIGHT_DP); lp.topMargin = 0
                    lp.bottomMargin = dp(if (isNote(next)) NOTE_ATTACH_GAP_DP else if (page == "settings") FidelitySettingsMetrics.CONTROL_GAP_DP else CONTROL_GAP_DP)
                }
                // Toggle rows carried their own vertical padding, which doubled the gap after a button.
                isToggleRow(child) -> {
                    child.setPadding(child.paddingLeft, 0, child.paddingRight, 0)
                    lp.topMargin = 0
                    lp.bottomMargin = dp(if (isNote(next)) NOTE_ATTACH_GAP_DP else if (page == "settings") FidelitySettingsMetrics.CONTROL_GAP_DP else CONTROL_GAP_DP)
                }
                child is TextView -> {
                    if (!isNote(previous)) noteFollowsButton = isControl(previous)
                    child.setPadding(child.paddingLeft, 0, child.paddingRight, 0)
                    lp.topMargin = 0
                    // Lines of one note stay together; the gap after the last line closes the group.
                    lp.bottomMargin = dp(when {
                        isNote(next) -> NOTE_LINE_GAP_DP
                        noteFollowsButton -> GROUP_GAP_DP
                        else -> CONTROL_GAP_DP
                    })
                }
                child is LinearLayout && child.orientation == LinearLayout.VERTICAL -> {
                    normalizeSpacing(child, isCard = false)
                    lp.topMargin = 0; lp.bottomMargin = 0
                }
                // Any other row (an icon preview, a status line) gets the same gap; the card heading keeps its padding.
                i > 0 || !isCard -> { lp.topMargin = 0; lp.bottomMargin = dp(CONTROL_GAP_DP) }
            }
            child.layoutParams = lp
            if (child.visibility != View.GONE) last = child
            previous = child
        }
        // A nested group keeps its trailing gap so it does not touch the control after it.
        if (isCard && !(last is LinearLayout && last.orientation == LinearLayout.VERTICAL)) {
            (last?.layoutParams as? LinearLayout.LayoutParams)?.let { it.bottomMargin = 0; last.layoutParams = it }
        }
    }
    // Rounded, not truncated: below 160 dpi dp(1) became 0 and every border vanished.
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    // Reset a previously saved light starting-window theme when upgrading to dark-only UI.
    private fun rememberLaunchAppearance() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        splashScreen.setSplashScreenTheme(R.style.Theme_Xcertplay)
    }

    private fun applyWindowAppearance() {
        window.setBackgroundDrawable(ColorDrawable(palette.background))
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.systemBarIconsAreDark
            isAppearanceLightNavigationBars = palette.systemBarIconsAreDark
        }
        applyFullscreenMode()
    }

    private val BG get() = palette.background
    private val SURFACE get() = palette.surface
    private val BUTTON get() = palette.button
    private val BORDER get() = palette.outline
    private val ACCENT get() = palette.accent
    private val ON_ACCENT get() = palette.onAccent
    private val RAIL_SELECTED get() = palette.railSelected
    private val RAIL_SELECTED_BORDER get() = palette.railSelectedOutline
    private val TEXT get() = palette.primaryText
    private val MUTED get() = palette.secondaryText
    private val WARNING get() = palette.warning
    private val READY get() = palette.success
    private val RIPPLE get() = palette.ripple
    private val FOCUS_RING get() = palette.focusRing

    companion object {
        internal const val EXTRA_RETURN_TO_CARPLAY = "return_to_carplay"
        private fun alignedTextCentreY(
            view: TextView,
            text: CharSequence,
            bounds: android.graphics.Rect,
            drawingBaseline: Int = view.baseline,
        ): Float {
            if (usesChineseGlyphAlignment(view.resources.configuration.locales[0].language, text)) {
                val value = text.toString()
                view.paint.getTextBounds(value, 0, value.length, bounds)
                return drawingBaseline + (bounds.top + bounds.bottom) / 2f
            }
            // Keep the baseline stable when labels contain descenders or accents.
            val metrics = view.paint.fontMetrics
            return drawingBaseline + (metrics.ascent + metrics.descent) / 2f
        }

        private fun usesChineseGlyphAlignment(language: String, text: CharSequence): Boolean {
            return language == "zh" && text.isNotBlank() &&
                text.none { it in 'A'..'Z' || it in 'a'..'z' }
        }

        internal fun isLauncherIntent(intent: Intent): Boolean =
            intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
                !intent.hasExtra("page")

        private const val BYD_VEHICLE_TAG = "DiPlay-BYD13"
        private const val VEHICLE_VALIDATION_RETRY_MILLIS = 500L
        private const val ADB_KEY_SAVE_WAIT_MILLIS = 500L
        private const val SETTINGS_SEARCH_BUTTON_TAG = "settings_search_button"
        private const val SETTINGS_HEADER_COMPACT_GAP_DP = 8
        private const val SETTINGS_HEADER_GAP_DP = 12
        private const val SETTINGS_RECONNECT_HORIZONTAL_MIN_WIDTH_DP = 550
        private const val SETTINGS_RECONNECT_SIDE_PADDING_DP = 16
        private const val SETTINGS_RECONNECT_END_PADDING_DP = 8
        private const val SETTINGS_RECONNECT_VERTICAL_PADDING_DP = 6
        private const val SETTINGS_RECONNECT_CONTENT_GAP_DP = 12
        private const val SETTINGS_RECONNECT_STACK_GAP_DP = 6
        private const val SETTINGS_RECONNECT_ACTION_HEIGHT_DP = 48
        private const val SETTINGS_RECONNECT_ACTION_SIDE_PADDING_DP = 16
        private const val SETTINGS_RECONNECT_MESSAGE_MAX_LINES = 2
        private const val SETTINGS_SEARCH_RESULTS_GAP_DP = 6
        private const val SETTINGS_SEARCH_RESULTS_SHORT_HEIGHT_DP = 200
        private const val SETTINGS_SEARCH_RESULTS_HEIGHT_DP = 380
        private const val VALUE_SEPARATOR = " · "
        private const val SEARCH_HIGHLIGHT_MILLIS = 900L
        private const val SPACER = "spacer"
        private const val SETTINGS_NOTE = "settings_note"
        private const val CONTROL_HEIGHT_DP = 60
        private const val HOME_RING_DP = 104
        private const val HOME_RING_LOW_DP = 88
        private const val HOME_RING_SHORT_DP = 44
        private const val HOME_SHORT_HEIGHT_DP = 400
        private const val HOME_CONTROL_SHARE = .54f
        private const val CONTROL_GAP_DP = 12
        private const val GROUP_GAP_DP = 20
        private const val NOTE_LINE_GAP_DP = 4
        private const val NOTE_ATTACH_GAP_DP = 6
        private const val SETTINGS_UPDATE_GAP_DP = 10
        private const val SETTINGS_BLOCK_GAP_DP = 16
        private const val UPDATE_SUCCESS_FEEDBACK_MILLIS = 4_000L
    }
}
