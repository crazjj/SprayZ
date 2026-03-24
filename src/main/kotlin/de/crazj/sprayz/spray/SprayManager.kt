package de.crazj.sprayz.spray

import de.crazj.sprayz.ConfPath
import de.crazj.sprayz.Permission
import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.Util
import de.crazj.sprayz.Util.ImageUtil
import de.crazj.sprayz.Util.PacketUtil.sendMapData
import me.tofaa.entitylib.wrapper.WrapperEntity
import net.axay.kspigot.runnables.taskRunLater
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import java.util.*


class SprayManager {
    var sprays = ArrayList<WrapperEntity>()
    val cooldowns = ArrayList<UUID>()


    var listener = object : Listener {
        @EventHandler
        fun onInteract(e: PlayerInteractEvent) {
            if (e.action != Action.RIGHT_CLICK_BLOCK) return
            if (!e.player.isSneaking) return
            if (!e.player.hasPermission(Permission.SPRAYZ_USE.full)) return
            if (!e.clickedBlock!!.type.isSolid) return
            if (!e.clickedBlock!!.getRelative(e.blockFace).isEmpty) return
            if (cooldowns.contains(e.player.uniqueId)) return
            cooldowns.add(e.player.uniqueId)
            taskRunLater(SprayZ.instance.config.getLong(ConfPath.SPRAY_COOLDOWN.path) * 20, true) {
                cooldowns.remove(e.player.uniqueId)
            }
            spray(e)
        }
    }

    private fun spray(event: PlayerInteractEvent) {
        val emotes = SprayZ.instance.emoteManager.getAllEmotes()
        if (emotes.isEmpty()) return
        val entry = emotes.entries.random()

        val mapId = Util.relativelySafeMapID()
        val itemFrame = Util.PacketUtil.sendMapItemFrame(
            event.player,
            event.clickedBlock!!.getRelative(event.blockFace).location,
            entry.key,
            mapId,
            event.blockFace
        )
        sprays.add(itemFrame)

        val emote = entry.value
        if (emote is StaticEmote) {
            sendMapData(event.player, mapId, ImageUtil.toMapBytes(emote.image))
        } else if (emote is GifEmote) {
            playGifSpray(event.player, itemFrame, mapId, emote)
        }

        val disappear = SprayZ.instance.config.getLong(ConfPath.DISAPPEAR_AFTER.path)
        if (disappear > 0)
            taskRunLater(disappear * 20, true) {
                sprays.remove(itemFrame)
                itemFrame.despawn()
            }
    }

    private fun playGifSpray(player: Player, itemFrame: WrapperEntity, mapId: Int, emote: GifEmote) {
        if (emote.frames.isEmpty()) return
        if (!sprays.contains(itemFrame)) return

        var frameIndex = 0

        fun scheduleNextFrame() {
            if (!sprays.contains(itemFrame)) return

            val nextImage = emote.byteFrames[frameIndex]
            val delayMs = emote.delays.getOrNull(frameIndex) ?: 100
            val delayTicks = maxOf(1L, delayMs / 50L)
            sendMapData(
                player, mapId, nextImage
            )

            frameIndex = (frameIndex + 1) % emote.frames.size

            taskRunLater(delayTicks, true) {
                scheduleNextFrame()
            }
        }

        scheduleNextFrame()
    }

}


