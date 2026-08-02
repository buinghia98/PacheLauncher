package com.teampacheworks.launcher.input

import android.content.Context
import android.view.InputDevice
import com.teampacheworks.launcher.log.LauncherLog
import java.util.Locale

/**
 * `assets/gamecontrollerdb.txt`, filtered to `platform:Android` rows (design spec §5).
 *
 * Source: https://github.com/mdqinc/SDL_GameControllerDB (the repo moved from gabomdq). The
 * library ships a copy of this file as a launcher asset; a host app may replace/extend it by
 * shadowing the same asset path with its own copy if it needs newer entries.
 */
object GameControllerDb {

    const val ASSET = "gamecontrollerdb.txt"

    /** One row: `<guid>,<name>,a:b0,b:b1,…,platform:Android,` */
    data class Entry(val guid: String, val name: String, val bindings: Map<String, String>)

    private var cache: List<Entry>? = null
    private var byGuid: Map<String, Entry> = emptyMap()

    fun load(context: Context): List<Entry> {
        cache?.let { return it }
        val parsed = try {
            context.assets.open(ASSET).bufferedReader().use { parse(it.lineSequence()) }
        } catch (t: Throwable) {
            LauncherLog.write("input", "gamecontrollerdb could not be read (${t.javaClass.simpleName})")
            emptyList()
        }
        cache = parsed
        byGuid = parsed.associateBy { it.guid }
        return parsed
    }

    fun parse(lines: Sequence<String>): List<Entry> {
        val out = ArrayList<Entry>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(',')
            if (parts.size < 3) continue
            val guid = parts[0].trim().lowercase(Locale.US)
            if (guid.length != 32) continue
            val name = parts[1].trim()
            val bindings = LinkedHashMap<String, String>()
            for (i in 2 until parts.size) {
                val p = parts[i].trim()
                if (p.isEmpty()) continue
                val colon = p.indexOf(':')
                if (colon <= 0) continue
                val key = p.substring(0, colon).trim().lowercase(Locale.US)
                if (key == "platform") continue
                bindings[key] = p.substring(colon + 1).trim()
            }
            if (bindings.isNotEmpty()) out += Entry(guid, name, bindings)
        }
        return out
    }

    /**
     * Lookup ladder, most specific first:
     *  1. an exact GUID from [SdlGuid.candidates] (capability masks included, then zeroed);
     *  2. a prefix match on bus+vendor+product, ignoring the capability masks - they are derived
     *     from `hasKeys`/motion ranges and drift between SDL releases, so an exact match is a
     *     bonus rather than something to rely on;
     *  3. the controller's reported name.
     */
    fun find(entries: List<Entry>, device: InputDevice): Pair<Entry, String>? {
        val index = if (byGuid.isNotEmpty()) byGuid else entries.associateBy { it.guid }
        for (g in SdlGuid.candidates(device)) {
            index[g]?.let { return it to "guid:$g" }
        }
        SdlGuid.vidPidPrefix(device)?.let { prefix ->
            entries.firstOrNull { it.guid.startsWith(prefix) }?.let { return it to "vid/pid:$prefix" }
        }
        val name = device.name?.trim().orEmpty()
        if (name.isNotEmpty()) {
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it to "name" }
        }
        return null
    }
}
