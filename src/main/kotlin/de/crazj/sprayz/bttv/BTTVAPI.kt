package de.crazj.sprayz.bttv

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.crazj.sprayz.ConfPath
import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.spray.Emote
import de.crazj.sprayz.spray.loadEmoteFromFile
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.map.MapPalette
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class BTTVEmoteDescriptor(
    val id: String,
    val code: String,
    val imageType: String,
) {
    val animated: Boolean
        get() = imageType.equals("gif", ignoreCase = true)
}

sealed class BTTVSource(val cacheKey: String) {
    abstract suspend fun fetchDescriptors(): List<BTTVEmoteDescriptor>

    data object Global : BTTVSource("global") {
        override suspend fun fetchDescriptors(): List<BTTVEmoteDescriptor> = BTTVAPI.fetchGlobalEmotes()
    }

    data class Channel(val userId: String) : BTTVSource("channel-$userId") {
        override suspend fun fetchDescriptors(): List<BTTVEmoteDescriptor> = BTTVAPI.fetchChannelEmotes(userId)
    }
}

object BTTVCache {
    private object ActiveEmoteStore : TimedYamlCacheStore<String, List<BTTVEmoteDescriptor>>() {
        override fun cacheDirectory(): File = File(cacheRoot(), "active").apply(File::mkdirs)

        override fun ttlDays(): Long = ConfPath.BTTV_ACTIVE_EMOTE_IDS_CACHE_DAYS.getLong()

        override fun fileName(key: String): String = "${sanitizeFileSegment(key)}.yml"

        override fun readValue(configuration: YamlConfiguration, key: String): List<BTTVEmoteDescriptor>? {
            if (!configuration.isList("emotes")) return null

            return configuration.getMapList("emotes").mapNotNull { map ->
                val id = map["id"]?.toString()?.trim().orEmpty()
                val code = map["code"]?.toString()?.trim().orEmpty()
                val imageType = map["image-type"]?.toString()?.trim().orEmpty()
                BTTVEmoteDescriptor(id, code, imageType).takeIf {
                    id.isNotBlank() && code.isNotBlank() && imageType.isNotBlank()
                }
            }
        }

        override fun writeValue(configuration: YamlConfiguration, key: String, value: List<BTTVEmoteDescriptor>) {
            configuration.set(
                "emotes",
                value.map { descriptor ->
                    mapOf(
                        "id" to descriptor.id,
                        "code" to descriptor.code,
                        "image-type" to descriptor.imageType,
                    )
                }
            )
        }
    }

    fun getFreshActiveEmotes(cacheKey: String): List<BTTVEmoteDescriptor>? = ActiveEmoteStore.getFresh(cacheKey)

    fun getStoredActiveEmotes(cacheKey: String): List<BTTVEmoteDescriptor>? = ActiveEmoteStore.getStored(cacheKey)

    fun storeActiveEmotes(cacheKey: String, descriptors: List<BTTVEmoteDescriptor>) {
        ActiveEmoteStore.store(cacheKey, descriptors)
    }

    fun getFreshImageFile(descriptor: BTTVEmoteDescriptor): File? {
        val cachedFile = findExactCachedImageFile(descriptor) ?: return null
        return cachedFile.takeIf { !isExpired(it.lastModified(), ConfPath.BTTV_DOWNLOADED_EMOTES_CACHE_DAYS.getLong()) }
    }

    fun getStoredImageFile(descriptor: BTTVEmoteDescriptor): File? {
        return findExactCachedImageFile(descriptor) ?: findAnyCachedImageFile(descriptor.id)
    }

    fun createImageDownloadTarget(descriptor: BTTVEmoteDescriptor): File {
        val cacheDir = emoteCacheDir()
        cacheDir.mkdirs()
        return File.createTempFile("${descriptor.id}-", ".download", cacheDir)
    }

    fun storeDownloadedImage(descriptor: BTTVEmoteDescriptor, downloadedFile: File): File {
        val targetFile = emoteCacheFile(descriptor)
        targetFile.parentFile.mkdirs()

        findAnyCachedImageFile(descriptor.id)
            ?.takeIf { it.absolutePath != targetFile.absolutePath }
            ?.let { cachedFile ->
                if (!cachedFile.delete()) {
                    throw IllegalStateException("Could not delete old cache file ${cachedFile.name}")
                }
            }

        Files.move(downloadedFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        targetFile.setLastModified(System.currentTimeMillis())
        return targetFile
    }

    fun deleteDownloadTarget(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }

    private fun emoteCacheFile(descriptor: BTTVEmoteDescriptor): File {
        return File(emoteCacheDir(), "${descriptor.id}.${descriptor.imageType.lowercase()}")
    }

    private fun findExactCachedImageFile(descriptor: BTTVEmoteDescriptor): File? {
        return emoteCacheFile(descriptor).takeIf(File::isFile)
    }

    private fun findAnyCachedImageFile(id: String): File? {
        return emoteCacheDir().listFiles().orEmpty().firstOrNull { file ->
            file.isFile && file.nameWithoutExtension == id
        }
    }

    private fun isExpired(cachedAt: Long, cacheDays: Long): Boolean {
        if (cacheDays <= 0 || cachedAt <= 0) return true
        val maxAgeMillis = cacheDays * 24L * 60L * 60L * 1000L
        return System.currentTimeMillis() - cachedAt >= maxAgeMillis
    }

    private fun emoteCacheDir(): File = File(cacheRoot(), "emotes").apply(File::mkdirs)

    private fun cacheRoot(): File = File(File(SprayZ.instance.dataFolder, "cache"), "bttv").apply(File::mkdirs)
}

object BTTVAPI : ManagedHttpApiClient() {
    private const val GLOBAL = "https://api.betterttv.net/3/cached/emotes/global"
    private const val CHANNEL_EMOTES = "https://api.betterttv.net/3/cached/users/twitch/<TWITCH_USER_ID>"
    private const val EMOTE_3X = "https://cdn.betterttv.net/emote/<EMOTE_ID>/3x"

    suspend fun fetchChannelEmotes(userId: String): List<BTTVEmoteDescriptor> {
        val url = URI(CHANNEL_EMOTES.replace("<TWITCH_USER_ID>", userId)).toURL()
        val jsonObject = getRequest(url).asJsonObject

        return buildList {
            addAll(parseEmoteArray(jsonObject.getAsJsonArray("channelEmotes")))
            addAll(parseEmoteArray(jsonObject.getAsJsonArray("sharedEmotes")))
        }
    }

    suspend fun fetchGlobalEmotes(): List<BTTVEmoteDescriptor> {
        return parseEmoteArray(getRequest(URI(GLOBAL).toURL()).asJsonArray)
    }

    fun downloadEmote(descriptor: BTTVEmoteDescriptor, targetFile: File) {
        targetFile.parentFile.mkdirs()
        URI(EMOTE_3X.replace("<EMOTE_ID>", descriptor.id)).toURL().openStream().use { input ->
            targetFile.outputStream().use(input::copyTo)
        }
    }

    private suspend fun getRequest(url: URL): JsonElement {
        val response = httpClient.get(url)
        if (response.status.value != 200) {
            throw IllegalStateException("BTTV API responded with ${response.status.value} for $url")
        }
        return JsonParser.parseString(response.bodyAsText())
    }

    private fun parseEmoteArray(array: JsonArray?): List<BTTVEmoteDescriptor> {
        return array.orEmpty()
            .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            .mapNotNull(::descriptorOf)
    }

    private fun descriptorOf(jsonObject: JsonObject): BTTVEmoteDescriptor? {
        val id = jsonObject.readString("id")
        val code = jsonObject.readString("code")
        val imageType = jsonObject.readString("imageType")
        return BTTVEmoteDescriptor(id.orEmpty(), code.orEmpty(), imageType.orEmpty()).takeIf {
            !id.isNullOrBlank() && !code.isNullOrBlank() && !imageType.isNullOrBlank()
        }
    }

    private fun JsonObject.readString(key: String): String? {
        val value = get(key) ?: return null
        return value.takeIf(JsonElement::isJsonPrimitive)?.let { runCatching { it.asString.trim() }.getOrNull() }
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList().orEmpty()
}

object BTTVService {
    suspend fun getGlobalEmotes(forceRefresh: Boolean = false): Set<Emote> = getEmotes(BTTVSource.Global, forceRefresh)

    suspend fun getChannelEmotes(userId: String, forceRefresh: Boolean = false): Set<Emote> =
        getEmotes(BTTVSource.Channel(userId), forceRefresh)

    fun shutdown() = BTTVAPI.shutdown()

    private suspend fun getEmotes(source: BTTVSource, forceRefresh: Boolean): Set<Emote> {
        return getEmoteDescriptors(source, forceRefresh).mapNotNullTo(linkedSetOf()) { loadEmote(it, forceRefresh) }
    }

    private suspend fun getEmoteDescriptors(source: BTTVSource, forceRefresh: Boolean): List<BTTVEmoteDescriptor> {
        return fetchWithSuspendCache(
            forceRefresh = forceRefresh,
            fresh = { BTTVCache.getFreshActiveEmotes(source.cacheKey) },
            stored = { BTTVCache.getStoredActiveEmotes(source.cacheKey) },
            fetch = { source.fetchDescriptors() },
            store = { BTTVCache.storeActiveEmotes(source.cacheKey, it) },
        ).valueOrElse(
            onStoredFallback = { _, cause ->
                SprayZ.instance.log(
                    "BTTV emote list for ${source.cacheKey} could not be refreshed, continuing with cache: ${cause.reason()}"
                )
            },
            onFailure = { cause ->
                SprayZ.instance.log(
                    "BTTV emote list for ${source.cacheKey} could not be loaded: ${cause.reason()}"
                )
                emptyList()
            }
        )
    }

    private fun loadEmote(descriptor: BTTVEmoteDescriptor, forceRefresh: Boolean): Emote? {
        val fileToLoad = fetchWithCache(
            forceRefresh = forceRefresh,
            fresh = { BTTVCache.getFreshImageFile(descriptor) },
            stored = { BTTVCache.getStoredImageFile(descriptor) },
            fetch = { downloadToCache(descriptor) },
        ).valueOrNull(
            onStoredFallback = { _, cause ->
                SprayZ.instance.log(
                    "BTTV emote ${descriptor.code} could not be refreshed, continuing with cache: ${cause.reason()}"
                )
            },
            onFailure = { cause ->
                SprayZ.instance.log(
                    "BTTV emote ${descriptor.code} could not be loaded: ${cause.reason()}"
                )
            },
        ) ?: return null

        return runCatching {
            emoteFromFile(descriptor, fileToLoad)
        }.getOrElse { firstLoadError ->
            runCatching {
                emoteFromFile(descriptor, downloadToCache(descriptor))
            }.getOrElse { retryError ->
                SprayZ.instance.log(
                    "BTTV emote ${descriptor.code} could not be processed: ${retryError.message ?: firstLoadError.reason()}"
                )
                null
            }
        }
    }

    private fun downloadToCache(descriptor: BTTVEmoteDescriptor): File {
        val downloadTarget = BTTVCache.createImageDownloadTarget(descriptor)
        return try {
            BTTVAPI.downloadEmote(descriptor, downloadTarget)
            BTTVCache.storeDownloadedImage(descriptor, downloadTarget)
        } catch (ex: Exception) {
            BTTVCache.deleteDownloadTarget(downloadTarget)
            throw ex
        }
    }

    private fun emoteFromFile(descriptor: BTTVEmoteDescriptor, file: File): Emote {
        return loadEmoteFromFile(descriptor.code, file, descriptor.animated)
    }
}
