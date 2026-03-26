package de.crazj.sprayz

import java.util.Locale

private data class EditableSetting(
    val suggestions: List<String> = emptyList(),
    val requiresBTTVRefresh: Boolean = false,
    val parse: (String) -> Any,
)

enum class ConfPath(
    sub: String,
    val defaultValue: Any?,
    parent: ConfPath?,
    val description: String,
    private val editor: EditableSetting? = null,
) {
    SPRAY(
        "spray",
        null,
        null,
        "Top-level settings for spray placement, range, and lifetime.",
    ),
    DISAPPEAR_AFTER(
        "disappear-after",
        10,
        SPRAY,
        "Time in seconds before a spray disappears. Use -1 to keep sprays forever.",
        longSetting(
            suggestions = listOf("-1", "30", "60", "300"),
            key = "disappear-after",
            minValue = -1,
        ),
    ),
    SPRAY_COOLDOWN(
        "cooldown",
        2,
        SPRAY,
        "Time in seconds before a player can place the next spray. Use -1 to disable the cooldown.",
        longSetting(
            suggestions = listOf("-1", "5", "10", "30"),
            key = "cooldown",
            minValue = -1,
        ),
    ),
    SPRAY_RANGE(
        "range",
        8.0,
        SPRAY,
        "Maximum spray placement range in blocks for ray tracing.",
        positiveDoubleSetting(
            suggestions = listOf("8", "16", "32", "64"),
            key = "range",
        ),
    ),
    BTTV(
        "bttv",
        null,
        null,
        "Top-level settings for BTTV channel loading, refresh behavior, and caches.",
    ),
    BTTV_EMOTE_CACHE_DAYS(
        "emote-cache-days",
        null,
        BTTV,
        "Cache durations in days for BTTV metadata and downloaded image files.",
    ),
    BTTV_GLOBAL(
        "global-emotes-enabled",
        true,
        BTTV,
        "Whether BTTV global emotes should be loaded.",
        booleanSetting(
            suggestions = listOf("true", "false"),
            key = "global-emotes-enabled",
            requiresBTTVRefresh = true,
        ),
    ),
    BTTV_CHANNELS(
        "emote-channels",
        listOf("bastighg"),
        BTTV,
        "Configured BTTV channels. Twitch login names are preferred, but numeric Twitch user IDs still work.",
    ),
    BTTV_CHANNEL_ID_CACHE_DAYS(
        "channel-id-cache-days",
        30,
        BTTV,
        "How many days resolved Twitch channel IDs from DecAPI should stay cached locally.",
        longSetting(
            suggestions = listOf("0", "1", "7", "30"),
            key = "channel-id-cache-days",
            minValue = 0,
            requiresBTTVRefresh = true,
        ),
    ),
    BTTV_ACTIVE_EMOTE_IDS_CACHE_DAYS(
        "ids",
        1,
        BTTV_EMOTE_CACHE_DAYS,
        "How many days active BTTV emote ID lists should stay cached before refreshing.",
        longSetting(
            suggestions = listOf("0", "1", "3", "7"),
            key = "bttv.emote-cache-days.ids",
            minValue = 0,
            requiresBTTVRefresh = true,
        ),
    ),
    BTTV_DOWNLOADED_EMOTES_CACHE_DAYS(
        "images",
        7,
        BTTV_EMOTE_CACHE_DAYS,
        "How many days downloaded BTTV emote image files should stay fresh before re-downloading.",
        longSetting(
            suggestions = listOf("0", "1", "7", "30"),
            key = "bttv.emote-cache-days.images",
            minValue = 0,
            requiresBTTVRefresh = true,
        ),
    ),
    ;

    val path: String = if (parent != null) "${parent.path}.$sub" else sub
    val suggestions: List<String>
        get() = editor?.suggestions.orEmpty()
    val requiresBTTVRefresh: Boolean
        get() = editor?.requiresBTTVRefresh == true
    val isEditable: Boolean
        get() = editor != null

    companion object {
        val editableEntries: List<ConfPath> = entries.filter { it.isEditable }
        val editableByPath: Map<String, ConfPath> =
            editableEntries.associateByTo(linkedMapOf(), ConfPath::path)
    }

    override fun toString(): String = path

    fun get(): Any? = SprayConfig.get(this)

    fun getBoolean(): Boolean = SprayConfig.getBoolean(this)

    fun getLong(): Long = SprayConfig.getLong(this)

    fun getDouble(): Double = SprayConfig.getDouble(this)

    fun getStringList(): List<String> = SprayConfig.getStringList(this)

    fun parse(raw: String): Any = editor?.parse?.invoke(raw) ?: throw IllegalStateException("$path is not editable")

    fun set(value: Any?) = SprayConfig.set(this, value)
}

private fun parseLong(raw: String, key: String): Long {
    return raw.toLongOrNull()
        ?: throw IllegalArgumentException("'$raw' is not a valid whole number for '$key'.")
}

private fun parseDouble(raw: String, key: String): Double {
    return raw.toDoubleOrNull()
        ?: throw IllegalArgumentException("'$raw' is not a valid number for '$key'.")
}

private fun parseBoolean(raw: String, key: String): Boolean {
    return when (raw.lowercase(Locale.ROOT)) {
        "true", "on", "yes", "1", "enabled" -> true
        "false", "off", "no", "0", "disabled" -> false
        else -> throw IllegalArgumentException("'$raw' is not a valid boolean for '$key'. Use true or false.")
    }
}

private fun longSetting(
    suggestions: List<String>,
    key: String,
    minValue: Long,
    requiresBTTVRefresh: Boolean = false,
): EditableSetting = EditableSetting(suggestions, requiresBTTVRefresh) { raw ->
    parseLong(raw, key).also {
        require(it >= minValue) { "$key must be $minValue or greater" }
    }
}

private fun positiveDoubleSetting(
    suggestions: List<String>,
    key: String,
): EditableSetting = EditableSetting(suggestions = suggestions) { raw ->
    parseDouble(raw, key).also {
        require(it > 0.0) { "$key must be greater than 0" }
    }
}

private fun booleanSetting(
    suggestions: List<String>,
    key: String,
    requiresBTTVRefresh: Boolean = false,
): EditableSetting = EditableSetting(suggestions, requiresBTTVRefresh) { raw ->
    parseBoolean(raw, key)
}
