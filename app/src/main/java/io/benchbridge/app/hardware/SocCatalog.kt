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
        fun key(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}+]"), "")
        val profiles = entries()
        val aliases = mutableMapOf<String, MutableSet<Int>>()
        profiles.forEachIndexed { index, soc ->
            val names = soc.getJSONArray("aliases")
            for (i in 0 until names.length()) aliases.getOrPut(key(names.getString(i))) { mutableSetOf() }.add(index)
        }
        val candidates = identifiers.flatMap { raw ->
            listOf(raw) + Regex("(?i)\\b(?:SM[0-9]{4}(?:-[A-Z0-9]+)*|MT[0-9]{4}[A-Z]*|S5E[0-9]{4}|GS[0-9]{3})\\b")
                .findAll(raw).map { it.value }.toList()
        }.map(::key).filter { it.length >= 3 }.toSet()
        // 共用代号需要其他明确名称消歧；相互矛盾的字段仍不匹配。
        // A shared code needs an unambiguous name; conflicting identifiers still reject the match.
        val evidence = candidates.mapNotNull { aliases[it]?.toSet() }
        val matches = evidence.reduceOrNull { remaining, supported -> remaining.intersect(supported) }.orEmpty()
        return matches.singleOrNull()?.let { JSONObject(profiles[it].toString()).put("catalog_revision", revision) }
    }
}
