package com.shilapi.xcertplay

/** Semantic colors for a connection placeholder; video content keeps its own day/night policy. */
internal data class WaitingScreenColors(val background: Int, val text: Int, val secondary: Int) {
    companion object {
        fun of(night: Boolean): WaitingScreenColors = DiPlayPalette.of(night).let {
            WaitingScreenColors(it.background, it.primaryText, it.secondaryText)
        }
    }
}
