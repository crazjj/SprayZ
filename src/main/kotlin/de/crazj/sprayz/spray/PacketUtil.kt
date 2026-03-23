package de.crazj.sprayz.spray

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData
import de.crazj.sprayz.SprayZ
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import me.tofaa.entitylib.meta.other.ItemFrameMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import org.bukkit.entity.Player
import org.bukkit.map.MapPalette
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage

object PacketUtil {

    internal fun sendMapData(viewer: Player, mapId: Int, pixels: ByteArray) {
        require(pixels.size == 128 * 128) {
            "Map pixel array must have size 16384, got ${pixels.size}"
        }

        val packet = WrapperPlayServerMapData(
            mapId,
            0,
            false,
            true,
            emptyList(),
            128,
            128,
            0,
            0,
            pixels
        )

        PacketEvents.getAPI().playerManager.sendPacket(viewer, packet)
    }

    fun sendMapItemFrame(player: Player, img: java.awt.Image, mapId: Int) {
        sendMapData(player, mapId, ImageUtil.toMapBytes(img))

        WrapperEntity(EntityTypes.ITEM_FRAME).apply {
            consumeEntityMeta(ItemFrameMeta::class.java) {
                it.isInvisible = true
                val emote = SprayZ.instance.emoteManager.getAllEmotes().entries.random()
                it.item = SpigotConversionUtil.fromBukkitItemStack(
                    SprayZ.instance.emoteManager.map(
                        emote.key
                    )
                ).apply {
                    this.setComponent(ComponentTypes.MAP_ID, mapId)
                }
                it.orientation = ItemFrameMeta.Orientation.UP
                var normalizedYaw = player.location.yaw % 360
                if (normalizedYaw < 0) {
                    normalizedYaw += 360
                }
                it.metadata.setIndex(
                    10.toByte(), EntityDataTypes.INT, when {
                        135 < normalizedYaw && normalizedYaw <= 225 -> 0 // North
                        225 < normalizedYaw && normalizedYaw <= 315 -> 1 //East/Clockwise
                        315 < normalizedYaw || normalizedYaw <= 45 -> 2 // South/Flipped
                        else   /* 45 < normalizedYaw && normalizedYaw <= 135 */ -> 3 // West/CounterClockwise
                    }
                )
            }
            addViewer(player.uniqueId)
            spawn(SpigotConversionUtil.fromBukkitLocation(player.location))
        }

    }


    object ImageUtil {

        fun resizeTo128(source: java.awt.Image): BufferedImage {
            val out = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
            val g: Graphics2D = out.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(source.getScaledInstance(128, 128, java.awt.Image.SCALE_SMOOTH), 0, 0, 128, 128, null)
            } finally {
                g.dispose()
            }
            return out
        }

        fun toMapBytes(source: java.awt.Image): ByteArray {
            val resized = resizeTo128(source)
            val out = ByteArray(128 * 128)

            for (y in 0 until 128) {
                for (x in 0 until 128) {
                    val argb = resized.getRGB(x, y)
                    val color = Color(argb, true)
                    val index = y * 128 + x

                    out[index] = if (color.alpha < 16) {
                        0.toByte()
                    } else {
                        MapPalette.matchColor(color) // TODO another way
                    }
                }
            }

            return out
        }
    }


}