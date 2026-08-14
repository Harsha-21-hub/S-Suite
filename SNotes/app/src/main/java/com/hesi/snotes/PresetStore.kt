package com.hesi.snotes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named tool presets (pen color/size, eraser size, shape detection,
 * pressure, beautify). Stored as one JSON array in SharedPreferences.
 */
object PresetStore {

    data class Preset(
        val name: String,
        val penColor: Int,
        val penWidth: Float,
        val eraserWidth: Float,
        val shapeDetect: Boolean,
        val pressure: Boolean,
        val refine: Boolean
    )

    private const val PREFS = "snotes_presets"
    private const val KEY = "presets"

    fun list(ctx: Context): ArrayList<Preset> {
        val out = ArrayList<Preset>()
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Preset(
                        o.getString("name"),
                        o.getInt("color"),
                        o.getDouble("penW").toFloat(),
                        o.getDouble("eraserW").toFloat(),
                        o.optBoolean("shapes", false),
                        o.optBoolean("pressure", true),
                        o.optBoolean("refine", true)
                    )
                )
            }
        }
        return out
    }

    fun save(ctx: Context, preset: Preset) {
        val all = list(ctx)
        all.removeAll { it.name.equals(preset.name, ignoreCase = true) }
        all.add(0, preset)
        while (all.size > 12) all.removeAt(all.size - 1)
        persist(ctx, all)
    }

    fun delete(ctx: Context, name: String) {
        val all = list(ctx)
        all.removeAll { it.name == name }
        persist(ctx, all)
    }

    private fun persist(ctx: Context, all: List<Preset>) {
        val arr = JSONArray()
        for (p in all) {
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("color", p.penColor)
                put("penW", p.penWidth.toDouble())
                put("eraserW", p.eraserWidth.toDouble())
                put("shapes", p.shapeDetect)
                put("pressure", p.pressure)
                put("refine", p.refine)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
