package de.crazj.sprayz.bttv

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

abstract class ManagedHttpApiClient {
    private var client: HttpClient? = null

    protected open fun HttpClientConfig<*>.configureClient() = Unit

    protected val httpClient: HttpClient
        get() = client ?: HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
            configureClient()
        }.also { client = it }

    fun shutdown() {
        client?.close()
        client = null
    }
}

abstract class TimedYamlCacheStore<K, V> {
    private data class CachedValue<T>(
        val fetchedAt: Long,
        val value: T,
    )

    fun getFresh(key: K): V? {
        val cachedValue = getStoredValue(key) ?: return null
        return cachedValue.takeIf { !isExpired(it.fetchedAt) }?.value
    }

    fun getStored(key: K): V? = getStoredValue(key)?.value

    fun store(key: K, value: V) {
        val file = cacheFile(key)
        file.parentFile.mkdirs()

        val configuration = YamlConfiguration()
        configuration.set("fetched-at", System.currentTimeMillis())
        writeValue(configuration, key, value)
        configuration.save(file)
    }

    protected abstract fun cacheDirectory(): File

    protected abstract fun ttlDays(): Long

    protected abstract fun fileName(key: K): String

    protected abstract fun readValue(configuration: YamlConfiguration, key: K): V?

    protected abstract fun writeValue(configuration: YamlConfiguration, key: K, value: V)

    protected fun sanitizeFileSegment(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun getStoredValue(key: K): CachedValue<V>? {
        val file = cacheFile(key)
        if (!file.isFile) return null

        return runCatching { readStoredValue(file, key) }.getOrNull()
    }

    private fun readStoredValue(file: File, key: K): CachedValue<V>? {
        val configuration = YamlConfiguration.loadConfiguration(file)
        val fetchedAt = configuration.getLong("fetched-at")
        if (fetchedAt <= 0L) {
            return null
        }

        val value = readValue(configuration, key) ?: return null
        return CachedValue(fetchedAt, value)
    }

    private fun cacheFile(key: K): File {
        return File(cacheDirectory().apply { mkdirs() }, fileName(key))
    }

    private fun isExpired(fetchedAt: Long): Boolean {
        if (fetchedAt <= 0L) return true

        val cacheDays = ttlDays()
        if (cacheDays <= 0) return true

        val maxAgeMillis = cacheDays * 24L * 60L * 60L * 1000L
        return System.currentTimeMillis() - fetchedAt >= maxAgeMillis
    }
}

sealed interface CachedFetchResult<out T> {
    data class Fresh<T>(val value: T) : CachedFetchResult<T>
    data class Remote<T>(val value: T) : CachedFetchResult<T>
    data class StoredFallback<T>(val value: T, val cause: Throwable) : CachedFetchResult<T>
    data class Failure(val cause: Throwable) : CachedFetchResult<Nothing>
}

inline fun <T, R> CachedFetchResult<T>.fold(
    onFresh: (T) -> R,
    onRemote: (T) -> R,
    onStoredFallback: (T, Throwable) -> R,
    onFailure: (Throwable) -> R,
): R = when (this) {
    is CachedFetchResult.Fresh -> onFresh(value)
    is CachedFetchResult.Remote -> onRemote(value)
    is CachedFetchResult.StoredFallback -> onStoredFallback(value, cause)
    is CachedFetchResult.Failure -> onFailure(cause)
}

inline fun <T> CachedFetchResult<T>.valueOrElse(
    onStoredFallback: (T, Throwable) -> Unit = { _, _ -> },
    onFailure: (Throwable) -> T,
): T = fold(
    onFresh = { it },
    onRemote = { it },
    onStoredFallback = { value, cause ->
        onStoredFallback(value, cause)
        value
    },
    onFailure = onFailure,
)

inline fun <T> CachedFetchResult<T>.valueOrNull(
    onStoredFallback: (T, Throwable) -> Unit = { _, _ -> },
    onFailure: (Throwable) -> Unit = {},
): T? = fold(
    onFresh = { it },
    onRemote = { it },
    onStoredFallback = { value, cause ->
        onStoredFallback(value, cause)
        value
    },
    onFailure = {
        onFailure(it)
        null
    },
)

suspend inline fun <T> fetchWithSuspendCache(
    forceRefresh: Boolean,
    crossinline fresh: () -> T?,
    crossinline stored: () -> T?,
    crossinline fetch: suspend () -> T,
    crossinline store: (T) -> Unit = {},
): CachedFetchResult<T> {
    return fetchWithCacheResult(forceRefresh, fresh, stored, { runCatching { fetch() } }, store)
}

inline fun <T> fetchWithCache(
    forceRefresh: Boolean,
    crossinline fresh: () -> T?,
    crossinline stored: () -> T?,
    crossinline fetch: () -> T,
    crossinline store: (T) -> Unit = {},
): CachedFetchResult<T> {
    return fetchWithCacheResult(forceRefresh, fresh, stored, { runCatching(fetch) }, store)
}

inline fun <T> fetchWithCacheResult(
    forceRefresh: Boolean,
    crossinline fresh: () -> T?,
    crossinline stored: () -> T?,
    fetchResult: () -> Result<T>,
    crossinline store: (T) -> Unit = {},
): CachedFetchResult<T> {
    if (!forceRefresh) {
        fresh()?.let { return CachedFetchResult.Fresh(it) }
    }

    val fallback = if (forceRefresh) null else stored()
    return fetchResult().fold(
        onSuccess = { value ->
            store(value)
            CachedFetchResult.Remote(value)
        },
        onFailure = { ex ->
            fallback?.let { CachedFetchResult.StoredFallback(it, ex) }
                ?: CachedFetchResult.Failure(ex)
        },
    )
}

fun Throwable.reason(): String = message ?: "unknown error"
