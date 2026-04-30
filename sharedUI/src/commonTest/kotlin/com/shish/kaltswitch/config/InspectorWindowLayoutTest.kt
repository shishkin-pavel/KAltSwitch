package com.shish.kaltswitch.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InspectorWindowLayoutTest {

    private val savedSettingsFrame = WindowFrame(x = 10.0, y = 20.0, width = 320.0, height = 600.0)

    @Test
    fun restore_addsInspectorWidthOnlyWhenVisible() {
        assertEquals(
            savedSettingsFrame.copy(width = 800.0),
            restoredInspectorWindowFrame(
                settingsFrame = savedSettingsFrame,
                inspectorVisible = true,
                inspectorWidth = 480.0,
            ),
        )
        assertEquals(
            savedSettingsFrame,
            restoredInspectorWindowFrame(
                settingsFrame = savedSettingsFrame,
                inspectorVisible = false,
                inspectorWidth = 480.0,
            ),
        )
    }

    @Test
    fun restore_returnsNullWhenNoSavedFrameExists() {
        assertNull(restoredInspectorWindowFrame(null, inspectorVisible = true, inspectorWidth = 480.0))
    }

    @Test
    fun persist_visibleWindowKeepsSettingsWidthAndRecomputesInspectorWidth() {
        val persisted = persistInspectorWindowLayout(
            currentFrame = savedSettingsFrame.copy(width = 900.0),
            inspectorVisible = true,
            settingsWidth = 320.0,
            currentInspectorWidth = 480.0,
            minInspectorWidth = 120.0,
        )

        assertEquals(savedSettingsFrame, persisted.settingsFrame)
        assertEquals(580.0, persisted.inspectorWidth)
    }

    @Test
    fun persist_visibleWindowClampsInspectorWidth() {
        val persisted = persistInspectorWindowLayout(
            currentFrame = savedSettingsFrame.copy(width = 350.0),
            inspectorVisible = true,
            settingsWidth = 320.0,
            currentInspectorWidth = 480.0,
            minInspectorWidth = 120.0,
        )

        assertEquals(120.0, persisted.inspectorWidth)
    }

    @Test
    fun persist_hiddenWindowTreatsCurrentWidthAsSettingsWidth() {
        val hiddenFrame = savedSettingsFrame.copy(width = 420.0)
        val persisted = persistInspectorWindowLayout(
            currentFrame = hiddenFrame,
            inspectorVisible = false,
            settingsWidth = 320.0,
            currentInspectorWidth = 480.0,
            minInspectorWidth = 120.0,
        )

        assertEquals(hiddenFrame, persisted.settingsFrame)
        assertEquals(480.0, persisted.inspectorWidth)
    }

    @Test
    fun targetFrame_growsAndShrinksAroundCurrentOrigin() {
        assertEquals(
            savedSettingsFrame.copy(width = 800.0),
            inspectorVisibilityTargetFrame(
                currentFrame = savedSettingsFrame,
                visible = true,
                inspectorWidth = 480.0,
                minSettingsWidth = 200.0,
            ),
        )
        assertEquals(
            savedSettingsFrame.copy(width = 320.0),
            inspectorVisibilityTargetFrame(
                currentFrame = savedSettingsFrame.copy(width = 800.0),
                visible = false,
                inspectorWidth = 480.0,
                minSettingsWidth = 200.0,
            ),
        )
    }

    @Test
    fun targetFrame_neverShrinksBelowMinimumSettingsWidth() {
        assertEquals(
            savedSettingsFrame.copy(width = 200.0),
            inspectorVisibilityTargetFrame(
                currentFrame = savedSettingsFrame.copy(width = 300.0),
                visible = false,
                inspectorWidth = 480.0,
                minSettingsWidth = 200.0,
            ),
        )
    }
}
