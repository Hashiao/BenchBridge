package io.benchbridge.app.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import org.json.JSONObject
import java.util.Locale

/** 首选语言严格分流；不让第二首选中文覆盖英文回退。
 * Resolve the primary language only; secondary Chinese must not override English fallback. */
object L10n {
    enum class Language(val tag: String) { EN("en"), HANS("zh-Hans"), HANT("zh-Hant") }
    fun languageFor(tag: String): Language {
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        if (locale.language != "zh") return Language.EN
        return when (locale.script.lowercase(Locale.ROOT)) {
            "hans" -> Language.HANS
            "hant" -> Language.HANT
            "" -> if (locale.country in setOf("TW", "HK", "MO")) Language.HANT else Language.HANS
            else -> Language.EN
        }
    }
    val language: Language get() = languageFor(Resources.getSystem().configuration.locales[0]?.toLanguageTag().orEmpty())
    val tag: String get() = language.tag
    @Volatile private var application: Context? = null
    private val localized = mutableMapOf<Language, Resources>()
    private val argument = Regex("\\{(\\d+)\\}")
    private val han = Regex("[\\u3400-\\u9fff]")
    private val displayCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 512
    }
    @Synchronized fun initialize(context: Context) {
        application = context.applicationContext ?: context
        localized.clear()
        synchronized(displayCache) { displayCache.clear() }
    }
    fun wrap(context: Context): Context = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(tag))
        setLayoutDirection(Locale.forLanguageTag(tag))
    })
    @Synchronized private fun resources(language: Language): Resources = localized.getOrPut(language) {
        val context = checkNotNull(application) { "Localization context is not initialized" }
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(language.tag))
        }).resources
    }
    fun t(key: String, vararg values: Any?): String = text(key, language, *values)
    fun text(key: String, language: Language, vararg values: Any?): String {
        val id = checkNotNull(StringResources.ids[key]) { "Unknown localization key: $key" }
        return argument.replace(resources(language).getString(id)) { match -> values.getOrNull(match.groupValues[1].toInt()).toString() }
    }

    private data class Entry(val values: Map<Language, String>)
    private data class Matcher(val regex: Regex, val hint: String, val order: List<Int>, val entry: Entry)
    private val entries: List<Entry> by lazy {
        val json = checkNotNull(application).assets.open("localization.json").bufferedReader().use { JSONObject(it.readText()) }
        json.keys().asSequence().map { key -> Entry(Language.entries.associateWith { json.getJSONObject(key).getString(it.tag) }) }.toList()
    }
    private val exact by lazy {
        buildMap<String, Entry> { for (entry in L10n.entries) for (value in entry.values.values) if (!argument.containsMatchIn(value)) putIfAbsent(value, entry) }
    }
    private val matchers: List<Matcher> by lazy {
        entries.flatMap { entry -> entry.values.values.distinct().filter(argument::containsMatchIn).map { template ->
            val matches = argument.findAll(template).toList()
            val pieces = mutableListOf<String>(); val regex = StringBuilder("^"); var end = 0
            for (match in matches) {
                val fixed = template.substring(end, match.range.first); pieces += fixed
                regex.append(Regex.escape(fixed)).append("(.*?)"); end = match.range.last + 1
            }
            val fixed = template.substring(end); pieces += fixed; regex.append(Regex.escape(fixed)).append('$')
            Matcher(Regex(regex.toString(), RegexOption.DOT_MATCHES_ALL), pieces.maxBy { it.length }, matches.map { it.groupValues[1].toInt() }, entry)
        } }.sortedByDescending { it.hint.length }
    }
    /** 仅翻译已知历史说明；不更改原始 JSON、错误码或测量数字。
     * Translate known historical descriptions at display time without modifying raw JSON, codes or measurements. */
    fun display(value: String): String = display(value, language, 0)
    internal fun display(value: String, language: Language, depth: Int = 0): String {
        if (value.isEmpty() || depth > 3 || language == Language.EN && !han.containsMatchIn(value)) return value
        val cacheKey = language.tag + '\u0000' + value
        synchronized(displayCache) { displayCache[cacheKey]?.let { return it } }
        val direct = exact[value]?.values?.get(language)
        var result = direct
        if (result == null) {
            for (matcher in matchers) {
                if (matcher.hint.length < 2 || !value.contains(matcher.hint)) continue
                val match = matcher.regex.matchEntire(value) ?: continue
                val captures = matcher.order.mapIndexed { index, position -> position to match.groupValues[index + 1] }.toMap()
                result = argument.replace(matcher.entry.values.getValue(language)) {
                    display(captures[it.groupValues[1].toInt()].orEmpty(), language, depth + 1)
                }
                break
            }
        }
        if (result == null && '\n' in value) result = value.split('\n').joinToString("\n") { display(it, language, depth + 1) }
        val resolved = result ?: value
        synchronized(displayCache) { displayCache[cacheKey] = resolved }
        return resolved
    }
}
