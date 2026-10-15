package tech.hawkon.dotctrl.data

import android.content.Context
import android.util.AtomicFile
import java.io.File

class ClickSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("click_settings", Context.MODE_PRIVATE)
    fun read(): ClickSettings = runCatching {
        AppJson.format.decodeFromString<ClickSettings>(preferences.getString("settings", null) ?: "{}")
            .also { it.validate() }
    }.getOrDefault(ClickSettings())
    fun write(settings: ClickSettings) {
        settings.validate()
        preferences.edit().putString("settings", AppJson.format.encodeToString(settings)).apply()
    }
}

class ScriptRepository(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "scripts.json"))
    fun load(): List<ClickScript> {
        if (!file.baseFile.exists()) return emptyList()
        return file.openRead().bufferedReader().use { ScriptCodec.decode(it.readText()) }
    }
    fun save(scripts: List<ClickScript>) {
        val bytes = ScriptCodec.encode(scripts).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
    fun upsert(script: ClickScript) {
        val current = load()
        save(if (current.any { it.id == script.id }) current.map { if (it.id == script.id) script else it }
            else current + script)
    }
}

class PresetStore(context: Context) {
    private val preferences = context.getSharedPreferences("click_presets", Context.MODE_PRIVATE)
    fun read(): PresetLibrary = AppJson.format.decodeFromString<PresetLibrary>(
        preferences.getString("library", null) ?: "{}",
    ).also { it.validate() }

    fun write(library: PresetLibrary) {
        library.validate()
        preferences.edit().putString("library", AppJson.format.encodeToString(library)).apply()
    }
}
