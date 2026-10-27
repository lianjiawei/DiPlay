package com.shilapi.xcertplay.lincoln

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.shilapi.xcertplay.glance.CarPlayGlance

/**
 * Forwards CarPlay guidance and now-playing to a Lincoln/Ford instrument-cluster app that listens
 * for plain broadcasts (see [LincolnClusterProtocol]).
 *
 * This follows [CarPlayGlance], the same snapshot the home-screen widget uses, so it does not read
 * the widget or depend on resource ids the way the standalone bridge did. It is off by default and
 * only broadcasts when [LincolnClusterSettings] has it enabled and a connection is up.
 */
object LincolnClusterOutput {
    private const val TAG = "DiPlay-Lincoln-Cluster"

    @Volatile private var context: Context? = null
    @Volatile private var listener: ((CarPlayGlance.Snapshot) -> Unit)? = null

    // Guards the "owned" flags so a stale snapshot cannot clear state another write just set.
    private val lock = Any()
    private var navOwned = false
    private var musicOwned = false
    // What we last told the cluster, so a missing route actively clears a stale "arrived" card
    // left by an earlier session instead of only stopping new writes.
    private var navStateOn: Boolean? = null
    private var auxiliaryNavOwned = false
    private var firstManeuverNs = 0L
    private var lastAuxiliaryIndex = LincolnClusterProtocol.NO_TURN_INDEX
    private var lastAuxiliaryStreet = ""
    private var lastNavKey: String? = null
    // The instruction last written to the cluster, so the same one can be repeated. The cluster
    // retires a nav card it has not heard about for a few seconds, which read as the card blinking
    // out and back on the way to the next turn; the working standalone bridge re-sent every second,
    // and so does this.
    private var lastNavTurn: String? = null
    private var lastNavManeuverIndex: Int? = null
    private var lastNavGeneration: Long? = null
    private var lastNavDistanceRevision: Long? = null
    private var lastNavUsableForZero = false
    private data class PendingZero(
        val index: Int, val generation: Long, val startedNs: Long,
        var lastRevision: Long, var samples: Int = 1,
    )
    private var pendingZero: PendingZero? = null
    // OFF can throw after delivery. Reannounce ON before the next valid instruction in that case.
    private var staleClearNeedsStart = false
    private var lastNavDistance: Int? = null
    private var lastNavRoad: String = ""
    // Identity for the next held packet; renewed after an ambiguous data-send failure.
    private var lastNavManeuverToken: LincolnManeuverToken? = null
    private val maneuverTracker = LincolnManeuverTracker()
    private var lastNavSentNs = 0L
    private var lastFilteredRevision: Long? = null
    private var lastMusicKey: String? = null
    private var lastRemainKey: String? = null
    private var remainOwned = false
    private var navSent = 0
    private var musicSent = 0
    private var remainSent = 0
    private var supersededSnapshots = 0
    private var lastReason = "init"
    // Only diagnostics skip an unchanged state. Output maintenance still runs so a timer or
    // remaining-info switch can clear a readout even without another iPhone frame.
    private var lastSnapshot: CarPlayGlance.Snapshot? = null
    private var lastNavEnabled: Boolean? = null
    private var lastMusicEnabled: Boolean? = null
    private var lastRemainingEnabled: Boolean? = null
    // The iPhone sends a transient NoRouteSet for a few tens of milliseconds when a route starts or
    // is rerouted. Acting on it cleared the card and drew it again, which is the blink that was
    // still visible after the 30 s staleness fix. An end has to stick before the cluster is cleared.
    private var endedSinceNs = 0L
    /** Injectable monotonic clock; the settle window is time-based, so tests advance it. */
    internal var nanoTime: () -> Long = System::nanoTime
    internal var hudSocket: LincolnHudSocket = LincolnHudSocketServer

    // Guidance has expiry deadlines (a stale route retires ~30 s after the last frame, and an
    // empty maneuver list hides after 3 s). Those only advance when snapshot() is called, and a
    // head unit without the home-screen widget placed never calls it, so a finished route's last
    // instruction — Apple Maps' "arrived" card — stayed on the cluster until the next connection.
    // Tick the glance ourselves so the same expiry the widget relies on also clears the cluster.
    private const val TICK_MILLIS = 1_000L
    // Repeat the current card at this cadence while it is unchanged. The cluster hides a nav card it
    // has not heard about within a few seconds; the firmware's own standalone bridge re-sent every
    // second, which is why its card was steady.
    private const val REPEAT_NAV_NS = 1_000_000_000L
    /**
     * An end must be reported this long before the cluster card is cleared. A route start or a
     * reroute sends a momentary NoRouteSet; acting on it at once blinked the card off and on.
     */
    private const val END_SETTLE_NS = 2_500_000_000L
    private const val START_DISTANCE_HOLD_NS = 3_000_000_000L
    private const val ZERO_HOLD_MIN_DISTANCE_METERS = 50
    private const val ZERO_CONFIRM_NS = 500_000_000L
    private const val ZERO_HOLD_MAX_NS = 2_000_000_000L
    @Volatile private var ticker: HandlerThread? = null
    @Volatile private var tickHandler: Handler? = null
    /**
     * The cluster drops everything it was told when the car's audio source changes, which the
     * audio-off key does (`AudioSource have changed, clear TP filter String`). Forget what was sent
     * so the next tick puts the cards back instead of assuming they are still there.
     */
    private val audioOffListener: (Boolean) -> Unit = {
        synchronized(lock) {
            // The tick skips an unchanged snapshot, so force the next one to rewrite the cards.
            lastSnapshot = null
            lastMusicKey = null
            lastNavKey = null
            lastRemainKey = null
            lastNavSentNs = 0L
            lastFilteredRevision = null
            // The cluster can forget the navigation announcement along with its card.
            auxiliaryNavOwned = false
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (context == null) return
            onPublishedSnapshot(CarPlayGlance.snapshot())
            tickHandler?.postDelayed(this, TICK_MILLIS)
        }
    }

    /** Called once from the app; attaches to the glance stream and forwards from then on. */
    fun attach(appContext: Context) {
        val app = appContext.applicationContext
        context = app
        LincolnNavigationDiagnostics.enableFrameCapture(true)
        if (listener != null) return
        val next: (CarPlayGlance.Snapshot) -> Unit = { onPublishedSnapshot(it) }
        listener = next
        CarPlayGlance.addListener(next)
        com.shilapi.xcertplay.media.CarAudioOff.attach(app)
        com.shilapi.xcertplay.media.CarAudioOff.addListener(audioOffListener)
        startTicking()
        // A session may already be running when the setting is enabled.
        onPublishedSnapshot(CarPlayGlance.snapshot())
    }

    fun detach() {
        LincolnNavigationDiagnostics.enableFrameCapture(false)
        listener?.let { CarPlayGlance.removeListener(it) }
        listener = null
        com.shilapi.xcertplay.media.CarAudioOff.removeListener(audioOffListener)
        context?.let { synchronized(lock) { endAuxiliaryNavLocked(it) } }
        context = null
        stopTicking()
        hudSocket.stop()
        synchronized(lock) {
            navOwned = false
            auxiliaryNavOwned = false
            firstManeuverNs = 0L
            lastAuxiliaryIndex = LincolnClusterProtocol.NO_TURN_INDEX
            lastAuxiliaryStreet = ""
            musicOwned = false
            remainOwned = false
            navStateOn = null
            lastNavKey = null
            lastNavTurn = null
            lastNavManeuverIndex = null
            lastNavGeneration = null
            lastNavDistanceRevision = null
            lastNavUsableForZero = false
            pendingZero = null
            staleClearNeedsStart = false
            lastNavDistance = null
            lastNavRoad = ""
            lastNavManeuverToken = null
            maneuverTracker.reset("detached")
            lastNavSentNs = 0L
            lastFilteredRevision = null
            lastMusicKey = null
            lastRemainKey = null
            lastSnapshot = null
            lastNavEnabled = null
            lastMusicEnabled = null
            lastRemainingEnabled = null
            endedSinceNs = 0L
        }
    }

    fun statusLine(): String = synchronized(lock) {
        "navSent=$navSent musicSent=$musicSent remainSent=$remainSent" +
            " navOwned=$navOwned musicOwned=$musicOwned remainOwned=$remainOwned" +
            " supersededSnapshots=$supersededSnapshots"
    }

    private fun startTicking() {
        if (tickHandler != null) return
        val thread = HandlerThread("diplay-lincoln-cluster").apply { start() }
        ticker = thread
        val handler = Handler(thread.looper)
        tickHandler = handler
        handler.postDelayed(tick, TICK_MILLIS)
    }

    private fun stopTicking() {
        tickHandler?.removeCallbacksAndMessages(null)
        tickHandler = null
        ticker?.quitSafely()
        ticker = null
    }

    /** A short reason the output is currently idle, for the settings screen. */
    fun reason(snapshot: CarPlayGlance.Snapshot): String = when {
        !snapshot.connected -> "DiPlay 未连接 CarPlay"
        snapshot.maneuverType == null && snapshot.song == null -> "CarPlay 已连接，暂无导航或歌曲信息"
        else -> "就绪"
    }

    private fun onPublishedSnapshot(snapshot: CarPlayGlance.Snapshot) = synchronized(lock) {
        // Read after acquiring the output lock; a newer callback can otherwise send first.
        // Do not just drop a stale tick: the latest notification may be blocked elsewhere,
        // but its published data is ready. Glance never holds its lock while calling us.
        val current = CarPlayGlance.latestPublished()
        if (snapshot !== current) {
            supersededSnapshots++
            Log.i(TAG, "coalesced superseded glance index=${snapshot.maneuverIndex} " +
                "distance=${snapshot.distanceMeters} generation=${snapshot.routeProgressGeneration}")
        }
        onSnapshot(current)
    }

    private fun onSnapshot(snapshot: CarPlayGlance.Snapshot) = synchronized(lock) {
        val app = context ?: return@synchronized
        val navEnabled = LincolnClusterSettings.navigation(app)
        val musicEnabled = LincolnClusterSettings.music(app)
        val remainingEnabled = LincolnClusterSettings.remaining(app)
        val enhanced = navEnabled && LincolnClusterSettings.enhancedNavigation(app) && snapshot.connected &&
            LincolnClusterSettings.clusterPackage(app) == LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE
        runCatching { LincolnNavigationDiagnostics.observe(snapshot, navEnabled, enhanced) }
        if (enhanced) hudSocket.start() else {
            endAuxiliaryNavLocked(app)
            hudSocket.stop()
        }
        val changed = snapshot != lastSnapshot || navEnabled != lastNavEnabled ||
            musicEnabled != lastMusicEnabled || remainingEnabled != lastRemainingEnabled
        lastSnapshot = snapshot
        lastNavEnabled = navEnabled
        lastMusicEnabled = musicEnabled
        lastRemainingEnabled = remainingEnabled
        val turn = LincolnClusterProtocol.turnCode(snapshot.maneuverType, snapshot.drivingSide)
        val distance = LincolnClusterProtocol.roundDistance(snapshot.distanceMeters)
        lastReason = when {
            !navEnabled && !musicEnabled -> "switches off"
            !snapshot.connected -> "not connected"
            snapshot.maneuverType == null && snapshot.routeEnded -> "connected, route ended"
            snapshot.maneuverType == null -> "connected, no new guidance"
            turn == null -> "unknown maneuver ${snapshot.maneuverType}"
            distance == null -> "distance out of range ${snapshot.distanceMeters}"
            else -> "guidance $turn ${distance}m '${snapshot.road}'"
        }
        if (changed) Log.i(TAG, "glance connected=${snapshot.connected} maneuver=${snapshot.maneuverType} " +
            "index=${snapshot.maneuverIndex} distance=${snapshot.distanceMeters} " +
            "road='${snapshot.road}' song='${snapshot.song}' " +
            "playing=${snapshot.playing} remain=${snapshot.remainingMeters}/${snapshot.remainingSeconds} " +
            "routeActive=${snapshot.routeActive} routeEnded=${snapshot.routeEnded} " +
            "endPending=${snapshot.routeEndPending} distanceRevision=${snapshot.distanceRevision} " +
            "progressGeneration=${snapshot.routeProgressGeneration} progressPending=${snapshot.routeProgressPending} " +
            "nav=$navEnabled music=$musicEnabled -> $lastReason")
        if (snapshot.routeEnded) {
            if (endedSinceNs == 0L) endedSinceNs = nanoTime()
        } else {
            endedSinceNs = 0L
        }
        if (navEnabled && snapshot.connected &&
            !(snapshot.routeEnded && !snapshot.routeEndPending && endSettled())) {
            sendNavLocked(app, snapshot)
            sendRemainLocked(app, snapshot)
        } else {
            clearNavLocked(app)
            clearRemainLocked(app)
        }
        if (musicEnabled && snapshot.connected) sendMusicLocked(app, snapshot) else clearMusicLocked(app)
    }

    private fun sendNavLocked(app: Context, snapshot: CarPlayGlance.Snapshot) {
        pendingZero?.let { candidate ->
            val selected = snapshot.selectedManeuverIndex ?: snapshot.maneuverIndex.takeIf { it >= 0 }
            if (snapshot.routeEnded || snapshot.routeProgressPending ||
                snapshot.routeProgressGeneration != candidate.generation ||
                (selected != null && selected != candidate.index) ||
                LincolnClusterSettings.clusterPackage(app) != LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE
            ) cancelPendingZero("route-or-instruction-changed")
        }
        if (staleClearNeedsStart &&
            (snapshot.maneuverType == null || snapshot.routeProgressPending || snapshot.routeEnded)
        ) {
            // A failed OFF may have been delivered. Retry it, but never resurrect the now-known
            // stale destination on a quiet tick, an end transition or a reroute hold.
            clearNavLocked(app)
            if (navStateOn == true) staleClearNeedsStart = true
            return
        }
        if (snapshot.routeEnded) {
            // Confirmed ends invalidate identity; transient NoRouteSet retains it. Ignore stale
            // guidance fields in either case and keep only the last successfully sent packet.
            if (!snapshot.routeEndPending) maneuverTracker.reset("route-ended")
            maintainNavLocked(app, snapshot)
            return
        }
        if (snapshot.routeProgressPending &&
            LincolnClusterSettings.clusterPackage(app) == LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE
        ) {
            // Preserve the last successful packet through calculation. Neither a state-only
            // frame nor new maneuver text may use its stale distance as the new baseline.
            maintainNavLocked(app, snapshot)
            return
        }
        val turn = LincolnClusterProtocol.turnCode(snapshot.maneuverType, snapshot.drivingSide)
        val formattedDistance = LincolnClusterProtocol.roundDistance(snapshot.distanceMeters)
        if (snapshot.maneuverType == null) {
            val selected = snapshot.selectedManeuverIndex
            val previous = lastNavManeuverIndex
            if (lastNavTurn == "turn_dest" && previous != null && selected != null &&
                selected != previous && snapshot.routeActive &&
                LincolnClusterSettings.clusterPackage(app) == LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE
            ) {
                // The phone selected a different maneuver, so the old destination is no longer
                // a valid instruction. Do not guess the new maneuver while its details are absent.
                runCatching { LincolnNavigationDiagnostics.staleDestination(previous, selected) }
                clearNavLocked(app)
                if (navStateOn == true) staleClearNeedsStart = true
                return
            }
            // No current instruction can mean three things: the iPhone's brief empty-maneuver frame
            // (every few seconds, and while rerouting), a route whose next turn is simply not
            // changing (the iAP2 route update goes quiet while parked at a light), or a real end.
            // Only a real end — Arrived, NoRouteSet, or a session drop — clears the card. Anything
            // else keeps the last instruction and re-sends it on the repeat cadence, because the
            // cluster hides a card it has not heard about within a few seconds. Clearing on
            // `routeActive` (a 30 s staleness timer) made the card vanish and come back while the
            // navigation was still running.
            maintainNavLocked(app, snapshot)
            return
        }
        if (turn == null || formattedDistance == null) {
            clearNavLocked(app)
            return
        }
        val road = LincolnClusterProtocol.sanitizeRoadName(snapshot.road).orEmpty()
        if (shouldHoldZero(app, snapshot, turn)) {
            // The primary code can be unchanged while the optional HUD distinguishes maneuvers
            // such as entering versus exiting a roundabout. Publish that direction immediately,
            // using the last accepted distance/identity, without leaking the unconfirmed zero.
            val auxiliaryIndex = LincolnClusterProtocol.turnIndex(snapshot.maneuverType, snapshot.drivingSide)
            if (LincolnClusterSettings.enhancedNavigation(app) && LincolnClusterProtocol.hasTurnIcon(auxiliaryIndex) &&
                (!auxiliaryNavOwned || auxiliaryIndex != lastAuxiliaryIndex)
            ) {
                val heldDistance = lastNavDistance
                val heldToken = lastNavManeuverToken
                if (heldDistance != null && heldToken != null) {
                    sendAuxiliaryNavLocked(app, snapshot.copy(road = lastNavRoad), heldDistance, heldToken)
                }
            }
            repeatLastNavLocked(app)
            return
        }
        if (firstManeuverNs == 0L) firstManeuverNs = nanoTime()
        if (navStateOn != true || staleClearNeedsStart) {
            if (broadcast(app, Intent(LincolnClusterProtocol.ACTION_NAV_STATE)
                    .putExtra(LincolnClusterProtocol.EXTRA_STATE, LincolnClusterProtocol.STATE_ON))) {
                navOwned = true
                navStateOn = true
                staleClearNeedsStart = false
            } else if (staleClearNeedsStart) {
                navOwned = false
            }
        }
        if (!navOwned) return
        val maneuverToken = maneuverTracker.update(
            snapshot.maneuverIndex, turn, snapshot.road, snapshot.distanceMeters,
            snapshot.routeProgressGeneration, snapshot.distanceRevision, nanoTime(),
            stabilize = LincolnClusterSettings.clusterPackage(app) == LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE,
        )
        val distance = maneuverTracker.distanceMeters
        if (distance != formattedDistance && snapshot.distanceRevision != lastFilteredRevision) {
            Log.i(TAG, "nav distance held raw=${snapshot.distanceMeters} formatted=$formattedDistance " +
                "sent=$distance samples=${maneuverTracker.pendingRiseSamples} index=${snapshot.maneuverIndex}")
            lastFilteredRevision = snapshot.distanceRevision
        }
        if (maneuverToken != lastNavManeuverToken &&
            LincolnClusterSettings.clusterPackage(app) == LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE
        ) {
            Log.i(TAG, "nav progress baseline reason=${maneuverTracker.resetReason} " +
                "index=${snapshot.maneuverIndex} generation=${snapshot.routeProgressGeneration} " +
                "distance=$distance previousDistance=$lastNavDistance")
        }
        val key = if (LincolnClusterSettings.clusterPackage(app) == LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE) {
            val auxiliaryIndex = if (LincolnClusterSettings.enhancedNavigation(app)) {
                LincolnClusterProtocol.turnIndex(snapshot.maneuverType, snapshot.drivingSide)
            } else LincolnClusterProtocol.NO_TURN_INDEX
            "$turn|$distance|$road|$maneuverToken|$auxiliaryIndex"
        } else {
            "$turn|$distance|$road|$maneuverToken|${snapshot.remainingMeters}|${snapshot.remainingSeconds}"
        }
        val changed = key != lastNavKey
        // Repeat the current card at least once a second even when nothing changed: the cluster hides
        // a nav card it has not heard about within a few seconds, so a long leg or a red light made it
        // blink out and back. The standalone bridge that this firmware ships with re-sent every second.
        val due = nanoTime() - lastNavSentNs >= REPEAT_NAV_NS
        if (changed || due) {
            val info = navigationIntent(app, turn, distance, road, maneuverToken)
            if (broadcast(app, info)) {
                runCatching { LincolnNavigationDiagnostics.navigationSent(snapshot, turn, distance,
                    maneuverToken != lastNavManeuverToken, maneuverTracker.resetReason, changed) }
                lastNavKey = key
                lastNavTurn = turn
                lastNavManeuverIndex = snapshot.maneuverIndex.takeIf { it >= 0 }
                lastNavGeneration = snapshot.routeProgressGeneration
                lastNavDistanceRevision = snapshot.distanceRevision
                lastNavUsableForZero = true
                lastNavDistance = distance
                lastNavRoad = road
                lastNavManeuverToken = maneuverToken
                lastNavSentNs = nanoTime()
                navSent++
                if (changed) Log.i(TAG, "nav INFO $turn ${distance}m '${snapshot.road}' (#$navSent)")
                sendAuxiliaryNavLocked(app, snapshot, distance, maneuverToken)
            } else {
                // Failure does not tell us whether the receiver processed the packet. Start
                // fresh on the next valid sample. Also give held data a fresh identity: replaying
                // its OLDER, LARGER distance under the failed packet's identity could overflow
                // if a restarted receiver actually established its baseline from that packet.
                maneuverTracker.reset("send-failed")
                lastNavUsableForZero = false
                if (lastNavManeuverToken != null) lastNavManeuverToken = LincolnManeuverToken.fresh()
            }
        }
    }

    private fun shouldHoldZero(app: Context, snapshot: CarPlayGlance.Snapshot, turn: String): Boolean {
        val eligible = LincolnClusterSettings.clusterPackage(app) == LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE &&
            snapshot.routeActive && navStateOn == true && navOwned && lastNavUsableForZero &&
            snapshot.maneuverIndex >= 0 && snapshot.maneuverIndex == lastNavManeuverIndex &&
            snapshot.routeProgressGeneration == lastNavGeneration && turn == lastNavTurn &&
            (lastNavDistance ?: 0) >= ZERO_HOLD_MIN_DISTANCE_METERS && lastNavManeuverToken != null
        if (!eligible || snapshot.distanceMeters != 0) {
            cancelPendingZero(if (snapshot.distanceMeters != 0) "distance-recovered" else "ineligible")
            return false
        }
        val current = pendingZero
        val now = nanoTime()
        if (current == null) {
            if (snapshot.distanceRevision == lastNavDistanceRevision) return false
            pendingZero = PendingZero(snapshot.maneuverIndex, snapshot.routeProgressGeneration, now, snapshot.distanceRevision)
            runCatching { LincolnNavigationDiagnostics.zeroDecision("started", snapshot) }
            return true
        }
        if (current.index != snapshot.maneuverIndex || current.generation != snapshot.routeProgressGeneration || now < current.startedNs) {
            cancelPendingZero("identity-or-clock-changed")
            return false
        }
        val freshZero = snapshot.distanceRevision != current.lastRevision
        if (freshZero) {
            current.lastRevision = snapshot.distanceRevision
            current.samples++
        }
        val elapsed = now - current.startedNs
        if ((freshZero && current.samples >= 2 && elapsed >= ZERO_CONFIRM_NS) || elapsed >= ZERO_HOLD_MAX_NS) {
            pendingZero = null
            runCatching { LincolnNavigationDiagnostics.zeroDecision(if (elapsed >= ZERO_HOLD_MAX_NS) "timeout" else "confirmed", snapshot) }
            return false
        }
        return true
    }

    private fun cancelPendingZero(reason: String) {
        val pending = pendingZero ?: return
        pendingZero = null
        runCatching { LincolnNavigationDiagnostics.zeroCancelled(reason, pending.index, pending.generation) }
    }

    /**
     * Re-send the card already on the cluster while the route is still running but the iPhone's
     * current-maneuver list is briefly empty. The cluster hides a card it has not heard about within
     * a few seconds, so without this the held card would still time out and blink.
     */
    private fun maintainNavLocked(app: Context, snapshot: CarPlayGlance.Snapshot) {
        // A stated end still has to stick: starting or rerouting a route sends NoRouteSet for a few
        // tens of milliseconds, and clearing on that produced a visible blink at every route start.
        if (snapshot.routeEnded) {
            if (endedSinceNs == 0L) endedSinceNs = nanoTime()
        } else {
            endedSinceNs = 0L
        }
        if (navStateOn != true || lastNavTurn == null) {
            clearNavLocked(app)
            return
        }
        if (snapshot.routeEnded) {
            if (snapshot.routeEndPending || !endSettled()) {
                repeatLastNavLocked(app)
                return
            }
            clearNavLocked(app)
            return
        }
        // Quiet guidance, or the iPhone's empty current-maneuver frame: hold the card and keep it
        // alive on the cluster's own timeout.
        repeatLastNavLocked(app)
    }

    private fun endSettled(): Boolean {
        val since = endedSinceNs
        return since != 0L && nanoTime() - since >= END_SETTLE_NS
    }

    private fun repeatLastNavLocked(app: Context) {
        val turn = lastNavTurn ?: return
        val distance = lastNavDistance ?: return
        if (nanoTime() - lastNavSentNs < REPEAT_NAV_NS) return
        val token = lastNavManeuverToken ?: return
        val info = navigationIntent(app, turn, distance, lastNavRoad, token)
        if (broadcast(app, info)) {
            runCatching { LincolnNavigationDiagnostics.navigationRepeated() }
            lastNavSentNs = nanoTime()
            navSent++
            repeatAuxiliaryNavLocked(app, distance, token)
        }
    }

    private fun navigationIntent(
        app: Context, turn: String, distance: Int, road: String, token: LincolnManeuverToken,
    ): Intent {
        val targetPackage = LincolnClusterSettings.clusterPackage(app)
        return Intent(LincolnClusterProtocol.ACTION_NAV_INFO)
            .setPackage(targetPackage)
            .putExtra(LincolnClusterProtocol.EXTRA_NAME, turn)
            .putExtra(LincolnClusterProtocol.EXTRA_DISTANCE, distance)
            .putExtra(LincolnClusterProtocol.EXTRA_ROAD, road)
            .apply {
                // Only this inspected adapter is known to use the coordinate slots as identity,
                // not location. Keep the checked target even if settings change before dispatch.
                if (targetPackage == LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE) {
                    putExtra(LincolnClusterProtocol.EXTRA_MANEUVER_LATITUDE, token.latitude)
                    putExtra(LincolnClusterProtocol.EXTRA_MANEUVER_LONGITUDE, token.longitude)
                }
            }
    }

    /** Additional factory channels, emitted only for the inspected Lincoln/Ford cluster package. */
    private fun sendAuxiliaryNavLocked(
        app: Context,
        snapshot: CarPlayGlance.Snapshot,
        distance: Int,
        token: LincolnManeuverToken,
    ) {
        if (!LincolnClusterSettings.enhancedNavigation(app) ||
            LincolnClusterSettings.clusterPackage(app) != LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE) return
        val index = LincolnClusterProtocol.turnIndex(snapshot.maneuverType, snapshot.drivingSide)
        if (!LincolnClusterProtocol.hasTurnIcon(index)) return
        // Route startup can contain a transient 0 m placeholder. Give the phone three seconds to
        // supply the real first distance before opening the other receivers' navigation view.
        if (distance == 0 && !auxiliaryNavOwned && nanoTime() - firstManeuverNs < START_DISTANCE_HOLD_NS) return
        if (!auxiliaryNavOwned) {
            val opened = auxiliaryBroadcast(app, Intent(LincolnClusterProtocol.ACTION_NAV_ACTIVE_OPEN))
            val status = auxiliaryBroadcast(app, standardNavStatus(LincolnClusterProtocol.VALUE_KEY_TYPE_NAV_START))
            val announced = auxiliaryBroadcast(app, Intent(LincolnClusterProtocol.ACTION_START_NAVIGATION), global = true)
            if (!opened && !status && !announced) return
            auxiliaryNavOwned = true
            Log.i(TAG, "auxiliary nav start open=$opened status=$status announced=$announced")
        }
        val street = LincolnClusterProtocol.sanitizeTurnStreet(snapshot.road)
        publishAuxiliaryTurnLocked(app, index, distance, street, token)
    }

    private fun repeatAuxiliaryNavLocked(app: Context, distance: Int, token: LincolnManeuverToken) {
        if (!auxiliaryNavOwned || lastAuxiliaryIndex == LincolnClusterProtocol.NO_TURN_INDEX) return
        publishAuxiliaryTurnLocked(app, lastAuxiliaryIndex, distance, lastAuxiliaryStreet, token)
    }

    private fun publishAuxiliaryTurnLocked(
        app: Context, index: Int, distance: Int, street: String, token: LincolnManeuverToken,
    ) {
        val intent = Intent(LincolnClusterProtocol.ACTION_TURN_BY_TURN)
        LincolnClusterProtocol.turnByTurnExtras(index, distance, street).forEach { (key, value) ->
            when (value) {
                is Int -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
            }
        }
        auxiliaryBroadcast(app, intent)
        hudSocket.update(index, distance, street, token.latitude, token.longitude)
        runCatching { LincolnNavigationDiagnostics.transport("hud-socket", "offer", true) }
        lastAuxiliaryIndex = index
        lastAuxiliaryStreet = street
    }

    private fun endAuxiliaryNavLocked(app: Context) {
        firstManeuverNs = 0L
        lastAuxiliaryIndex = LincolnClusterProtocol.NO_TURN_INDEX
        lastAuxiliaryStreet = ""
        if (!auxiliaryNavOwned) return
        auxiliaryNavOwned = false
        auxiliaryBroadcast(app, Intent(LincolnClusterProtocol.ACTION_NAV_ACTIVE_CLOSE))
        auxiliaryBroadcast(app, standardNavStatus(LincolnClusterProtocol.VALUE_KEY_TYPE_NAV_STOP))
        auxiliaryBroadcast(app, Intent(LincolnClusterProtocol.ACTION_STOP_NAVIGATION), global = true)
        hudSocket.end()
        Log.i(TAG, "auxiliary nav ended")
    }

    private fun standardNavStatus(keyType: String): Intent =
        Intent(LincolnClusterProtocol.ACTION_STANDARD_BROADCAST_SEND)
            .putExtra(LincolnClusterProtocol.EXTRA_KEY_TYPE, keyType)
            .putExtra(LincolnClusterProtocol.EXTRA_MODULE_TYPE, LincolnClusterProtocol.VALUE_MODULE_NAVI)

    private fun auxiliaryBroadcast(app: Context, intent: Intent, global: Boolean = false): Boolean = runCatching {
        if (!global) intent.setPackage(LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE)
        intent.addFlags(LincolnClusterProtocol.FLAG_INCLUDE_STOPPED_PACKAGES)
        app.sendBroadcast(intent)
        true
    }.getOrElse {
        Log.w(TAG, "auxiliary broadcast failed ${intent.action} ${it.javaClass.simpleName}")
        false
    }.also { accepted ->
        runCatching { LincolnNavigationDiagnostics.transport("auxiliary", intent.action.orEmpty(), accepted) }
    }

    /**
     * Total remaining distance/time on the cluster's own REMAIN channel. The cluster takes
     * `remainDistance` in metres and `remainTime` in seconds and formats both itself.
     */
    private fun sendRemainLocked(app: Context, snapshot: CarPlayGlance.Snapshot) {
        if (!LincolnClusterSettings.remaining(app)) {
            clearRemainLocked(app)
            return
        }
        val meters = snapshot.remainingMeters?.toInt()?.takeIf { it in 0..LincolnClusterProtocol.MAX_REMAIN_DISTANCE_METERS }
        val seconds = snapshot.remainingSeconds?.toInt()?.takeIf { it >= 0 }
        if (meters == null && seconds == null) {
            // Same rule as the instruction card: a quiet update is not a finished route.
            if (!snapshot.connected || (snapshot.routeEnded && endSettled())) clearRemainLocked(app)
            return
        }
        val key = "$meters|$seconds"
        if (key == lastRemainKey) return
        val intent = Intent(LincolnClusterProtocol.ACTION_NAV_REMAIN)
        meters?.let { intent.putExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, it) }
        seconds?.let { intent.putExtra(LincolnClusterProtocol.EXTRA_REMAIN_TIME, it) }
        if (broadcast(app, intent)) {
            lastRemainKey = key
            remainOwned = true
            remainSent++
            Log.i(TAG, "remain ${meters}m ${seconds}s (#$remainSent)")
        }
    }

    private fun sendMusicLocked(app: Context, snapshot: CarPlayGlance.Snapshot) {
        // The cluster draws one text row. Music apps put the current lyric in the iAP2 title field
        // and the song name in the artist field, so send the raw title alone: the widget's combined
        // "title — artist" would otherwise carry the song name along on the lyric line.
        val title = LincolnClusterProtocol.sanitizeMediaText(snapshot.songTitle ?: snapshot.song)
        if (title == null) {
            clearMusicLocked(app)
            return
        }
        val key = "$title|${snapshot.playing}"
        if (key == lastMusicKey) return
        if (sendMusic(app, title, snapshot.playing)) {
            lastMusicKey = key
            musicOwned = true
            musicSent++
            Log.i(TAG, "music '$title' playing=${snapshot.playing} (#$musicSent)")
        }
    }

    private fun sendMusic(app: Context, title: String, playing: Boolean): Boolean =
        broadcast(app, Intent(LincolnClusterProtocol.ACTION_MEDIA_INFO)
            .putExtra("id", "diplay-widget")
            .putExtra(LincolnClusterProtocol.EXTRA_TITLE, title)
            .putExtra(LincolnClusterProtocol.EXTRA_ARTIST, "")
            .putExtra(LincolnClusterProtocol.EXTRA_ALBUM, "")
            .putExtra(LincolnClusterProtocol.EXTRA_SOURCE_NAME, LincolnClusterProtocol.SOURCE_NAME)
            .putExtra(LincolnClusterProtocol.EXTRA_SOURCE_TYPE, LincolnClusterProtocol.SOURCE_TYPE)
            .putExtra(LincolnClusterProtocol.EXTRA_PLAYING, playing))

    private fun clearNavLocked(app: Context) {
        cancelPendingZero("navigation-cleared")
        maneuverTracker.reset("navigation-cleared")
        endAuxiliaryNavLocked(app)
        // Send OFF whenever the cluster was told ON, even if a later snapshot already dropped the
        // owned flag, so a finished route cannot leave "arrived" on the cluster.
        if (navStateOn != true) return
        if (broadcast(app, Intent(LincolnClusterProtocol.ACTION_NAV_STATE)
                .putExtra(LincolnClusterProtocol.EXTRA_STATE, LincolnClusterProtocol.STATE_OFF))) {
            navOwned = false
            navStateOn = false
            lastNavKey = null
            lastNavTurn = null
            lastNavManeuverIndex = null
            lastNavGeneration = null
            lastNavDistanceRevision = null
            lastNavUsableForZero = false
            staleClearNeedsStart = false
            lastNavDistance = null
            lastNavRoad = ""
            lastNavManeuverToken = null
            lastNavSentNs = 0L
            firstManeuverNs = 0L
            Log.i(TAG, "nav cleared")
        }
    }

    private fun clearRemainLocked(app: Context) {
        if (!remainOwned) return
        // Distance 0 and time 0 clear the readout; the cluster keeps the last value otherwise.
        if (broadcast(app, Intent(LincolnClusterProtocol.ACTION_NAV_REMAIN)
                .putExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, 0)
                .putExtra(LincolnClusterProtocol.EXTRA_REMAIN_TIME, 0))) {
            remainOwned = false
            lastRemainKey = null
            Log.i(TAG, "remain cleared")
        }
    }

    private fun clearMusicLocked(app: Context) {
        if (!musicOwned) return
        if (sendMusic(app, "", false)) {
            musicOwned = false
            lastMusicKey = null
            Log.i(TAG, "music cleared")
        }
    }

    private fun broadcast(app: Context, intent: Intent): Boolean = runCatching {
        if (intent.`package` == null) intent.setPackage(LincolnClusterSettings.clusterPackage(app))
        app.sendBroadcast(intent)
        true
    }.getOrElse {
        Log.w(TAG, "broadcast failed ${it.javaClass.simpleName}")
        false
    }.also { accepted ->
        if (intent.action == LincolnClusterProtocol.ACTION_NAV_INFO || intent.action == LincolnClusterProtocol.ACTION_NAV_STATE) {
            runCatching {
                val action = if (intent.action == LincolnClusterProtocol.ACTION_NAV_STATE) {
                    intent.action.orEmpty() + ":" + intent.getStringExtra(LincolnClusterProtocol.EXTRA_STATE).orEmpty()
                } else intent.action.orEmpty()
                LincolnNavigationDiagnostics.transport("primary", action, accepted)
            }
        }
    }
}
