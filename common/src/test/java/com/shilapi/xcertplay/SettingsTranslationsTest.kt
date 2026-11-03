package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// Lint does not check this library module's translations, so Settings copy is guarded here.
class SettingsTranslationsTest {
    private val res = File("src/main/res")
    private val placeholder = Regex("%\\d+\\$[sd]")

    @Test fun everySettingsStringIsTranslatedWithTheSamePlaceholders() {
        val base = strings(File(res, "values")).filterKeys { it.startsWith("settings_") }
        val locales = res.listFiles { file -> file.name.matches(Regex("values-[a-z]{2}(-r[A-Z]{2})?")) }!!
        for (locale in locales) {
            val translated = strings(locale)
            assertEquals(locale.name, emptySet<String>(), base.keys - translated.keys)
            for ((name, text) in base) {
                assertEquals("${locale.name}/$name", placeholders(text), placeholders(translated.getValue(name)))
            }
        }
    }

    @Test fun settingsCopyDoesNotUseGenericExperimentalLabelsInEitherLanguage() {
        // Some controls keep historical resource names without the settings_ prefix. Include
        // vehicle-specific and dialog-only titles even when the current head unit hides them.
        val otherTitles = setOf(
            "hotspot_join_title", "platform21_cluster_mode", "carplay_map_on_instrument_cluster_experimental",
            "boot_start_repair", "cluster_small_window_auto", "adb_cluster_activity_mode",
            "carplay_call_controls_experimental", "side_panel", "main_buffered_audio",
            "call_echo_cancellation", "call_voice_filter", "phone_audio_route", "phone_audio_route_apps",
            "smooth_video",
        )
        val genericRemark = Regex("实验性|\\bexperimental\\b", RegexOption.IGNORE_CASE)
        for (folderName in listOf("values", "values-zh-rCN")) {
            val translations = strings(File(res, folderName))
            assertEquals(folderName, emptySet<String>(), otherTitles - translations.keys)
            val labels = translations.filterKeys { it.startsWith("settings_") || it in otherTitles }
            assertEquals(folderName, emptySet<String>(), labels.filterValues { genericRemark.containsMatchIn(it) }.keys)
        }
    }

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.toSet()

    private fun strings(folder: File): Map<String, String> = folder.listFiles { file -> file.extension == "xml" }!!
        .flatMap { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { nodes.item(it) }.map { it.attributes.getNamedItem("name").nodeValue to it.textContent }
        }.toMap()
}
