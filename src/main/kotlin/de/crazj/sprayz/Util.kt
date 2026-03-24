package de.crazj.sprayz

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData
import de.crazj.sprayz.spray.GifEmote
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import me.tofaa.entitylib.meta.other.ItemFrameMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.map.MapPalette
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node
import java.awt.Color
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.net.URL
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream
import kotlin.random.Random

object Util {

    fun relativelySafeMapID(): Int {
        var mapId: Int
        do {
            mapId = Random.nextInt() + 100_000 // 99.9% safe like this
        } while (mapId == Int.MAX_VALUE || Bukkit.getMap(mapId) != null)

        return mapId
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

                    val emote = SprayZ.instance.emoteManager.getAllEmotes().entries.random()
                    it.item = SpigotConversionUtil.fromBukkitItemStack(
                        SprayZ.instance.emoteManager.mapItem(
                            emote.key
                        )
                    ).apply {
                        this.setComponent(ComponentTypes.MAP_ID, mapId)
                    }
                    var normalizedYaw = player.location.yaw % 360
                    if (normalizedYaw < 0) {
                        normalizedYaw += 360
                    }
                    if (facingDirection == BlockFace.UP || facingDirection == BlockFace.DOWN)
                        it.metadata.setIndex(
                            10.toByte(), EntityDataTypes.INT, when {
                                135 < normalizedYaw && normalizedYaw <= 225 -> 0 // North
                                225 < normalizedYaw && normalizedYaw <= 315 -> 1 //East/Clockwise
                                315 < normalizedYaw || normalizedYaw <= 45 -> 2 // South/Flipped
                                else   /* 45 < normalizedYaw && normalizedYaw <= 135 */ -> 3 // West/CounterClockwise
                            }
                        )
                }
//                addViewer(player.uniqueId)
                spawn(SpigotConversionUtil.fromBukkitLocation(loc))
            }

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
                    val frame = MapPalette.resizeImage(reader.read(i))
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

        fun readGifFrames(name: String, url: URL): GifEmote {
            url.openStream().use { inputStream ->
                ImageIO.createImageInputStream(inputStream).use { iis ->
                    return readGifFrames(name, iis)
                }
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


}
