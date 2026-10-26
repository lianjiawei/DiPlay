package com.shilapi.xcertplay.lincoln

import java.io.DataInputStream
import java.net.Socket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33])
class LincolnHudSocketServerTest {
    @Test
    @Config(sdk = [33])
    fun authenticatedHudClientReceivesTheSameManeuverThenRouteEnd() {
        val productionPorts = LincolnHudSocketServer.ports
        LincolnHudSocketServer.ports = intArrayOf(0)
        LincolnHudSocketServer.start()
        try {
            val deadline = System.nanoTime() + 2_000_000_000L
            var port: Int? = null
            while (port == null && System.nanoTime() < deadline) {
                port = Regex("bound=\\[(\\d+)").find(LincolnHudSocketServer.diagnostics())
                    ?.groupValues?.get(1)?.toInt()
                if (port == null) Thread.sleep(20)
            }
            val boundPort = requireNotNull(port) { "HUD listener did not bind a loopback port" }
            Socket("127.0.0.1", boundPort).use { socket ->
                socket.soTimeout = 2_000
                val input = DataInputStream(socket.getInputStream())
                val auth = JSONObject().put("data", JSONObject()
                    .put("messageType", LincolnHudSocketServer.MSG_AUTH)
                    .put("messageData", JSONObject()))
                val bytes = auth.toString().toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                        (bytes.size ushr 8).toByte(), bytes.size.toByte()))
                    write(bytes)
                    write(byteArrayOf(13, 10))
                    flush()
                }
                assertEquals(LincolnHudSocketServer.MSG_AUTH_ACK, readFrame(input).getInt("messageType"))
                assertEquals(LincolnHudSocketServer.MSG_INIT, readFrame(input).getInt("messageType"))

                LincolnHudSocketServer.update(7, 350, "Test Road", 1.0, 2.0)
                val turn = readFrame(input)
                assertEquals(LincolnHudSocketServer.MSG_MANEUVER, turn.getInt("messageType"))
                assertEquals(350, turn.getJSONObject("messageData").getInt("distance"))
                assertEquals("Test Road", turn.getJSONObject("messageData").getString("nextRoadName"))
                assertEquals(LincolnHudSocketServer.MSG_ROAD, readFrame(input).getInt("messageType"))

                LincolnHudSocketServer.end()
                val end = readFrame(input)
                assertEquals(LincolnHudSocketServer.MSG_MANEUVER, end.getInt("messageType"))
                assertTrue(end.getJSONObject("messageData").getBoolean("isNaviEnd"))
            }
        } finally {
            LincolnHudSocketServer.stop()
            LincolnHudSocketServer.ports = productionPorts
        }
    }

    private fun readFrame(input: DataInputStream): JSONObject {
        val length = input.readInt()
        assertTrue(length in 1..262_144)
        val body = ByteArray(length)
        input.readFully(body)
        assertEquals(13, input.readUnsignedByte())
        assertEquals(10, input.readUnsignedByte())
        return JSONObject(String(body, Charsets.UTF_8)).getJSONObject("data")
    }

    @Test
    fun formatsThePanelDistanceTheWayTheHudExpects() {
        assertEquals(0, LincolnHudSocketServer.hudDistance(-1))
        assertEquals(0, LincolnHudSocketServer.hudDistance(0))
        assertEquals(350, LincolnHudSocketServer.hudDistance(350))
        assertEquals(999, LincolnHudSocketServer.hudDistance(999))
        assertEquals(1_000, LincolnHudSocketServer.hudDistance(1_000))
        assertEquals(2_500, LincolnHudSocketServer.hudDistance(2_500))
        assertEquals(12_300, LincolnHudSocketServer.hudDistance(12_300))
        assertEquals(99_900, LincolnHudSocketServer.hudDistance(99_999))
        assertEquals(100_000, LincolnHudSocketServer.hudDistance(100_000))
        assertEquals(123_000, LincolnHudSocketServer.hudDistance(123_456))
    }

    @Test
    fun speaksTheManeuverTipTheHudShows() {
        assertEquals("350米后进入Test Road", LincolnHudSocketServer.maneuverTips(350, "Test Road"))
        assertEquals("350米后", LincolnHudSocketServer.maneuverTips(350, ""))
        assertEquals("沿Test Road 行驶", LincolnHudSocketServer.maneuverTips(0, "Test Road"))
    }

    @Test
    fun sendsTheManeuverFrameTheTestedBuildSends() {
        val data = LincolnHudSocketServer.maneuverData(index = 7, metres = 350, street = "Test Road")

        assertEquals("turn_left", data.getString("maneuverName"))
        assertFalse(data.getBoolean("straight"))
        assertEquals(350, data.getInt("distance"))
        assertEquals("Test Road", data.getString("nextRoadName"))
        assertEquals("Test Road", data.getString("currentRoadName"))
        assertEquals("350米后进入Test Road", data.getString("maneuverTips"))
        assertFalse(data.getBoolean("isHighWay"))
        assertEquals(0, data.getInt("laneCount"))
    }

    @Test
    fun aStraightManeuverIsMarkedStraightAndABlankRoadFallsBack() {
        val straight = LincolnHudSocketServer.maneuverData(index = 1, metres = 350, street = "")

        assertTrue(straight.getBoolean("straight"))
        assertEquals("turn_front", straight.getString("maneuverName"))
        assertEquals("当前道路", straight.getString("currentRoadName"))
        assertEquals("350米后进入当前道路", straight.getString("maneuverTips"))
    }

    @Test
    fun anUnknownIndexStillNamesTheStraightArrow() {
        val data = LincolnHudSocketServer.maneuverData(
            index = LincolnClusterProtocol.NO_TURN_INDEX,
            metres = 0,
            street = "",
        )

        assertEquals("turn_front", data.getString("maneuverName"))
        assertEquals(0, data.getInt("distance"))
    }

    @Test
    fun theEndFrameClearsTheHudCard() {
        val data = LincolnHudSocketServer.endManeuverData()

        assertTrue(data.getBoolean("isNaviEnd"))
        assertTrue(data.getBoolean("straight"))
        assertEquals(0, data.getInt("distance"))
        assertEquals("", data.getString("maneuverName"))
        assertEquals("", data.getString("nextRoadName"))
        assertEquals("", data.getString("currentRoadName"))
    }

    @Test
    fun theRoadFrameCarriesOnlyTheCurrentRoad() {
        assertEquals("Main Street", LincolnHudSocketServer.roadData("Main Street").getString("currentRoadName"))
        assertEquals("", LincolnHudSocketServer.roadData("").getString("currentRoadName"))
    }

    /**
     * The route end must not take the socket down: the car's navigation client stays connected, so
     * the next route in the same drive starts without a reconnect (and without the empty card that
     * reconnect left behind). Only a dropped session or a switched-off output releases the ports.
     */
    @Test
    fun theSocketKeepsServingAfterTheRouteEnds() {
        LincolnHudSocketServer.start()
        try {
            assertTrue(LincolnHudSocketServer.running)
            LincolnHudSocketServer.end()
            assertTrue(LincolnHudSocketServer.running)
            assertTrue(LincolnHudSocketServer.diagnostics().startsWith("running=true"))
        } finally {
            LincolnHudSocketServer.stop()
        }
        assertFalse(LincolnHudSocketServer.running)
        assertTrue(LincolnHudSocketServer.diagnostics().startsWith("running=false"))
    }
}
