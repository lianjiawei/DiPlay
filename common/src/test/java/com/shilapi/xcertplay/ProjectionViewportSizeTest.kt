package com.shilapi.xcertplay

import androidx.core.graphics.Insets
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionViewportSizeTest {
    @Test fun visibleStatusBarIsRemovedBeforeCarPlayNegotiation() {
        assertEquals(
            ProjectionViewportSize(1920, 942),
            projectionViewportSize(
                1920, 990,
                hideTopBar = false,
                hideBottomBar = true,
                statusBars = Insets.of(0, 48, 0, 0),
                navigationBars = Insets.NONE,
            ),
        )
    }

    @Test fun visibleBottomNavigationBarIsRemovedIndependently() {
        assertEquals(
            ProjectionViewportSize(1920, 930),
            projectionViewportSize(
                1920, 990,
                hideTopBar = true,
                hideBottomBar = false,
                statusBars = Insets.of(0, 48, 0, 0),
                navigationBars = Insets.of(0, 0, 0, 60),
            ),
        )
    }

    @Test fun sideNavigationBarReducesWidthInsteadOfAddingPillarboxingLater() {
        assertEquals(
            ProjectionViewportSize(1840, 990),
            projectionViewportSize(
                1920, 990,
                hideTopBar = true,
                hideBottomBar = false,
                statusBars = Insets.NONE,
                navigationBars = Insets.of(0, 0, 80, 0),
            ),
        )
    }

    @Test fun fullyHiddenBarsKeepTheEntireCanvas() {
        assertEquals(
            ProjectionViewportSize(1920, 990),
            projectionViewportSize(
                1920, 990,
                hideTopBar = true,
                hideBottomBar = true,
                statusBars = Insets.of(0, 48, 0, 0),
                navigationBars = Insets.of(0, 0, 0, 60),
                displayCutout = Insets.of(24, 0, 0, 0),
            ),
        )
    }

    @Test fun visibleBarsUseTheUnionOfBarsAndCutout() {
        assertEquals(
            ProjectionViewportSize(1872, 882),
            projectionViewportSize(
                1920, 990,
                hideTopBar = false,
                hideBottomBar = false,
                statusBars = Insets.of(0, 48, 0, 0),
                navigationBars = Insets.of(0, 0, 0, 60),
                displayCutout = Insets.of(24, 6, 24, 0),
            ),
        )
    }

    @Test fun invalidInsetsCannotProduceAnInvalidCarPlayCanvas() {
        assertEquals(
            ProjectionViewportSize(100, 50),
            projectionViewportSize(
                100, 50,
                hideTopBar = false,
                hideBottomBar = false,
                statusBars = Insets.of(60, 40, 60, 40),
                navigationBars = Insets.NONE,
            ),
        )
    }
}
