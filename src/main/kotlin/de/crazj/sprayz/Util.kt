package de.crazj.sprayz

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.color.AlphaColor
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.particle.Particle
import com.github.retrooper.packetevents.protocol.particle.data.ParticleColorData
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle
import de.crazj.sprayz.spray.GifEmote
import de.crazj.sprayz.spray.SprayManager
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import me.tofaa.entitylib.meta.other.ItemFrameMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.map.MapPalette
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node
import java.awt.Color
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream

object Util {

    fun mapItem(name: String): ItemStack {
        val map = ItemStack(Material.FILLED_MAP)
        val meta = (map.itemMeta as MapMeta)
        meta.itemName(Component.text(name).color(NamedTextColor.DARK_PURPLE))
        map.setItemMeta(meta)
        return map
    }

    object PacketUtil {
        fun sendMapData(viewer: Player, mapId: Int, pixels: ByteArray) {
            require(pixels.size == 128 * 128) {
                "Map pixel array must have size 16384, got ${pixels.size}"
            }

            val packet = WrapperPlayServerMapData(
                mapId, 0, false, true, emptyList(), 128, 128, 0, 0, pixels
            )

            PacketEvents.getAPI().playerManager.sendPacket(viewer, packet)
        }

        fun sendMapItemFrame(
            player: Player, loc: Location, name: String, mapId: Int, facingDirection: BlockFace
        ): WrapperEntity {
            return WrapperEntity(EntityTypes.ITEM_FRAME).apply {
                consumeEntityMeta(ItemFrameMeta::class.java) {
                    it.isInvisible = true
                    it.orientation = facingDirection.toPacketItemFrameOrientation()
                    it.isCustomNameVisible = true
                    it.customName = Component.text(name).color(NamedTextColor.DARK_PURPLE)

                    it.item = SpigotConversionUtil.fromBukkitItemStack(
                        mapItem(
                            name
                        )
                    ).apply {
                        this.setComponent(ComponentTypes.MAP_ID, mapId)
                    }
                    var normalizedYaw = player.location.yaw % 360
                    if (normalizedYaw < 0) {
                        normalizedYaw += 360
                    }
                    if (facingDirection == BlockFace.UP || facingDirection == BlockFace.DOWN) it.metadata.setIndex(
                        10.toByte(), EntityDataTypes.INT, when {
                            135 < normalizedYaw && normalizedYaw <= 225 -> 0 // North
                            225 < normalizedYaw && normalizedYaw <= 315 -> 1 //East/Clockwise
                            315 < normalizedYaw || normalizedYaw <= 45 -> 2 // South/Flipped
                            else   /* 45 < normalizedYaw && normalizedYaw <= 135 */ -> 3 // West/CounterClockwise
                        }
                    )
                }
                spawn(SpigotConversionUtil.fromBukkitLocation(loc))
            }
        }

        fun BlockFace.toPacketItemFrameOrientation(): ItemFrameMeta.Orientation = when (this) {
            BlockFace.UP -> ItemFrameMeta.Orientation.UP
            BlockFace.DOWN -> ItemFrameMeta.Orientation.DOWN
            BlockFace.NORTH -> ItemFrameMeta.Orientation.NORTH
            BlockFace.EAST -> ItemFrameMeta.Orientation.EAST
            BlockFace.SOUTH -> ItemFrameMeta.Orientation.SOUTH
            BlockFace.WEST -> ItemFrameMeta.Orientation.WEST
            else -> throw IllegalArgumentException("Facing direction must be one of the cardinal directions")
        }


        fun spawnGreenParticlesOnFaceGrid(
            sprayManager: SprayManager,
            world: World,
            block: Block,
            face: BlockFace,
            gridSize: Int = 4,
            speed: Float = 0.1f
        ) {
            val bx = block.x.toDouble()
            val by = block.y.toDouble()
            val bz = block.z.toDouble()

            val min = 0.08
            val max = 0.92
            val epsilon = 0.01

            fun lerp(t: Double): Double = min + (max - min) * t

            for (row in 0 until gridSize) {
                for (col in 0 until gridSize) {
                    val u = if (gridSize == 1) 0.5 else col.toDouble() / (gridSize - 1).toDouble()
                    val v = if (gridSize == 1) 0.5 else row.toDouble() / (gridSize - 1).toDouble()

                    val a = lerp(u)
                    val b = lerp(v)

                    val (x, y, z) = when (face) {
                        BlockFace.UP -> Triple(bx + a, by + 1.0 + epsilon, bz + b)
                        BlockFace.DOWN -> Triple(bx + a, by - epsilon, bz + b)

                        BlockFace.NORTH -> Triple(bx + a, by + b, bz - epsilon)
                        BlockFace.SOUTH -> Triple(bx + a, by + b, bz + 1.0 + epsilon)

                        BlockFace.WEST -> Triple(bx - epsilon, by + b, bz + a)
                        BlockFace.EAST -> Triple(bx + 1.0 + epsilon, by + b, bz + a)

                        else -> return
                    }

                    val particle = Particle(
                        ParticleTypes.ENTITY_EFFECT, ParticleColorData(AlphaColor(255, 0, 255, 0))
                    )

                    val packet = WrapperPlayServerParticle(
                        particle, true, Vector3d(x, y, z), Vector3f(0f, 0f, 0f), speed, 1
                    )

                    for (player in world.players) {
                        if (sprayManager.hideFrom.contains(player.uniqueId)) continue
                        PacketEvents.getAPI().playerManager.sendPacket(player, packet)
                    }
                }
            }
        }
    }

    object ImageUtil {

        fun resizeForMap(input: Image): BufferedImage {
            val output = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
            val g = output.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g.drawImage(input, 0, 0, 128, 128, null)
            g.dispose()
            return output
        }

        fun toMapBytes(source: BufferedImage): ByteArray {
            val resized = resizeForMap(source)
            val out = ByteArray(128 * 128)

            for (y in 0 until 128) {
                for (x in 0 until 128) {
                    val argb = resized.getRGB(x, y)

                    val alpha = (argb ushr 24) and 0xFF
                    val red = (argb ushr 16) and 0xFF
                    val green = (argb ushr 8) and 0xFF
                    val blue = argb and 0xFF

                    val index = y * 128 + x

                    out[index] = if (alpha < 32) {
                        0.toByte()
                    } else {
                        MapPalette.matchColor(Color(red, green, blue))
                    }
                }
            }

            return out
        }

        fun isAnimated(file: File): Boolean {
            ImageIO.createImageInputStream(file).use { iis ->
                val readers = ImageIO.getImageReaders(iis)
                if (!readers.hasNext()) return false

                val reader = readers.next()
                return try {
                    reader.input = iis
                    reader.getNumImages(true) > 1
                } catch (_: Exception) {
                    false
                } finally {
                    reader.dispose()
                }
            }
        }
    }

    object GIFUtil {

        private fun readGifFrames(name: String, iis: ImageInputStream): GifEmote {
            val frames = mutableListOf<BufferedImage>()
            val delays = mutableListOf<Int>()

            val readers = ImageIO.getImageReadersByFormatName("gif")
            if (!readers.hasNext()) {
                throw IOException("No GIF reader found")
            }

            val reader = readers.next()
            return try {
                reader.input = iis
                val count = reader.getNumImages(true)

                for (i in 0 until count) {
                    val frame = ImageUtil.resizeForMap(reader.read(i))
                    frames.add(frame)

                    val metadata = reader.getImageMetadata(i)
                    val root = metadata.getAsTree(metadata.nativeMetadataFormatName)
                    val delay = findGraphicControlExtensionDelay(root)
                    delays.add(delay)
                }

                GifEmote(name, frames, delays)
            } finally {
                reader.dispose()
            }
        }

        fun readGifFrames(name: String, file: File): GifEmote {
            ImageIO.createImageInputStream(file).use { iis ->
                return readGifFrames(name, iis)
            }
        }

        fun findGraphicControlExtensionDelay(node: Node): Int {
            if (node.nodeName == "GraphicControlExtension") {
                val attrs: NamedNodeMap = node.attributes
                val delayTime = attrs.getNamedItem("delayTime")?.nodeValue?.toIntOrNull() ?: 1
                return delayTime * 10 // GIFUtil delayTime ist in 1/100 Sekunden
            }

            val children = node.childNodes
            for (i in 0 until children.length) {
                val result = findGraphicControlExtensionDelay(children.item(i))
                if (result != -1) return result
            }

            return 100
        }
    }
    object SearchUtil {
        fun fuzzyCandidates(values: Collection<String>, query: String, limit: Int = Int.MAX_VALUE): List<String> {
            val normalizedQuery = query.trim()
            if (normalizedQuery.isBlank()) {
                return values.distinct().take(limit)
            }

            val fuzzyRegex = normalizedQuery
                .toCharArray()
                .joinToString(separator = ".*", prefix = ".*", postfix = ".*") { Regex.escape(it.toString()) }
                .toRegex(RegexOption.IGNORE_CASE)

            return values.asSequence()
                .distinct()
                .filter { candidate ->
                    candidate.contains(normalizedQuery, ignoreCase = true) || fuzzyRegex.matches(candidate)
                }
                .sortedWith(
                    compareBy<String>(
                        { !it.equals(normalizedQuery, ignoreCase = true) },
                        { !it.startsWith(normalizedQuery, ignoreCase = true) },
                        { !it.contains(normalizedQuery, ignoreCase = true) },
                        { it.length },
                        { it.lowercase(Locale.ROOT) },
                    )
                )
                .take(limit)
                .toList()
        }

        fun resolveFuzzyCandidate(values: Collection<String>, query: String): String? =
            fuzzyCandidates(values, query, limit = 1).firstOrNull()
    }

}
