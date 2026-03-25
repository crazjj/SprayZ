package de.crazj.sprayz.spray

import de.crazj.sprayz.ConfPath
import de.crazj.sprayz.SprayConfig
import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.Util
import de.crazj.sprayz.Util.SearchUtil.resolveFuzzyCandidate
import de.crazj.sprayz.bttv.BTTVService
import de.crazj.sprayz.bttv.TwitchChannelResolutionResult
import de.crazj.sprayz.bttv.TwitchChannelResolverService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

class EmoteManager {
    private data class ChannelLoadSummary(
        val resolvedChannelIds: Set<String>,
        val cachedFallbacks: Int,
        val skippedChannels: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadGeneration = AtomicLong(0)

    @Volatile
    private var emotes: Map<String, Emote> = emptyMap()

    private var loadJob: Job? = null

    fun getAllEmotes(): Map<String, Emote> = LinkedHashMap(emotes)

    fun findEmoteEntry(query: String): Pair<String, Emote>? {
        val snapshot = emotes
        snapshot[query]?.let { return query to it }

        val resolvedName = resolveFuzzyCandidate(snapshot.keys, query) ?: return null
        return resolvedName to snapshot.getValue(resolvedName)
    }

    init {
        loadAll()
    }

    fun refresh(forceBTTVRefresh: Boolean = false) {
        SprayConfig.reload()
        loadAll(forceBTTVRefresh)
    }

    fun shutdown() {
        loadJob?.cancel()
        scope.cancel()
        BTTVService.shutdown()
        TwitchChannelResolverService.shutdown()
    }

    internal fun loadAll(forceBTTVRefresh: Boolean = false) {
        val generation = loadGeneration.incrementAndGet()
        loadJob?.cancel()
        loadJob = scope.launch {
            val loadedEmotes = linkedMapOf<String, Emote>()
            var bttvEmoteCount = 0
            var localEmoteCount = 0

            if (ConfPath.BTTV_GLOBAL.getBoolean()) {
                val globalEmotes = BTTVService.getGlobalEmotes(forceBTTVRefresh)
                bttvEmoteCount += globalEmotes.size
                globalEmotes.forEach { emote ->
                    loadedEmotes[emote.name] = emote
                }
            }

            val configuredChannels = ConfPath.BTTV_CHANNELS.getStringList()
                .map(String::trim)
                .filter(String::isNotBlank)

            if (configuredChannels.isNotEmpty()) {
                SprayZ.instance.log("Loading BTTV emotes from channels: ${configuredChannels.joinToString(", ")}")
                val channelSummary = resolveChannels(configuredChannels, forceBTTVRefresh)

                for (channelID in channelSummary.resolvedChannelIds) {
                    val channelEmotes = BTTVService.getChannelEmotes(channelID, forceBTTVRefresh)
                    bttvEmoteCount += channelEmotes.size
                    channelEmotes.forEach { emote ->
                        loadedEmotes[emote.name] = emote
                    }
                }

                SprayZ.instance.log(
                    "Resolved ${channelSummary.resolvedChannelIds.size} BTTV channels, " +
                        "used ${channelSummary.cachedFallbacks} cached fallback(s), skipped ${channelSummary.skippedChannels}"
                )
            }

            val sprayFolder = File(SprayZ.instance.dataFolder, "sprays").apply { mkdirs() }
            sprayFolder.listFiles().orEmpty()
                .asSequence()
                .filter(File::isFile)
                .forEach { file ->
                    runCatching {
                        loadEmoteFromFile(file.nameWithoutExtension, file)
                    }.onSuccess { emote ->
                        loadedEmotes[file.nameWithoutExtension] = emote
                        localEmoteCount++
                    }.onFailure { ex ->
                        SprayZ.instance.log("Spray file ${file.name} could not be loaded: ${ex.message}")
                    }
                }

            if (generation == loadGeneration.get()) {
                emotes = loadedEmotes
                SprayZ.instance.log(
                    "Loaded ${loadedEmotes.size} emotes total ($bttvEmoteCount from BTTV, $localEmoteCount local file emotes)"
                )
            }
        }
    }

    private suspend fun resolveChannels(
        configuredChannels: List<String>,
        forceBTTVRefresh: Boolean,
    ): ChannelLoadSummary {
        val resolvedChannelIds = LinkedHashSet<String>()
        var cachedFallbacks = 0
        var skippedChannels = 0

        configuredChannels.forEach { channelEntry ->
            when (val resolution = TwitchChannelResolverService.resolveChannel(channelEntry, forceBTTVRefresh)) {
                is TwitchChannelResolutionResult.Resolved -> {
                    resolvedChannelIds.add(resolution.userId)
                }

                is TwitchChannelResolutionResult.CachedFallback -> {
                    resolvedChannelIds.add(resolution.userId)
                    cachedFallbacks++
                }

                is TwitchChannelResolutionResult.Failed -> {
                    skippedChannels++
                }
            }
        }

        return ChannelLoadSummary(resolvedChannelIds, cachedFallbacks, skippedChannels)
    }


}

abstract class Emote(open val name: String) {
    abstract fun anyFrame(): Image
    fun toMapBytes(img: BufferedImage) = Util.ImageUtil.toMapBytes(img)
}

data class StaticEmote(override val name: String, val image: BufferedImage) : Emote(name) {
    override fun anyFrame(): Image = image
}

class GifEmote(
    override val name: String,
    val frames: List<BufferedImage>,
    val delays: List<Int>,
) : Emote(name) {
    init {
        require(frames.isNotEmpty()) { "GifEmote requires at least one frame" }
        require(delays.size == frames.size) { "GifEmote delays must match the number of frames" }
    }

    val byteFrames = frames.map(::toMapBytes)

    override fun anyFrame(): Image = frames.first()
}

fun loadEmoteFromFile(
    name: String,
    file: File,
    animatedHint: Boolean = false,
    resizeStatic: (Image) -> BufferedImage = Util.ImageUtil::resizeForMap,
): Emote {
    if (animatedHint || Util.ImageUtil.isAnimated(file)) {
        return Util.GIFUtil.readGifFrames(name, file)
    }

    val image = ImageIO.read(file)
        ?: throw IllegalStateException("Could not read ${file.name} as an image")
    return StaticEmote(name, resizeStatic(image))
}
