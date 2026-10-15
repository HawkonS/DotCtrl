package tech.hawkon.dotctrl

import tech.hawkon.dotctrl.data.*
import org.junit.Assert.*
import org.junit.Test

class PresetTest {
    @Test fun builtInSwipeIsSeededAndKeepsUserScripts() {
        val user = ClickScript(id = "user", name = "我的脚本", steps = listOf(ScriptStep()))
        val seeded = ensureBuiltInScripts(listOf(user), orientation = 1)
        assertEquals(listOf("user", "builtin-swipe-down"), seeded.map { it.id })
        assertTrue(seeded.last().builtIn)
        assertEquals(GestureType.SWIPE, seeded.last().steps.single().gestureType)
        val rotated = ensureBuiltInScripts(seeded, orientation = 2)
        assertEquals(user, rotated.first())
        assertEquals(2, rotated.last().orientation)
    }

    @Test fun switchingModeKeepsTheChosenPositionAndOrientation() {
        val selected = ClickSettings(x = .12f, y = .83f, orientation = 2, positionSet = true)
        builtInModes.forEach { preset ->
            val updated = preset.mode.applyTo(selected)
            updated.validate()
            assertEquals(selected.x, updated.x)
            assertEquals(selected.y, updated.y)
            assertEquals(selected.orientation, updated.orientation)
            assertTrue(updated.positionSet)
            assertEquals(preset.mode, ClickMode.from(updated))
        }
    }

    @Test fun switchingPositionKeepsCountTimingAndSafetyLimit() {
        val selected = ClickSettings(intervalMs = 400, repeatCount = 35, continuous = true,
            gestureType = GestureType.LONG_PRESS, gestureDurationMs = 800, timeoutMinutes = 2)
        val preset = PositionPreset(name = "确认按钮", x = .2f, y = .8f, orientation = 2)
        val updated = preset.applyTo(selected)
        assertEquals(ClickMode.from(selected), ClickMode.from(updated))
        assertTrue(preset.matches(updated))
        assertEquals(2, updated.orientation)
    }

    @Test fun renamedPresetsAndCustomModeSurviveSerialization() {
        val position = PositionPreset(name = "领取按钮", x = .2f, y = .8f, orientation = 1)
        val mode = ModePreset(name = "点十次", mode = ClickMode(intervalMs = 300, repeatCount = 10))
        val library = PresetLibrary(positions = listOf(position), modes = listOf(mode))
        val restored = AppJson.format.decodeFromString<PresetLibrary>(AppJson.format.encodeToString(library))
        restored.validate()
        assertEquals(library, restored)
        val selected = mode.mode.applyTo(position.applyTo(ClickSettings()))
        assertEquals("领取按钮", restored.positionName(selected))
        assertEquals("点十次", restored.modeName(selected))
        assertEquals("自定义", restored.modeName(selected.copy(repeatCount = 11)))
        assertEquals("自定义", PresetLibrary().positionName(selected))
    }

    @Test fun presetsRejectInvalidAndDuplicateData() {
        val position = PositionPreset(name = "位置", x = .1f, y = .5f, orientation = 1)
        val mode = ModePreset(name = "模式")
        listOf(
            PresetLibrary(version = 2),
            PresetLibrary(positions = listOf(position, position)),
            PresetLibrary(positions = listOf(position.copy(x = Float.NaN))),
            PresetLibrary(positions = listOf(position.copy(orientation = 0))),
            PresetLibrary(modes = listOf(mode, mode)),
            PresetLibrary(modes = listOf(mode.copy(name = " "))),
            PresetLibrary(modes = listOf(mode.copy(mode = ClickMode(repeatCount = 0)))),
            PresetLibrary(modes = listOf(mode.copy(mode = ClickMode(timeoutMinutes = 0)))),
            PresetLibrary(modes = listOf(mode.copy(mode = ClickMode(gestureType = GestureType.LONG_PRESS, gestureDurationMs = 80)))),
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid.validate() } }
    }

    @Test fun configFileRoundTripsPresetsAndScriptsTogether() {
        val library = PresetLibrary(
            positions = listOf(PositionPreset(name = "确认", x = .2f, y = .8f, orientation = 1)),
            modes = listOf(ModePreset(name = "快速", mode = ClickMode(intervalMs = 120))),
        )
        val scripts = listOf(ClickScript(name = "签到", steps = listOf(ScriptStep(x = .2f, y = .8f))))
        val restored = ConfigCodec.decode(ConfigCodec.encode(library, scripts))
        assertEquals(library, restored.presets)
        assertEquals(scripts, restored.scripts)
    }

    @Test fun configFileRejectsUnsupportedVersion() {
        val json = AppJson.format.encodeToString(ConfigFile(formatVersion = 2))
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decode(json) }
    }
}
