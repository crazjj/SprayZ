package de.crazj.sprayz.spray

import de.crazj.sprayz.ConfPath
import de.crazj.sprayz.Permission
import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.Util
import de.tr7zw.changeme.nbtapi.NBT
import me.tofaa.entitylib.meta.other.ItemFrameMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import net.axay.kspigot.runnables.taskRunLater
import net.kyori.adventure.text.Component
import org.bukkit.ChatColor
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemFrame
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
        // Create a map with an image
        val emote = SprayZ.instance.emoteManager.getAllEmotes().entries.random()

        val itemFrame = Util.PacketUtil
            .sendMapItemFrame(
                event.player,
                event.clickedBlock!!.getRelative(event.blockFace).location,
                emote.key,
                emote.value,
                Util.relativelySafeMapID(),
                event.blockFace
            )

        sprays.add(itemFrame)

        taskRunLater(SprayZ.instance.config.getLong(ConfPath.DISAPPEAR_AFTER.path) * 20, true) {
            sprays.remove(itemFrame)
            itemFrame.remove()
        }
    }

}


