package de.crazj.sprayz.map

import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.Util
import de.crazj.sprayz.spray.Emote
import de.crazj.sprayz.spray.GifEmote
import de.crazj.sprayz.spray.StaticEmote
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import org.bukkit.persistence.PersistentDataType
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

internal data class StoredMapAsset(
    val mapId: Int,
    val displayName: String,
    val animated: Boolean,
    val frames: List<BufferedImage>,
    val delays: List<Int>,
) {
    init {
        require(frames.isNotEmpty()) { "Stored map assets require at least one frame" }
    }

    val byteFrames: List<ByteArray> = frames.map(Util.ImageUtil::toMapBytes)

    fun withMapId(newMapId: Int): StoredMapAsset = copy(mapId = newMapId)

    companion object {
        fun fromEmote(mapId: Int, displayName: String, emote: Emote): StoredMapAsset = when (emote) {
            is GifEmote -> StoredMapAsset(mapId, displayName, true, emote.frames, emote.delays)
            is StaticEmote -> StoredMapAsset(
                mapId,
                displayName,
                false,
                listOf(emote.image),
                listOf(MapItemStore.DEFAULT_FRAME_DELAY_MS),
            )

            else -> error("Unsupported emote type: ${emote::class.simpleName}")
        }
    }
}

internal data class RestoredMap(
    val asset: StoredMapAsset,
    val mapView: MapView,
)

internal class MapItemStore {
    companion object {
        const val MAP_ITEM_VERSION = 1
        const val MAP_ITEM_FLAG = 1.toByte()
        const val FRAME_FILENAME_PATTERN = "frame-%03d.png"
        const val DEFAULT_FRAME_DELAY_MS = 100
    }

    private val assetRoot = File(SprayZ.instance.dataFolder, "map-items").apply { mkdirs() }
    private val assetCache = linkedMapOf<Int, StoredMapAsset>()
    private val loggedAssetFailures = linkedSetOf<Int>()

    private val mapItemKey = NamespacedKey(SprayZ.instance, "map-item")
    private val mapVersionKey = NamespacedKey(SprayZ.instance, "map-version")
    private val mapIdKey = NamespacedKey(SprayZ.instance, "map-id")
    private val mapAnimatedKey = NamespacedKey(SprayZ.instance, "map-animated")

    fun createMapItem(emoteName: String, emote: Emote, world: World): ItemStack {
        val mapView = createConfiguredMapView(world)
        val asset = StoredMapAsset.fromEmote(mapView.id, emoteName, emote)
        persistAsset(asset)
        restoreRenderer(mapView, asset)

        return Util.mapItem(emoteName).apply {
            editMeta(MapMeta::class.java) { meta ->
                writeMapMetadata(meta, mapView, asset.animated)
            }
        }
    }

    fun ensureRestored(item: ItemStack?, player: Player? = null): RestoredMap? {
        return restoreSprayZMap(item, player)
    }

    fun ensureRestored(frame: ItemFrame, player: Player? = null): RestoredMap? {
        return restoreFrameMap(frame, player)
    }

    fun loadAsset(mapId: Int): StoredMapAsset? {
        assetCache[mapId]?.let { return it }

        val directory = assetDirectory(mapId)
        val metaFile = File(directory, "meta.yml")
        if (!metaFile.isFile) {
            logAssetFailureOnce(mapId, "Map item asset metadata for map $mapId is missing.")
            return null
        }

        return runCatching {
            val meta = YamlConfiguration.loadConfiguration(metaFile)
            val frameCount = meta.getInt("frameCount")
            require(frameCount > 0) { "frameCount must be greater than 0" }

            val delaysFromConfig = meta.getIntegerList("delays")
            val frames = (0 until frameCount).map { index ->
                val frameFile = File(directory, FRAME_FILENAME_PATTERN.format(index))
                require(frameFile.isFile) { "Missing frame file ${frameFile.name}" }
                ImageIO.read(frameFile) ?: error("Could not read ${frameFile.name} as an image")
            }

            StoredMapAsset(
                mapId = mapId,
                displayName = meta.getString("displayName").orEmpty().ifBlank { "SprayZ Map" },
                animated = meta.getBoolean("animated"),
                frames = frames,
                delays = List(frameCount) { index -> delaysFromConfig.getOrElse(index) { DEFAULT_FRAME_DELAY_MS } },
            ).also { asset ->
                assetCache[mapId] = asset
                loggedAssetFailures.remove(mapId)
            }
        }.getOrElse { ex ->
            logAssetFailureOnce(mapId, "Map item asset for map $mapId could not be loaded: ${ex.message}")
            null
        }
    }

    fun resolveMapId(item: ItemStack?): Int? {
        val mapMeta = getSprayZMapMeta(item) ?: return null
        return mapMeta.persistentDataContainer.get(mapIdKey, PersistentDataType.INTEGER)
    }

    fun isMapHeldByPlayer(player: Player, mapId: Int): Boolean {
        return resolveMapId(player.inventory.itemInMainHand) == mapId ||
            resolveMapId(player.inventory.itemInOffHand) == mapId
    }

    fun collectCandidateItems(player: Player): List<ItemStack> {
        return buildList {
            addAll(player.inventory.contents.filterNotNull())
            addAll(player.inventory.armorContents.filterNotNull())
            addAll(player.inventory.extraContents.filterNotNull())
            add(player.inventory.itemInMainHand)
            add(player.inventory.itemInOffHand)
            add(player.itemOnCursor)
        }.filter { it.type == Material.FILLED_MAP }
            .distinctBy { System.identityHashCode(it) }
    }

    fun shutdown() {
        assetCache.clear()
        loggedAssetFailures.clear()
    }

    private fun restoreFrameMap(frame: ItemFrame, player: Player?): RestoredMap? {
        val originalItem = frame.item.takeIf { it.type == Material.FILLED_MAP } ?: return null
        val originalMapId = resolveMapId(originalItem)
        val originalViewId = (originalItem.itemMeta as? MapMeta)?.mapView?.id
        val workingCopy = originalItem.clone()
        val restored = restoreSprayZMap(workingCopy, player) ?: return null

        if (originalMapId != restored.asset.mapId || originalViewId != restored.mapView.id) {
            frame.setItem(workingCopy, false)
        }

        return restored
    }

    private fun restoreSprayZMap(item: ItemStack?, player: Player?): RestoredMap? {
        val workingItem = item ?: return null
        val mapMeta = getSprayZMapMeta(workingItem) ?: return null
        val storedMapId = mapMeta.persistentDataContainer.get(mapIdKey, PersistentDataType.INTEGER) ?: return null
        val storedAsset = loadAsset(storedMapId) ?: return null
        val (asset, mapView) = restoreView(workingItem, mapMeta, storedAsset, player) ?: return null

        restoreRenderer(mapView, asset)
        return RestoredMap(asset, mapView)
    }

    private fun restoreView(
        item: ItemStack,
        mapMeta: MapMeta,
        asset: StoredMapAsset,
        player: Player?,
    ): Pair<StoredMapAsset, MapView>? {
        val existingView = Bukkit.getMap(asset.mapId)
        if (existingView != null) {
            if (mapMeta.mapView?.id != existingView.id) {
                item.editMeta(MapMeta::class.java) { meta ->
                    writeMapMetadata(meta, existingView, asset.animated)
                }
            }
            return asset to existingView
        }

        val world = player?.world ?: Bukkit.getWorlds().firstOrNull()
        if (world == null) {
            SprayZ.instance.log("Could not restore SprayZ map ${asset.mapId} because no world is available.")
            return null
        }

        val replacementView = createConfiguredMapView(world)
        val replacementAsset = asset.withMapId(replacementView.id)

        return runCatching {
            persistAsset(replacementAsset)
            item.editMeta(MapMeta::class.java) { meta ->
                writeMapMetadata(meta, replacementView, replacementAsset.animated)
            }
            SprayZ.instance.log("Restored missing SprayZ map ${asset.mapId} as new map ${replacementView.id}.")
            replacementAsset to replacementView
        }.getOrElse { ex ->
            SprayZ.instance.log("Could not persist restored SprayZ map ${replacementView.id}: ${ex.message}")
            null
        }
    }

    private fun persistAsset(asset: StoredMapAsset) {
        val directory = assetDirectory(asset.mapId)
        if (directory.exists()) {
            directory.deleteRecursively()
        }
        directory.mkdirs()

        asset.frames.forEachIndexed { index, frame ->
            val frameFile = File(directory, FRAME_FILENAME_PATTERN.format(index))
            check(ImageIO.write(frame, "png", frameFile)) {
                "Could not write ${frameFile.name}"
            }
        }

        YamlConfiguration().apply {
            set("mapId", asset.mapId)
            set("displayName", asset.displayName)
            set("animated", asset.animated)
            set("frameCount", asset.frames.size)
            set("delays", asset.delays)
            set("createdAt", System.currentTimeMillis())
            save(File(directory, "meta.yml"))
        }

        assetCache[asset.mapId] = asset
        loggedAssetFailures.remove(asset.mapId)
    }

    private fun createConfiguredMapView(world: World): MapView {
        return Bukkit.createMap(world).apply {
            isTrackingPosition = false
            isUnlimitedTracking = false
            isLocked = true
            scale = MapView.Scale.FARTHEST
        }
    }

    private fun restoreRenderer(mapView: MapView, asset: StoredMapAsset) {
        if (mapView.renderers.any { it is SprayZMapRenderer }) return

        mapView.renderers.toList().forEach(mapView::removeRenderer)
        mapView.addRenderer(SprayZMapRenderer(asset.frames.first()))
    }

    private fun getSprayZMapMeta(item: ItemStack?): MapMeta? {
        if (item == null || item.type != Material.FILLED_MAP) return null
        val mapMeta = item.itemMeta as? MapMeta ?: return null
        if (!mapMeta.persistentDataContainer.has(mapItemKey, PersistentDataType.BYTE)) return null
        return mapMeta
    }

    private fun writeMapMetadata(meta: MapMeta, mapView: MapView, animated: Boolean) {
        meta.mapView = mapView
        meta.persistentDataContainer.set(mapItemKey, PersistentDataType.BYTE, MAP_ITEM_FLAG)
        meta.persistentDataContainer.set(mapVersionKey, PersistentDataType.INTEGER, MAP_ITEM_VERSION)
        meta.persistentDataContainer.set(mapIdKey, PersistentDataType.INTEGER, mapView.id)
        meta.persistentDataContainer.set(mapAnimatedKey, PersistentDataType.BYTE, if (animated) MAP_ITEM_FLAG else 0.toByte())
    }

    private fun assetDirectory(mapId: Int): File = File(assetRoot, mapId.toString())

    private fun logAssetFailureOnce(mapId: Int, message: String) {
        if (loggedAssetFailures.add(mapId)) {
            SprayZ.instance.log(message)
        }
    }
}

internal class SprayZMapRenderer(private val image: Image) : MapRenderer() {
    private var rendered = false

    override fun render(view: MapView, canvas: MapCanvas, player: Player) {
        if (rendered) return

        canvas.drawImage(0, 0, image)
        view.isTrackingPosition = false
        view.isUnlimitedTracking = false
        view.isLocked = true
        view.scale = MapView.Scale.FARTHEST
        rendered = true
    }
}
