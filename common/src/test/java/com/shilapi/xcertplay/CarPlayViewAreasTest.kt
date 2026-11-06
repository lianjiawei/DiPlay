package com.shilapi.xcertplay

import com.shilapi.xcertplay.CarPlayViewAreas.Kind
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_BOTTOM
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayViewAreasTest {
    // On a Tang, BYD's split screen gives DiPlay 1270x1208 of the 2560x1440 screen.
    private val split = 1270f / 2560 to 1208f / 1440

    @Test
    fun automaticDockWithoutSplitScreenNeedsNoAreas() {
        assertNull(CarPlayViewAreas.build(2560, 1440, CarPlayDock.AUTOMATIC, splitWindow = null))
    }

    @Test
    fun fixedDockDeclaresTheScreenOncePerEdgeAndStartsOnTheChosenOne() {
        val driver = CarPlayViewAreas.build(2560, 1440, CarPlayDock.DRIVER_SIDE, splitWindow = null)!!
        assertEquals(listOf(DOCK_EDGE_DRIVER_SIDE, DOCK_EDGE_BOTTOM), driver.areas.map { it.dockEdge })
        assertEquals(0, driver.current)
        val bottom = CarPlayViewAreas.build(2560, 1440, CarPlayDock.BOTTOM, splitWindow = null)!!
        assertEquals(1, bottom.current)
        assertEquals(0, bottom.withDock(DOCK_EDGE_DRIVER_SIDE))
    }

    @Test
    fun splitScreenAddsTheWindowSizedAreaPerEdge() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.BOTTOM, split)!!
        assertEquals(4, areas.areas.size)
        assertEquals(listOf(Kind.FULL_SCREEN, Kind.FULL_SCREEN, Kind.SPLIT_SCREEN, Kind.SPLIT_SCREEN), areas.areas.indices.map(areas::kindOf))
        val splitBottom = areas.index(Kind.SPLIT_SCREEN, DOCK_EDGE_BOTTOM)!!
        assertEquals(1270, areas.areas[splitBottom].width)
        assertEquals(1208, areas.areas[splitBottom].height)
    }

    @Test
    fun theWindowPicksSplitScreenOnlyWhenSplitAndCloseInShape() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.AUTOMATIC, split)!!
        assertEquals(0, areas.current)
        assertEquals(1, areas.indexFor(1270, 1208, splitScreen = true))
        // A full-size window, or a window far from the area's shape (a floating one), keeps the whole screen.
        assertEquals(0, areas.indexFor(2560, 1440, splitScreen = false))
        assertEquals(0, areas.indexFor(600, 1200, splitScreen = true))
    }

    @Test
    fun theDockMovesWithinTheSameKindOfArea() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.DRIVER_SIDE, split)!!
        areas.use(areas.indexFor(1270, 1208, splitScreen = true)!!)
        assertEquals(Kind.SPLIT_SCREEN, areas.kindOf(areas.current))
        val moved = areas.withDock(DOCK_EDGE_BOTTOM)!!
        assertEquals(Kind.SPLIT_SCREEN, areas.kindOf(moved))
        assertEquals(DOCK_EDGE_BOTTOM, areas.areas[moved].dockEdge)
    }

    private fun turning(dock: CarPlayDock = CarPlayDock.AUTOMATIC, splits: Boolean = false, startPortrait: Boolean = false) =
        CarPlayViewAreas.build(2560, 2560, listOf(
            CarPlayViewAreas.Screen(2560, 1440, portrait = false),
            CarPlayViewAreas.Screen(1440, 2560, portrait = true),
        ), dock, splitWindow = { portrait ->
            if (!splits) null else if (portrait) 1f to 1154f / 2560 else split
        }, startPortrait = startPortrait)!!

    @Test
    fun aSquareCanvasHoldsBothScreensAndStartsInTheWindowsOne() {
        val areas = turning()
        assertEquals(true, areas.turnsWithScreen)
        assertEquals(listOf(2560 to 1440, 1440 to 2560), areas.areas.map { it.width to it.height })
        assertEquals(0, areas.current)
        assertEquals(1, turning(startPortrait = true).current)
    }

    @Test
    fun turningPicksTheOtherScreenAndSplitScreenPicksItsOrientationsArea() {
        val areas = turning(splits = true)
        assertEquals(1, areas.indexFor(1440, 2560, splitScreen = false, portrait = true))
        val portraitSplit = areas.indexFor(1440, 1154, splitScreen = true, portrait = true)!!
        assertEquals(Kind.SPLIT_SCREEN, areas.kindOf(portraitSplit))
        assertEquals(1440 to 1154, areas.areas[portraitSplit].let { it.width to it.height })
        val landscapeSplit = areas.indexFor(1270, 1208, splitScreen = true, portrait = false)!!
        assertEquals(1270 to 1208, areas.areas[landscapeSplit].let { it.width to it.height })
    }

    @Test
    fun aPlainCanvasHasNoAreaForTheOtherScreen() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.BOTTOM, splitWindow = null)!!
        assertEquals(false, areas.turnsWithScreen)
        assertNull(areas.indexFor(1440, 2560, splitScreen = false, portrait = true))
    }

    @Test
    fun aSquareCanvasNeedsAreasEvenWithAnAutomaticDock() {
        val areas = turning()
        assertEquals(listOf<Int?>(null, null), areas.areas.map { it.dockEdge })
    }

    @Test
    fun theSidePanelSitsOnTheRightOrAtTheBottomOfCarPlaysTwoThirds() {
        val areas = CarPlayViewAreas.build(2560, 2560, listOf(
            CarPlayViewAreas.Screen(2560, 1440, portrait = false),
            CarPlayViewAreas.Screen(1440, 2560, portrait = true),
        ), CarPlayDock.AUTOMATIC, splitWindow = { null }, startPortrait = false, sidePanel = true)!!
        val landscape = areas.sidePanel(portrait = false)!!
        assertEquals(Kind.SIDE_PANEL, areas.kindOf(landscape))
        assertEquals(1706 to 1440, areas.areas[landscape].let { it.width to it.height })
        assertEquals(0, areas.areas[landscape].originX)
        val portrait = areas.sidePanel(portrait = true)!!
        assertEquals(1440 to 1706, areas.areas[portrait].let { it.width to it.height })
        // The canvas is laid out by the whole screen; the panel covers the rest.
        assertEquals(2560 to 1440, areas.layoutArea(landscape).let { it.width to it.height })
        assertEquals(1440 to 2560, areas.layoutArea(portrait).let { it.width to it.height })
        assertEquals(AirPlayViewArea(854, 1440, 1706, 0), areas.panelRect(landscape))
        assertEquals(AirPlayViewArea(1440, 854, 0, 1706), areas.panelRect(portrait))
        assertNull(areas.panelRect(areas.current))
    }

    @Test
    fun aRightHandDriveCarKeepsCarPlayOnTheRightWithThePanelOnTheLeft() {
        val areas = CarPlayViewAreas.build(2560, 2560, listOf(
            CarPlayViewAreas.Screen(2560, 1440, portrait = false),
            CarPlayViewAreas.Screen(1440, 2560, portrait = true),
        ), CarPlayDock.AUTOMATIC, splitWindow = { null }, startPortrait = false, sidePanel = true, rightHandDrive = true)!!
        val landscape = areas.sidePanel(portrait = false)!!
        assertEquals(854, areas.areas[landscape].originX)
        assertEquals(AirPlayViewArea(854, 1440, 0, 0), areas.panelRect(landscape))
        // A portrait screen keeps the panel at the bottom.
        assertEquals(AirPlayViewArea(1440, 854, 0, 1706), areas.panelRect(areas.sidePanel(portrait = true)!!))
    }

    @Test
    fun theSidePanelLeavesCarPlayTwoThirdsHalfOrOneThird() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.AUTOMATIC, splitWindow = null, sidePanel = true)!!
        assertEquals(listOf(1706, 1280, 852), CarPlayViewAreas.SIDE_PANEL_SHARES.map { areas.areas[areas.sidePanel(share = it)!!].width })
        // The panel covers the rest of the screen in each.
        assertEquals(AirPlayViewArea(1708, 1440, 852, 0), areas.panelRect(areas.sidePanel(share = 40)!!))
        // Two thirds without a choice.
        assertEquals(areas.sidePanel(share = 80), areas.sidePanel())
    }

    @Test
    fun releasingThePanelEdgeSnapsCarPlayToTheNearestShare() {
        assertEquals(80, CarPlayViewAreas.nearestSidePanelShare(0.8, music = false))
        assertEquals(60, CarPlayViewAreas.nearestSidePanelShare(0.55, music = false))
        assertEquals(40, CarPlayViewAreas.nearestSidePanelShare(0.2, music = false))
        assertEquals(90, CarPlayViewAreas.nearestSidePanelShare(0.75, music = true))
        assertEquals(100, CarPlayViewAreas.nearestSidePanelShare(0.84, music = true))
        assertEquals(105, CarPlayViewAreas.nearestSidePanelShare(0.875, music = true))
        assertEquals(96, CarPlayViewAreas.nearestSidePanelShare(0.8, music = true))
        assertEquals(93, CarPlayViewAreas.nearestSidePanelShare(0.775, music = true))
        assertEquals(87, CarPlayViewAreas.nearestSidePanelShare(0.725, music = true))
        assertEquals(84, CarPlayViewAreas.nearestSidePanelShare(0.7, music = true))
        assertEquals(72, CarPlayViewAreas.nearestSidePanelShare(0.6, music = true))
        // Both sides of the midpoint between the 20% and 22.5% widths.
        assertEquals(96, CarPlayViewAreas.nearestSidePanelShare(0.788, music = true))
        assertEquals(93, CarPlayViewAreas.nearestSidePanelShare(0.787, music = true))
    }

    @Test fun musicPanelAddsFinerWidthsAndKeepsTheDockOnTheSameSize() {
        for (rightHandDrive in listOf(false, true)) {
            val areas = CarPlayViewAreas.build(1920, 1080, CarPlayDock.DRIVER_SIDE, splitWindow = null,
                sidePanel = true, rightHandDrive = rightHandDrive, narrowMusicPanel = true)!!
            for ((share, width) in listOf(105 to 240, 100 to 320, 96 to 384, 93 to 432, 90 to 480,
                87 to 528, 84 to 576, 80 to 640, 72 to 768, 60 to 960, 40 to 1280)) {
                val selected = areas.sidePanel(share = share)!!
                val panel = areas.panelRect(selected)!!
                assertEquals(width, panel.width)
                assertEquals(if (rightHandDrive) 0 else 1920 - width, panel.originX)
                assertEquals(1080, panel.height)
                areas.use(selected)
                val moved = areas.withDock(DOCK_EDGE_BOTTOM)!!
                assertEquals(panel, areas.panelRect(moved))
                assertEquals(DOCK_EDGE_BOTTOM, areas.areas[moved].dockEdge)
            }
        }
    }

    @Test fun narrowMusicPanelAlsoWorksOnAPortraitScreen() {
        val areas = CarPlayViewAreas.build(1080, 1920, CarPlayDock.AUTOMATIC, splitWindow = null,
            sidePanel = true, narrowMusicPanel = true)!!
        assertEquals(AirPlayViewArea(1080, 480, 0, 1440), areas.panelRect(areas.sidePanel(share = 90)!!))
        assertEquals(AirPlayViewArea(1080, 320, 0, 1600), areas.panelRect(areas.sidePanel(share = 100)!!))
        assertEquals(AirPlayViewArea(1080, 384, 0, 1536), areas.panelRect(areas.sidePanel(share = 96)!!))
        assertEquals(AirPlayViewArea(1080, 240, 0, 1680), areas.panelRect(areas.sidePanel(share = 105)!!))
    }

    @Test fun narrowMusicAreasAreAdvertisedAndSelectedInTheCarPlayCommands() {
        val areas = CarPlayViewAreas.build(1920, 1080, CarPlayDock.AUTOMATIC, splitWindow = null,
            sidePanel = true, narrowMusicPanel = true)!!
        val info = AirPlayInfoPlist.build(AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02", sourceVersion = "366.0", main = AirPlayDisplayConfig(
                widthPixels = 1920, heightPixels = 1080, viewAreas = areas.areas, initialViewArea = areas.current)))
        val display = (info["displays"] as List<*>).first() as Map<*, *>
        val declared = (display["viewAreas"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf(1920, 1280, 960, 640, 1440, 1600, 1680, 1536, 1488, 1392, 1344, 1152),
            declared.map { it["widthPixels"] })
        for ((share, expectedWidth) in listOf(90 to 1440, 100 to 1600, 105 to 1680, 96 to 1536,
            93 to 1488, 87 to 1392, 84 to 1344, 72 to 1152)) {
            val index = areas.sidePanel(share = share)!!
            val command = AirPlayInfoPlist.viewAreaCommand(index, areas.areas.size)
            val params = command["params"] as Map<*, *>
            assertEquals(expectedWidth, declared[index]["widthPixels"])
            assertEquals(index, params["viewAreaIndex"])
            assertEquals((declared.indices - index).toList(), params["adjacentViewAreas"])
        }
    }

    @Test
    fun aWindowNeverPicksTheSidePanelByItself() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.AUTOMATIC, splitWindow = null, sidePanel = true)!!
        assertEquals(0, areas.indexFor(2560, 1440, splitScreen = false))
        assertEquals(0, areas.indexFor(1706, 1440, splitScreen = false))
        areas.use(areas.sidePanel()!!)
        assertEquals(0, areas.indexFor(2560, 1440, splitScreen = false))
    }

    @Test
    fun theDockMovesBesideTheSidePanelToo() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.DRIVER_SIDE, splitWindow = null, sidePanel = true)!!
        areas.use(areas.sidePanel()!!)
        val moved = areas.withDock(DOCK_EDGE_BOTTOM)!!
        assertEquals(Kind.SIDE_PANEL, areas.kindOf(moved))
        assertEquals(DOCK_EDGE_BOTTOM, areas.areas[moved].dockEdge)
    }

    @Test fun aSplitWindowKeepsItsShapeWhetherOrNotTheSystemBarsAreShown() {
        // My Tang with the navigation bar shown: 2560x1440 screen, CarPlay's full window 2560x1320 in
        // landscape and 1440x2440 in portrait; split screen gives 1270x1208 and 1440x1154 windows.
        val (landscapeWindow, portraitWindow) = CarPlayRotation.turnedWindows(2560, 1320, 2560, 1440)
        assertEquals(2560 to 1320, landscapeWindow)
        assertEquals(1440 to 2440, portraitWindow)
        val landscapeSplit = SplitScreenSettings.ofWindow(1270f / 2560 to 1208f / 1440, 2560, 1440, 2560, 1320)
        val portraitSplit = SplitScreenSettings.ofWindow(1440f / 1440 to 1154f / 2560, 1440, 2560, 1440, 2440)
        val (landscape, portrait) = CarPlayRotation.turningAreas(1920, 2560, 1320, 2560, 1440)
        val areas = CarPlayViewAreas.build(1920, 1920, listOf(
            CarPlayViewAreas.Screen(landscape.first, landscape.second, portrait = false),
            CarPlayViewAreas.Screen(portrait.first, portrait.second, portrait = true),
        ), CarPlayDock.AUTOMATIC, splitWindow = { if (it) portraitSplit else landscapeSplit }, startPortrait = false)!!
        val landscapeArea = areas.areas[areas.index(CarPlayViewAreas.Kind.SPLIT_SCREEN, false, null)!!]
        val portraitArea = areas.areas[areas.index(CarPlayViewAreas.Kind.SPLIT_SCREEN, true, null)!!]
        // Each split area has its window's shape (within rounding to even pixels), so nothing is stretched.
        assertEquals(1270.0 / 1208, landscapeArea.width.toDouble() / landscapeArea.height, 0.01)
        assertEquals(1440.0 / 1154, portraitArea.width.toDouble() / portraitArea.height, 0.01)
        assertEquals(areas.areas.indexOf(landscapeArea), areas.indexFor(1270, 1208, splitScreen = true, portrait = false))
        assertEquals(areas.areas.indexOf(portraitArea), areas.indexFor(1440, 1154, splitScreen = true, portrait = true))
    }

    @Test fun theExpectedSplitWindowMatchesTheHeadUnitsBeforeAnyIsSeen() {
        // My Tang: 2560x1440, a 112 px status bar, a 120 px navigation bar and a 20 px divider. It gave
        // 1270x1208 side by side and 1440x1154 stacked.
        val landscape = SplitScreenSettings.expectedWindow(false, 2560, 1440, 112, 120, 20)!!
        val portrait = SplitScreenSettings.expectedWindow(true, 2560, 1440, 112, 120, 20)!!
        assertEquals(1270f / 2560, landscape.first, 1e-6f)
        assertEquals(1208f / 1440, landscape.second, 1e-6f)
        assertEquals(1f, portrait.first, 1e-6f)
        assertEquals(1154f / 2560, portrait.second, 1e-6f)
        assertNull(SplitScreenSettings.expectedWindow(true, 0, 0, 112, 120, 20))
    }
}
