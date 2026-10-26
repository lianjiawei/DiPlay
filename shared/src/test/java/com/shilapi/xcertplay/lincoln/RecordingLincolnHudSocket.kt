package com.shilapi.xcertplay.lincoln

internal class RecordingLincolnHudSocket : LincolnHudSocket {
    data class Turn(val index: Int, val metres: Int, val street: String,
        val latitude: Double?, val longitude: Double?)

    var running = false
    var starts = 0
    var ends = 0
    var stops = 0
    val turns = mutableListOf<Turn>()

    override fun start() {
        if (!running) {
            running = true
            starts++
        }
    }

    override fun update(index: Int, metres: Int, street: String, latitude: Double?, longitude: Double?) {
        if (running) turns += Turn(index, metres, street, latitude, longitude)
    }

    override fun end() { ends++ }

    override fun stop() {
        if (running) {
            running = false
            stops++
        }
    }

    override fun diagnostics(): String = "test"
}
