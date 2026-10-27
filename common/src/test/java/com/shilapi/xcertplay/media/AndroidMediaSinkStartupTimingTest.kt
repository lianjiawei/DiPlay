package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.view.Surface
import com.shilapi.xcertplay.orchestration.WiredStartupTiming
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, qualifiers = "zh-rCN")
class AndroidMediaSinkStartupTimingTest {
    @Test fun onlyMainScreenFirstVisibleOutputCompletesStartup() {
        val lines = mutableListOf<String>()
        val timing = WiredStartupTiming(lines::add) { 0 }
        val sink = AndroidMediaSink(onMainVideoFirstFrame = {
            timing.mark(WiredStartupTiming.Stage.MAIN_FRAME_SUBMITTED)
        })
        try {
            output(sink, 111, null, mock(Surface::class.java)) // instrument screen
            output(sink, 110, "mirror", mock(Surface::class.java))
            output(sink, 110, null, null) // no visible Surface
            assertTrue(lines.isEmpty())
            output(sink, 110, null, mock(Surface::class.java), frames = 1_000)
            assertEquals(1, lines.size)
            // A replacement decoder may have its own first frame; the connection is still one.
            output(sink, 110, null, mock(Surface::class.java))
            assertEquals(1, lines.size)
        } finally { sink.close() }
    }

    @Test fun optionalTimingFailureDoesNotInterruptFrameReleaseOrExistingDiagnostics() {
        val diagnostic = mutableListOf<String>()
        val sink = AndroidMediaSink(onMainVideoFirstFrame = { error("broken optional sink") })
        sink.setVideoDiagnosticHandler(110, diagnostic::add)
        try {
            output(sink, 110, null, mock(Surface::class.java), frames = 3)
            assertEquals(1, diagnostic.count { it == "first frame rendered" })
        } finally { sink.close() }
    }

    /** Exercise the production output branch with an immediately available fake codec. */
    private fun output(sink: AndroidMediaSink, type: Int, label: String?, surface: Surface?, frames: Int = 1) {
        val decoder = AndroidMediaSink::class.java.getDeclaredMethod("newVideoDecoder",
            Int::class.javaPrimitiveType, Surface::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(sink, type, surface, label, false)
        val codec = mock(MediaCodec::class.java)
        val handle = decoder.javaClass.getDeclaredMethod("handleOutput", MediaCodec::class.java,
            Int::class.javaPrimitiveType, MediaCodec.BufferInfo::class.java).apply { isAccessible = true }
        try {
            repeat(frames) { handle.invoke(decoder, codec, 0, MediaCodec.BufferInfo()) }
            verify(codec, times(frames)).releaseOutputBuffer(0, surface != null)
        } finally { (decoder as Closeable).close() }
    }
}
