package io.benchbridge.app.hardware

import android.content.Context
import java.util.Locale
import org.json.JSONObject

/** 内部规格仅补充缺失的设备信息，不生成跑分。 / Internal specifications fill metadata gaps, never benchmark scores. */
class SocCatalog(context: Context) {
    private val database = context.assets.open("soc_catalog.json").bufferedReader().use { JSONObject(it.readText()) }
    val revision: String = database.getString("revision")
    fun entries(): List<JSONObject> = database.getJSONArray("socs").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    fun match(identifiers: List<String>): JSONObject? {
        fun key(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9+]"), "")
        val candidates = identifiers.flatMap { raw ->
            listOf(raw) + Regex("(?i)\\b(?:SM[0-9]{4}(?:-[A-Z0-9]+)*|MT[0-9]{4}[A-Z]*)\\b").findAll(raw).map { it.value }.toList()
        }.map(::key).filter { it.length >= 4 }.toSet()
        val matches = entries().filter { soc ->
            val aliases = soc.getJSONArray("aliases")
            (0 until aliases.length()).any { key(aliases.getString(it)) in candidates }
        }
        return matches.singleOrNull()?.let { JSONObject(it.toString()).put("catalog_revision", revision) }
    }
}
