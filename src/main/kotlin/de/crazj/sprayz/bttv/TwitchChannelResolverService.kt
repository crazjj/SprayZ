package de.crazj.sprayz.bttv

import de.crazj.sprayz.ConfPath
import de.crazj.sprayz.SprayZ
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.net.URI
import java.util.Locale

data class CachedTwitchChannelResolution(
    val login: String,
    val userId: String,
)

sealed interface TwitchChannelResolutionResult {
    val input: String

    data class Resolved(
        override val input: String,
        val userId: String,
        val source: TwitchChannelResolutionSource,
        val normalizedLogin: String? = null,
    ) : TwitchChannelResolutionResult

    data class CachedFallback(
        override val input: String,
        val userId: String,
        val normalizedLogin: String,
        val reason: String,
    ) : TwitchChannelResolutionResult

    data class Failed(
        override val input: String,
        val normalizedLogin: String? = null,
        val reason: String,
    ) : TwitchChannelResolutionResult
}

enum class TwitchChannelResolutionSource {
    DIRECT_ID,
    FRESH_CACHE,
    DECAPI,
}

object DecAPI : ManagedHttpApiClient() {
    private const val TWITCH_ID = "https://decapi.me/twitch/id/<CHANNEL_LOGIN>"
    private val numericResponse = Regex("^\\d+$")

    suspend fun fetchTwitchUserId(login: String): String {
        val response = httpClient.get(URI(TWITCH_ID.replace("<CHANNEL_LOGIN>", login)).toURL())
        if (response.status.value != 200) {
            throw IllegalStateException("DecAPI responded with ${response.status.value} for login '$login'")
        }

        val body = response.bodyAsText().trim()
        return body.takeIf(numericResponse::matches)
            ?: throw IllegalStateException("Unexpected DecAPI response for login '$login': $body")
    }
}

object TwitchChannelResolverCache : TimedYamlCacheStore<String, CachedTwitchChannelResolution>() {
    private val numericUserIdPattern = Regex("^\\d+$")

    fun getFreshResolution(login: String): CachedTwitchChannelResolution? = getFresh(login)

    fun getStoredResolution(login: String): CachedTwitchChannelResolution? = getStored(login)

    fun storeResolution(login: String, userId: String) {
        store(login, CachedTwitchChannelResolution(login, userId))
    }

    override fun cacheDirectory(): File = File(File(SprayZ.instance.dataFolder, "cache"), "twitch").apply(File::mkdirs)

    override fun ttlDays(): Long = ConfPath.BTTV_CHANNEL_ID_CACHE_DAYS.getLong()

    override fun fileName(key: String): String = "${sanitizeFileSegment(key)}.yml"

    override fun readValue(configuration: YamlConfiguration, key: String): CachedTwitchChannelResolution? {
        val cachedLogin = configuration.getString("login")?.trim().orEmpty()
        val userId = configuration.getString("user-id")?.trim().orEmpty()
        return CachedTwitchChannelResolution(cachedLogin, userId).takeIf {
            cachedLogin == key && numericUserIdPattern.matches(userId)
        }
    }

    override fun writeValue(configuration: YamlConfiguration, key: String, value: CachedTwitchChannelResolution) {
        configuration.set("login", value.login)
        configuration.set("user-id", value.userId)
    }
}

object TwitchChannelResolverService {
    private val numericIdPattern = Regex("^\\d+$")
    private val twitchLoginPattern = Regex("^[a-z0-9_]{1,25}$")

    fun shutdown() {
        DecAPI.shutdown()
    }

    fun normalizeConfigChannelEntry(input: String): String? = normalizeChannelInput(input)?.value

    suspend fun resolveChannel(input: String, forceRefresh: Boolean = false): TwitchChannelResolutionResult {
        return when (val normalized = normalizeChannelInput(input)) {
            is NormalizedChannelInput.UserId -> TwitchChannelResolutionResult.Resolved(
                input = input,
                userId = normalized.userId,
                source = TwitchChannelResolutionSource.DIRECT_ID,
            )

            is NormalizedChannelInput.Login -> resolveLogin(input, normalized.login, forceRefresh)
            null -> invalidEntryFailure(input)
        }
    }

    private suspend fun resolveLogin(
        input: String,
        login: String,
        forceRefresh: Boolean,
    ): TwitchChannelResolutionResult {
        return fetchWithSuspendCache(
            forceRefresh = forceRefresh,
            fresh = { TwitchChannelResolverCache.getFreshResolution(login) },
            stored = { TwitchChannelResolverCache.getStoredResolution(login) },
            fetch = { CachedTwitchChannelResolution(login, DecAPI.fetchTwitchUserId(login)) },
            store = { TwitchChannelResolverCache.storeResolution(it.login, it.userId) },
        ).fold(
            onFresh = { resolved(input, it, TwitchChannelResolutionSource.FRESH_CACHE) },
            onRemote = { resolved(input, it, TwitchChannelResolutionSource.DECAPI) },
            onStoredFallback = { value, cause ->
                SprayZ.instance.log(
                    "Twitch channel '$login' could not be refreshed via DecAPI, using cached user ID ${value.userId} instead: ${cause.reason()}"
                )
                TwitchChannelResolutionResult.CachedFallback(
                    input = input,
                    userId = value.userId,
                    normalizedLogin = login,
                    reason = cause.reason(),
                )
            },
            onFailure = { cause ->
                SprayZ.instance.log(
                    "Twitch channel '$login' could not be resolved via DecAPI and will be skipped: ${cause.reason()}"
                )
                TwitchChannelResolutionResult.Failed(
                    input = input,
                    normalizedLogin = login,
                    reason = cause.reason(),
                )
            },
        )
    }

    private fun invalidEntryFailure(input: String): TwitchChannelResolutionResult.Failed {
        return TwitchChannelResolutionResult.Failed(
            input = input,
            reason = "Invalid Twitch channel entry",
        ).also {
            SprayZ.instance.log(
                "BTTV channel entry '$input' is neither a valid Twitch login nor a numeric user ID and will be skipped"
            )
        }
    }

    private fun resolved(
        input: String,
        resolution: CachedTwitchChannelResolution,
        source: TwitchChannelResolutionSource,
    ): TwitchChannelResolutionResult.Resolved {
        return TwitchChannelResolutionResult.Resolved(
            input = input,
            userId = resolution.userId,
            source = source,
            normalizedLogin = resolution.login,
        )
    }

    private fun normalizeChannelInput(input: String): NormalizedChannelInput? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        if (numericIdPattern.matches(trimmed)) return NormalizedChannelInput.UserId(trimmed)

        val candidate = (extractLoginFromTwitchUrl(trimmed.removePrefix("#").removePrefix("@"))
            ?: trimmed.removePrefix("#").removePrefix("@"))
            .trim()
            .trim('/')
            .lowercase(Locale.ROOT)

        return candidate.takeIf(twitchLoginPattern::matches)?.let(NormalizedChannelInput::Login)
    }

    private fun extractLoginFromTwitchUrl(value: String): String? {
        val rawUrl = when {
            value.startsWith("http://", ignoreCase = true) -> value
            value.startsWith("https://", ignoreCase = true) -> value
            value.contains("twitch.tv", ignoreCase = true) -> "https://$value"
            else -> return null
        }

        return runCatching {
            val uri = URI(rawUrl)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (host !in setOf("twitch.tv", "www.twitch.tv", "m.twitch.tv")) {
                return null
            }

            uri.path
                ?.trim('/')
                ?.split('/')
                ?.singleOrNull()
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private sealed interface NormalizedChannelInput {
        val value: String

        data class UserId(val userId: String) : NormalizedChannelInput {
            override val value: String = userId
        }

        data class Login(val login: String) : NormalizedChannelInput {
            override val value: String = login
        }
    }
}
